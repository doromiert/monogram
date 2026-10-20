// SPDX-License-Identifier: GPL-3.0-or-later
package org.jivesoftware.smackx.omemo;

import com.negzero.monogram.xmpp.*;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Collections;
import java.util.TreeSet;
import org.jivesoftware.smackx.omemo.element.*;
import org.jivesoftware.smackx.omemo.internal.*;
import org.jivesoftware.smackx.omemo.signal.*;
import org.jivesoftware.smackx.omemo.trust.OmemoFingerprint;
import org.jxmpp.jid.impl.JidCreate;
import static com.negzero.monogram.xmpp.LegacyOmemoRecords.*;

/** Dedicated archive ratchet. Never installs a backend, creates a completion or publishes a bundle. */
public final class MonogramLegacyReceiveOnly {
    private final MonogramSignalStore active,archive;
    private final IdentityTrust trust;
    private final OmemoDevice own;
    private final LegacyIncomingJournal journal;
    private LegacyOmemoRecords.Retired retired;

    public MonogramLegacyReceiveOnly(MonogramSignalStore active,OmemoDevice own,
            SignalFileBasedOmemoStore emptyBootstrap,LegacyOmemoRecords.Factory archived,IdentityTrust trust) throws Exception {
        this(active,own,own,emptyBootstrap,archived,trust);
    }
    public MonogramLegacyReceiveOnly(MonogramSignalStore active,OmemoDevice activeOwn,OmemoDevice archivedOwn,
            SignalFileBasedOmemoStore emptyBootstrap,LegacyOmemoRecords.Factory archived,IdentityTrust trust) throws Exception {
        this(active,activeOwn,archivedOwn,null,emptyBootstrap,archived,trust);
    }
    public MonogramLegacyReceiveOnly(MonogramSignalStore active,OmemoDevice activeOwn,OmemoDevice archivedOwn,String fingerprint,
            SignalFileBasedOmemoStore emptyBootstrap,LegacyOmemoRecords.Factory archived,IdentityTrust trust) throws Exception {
        if(fingerprint!=null && !fingerprint.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid historical identity pin");
        if(!activeOwn.getJid().equals(archivedOwn.getJid())) throw new GeneralSecurityException("Historical account differs from active account");
        OmemoDevice own=archivedOwn;
        this.active=active;this.own=own;this.trust=java.util.Objects.requireNonNull(trust);
        try(LegacyOmemoRecords live=active.begin(activeOwn);LegacyOmemoRecords old=archived.begin(own.getJid().toString(),own.getDeviceId())) {
            byte[] marker=live.load(PURGED,"",0);
            if(!MonogramLegacyRestore.marker(marker) || Arrays.equals(marker,old.load(PURGED,"",0)) || old.load(PURGED,"",0)==null)
                throw new GeneralSecurityException("Independent materialized migration archive required");
            byte[] current=live.load(IDENTITY_PAIR,"",0),previous=old.load(IDENTITY_PAIR,"",0);
            try {
                if(current==null || previous==null || fingerprint==null && activeOwn.equals(own) && !java.security.MessageDigest.isEqual(current,previous))
                    throw new GeneralSecurityException("Legacy migration identity changed");
            } finally { if(current!=null) Arrays.fill(current,(byte)0);if(previous!=null) Arrays.fill(previous,(byte)0); }
        }
        archive=new MonogramSignalStore(emptyBootstrap,guard(archived,own));
        if(fingerprint!=null && !fingerprint.equals(archive.getFingerprint(own).toString())) throw new GeneralSecurityException("Historical identity pin changed");
        journal=new LegacyIncomingJournal(archive,own,trust);
    }
    /** Ordinary verified peer rotation: no migration marker or alternate active backend. */
    MonogramLegacyReceiveOnly(MonogramSignalStore active,MonogramSignalStore source,OmemoDevice own,IdentityTrust trust,LegacyOmemoRecords.Retired retired,
            LegacyOmemoRecords.Factory scoped) throws Exception {
        this.active=active;this.own=own;this.trust=java.util.Objects.requireNonNull(trust);this.retired=retired;
        archive=source.receiveView(scoped);
        OmemoDevice peer=new OmemoDevice(JidCreate.bareFrom(retired.peer),retired.device);
        if(!retired.fingerprint.equals(archive.getFingerprint(own,peer).toString())) throw new GeneralSecurityException("Retired peer pin changed");
        journal=new LegacyIncomingJournal(archive,own,trust);
    }
    boolean accepts(LegacyIncomingJournal.Context context,OmemoElement element) {
        return retired==null || retired.peer.equals(context.author) && retired.device==element.getHeader().getSid();
    }
    java.util.Collection<MonogramLegacyReceiveOnly> retiredBranches() throws Exception {
        return retired==null?MonogramLegacyRetired.load(active,archive,own,trust,java.util.Collections.emptyMap()).values():java.util.Collections.emptyList();
    }
    public LegacyIncomingJournal journal() { return journal; }
    boolean addressed(OmemoElement element) { for(OmemoKeyElement key:element.getHeader().getKeys()) if(key.getId()==own.getDeviceId()) return true;return false; }
    int deviceId() { return own.getDeviceId(); }
    void requireActive(MonogramSignalStore store,LegacyIncomingJournal journal) {
        if(store!=active || !own.getJid().equals(journal.own.getJid())) throw new IllegalArgumentException("Legacy archive belongs to another account/device");
    }
    LegacyIncomingJournal.Received cached(LegacyIncomingJournal.Context context) throws Exception {
        try(LegacyOmemoRecords tx=archive.begin(own)) {
            synchronized(trust) {
                return archive.withTransaction(own,tx,() -> journal.find(tx,context,journal.digest(context)));
            }
        }
    }
    LegacyIncomingJournal.Received receive(OmemoManager manager,LegacyIncomingJournal.Context context) throws Exception {
        if(!own.getJid().equals(manager.getOwnDevice().getJid())) throw new GeneralSecurityException("Recovery account changed");
        // Match pending-journal lock order: archive storage first, then current trust.
        try(LegacyOmemoRecords tx=archive.begin(own)) {
            synchronized(trust) {
                LegacyIncomingJournal.Received result=archive.withTransaction(own,tx,() -> {
                    byte[] digest=journal.digest(context);
                    LegacyIncomingJournal.Received existing=journal.find(tx,context,digest);if(existing!=null) return existing;
                    OmemoElement element=context.outer.getExtension("encrypted",OmemoElement_VAxolotl.NAMESPACE);
                    OmemoDevice sender=new OmemoDevice(JidCreate.bareFrom(context.author),element.getHeader().getSid());
                    OmemoKeyElement selected=null;
                    for(OmemoKeyElement key:element.getHeader().getKeys()) if(key.getId()==own.getDeviceId()) {
                        if(selected!=null) throw new GeneralSecurityException("Ambiguous historical recipient keys");selected=key;
                    }
                    if(selected==null) throw new org.whispersystems.libsignal.NoSessionException("Not addressed to historical device");
                    MonogramLegacyArchiveCipher.checkCandidate(archive,own,sender,selected,trust);
                    MonogramLegacyArchiveCipher.Authenticated authenticated=MonogramLegacyArchiveCipher.decryptAuthenticated(archive,own,sender,selected,trust);
                    byte[] transported=authenticated.material;
                    CipherAndAuthTag material;
                    if(transported.length==32) material=new CipherAndAuthTag(Arrays.copyOf(transported,16),element.getHeader().getIv(),Arrays.copyOfRange(transported,16,32),selected.isPreKey());
                    else if(transported.length==16 && element.isKeyTransportElement()) material=new CipherAndAuthTag(transported.clone(),element.getHeader().getIv(),null,selected.isPreKey());
                    else { Arrays.fill(transported,(byte)0);throw new GeneralSecurityException("Invalid historical transported key length"); }
                    Arrays.fill(transported,(byte)0);
                    try {
                        OmemoFingerprint fingerprint=authenticated.fingerprint;
                        if(!trust.isTrusted(sender,fingerprint)) throw new GeneralSecurityException("Recovery sender is no longer trusted");
                        String body=element.isMessageElement()?OmemoRatchet.decryptMessageElement(element,material):null;
                        OmemoMessage.Received decrypted=new OmemoMessage.Received(element,material.getKey(),material.getIv(),body,fingerprint,sender,material.wasPreKeyEncrypted());
                        return journal.append(tx,context,digest,decrypted,null,false);
                    } finally { Arrays.fill(material.getKey(),(byte)0); }
                });tx.commit();return result;
            }
        }
    }
    static LegacyOmemoRecords.Factory guard(LegacyOmemoRecords.Factory source,OmemoDevice own) {
        return new LegacyOmemoRecords.Factory() {
            public java.util.SortedSet<Integer> localDevices(String owner) {
                return new TreeSet<>(owner.equals(own.getJid().toString())?Collections.singleton(own.getDeviceId()):Collections.emptySet());
            }
            public LegacyOmemoRecords begin(String owner,int device) throws Exception {
                if(!owner.equals(own.getJid().toString()) || device!=own.getDeviceId()) throw new GeneralSecurityException("Recovery owner/device mismatch");
                LegacyOmemoRecords tx=source.begin(owner,device);
                return new LegacyOmemoRecords() {
                    public byte[] load(int kind,String peer,int id) throws Exception { return tx.load(kind,peer,id); }
                    public int[] ids(int kind,String peer) throws Exception { return tx.ids(kind,peer); }
                    public void save(int kind,String peer,int id,byte[] value) throws Exception {
                        // Typed PRE_KEY removal writes the one-byte tombstone, not SQL NULL.
                        if(kind!=SESSION && kind!=IDENTITY && kind!=INCOMING && kind!=MESSAGE_COUNTER && kind!=LAST_RECEIVED
                                && !(kind==PRE_KEY && value!=null && value.length==1 && value[0]==0))
                            throw new IOException("Receive-only archive cannot change sending keys or publication state");
                        tx.save(kind,peer,id,value);
                    }
                    public java.util.List<Retired> retired() throws Exception { return tx.retired(); }
                    public byte[] loadRetired(Retired lineage,int kind,int id) throws Exception { return tx.loadRetired(lineage,kind,id); }
                    public int[] retiredIds(Retired lineage,int kind) throws Exception { return tx.retiredIds(lineage,kind); }
                    public void saveRetired(Retired lineage,int kind,int id,byte[] value) throws Exception {
                        if(kind!=SESSION && kind!=INCOMING) throw new IOException("Receive-only archive cannot change retired identities");
                        tx.saveRetired(lineage,kind,id,value);
                    }
                    public void purge() throws Exception { throw new IOException("Receive-only archive cannot purge keys"); }
                    public void commit() throws Exception { tx.commit(); }
                    public void close() throws Exception { tx.close(); }
                };
            }
        };
    }
}
