package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramBackupDatabaseTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private SQLiteDatabase database(String name,String... schema) throws Exception {
        SQLiteDatabase db=SQLiteDatabase.openOrCreateDatabase(new File(temporary.getRoot(),name),null);
        for(String sql:schema) db.execSQL(sql);db.setVersion(1);return db;
    }
    private static final String RECORDS="CREATE TABLE records(owner TEXT NOT NULL,device INTEGER NOT NULL,kind INTEGER NOT NULL,peer TEXT NOT NULL,id INTEGER NOT NULL,value BLOB NOT NULL,PRIMARY KEY(owner,device,kind,peer,id))";
    @Test public void sharedLegacySnapshotFiltersOwnerAndRestoresLargeIdentityRecordsWithoutCursorWindowLoss() throws Exception {
        byte[] payload=new byte[3*1024*1024];new java.util.Random(97).nextBytes(payload);
        File exported=new File(temporary.getRoot(),"portable.db");
        try(SQLiteDatabase source=database("source.db",RECORDS)) {
            for(String owner:new String[]{"alice@example.org","bob@example.org"}) {
                ContentValues row=new ContentValues();row.put("owner",owner);row.put("device",123);row.put("kind",4);row.put("peer","carol@example.org");row.put("id",47);row.put("value",payload);source.insertOrThrow("records",null,row);
            }
            MonogramBackupDatabase.snapshot(source,exported,MonogramBackupDatabase.Scope.LEGACY,"alice@example.org",value->{throw new AssertionError();});
        }
        try(SQLiteDatabase staged=SQLiteDatabase.openDatabase(exported.getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY);
                SQLiteDatabase target=database("target.db",RECORDS)) {
            MonogramBackupDatabase.restoreIntoEmpty(staged,target,MonogramBackupDatabase.Scope.LEGACY,"alice@example.org",value->{throw new AssertionError();});
            try(Cursor rows=target.rawQuery("SELECT owner,length(value),hex(substr(value,1,16)) FROM records",null)) {
                assertTrue(rows.moveToFirst());assertEquals("alice@example.org",rows.getString(0));assertEquals(payload.length,rows.getInt(1));assertFalse(rows.moveToNext());
            }
            ByteArrayOutputStream restored=new ByteArrayOutputStream();
            for(int offset=0;offset<payload.length;offset+=512*1024) try(Cursor chunk=target.rawQuery("SELECT substr(value,?,?) FROM records",new String[]{Integer.toString(offset+1),Integer.toString(512*1024)})) {
                assertTrue(chunk.moveToFirst());restored.write(chunk.getBlob(0));
            }
            assertArrayEquals(payload,restored.toByteArray());
            assertThrows(IOException.class,()->MonogramBackupDatabase.restoreIntoEmpty(staged,target,MonogramBackupDatabase.Scope.LEGACY,"alice@example.org",value->value));
        }
    }
    @Test public void historyRewrapsBookmarkPayloadAndPreservesAutoincrementHighWaterMark() throws Exception {
        String[] schema={"CREATE TABLE peers(id INTEGER PRIMARY KEY AUTOINCREMENT,jid TEXT)","CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT,body TEXT)","CREATE TABLE room_bookmarks(room INTEGER PRIMARY KEY,payload TEXT)"};
        File exported=new File(temporary.getRoot(),"history.db");
        try(SQLiteDatabase source=database("source-history.db",schema)) {
            source.execSQL("INSERT INTO messages(id,body) VALUES(999,'removed')");source.execSQL("DELETE FROM messages");
            source.execSQL("INSERT INTO room_bookmarks VALUES(1,'old-phone:bookmark')");
            MonogramBackupDatabase.snapshot(source,exported,MonogramBackupDatabase.Scope.HISTORY,"alice@example.org",value->value.substring(10));
        }
        try(SQLiteDatabase staged=SQLiteDatabase.openDatabase(exported.getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY);SQLiteDatabase target=database("target-history.db",schema)) {
            MonogramBackupDatabase.restoreIntoEmpty(staged,target,MonogramBackupDatabase.Scope.HISTORY,"alice@example.org",value->"new-phone:"+value);
            try(Cursor rows=target.rawQuery("SELECT payload FROM room_bookmarks",null)) { assertTrue(rows.moveToFirst());assertEquals("new-phone:bookmark",rows.getString(0)); }
            ContentValues message=new ContentValues();message.put("body","after restore");assertEquals(1000,target.insertOrThrow("messages",null,message));
        }
    }
    @Test public void foreignOwnerAndUnrecognizedSchemaNeverModifyEmptyRestoreDestination() throws Exception {
        try(SQLiteDatabase staged=database("foreign.db",RECORDS);SQLiteDatabase target=database("empty.db",RECORDS)) {
            staged.execSQL("INSERT INTO records VALUES('bob@example.org',123,0,'',0,x'01')");
            assertThrows(IOException.class,()->MonogramBackupDatabase.restoreIntoEmpty(staged,target,MonogramBackupDatabase.Scope.LEGACY,"alice@example.org",value->value));
            try(Cursor rows=target.rawQuery("SELECT count(*) FROM records",null)) { assertTrue(rows.moveToFirst());assertEquals(0,rows.getInt(0)); }
            staged.execSQL("ALTER TABLE records ADD COLUMN surprise TEXT");
            assertThrows(IOException.class,()->MonogramBackupDatabase.restoreIntoEmpty(staged,target,MonogramBackupDatabase.Scope.LEGACY,"alice@example.org",value->value));
        }
    }
}
