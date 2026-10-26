// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.negzero.monogram.xmpp.ChecklistContent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.telegram.tgnet.TLRPC;

/** Durable, authenticated checklist state. The controller is responsible for the transport trust boundary. */
final class MonogramChecklistStore {
    private final MonogramStore store;

    MonogramChecklistStore(MonogramStore store) { this.store = store; }

    static void create(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS checklist_documents (message INTEGER PRIMARY KEY,peer INTEGER NOT NULL,owner TEXT NOT NULL,body TEXT NOT NULL,data TEXT NOT NULL,revision INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS checklist_documents_peer ON checklist_documents(peer,message)");
        db.execSQL("CREATE TABLE IF NOT EXISTS checklist_updates (id INTEGER PRIMARY KEY AUTOINCREMENT,peer INTEGER NOT NULL,target TEXT NOT NULL,actor TEXT NOT NULL,nonce INTEGER NOT NULL,data TEXT NOT NULL,source INTEGER NOT NULL DEFAULT 0,applied INTEGER NOT NULL DEFAULT 0,rejected INTEGER NOT NULL DEFAULT 0,UNIQUE(peer,actor,target,nonce))");
        db.execSQL("CREATE INDEX IF NOT EXISTS checklist_updates_pending ON checklist_updates(peer,target,applied,rejected,id)");
        db.execSQL("CREATE TABLE IF NOT EXISTS checklist_controls (message INTEGER PRIMARY KEY)");
    }

    /** Called only after the controller has authenticated plaintext or an OMEMO2 decrypted stanza. */
    Set<Integer> acceptDocument(int message, long peer, String body, ChecklistContent content,
            String authenticatedBare, boolean room, boolean realSenderVerified) {
        if (content == null || content.update || !senderAllowed(authenticatedBare, room, realSenderVerified)) return Collections.emptySet();
        MonogramChecklists.Checklist checklist = MonogramChecklists.decode(content.data);
        if (checklist == null || !sameBare(checklist.owner, authenticatedBare) || !Objects.equals(body, MonogramChecklists.markdown(checklist)))
            return Collections.emptySet();
        synchronized (store) {
            SQLiteDatabase db = store.getWritableDatabase();
            db.beginTransaction();
            try {
                if (!messageMatches(db, message, peer)) return Collections.emptySet();
                try (Cursor current = db.rawQuery("SELECT peer,owner,body,data FROM checklist_documents WHERE message=?", new String[]{Integer.toString(message)})) {
                    if (current.moveToFirst()) {
                        if (current.getLong(0) != peer || !checklist.owner.equals(current.getString(1)) || !body.equals(current.getString(2)) || !content.data.equals(current.getString(3)))
                            return Collections.emptySet();
                    } else {
                        ContentValues values = new ContentValues();
                        values.put("message", message); values.put("peer", peer); values.put("owner", checklist.owner);
                        values.put("body", body); values.put("data", content.data); values.put("revision", checklist.revision);
                        if (db.insertWithOnConflict("checklist_documents", null, values, SQLiteDatabase.CONFLICT_IGNORE) == -1)
                            return Collections.emptySet();
                    }
                }
                Set<Integer> changed = reconcileLocked(db, peer);
                changed.add(message);
                db.setTransactionSuccessful();
                return changed;
            } finally { db.endTransaction(); }
        }
    }

    /** Persists a control stanza even when its target has not arrived yet. It is never a chat message. */
    Set<Integer> acceptUpdate(int controlMessage, long peer, ChecklistContent content,
            String authenticatedBare, boolean room, boolean realSenderVerified) {
        if (content == null || !content.update || !senderAllowed(authenticatedBare, room, realSenderVerified)) return Collections.emptySet();
        MonogramChecklists.Mutation mutation = MonogramChecklists.decodeMutation(content.data);
        if (mutation == null || !content.target.equals(mutation.reference)) return Collections.emptySet();
        synchronized (store) {
            SQLiteDatabase db = store.getWritableDatabase(); db.beginTransaction();
            try {
                if (controlMessage > 0 && !messageMatches(db, controlMessage, peer)) return Collections.emptySet();
                if (controlMessage > 0) controlLocked(db, controlMessage);
                ContentValues values = new ContentValues(); values.put("peer", peer); values.put("target", content.target);
                values.put("actor", bare(authenticatedBare)); values.put("nonce", mutation.nonce); values.put("data", content.data); values.put("source", controlMessage);
                db.insertWithOnConflict("checklist_updates", null, values, SQLiteDatabase.CONFLICT_IGNORE);
                Set<Integer> changed = reconcileLocked(db, peer);
                db.setTransactionSuccessful();
                return changed;
            } finally { db.endTransaction(); }
        }
    }

    /** Mark a recognized update as hidden before a later controller failure/retry. */
    void control(int message) {
        if (message <= 0) return;
        synchronized (store) { controlLocked(store.getWritableDatabase(), message); }
    }

    boolean hidden(int message) {
        if (message <= 0) return false;
        try (Cursor row = store.getReadableDatabase().rawQuery("SELECT 1 FROM checklist_controls WHERE message=?", new String[]{Integer.toString(message)})) { return row.moveToFirst(); }
    }

    Set<Integer> hidden(long peer) {
        Set<Integer> result = new LinkedHashSet<>();
        try (Cursor rows = store.getReadableDatabase().rawQuery("SELECT c.message FROM checklist_controls c JOIN messages m ON m.id=c.message WHERE m.peer=?", new String[]{Long.toString(peer)})) {
            while (rows.moveToNext()) result.add(rows.getInt(0));
        }
        return result;
    }

    Set<Integer> reconcile(long peer) {
        synchronized (store) {
            SQLiteDatabase db = store.getWritableDatabase(); db.beginTransaction();
            try { Set<Integer> result = reconcileLocked(db, peer); db.setTransactionSuccessful(); return result; }
            finally { db.endTransaction(); }
        }
    }

    /** Projects persisted state into Telegram's existing checklist cell; no remote TL object is read. */
    boolean project(TLRPC.Message message, long self) { return project(message, message == null ? 0 : message.id, self); }

    /** Payload may differ from the displayed message when an existing correction is projected. */
    boolean project(TLRPC.Message message, int payload, long self) {
        if (message == null || payload <= 0) return false;
        MonogramChecklists.Checklist checklist = checklist(payload);
        if (checklist == null) return false;
        return MonogramChecklists.project(message, checklist, jid -> {
            if (sameBare(jid, store.jid(self))) return self;
            long known = store.knownPeer(jid);
            return known > 0 ? known : 0;
        });
    }

    MonogramChecklists.Checklist checklist(int message) {
        try (Cursor row = store.getReadableDatabase().rawQuery("SELECT d.data FROM checklist_documents d JOIN messages m ON m.id=d.message WHERE d.message=? AND m.deleted=0 AND m.readable=1", new String[]{Integer.toString(message)})) {
            return row.moveToFirst() ? MonogramChecklists.decode(row.getString(0)) : null;
        }
    }

    /** Snapshot for a native toggle/append request; the peer check prevents cross-dialog local IDs. */
    MonogramChecklists.Checklist snapshot(int message, long peer) {
        try (Cursor row = store.getReadableDatabase().rawQuery("SELECT d.data FROM checklist_documents d JOIN messages m ON m.id=d.message WHERE d.message=? AND d.peer=? AND m.peer=d.peer AND m.deleted=0 AND m.readable=1", new String[]{Integer.toString(message), Long.toString(peer)})) {
            return row.moveToFirst() ? MonogramChecklists.decode(row.getString(0)) : null;
        }
    }

    /** Returns a stable XMPP alias, never a local database id, for a native toggle/append action. */
    String target(int message, long peer) {
        if (snapshot(message, peer) == null || store.hiddenCorrection(message)) throw new IllegalArgumentException("Checklist is unavailable");
        String kind = peer < 0 ? "archive" : null, found = null;
        String query = peer < 0 ? "SELECT value FROM message_ids WHERE message=? AND peer=? AND kind='archive'"
                : "SELECT kind,value FROM message_ids WHERE message=? AND peer=? AND kind IN ('origin','stanza') ORDER BY CASE kind WHEN 'origin' THEN 0 ELSE 1 END,rowid";
        try (Cursor ids = store.getReadableDatabase().rawQuery(query, new String[]{Integer.toString(message), Long.toString(peer)})) {
            while (ids.moveToNext()) {
                String value;
                if (peer < 0) value = ids.getString(0);
                else { kind = ids.getString(0); value = ids.getString(1); if ("stanza".equals(kind)) value = suffix(value); }
                if (value == null || value.isEmpty() || resolve(store.getReadableDatabase(), peer, value) != message) continue;
                if (found != null && !found.equals(value)) throw new IllegalArgumentException("Checklist has ambiguous XMPP IDs");
                found = value;
                if (peer > 0 && "origin".equals(kind)) break;
            }
        }
        if (found == null) throw new IllegalArgumentException(peer < 0 ? "Checklist has no stable room ID yet" : "Checklist has no stable XMPP ID yet");
        return found;
    }

    private Set<Integer> reconcileLocked(SQLiteDatabase db, long peer) {
        LinkedHashSet<Integer> changed = new LinkedHashSet<>();
        // A later revision may be stored before the revision it depends on, so repeat until no update applies.
        for (int pass = 0; pass < MonogramChecklists.MAX_ITEMS + 1; pass++) {
            boolean progressed = false;
            try (Cursor updates = db.rawQuery("SELECT id,target,actor,data FROM checklist_updates WHERE peer=? AND applied=0 AND rejected=0 ORDER BY id", new String[]{Long.toString(peer)})) {
                while (updates.moveToNext()) {
                    long id = updates.getLong(0); String target = updates.getString(1); String actor = updates.getString(2);
                    MonogramChecklists.Mutation mutation = MonogramChecklists.decodeMutation(updates.getString(3));
                    int message = resolve(db, peer, target);
                    if (mutation == null || !target.equals(mutation.reference)) { reject(db, id); continue; }
                    if (message == 0) continue;
                    if (!messageMatches(db, message, peer) || store.hiddenCorrection(message)) { reject(db, id); continue; }
                    Document document = document(db, message);
                    if (document == null) continue;
                    MonogramChecklists.Checklist current = MonogramChecklists.decode(document.data);
                    if (current == null) { reject(db, id); continue; }
                    if (mutation.baseRevision > current.revision) continue;
                    if (mutation.baseRevision < current.revision) { reject(db, id); continue; }
                    MonogramChecklists.Checklist next = MonogramChecklists.apply(current, mutation, actor, now());
                    if (next == null) { reject(db, id); continue; }
                    if (next != current) {
                        String data = MonogramChecklists.encode(next);
                        ContentValues updated = new ContentValues(); updated.put("data", data); updated.put("revision", next.revision);
                        db.update("checklist_documents", updated, "message=?", new String[]{Integer.toString(message)});
                        changed.add(message);
                    }
                    ContentValues applied = new ContentValues(); applied.put("applied", 1);
                    db.update("checklist_updates", applied, "id=?", new String[]{Long.toString(id)});
                    progressed = true;
                }
            }
            if (!progressed) break;
        }
        return changed;
    }

    private static final class Document {
        final String data;
        Document(Cursor row) { data = row.getString(0); }
    }

    private static Document document(SQLiteDatabase db, int message) {
        try (Cursor row = db.rawQuery("SELECT data FROM checklist_documents WHERE message=?", new String[]{Integer.toString(message)})) { return row.moveToFirst() ? new Document(row) : null; }
    }

    private static int resolve(SQLiteDatabase db, long peer, String target) {
        String match = peer < 0 ? "i.kind='archive' AND i.value=?" : "(i.kind='origin' AND i.value=? OR i.kind='stanza' AND substr(i.value,instr(i.value,char(10))+1)=?)";
        String[] args = peer < 0 ? new String[]{Long.toString(peer), Long.toString(peer), target}
                : new String[]{Long.toString(peer), Long.toString(peer), target, target};
        int result = 0;
        // Resolve across every message, including tombstones and ordinary text. Restricting this
        // query to checklist_documents hides collisions with a non-checklist origin/stanza ID and
        // could apply a control to an arbitrary checklist in that dialog.
        try (Cursor rows = db.rawQuery("SELECT DISTINCT m.id FROM messages m JOIN message_ids i ON i.message=m.id WHERE m.peer=? AND i.peer=? AND (" + match + ")", args)) {
            while (rows.moveToNext()) { int candidate = rows.getInt(0); if (result != 0 && result != candidate) return 0; result = candidate; }
        }
        return result;
    }

    private static boolean messageMatches(SQLiteDatabase db, int message, long peer) {
        try (Cursor row = db.rawQuery("SELECT 1 FROM messages WHERE id=? AND peer=? AND readable=1 AND deleted=0", new String[]{Integer.toString(message), Long.toString(peer)})) { return row.moveToFirst(); }
    }

    private static void controlLocked(SQLiteDatabase db, int message) {
        ContentValues value = new ContentValues(); value.put("message", message);
        db.insertWithOnConflict("checklist_controls", null, value, SQLiteDatabase.CONFLICT_IGNORE);
    }

    private static void reject(SQLiteDatabase db, long id) {
        ContentValues value = new ContentValues(); value.put("rejected", 1);
        db.update("checklist_updates", value, "id=?", new String[]{Long.toString(id)});
    }

    private static boolean senderAllowed(String sender, boolean room, boolean verified) {
        try { bare(sender); return !room || verified; } catch (IllegalArgumentException invalid) { return false; }
    }

    private static boolean sameBare(String first, String second) {
        try { return bare(first).equals(bare(second)); } catch (IllegalArgumentException invalid) { return false; }
    }

    private static String bare(String value) {
        if (value == null) throw new IllegalArgumentException("Missing sender");
        if (value.length() > 1024 || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid sender");
        try { return org.jxmpp.jid.impl.JidCreate.entityFrom(value).asEntityBareJid().toString(); }
        catch (org.jxmpp.stringprep.XmppStringprepException invalid) { throw new IllegalArgumentException("Invalid sender", invalid); }
    }

    private static String suffix(String value) {
        int separator = value.indexOf('\n'); return separator < 0 ? value : value.substring(separator + 1);
    }

    private static int now() {
        long value = System.currentTimeMillis() / 1000L;
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }
}
