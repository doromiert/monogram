// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.negzero.monogram.xmpp.*;
import java.io.*;
import java.util.*;
import org.jivesoftware.smackx.omemo.*;
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;
import org.jivesoftware.smackx.omemo.signal.SignalFileBasedOmemoStore;
import org.jxmpp.jid.impl.JidCreate;
import org.telegram.messenger.ApplicationLoader;

/** Validates an already cold-installed manifest before any connection. Never installs or activates a restore. */
final class MonogramRecoveryStore implements XmppRecovery {
    private static final Map<Integer,MonogramRecoveryStore> OPEN=new HashMap<>();
    private final RecoveryManifest manifest;
    private final byte[] encoded;
    private final File root;
    private final Map<String,MonogramOmemo2Store> modern=new HashMap<>();
    private final Map<String,MonogramLegacyOmemoStore> legacy=new HashMap<>();
    private MonogramRecoveryStore(File root,RecoveryManifest manifest,byte[] encoded) { this.root=root;this.manifest=manifest;this.encoded=encoded; }

    /** Absent directory is the unchanged normal-account path. Any partial/mismatched installation blocks connect. */
    static synchronized XmppRecovery load(int slot,String owner,int legacyDevice,MonogramOmemo2Store modernActive,MonogramLegacyOmemoStore legacyActive) throws Exception {
        File root=new File(ApplicationLoader.applicationContext.getNoBackupFilesDir().getCanonicalFile(),"monogram-recovery-"+slot);
        if(!root.exists()) {
            if(OPEN.containsKey(slot)) throw new IOException("Recovery installation changed; restart before reconnecting.");
            return null;
        }
        if(!root.isDirectory() || !root.getCanonicalFile().equals(root.getAbsoluteFile())) throw new IOException("Invalid recovery directory.");
        File file=new File(root,"manifest.bin");byte[] bytes=readManifest(file);
        RecoveryManifest manifest=RecoveryManifest.read(new ByteArrayInputStream(bytes));
        if(!manifest.owner.equals(owner) || legacyDevice<=0 || manifest.legacyDevice!=legacyDevice)
            throw new IOException("Recovery account/device binding does not match this slot.");
        if(!manifest.reconnectApproved) throw new IOException("Restored account is disconnected until migration/reconnect is explicitly confirmed.");
        MonogramRecoveryStore cached=OPEN.get(slot);
        if(cached!=null) {
            if(!Arrays.equals(bytes,cached.encoded)) throw new IOException("Recovery manifest changed; restart before reconnecting.");
            cached.validateActive(modernActive,legacyActive);return cached;
        }
        MonogramRecoveryStore loaded=new MonogramRecoveryStore(root,manifest,bytes);boolean success=false;
        try {
            loaded.validateActive(modernActive,legacyActive);
            File archives=new File(root,"archives");
            if(!archives.isDirectory() || !archives.getCanonicalFile().equals(archives.getAbsoluteFile())) throw new IOException("Recovery archive directory is missing.");
            Set<String> expected=new HashSet<>();
            for(RecoveryManifest.Archive entry:manifest.archives) {
                File archive=new File(archives,entry.filename());expected.add(entry.filename());
                if(!archive.isFile() || !archive.getCanonicalFile().equals(archive.getAbsoluteFile())) throw new IOException("Recovery archive is missing or redirected.");
                for(String suffix:new String[]{"-wal","-shm","-journal"}) if(new File(archive.getPath()+suffix).exists()) throw new IOException("Recovery archive has an unfinished database transaction.");
                loaded.validateArchive(archive,entry);
            }
            File[] contents=archives.listFiles();if(contents==null) throw new IOException("Could not inspect recovery archives.");
            for(File child:contents) if(!expected.contains(child.getName())) throw new IOException("Recovery archive is not listed in its account manifest.");
            success=true;OPEN.put(slot,loaded);return loaded;
        } finally { if(!success) loaded.closeFailed(); }
    }
    private static byte[] readManifest(File file) throws Exception {
        if(!file.isFile() || file.length()>2*1024*1024 || !file.getCanonicalFile().equals(file.getAbsoluteFile())) throw new IOException("Recovery manifest is missing or invalid.");
        try(InputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[4096];int read;while((read=in.read(buffer))!=-1) { if(out.size()+read>2*1024*1024) throw new IOException("Recovery manifest exceeds capacity.");out.write(buffer,0,read); }return out.toByteArray();
        }
    }
    private void validateActive(MonogramOmemo2Store modernActive,MonogramLegacyOmemoStore legacyActive) throws Exception {
        try(Cursor own=modernActive.getReadableDatabase().rawQuery("SELECT device FROM own_devices WHERE owner=?",new String[]{manifest.owner})) {
            if(!own.moveToFirst() || own.getInt(0)!=manifest.modernDevice || own.moveToNext()) throw new IOException("Restored modern device changed or is missing.");
        }
        try(Omemo2Records tx=modernActive.begin(manifest.owner,manifest.modernDevice)) {
            requireHex(tx.load(Omemo2Records.IDENTITY,"",1),manifest.modernFingerprint,"Modern identity");
            requireHex(tx.load(Omemo2Records.META,"",1),manifest.modernMarker,"Modern migration epoch");
        }
        OmemoDevice own=new OmemoDevice(JidCreate.entityBareFrom(manifest.owner),manifest.legacyDevice);
        try(LegacyOmemoRecords tx=legacyActive.begin(manifest.owner,manifest.legacyDevice)) { requireHex(tx.load(LegacyOmemoRecords.PURGED,"",0),manifest.legacyMarker,"Legacy migration epoch"); }
        if(!manifest.legacyFingerprint.equals(MonogramLegacyOmemoService.instance().records().getFingerprint(own).toString())) throw new IOException("Restored legacy identity changed or is missing.");
    }
    private void validateArchive(File file,RecoveryManifest.Archive entry) throws Exception {
        // Compare sqlite_master to trusted in-memory app schema BEFORE any archive table SELECT.
        try(SQLiteDatabase source=SQLiteDatabase.openDatabase(file.getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY);SQLiteDatabase trusted=SQLiteDatabase.create(null)) {
            if(entry.protocol==RecoveryManifest.Protocol.MODERN) {
                MonogramOmemo2Store helper=new MonogramOmemo2Store(ApplicationLoader.applicationContext,null);try { helper.onCreate(trusted);trusted.setVersion(2); } finally { helper.close(); }
            } else {
                MonogramLegacyOmemoStore helper=new MonogramLegacyOmemoStore(ApplicationLoader.applicationContext,null);try { helper.onCreate(trusted);trusted.setVersion(MonogramLegacyOmemoStore.VERSION); } finally { helper.close(); }
            }
            Map<String,String> expected=schema(trusted),actual=schema(source),oldLegacy=new TreeMap<>(expected);oldLegacy.remove("table:retired_records");
            boolean legacyV1=entry.protocol==RecoveryManifest.Protocol.LEGACY && source.getVersion()==1 && actual.equals(oldLegacy);
            if(!legacyV1 && (source.getVersion()!=trusted.getVersion() || !actual.equals(expected))) throw new IOException("Unsupported recovery archive schema.");
            try(Cursor check=source.rawQuery("PRAGMA quick_check",null)) { if(!check.moveToFirst() || !"ok".equals(check.getString(0)) || check.moveToNext()) throw new IOException("Recovery archive failed its integrity check."); }
            for(String table:actual.containsKey("table:retired_records")?new String[]{"records","retired_records"}:new String[]{"records"})
                try(Cursor owners=source.rawQuery("SELECT 1 FROM "+table+" WHERE owner IS NULL OR owner<>? LIMIT 1",new String[]{manifest.owner})) { if(owners.moveToFirst()) throw new IOException("Recovery archive contains another account."); }
        }
        if(entry.protocol==RecoveryManifest.Protocol.MODERN) {
            MonogramOmemo2Store store=new MonogramOmemo2Store(ApplicationLoader.applicationContext,file.getAbsolutePath());modern.put(entry.id,store);
            try(Omemo2Records tx=store.begin(manifest.owner,entry.device)) {
                requireHex(tx.load(Omemo2Records.IDENTITY,"",1),entry.fingerprint,"Historical modern identity");
                if(tx.load(Omemo2Records.IDENTITY,"",0)==null) throw new IOException("Historical modern private identity is missing.");
            }
        } else {
            MonogramLegacyOmemoStore store=new MonogramLegacyOmemoStore(ApplicationLoader.applicationContext,file.getAbsolutePath());legacy.put(entry.id,store);
            try(LegacyOmemoRecords tx=store.begin(manifest.owner,entry.device)) { if(tx.load(LegacyOmemoRecords.PURGED,"",0)==null) throw new IOException("Historical legacy bootstrap was not materialized."); }
            MonogramSignalStore typed=new MonogramSignalStore(emptyBootstrap(),store);
            if(!entry.fingerprint.equals(typed.getFingerprint(new OmemoDevice(JidCreate.entityBareFrom(manifest.owner),entry.device)).toString())) throw new IOException("Historical legacy identity changed or is missing.");
        }
    }
    private SignalFileBasedOmemoStore emptyBootstrap() throws Exception {
        File empty=new File(root,"empty-bootstrap");
        if(!empty.isDirectory() || !empty.getCanonicalFile().equals(empty.getAbsoluteFile())) throw new IOException("Isolated recovery bootstrap is missing.");
        requireNoFiles(empty);return new SignalFileBasedOmemoStore(empty);
    }
    private static void requireNoFiles(File root) throws IOException {
        File[] children=root.listFiles();if(children==null) throw new IOException("Could not inspect isolated bootstrap.");
        for(File child:children) { if(!child.isDirectory()) throw new IOException("Recovery bootstrap must have no key files.");if(!child.getCanonicalFile().equals(child.getAbsoluteFile())) throw new IOException("Redirected recovery bootstrap.");requireNoFiles(child); }
    }
    private static Map<String,String> schema(SQLiteDatabase database) {
        Map<String,String> schema=new TreeMap<>();try(Cursor rows=database.rawQuery("SELECT type,name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' AND sql IS NOT NULL",null)) { while(rows.moveToNext()) schema.put(rows.getString(0)+":"+rows.getString(1),rows.getString(2)); }return schema;
    }
    static void requireHex(byte[] value,String expected,String field) throws IOException { if(value==null || !hex(value).equals(expected)) throw new IOException(field+" does not match its recovery manifest."); }
    static String hex(byte[] value) { StringBuilder out=new StringBuilder();for(byte b:value) out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString(); }
    @Override public List<Omemo2ReceiveOnly> modern(Omemo2Device active) throws Exception {
        if(!manifest.owner.equals(active.jid) || manifest.modernDevice!=active.id) throw new IOException("Recovery modern account/device changed.");
        List<Omemo2ReceiveOnly> result=new ArrayList<>();for(RecoveryManifest.Archive entry:manifest.archives) if(entry.protocol==RecoveryManifest.Protocol.MODERN) {
            MonogramOmemo2Store store=modern.get(entry.id);result.add(new Omemo2ReceiveOnly(active,entry.device,entry.fingerprint,() -> store.begin(manifest.owner,entry.device)));
        }return result;
    }
    @Override public List<MonogramLegacyReceiveOnly> legacy(MonogramSignalStore active,OmemoDevice own,IdentityTrust trust) throws Exception {
        if(!manifest.owner.equals(own.getJid().toString()) || manifest.legacyDevice!=own.getDeviceId()) throw new IOException("Recovery legacy account/device changed.");
        List<MonogramLegacyReceiveOnly> result=new ArrayList<>();for(RecoveryManifest.Archive entry:manifest.archives) if(entry.protocol==RecoveryManifest.Protocol.LEGACY)
            result.add(new MonogramLegacyReceiveOnly(active,own,new OmemoDevice(own.getJid(),entry.device),entry.fingerprint,emptyBootstrap(),legacy.get(entry.id),trust));
        return result;
    }
    private void closeFailed() { for(MonogramOmemo2Store store:modern.values()) store.close();for(MonogramLegacyOmemoStore store:legacy.values()) store.close(); }
}
