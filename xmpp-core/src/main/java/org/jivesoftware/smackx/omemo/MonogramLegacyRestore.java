// SPDX-License-Identifier: GPL-3.0-or-later
package org.jivesoftware.smackx.omemo;

import com.negzero.monogram.xmpp.LegacyIncomingJournal;
import com.negzero.monogram.xmpp.LegacyOmemoRecords;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Date;
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;
import org.whispersystems.libsignal.IdentityKeyPair;
import static com.negzero.monogram.xmpp.LegacyOmemoRecords.*;

/** Offline fresh sending keys in a materialized, isolated stage; identity/device never change. */
public final class MonogramLegacyRestore {
    private static final int MARKER=0x4c523031;
    private MonogramLegacyRestore() { }
    static boolean marker(byte[] value) { return value!=null && value.length==20 && ByteBuffer.wrap(value).getInt()==MARKER; }
    /** Caller must remove every per-peer SESSION row in the private stage before invoking this. */
    public static void freshKeys(MonogramSignalStore store,OmemoDevice own,long now) throws Exception {
        if(now<0) throw new IllegalArgumentException("Invalid key time");
        try(LegacyOmemoRecords tx=store.begin(own)) {
            store.withTransaction(own,tx,() -> {
                byte[] purged=tx.load(PURGED,"",0);
                if(purged==null) throw new GeneralSecurityException("Materialized legacy stage required");
                IdentityKeyPair identity=store.loadOmemoIdentityKeyPair(own);
                if(identity==null) throw new GeneralSecurityException("Legacy identity is unavailable");
                int nextPre=1,nextSigned=1;
                for(int id:tx.ids(PRE_KEY,"")) { if(id<0 || id>=0xfffffe) throw new GeneralSecurityException("Legacy prekey identifiers exhausted");nextPre=Math.max(nextPre,id+1); }
                for(int id:tx.ids(SIGNED_PRE_KEY,"")) { if(id<0 || id>=0xfffffe) throw new GeneralSecurityException("Legacy signed key identifiers exhausted");nextSigned=Math.max(nextSigned,id+1); }
                if(nextPre>0xfffffe-100) throw new GeneralSecurityException("Legacy prekey identifiers exhausted");
                // Bootstrap is suppressed, so deleting local rows cannot resurrect old private keys.
                for(int kind:new int[]{PRE_KEY,SIGNED_PRE_KEY}) for(int id:tx.ids(kind,"")) tx.save(kind,"",id,null);
                store.storeOmemoSignedPreKey(own,nextSigned,store.generateOmemoSignedPreKey(identity,nextSigned));
                store.storeOmemoPreKeys(own,store.generateOmemoPreKeys(nextPre,100));
                store.setDateOfLastSignedPreKeyRenewal(own,new Date(now));
                byte[] epoch=new byte[16];new SecureRandom().nextBytes(epoch);
                tx.save(PURGED,"",0,ByteBuffer.allocate(20).putInt(MARKER).put(epoch).array());
                LegacyIncomingJournal.rotate(tx);return null;
            });tx.commit();
        }
    }
}
