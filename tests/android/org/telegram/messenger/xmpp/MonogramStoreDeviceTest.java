package org.telegram.messenger.xmpp;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.DatabaseErrorHandler;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.negzero.monogram.xmpp.MessageFixture;
import java.io.File;
import java.util.ArrayList;
import org.telegram.tgnet.TLRPC;

/** Runs under app_process against a disposable database, without starting the application or network. */
public final class MonogramStoreDeviceTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]); root.mkdirs();
        Context context = new ContextWrapper(null) {
            @Override public File getDatabasePath(String name) { return new File(root, name); }
            @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler handler) {
                return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).getPath(), factory, handler);
            }
            @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory) {
                return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory);
            }
        };
        File database = context.getDatabasePath("migration.db");
        check(!database.exists(), "Test requires a fresh disposable database");
        try {
            try (SQLiteDatabase old = SQLiteDatabase.openOrCreateDatabase(database, null)) {
                old.execSQL("CREATE TABLE peers (id INTEGER PRIMARY KEY AUTOINCREMENT, jid TEXT NOT NULL UNIQUE)");
                old.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, peer INTEGER NOT NULL, stanza TEXT NOT NULL, body TEXT NOT NULL, encrypted INTEGER NOT NULL, outgoing INTEGER NOT NULL, time INTEGER NOT NULL, UNIQUE(peer,stanza,outgoing))");
                old.execSQL("INSERT INTO peers VALUES (1,'alice@example.org'),(2,'bob@example.org')");
                old.execSQL("INSERT INTO messages VALUES (1,2,'existing','known plaintext',1,0,200)");
                old.setVersion(1);
            }
            try (MonogramStore store = new MonogramStore(context, "migration.db")) {
                check(store.getReadableDatabase().getVersion() == 3, "Schema must migrate");
                check(store.history(2, 1, 0, 100).get(0).id == 1, "Migration must preserve IDs");
                check(store.history(2, 1, 0, 100).get(0).message.equals("known plaintext"), "Migration must preserve text");
                check(store.save(MessageFixture.message("existing", "origin", "archive", null, false, false, 200000)) == 0,
                        "Unreadable replay must match the migrated ID and retain plaintext");
                check(store.save(MessageFixture.message("rewritten", "origin", "archive", null, false, false, 200000)) == 0,
                        "Origin/archive aliases must match across rewritten stanza IDs");
                int older = store.save(MessageFixture.message("older", null, "older-archive", "older", true, false, 100000));
                int newest = store.save(MessageFixture.message("newer", null, "newer-archive", "newer", true, false, 300000));
                ArrayList<TLRPC.Message> all = store.history(2, 1, 0, 100);
                check(all.size() == 3 && all.get(0).id == newest && all.get(1).id == 1 && all.get(2).id == older,
                        "Late imports must sort by time, not insertion ID");
                check(store.history(2, 1, 1, 1).get(0).id == older, "Backward pagination must use the timeline anchor");
                check(store.history(2, 1, 1, 1, true).get(0).id == newest, "Forward pagination must use the timeline anchor");
                int unreadable = store.save(MessageFixture.message("locked", null, "locked-archive", null, false, false, 400000));
                check(store.history(2, 1, 0, 1).get(0).message.contains("unavailable"), "Missing keys need an honest placeholder");
                check(store.save(MessageFixture.message("locked", null, "locked-archive", "recovered", true, false, 400000)) == unreadable,
                        "A later successful decryption must update the same local message");
                check(store.history(2, 1, 0, 1).get(0).message.equals("recovered"), "Recovered text must replace the placeholder");
                check(store.save(MessageFixture.message("existing", null, "sent-archive", "sent carbon", true, true, 500000)) > 0,
                        "Incoming and outgoing IDs must remain separate");
                store.saveArchive(MessageFixture.page(MessageFixture.message("tied-new", null, "tied-new", "tied newer", true, false, 600000)), false);
                store.saveArchive(MessageFixture.page(MessageFixture.message("tied-old", null, "tied-old", "tied older", true, false, 600000)), true);
                all = store.history(2, 1, 0, 100);
                check(all.get(0).message.equals("tied newer") && all.get(1).message.equals("tied older"), "MAM page order must break timestamp ties");
                check(store.history(2, 1, all.get(0).id, 1).get(0).id == all.get(1).id, "Paging must retain archive order at equal timestamps");
                check(store.archiveCursor(true).equals("tied-old") && store.archiveCursor(false).equals("tied-new"), "Archive cursors must persist with the page");
                try {
                    store.saveArchive(MessageFixture.page(
                            MessageFixture.message("rollback", null, "rollback", "must roll back", true, false, 700000),
                            MessageFixture.message("existing", "origin", "older-archive", "conflict", true, false, 800000)), false);
                    throw new AssertionError("Conflicting aliases must fail");
                } catch (IllegalStateException expected) { /* Both page data and cursor must roll back. */ }
                check(store.history(2, 1, 0, 100).size() == 7, "Failed pages must not partially persist");
                check(store.archiveCursor(false).equals("tied-new"), "Failed pages must retain the previous cursor");
            }
            try (MonogramStore reopened = new MonogramStore(context, "migration.db")) {
                check(reopened.history(2, 1, 0, 100).size() == 7, "Messages must survive reopen");
                check(reopened.save(MessageFixture.message("rewritten-again", "origin", "archive", null, false, false, 200000)) == 0,
                        "ID aliases must survive reopen");
                int firstResource = reopened.save(MessageFixture.messageFrom("counter-1", null, null, "phone", true, false, 900000, "phone"));
                int secondResource = reopened.save(MessageFixture.messageFrom("counter-1", null, null, "desktop", true, false, 900001, "desktop"));
                check(firstResource > 0 && secondResource > 0 && firstResource != secondResource, "Different sender resources may reuse client stanza IDs");
                check(reopened.save(MessageFixture.messageFrom("counter-1", null, null, "desktop", true, false, 900001, "desktop")) == 0, "The same resource replay must deduplicate");
                reopened.resetArchiveCursor();
                check(reopened.archiveCursor(false) == null && !reopened.archiveComplete(), "Expired archive cursors must reset");
                check(reopened.history(2, 1, 0, 100).size() == 9, "Resetting cursors must preserve messages");
                reopened.clear();
                check(reopened.peers().isEmpty(), "Logout must clear peers");
                try (Cursor c = reopened.getReadableDatabase().rawQuery("SELECT count(*) FROM message_ids", null)) {
                    check(c.moveToFirst() && c.getInt(0) == 0, "Logout must clear ID aliases");
                }
            }
            File version2 = context.getDatabasePath("version2.db");
            try {
                try (SQLiteDatabase old = SQLiteDatabase.openOrCreateDatabase(version2, null)) {
                    old.execSQL("CREATE TABLE peers (id INTEGER PRIMARY KEY AUTOINCREMENT, jid TEXT NOT NULL UNIQUE)");
                    old.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, peer INTEGER NOT NULL, stanza TEXT NOT NULL, body TEXT NOT NULL, encrypted INTEGER NOT NULL, outgoing INTEGER NOT NULL, time INTEGER NOT NULL, readable INTEGER NOT NULL DEFAULT 1, time_ms INTEGER NOT NULL DEFAULT 0, archive_order INTEGER NOT NULL DEFAULT 0, UNIQUE(peer,stanza,outgoing))");
                    old.execSQL("CREATE TABLE message_ids (peer INTEGER NOT NULL, outgoing INTEGER NOT NULL, kind TEXT NOT NULL, value TEXT NOT NULL, message INTEGER NOT NULL, PRIMARY KEY(peer,outgoing,kind,value))");
                    old.execSQL("CREATE TABLE archive_state (scope TEXT PRIMARY KEY, oldest TEXT, newest TEXT, complete INTEGER NOT NULL DEFAULT 0)");
                    old.execSQL("INSERT INTO peers VALUES (1,'alice@example.org'),(2,'bob@example.org')");
                    old.execSQL("INSERT INTO messages VALUES (1,2,'local-id','existing v2',1,0,200,1,200000,0)");
                    old.execSQL("INSERT INTO message_ids VALUES (2,0,'stanza','old-wire-id',1)");
                    old.setVersion(2);
                }
                try (MonogramStore migrated = new MonogramStore(context, "version2.db")) {
                    check(migrated.save(MessageFixture.messageFrom("old-wire-id", null, "v2-archive", null, false, false, 200000, "phone")) == 0,
                            "Schema 2 migration must preserve its existing wire-ID aliases");
                    check(migrated.history(2, 1, 0, 100).size() == 1 && migrated.history(2, 1, 0, 1).get(0).message.equals("existing v2"),
                            "Schema 2 migration must preserve known plaintext");
                }
            } finally { SQLiteDatabase.deleteDatabase(version2); }
            System.out.println("PASS: migration, preservation, aliases, unreadable replay, timeline paging, recovery, reopen, logout");
        } finally { SQLiteDatabase.deleteDatabase(database); }
    }
}
