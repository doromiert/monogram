package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.*;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.VideoEditedInfo;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLRPC;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows={MonogramBackupNativeStoreTest.App.class})
public class MonogramBackupNativeStoreTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    @Implements(value=org.telegram.messenger.ApplicationLoader.class,isInAndroidSdk=false)
    public static class App {
        @Implementation public static void __staticInitializer__() { }
        @Implementation public static boolean isAndroidTestEnvironment() { return false; }
    }
    private static final String[] SCHEMA={
            "CREATE TABLE messages_v2(mid INTEGER,uid INTEGER,data BLOB,send_state INTEGER,out INTEGER,custom_params BLOB,PRIMARY KEY(mid,uid))",
            "CREATE TABLE scheduled_messages_v2(mid INTEGER,uid INTEGER,data BLOB,send_state INTEGER,PRIMARY KEY(mid,uid))",
            "CREATE TABLE randoms_v2(random_id INTEGER,mid INTEGER,uid INTEGER,PRIMARY KEY(random_id,mid,uid))",
            "CREATE TABLE dialogs(did INTEGER PRIMARY KEY)","CREATE TABLE users(uid INTEGER PRIMARY KEY)","CREATE TABLE chats(uid INTEGER PRIMARY KEY)"};
    private SQLiteDatabase database(File file) {
        SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(file,null);for(String sql:SCHEMA) db.execSQL(sql);db.setVersion(MessagesStorage.LAST_DB_VERSION);return db;
    }
    private byte[] message(int id,int state,String attachment,String original,String video) {
        TLRPC.Message message=new TLRPC.TL_message();message.id=id;message.out=true;message.send_state=state;
        message.from_id=new TLRPC.TL_peerUser();message.from_id.user_id=1;message.flags|=256;
        message.peer_id=new TLRPC.TL_peerChat();message.peer_id.chat_id=7;message.dialog_id=-7;message.date=100;
        message.message="queued body";message.attachPath=attachment;message.params=new HashMap<>();
        message.params.put("monogram-encryption","OMEMO2");message.params.put("unchanged","opaque local param");
        if(original!=null) message.params.put("originalPath",original);if(video!=null) message.params.put("ve",video);
        SerializedData output=new SerializedData();try { message.serializeToStream(output);return output.toByteArray(); } finally { output.cleanup(); }
    }
    private TLRPC.Message decode(byte[] bytes,int id,int state) {
        SerializedData input=new SerializedData(bytes);
        try { TLRPC.Message message=TLRPC.Message.TLdeserialize(input,input.readInt32(true),true);message.id=id;message.send_state=state;message.readAttachPath(input,1);return message; }
        finally { input.cleanup(); }
    }
    private void insert(SQLiteDatabase db,String table,int id,int state,byte[] message) {
        ContentValues values=new ContentValues();values.put("mid",id);values.put("uid",-7);values.put("data",message);values.put("send_state",state);
        if(table.equals("messages_v2")) { values.put("out",1);values.put("custom_params",new byte[]{11,12,13}); }
        db.insertOrThrow(table,null,values);db.execSQL("INSERT INTO randoms_v2 VALUES(?,?,-7)",new Object[]{9000-id,id});
    }
    @Test public void walSnapshotAndColdStagingPreserveDurableRowsIdsModesAndMediaMetadata() throws Exception {
        File live=new File(temporary.getRoot(),"native.db"),snapshot=new File(temporary.getRoot(),"outbox.db"),template=new File(temporary.getRoot(),"empty.db"),restored=new File(temporary.getRoot(),"restored.db");
        String oldAttachment="/old/converted.mp4",oldOriginal="/old/source-video.mp4";
        VideoEditedInfo video=new VideoEditedInfo();video.originalPath=oldOriginal;video.originalWidth=320;video.originalHeight=240;video.resultWidth=160;video.resultHeight=120;video.bitrate=123456;video.startTime=17;video.endTime=91;
        try(SQLiteDatabase source=database(live)) {
            source.enableWriteAheadLogging();
            insert(source,"messages_v2",-1,1,message(-1,1,oldAttachment,oldOriginal,video.getString()));
            insert(source,"messages_v2",-2,2,message(-2,2,"/old/failed.ogg",null,null));
            insert(source,"scheduled_messages_v2",-3,1,message(-3,1,"/old/scheduled.webp",null,null));
            MonogramBackupNativeStore.snapshot(source::execSQL,snapshot);
        }
        try(SQLiteDatabase ignored=database(template)) { }
        MonogramBackupNativeStore.Metadata metadata=MonogramBackupNativeStore.inspect(snapshot,"alice@example.org",1);
        assertEquals(1,metadata.pending);assertEquals(1,metadata.failed);assertEquals(1,metadata.scheduled);
        assertEquals(new HashSet<>(Arrays.asList(oldAttachment,oldOriginal,"/old/failed.ogg","/old/scheduled.webp")),MonogramBackupNativeStore.pendingMedia(snapshot,1));
        Map<String,String> paths=new HashMap<>();for(String path:MonogramBackupNativeStore.pendingMedia(snapshot,1)) paths.put(path,path.replace("/old/","/new/"));
        MonogramBackupNativeStore.prepareRestore(snapshot,metadata,"alice@example.org",template,restored,paths);
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(restored.getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY)) {
            try(Cursor rows=db.rawQuery("SELECT mid,uid,send_state,data,custom_params FROM messages_v2 ORDER BY mid DESC",null)) {
                assertTrue(rows.moveToFirst());assertEquals(-1,rows.getInt(0));assertEquals(-7,rows.getLong(1));assertEquals(1,rows.getInt(2));assertArrayEquals(new byte[]{11,12,13},rows.getBlob(4));
                TLRPC.Message message=decode(rows.getBlob(3),-1,1);assertEquals("/new/converted.mp4",message.attachPath);assertEquals("/new/source-video.mp4",message.params.get("originalPath"));
                assertEquals("OMEMO2",message.params.get("monogram-encryption"));assertEquals("opaque local param",message.params.get("unchanged"));
                VideoEditedInfo converted=new VideoEditedInfo();assertTrue(converted.parseString(message.params.get("ve")));assertEquals("/new/source-video.mp4",converted.originalPath);assertEquals(123456,converted.bitrate);assertEquals(17,converted.startTime);assertEquals(91,converted.endTime);
                assertTrue(rows.moveToNext());assertEquals(-2,rows.getInt(0));assertEquals(2,rows.getInt(2));assertEquals("/new/failed.ogg",decode(rows.getBlob(3),-2,2).attachPath);
            }
            try(Cursor rows=db.rawQuery("SELECT count(*),sum(random_id) FROM randoms_v2",null)) { assertTrue(rows.moveToFirst());assertEquals(3,rows.getInt(0));assertEquals(27006,rows.getInt(1)); }
            try(Cursor rows=db.rawQuery("SELECT send_state,data FROM scheduled_messages_v2 WHERE mid=-3",null)) { assertTrue(rows.moveToFirst());assertEquals(1,rows.getInt(0));assertEquals("/new/scheduled.webp",decode(rows.getBlob(1),-3,1).attachPath); }
        }
    }
    @Test public void missingPendingMappingWrongOwnerAndForeignSchemaFailBeforeReplacingAnyLiveData() throws Exception {
        File snapshot=new File(temporary.getRoot(),"snapshot.db"),template=new File(temporary.getRoot(),"template.db");
        try(SQLiteDatabase source=database(snapshot)) { insert(source,"messages_v2",-1,1,message(-1,1,"/old/photo.webp",null,null)); }
        try(SQLiteDatabase ignored=database(template)) { }
        MonogramBackupNativeStore.Metadata metadata=MonogramBackupNativeStore.inspect(snapshot,"alice@example.org",1);
        File destination=new File(temporary.getRoot(),"restore.db");
        assertThrows(IOException.class,()->MonogramBackupNativeStore.prepareRestore(snapshot,metadata,"alice@example.org",template,destination,Collections.emptyMap()));assertFalse(destination.exists());
        assertThrows(IOException.class,()->MonogramBackupNativeStore.prepareRestore(snapshot,metadata,"bob@example.org",template,destination,Collections.emptyMap()));assertFalse(destination.exists());
        try(SQLiteDatabase source=SQLiteDatabase.openDatabase(snapshot.getAbsolutePath(),null,SQLiteDatabase.OPEN_READWRITE)) {
            source.execSQL("CREATE TRIGGER erase_messages AFTER UPDATE ON messages_v2 BEGIN DELETE FROM messages_v2; END");
        }
        MonogramBackupNativeStore.Metadata modified=MonogramBackupNativeStore.inspect(snapshot,"alice@example.org",1);
        assertThrows(IOException.class,()->MonogramBackupNativeStore.prepareRestore(snapshot,modified,"alice@example.org",template,destination,Collections.singletonMap("/old/photo.webp","/new/photo.webp")));assertFalse(destination.exists());
        byte[] original={1,2,3};java.nio.file.Files.write(destination.toPath(),original);
        assertThrows(IOException.class,()->MonogramBackupNativeStore.snapshot(sql->{throw new AssertionError();},destination));assertArrayEquals(original,java.nio.file.Files.readAllBytes(destination.toPath()));
    }
}
