// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.io.*;
import java.util.*;
import org.jxmpp.jid.impl.JidCreate;

/** Private cold-start binding. Archive files remain mutable receive state; their identity pins are stable. */
public final class RecoveryManifest {
    private static final int MAGIC=0x4d524d31,VERSION=1,MAX_ARCHIVES=4096;
    public enum Protocol { LEGACY,MODERN }
    public static final class Archive {
        public final String id,fingerprint;
        public final Protocol protocol;
        public final int device;
        public Archive(String id,Protocol protocol,int device,String fingerprint) {
            uuid(id);positive(device);hex(fingerprint,64);this.id=id;this.protocol=Objects.requireNonNull(protocol);this.device=device;this.fingerprint=fingerprint;
        }
        public String filename() { return id+".db"; }
    }
    public final String owner,generation,legacyFingerprint,modernFingerprint,legacyMarker,modernMarker;
    public final int legacyDevice,modernDevice;
    public final boolean reconnectApproved;
    /** Newest snapshots first; older mutable receive archives are never overwritten or deduplicated by device ID. */
    public final List<Archive> archives;
    public RecoveryManifest(String owner,String generation,int legacyDevice,int modernDevice,String legacyFingerprint,String modernFingerprint,
            String legacyMarker,String modernMarker,boolean reconnectApproved,List<Archive> archives) throws Exception {
        if(owner==null || owner.length()>4096 || !owner.equals(JidCreate.entityBareFrom(owner).toString())) throw new IOException("Invalid recovery account binding");
        uuid(generation);positive(legacyDevice);positive(modernDevice);hex(legacyFingerprint,64);hex(modernFingerprint,64);hex(legacyMarker,40);hex(modernMarker,40);
        if(!legacyMarker.startsWith("4c523031") || !modernMarker.startsWith("4d523031")) throw new IOException("Invalid migration epoch format");
        if(archives==null || archives.size()>MAX_ARCHIVES) throw new IOException("Recovery archive manifest exceeds capacity");
        Set<String> ids=new HashSet<>();for(Archive archive:archives) if(archive==null || !ids.add(archive.id)) throw new IOException("Duplicate recovery archive identifier");
        this.owner=owner;this.generation=generation;this.legacyDevice=legacyDevice;this.modernDevice=modernDevice;
        this.legacyFingerprint=legacyFingerprint;this.modernFingerprint=modernFingerprint;this.legacyMarker=legacyMarker;this.modernMarker=modernMarker;
        this.reconnectApproved=reconnectApproved;this.archives=Collections.unmodifiableList(new ArrayList<>(archives));
    }
    /** Carry every earlier archive forward verbatim; archive ID identifies state, device ID does not. */
    public RecoveryManifest next(String generation,String legacyMarker,String modernMarker,List<Archive> newlyRetired,boolean reconnectApproved) throws Exception {
        List<Archive> all=new ArrayList<>(newlyRetired);all.addAll(archives);
        return new RecoveryManifest(owner,generation,legacyDevice,modernDevice,legacyFingerprint,modernFingerprint,legacyMarker,modernMarker,reconnectApproved,all);
    }
    public void write(OutputStream target) throws IOException {
        DataOutputStream out=new DataOutputStream(target);out.writeInt(MAGIC);out.writeInt(VERSION);out.writeUTF(owner);out.writeUTF(generation);
        out.writeInt(legacyDevice);out.writeInt(modernDevice);out.writeUTF(legacyFingerprint);out.writeUTF(modernFingerprint);out.writeUTF(legacyMarker);out.writeUTF(modernMarker);out.writeBoolean(reconnectApproved);
        out.writeInt(archives.size());for(Archive archive:archives) { out.writeUTF(archive.id);out.writeByte(archive.protocol.ordinal());out.writeInt(archive.device);out.writeUTF(archive.fingerprint); }out.flush();
    }
    public static RecoveryManifest read(InputStream source) throws Exception {
        DataInputStream in=new DataInputStream(source);if(in.readInt()!=MAGIC || in.readInt()!=VERSION) throw new IOException("Unsupported recovery manifest version");
        String owner=in.readUTF(),generation=in.readUTF();int legacy=in.readInt(),modern=in.readInt();
        String legacyFp=in.readUTF(),modernFp=in.readUTF(),legacyMarker=in.readUTF(),modernMarker=in.readUTF();int approval=in.readUnsignedByte();
        if(approval>1) throw new IOException("Invalid reconnect approval state");boolean approved=approval==1;
        int count=in.readInt();if(count<0 || count>MAX_ARCHIVES) throw new IOException("Invalid recovery archive count");
        List<Archive> archives=new ArrayList<>();for(int n=0;n<count;n++) {
            String id=in.readUTF();int protocol=in.readUnsignedByte();if(protocol>=Protocol.values().length) throw new IOException("Invalid recovery archive protocol");
            archives.add(new Archive(id,Protocol.values()[protocol],in.readInt(),in.readUTF()));
        }
        if(in.read()!=-1) throw new IOException("Trailing recovery manifest content");
        return new RecoveryManifest(owner,generation,legacy,modern,legacyFp,modernFp,legacyMarker,modernMarker,approved,archives);
    }
    private static void uuid(String value) { if(value==null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException("Invalid recovery identifier"); }
    private static void positive(int value) { if(value<=0) throw new IllegalArgumentException("Invalid recovery device"); }
    private static void hex(String value,int length) { if(value==null || value.length()!=length || !value.matches("[0-9a-f]+")) throw new IllegalArgumentException("Invalid recovery identity binding"); }
}
