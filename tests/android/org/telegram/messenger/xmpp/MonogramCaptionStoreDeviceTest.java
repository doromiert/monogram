package org.telegram.messenger.xmpp;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.negzero.monogram.xmpp.*;
import java.io.File;
import java.nio.file.Files;
import org.jxmpp.jid.impl.JidCreate;
import org.telegram.tgnet.TLRPC;

/** One file/caption identity survives cache updates, replies, replay and schema 7 migration. */
public final class MonogramCaptionStoreDeviceTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        org.telegram.messenger.ApplicationLoader.applicationLoaderInstance = new org.telegram.messenger.ApplicationLoader();
        File root = new File(args[0]); root.mkdirs();
        Context context = new ContextWrapper(null) {
            @Override public File getDatabasePath(String name) { return new File(root, name); }
            @Override public File getFilesDir() { File f = new File(root, "files"); f.mkdirs(); return f; }
            @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory, android.database.DatabaseErrorHandler handler) { return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).getPath(), factory, handler); }
            @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory) { return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory); }
        };
        File database = context.getDatabasePath("captions.db"); check(!database.exists(), "Fresh disposable database required");
        String url = "https://example.org/photo.png", caption = "Photo of us 😀\nSecond line 🦄";
        long self, peer; int id, original;
        File cached; FileShare sharing; XmppMessage sent;
        try {
            try (MonogramStore store = new MonogramStore(context, "captions.db")) {
                self = store.peer("alice@example.org"); peer = store.peer("bob@example.org");
                original = store.save(MessageFixture.replyMessage("original", null, null, "Quoted original", true, false, 100000, "phone", false, null));
                ReplyReference reply = store.replyTo(original, peer, self, false, null, 0);
                cached = store.attachmentFile(url); cached.getParentFile().mkdirs();
                byte[] png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a2ioAAAAASUVORK5CYII=");
                Files.write(cached.toPath(), png);
                sharing = FileShare.describe(FileLink.parse(url), cached, "Friends 😀.png", "image/png");
                cached = store.attachmentFile(sharing); Files.write(cached.toPath(), png);
                sent = XmppMessage.sentFile(JidCreate.entityFullFrom("alice@example.org/self"), "bob@example.org", "caption-file", sharing, caption, reply);
                id = store.save(sent);
                TLRPC.Message message = store.getMessage(id, self);
                check(message.message.equals(caption) && message.media.photo != null, "One native photo must retain the Unicode/multiline caption");
                check(message.reply_to.reply_to_msg_id == original && message.replyMessage.message.equals("Quoted original"), "Photo captions must retain reply previews without the quoted fallback");
                check(store.history(peer, self, 0, 100).size() == 2, "The caption must not create a second message");
                store.attach(id, url, cached, message.media);
                check(store.getMessage(id, self).message.equals(caption) && store.sharing(id).xml().equals(sharing.xml()), "Saving the downloaded preview must preserve caption and integrity metadata");
                check(store.save(sent) == 0 && store.getMessage(id, self).message.equals(caption), "Archive/carbon replay must deduplicate the complete captioned file");
                ReplyReference quoteCaption = store.replyTo(id, peer, self, false, "us 😀", "Photo of ".length());
                check(quoteCaption.wireBody("Reply").startsWith("> us 😀\n"), "Selected replies to media must use the caption text");
                int answer = store.save(MessageFixture.replyMessage("answer", null, null, "Answer", true, false, 300000, "phone", false, new ReplyReference("alice@example.org", "caption-file")));
                check(store.getMessage(answer, self).replyMessage.message.equals(caption), "Incoming reply previews must show the media caption");
                ReplyReference missing = new ReplyReference("bob@example.org", "missing").quoted("Missing original");
                int unavailable = store.save(XmppMessage.sentFile(JidCreate.entityFullFrom("alice@example.org/self"), "bob@example.org", "missing-reply-file", sharing, "My caption", missing));
                check(store.getMessage(unavailable, self).message.equals("> Missing original\nMy caption"), "A missing replied-to original must preserve both fallback and caption");
                String secretUrl = "aesgcm://example.org/private.png#" + new String(new char[88]).replace('\0', 'a');
                int secret = store.save(XmppMessage.sentFile(JidCreate.entityFullFrom("alice@example.org/self"), "bob@example.org", "secret-file", FileLink.parse(secretUrl), true));
                try {
                    store.save(XmppMessage.sentFile(JidCreate.entityFullFrom("alice@example.org/self"), "bob@example.org", "secret-file", sharing, "Injected caption", null));
                    throw new AssertionError("Plain replay must not enrich an encrypted message");
                } catch (IllegalStateException expected) {}
                check(store.sharing(secret) == null && store.getMessage(secret, self).message.isEmpty(), "Rejected plain metadata must leave private media unchanged");
                File otherBytes = new File(root, "different.png"); Files.write(otherBytes.toPath(), new byte[]{1,2,3});
                FileShare other = FileShare.describe(FileLink.parse(url), otherBytes, "Other.png", "image/png");
                int reused = store.save(XmppMessage.sentFile(JidCreate.entityFullFrom("alice@example.org/self"), "bob@example.org", "reused-url", other, "Different expected bytes", null));
                File expected = store.attachmentFile(other);
                check(store.attachmentPath(reused).equals(expected) && !expected.equals(cached) && !expected.isFile(), "Reused URLs with different hashes must not reuse old cached bytes");
                check(store.getMessage(reused, self).attachPath.equals(expected.getAbsolutePath()), "Native views must resolve the descriptor-specific path before downloading");
                TLRPC.Message placeholder = store.getMessage(reused, self);
                check(placeholder.media.photo != null && placeholder.media.photo.sizes.get(0).location.volume_id == MonogramAttachments.PHOTO_VOLUME,
                        "An uncached described photo must use the native photo/download placeholder with its local XMPP marker");
                org.telegram.tgnet.SerializedData serialized = new org.telegram.tgnet.SerializedData(); placeholder.serializeToStream(serialized);
                org.telegram.tgnet.SerializedData input = new org.telegram.tgnet.SerializedData(serialized.toByteArray());
                TLRPC.Message restored = TLRPC.Message.TLdeserialize(input, input.readInt32(true), true);
                check(restored.media.photo != null && restored.message.equals("Different expected bytes"), "Photo placeholders and captions must survive native serialization before download");
                serialized.cleanup(); input.cleanup();
                int legacy = store.save(MessageFixture.replyMessage("legacy-file", "legacy-file", null, url, true, true, 700000, "self", false, null));
                store.attach(legacy, url, store.attachmentFile(url), new org.telegram.tgnet.TLRPC.TL_messageMediaEmpty());
                check(store.attachmentPath(legacy).equals(store.attachmentFile(url)), "Legacy cache paths must remain usable without integrity metadata");
                store.save(XmppMessage.sentFile(JidCreate.entityFullFrom("alice@example.org/self"), "bob@example.org", "legacy-file", other, "Enriched", null));
                check(store.attachmentPath(legacy).equals(expected) && cached.isFile() && store.attachmentFile(url).isFile(), "New integrity metadata must bypass old bytes while retaining both existing files");
                store.getWritableDatabase().execSQL("UPDATE attachments SET sharing=? WHERE message=?", new Object[]{"<broken/>", reused});
                check(store.attachmentPath(reused) == null && store.getMessage(reused, self).message.equals("Attachment unavailable"), "Invalid persisted metadata must fail closed in native views");
                try { store.sharing(reused); throw new AssertionError("Invalid metadata must not silently fall back to an unchecked download"); }
                catch (IllegalStateException rejected) {}
                store.getWritableDatabase().execSQL("UPDATE attachments SET sharing=? WHERE message=?", new Object[]{other.xml().replace(url, "https://example.org/unrelated.png"), reused});
                check(store.attachmentPath(reused) == null, "Persisted metadata must match the stored attachment URL");
                otherBytes.delete();
            }
            try (MonogramStore reopened = new MonogramStore(context, "captions.db")) {
                check(reopened.getMessage(id, self).message.equals(caption) && reopened.attachmentPath(id).equals(cached), "Caption and verified media path must survive offline reopen");
            }
            try (SQLiteDatabase old = SQLiteDatabase.openOrCreateDatabase(database, null)) {
                MonogramStoreDeviceTest.legacyAttachments(old); old.setVersion(7);
                old.execSQL("INSERT OR REPLACE INTO archive_state VALUES('direct','oldest','newest',1)");
                old.execSQL("INSERT OR REPLACE INTO archive_state VALUES('direct:2','peer-oldest','peer-newest',1)");
            }
            try (MonogramStore migrated = new MonogramStore(context, "captions.db")) {
                check(migrated.getReadableDatabase().getVersion() == 24 && migrated.archiveCursor(false) == null && migrated.archiveCursor(peer, false) == null, "Schema 7 must rescan both global and per-chat metadata without deleting messages");
                check(migrated.getMessage(id, self).media.photo != null && migrated.attachmentPath(id).equals(cached), "Migration must preserve existing media and local paths");
                check(migrated.save(sent) == id, "Metadata rescan must enrich the existing message, preserving its ID");
                check(migrated.getMessage(id, self).message.equals(caption) && migrated.getMessage(id, self).reply_to.reply_to_msg_id == original, "Rescan must recover caption and reply metadata together");
                try (Cursor c = migrated.getReadableDatabase().rawQuery("SELECT body FROM messages WHERE id=?", new String[]{Integer.toString(id)})) { check(c.moveToFirst() && c.getString(0).equals(sent.body), "Raw original content must remain intact"); }
                migrated.clear();
                try (Cursor c = migrated.getReadableDatabase().rawQuery("SELECT count(*) FROM attachments", null)) { check(c.moveToFirst() && c.getInt(0)==0, "Logout must clear captions and metadata"); }
            }
            System.out.println("PASS: single captioned photo, Unicode/replies, preview/cache persistence, metadata rescan, forged plaintext rejection, schema 7 migration and logout");
        } finally { SQLiteDatabase.deleteDatabase(database); }
    }
}
