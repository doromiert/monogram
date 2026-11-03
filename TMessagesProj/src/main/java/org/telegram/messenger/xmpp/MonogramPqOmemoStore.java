// SPDX-License-Identifier: AGPL-3.0-only
package org.telegram.messenger.xmpp;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import com.negzero.monogram.xmpp.PqOmemoRecords;
import java.util.Set;
import java.util.TreeSet;
import org.jxmpp.jid.impl.JidCreate;
import org.telegram.messenger.ApplicationLoader;

/** PQ-only account storage; absent from Android automatic backups, separate from both OMEMO databases. */
final class MonogramPqOmemoStore extends SQLiteOpenHelper {
    MonogramPqOmemoStore(int account) {
        this(ApplicationLoader.applicationContext,new java.io.File(ApplicationLoader.applicationContext.getNoBackupFilesDir(),
                "monogram-pq-omemo-" + account + ".db").getAbsolutePath());
    }
    MonogramPqOmemoStore(Context context,String path) { super(context,path,null,1); }
    @Override public void onConfigure(SQLiteDatabase db) { db.execSQL("PRAGMA synchronous=FULL"); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE records(owner TEXT NOT NULL,key TEXT NOT NULL,value BLOB NOT NULL,PRIMARY KEY(owner,key))");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int from,int to) { throw new IllegalStateException("Unknown PQ OMEMO database version"); }
    PqOmemoRecords.Factory factory(String account) throws Exception {
        if (account == null || account.indexOf('/') >= 0 || !JidCreate.entityBareFrom(account).toString().equals(account))
            throw new IllegalArgumentException("Canonical account JID required");
        return () -> begin(account);
    }
    PqOmemoRecords begin(String owner) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        return new PqOmemoRecords() {
            final Thread opening = Thread.currentThread(); boolean closed;
            void active() { if (closed || Thread.currentThread() != opening) throw new IllegalStateException("PQ transaction belongs to its opening thread"); }
            void key(String key,boolean prefix) {
                active();
                if (key == null || key.length() > 4096 || !key.matches(prefix ? "[A-Za-z0-9_/-]*" : "[A-Za-z0-9_/-]+"))
                    throw new IllegalArgumentException("Invalid PQ record key");
            }
            @Override public byte[] get(String key) {
                key(key,false);
                try (Cursor row = db.rawQuery("SELECT value FROM records WHERE owner=? AND key=?",new String[]{owner,key})) {
                    return row.moveToFirst() ? row.getBlob(0) : null;
                }
            }
            @Override public void put(String key,byte[] value) {
                key(key,false);
                if (value == null) db.delete("records","owner=? AND key=?",new String[]{owner,key});
                else {
                    if (value.length == 0 || value.length > 2 * 1024 * 1024) throw new IllegalArgumentException("Invalid PQ record size");
                    ContentValues row = new ContentValues(); row.put("owner",owner); row.put("key",key); row.put("value",value);
                    db.replaceOrThrow("records",null,row);
                }
            }
            @Override public Set<String> keys(String prefix) {
                key(prefix,true); Set<String> keys = new TreeSet<>();
                // LIKE would treat '_' in base64url-encoded addresses as a wildcard.
                try (Cursor rows = db.rawQuery("SELECT key FROM records WHERE owner=? AND substr(key,1,?)=? ORDER BY key",
                        new String[]{owner,Integer.toString(prefix.length()),prefix})) {
                    while (rows.moveToNext()) keys.add(rows.getString(0));
                }
                return keys;
            }
            @Override public void commit() {
                active(); try { db.setTransactionSuccessful(); } finally { close(); }
            }
            @Override public void close() {
                if (closed) return; active(); closed = true; db.endTransaction();
            }
        };
    }
    void clearIncoming(String account) throws Exception {
        try (PqOmemoRecords tx = factory(account).begin()) {
            for (String key : tx.keys("incoming/")) tx.put(key,null); tx.commit();
        }
    }
}
