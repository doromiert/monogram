package org.telegram.messenger.xmpp;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.negzero.monogram.xmpp.*;
import java.io.File;
import java.util.Collections;
import org.telegram.tgnet.TLRPC;

/** Corrections use the real Android database and native models, with no real account or messages. */
public final class MonogramCorrectionStoreDeviceTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static XmppMessage event(String id, String target, String text, boolean outgoing, boolean encrypted, long time, String resource) throws Exception {
        return MessageFixture.correction(id, id + "-origin", id + "-archive", text, text != null, outgoing, time, resource, encrypted, null, target);
    }
    public static void main(String[] args) throws Exception {
        org.telegram.messenger.ApplicationLoader.applicationLoaderInstance = new org.telegram.messenger.ApplicationLoader();
        File root = new File(args[0]); root.mkdirs();
        Context context = new ContextWrapper(null) {
            @Override public File getDatabasePath(String name) { return new File(root, name); }
            @Override public File getFilesDir() { File f = new File(root, "files"); f.mkdirs(); return f; }
            @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory, android.database.DatabaseErrorHandler handler) { return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).getPath(), factory, handler); }
            @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory) { return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory); }
        };
        File database = context.getDatabasePath("corrections.db"); check(!database.exists(), "Disposable database required");
        long self, peer; int original, second, editedReply;
        try {
            try (MonogramStore store = new MonogramStore(context, "corrections.db")) {
                self = store.peer("alice@example.org"); peer = store.peer("bob@example.org");
                original = store.save(event("original", null, "Before 😀", false, true, 100000, "phone"));
                int reply = store.save(MessageFixture.replyMessage("reply", null, null, "Answer", true, true, 150000, "self", true, new ReplyReference("bob@example.org", "original")));
                store.changedReplies(peer, self);
                int edit = store.save(event("edit-1", "original", "After 🦄", false, true, 200000, "desktop"));
                MonogramStore.CorrectionChanges first = store.reconcileCorrections(peer);
                check(first.edited.contains(original) && first.hidden.contains(edit), "Direct corrections must accept the same bare author across resources");
                check(store.history(peer, self, 0, 100).size() == 2, "Applied edits must not create duplicate bubbles");
                TLRPC.Message message = store.getMessage(original, self);
                check(message.id == original && message.date == 100 && message.edit_date == 200 && message.message.equals("After 🦄"), "Keep original identity/date and separate edit date");
                check((message.flags & TLRPC.MESSAGE_FLAG_EDITED) != 0, "Show the native edited marker");
                org.telegram.tgnet.SerializedData bytes = new org.telegram.tgnet.SerializedData(); message.serializeToStream(bytes);
                org.telegram.tgnet.SerializedData input = new org.telegram.tgnet.SerializedData(bytes.toByteArray());
                TLRPC.Message persisted = TLRPC.Message.TLdeserialize(input, input.readInt32(true), true); bytes.cleanup(); input.cleanup();
                check(persisted.edit_date == 200 && (persisted.flags & TLRPC.MESSAGE_FLAG_EDITED) != 0, "Edited metadata must survive native serialization");
                check(store.changedReplies(peer, self, first.edited).stream().anyMatch(m -> m.id == reply && m.replyMessage.message.equals("After 🦄")), "Existing reply previews must refresh after an edit");
                second = store.save(event("edit-2", "original", "Latest", false, true, 300000, "phone"));
                store.reconcileCorrections(peer);
                store.save(event("edit-old", "original", "Older archive edit", false, true, 250000, "phone")); store.reconcileCorrections(peer);
                check(store.getMessage(original, self).message.equals("Latest"), "Older MAM edits must not revert the latest edit");
                check(store.save(event("original", null, "Before 😀", false, true, 100000, "phone")) == 0, "Original archive replay must deduplicate");
                check(store.save(event("edit-2", "original", null, false, true, 300000, "phone")) == 0, "Consumed OMEMO replay must retain the edit's plaintext");
                store.reconcileCorrections(peer);
                check(store.getMessage(original, self).message.equals("Latest"), "Replay must not revert or erase known corrections");
                int unreadable = store.save(event("edit-3", "original", null, false, true, 400000, "phone")); store.reconcileCorrections(peer);
                check(store.getMessage(original, self).message.contains("unavailable"), "Unreadable latest correction must not silently display stale content");
                check(store.save(event("edit-3", "original", "Recovered correction", false, true, 400000, "phone")) == unreadable, "Recovered edit keeps its event identity");
                check(store.reconcileCorrections(peer, Collections.singleton(unreadable)).edited.contains(original), "Recovered latest correction must refresh the original bubble");
                int downgrade = store.save(event("downgrade", "original", "Unsafe plaintext", false, false, 500000, "phone")); store.reconcileCorrections(peer);
                check(!store.hiddenCorrection(downgrade) && store.getMessage(original, self).message.equals("Recovered correction"), "Plaintext corrections must never replace an encrypted original");
                int plain = store.save(event("plain-original", null, "Plain before", true, false, 400000, "self"));
                store.save(event("upgrade", "plain-original", "Encrypted upgrade", true, true, 500000, "self"));
                int undoUpgrade = store.save(event("undo-upgrade", "plain-original", "Plaintext after encryption", true, false, 600000, "self")); store.reconcileCorrections(peer);
                check(!store.hiddenCorrection(undoUpgrade) && store.getMessage(plain, self).message.equals("Encrypted upgrade") && store.editTarget(plain, peer, self).encrypted,
                        "Allow encryption upgrades, then reject plaintext downgrades and preserve encryption for future edits");
                int opposite = store.save(event("wrong-direction", "original", "Wrong author", true, true, 500000, "self")); store.reconcileCorrections(peer);
                check(!store.hiddenCorrection(opposite), "A contact cannot correct our outgoing message or vice versa");
                int originAlias = store.save(event("wrong-alias", "original-origin", "Origin alias", false, true, 500000, "phone"));
                int archiveAlias = store.save(event("wrong-archive", "original-archive", "Archive alias", false, true, 500000, "phone"));
                int chain = store.save(event("wrong-chain", "edit-1", "Chained edit", false, true, 500000, "phone")); store.reconcileCorrections(peer);
                check(!store.hiddenCorrection(originAlias) && !store.hiddenCorrection(archiveAlias) && !store.hiddenCorrection(chain), "Only the original client stanza ID may select a correction target");
                int pending = store.save(event("late-edit", "late-original", "Late corrected", false, true, 700000, "desktop"));
                store.reconcileCorrections(peer); check(!store.hiddenCorrection(pending), "Unknown originals need a visible fallback");
                int late = store.save(event("late-original", null, "Late before", false, true, 600000, "phone"));
                MonogramStore.CorrectionChanges recovered = store.reconcileCorrections(peer);
                check(recovered.hidden.contains(pending) && recovered.edited.contains(late) && store.getMessage(late, self).message.equals("Late corrected"), "Late MAM original must consolidate its existing edit");
                store.save(MessageFixture.replyMessage("late-original", "different-origin", "different-archive", "Reused ID", true, false, 600001, "tablet", true, null));
                MonogramStore.CorrectionChanges ambiguous = store.reconcileCorrections(peer);
                check(ambiguous.shown.contains(pending) && store.getMessage(late, self).message.equals("Late before"), "A late ID collision must undo the match instead of guessing an author resource");
                int own = store.save(event("own-original", null, "My original", true, true, 800000, "self"));
                store.save(event("own-edit", "own-original", "My correction", true, true, 900000, "other-device")); store.reconcileCorrections(peer);
                check(store.getMessage(own, self).message.equals("My correction"), "Outgoing carbons must update our original");
                MonogramStore.EditTarget target = store.editTarget(own, peer, self);
                check(target.correction.id.equals("own-original") && target.encrypted, "Repeated local edits must reference the first stanza and retain encryption");
                try { store.editTarget(original, peer, self); throw new AssertionError("Cannot edit another author's message"); } catch (IllegalArgumentException expected) {}
                try { store.editTarget(own, store.peer("charlie@example.org"), self); throw new AssertionError("Cannot edit another conversation"); } catch (IllegalArgumentException expected) {}
                try { store.editTarget(second, peer, self); throw new AssertionError("Cannot edit a correction event"); } catch (IllegalArgumentException expected) {}
                int ownReply = store.save(MessageFixture.replyMessage("own-reply", null, null, "Original answer", true, true, 1000000, "self", true, new ReplyReference("bob@example.org", "original")));
                MonogramStore.EditTarget replyTarget = store.editTarget(ownReply, peer, self);
                check(replyTarget.reply.id.equals("original"), "Editing must preserve reply metadata");
                editedReply = store.save(MessageFixture.correction("own-reply-edit", null, null, "Edited answer", true, true, 1100000, "self", true, replyTarget.reply, "own-reply"));
                store.reconcileCorrections(peer);
                check(store.getMessage(ownReply, self).replyMessage.message.equals("Recovered correction") && store.getMessage(ownReply, self).message.equals("Edited answer"), "An edited reply needs the current original preview and corrected body");
                try (Cursor c = store.getReadableDatabase().rawQuery("SELECT body FROM messages WHERE id=?", new String[]{Integer.toString(original)})) {
                    check(c.moveToFirst() && c.getString(0).equals("Before 😀"), "The original stored content must remain intact");
                }
            }
            try (MonogramStore reopened = new MonogramStore(context, "corrections.db")) {
                check(reopened.getMessage(original, self).message.equals("Recovered correction"), "Edits must persist offline across reopening");
                check(reopened.hiddenCorrection(second) && reopened.hiddenCorrection(editedReply), "Hidden events must stay consolidated across reopening");
            }
            try (SQLiteDatabase old = SQLiteDatabase.openOrCreateDatabase(database, null)) {
                old.execSQL("DROP TABLE corrections"); old.execSQL("DROP TABLE applied_corrections"); MonogramStoreDeviceTest.legacyAttachments(old); old.setVersion(6);
            }
            try (MonogramStore migrated = new MonogramStore(context, "corrections.db")) {
                check(migrated.getReadableDatabase().getVersion() == 27 && migrated.archiveCursor(false) == null, "Schema 6 must rescan metadata without losing cached content");
                check(migrated.getMessage(original, self).message.equals("Before 😀"), "Migration must preserve the raw original");
                migrated.save(event("edit-3", "original", null, false, true, 400000, "phone")); migrated.reconcileCorrections(peer);
                check(migrated.getMessage(original, self).message.equals("Recovered correction"), "Unreadable MAM metadata rescan must reuse already known edit plaintext");
                migrated.clear();
                try (Cursor c = migrated.getReadableDatabase().rawQuery("SELECT (SELECT count(*) FROM corrections)+(SELECT count(*) FROM applied_corrections)", null)) { check(c.moveToFirst() && c.getInt(0) == 0, "Logout must clear correction metadata"); }
            }
            System.out.println("PASS: corrections, repeated/encrypted/carbon/MAM edits, author/ID ambiguity, downgrade rejection, late originals, reply refresh, native serialization, offline persistence, schema 6 migration and logout");
        } finally { SQLiteDatabase.deleteDatabase(database); }
    }
}
