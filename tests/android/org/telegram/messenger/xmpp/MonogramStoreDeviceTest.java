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
    static void legacyAttachments(SQLiteDatabase db) {
        legacyProtocols(db);
        db.execSQL("ALTER TABLE attachments RENAME TO caption_attachments");
        db.execSQL("CREATE TABLE attachments (message INTEGER PRIMARY KEY, url TEXT NOT NULL, path TEXT, media BLOB)");
        db.execSQL("INSERT INTO attachments SELECT message,url,path,media FROM caption_attachments");
        db.execSQL("DROP TABLE caption_attachments");
    }
    static void legacyRoomDescription(SQLiteDatabase db) {
        // Remove newer tables/triggers before ALTER TABLE validates the historical schema.
        db.execSQL("DROP TABLE IF EXISTS call_events");
        db.execSQL("DROP TRIGGER IF EXISTS clear_message_formatting");
        db.execSQL("DROP TRIGGER IF EXISTS delete_message_formatting");
        db.execSQL("DROP TABLE IF EXISTS message_formatting");
        boolean found=false;
        try(android.database.Cursor rows=db.rawQuery("PRAGMA table_info(rooms)",null)) {
            while(rows.moveToNext()) if("description".equals(rows.getString(1))) found=true;
        }
        if(found) db.execSQL("ALTER TABLE rooms DROP COLUMN description");
    }
    static void legacyDeletions(SQLiteDatabase db) {
        legacyRoomDescription(db);
        db.execSQL("DROP TABLE message_retractions");
        db.execSQL("DROP TABLE retraction_sources");
        db.execSQL("ALTER TABLE messages DROP COLUMN sender_fingerprint");
        db.execSQL("ALTER TABLE messages DROP COLUMN deleted");
    }
    static void legacyProtocols(SQLiteDatabase db) {
        legacyDeletions(db);
        db.execSQL("DROP TABLE IF EXISTS rooms");
        db.execSQL("DROP TABLE IF EXISTS room_message_actors");
        db.execSQL("ALTER TABLE messages DROP COLUMN encryption");
        db.execSQL("ALTER TABLE message_ids RENAME TO protocol_message_ids");
        db.execSQL("CREATE TABLE message_ids (peer INTEGER NOT NULL,outgoing INTEGER NOT NULL,kind TEXT NOT NULL,value TEXT NOT NULL,message INTEGER NOT NULL,PRIMARY KEY(peer,outgoing,kind,value))");
        db.execSQL("INSERT INTO message_ids SELECT peer,outgoing,kind,value,message FROM protocol_message_ids WHERE namespace=0");
        db.execSQL("DROP TABLE protocol_message_ids");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]); root.mkdirs();
        Context context = new ContextWrapper(null) {
            @Override public File getDatabasePath(String name) { return new File(root, name); }
            @Override public File getFilesDir() { File files = new File(root, "files"); files.mkdirs(); return files; }
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
                check(store.getReadableDatabase().getVersion() == 26, "Schema must migrate");
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
                java.util.Set<Integer> replaced=new java.util.HashSet<>();
                check(store.save(MessageFixture.message("locked", null, "locked-archive", "recovered", true, false, 400000),replaced) == unreadable,
                        "A later successful decryption must update the same local message");
                check(replaced.contains(unreadable), "Recovery must tell the native UI to replace the placeholder");
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
                String url = "aesgcm://example.org/photo.png#" + new String(new char[88]).replace('\0', 'a');
                int attachment = reopened.save(MessageFixture.message("file", null, "file-archive", url, true, false, 910000));
                TLRPC.Message fileMessage = reopened.getMessage(attachment, 1);
                check(MonogramAttachments.has(fileMessage) && fileMessage.message.isEmpty(), "File secrets must not be displayed as text or links");
                check(fileMessage.media.document != null, "Undownloaded files must offer a document download");
                File cached = reopened.attachmentFile(url); cached.getParentFile().mkdirs();
                android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(2, 3, android.graphics.Bitmap.Config.ARGB_8888);
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(cached)) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); }
                bitmap.recycle();
                reopened.attach(attachment, url, cached, MonogramAttachments.media(attachment, 910, cached, "photo.png"));
                fileMessage = reopened.getMessage(attachment, 1);
                check(fileMessage.media.photo != null && fileMessage.media.photo.sizes.get(0).w == 2
                        && fileMessage.media.photo.sizes.get(0).h == 3, "Downloaded images must produce photo previews with real dimensions");
                check(new File(fileMessage.attachPath).isFile(), "Preview must refer to the verified local file");
                reopened.close();
                fileMessage = reopened.getMessage(attachment, 1);
                check(fileMessage.media.photo != null && fileMessage.message.isEmpty(), "Media metadata must survive database reopen");
                long alice = reopened.knownPeer("alice@example.org");
                long bob = reopened.knownPeer("bob@example.org");
                java.io.ByteArrayOutputStream avatarBytes = new java.io.ByteArrayOutputStream();
                android.graphics.Bitmap avatarBitmap = android.graphics.Bitmap.createBitmap(600, 300, android.graphics.Bitmap.Config.ARGB_8888);
                avatarBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, avatarBytes); avatarBitmap.recycle();
                com.negzero.monogram.xmpp.XmppProfile profile = new com.negzero.monogram.xmpp.XmppProfile(
                    "<vCard xmlns='vcard-temp'><FN>Bob Example</FN><DESC>Profile bio</DESC><EMAIL><USERID>bob@example.org</USERID></EMAIL></vCard>", "Bobby", avatarBytes.toByteArray());
                reopened.profile(bob, profile);
                check(reopened.user(bob, alice).first_name.equals("Bobby"), "Profiles must supply display names");
                reopened.rosterName(bob, "My friend");
                check(reopened.user(bob, alice).first_name.equals("My friend"), "Local roster names take precedence");
                check(reopened.fullUser(bob, alice).about.equals("Profile bio"), "Native full profiles need the received bio");
                check(reopened.user(bob, alice).photo.photo_big.volume_id == MonogramProfiles.PHOTO_VOLUME, "Avatars need local profile locations");
                File avatarFile = reopened.avatar(bob);
                check(avatarFile.isFile(), "Avatar must be committed to private storage");
                org.telegram.tgnet.SerializedData userBytes = new org.telegram.tgnet.SerializedData();
                reopened.user(bob, alice).serializeToStream(userBytes);
                org.telegram.tgnet.SerializedData userRead = new org.telegram.tgnet.SerializedData(userBytes.toByteArray());
                TLRPC.User cachedUser = TLRPC.User.TLdeserialize(userRead, userRead.readInt32(true), true);
                check(cachedUser.photo.dc_id == Integer.MIN_VALUE && cachedUser.photo.photo_big.volume_id != MonogramProfiles.PHOTO_VOLUME,
                        "Native user-cache serialization must retain the persistent XMPP marker");
                org.telegram.messenger.ImageLocation cachedImage = MonogramProfiles.imageLocation(cachedUser, avatarFile);
                check(cachedImage != null && cachedImage.location != null && cachedImage.location.local_id == bob
                        && cachedImage.location.volume_id == MonogramProfiles.PHOTO_VOLUME && cachedImage.photoId == cachedUser.photo.photo_id
                        && cachedImage.path.equals(avatarFile.getAbsolutePath()),
                        "Reopened avatars must load offline with the native profile gallery metadata");
                check(MonogramProfiles.imageLocation(cachedUser, new File(root, "missing-avatar.png")) == null,
                        "Missing cached avatars must never become Telegram network requests");
                userBytes.cleanup(); userRead.cleanup();
                android.graphics.BitmapFactory.Options avatarBounds = new android.graphics.BitmapFactory.Options(); avatarBounds.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFile(avatarFile.getAbsolutePath(), avatarBounds);
                check(avatarBounds.outWidth <= 512 && avatarBounds.outHeight <= 512, "Decoded avatars must be bounded");
                org.telegram.tgnet.SerializedData fullBytes = new org.telegram.tgnet.SerializedData();
                reopened.fullUser(bob, alice).serializeToStream(fullBytes);
                org.telegram.tgnet.SerializedData fullRead = new org.telegram.tgnet.SerializedData(fullBytes.toByteArray());
                TLRPC.UserFull decoded = TLRPC.UserFull.TLdeserialize(fullRead, fullRead.readInt32(true), true);
                check(decoded.about.equals("Profile bio") && decoded.profile_photo instanceof TLRPC.TL_photo, "Native profile metadata must serialize/reopen");
                org.telegram.messenger.ImageLocation galleryImage = org.telegram.messenger.ImageLocation.getForPhoto(
                        decoded.profile_photo.sizes.get(0), decoded.profile_photo);
                check(galleryImage.location.volume_id == MonogramProfiles.PHOTO_VOLUME && galleryImage.location.local_id == bob,
                        "Cached native gallery photos must retain the private avatar reference after serialization");
                fullBytes.cleanup(); fullRead.cleanup();
                reopened.close();
                try (MonogramStore fresh = new MonogramStore(context, "migration.db")) {
                    check(fresh.profile(bob).field("EMAIL", "USERID").equals("bob@example.org") && fresh.avatar(bob).isFile(), "Profile cache must survive a new store instance");
                    check(fresh.user(bob, alice).first_name.equals("My friend"), "Roster aliases must survive a new store instance");
                }
                check(reopened.user(alice, alice).first_name.equals("alice"), "An unnamed account must use its username, separately from its full Jabber address");
                check(reopened.user(bob, bob).first_name.equals("Bobby"), "Published account names must take precedence over username and roster fallbacks");
                check(reopened.user(alice, bob).first_name.equals("alice@example.org"), "Unnamed contacts must retain their full address to distinguish servers");
                try (MonogramStore isolated = new MonogramStore(context, "isolated.db")) {
                    long isolatedBob = isolated.peer("bob@example.org");
                    check(isolated.profile(isolatedBob) == null && isolated.avatar(isolatedBob) == null, "Accounts must have separate profile caches");
                } finally { SQLiteDatabase.deleteDatabase(context.getDatabasePath("isolated.db")); }
                reopened.profile(bob, profile.avatar(new byte[0], "image/png"));
                check(reopened.avatar(bob) == null && reopened.user(bob, alice).photo instanceof TLRPC.TL_userProfilePhotoEmpty, "Avatar removal must clear the native model");
                reopened.profile(bob, new com.negzero.monogram.xmpp.XmppProfile(profile.xml(), "Bobby", new byte[]{1, 2, 3}));
                check(reopened.avatar(bob) == null, "Malformed image bytes must never become a displayed avatar");
                check(avatarFile.delete(), "Disposable avatar must clean up");
                reopened.resetArchiveCursor();
                check(reopened.archiveCursor(false) == null && !reopened.archiveComplete(), "Expired archive cursors must reset");
                check(reopened.history(2, 1, 0, 100).size() == 10, "Resetting cursors must preserve messages");
                check(cached.delete(), "Disposable media must clean up");
                reopened.clear();
                check(reopened.peers().isEmpty(), "Logout must clear peers");
                check(reopened.profile(bob) == null && reopened.avatar(bob) == null, "Logout must clear cached profile references");
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
                try (SQLiteDatabase old = SQLiteDatabase.openOrCreateDatabase(version2, null)) {
                    legacyProtocols(old); old.execSQL("DROP TABLE attachments");
                    old.execSQL("DROP TABLE profiles");
                    old.execSQL("DROP TABLE corrections"); old.execSQL("DROP TABLE applied_corrections");
                    old.execSQL("DROP TABLE replies"); old.execSQL("DROP TABLE message_senders");
                    String link = "aesgcm://example.org/archived.png#" + new String(new char[88]).replace('\0', 'b');
                    old.execSQL("INSERT INTO messages VALUES (2,2,'archived-file',?,1,0,201,1,201000,0)", new Object[]{link});
                    old.setVersion(3);
                }
                try (MonogramStore migrated = new MonogramStore(context, "version2.db")) {
                    check(migrated.getReadableDatabase().getVersion() == 26, "Schema 3 must migrate to 26");
                    check(migrated.getMessage(1, 1).message.equals("existing v2"), "Schema 3 migration must retain known text");
                    TLRPC.Message archived = migrated.getMessage(2, 1);
                    check(MonogramAttachments.has(archived) && archived.message.isEmpty(),
                            "Schema 3 encrypted file URLs must become attachments without exposing keys");
                }
            } finally { SQLiteDatabase.deleteDatabase(version2); }
            try (SQLiteDatabase previous = SQLiteDatabase.openOrCreateDatabase(database, null)) {
                previous.execSQL("DROP TABLE profiles");
                previous.execSQL("DROP TABLE corrections"); previous.execSQL("DROP TABLE applied_corrections");
                previous.execSQL("DROP TABLE replies"); previous.execSQL("DROP TABLE message_senders");
                previous.execSQL("INSERT INTO peers VALUES (1,'alice@example.org'),(2,'bob@example.org')");
                previous.execSQL("INSERT INTO messages(id,peer,stanza,body,encrypted,outgoing,time,time_ms) VALUES(900,2,'v4-wire','schema 4 preserved',1,0,800,800000)");
                legacyAttachments(previous); previous.setVersion(4);
            }
            try (MonogramStore migrated = new MonogramStore(context, "migration.db")) {
                check(migrated.getReadableDatabase().getVersion() == 26 && migrated.profile(1) == null, "Schema 4 must gain profiles without changing message tables");
                check(migrated.getMessage(900, 1).message.equals("schema 4 preserved"), "Schema 4 messages must survive the profile migration");
            }
            TLRPC.MessageMedia remotePhoto = MonogramAttachments.remoteMedia(1, "photo.jpg", "application/octet-stream", 65537);
            TLRPC.MessageMedia remoteVideo = MonogramAttachments.remoteMedia(2, "clip.mp4", "video/mp4", 65538);
            TLRPC.MessageMedia remoteMusic = MonogramAttachments.remoteMedia(3, "music.flac", "application/octet-stream", 65539);
            check(remotePhoto.photo != null && remotePhoto.photo.sizes.get(0).size == 65537,
                    "HTTP metadata must supply the real plaintext photo size to native presets");
            check(org.telegram.messenger.MessageObject.isVideoDocument(remoteVideo.document), "Native video presets must recognize HTTP videos");
            check(org.telegram.messenger.MessageObject.isMusicDocument(remoteMusic.document), "Native file/music presets must recognize HTTP music");
            check(org.telegram.messenger.MessageObject.getMediaSize(remoteVideo) == 65538
                    && org.telegram.messenger.MessageObject.getMediaSize(remoteMusic) == 65539,
                    "Native download policy must receive each attachment's real size");
            System.out.println("PASS: migration, preservation, aliases, unreadable replay, timeline paging, recovery, profiles, avatars, account isolation, reopen, logout");
        } finally { SQLiteDatabase.deleteDatabase(database); }
    }
}
