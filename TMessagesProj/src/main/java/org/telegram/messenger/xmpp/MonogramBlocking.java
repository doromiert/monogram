// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import org.json.JSONArray;
import org.jxmpp.jid.Jid;
import org.jxmpp.jid.impl.JidCreate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

/** The server owns blocking; this account-scoped snapshot also supports offline settings. */
final class MonogramBlocking {
    private final SharedPreferences preferences;
    private final MonogramStore store;
    private final String owner;
    private final long self;
    MonogramBlocking(SharedPreferences preferences, MonogramStore store, String owner, long self) {
        this.preferences=preferences; this.store=store; this.owner=owner; this.self=self;
    }
    static String key(String owner) { return "blocked-jids-"+owner; }
    static boolean handles(TLObject request) {
        return request instanceof TLRPC.TL_contacts_block || request instanceof TLRPC.TL_contacts_unblock
                || request instanceof TLRPC.TL_contacts_getBlocked;
    }
    List<String> cached() {
        String raw=preferences.getString(key(owner),null);
        if(raw==null || raw.length()>2*1024*1024) return null;
        try {
            JSONArray array=new JSONArray(raw); if(array.length()>10000) return null;
            List<String> result=new ArrayList<>();
            for(int i=0;i<array.length();i++) result.add(array.getString(i));
            return normalized(result);
        } catch(Exception invalid) { return null; }
    }
    void remember(List<String> snapshot) throws Exception {
        JSONArray array=new JSONArray(normalized(snapshot));
        if(array.toString().length()>2*1024*1024) throw new IllegalArgumentException("Block list is too large to cache");
        preferences.edit().putString(key(owner),array.toString()).apply();
    }
    private static List<String> normalized(List<String> addresses) throws Exception {
        if(addresses==null || addresses.size()>10000) throw new IllegalArgumentException("Invalid block list");
        TreeSet<String> result=new TreeSet<>();
        for(String address:addresses) {
            if(address==null || address.isEmpty() || address.length()>3071) throw new IllegalArgumentException("Invalid blocked address");
            result.add(JidCreate.from(address).toString());
        }
        return Collections.unmodifiableList(new ArrayList<>(result));
    }
    boolean blocked(String address) {
        return blocked(cached(),address);
    }
    static boolean blocked(List<String> list,String address) {
        if(list==null) return false;
        try {
            Jid jid=JidCreate.from(address);
            return list.contains(jid.toString()) || list.contains(jid.asBareJid().toString())
                    || list.contains(jid.asDomainBareJid().toString());
        } catch(Exception invalid) { return false; }
    }
    String target(TLRPC.InputPeer input) {
        if(input==null || !(input instanceof TLRPC.TL_inputPeerUser) || input.user_id<=0 || input.user_id==self)
            throw new IllegalArgumentException("Choose another XMPP contact to block");
        return store.jid(input.user_id);
    }
    TLRPC.TL_contacts_blockedSlice page(List<String> snapshot,int offset,int limit) {
        if(offset<0 || limit<=0 || limit>1000) throw new IllegalArgumentException("Invalid block list page");
        TLRPC.TL_contacts_blockedSlice result=new TLRPC.TL_contacts_blockedSlice();result.count=snapshot.size();
        for(int i=Math.min(offset,snapshot.size()),end=i+Math.min(limit,snapshot.size()-i);i<end;i++) {
            long peer=store.peer(snapshot.get(i));TLRPC.TL_peerBlocked blocked=new TLRPC.TL_peerBlocked();
            blocked.peer_id=new TLRPC.TL_peerUser();blocked.peer_id.user_id=peer;blocked.date=0;
            result.blocked.add(blocked);result.users.add(store.user(peer,self));
        }
        return result;
    }
}
