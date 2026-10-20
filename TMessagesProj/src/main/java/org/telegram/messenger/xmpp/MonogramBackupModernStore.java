// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.io.IOException;
import com.negzero.monogram.xmpp.Omemo2Device;
import com.negzero.monogram.xmpp.Omemo2Records;
import com.negzero.monogram.xmpp.Omemo2Restore;
import org.jxmpp.jid.impl.JidCreate;
import org.telegram.messenger.ApplicationLoader;

/** Offline modern-key restore preparation. No controller/device publication or live files are touched. */
final class MonogramBackupModernStore {
    static final class Stage {
        final File active,recovery;
        final int device;
        Stage(File active,File recovery,int device) { this.active=active;this.recovery=recovery;this.device=device; }
    }
    private MonogramBackupModernStore() { }

    /** Creates a new dedicated work directory; caller deletes the whole stage on cancellation/failure. */
    static Stage prepare(File snapshot,File privateWork,String owner) throws Exception {
        if(!owner.equals(JidCreate.entityBareFrom(owner).toString())) throw new IOException("Invalid migration account.");
        String root=ApplicationLoader.applicationContext.getNoBackupFilesDir().getCanonicalPath()+File.separator;
        if(!privateWork.getCanonicalPath().startsWith(root) || privateWork.exists()
                || snapshot.getCanonicalPath().startsWith(privateWork.getCanonicalPath()+File.separator))
            throw new IOException("Modern migration needs a new app-private work directory.");
        if(!privateWork.mkdir()) throw new IOException("Could not create private migration stage.");
        File activeFile=new File(privateWork,"active.db"),recoveryFile=new File(privateWork,"receive-only.db");
        boolean success=false;
        try(MonogramOmemo2Store active=new MonogramOmemo2Store(ApplicationLoader.applicationContext,activeFile.getAbsolutePath());
            MonogramOmemo2Store recovery=new MonogramOmemo2Store(ApplicationLoader.applicationContext,recoveryFile.getAbsolutePath());
            SQLiteDatabase source=SQLiteDatabase.openDatabase(snapshot.getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY)) {
            SQLiteDatabase fresh=active.getWritableDatabase(),old=recovery.getWritableDatabase();
            MonogramBackupDatabase.restoreIntoEmpty(source,old,MonogramBackupDatabase.Scope.MODERN,owner,null);
            MonogramBackupDatabase.restoreIntoEmpty(source,fresh,MonogramBackupDatabase.Scope.MODERN,owner,null);
            Integer selected=null;
            try(Cursor rows=old.rawQuery("SELECT owner,device FROM own_devices",null)) {
                if(rows.moveToFirst()) {
                    if(!owner.equals(rows.getString(0)) || rows.getLong(1)<=0 || rows.getLong(1)>Integer.MAX_VALUE)
                        throw new IOException("Migration device does not belong to this account.");
                    selected=rows.getInt(1);
                    if(rows.moveToNext()) throw new IOException("Migration contains multiple device owners.");
                }
            }
            if(selected==null) throw new IOException("Modern OMEMO identity is not initialized; no recovery stage is required.");
            final int device=selected;
            try(Cursor rows=old.rawQuery("SELECT 1 FROM records WHERE owner<>? OR device<>? LIMIT 1",new String[]{owner,Integer.toString(device)})) {
                if(rows.moveToFirst()) throw new IOException("Migration contains foreign device records.");
            }
            // The generic records API cannot enumerate per-peer sessions. Delete both lineage
            // kinds by SQL only in this independently initialized stage, never in the archive.
            fresh.delete("records","kind=? OR kind=?",new String[]{Integer.toString(Omemo2Records.SESSION),Integer.toString(Omemo2Records.SESSION_IDENTITY)});
            Omemo2Device target=new Omemo2Device(owner,device,() -> active.begin(owner,device));
            Omemo2Restore.freshKeys(target,System.currentTimeMillis());
            success=true;return new Stage(activeFile,recoveryFile,device);
        } finally {
            if(!success) { SQLiteDatabase.deleteDatabase(activeFile);SQLiteDatabase.deleteDatabase(recoveryFile);privateWork.delete(); }
        }
    }
}
