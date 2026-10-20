// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.negzero.monogram.xmpp.LegacyOmemoRecords;
import java.io.File;
import java.io.IOException;
import org.jivesoftware.smackx.omemo.MonogramLegacyRestore;
import org.jivesoftware.smackx.omemo.MonogramSignalStore;
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;
import org.jivesoftware.smackx.omemo.signal.SignalFileBasedOmemoStore;
import org.jxmpp.jid.impl.JidCreate;
import org.telegram.messenger.ApplicationLoader;

/** Separate active and receive-only legacy stages from an owner-materialized portable snapshot. */
final class MonogramBackupLegacyRestore {
    static final class Stage {
        final File active,recovery;final int device;
        Stage(File active,File recovery,int device) { this.active=active;this.recovery=recovery;this.device=device; }
    }
    private MonogramBackupLegacyRestore() { }
    static Stage prepare(File snapshot,File privateWork,String owner,int device) throws Exception {
        if(!owner.equals(JidCreate.entityBareFrom(owner).toString()) || device<=0) throw new IOException("Invalid legacy migration owner/device.");
        String root=ApplicationLoader.applicationContext.getNoBackupFilesDir().getCanonicalPath()+File.separator;
        if(!privateWork.getCanonicalPath().startsWith(root) || privateWork.exists()
                || snapshot.getCanonicalPath().startsWith(privateWork.getCanonicalPath()+File.separator))
            throw new IOException("Legacy migration needs a new app-private work directory.");
        if(!privateWork.mkdir()) throw new IOException("Could not create private legacy migration stage.");
        File activeFile=new File(privateWork,"active.db"),recoveryFile=new File(privateWork,"receive-only.db"),bootstrap=new File(privateWork,"empty-bootstrap");
        boolean success=false;
        try(MonogramLegacyOmemoStore active=new MonogramLegacyOmemoStore(ApplicationLoader.applicationContext,activeFile.getAbsolutePath());
            MonogramLegacyOmemoStore recovery=new MonogramLegacyOmemoStore(ApplicationLoader.applicationContext,recoveryFile.getAbsolutePath());
            SQLiteDatabase source=SQLiteDatabase.openDatabase(snapshot.getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY)) {
            SQLiteDatabase fresh=active.getWritableDatabase(),old=recovery.getWritableDatabase();
            MonogramBackupDatabase.restoreIntoEmpty(source,old,MonogramBackupDatabase.Scope.LEGACY,owner,null);
            MonogramBackupDatabase.restoreIntoEmpty(source,fresh,MonogramBackupDatabase.Scope.LEGACY,owner,null);
            try(Cursor rows=old.rawQuery("SELECT 1 FROM records WHERE owner=? AND device=? AND kind=? AND peer='' AND id=0",new String[]{owner,Integer.toString(device),Integer.toString(LegacyOmemoRecords.PURGED)})) {
                if(!rows.moveToFirst()) throw new IOException("Legacy snapshot must materialize and suppress bootstrap for its selected device.");
            }
            // Other historical own devices remain in recovery only; never open old outgoing chains.
            fresh.delete("records","device<>? OR kind=?",new String[]{Integer.toString(device),Integer.toString(LegacyOmemoRecords.SESSION)});
            fresh.delete("retired_records",null,null); // All retired peer ratchets belong to the receive-only copy, never duplicate mutable archives.
            if(!bootstrap.mkdir()) throw new IOException("Could not create isolated empty bootstrap.");
            MonogramSignalStore store=new MonogramSignalStore(new SignalFileBasedOmemoStore(bootstrap),active);
            MonogramLegacyRestore.freshKeys(store,new OmemoDevice(JidCreate.entityBareFrom(owner),device),System.currentTimeMillis());
            success=true;return new Stage(activeFile,recoveryFile,device);
        } finally {
            if(!success) { SQLiteDatabase.deleteDatabase(activeFile);SQLiteDatabase.deleteDatabase(recoveryFile);erase(bootstrap);privateWork.delete(); }
        }
    }
    private static void erase(File file) {
        if(file.isDirectory()) { File[] children=file.listFiles();if(children!=null) for(File child:children) erase(child); }file.delete();
    }
}
