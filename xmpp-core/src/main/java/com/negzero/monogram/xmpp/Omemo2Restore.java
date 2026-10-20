// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import static com.negzero.monogram.xmpp.Omemo2Records.*;

/** Offline operation on an isolated restore stage only. Never allocate or replace an identity. */
public final class Omemo2Restore {
    private static final int MARKER=0x4d523031;
    private Omemo2Restore() { }
    static boolean marker(byte[] value) { return value!=null && value.length==20 && ByteBuffer.wrap(value).getInt()==MARKER; }

    /**
     * Caller must remove ALL per-peer SESSION/SESSION_IDENTITY rows in a separate private stage first.
     * This records interface intentionally cannot enumerate peer sessions. Never call on a live store.
     * The unmodified archive must remain separately available for receive-only recovery.
     */
    public static void freshKeys(Omemo2Device device,long now) throws Exception {
        if(now<0) throw new IllegalArgumentException("Invalid key time");
        try(Omemo2Records tx=device.records.begin()) {
            byte[] identity=tx.load(IDENTITY,"",0),pub=tx.load(IDENTITY,"",1),meta=tx.load(META,"",0);
            try {
                if(identity==null || pub==null || pub.length!=32 || meta==null || meta.length!=24)
                    throw new GeneralSecurityException("Incomplete migration identity");
                ByteBuffer input=ByteBuffer.wrap(meta);
                if(input.getInt()!=1) throw new GeneralSecurityException("Unknown migration key metadata");
                long nextPre=input.getLong(),nextSigned=input.getLong();int previousSigned=input.getInt();
                if(nextPre<=0 || nextPre>Integer.MAX_VALUE-101L || nextSigned<=0 || nextSigned>Integer.MAX_VALUE || previousSigned<=0)
                    throw new GeneralSecurityException("Migration key identifiers exhausted or invalid");
                for(int kind:new int[]{PRE_KEY,PRE_PUBLIC,SIGNED_PRE_KEY,SIGNED_PUBLIC})
                    for(int id:tx.ids(kind)) {
                        long next=(kind==PRE_KEY || kind==PRE_PUBLIC)?nextPre:nextSigned;
                        if(id<=0 || id>=next) throw new GeneralSecurityException("Migration key counters do not follow existing keys");
                        tx.save(kind,"",id,null);
                    }
                int signedId=(int)nextSigned++;
                byte[][] signed=Omemo2Native.signedPreKey(identity,signedId,now);
                try {
                    tx.save(SIGNED_PRE_KEY,"",signedId,signed[0]);
                    tx.save(SIGNED_PUBLIC,"",signedId,ByteBuffer.allocate(104).put(signed[1]).put(signed[2]).putLong(now).array());
                } finally { Arrays.fill(signed[0],(byte)0); }
                for(int n=0;n<100;n++) {
                    if(nextPre==0xffffff) nextPre++;
                    int id=(int)nextPre++;byte[][] key=Omemo2Native.preKey(id);
                    try { tx.save(PRE_KEY,"",id,key[0]);tx.save(PRE_PUBLIC,"",id,key[1]); }
                    finally { Arrays.fill(key[0],(byte)0); }
                }
                tx.save(META,"",0,ByteBuffer.allocate(24).putInt(1).putLong(nextPre).putLong(nextSigned).putInt(signedId).array());
                byte[] epoch=new byte[16];new SecureRandom().nextBytes(epoch);
                tx.save(META,"",1,ByteBuffer.allocate(20).putInt(MARKER).put(epoch).array());
                Omemo2IncomingJournal.rotate(tx);tx.commit();
            } finally { if(identity!=null) Arrays.fill(identity,(byte)0); }
        }
    }
}
