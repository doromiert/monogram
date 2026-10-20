// SPDX-License-Identifier: GPL-3.0-or-later
package org.jivesoftware.smackx.omemo;

import static com.negzero.monogram.xmpp.LegacyOmemoRecords.*;
import com.negzero.monogram.xmpp.LegacyOmemoRecords;
import java.io.*;
import java.util.*;
import org.jivesoftware.smackx.omemo.internal.OmemoCachedDeviceList;
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;
import org.jivesoftware.smackx.omemo.signal.SignalFileBasedOmemoStore;
import org.jivesoftware.smackx.omemo.signal.SignalOmemoKeyUtil;
import org.jxmpp.jid.BareJid;
import org.jxmpp.jid.impl.JidCreate;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.state.*;

/** Materializes one account's read-only bootstrap into an already isolated OVERLAY SNAPSHOT, never the live factory. */
public final class MonogramLegacyBackup {
    public interface Peers { Set<BareJid> forDevice(String owner,int device) throws Exception; }
    private MonogramLegacyBackup() { }

    /** liveRoot is the configured Signal root; privateCopy must be a separate empty app-private staging directory. */
    public static void materialize(File liveRoot,File privateCopy,BareJid owner,LegacyOmemoRecords.Factory snapshot,Peers overlayPeers) throws Exception {
        if(owner==null || !owner.isEntityBareJid()) throw new IllegalArgumentException("Use a bare account for legacy backup.");
        File sourceRoot=liveRoot.getCanonicalFile(),copyRoot=privateCopy.getCanonicalFile();
        if(copyRoot.equals(sourceRoot) || copyRoot.getPath().startsWith(sourceRoot.getPath()+File.separator)) throw new IOException("Bootstrap staging must be separate from the live store.");
        if(copyRoot.exists() && (!copyRoot.isDirectory() || copyRoot.list()==null || copyRoot.list().length!=0)) throw new IOException("Bootstrap staging must be empty.");
        if(!copyRoot.isDirectory() && !copyRoot.mkdirs()) throw new IOException("Could not create private bootstrap staging.");
        // Monogram uses Smack 4.4.8's default UrlSafeEncoder; legacy raw-JID encoding is never enabled.
        File source=new File(new File(sourceRoot,"OMEMO_Store"),owner.asUrlEncodedString());
        File copied=new File(new File(copyRoot,"OMEMO_Store"),owner.asUrlEncodedString());
        if(source.exists()) copyTree(sourceRoot,source,copied,new long[]{0});
        SignalFileBasedOmemoStore bootstrap=new SignalFileBasedOmemoStore(copyRoot);
        MonogramSignalStore merged=new MonogramSignalStore(bootstrap,snapshot);
        SortedSet<Integer> devices=new TreeSet<>(snapshot.localDevices(owner.toString()));
        for(File entry:children(copied)) {
            if(!entry.isDirectory()) throw new IOException("Unsupported bootstrap owner entry.");devices.add(deviceId(entry.getName()));
        }
        for(int device:devices) {
            if(device<=0) throw new IOException("Invalid legacy own-device identifier.");
            OmemoDevice own=new OmemoDevice(owner,device);File ownDirectory=new File(copied,Integer.toString(device));
            boolean purged;
            try(LegacyOmemoRecords state=snapshot.begin(owner.toString(),device)) { purged=state.load(PURGED,"",0)!=null; }
            if(!purged) validateDeviceDirectory(ownDirectory);
            Set<BareJid> peers=new HashSet<>(overlayPeers.forDevice(owner.toString(),device));peers.add(owner);
            File contacts=new File(ownDirectory,"contacts");
            for(File contact:purged ? new File[0] : children(contacts)) {
                if(!contact.isDirectory()) throw new IOException("Unsupported bootstrap contact entry.");
                BareJid peer=JidCreate.bareFromUrlEncoded(contact.getName());
                if(!peer.asUrlEncodedString().equals(contact.getName())) throw new IOException("Noncanonical bootstrap contact path.");
                peers.add(peer);
            }
            try(LegacyOmemoRecords transaction=merged.begin(own)) {
                merged.withTransaction(own,transaction,() -> {
                    IdentityKeyPair pair=merged.loadOmemoIdentityKeyPair(own);merged.storeOmemoIdentityKeyPair(own,pair);
                    for(Map.Entry<Integer,PreKeyRecord> key:merged.loadOmemoPreKeys(own).entrySet()) merged.storeOmemoPreKey(own,key.getKey(),key.getValue());
                    for(Map.Entry<Integer,SignedPreKeyRecord> key:merged.loadOmemoSignedPreKeys(own).entrySet()) merged.storeOmemoSignedPreKey(own,key.getKey(),key.getValue());
                    merged.setDateOfLastSignedPreKeyRenewal(own,merged.getDateOfLastSignedPreKeyRenewal(own));
                    for(BareJid peer:peers) {
                        OmemoCachedDeviceList list=merged.loadCachedDeviceList(own,peer);merged.storeCachedDeviceList(own,peer,list);
                        Set<Integer> ids=new TreeSet<>(list.getActiveDevices());ids.addAll(list.getInactiveDevices());
                        for(int kind:new int[]{IDENTITY,SESSION,MESSAGE_COUNTER,LAST_RECEIVED,LAST_PUBLISHED}) for(int id:transaction.ids(kind,peer.toString())) ids.add(id);
                        if(!purged) for(File entry:children(new File(contacts,peer.asUrlEncodedString()))) if(entry.isDirectory()) ids.add(deviceId(entry.getName()));
                        ids.addAll(merged.loadAllRawSessionsOf(own,peer).keySet());
                        for(int id:ids) {
                            if(id<=0) throw new IOException("Invalid legacy recipient-device identifier.");
                            OmemoDevice contact=new OmemoDevice(peer,id);
                            IdentityKey identity=merged.loadOmemoIdentityKey(own,contact);merged.storeOmemoIdentityKey(own,contact,identity);
                            SessionRecord session=merged.loadRawSession(own,contact);merged.storeRawSession(own,contact,session);
                            merged.storeOmemoMessageCounter(own,contact,merged.loadOmemoMessageCounter(own,contact));
                            merged.setDateOfLastReceivedMessage(own,contact,merged.getDateOfLastReceivedMessage(own,contact));
                            merged.setDateOfLastDeviceIdPublication(own,contact,merged.getDateOfLastDeviceIdPublication(own,contact));
                        }
                    }
                    // All merged values now live in the isolated snapshot. Never consult destination-phone bootstrap files.
                    if(transaction.load(PURGED,"",0)==null) transaction.save(PURGED,"",0,new byte[]{1});return null;
                });
                transaction.commit();
            }
        }
    }
    private static void validateDeviceDirectory(File device) throws Exception {
        SignalOmemoKeyUtil keys=new SignalOmemoKeyUtil();
        for(File entry:children(device)) {
            switch(entry.getName()) {
                case "identityKeyPair": keys.identityKeyPairFromBytes(read(entry));break;
                case "lastSignedPreKeyRenewal": number(entry);break;
                case "preKeys":
                    for(File key:children(entry)) { keyId(key.getName());keys.preKeyFromBytes(read(key)); }break;
                case "signedPreKeys":
                    for(File key:children(entry)) { keyId(key.getName());keys.signedPreKeyFromBytes(read(key)); }break;
                case "contacts":
                    for(File contact:children(entry)) for(File part:children(contact)) {
                        if(part.isDirectory()) {
                            deviceId(part.getName());
                            for(File value:children(part)) switch(value.getName()) {
                                case "identityKey": keys.identityKeyFromBytes(read(value));break;
                                case "session": keys.rawSessionFromBytes(read(value));break;
                                case "messageCounter":case "lastMessageReceivedDate":case "lastDeviceIdPublicationDate":number(value);break;
                                default:throw new IOException("Unsupported bootstrap device field.");
                            }
                        } else if(part.getName().equals("activeDevices") || part.getName().equals("inactiveDevices")) {
                            if(read(part).length%4!=0) throw new IOException("Invalid bootstrap device list.");
                        } else throw new IOException("Unsupported bootstrap contact field.");
                    }
                    break;
                default:throw new IOException("Unsupported bootstrap own-device field.");
            }
        }
    }
    private static int deviceId(String value) throws IOException {
        try { int id=Integer.parseInt(value);if(id<=0 || !Integer.toString(id).equals(value)) throw new NumberFormatException();return id; }
        catch(NumberFormatException invalid) { throw new IOException("Invalid bootstrap device/key identifier."); }
    }
    private static int keyId(String value) throws IOException {
        if(value.equals("0")) return 0;return deviceId(value);
    }
    private static void number(File file) throws IOException { if(read(file).length!=8) throw new IOException("Invalid bootstrap numeric record."); }
    private static byte[] read(File file) throws IOException {
        if(!file.isFile() || file.length()>MAX_RECORD_BYTES-1) throw new IOException("Unsupported bootstrap record size/type.");
        try(InputStream input=new FileInputStream(file);ByteArrayOutputStream result=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1) {
                if(result.size()+count>MAX_RECORD_BYTES-1) throw new IOException("Bootstrap record exceeds capacity.");result.write(buffer,0,count);
            }
            return result.toByteArray();
        }
    }
    private static File[] children(File directory) throws IOException {
        if(!directory.exists()) return new File[0];
        File[] files=directory.listFiles();if(files==null) throw new IOException("Could not enumerate bootstrap staging.");return files;
    }
    private static void copyTree(File root,File source,File target,long[] total) throws IOException {
        if(!source.getAbsoluteFile().equals(source.getCanonicalFile()) || !source.getCanonicalPath().startsWith(root.getPath()+File.separator)) throw new IOException("Bootstrap contains a linked or escaped path.");
        if(source.isDirectory()) {
            if(!target.isDirectory() && !target.mkdirs()) throw new IOException("Could not copy private bootstrap directory.");
            for(File file:children(source)) copyTree(root,file,new File(target,file.getName()),total);
        } else {
            byte[] bytes=read(source);if((total[0]+=bytes.length)>512L*1024*1024) throw new IOException("Legacy bootstrap exceeds the 512 MiB safety limit.");
            try(FileOutputStream output=new FileOutputStream(target)) { output.write(bytes);output.getFD().sync(); }
        }
    }
}
