// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.io.IOException;
import java.util.*;
import org.jxmpp.jid.impl.JidCreate;

/** Logical snapshots for the Android SQLite stores. Native MessagesStorage needs its own queue-aware adapter. */
final class MonogramBackupDatabase {
    enum Scope { HISTORY, LEGACY, MODERN }
    private static final int MAX_CELL = 16 * 1024 * 1024;
    private MonogramBackupDatabase() { }

    /** Caller must quiesce writers across all stores first. Output is private, portable plaintext for the envelope. */
    static void snapshot(SQLiteDatabase source, File file, Scope scope, String owner, MonogramBackupSecrets.Transform unseal) throws Exception {
        owner(owner); if(file.exists()) throw new IOException("Snapshot destination already exists.");
        boolean success=false;
        source.beginTransaction();
        try(SQLiteDatabase target=SQLiteDatabase.openOrCreateDatabase(file,null)) {
            target.beginTransaction();
            try {
                Map<String,String> schema=schema(source);validateScope(schema,scope);
                for(Map.Entry<String,String> entry:schema.entrySet()) if(entry.getKey().startsWith("table:")) target.execSQL(entry.getValue());
                copyRows(source,target,schema,scope,owner,unseal,false);
                for(Map.Entry<String,String> entry:schema.entrySet()) if(!entry.getKey().startsWith("table:")) target.execSQL(entry.getValue());
                copySequence(source,target);target.setVersion(source.getVersion());target.setTransactionSuccessful();
            } finally { target.endTransaction(); }
            success=true;
        } finally {
            source.endTransaction();
            if(!success) SQLiteDatabase.deleteDatabase(file);
        }
    }
    /** Destination must be a freshly initialized, unopened-to-messaging temporary store using trusted app schema. */
    static void restoreIntoEmpty(SQLiteDatabase staged, SQLiteDatabase target, Scope scope, String owner,
            MonogramBackupSecrets.Transform reseal) throws Exception {
        owner(owner);Map<String,String> expected=schema(target),actual=schema(staged);
        validateScope(actual,scope);
        Map<String,String> compatible=new TreeMap<>(expected);compatible.remove("table:retired_records");
        boolean legacyV1=scope==Scope.LEGACY && staged.getVersion()==1 && target.getVersion()==MonogramLegacyOmemoStore.VERSION && actual.equals(compatible);
        if(!legacyV1 && (staged.getVersion()!=target.getVersion() || !actual.equals(expected))) throw new IOException("Backup database schema does not match this app version.");
        try(Cursor check=staged.rawQuery("PRAGMA quick_check",null)) {
            if(!check.moveToFirst() || !"ok".equals(check.getString(0)) || check.moveToNext()) throw new IOException("Backup database failed its integrity check.");
        }
        target.beginTransaction();
        try {
            for(String key:expected.keySet()) if(key.startsWith("table:")) {
                try(Cursor rows=target.rawQuery("SELECT 1 FROM "+quote(key.substring(6))+" LIMIT 1",null)) {
                    if(rows.moveToFirst()) throw new IOException("Restore destination must be empty; existing messages and keys were preserved.");
                }
            }
            copyRows(staged,target,actual,scope,owner,reseal,true);copySequence(staged,target);target.setTransactionSuccessful();
        } finally { target.endTransaction(); }
    }
    private static void copyRows(SQLiteDatabase source,SQLiteDatabase target,Map<String,String> schema,Scope scope,String owner,
            MonogramBackupSecrets.Transform secrets,boolean rejectOtherOwners) throws Exception {
        for(String key:schema.keySet()) if(key.startsWith("table:")) {
            String table=key.substring(6);ArrayList<String> columns=new ArrayList<>();
            try(Cursor info=source.rawQuery("PRAGMA table_info("+quote(table)+")",null)) { while(info.moveToNext()) columns.add(info.getString(1)); }
            if(columns.isEmpty()) throw new IOException("Backup table has no columns.");
            boolean scoped=scope!=Scope.HISTORY;
            if(scoped && !columns.contains("owner")) throw new IOException("Key store table lacks account ownership.");
            // Signal/incoming journal BLOBs can exceed CursorWindow; fetch them in bounded chunks separately.
            StringBuilder select=new StringBuilder("SELECT rowid");
            for(String column:columns) select.append(",typeof(").append(quote(column)).append("),CASE WHEN typeof(").append(quote(column)).append(")='blob' THEN NULL ELSE ").append(quote(column)).append(" END");
            select.append(" FROM ").append(quote(table));
            if(scoped) select.append(" WHERE owner=?");
            try(Cursor rows=source.rawQuery(select.toString(),scoped ? new String[]{owner} : null)) {
                while(rows.moveToNext()) {
                    long rowId=rows.getLong(0);ContentValues values=new ContentValues();
                    for(int i=0;i<columns.size();i++) {
                        String column=columns.get(i),type=rows.getString(1+i*2);int at=2+i*2;
                        switch(type) {
                            case "null": values.putNull(column);break;
                            case "integer": values.put(column,rows.getLong(at));break;
                            case "real": values.put(column,rows.getDouble(at));break;
                            case "blob": values.put(column,blob(source,table,column,rowId));break;
                            case "text":
                                String text=rows.getString(at);
                                if(text.length()>MAX_CELL) throw new IOException("Backup text field is too large.");
                                if(scope==Scope.HISTORY && table.equals("room_bookmarks") && column.equals("payload"))
                                    text=MonogramBackupSecrets.bookmarkPayload(text,secrets);
                                values.put(column,text);break;
                            default: throw new IOException("Unsupported SQLite value type.");
                        }
                    }
                    target.insertOrThrow(table,null,values);
                }
            }
            if(scoped) try(Cursor other=source.rawQuery("SELECT 1 FROM "+quote(table)+" WHERE owner IS NULL OR owner<>? LIMIT 1",new String[]{owner})) {
                // Export filters a shared source; a portable restore must never contain another account.
                if(other.moveToFirst() && rejectOtherOwners) throw new IOException("Portable key store includes another account.");
            }
        }
    }
    static byte[] blob(SQLiteDatabase db,String table,String column,long rowId) throws IOException {
        long length;
        try(Cursor size=db.rawQuery("SELECT length("+quote(column)+") FROM "+quote(table)+" WHERE rowid=?",new String[]{Long.toString(rowId)})) {
            if(!size.moveToFirst()) throw new IOException("Backup source row disappeared.");length=size.getLong(0);
        }
        if(length<0 || length>MAX_CELL) throw new IOException("Backup binary field is too large.");
        byte[] result=new byte[(int)length];int offset=0;
        while(offset<result.length) try(Cursor chunk=db.rawQuery("SELECT substr("+quote(column)+",?,?) FROM "+quote(table)+" WHERE rowid=?",
                new String[]{Integer.toString(offset+1),Integer.toString(Math.min(512*1024,result.length-offset)),Long.toString(rowId)})) {
            if(!chunk.moveToFirst()) throw new IOException("Missing backup binary chunk.");byte[] bytes=chunk.getBlob(0);
            if(bytes.length==0 || bytes.length>result.length-offset) throw new IOException("Invalid backup binary chunk.");
            System.arraycopy(bytes,0,result,offset,bytes.length);offset+=bytes.length;
        }
        return result;
    }
    private static Map<String,String> schema(SQLiteDatabase db) throws IOException {
        Map<String,String> result=new TreeMap<>();
        try(Cursor rows=db.rawQuery("SELECT type,name,sql FROM sqlite_master WHERE type IN ('table','index','trigger') AND name NOT LIKE 'sqlite_%' AND name<>'android_metadata' AND sql IS NOT NULL ORDER BY type,name",null)) {
            while(rows.moveToNext()) {
                String name=rows.getString(1);quote(name);String sql=rows.getString(2);
                if(sql.contains("VIRTUAL TABLE") || sql.toUpperCase(Locale.ROOT).contains("WITHOUT ROWID")) throw new IOException("Unsupported logical backup table type.");
                result.put(rows.getString(0)+":"+name,sql);
            }
        }
        return result;
    }
    private static void validateScope(Map<String,String> schema,Scope scope) throws IOException {
        if(scope==Scope.HISTORY) {
            if(!schema.containsKey("table:messages") || !schema.containsKey("table:peers")) throw new IOException("Not a Monogram history database.");
        } else {
            Set<String> tables=new HashSet<>();for(String key:schema.keySet()) if(key.startsWith("table:")) tables.add(key.substring(6));
            Set<String> expected=new HashSet<>(Collections.singletonList("records"));if(scope==Scope.MODERN) expected.add("own_devices");
            if(scope==Scope.LEGACY && tables.contains("retired_records")) expected.add("retired_records");
            if(!tables.equals(expected)) throw new IOException("Unsupported OMEMO database tables.");
        }
    }
    private static void copySequence(SQLiteDatabase source,SQLiteDatabase target) throws IOException {
        boolean present;
        try(Cursor rows=source.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='sqlite_sequence'",null)) { present=rows.moveToFirst(); }
        if(!present) return;
        target.delete("sqlite_sequence",null,null);
        try(Cursor rows=source.rawQuery("SELECT name,seq FROM sqlite_sequence",null)) {
            while(rows.moveToNext()) { ContentValues value=new ContentValues();value.put("name",rows.getString(0));value.put("seq",rows.getLong(1));target.insertOrThrow("sqlite_sequence",null,value); }
        }
    }
    private static String quote(String identifier) throws IOException {
        if(identifier==null || !identifier.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IOException("Unsupported SQLite identifier.");
        return '"'+identifier+'"';
    }
    private static void owner(String owner) throws Exception {
        if(owner==null || owner.indexOf('/')>=0 || !JidCreate.entityBareFrom(owner).toString().equals(owner)) throw new IllegalArgumentException("Invalid backup account.");
    }
}
