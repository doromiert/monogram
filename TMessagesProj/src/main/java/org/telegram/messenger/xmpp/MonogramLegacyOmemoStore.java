// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import com.negzero.monogram.xmpp.LegacyIncomingJournal;
import com.negzero.monogram.xmpp.LegacyOmemoRecords;
import java.io.File;
import java.util.ArrayDeque;
import java.util.SortedSet;
import java.util.TreeSet;
import org.jxmpp.jid.impl.JidCreate;
import org.telegram.messenger.ApplicationLoader;

/** Application-wide transactional overlay; existing Signal files remain a read-only bootstrap. */
final class MonogramLegacyOmemoStore extends SQLiteOpenHelper implements LegacyOmemoRecords.Factory {
    private static MonogramLegacyOmemoStore instance;
    private final ThreadLocal<ArrayDeque<Transaction>> transactions=new ThreadLocal<ArrayDeque<Transaction>>() {
        @Override protected ArrayDeque<Transaction> initialValue() { return new ArrayDeque<>(); }
    };

    static synchronized MonogramLegacyOmemoStore application() {
        if(instance==null) {
            Context context=ApplicationLoader.applicationContext;
            instance=new MonogramLegacyOmemoStore(context,new File(context.getNoBackupFilesDir(),"monogram-legacy-omemo.db").getAbsolutePath());
        }
        return instance;
    }
    MonogramLegacyOmemoStore(Context context,String name) { super(context,name,null,1); }
    @Override public void onConfigure(SQLiteDatabase db) { db.execSQL("PRAGMA synchronous=FULL"); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE records(owner TEXT NOT NULL,device INTEGER NOT NULL CHECK(device BETWEEN 1 AND 2147483647),kind INTEGER NOT NULL,peer TEXT NOT NULL,id INTEGER NOT NULL,value BLOB NOT NULL,PRIMARY KEY(owner,device,kind,peer,id))");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int from,int to) { throw new IllegalStateException("Unknown legacy OMEMO migration"); }
    private static String owner(String account) throws Exception {
        if(account==null || account.indexOf('/')>=0) throw new IllegalArgumentException("Invalid legacy OMEMO account");
        return JidCreate.entityBareFrom(account).toString();
    }
    @Override public SortedSet<Integer> localDevices(String account) throws Exception {
        String owner=owner(account);SortedSet<Integer> devices=new TreeSet<>();
        try(Cursor rows=getReadableDatabase().rawQuery("SELECT DISTINCT device FROM records WHERE owner=? ORDER BY device",new String[]{owner})) {
            while(rows.moveToNext()) {
                long device=rows.getLong(0);
                if(device<=0 || device>Integer.MAX_VALUE) throw new IllegalStateException("Invalid legacy OMEMO device state");
                devices.add((int)device);
            }
        }
        return devices;
    }
    @Override public LegacyOmemoRecords begin(String account,int device) throws Exception {
        String owner=owner(account);
        if(device<=0) throw new IllegalArgumentException("Invalid legacy OMEMO device");
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        Transaction tx=new Transaction(db,owner,device);transactions.get().push(tx);return tx;
    }
    /** Revoke decrypted pending data without changing keys, trust, or any other account. */
    void clearIncoming(String account) throws Exception {
        String owner=owner(account);SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            for(int device:localDevices(owner)) try(LegacyOmemoRecords tx=begin(owner,device)) {
                LegacyIncomingJournal.rotate(tx);tx.commit();
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    private final class Transaction implements LegacyOmemoRecords {
        final SQLiteDatabase db;
        final String owner;
        final int device;
        final Thread thread=Thread.currentThread();
        boolean closed;
        Transaction(SQLiteDatabase db,String owner,int device) { this.db=db;this.owner=owner;this.device=device; }
        void active() {
            if(Thread.currentThread()!=thread || closed || transactions.get().peek()!=this)
                throw new IllegalStateException("Legacy OMEMO transactions must be used on their opening thread in nesting order");
        }
        String[] key(int kind,String peer,int id) throws Exception {
            active();
            if(kind<IDENTITY_PAIR || kind>INCOMING || peer==null) throw new IllegalArgumentException("Invalid legacy OMEMO record");
            boolean contact=kind==IDENTITY || kind==SESSION || kind==MESSAGE_COUNTER || kind==LAST_RECEIVED || kind==LAST_PUBLISHED || kind==DEVICE_LIST;
            if(contact) {
                if((kind==DEVICE_LIST ? id!=0 : id<=0) || !JidCreate.entityBareFrom(peer).toString().equals(peer))
                    throw new IllegalArgumentException("Invalid legacy OMEMO peer/device");
            } else {
                if(!peer.isEmpty() || kind==INCOMING && (id<0 || id>LegacyIncomingJournal.MAX_ENTRIES)
                        || kind!=INCOMING && kind!=PRE_KEY && kind!=SIGNED_PRE_KEY && id!=0)
                    throw new IllegalArgumentException("Invalid local legacy OMEMO record");
            }
            return new String[]{owner,Integer.toString(device),Integer.toString(kind),peer,Integer.toString(id)};
        }
        @Override public byte[] load(int kind,String peer,int id) throws Exception {
            String[] args=key(kind,peer,id);byte[] value;int length;
            // CursorWindow can be smaller than an existing Signal session; read large records in bounded chunks.
            try(Cursor rows=db.rawQuery("SELECT length(value),substr(value,1,1048576) FROM records WHERE owner=? AND device=? AND kind=? AND peer=? AND id=?",args)) {
                if(!rows.moveToFirst()) return null;
                long size=rows.getLong(0);
                int limit=kind==INCOMING ? LegacyIncomingJournal.MAX_ENTRY_BYTES : LegacyOmemoRecords.MAX_RECORD_BYTES;
                if(size<=0 || size>limit) throw new IllegalStateException("Invalid stored legacy OMEMO record size");
                length=(int)size;value=rows.getBlob(1);
            }
            if(value.length==length) return value;
            byte[] complete=new byte[length];int offset=value.length;System.arraycopy(value,0,complete,0,offset);
            while(offset<length) try(Cursor rows=db.rawQuery("SELECT substr(value,"+(offset+1)+",1048576) FROM records WHERE owner=? AND device=? AND kind=? AND peer=? AND id=?",args)) {
                if(!rows.moveToFirst()) throw new IllegalStateException("Missing legacy OMEMO record chunk");
                byte[] chunk=rows.getBlob(0);
                if(chunk.length==0 || chunk.length>length-offset) throw new IllegalStateException("Invalid legacy OMEMO record chunk");
                System.arraycopy(chunk,0,complete,offset,chunk.length);offset+=chunk.length;
            }
            return complete;
        }
        @Override public int[] ids(int kind,String peer) throws Exception {
            int probe=kind==IDENTITY || kind==SESSION || kind==MESSAGE_COUNTER || kind==LAST_RECEIVED || kind==LAST_PUBLISHED ? 1 : 0;
            String[] args=key(kind,peer,probe);
            try(Cursor rows=db.rawQuery("SELECT id FROM records WHERE owner=? AND device=? AND kind=? AND peer=? ORDER BY id",new String[]{args[0],args[1],args[2],args[3]})) {
                int[] ids=new int[rows.getCount()];int at=0;while(rows.moveToNext()) ids[at++]=rows.getInt(0);return ids;
            }
        }
        @Override public void save(int kind,String peer,int id,byte[] value) throws Exception {
            String[] args=key(kind,peer,id);
            int limit=kind==INCOMING ? LegacyIncomingJournal.MAX_ENTRY_BYTES : LegacyOmemoRecords.MAX_RECORD_BYTES;
            if(value!=null && (value.length==0 || value.length>limit)) throw new IllegalArgumentException("Invalid legacy OMEMO record size");
            if(value==null) db.delete("records","owner=? AND device=? AND kind=? AND peer=? AND id=?",args);
            else {
                ContentValues row=new ContentValues();row.put("owner",owner);row.put("device",device);row.put("kind",kind);
                row.put("peer",peer);row.put("id",id);row.put("value",value.clone());db.replaceOrThrow("records",null,row);
            }
        }
        @Override public void purge() {
            active();db.delete("records","owner=? AND device=?",new String[]{owner,Integer.toString(device)});
        }
        @Override public void commit() {
            active();try { db.setTransactionSuccessful(); } finally { close(); }
        }
        @Override public void close() {
            if(Thread.currentThread()!=thread) throw new IllegalStateException("Legacy OMEMO transaction belongs to its opening thread");
            if(closed) return;
            active();closed=true;transactions.get().pop();
            try { db.endTransaction(); } finally { if(transactions.get().isEmpty()) transactions.remove(); }
        }
    }
}
