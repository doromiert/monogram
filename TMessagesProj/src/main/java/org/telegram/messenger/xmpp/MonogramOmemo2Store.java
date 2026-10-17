// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import com.negzero.monogram.xmpp.Omemo2Records;
import com.negzero.monogram.xmpp.Omemo2IncomingJournal;
import java.util.Arrays;
import org.jxmpp.jid.impl.JidCreate;
import org.telegram.messenger.ApplicationLoader;

/** Separate from legacy identities and conversation migrations; SQLite commits every ratchet change together. */
final class MonogramOmemo2Store extends SQLiteOpenHelper {
    MonogramOmemo2Store(int account) {
        this(ApplicationLoader.applicationContext, new java.io.File(ApplicationLoader.applicationContext.getNoBackupFilesDir(),
                "monogram-omemo2-" + account + ".db").getAbsolutePath());
    }
    MonogramOmemo2Store(Context context, String name) { super(context, name, null, 2); }
    @Override public void onConfigure(SQLiteDatabase db) { db.execSQL("PRAGMA synchronous=FULL"); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE records(owner TEXT NOT NULL,device INTEGER NOT NULL,kind INTEGER NOT NULL,peer TEXT NOT NULL,id INTEGER NOT NULL,value BLOB NOT NULL,PRIMARY KEY(owner,device,kind,peer,id))");
        createDevices(db);
    }
    private static void createDevices(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE own_devices(owner TEXT PRIMARY KEY NOT NULL,device INTEGER NOT NULL CHECK(device BETWEEN 1 AND 2147483647))");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int from, int to) {
        if (from == 1 && to == 2) createDevices(db);
        else throw new IllegalStateException("Unknown OMEMO2 migration");
    }
    private static String owner(String account) throws Exception {
        if (account == null || account.indexOf('/') >= 0) throw new IllegalArgumentException("Invalid OMEMO2 account");
        return JidCreate.entityBareFrom(account).toString();
    }
    /** Keep the device ID beside its keys, outside Android backups; never recycle a legacy ID. */
    int device(String account) throws Exception {
        String owner = owner(account);
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            Integer selected = null; boolean allocated = false;
            try (Cursor c = db.rawQuery("SELECT device FROM own_devices WHERE owner=?",new String[]{owner})) {
                if (c.moveToFirst()) { selected = c.getInt(0); allocated = true; }
            }
            try (Cursor c = db.rawQuery("SELECT DISTINCT device FROM records WHERE owner=?",new String[]{owner})) {
                while (c.moveToNext()) {
                    long existing = c.getLong(0);
                    if (existing <= 0 || existing > Integer.MAX_VALUE || selected != null && selected.longValue() != existing)
                        throw new IllegalStateException("Conflicting OMEMO2 device state; existing keys were preserved");
                    selected = (int)existing;
                }
            }
            if (selected == null) selected = new java.security.SecureRandom().nextInt(Integer.MAX_VALUE) + 1;
            if (!allocated) {
                ContentValues row = new ContentValues(); row.put("owner",owner); row.put("device",selected);
                db.insertOrThrow("own_devices",null,row);
            }
            db.setTransactionSuccessful(); return selected;
        } finally { db.endTransaction(); }
    }
    /** Revoke pending decrypted data without changing identities, trust, or the registered device. */
    void clearIncoming(String account) throws Exception {
        String owner=owner(account);
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            java.util.ArrayList<Integer> devices=new java.util.ArrayList<>();
            try(Cursor rows=db.rawQuery("SELECT device FROM records WHERE owner=? UNION SELECT device FROM own_devices WHERE owner=?",new String[]{owner,owner})) {
                while(rows.moveToNext()) devices.add(rows.getInt(0));
            }
            for(int device:devices) try(Omemo2Records tx=begin(owner,device)) {
                Omemo2IncomingJournal.rotate(tx);tx.commit();
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    Omemo2Records begin(String account, int device) throws Exception {
        if (device <= 0) throw new IllegalArgumentException("Invalid OMEMO2 device");
        String owner = owner(account);
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        return new Omemo2Records() {
            final Thread thread = Thread.currentThread();
            boolean closed;
            void active() {
                if (closed || Thread.currentThread() != thread) throw new IllegalStateException("OMEMO2 transaction belongs to its opening thread");
            }
            String[] key(int kind, String peer, int id) throws Exception {
                active();
                if (kind < IDENTITY || kind > INCOMING || peer == null) throw new IllegalArgumentException("Invalid OMEMO2 record");
                if (kind >= SESSION && kind <= TRUSTED || kind == SEEN || kind == DEVICES || kind == REJECTED) {
                    if ((kind == DEVICES ? id != 0 : id <= 0) || !JidCreate.entityBareFrom(peer).toString().equals(peer)) throw new IllegalArgumentException("Invalid OMEMO2 peer/device");
                } else if(kind==INCOMING) {
                    if(!peer.isEmpty() || id<0 || id>Omemo2IncomingJournal.MAX_ENTRIES) throw new IllegalArgumentException("Invalid incoming OMEMO2 record");
                } else if (!peer.isEmpty() || kind != IDENTITY && kind != META && id == 0) throw new IllegalArgumentException("Invalid local OMEMO2 record");
                return new String[]{owner, Integer.toString(device), Integer.toString(kind), peer, Integer.toString(id)};
            }
            @Override public byte[] load(int kind, String peer, int id) throws Exception {
                try (Cursor c = db.rawQuery("SELECT value FROM records WHERE owner=? AND device=? AND kind=? AND peer=? AND id=?", key(kind, peer, id))) {
                    return c.moveToFirst() ? c.getBlob(0) : null;
                }
            }
            @Override public int[] ids(int kind) throws Exception {
                key(kind,"",1);
                try (Cursor c = db.rawQuery("SELECT id FROM records WHERE owner=? AND device=? AND kind=? AND peer='' ORDER BY id",
                        new String[]{owner,Integer.toString(device),Integer.toString(kind)})) {
                    int[] ids = new int[c.getCount()]; int index = 0;
                    while (c.moveToNext()) ids[index++] = c.getInt(0);
                    return ids;
                }
            }
            @Override public void save(int kind, String peer, int id, byte[] value) throws Exception {
                String[] args = key(kind, peer, id);
                int limit=kind==INCOMING ? Omemo2IncomingJournal.MAX_ENTRY_BYTES : 1024*1024;
                if (value != null && (value.length == 0 || value.length > limit || kind == TRUSTED && value.length != 32)) throw new IllegalArgumentException("Invalid OMEMO2 record size");
                if (kind == TRUSTED && !Arrays.equals(load(kind, peer, id), value)) {
                    db.delete("records", "owner=? AND device=? AND peer=? AND id=? AND kind IN (?,?)",
                            new String[]{owner, Integer.toString(device), peer, Integer.toString(id), Integer.toString(SESSION), Integer.toString(SESSION_IDENTITY)});
                }
                if (value == null) db.delete("records", "owner=? AND device=? AND kind=? AND peer=? AND id=?", args);
                else {
                    ContentValues row = new ContentValues(); row.put("owner", owner); row.put("device", device); row.put("kind", kind);
                    row.put("peer", peer); row.put("id", id); row.put("value", value);
                    db.replaceOrThrow("records", null, row);
                }
            }
            @Override public void commit() {
                active();
                try { db.setTransactionSuccessful(); }
                finally { close(); }
            }
            @Override public void close() {
                if (closed) return;
                active(); closed = true; db.endTransaction();
            }
        };
    }
}
