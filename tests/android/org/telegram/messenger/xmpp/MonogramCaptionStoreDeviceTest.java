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
            }
            try (MonogramStore reopened = new MonogramStore(context, "captions.db")) {
                check(reopened.getMessage(id, self).message.equals(caption) && reopened.attachmentPath(id).equals(cached), "Caption and verified media path must survive offline reopen");
            }
            try (SQLiteDatabase old = SQLiteDatabase.openOrCreateDatabase(database, null)) {
                MonogramStoreDeviceTest.legacyAttachments(old); old.setVersion(7);
                old.execSQL("INSERT OR REPLACE INTO archive_state VALUES('direct','oldest','newest',1)");
            }
            try (MonogramStore migrated = new MonogramStore(context, "captions.db")) {
                check(migrated.getReadableDatabase().getVersion() == 8 && migrated.archiveCursor(false) == null, "Schema 7 must trigger a non-destructive metadata rescan");
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
