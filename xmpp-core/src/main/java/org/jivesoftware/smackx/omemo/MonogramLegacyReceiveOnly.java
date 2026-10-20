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

    public MonogramLegacyReceiveOnly(MonogramSignalStore active,OmemoDevice own,
            SignalFileBasedOmemoStore emptyBootstrap,LegacyOmemoRecords.Factory archived,IdentityTrust trust) throws Exception {
        this.active=active;this.own=own;this.trust=java.util.Objects.requireNonNull(trust);
        try(LegacyOmemoRecords live=active.begin(own);LegacyOmemoRecords old=archived.begin(own.getJid().toString(),own.getDeviceId())) {
            byte[] marker=live.load(PURGED,"",0);
            if(!MonogramLegacyRestore.marker(marker) || Arrays.equals(marker,old.load(PURGED,"",0)) || old.load(PURGED,"",0)==null)
                throw new GeneralSecurityException("Independent materialized migration archive required");
            byte[] current=live.load(IDENTITY_PAIR,"",0),previous=old.load(IDENTITY_PAIR,"",0);
            try {
                if(current==null || previous==null || !java.security.MessageDigest.isEqual(current,previous))
                    throw new GeneralSecurityException("Legacy migration identity changed");
            } finally { if(current!=null) Arrays.fill(current,(byte)0);if(previous!=null) Arrays.fill(previous,(byte)0); }
        }
        archive=new MonogramSignalStore(emptyBootstrap,guard(archived,own));
        journal=new LegacyIncomingJournal(archive,own,trust);
    }
    public LegacyIncomingJournal journal() { return journal; }
    void requireActive(MonogramSignalStore store,LegacyIncomingJournal journal) {
        if(store!=active || !own.equals(journal.own)) throw new IllegalArgumentException("Legacy archive belongs to another account/device");
    }
    LegacyIncomingJournal.Received cached(LegacyIncomingJournal.Context context) throws Exception {
        try(LegacyOmemoRecords tx=archive.begin(own)) {
            synchronized(trust) {
                return archive.withTransaction(own,tx,() -> journal.find(tx,context,journal.digest(context)));
            }
        }
    }
    LegacyIncomingJournal.Received receive(OmemoManager manager,LegacyIncomingJournal.Context context) throws Exception {
        if(!own.equals(manager.getOwnDevice())) throw new GeneralSecurityException("Recovery account changed");
        // Match pending-journal lock order: archive storage first, then current trust.
        try(LegacyOmemoRecords tx=archive.begin(own)) {
            synchronized(trust) {
                LegacyIncomingJournal.Received result=archive.withTransaction(own,tx,() -> {
                    byte[] digest=journal.digest(context);
                    LegacyIncomingJournal.Received existing=journal.find(tx,context,digest);if(existing!=null) return existing;
                    OmemoElement element=context.outer.getExtension("encrypted",OmemoElement_VAxolotl.NAMESPACE);
                    OmemoDevice sender=new OmemoDevice(JidCreate.bareFrom(context.author),element.getHeader().getSid());
                    // Type as base class: these package-private helpers belong to the installed
                    // Smack package, not the Signal implementation's different subpackage.
                    OmemoRatchet<org.whispersystems.libsignal.IdentityKeyPair,org.whispersystems.libsignal.IdentityKey,
                            org.whispersystems.libsignal.state.PreKeyRecord,org.whispersystems.libsignal.state.SignedPreKeyRecord,
                            org.whispersystems.libsignal.state.SessionRecord,org.whispersystems.libsignal.SignalProtocolAddress,
                            org.whispersystems.libsignal.ecc.ECPublicKey,org.whispersystems.libsignal.state.PreKeyBundle,
                            org.whispersystems.libsignal.SessionCipher> ratchet=new MonogramSignalRatchet(manager,archive);
                    CipherAndAuthTag material=ratchet.retrieveMessageKeyAndAuthTag(sender,element);
                    if(material==null) throw new GeneralSecurityException("Duplicate recovery message");
                    try {
                        OmemoFingerprint fingerprint=archive.getFingerprint(own,sender);
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
                    public void purge() throws Exception { throw new IOException("Receive-only archive cannot purge keys"); }
                    public void commit() throws Exception { tx.commit(); }
                    public void close() throws Exception { tx.close(); }
                };
            }
        };
    }
}
