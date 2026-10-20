// SPDX-License-Identifier: GPL-3.0-or-later
package org.jivesoftware.smackx.omemo;

import com.negzero.monogram.xmpp.*;
import java.io.IOException;
import java.util.*;
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;
import static com.negzero.monogram.xmpp.LegacyOmemoRecords.*;

/** Peer-scoped retired ratchets share the active database transaction, never its outgoing namespace. */
final class MonogramLegacyRetired {
    private MonogramLegacyRetired() { }
    static Map<Retired,MonogramLegacyReceiveOnly> load(MonogramSignalStore active,OmemoDevice own,IdentityTrust trust,
            Map<Retired,MonogramLegacyReceiveOnly> previous) throws Exception {
        return load(active,active,own,trust,previous);
    }
    static Map<Retired,MonogramLegacyReceiveOnly> load(MonogramSignalStore active,MonogramSignalStore source,OmemoDevice own,IdentityTrust trust,
            Map<Retired,MonogramLegacyReceiveOnly> previous) throws Exception {
        List<Retired> entries;
        try(LegacyOmemoRecords tx=source.begin(own)) { entries=new ArrayList<>(tx.retired()); }
        Map<Retired,MonogramLegacyReceiveOnly> result=new LinkedHashMap<>();
        for(Retired entry:entries) {
            // Preserve journal-only branches too: an evicted session must not hide an already authenticated body.
            try(LegacyOmemoRecords tx=source.begin(own)) {
                if(tx.loadRetired(entry,SESSION,0)==null && java.util.Arrays.stream(tx.retiredIds(entry,INCOMING)).noneMatch(id -> id>0)) continue;
            }
            MonogramLegacyReceiveOnly retained=previous.get(entry);
            result.put(entry,retained!=null?retained:new MonogramLegacyReceiveOnly(active,source,own,trust,entry,scope(source,own,entry)));
        }
        return Collections.unmodifiableMap(result);
    }
    static LegacyOmemoRecords.Factory scope(MonogramSignalStore source,OmemoDevice own,Retired entry) {
        return new LegacyOmemoRecords.Factory() {
            public SortedSet<Integer> localDevices(String owner) { return new TreeSet<>(owner.equals(own.getJid().toString())?Collections.singleton(own.getDeviceId()):Collections.emptySet()); }
            public LegacyOmemoRecords begin(String owner,int device) throws Exception {
                if(!owner.equals(own.getJid().toString()) || device!=own.getDeviceId()) throw new IOException("Retired receive owner changed");
                LegacyOmemoRecords tx=source.begin(own);
                return new LegacyOmemoRecords() {
                    boolean peer(String value,int id) { return entry.peer.equals(value) && entry.device==id; }
                    public byte[] load(int kind,String peer,int id) throws Exception {
                        if(kind==PURGED && peer.isEmpty() && id==0) return new byte[]{1}; // Never fall through to active/bootstrap keys.
                        if((kind==IDENTITY_PAIR || kind==INCOMING) && peer.isEmpty()) return tx.loadRetired(entry,kind,id);
                        if((kind==IDENTITY || kind==SESSION) && peer(peer,id)) return tx.loadRetired(entry,kind,0);
                        return null;
                    }
                    public int[] ids(int kind,String peer) throws Exception {
                        if(kind==INCOMING && peer.isEmpty()) return tx.retiredIds(entry,kind);
                        if((kind==IDENTITY || kind==SESSION) && entry.peer.equals(peer) && tx.loadRetired(entry,kind,0)!=null) return new int[]{entry.device};
                        return new int[0];
                    }
                    public void save(int kind,String peer,int id,byte[] value) throws Exception {
                        if(kind==SESSION && peer(peer,id)) tx.saveRetired(entry,kind,0,value);
                        else if(kind==INCOMING && peer.isEmpty()) tx.saveRetired(entry,kind,id,value);
                        else throw new IOException("Retired receive cannot mutate identity, keys or outgoing state");
                    }
                    public void purge() throws Exception { throw new IOException("Retired receive cannot purge state"); }
                    public void commit() throws Exception { tx.commit(); }
                    public void close() throws Exception { tx.close(); }
                };
            }
        };
    }
}
