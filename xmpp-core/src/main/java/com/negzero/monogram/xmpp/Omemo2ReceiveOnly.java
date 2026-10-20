// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import static com.negzero.monogram.xmpp.Omemo2Records.*;

/** Explicit migration archive. Its old ratchets can decrypt, but never encrypt or publish. */
public final class Omemo2ReceiveOnly {
    private final Omemo2Device active;
    final Omemo2Device device;
    final Omemo2IncomingJournal journal;

    /** Factories must refer to physically independent databases, not aliases of the active store. */
    public Omemo2ReceiveOnly(Omemo2Device active,Omemo2Records.Factory archived) throws Exception {
        this(active,active.id,archived);
    }
    /** A historical own device stays receive-only and is never registered or published again. */
    public Omemo2ReceiveOnly(Omemo2Device active,int archivedDevice,Omemo2Records.Factory archived) throws Exception {
        this(active,archivedDevice,null,archived);
    }
    /** Manifest-pinned historical identity, including an earlier identity that reused the same own-device ID. */
    public Omemo2ReceiveOnly(Omemo2Device active,int archivedDevice,String fingerprint,Omemo2Records.Factory archived) throws Exception {
        if(fingerprint!=null && !fingerprint.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid historical identity pin");
        if(archivedDevice<=0) throw new IllegalArgumentException("Invalid historical own device");
        if(archived==null || archived==active.records) throw new IllegalArgumentException("Independent recovery store required");
        this.active=active;
        try(Omemo2Records live=active.records.begin();Omemo2Records old=archived.begin()) {
            byte[] marker=live.load(META,"",1);
            if(!Omemo2Restore.marker(marker) || Arrays.equals(marker,old.load(META,"",1))) throw new GeneralSecurityException("Active device was not prepared for migration");
            for(int id=0;id<=1;id++) {
                byte[] a=live.load(IDENTITY,"",id),b=old.load(IDENTITY,"",id);
                try {
                    if(a==null || b==null || fingerprint==null && archivedDevice==active.id && !MessageDigest.isEqual(a,b) || id==1 && (b.length!=32 || fingerprint!=null && !fingerprint.equals(XmppSession.hex(b)))) throw new GeneralSecurityException("Recovery identity does not match active device");
                } finally { if(a!=null) Arrays.fill(a,(byte)0);if(b!=null) Arrays.fill(b,(byte)0); }
            }
        }
        device=new Omemo2Device(active.jid,archivedDevice,guard(active.records,archived));
        journal=new Omemo2IncomingJournal(device,true);
    }
    void requireActive(Omemo2Device candidate) {
        if(candidate!=active) throw new IllegalArgumentException("Recovery belongs to another active device");
    }
    static boolean recoverable(int code) { return code==-1003 || code==-1004 || code==-1005 || code==-1008; }

    /** Lock order is always active then archive. Current trust is pinned through receive commit. */
    static Omemo2Records.Factory guard(Omemo2Records.Factory active,Omemo2Records.Factory archive) {
        return () -> {
            Omemo2Records live=active.begin();
            final Omemo2Records old;
            try { old=archive.begin(); }
            catch(Exception failure) { live.close();throw failure; }
            return new Omemo2Records() {
                public byte[] load(int kind,String peer,int id) throws Exception {
                    return kind==TRUSTED ? live.load(kind,peer,id) : old.load(kind,peer,id);
                }
                public int[] ids(int kind) throws Exception { return old.ids(kind); }
                public void save(int kind,String peer,int id,byte[] value) throws Exception {
                    if(kind!=SESSION && kind!=SESSION_IDENTITY && kind!=INCOMING && !(kind==PRE_KEY && value==null))
                        throw new GeneralSecurityException("Receive-only recovery cannot change sending keys or trust");
                    old.save(kind,peer,id,value);
                }
                public void commit() throws Exception { old.commit(); }
                public void close() throws Exception { try { old.close(); } finally { live.close(); } }
            };
        };
    }
}
