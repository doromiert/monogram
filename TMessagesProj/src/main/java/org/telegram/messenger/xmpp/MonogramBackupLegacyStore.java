// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import org.jivesoftware.smackx.omemo.MonogramLegacyBackup;
import org.jxmpp.jid.BareJid;
import org.jxmpp.jid.impl.JidCreate;
import org.telegram.messenger.ApplicationLoader;

/** Completes the isolated legacy overlay snapshot without writing the live legacy database or bootstrap tree. */
final class MonogramBackupLegacyStore {
    private MonogramBackupLegacyStore() { }
    static void materialize(File isolatedOverlay,File privateWork,String owner) throws Exception {
        String root=ApplicationLoader.applicationContext.getNoBackupFilesDir().getCanonicalPath()+File.separator;
        if(!isolatedOverlay.getCanonicalPath().startsWith(root) || !privateWork.getCanonicalPath().startsWith(root))
            throw new IOException("Legacy backup staging must be inside app-private no_backup storage.");
        File live=new File(ApplicationLoader.applicationContext.getNoBackupFilesDir(),"monogram-legacy-omemo.db");
        if(isolatedOverlay.getCanonicalFile().equals(live.getCanonicalFile())
                || privateWork.getCanonicalFile().equals(ApplicationLoader.applicationContext.getNoBackupFilesDir().getCanonicalFile())
                || !isolatedOverlay.getCanonicalPath().startsWith(privateWork.getCanonicalPath()+File.separator))
            throw new IOException("Legacy materialization requires a dedicated isolated backup work directory.");
        BareJid account=JidCreate.entityBareFrom(owner);if(!owner.equals(account.toString())) throw new IOException("Invalid legacy backup account.");
        if(!isolatedOverlay.isFile()) throw new IOException("Create the owner-filtered legacy overlay snapshot first.");
        File bootstrap=File.createTempFile("backup-bootstrap-", ".tmp",privateWork);if(!bootstrap.delete()) throw new IOException("Could not prepare private bootstrap copy.");
        MonogramLegacyOmemoStore store=new MonogramLegacyOmemoStore(ApplicationLoader.applicationContext,isolatedOverlay.getAbsolutePath());boolean success=false;
        try {
            SQLiteDatabase database=store.getWritableDatabase();
            try(Cursor rows=database.rawQuery("SELECT 1 FROM records WHERE owner IS NULL OR owner<>? LIMIT 1",new String[]{owner})) {
                if(rows.moveToFirst()) throw new IOException("Legacy overlay snapshot contains another account.");
            }
            MonogramLegacyBackup.materialize(new File(ApplicationLoader.getFilesDirFixed(),"monogram-omemo"),bootstrap,account,store,(jid,device) -> {
                Set<BareJid> peers=new HashSet<>();
                try(Cursor rows=database.rawQuery("SELECT DISTINCT peer FROM records WHERE owner=? AND device=? AND peer<>''",new String[]{jid,Integer.toString(device)})) {
                    while(rows.moveToNext()) peers.add(JidCreate.entityBareFrom(rows.getString(0)));
                }
                return peers;
            });
            success=true;
        } finally { store.close();erase(bootstrap);if(!success) SQLiteDatabase.deleteDatabase(isolatedOverlay); }
    }
    private static void erase(File file) {
        if(file.isDirectory()) { File[] files=file.listFiles();if(files!=null) for(File child:files) erase(child); }file.delete();
    }
}
