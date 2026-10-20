// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.database.sqlite.SQLiteDatabase;
import com.negzero.monogram.xmpp.*;
import java.io.*;
import java.util.*;
import org.jivesoftware.smackx.omemo.*;
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;
import org.jivesoftware.smackx.omemo.signal.SignalFileBasedOmemoStore;
import org.jxmpp.jid.impl.JidCreate;
import org.telegram.messenger.ApplicationLoader;

/** Repeated migrations preserve every previous mutable receive archive in a new private stage. */
final class MonogramRecoveryStage {
    static final class Result {
        final File activeModern,activeLegacy,recoveryDirectory;
        final RecoveryManifest manifest;
        Result(File activeModern,File activeLegacy,File recoveryDirectory,RecoveryManifest manifest) {
            this.activeModern=activeModern;this.activeLegacy=activeLegacy;this.recoveryDirectory=recoveryDirectory;this.manifest=manifest;
        }
    }
    private MonogramRecoveryStage() { }
    /** Inputs are quiesced/authenticated private snapshots, never live databases. Does not install them. */
    static Result prepare(File modernSnapshot,File materializedLegacySnapshot,File previousRecovery,File work,
            String owner,int legacyDevice,boolean reconnectApproved) throws Exception {
        String privateRoot=ApplicationLoader.applicationContext.getNoBackupFilesDir().getCanonicalPath()+File.separator;
        work=work.getCanonicalFile();
        if(work.exists() || !work.getCanonicalPath().startsWith(privateRoot) || !work.mkdir()) throw new IOException("A new private recovery stage directory is required.");
        boolean success=false;
        try {
            MonogramBackupModernStore.Stage modern=MonogramBackupModernStore.prepare(modernSnapshot,new File(work,"modern"),owner);
            MonogramBackupLegacyRestore.Stage legacy=MonogramBackupLegacyRestore.prepare(materializedLegacySnapshot,new File(work,"legacy"),owner,legacyDevice);
            File recovery=new File(work,"recovery"),archives=new File(recovery,"archives"),empty=new File(recovery,"empty-bootstrap");
            if(!recovery.mkdir() || !archives.mkdir() || !empty.mkdir()) throw new IOException("Could not create recovery archive stage.");
            String modernFp,legacyFp,modernMarker,legacyMarker;
            try(MonogramOmemo2Store active=new MonogramOmemo2Store(ApplicationLoader.applicationContext,modern.active.getAbsolutePath());
                MonogramLegacyOmemoStore old=new MonogramLegacyOmemoStore(ApplicationLoader.applicationContext,legacy.active.getAbsolutePath())) {
                try(Omemo2Records tx=active.begin(owner,modern.device)) { modernFp=hex(tx.load(Omemo2Records.IDENTITY,"",1));modernMarker=hex(tx.load(Omemo2Records.META,"",1)); }
                MonogramSignalStore typed=new MonogramSignalStore(new SignalFileBasedOmemoStore(empty),old);
                legacyFp=typed.getFingerprint(new OmemoDevice(JidCreate.entityBareFrom(owner),legacyDevice)).toString();
                try(LegacyOmemoRecords tx=old.begin(owner,legacyDevice)) { legacyMarker=hex(tx.load(LegacyOmemoRecords.PURGED,"",0)); }
            }
            List<RecoveryManifest.Archive> entries=new ArrayList<>();
            entries.add(copy(modern.recovery,archives,owner,modern.device,RecoveryManifest.Protocol.MODERN,modernFp,UUID.randomUUID().toString()));
            // Each historical own device gets its own archive ID even when it shared the source
            // overlay file. It can evolve its receive state independently, without active managers.
            try(MonogramLegacyOmemoStore source=new MonogramLegacyOmemoStore(ApplicationLoader.applicationContext,legacy.recovery.getAbsolutePath())) {
                MonogramSignalStore typed=new MonogramSignalStore(new SignalFileBasedOmemoStore(empty),source);
                for(int device:typed.localDeviceIdsOf(JidCreate.entityBareFrom(owner))) {
                    String fingerprint=typed.getFingerprint(new OmemoDevice(JidCreate.entityBareFrom(owner),device)).toString();
                    entries.add(copy(legacy.recovery,archives,owner,device,RecoveryManifest.Protocol.LEGACY,fingerprint,UUID.randomUUID().toString()));
                }
            }
            if(previousRecovery!=null) {
                if(!previousRecovery.getCanonicalPath().startsWith(privateRoot)) throw new IOException("Previous receive archives must be privately staged.");
                previousRecovery=previousRecovery.getCanonicalFile();
                if(new File(previousRecovery,"manifest.bin").length()>2*1024*1024) throw new IOException("Previous recovery manifest exceeds capacity.");
                RecoveryManifest previous;
                try(InputStream in=new FileInputStream(new File(previousRecovery,"manifest.bin"))) { previous=RecoveryManifest.read(in); }
                if(!owner.equals(previous.owner) || legacyDevice!=previous.legacyDevice || modern.device!=previous.modernDevice
                        || !modernFp.equals(previous.modernFingerprint) || !legacyFp.equals(previous.legacyFingerprint)) throw new IOException("Previous recovery manifest belongs to another device identity.");
                // Prove that the prior archive chain and this active snapshot were captured together.
                try(MonogramOmemo2Store source=new MonogramOmemo2Store(ApplicationLoader.applicationContext,modern.recovery.getAbsolutePath());
                    MonogramLegacyOmemoStore old=new MonogramLegacyOmemoStore(ApplicationLoader.applicationContext,legacy.recovery.getAbsolutePath())) {
                    try(Omemo2Records tx=source.begin(owner,modern.device)) { MonogramRecoveryStore.requireHex(tx.load(Omemo2Records.META,"",1),previous.modernMarker,"Previous modern migration epoch"); }
                    try(LegacyOmemoRecords tx=old.begin(owner,legacyDevice)) { MonogramRecoveryStore.requireHex(tx.load(LegacyOmemoRecords.PURGED,"",0),previous.legacyMarker,"Previous legacy migration epoch"); }
                }
                for(RecoveryManifest.Archive entry:previous.archives) {
                    File source=new File(new File(previousRecovery,"archives"),entry.filename());
                    if(!source.isFile() || !source.getCanonicalFile().equals(source.getAbsoluteFile())) throw new IOException("Previous receive archive is missing or redirected.");
                    entries.add(copy(source,archives,owner,entry.device,entry.protocol,entry.fingerprint,entry.id));
                }
            }
            RecoveryManifest manifest=new RecoveryManifest(owner,UUID.randomUUID().toString(),legacyDevice,modern.device,legacyFp,modernFp,legacyMarker,modernMarker,reconnectApproved,entries);
            File manifestFile=new File(recovery,"manifest.bin");
            try(FileOutputStream out=new FileOutputStream(manifestFile)) { manifest.write(out);out.getFD().sync(); }
            success=true;return new Result(modern.active,legacy.active,recovery,manifest);
        } finally { if(!success) erase(work); }
    }
    private static RecoveryManifest.Archive copy(File source,File archives,String owner,int device,RecoveryManifest.Protocol protocol,String fingerprint,String id) throws Exception {
        RecoveryManifest.Archive entry=new RecoveryManifest.Archive(id,protocol,device,fingerprint);File target=new File(archives,entry.filename());
        if(target.exists()) throw new IOException("Duplicate receive archive ID; existing receive state was preserved.");
        try(SQLiteDatabase database=SQLiteDatabase.openDatabase(source.getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY)) {
            if(protocol==RecoveryManifest.Protocol.MODERN) {
                try(MonogramOmemo2Store helper=new MonogramOmemo2Store(ApplicationLoader.applicationContext,target.getAbsolutePath())) {
                    MonogramBackupDatabase.restoreIntoEmpty(database,helper.getWritableDatabase(),MonogramBackupDatabase.Scope.MODERN,owner,null);
                    try(Omemo2Records tx=helper.begin(owner,device)) { MonogramRecoveryStore.requireHex(tx.load(Omemo2Records.IDENTITY,"",1),fingerprint,"Copied modern identity"); }
                }
            } else {
                try(MonogramLegacyOmemoStore helper=new MonogramLegacyOmemoStore(ApplicationLoader.applicationContext,target.getAbsolutePath())) {
                    MonogramBackupDatabase.restoreIntoEmpty(database,helper.getWritableDatabase(),MonogramBackupDatabase.Scope.LEGACY,owner,null);
                    try(LegacyOmemoRecords tx=helper.begin(owner,device)) { if(tx.load(LegacyOmemoRecords.PURGED,"",0)==null) throw new IOException("Copied legacy archive is not materialized."); }
                    MonogramSignalStore typed=new MonogramSignalStore(new SignalFileBasedOmemoStore(new File(archives.getParentFile(),"empty-bootstrap")),helper);
                    if(!fingerprint.equals(typed.getFingerprint(new OmemoDevice(JidCreate.entityBareFrom(owner),device)).toString())) throw new IOException("Copied historical identity changed.");
                }
            }
        }return entry;
    }
    private static String hex(byte[] value) throws IOException { if(value==null) throw new IOException("Migration key metadata is missing.");return MonogramRecoveryStore.hex(value); }
    private static void erase(File file) { if(file.isDirectory()) { File[] children=file.listFiles();if(children!=null) for(File child:children) erase(child); }SQLiteDatabase.deleteDatabase(file);file.delete(); }
}
