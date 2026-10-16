package org.telegram.messenger.xmpp;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.sqlite.SQLiteDatabase;
import com.negzero.monogram.xmpp.*;
import java.io.File;
import java.util.ArrayList;
import org.telegram.tgnet.TLRPC;

/** Real Android SQLite/native message models, using only a disposable database. */
public final class MonogramReplyStoreDeviceTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static XmppMessage original(String stanza, String origin, String text, boolean outgoing, String resource) throws Exception {
        return MessageFixture.replyMessage(stanza, origin, null, text, text != null, outgoing, 100000, resource, true, null);
    }
    private static int reply(MonogramStore store, String author, String target, String text, boolean outgoing) throws Exception {
        ReplyReference reference = new ReplyReference(author, target).quoted("Quoted 😀");
        return store.save(MessageFixture.replyMessage(java.util.UUID.randomUUID().toString(), null, null,
                reference.wireBody(text), true, outgoing, 200000, outgoing ? "self-phone" : "friend-phone", true, reference));
    }
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]); root.mkdirs();
        Context context = new ContextWrapper(null) {
            @Override public File getDatabasePath(String name) { return new File(root, name); }
            @Override public File getFilesDir() { File f = new File(root, "files"); f.mkdirs(); return f; }
            @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory, android.database.DatabaseErrorHandler handler) { return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).getPath(), factory, handler); }
            @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory) { return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory); }
        };
        File database = context.getDatabasePath("replies.db"); check(!database.exists(), "Fresh disposable reply database required");
        long self, peer;
        int original, outgoingReply;
        try {
            try (MonogramStore store = new MonogramStore(context, "replies.db")) {
                self = store.peer("alice@example.org"); peer = store.peer("bob@example.org");
                store.profile(peer, new XmppProfile("<vCard xmlns='vcard-temp'><FN>Bob</FN></vCard>", "Bobby", new byte[0]));
                original = store.save(original("client-original", "origin-original", "Original 😀", false, "phone"));
                ReplyReference prepared = store.replyTo(original, peer, self, true, null, 0);
                check(prepared.id.equals("origin-original") && prepared.to.equals("bob@example.org/phone"), "Replies must prefer origin IDs and retain the original author");
                outgoingReply = store.save(XmppMessage.sent(org.jxmpp.jid.impl.JidCreate.entityFullFrom("alice@example.org/self-phone"), "bob@example.org", "outgoing-reply", "Answer 🦄", true, prepared));
                TLRPC.Message message = store.getMessage(outgoingReply, self);
                check(message.reply_to.reply_to_msg_id == original && message.replyMessage.id == original, "Native previews need the exact local original");
                check(message.message.equals("Answer 🦄") && message.replyMessage.message.equals("Original 😀"), "Known originals must suppress only the quoted fallback");
                check((message.flags & TLRPC.MESSAGE_FLAG_REPLY) != 0, "Native reply flag must be set");
                ReplyReference plaintext = store.replyTo(original, peer, self, false, null, 0);
                check(plaintext.wireBody("Plain answer").equals("Plain answer"), "Encrypted original text must never leak into a plaintext fallback");
                int ownOriginal = store.save(original("own-stanza", "own-origin", "My original", true, "desktop"));
                int incomingReply = reply(store, "alice@example.org/desktop", "own-origin", "Incoming reply", false);
                check(store.getMessage(incomingReply, self).reply_to.reply_to_msg_id == ownOriginal, "Incoming replies must resolve outgoing originals");
                int foreign = reply(store, "mallory@example.org/phone", "origin-original", "Foreign reference", false);
                check(store.getMessage(foreign, self).reply_to == null && store.getMessage(foreign, self).message.contains("Quoted"), "Foreign authors must not select a local target or hide fallback text");
                int wrongResource = reply(store, "bob@example.org/desktop", "origin-original", "Wrong resource", false);
                check(store.getMessage(wrongResource, self).reply_to == null, "Full-JID references must match the actual author resource");
                int first = store.save(original("counter-1", null, "Phone", false, "counter-phone"));
                int second = store.save(original("counter-1", null, "Desktop", false, "counter-desktop"));
                int exact = reply(store, "bob@example.org/counter-desktop", "counter-1", "Exact reference", false);
                check(store.getMessage(exact, self).reply_to.reply_to_msg_id == second && first != second, "Full author must disambiguate repeated stanza IDs");
                int ambiguous = reply(store, "bob@example.org", "counter-1", "Ambiguous reference", false);
                check(store.getMessage(ambiguous, self).reply_to == null, "Ambiguous bare-JID references must never guess a target");
                store.save(original("other-side", "origin-original", "Same ID, other direction", true, "desktop"));
                int noAuthor = reply(store, null, "origin-original", "No author", false);
                check(store.getMessage(noAuthor, self).reply_to == null, "Missing author must not guess between incoming and outgoing messages");
                int bare = reply(store, "bob@example.org", "origin-original", "Bare author", false);
                check(store.getMessage(bare, self).reply_to.reply_to_msg_id == original, "Bare author must scope direct-message references to the correct direction");
                int originOnly = store.save(original(null, "origin-only", "Origin without stanza ID", false, "tablet"));
                int originOnlyReply = reply(store, "bob@example.org/tablet", "origin-only", "Origin reply", false);
                check(store.getMessage(originOnlyReply, self).reply_to.reply_to_msg_id == originOnly, "Origin-only messages still need persistent author scope");
                int lateReply = reply(store, "bob@example.org/late", "late-origin", "Late answer", false);
                check(store.getMessage(lateReply, self).reply_to == null && store.getMessage(lateReply, self).message.contains("Quoted"), "Missing original needs its fallback");
                int lateOriginal = store.save(original("late-stanza", "late-origin", "Late original", false, "late"));
                ArrayList<TLRPC.Message> refreshed = store.changedReplies(peer, self);
                check(refreshed.stream().anyMatch(m -> m.id == lateReply && m.reply_to.reply_to_msg_id == lateOriginal && m.message.equals("Late answer")), "Late archive originals must refresh existing reply rows");
                check(store.historyAround(peer, self, original, 7).stream().anyMatch(m -> m.id == original), "Reply navigation must include its anchor message");
                long other = store.peer("charlie@example.org");
                try { store.replyTo(original, other, self, true, null, 0); throw new AssertionError("Cross-chat outgoing reply must fail"); }
                catch (IllegalArgumentException expected) {}
                try { store.replyTo(original, peer, self, true, "Fabricated quote", 0); throw new AssertionError("Fabricated outgoing quote must fail"); }
                catch (IllegalArgumentException expected) {}
                ReplyReference selected = store.replyTo(original, peer, self, true, "😀", "Original ".length());
                check(selected.wireBody("Selection reply").startsWith("> 😀\n"), "Native selected quotes must preserve Unicode");
                int locked = store.save(original("locked-original", "locked-origin", null, false, "locked"));
                int lockedReply = reply(store, "bob@example.org/locked", "locked-origin", "Reply to locked", false);
                store.changedReplies(peer, self);
                check(store.getMessage(lockedReply, self).replyMessage.message.contains("unavailable"), "Unavailable encrypted originals need honest previews");
                check(store.save(original("locked-original", "locked-origin", "Recovered original", false, "locked")) == locked, "Recovered original retains its local ID");
                check(store.changedReplies(peer, self, java.util.Collections.singleton(locked)).stream().anyMatch(m -> m.id == lockedReply && m.replyMessage.message.equals("Recovered original")), "Recovered plaintext must refresh its replies even when the target ID stays the same");
                ReplyReference oldReference = new ReplyReference("bob@example.org/phone", "origin-original").quoted("Old quote 😀");
                String oldBody = oldReference.wireBody("Already decrypted reply");
                int legacyReply = store.save(MessageFixture.replyMessage("legacy-reply", "legacy-reply-origin", null, oldBody, true, false, 250000, "legacy", true, null));
                check(store.save(MessageFixture.replyMessage("legacy-reply", "legacy-reply-origin", "legacy-archive", null, false, false, 250000, "legacy", true, oldReference)) == legacyReply, "Unreadable MAM replay must enrich the existing decrypted message with reply metadata");
                check(store.getMessage(legacyReply, self).message.equals("Already decrypted reply") && store.getMessage(legacyReply, self).reply_to.reply_to_msg_id == original,
                    "Reply metadata migration must use retained plaintext to validate fallback ranges");
                int cycleA = reply(store, "bob@example.org/cycle", "cycle-b", "Cycle A", false);
                // Add an origin alias for A, then B refers back to it. Rendering must stay bounded.
                store.getWritableDatabase().execSQL("INSERT INTO message_ids VALUES(?,0,'origin','cycle-a',?)", new Object[]{peer, cycleA});
                int cycleB = store.save(MessageFixture.replyMessage("cycle-b", "cycle-b", null, "Cycle B", true, false, 300000, "cycle", true, new ReplyReference("bob@example.org/friend-phone", "cycle-a")));
                check(store.getMessage(cycleA, self).replyMessage.id == cycleB && store.getMessage(cycleB, self).replyMessage.replyMessage == null, "Reply cycles must not recurse indefinitely");
                String link = "aesgcm://example.org/image.png#" + new String(new char[88]).replace('\0', 'a');
                int fileReply = store.save(XmppMessage.sentFile(org.jxmpp.jid.impl.JidCreate.entityFullFrom("alice@example.org/self-phone"), "bob@example.org", "file-reply", FileLink.parse(link), true, prepared));
                check(MonogramAttachments.has(store.getMessage(fileReply, self)) && store.getMessage(fileReply, self).reply_to.reply_to_msg_id == original, "Attachment replies need both native media and reply metadata");
            }
            try (MonogramStore reopened = new MonogramStore(context, "replies.db")) {
                check(reopened.getMessage(outgoingReply, self).reply_to.reply_to_msg_id == original, "Reply references must survive a new store instance");
                check(reopened.getMessage(outgoingReply, self).replyMessage.message.equals("Original 😀"), "Reply previews must resolve outside the loaded page");
            }
            try (SQLiteDatabase old = SQLiteDatabase.openOrCreateDatabase(database, null)) {
                old.execSQL("DROP TABLE corrections"); old.execSQL("DROP TABLE applied_corrections");
                old.execSQL("DROP TABLE replies"); old.execSQL("DROP TABLE message_senders");
                old.execSQL("INSERT OR REPLACE INTO archive_state VALUES('direct','oldest','newest',1)"); MonogramStoreDeviceTest.legacyAttachments(old); old.setVersion(5);
            }
            try (MonogramStore migrated = new MonogramStore(context, "replies.db")) {
                check(migrated.getReadableDatabase().getVersion() == 8, "Schema 5 must migrate to 8");
                check(migrated.getMessage(original, self).message.equals("Original 😀"), "Schema 5 messages must survive migration");
                check(migrated.profile(peer).nickname.equals("Bobby"), "Schema 5 profiles must survive reply migration");
                check(migrated.archiveCursor(false) == null && !migrated.archiveComplete(), "Reply migration must rescan archive metadata without deleting messages");
                ReplyReference reference = migrated.replyTo(original, peer, self, true, null, 0);
                check(reference.to.equals("bob@example.org/phone"), "Migration must recover scoped authors from existing stanza aliases");
                migrated.clear();
                try (android.database.Cursor cursor = migrated.getReadableDatabase().rawQuery("SELECT (SELECT count(*) FROM replies)+(SELECT count(*) FROM message_senders)", null)) {
                    check(cursor.moveToFirst() && cursor.getInt(0) == 0, "Logout must clear reply and author metadata");
                }
            }
            System.out.println("PASS: native replies, exact author/ID scope, fallback Unicode, late/recovered originals, around-message navigation, attachments, reopen, schema 5 migration, logout");
        } finally { SQLiteDatabase.deleteDatabase(database); }
    }
}
