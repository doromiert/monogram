// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.VideoEditedInfo;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;

/** The native per-account database IS the durable outbox; snapshot/reuse it, never invent another send queue. */
final class MonogramBackupNativeStore {
    interface Completion { void done(Metadata metadata,Exception error); }
    interface Execute { void sql(String statement) throws Exception; }
    static final class Metadata {
        final String owner,schema;
        final long self;
        final int version,pending,failed,scheduled;
        Metadata(String owner,long self,int version,String schema,int pending,int failed,int scheduled) {
            this.owner=owner;this.self=self;this.version=version;this.schema=schema;this.pending=pending;this.failed=failed;this.scheduled=scheduled;
        }
        JSONObject json() throws Exception {
            return new JSONObject().put("owner",owner).put("self",self).put("version",version).put("schema",schema)
                    .put("pending",pending).put("failed",failed).put("scheduled",scheduled);
        }
        static Metadata parse(JSONObject json) throws Exception {
            return new Metadata(json.getString("owner"),json.getLong("self"),json.getInt("version"),json.getString("schema"),
                    json.getInt("pending"),json.getInt("failed"),json.getInt("scheduled"));
        }
    }
    private MonogramBackupNativeStore() { }

    /** Cross-store writers must already be quiescent. Completion runs on the native storage queue. */
    static void capture(int account,String expectedOwner,File privateDestination,Completion completion) {
        MessagesStorage storage=MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> {
            Metadata result=null;Exception error=null;boolean ownsDestination=false;
            try {
                requirePrivate(privateDestination);
                if(privateDestination.exists()) throw new IOException("Native snapshot destination already exists.");
                ownsDestination=true;
                if(expectedOwner.isEmpty() || !expectedOwner.equals(MonogramController.get(account).accountJid())) throw new IOException("The backup account changed.");
                long self=UserConfig.getInstance(account).getClientUserId();
                if(self<=0) throw new IOException("The native account identity is unavailable.");
                snapshot(statement -> {
                    org.telegram.SQLite.SQLitePreparedStatement query=storage.getDatabase().executeFast(statement);
                    try { query.step(); } finally { query.dispose(); }
                },privateDestination);
                result=inspect(privateDestination,expectedOwner,self);
                if(!expectedOwner.equals(MonogramController.get(account).accountJid()) || self!=UserConfig.getInstance(account).getClientUserId())
                    throw new IOException("The backup account changed during its native snapshot.");
            } catch(Exception failure) { error=failure;if(ownsDestination) SQLiteDatabase.deleteDatabase(privateDestination); }
            completion.done(error==null ? result : null,error);
        });
    }
    /** Bundled native SQLite is 3.39.3; VACUUM INTO includes committed WAL pages in a standalone file. */
    static void snapshot(Execute execute,File destination) throws Exception {
        if(destination.exists()) throw new IOException("Native snapshot destination already exists.");
        File parent=destination.getParentFile();if(parent==null || !parent.isDirectory()) throw new IOException("Native snapshot directory is unavailable.");
        boolean complete=false;
        try {
            execute.sql("VACUUM INTO '"+destination.getAbsolutePath().replace("'","''")+"'");
            if(!destination.isFile() || destination.length()==0) throw new IOException("Native snapshot was not created.");
            try(FileOutputStream output=new FileOutputStream(destination,true)) { output.getFD().sync(); }
            complete=true;
        } finally { if(!complete) SQLiteDatabase.deleteDatabase(destination); }
    }
    static Metadata inspect(File file,String owner,long self) throws Exception {
        if(owner==null || owner.indexOf('/')>=0 || !org.jxmpp.jid.impl.JidCreate.entityBareFrom(owner).toString().equals(owner) || self<=0)
            throw new IOException("Invalid native backup account binding.");
        for(String suffix:new String[]{"-wal","-shm","-journal"}) if(new File(file.getPath()+suffix).exists())
            throw new IOException("Portable native snapshots must be standalone database files.");
        try(SQLiteDatabase database=SQLiteDatabase.openDatabase(file.getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY)) {
            integrity(database);
            if(database.getVersion()!=MessagesStorage.LAST_DB_VERSION) throw new IOException("Native backup version is unsupported by this app.");
            requireColumns(database,"messages_v2",new String[]{"mid","uid","data","send_state","out","custom_params"});
            requireColumns(database,"scheduled_messages_v2",new String[]{"mid","uid","data","send_state"});
            requireColumns(database,"randoms_v2",new String[]{"random_id","mid","uid"});
            return new Metadata(owner,self,database.getVersion(),schema(database),
                    count(database,"SELECT count(*) FROM messages_v2 WHERE mid<0 AND out=1 AND send_state=1"),
                    count(database,"SELECT count(*) FROM messages_v2 WHERE mid<0 AND out=1 AND send_state=2"),
                    count(database,"SELECT count(*) FROM scheduled_messages_v2"));
        }
    }
    /** Stage only. A separate cold-start commit installs this file; this method never opens a live account. */
    static Metadata prepareRestore(File snapshot,Metadata metadata,String expectedOwner,File trustedEmptyTemplate,File destination,
            Map<String,String> mediaPaths) throws Exception {
        if(!expectedOwner.equals(metadata.owner)) throw new IOException("The native outbox belongs to a different account.");
        Metadata verified=inspect(snapshot,expectedOwner,metadata.self);
        if(!metadata.schema.equals(verified.schema) || metadata.version!=verified.version || metadata.pending!=verified.pending
                || metadata.failed!=verified.failed || metadata.scheduled!=verified.scheduled) throw new IOException("Native backup metadata does not match its contents.");
        try(SQLiteDatabase template=SQLiteDatabase.openDatabase(trustedEmptyTemplate.getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY)) {
            if(template.getVersion()!=metadata.version || !schema(template).equals(metadata.schema)) throw new IOException("Native backup schema differs from the trusted empty-slot template.");
            for(String table:new String[]{"messages_v2","scheduled_messages_v2","dialogs","users","chats"})
                if(count(template,"SELECT count(*) FROM "+table)!=0) throw new IOException("Native restore template is not empty.");
        }
        if(destination.exists()) throw new IOException("Native restore staging already exists.");
        boolean success=false;
        try {
            copy(snapshot,destination);
            try(SQLiteDatabase database=SQLiteDatabase.openDatabase(destination.getAbsolutePath(),null,SQLiteDatabase.OPEN_READWRITE)) {
                database.beginTransaction();
                try {
                    remapMessages(database,"messages_v2",metadata.self,mediaPaths);
                    remapMessages(database,"scheduled_messages_v2",metadata.self,mediaPaths);
                    database.setTransactionSuccessful();
                } finally { database.endTransaction(); }
                integrity(database);
            }
            Metadata restored=inspect(destination,expectedOwner,metadata.self);success=true;return restored;
        } finally { if(!success) SQLiteDatabase.deleteDatabase(destination); }
    }
    /** Local files referenced by queued/failed/scheduled messages must be copied before declaring the backup recoverable. */
    static Set<String> pendingMedia(File snapshot,long self) throws Exception {
        Set<String> paths=new LinkedHashSet<>();
        try(SQLiteDatabase database=SQLiteDatabase.openDatabase(snapshot.getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY)) {
            for(String table:new String[]{"messages_v2","scheduled_messages_v2"}) {
                String where=table.equals("messages_v2") ? " WHERE mid<0 AND out=1 AND send_state<>0" : "";
                try(Cursor rows=database.rawQuery("SELECT mid,uid,send_state,rowid,length(data) FROM "+table+where,null)) {
                    while(rows.moveToNext()) {
                        TLRPC.Message message=message(database,table,rows,self);
                        paths.addAll(localPaths(message));
                    }
                }
            }
        }
        return paths;
    }
    private static void remapMessages(SQLiteDatabase database,String table,long self,Map<String,String> paths) throws Exception {
        try(Cursor rows=database.rawQuery("SELECT mid,uid,send_state,rowid,length(data) FROM "+table+" WHERE data IS NOT NULL",null)) {
            while(rows.moveToNext()) {
                TLRPC.Message message=message(database,table,rows,self);boolean changed=false;
                boolean pending=table.equals("scheduled_messages_v2") || message.id<0 && message.out && message.send_state!=0;
                if(pending) for(String required:localPaths(message)) {
                    String replacement=paths.get(required);
                    if(replacement==null || !replacement.startsWith("/") || replacement.indexOf('\0')>=0)
                        throw new IOException("A queued attachment is missing its portable media mapping.");
                }
                if(paths.containsKey(message.attachPath)) { message.attachPath=paths.get(message.attachPath);changed=true; }
                if(message.pollMediaAttachPaths!=null) for(int i=0;i<message.pollMediaAttachPaths.size();i++) {
                    String old=message.pollMediaAttachPaths.valueAt(i);
                    if(paths.containsKey(old)) { message.pollMediaAttachPaths.setValueAt(i,paths.get(old));changed=true; }
                }
                if(message.params!=null) {
                    String old=message.params.get("originalPath");if(paths.containsKey(old)) { message.params.put("originalPath",paths.get(old));changed=true; }
                    String video=message.params.get("ve");
                    if(video!=null) {
                        VideoEditedInfo info=new VideoEditedInfo();
                        if(!info.parseString(video)) throw new IOException("Video metadata could not be remapped safely.");
                        if(paths.containsKey(info.originalPath)) { info.originalPath=paths.get(info.originalPath);message.params.put("ve",info.getString());changed=true; }
                    }
                }
                if(changed) {
                    SerializedData data=new SerializedData();
                    try { // Native TL_message serializers already append writeAttachPath, including params.
                        message.serializeToStream(data);ContentValues values=new ContentValues();values.put("data",data.toByteArray());
                        if(database.update(table,values,"mid=? AND uid=?",new String[]{Integer.toString(message.id),Long.toString(message.dialog_id)})!=1) throw new IOException("Native message changed during restore staging.");
                    } finally { data.cleanup(); }
                }
            }
        }
    }
    private static TLRPC.Message message(SQLiteDatabase database,String table,Cursor rows,long self) throws IOException {
        if(rows.isNull(4)) throw new IOException("Native queued message is missing its serialized data.");
        byte[] bytes=MonogramBackupDatabase.blob(database,table,"data",rows.getLong(3));
        SerializedData data=new SerializedData(bytes);
        try {
            TLRPC.Message message=TLRPC.Message.TLdeserialize(data,data.readInt32(true),true);
            if(message==null || rows.getLong(0)<Integer.MIN_VALUE || rows.getLong(0)>Integer.MAX_VALUE) throw new IOException("Invalid native message identifier.");
            message.id=rows.getInt(0);message.dialog_id=rows.getLong(1);message.send_state=rows.getInt(2);message.readAttachPath(data,self);return message;
        } catch(RuntimeException invalid) { throw new IOException("Native message could not be decoded for restore.",invalid); }
        finally { data.cleanup(); }
    }
    private static Set<String> localPaths(TLRPC.Message message) throws IOException {
        Set<String> paths=new LinkedHashSet<>();addPath(paths,message.attachPath);
        if(message.pollMediaAttachPaths!=null) for(int i=0;i<message.pollMediaAttachPaths.size();i++) addPath(paths,message.pollMediaAttachPaths.valueAt(i));
        if(message.params!=null) {
            addPath(paths,message.params.get("originalPath"));String video=message.params.get("ve");
            if(video!=null) {
                VideoEditedInfo info=new VideoEditedInfo();if(!info.parseString(video)) throw new IOException("Queued video metadata could not be decoded.");
                addPath(paths,info.originalPath);
            }
        }
        return paths;
    }
    private static void addPath(Set<String> paths,String value) {
        if(value!=null && (value.startsWith("/") || value.startsWith("content://"))) paths.add(value);
    }
    private static void requirePrivate(File file) throws IOException {
        String root=ApplicationLoader.applicationContext.getNoBackupFilesDir().getCanonicalPath()+File.separator;
        if(!file.getCanonicalPath().startsWith(root)) throw new IOException("Native backup staging must be inside app-private no_backup storage.");
    }
    private static void requireColumns(SQLiteDatabase database,String table,String[] required) throws IOException {
        Set<String> columns=new HashSet<>();try(Cursor rows=database.rawQuery("PRAGMA table_info("+table+")",null)) { while(rows.moveToNext()) columns.add(rows.getString(1)); }
        if(!columns.containsAll(Arrays.asList(required))) throw new IOException("Native backup is missing required durable message columns.");
    }
    private static int count(SQLiteDatabase database,String sql) throws IOException {
        try(Cursor rows=database.rawQuery(sql,null)) { if(!rows.moveToFirst()) throw new IOException("Native backup count is unavailable.");return rows.getInt(0); }
    }
    private static void integrity(SQLiteDatabase database) throws IOException {
        try(Cursor rows=database.rawQuery("PRAGMA quick_check",null)) { if(!rows.moveToFirst() || !"ok".equals(rows.getString(0)) || rows.moveToNext()) throw new IOException("Native backup failed its integrity check."); }
    }
    private static String schema(SQLiteDatabase database) throws Exception {
        MessageDigest hash=MessageDigest.getInstance("SHA-256");
        try(Cursor rows=database.rawQuery("SELECT type,name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' AND sql IS NOT NULL ORDER BY type,name",null)) {
            while(rows.moveToNext()) for(int i=0;i<3;i++) { hash.update(rows.getString(i).getBytes(StandardCharsets.UTF_8));hash.update((byte)0); }
        }
        StringBuilder result=new StringBuilder();for(byte octet:hash.digest()) result.append(String.format(Locale.ROOT,"%02x",octet&255));return result.toString();
    }
    private static void copy(File source,File target) throws IOException {
        try(InputStream input=new BufferedInputStream(new FileInputStream(source));FileOutputStream output=new FileOutputStream(target)) {
            byte[] buffer=new byte[16384];int count;while((count=input.read(buffer))!=-1) output.write(buffer,0,count);output.getFD().sync();
        }
    }
}
