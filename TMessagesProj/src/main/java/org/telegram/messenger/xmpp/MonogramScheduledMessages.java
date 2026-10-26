// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Local scheduled-message journal for XMPP accounts.
 *
 * Telegram's scheduled-message RPCs are only a presentation protocol in Monogram: XMPP has no
 * equivalent server queue.  This journal deliberately owns no transport and never stores a live
 * {@link TLObject}; the controller reconstructs a normal send request from a claimed entry.  A
 * claim has an account-generation fence, so a reconnect can retry it while an account switch
 * cannot accidentally deliver it through the next account using that slot.
 */
public final class MonogramScheduledMessages {
    private static final int VERSION = 1;
    private static final long CLAIM_TIMEOUT_MS = 5 * 60 * 1000L;
    private static final long RETRY_MIN_MS = 5 * 1000L;
    private static final long RETRY_MAX_MS = 5 * 60 * 1000L;
    private static final Map<Integer, Object> LOCKS = new HashMap<>();
    private static final Set<String> LIVE_CLAIMS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private MonogramScheduledMessages() { }

    public static final class Entry {
        public final int id;
        /** Original negative native outbox id, kept only to emit the initial updateMessageID map. */
        public final int localId;
        public final long peer;
        public final String destination;
        public final String body;
        public final ArrayList<TLRPC.MessageEntity> entities;
        public final TLRPC.InputReplyTo replyTo;
        public final String encryption;
        public final boolean manualEncryption;
        public final boolean noWebpage;
        public final boolean silent;
        public final long createdAt;
        public final long dueAt;
        /** Planned occurrence time, independent of the retry deadline in {@link #dueAt}. */
        public final long occurrenceAt;
        public final int repeatPeriod;
        public final int attempts;
        /** Stable for retries of this occurrence; a repeated schedule receives a new value. */
        public final long randomId;
        public final String payload;

        public Entry(int id, int localId, long peer, String destination, String body,
                ArrayList<TLRPC.MessageEntity> entities, TLRPC.InputReplyTo replyTo,
                String encryption, boolean manualEncryption, boolean noWebpage, boolean silent,
                long createdAt, long dueAt, int repeatPeriod, int attempts, String payload) {
            this(id, localId, peer, destination, body, entities, replyTo, encryption, manualEncryption,
                    noWebpage, silent, createdAt, dueAt, repeatPeriod, attempts,
                    stableRandomId(id, createdAt, dueAt), payload);
        }

        public Entry(int id, int localId, long peer, String destination, String body,
                ArrayList<TLRPC.MessageEntity> entities, TLRPC.InputReplyTo replyTo,
                String encryption, boolean manualEncryption, boolean noWebpage, boolean silent,
                long createdAt, long dueAt, int repeatPeriod, int attempts, long randomId, String payload) {
            this(id, localId, peer, destination, body, entities, replyTo, encryption, manualEncryption,
                    noWebpage, silent, createdAt, dueAt, repeatPeriod, attempts, randomId, payload, dueAt);
        }

        private Entry(int id, int localId, long peer, String destination, String body,
                ArrayList<TLRPC.MessageEntity> entities, TLRPC.InputReplyTo replyTo,
                String encryption, boolean manualEncryption, boolean noWebpage, boolean silent,
                long createdAt, long dueAt, int repeatPeriod, int attempts, long randomId, String payload,
                long occurrenceAt) {
            this.id = id;
            this.localId = localId;
            this.peer = peer;
            this.destination = destination == null ? "" : destination;
            this.body = body == null ? "" : body;
            this.entities = entities == null ? new ArrayList<>() : new ArrayList<>(entities);
            this.replyTo = replyTo;
            this.encryption = encryption == null ? "" : encryption;
            this.manualEncryption = manualEncryption;
            this.noWebpage = noWebpage;
            this.silent = silent;
            this.createdAt = createdAt;
            this.dueAt = dueAt;
            this.occurrenceAt = occurrenceAt;
            this.repeatPeriod = Math.max(0, repeatPeriod);
            this.attempts = Math.max(0, attempts);
            this.randomId = randomId == 0 ? stableRandomId(id, createdAt, dueAt) : randomId;
            this.payload = payload == null ? "" : payload;
        }

        /** Rebuilds the ordinary text-send request; caller supplies the current, non-stale peer. */
        public TLRPC.TL_messages_sendMessage request(TLRPC.InputPeer inputPeer, long randomId) {
            if (inputPeer == null || peer == 0) throw new IllegalArgumentException("Missing scheduled-message peer");
            TLRPC.TL_messages_sendMessage request = new TLRPC.TL_messages_sendMessage();
            request.peer = inputPeer;
            request.message = body;
            request.random_id = randomId;
            request.no_webpage = noWebpage;
            request.silent = silent;
            if (replyTo != null) {
                request.reply_to = replyTo;
                request.flags |= 1;
            }
            if (!entities.isEmpty()) {
                request.entities.addAll(entities);
                request.flags |= 8;
            }
            return request;
        }

        /** Rebuilds a retry with the journaled per-occurrence identity. */
        public TLRPC.TL_messages_sendMessage request(TLRPC.InputPeer inputPeer) {
            return request(inputPeer, randomId);
        }

        Entry withDue(long dueAt, int attempts) {
            return new Entry(id, localId, peer, destination, body, entities, replyTo, encryption, manualEncryption,
                    noWebpage, silent, createdAt, dueAt, repeatPeriod, attempts, randomId, payload);
        }

        Entry withRetryAt(long retryAt, int attempts) {
            return new Entry(id, localId, peer, destination, body, entities, replyTo, encryption, manualEncryption,
                    noWebpage, silent, createdAt, retryAt, repeatPeriod, attempts, randomId, payload, occurrenceAt);
        }

        Entry nextOccurrence(long dueAt) {
            return new Entry(id, localId, peer, destination, body, entities, replyTo, encryption, manualEncryption,
                    noWebpage, silent, createdAt, dueAt, repeatPeriod, 0,
                    stableRandomId(id, createdAt, dueAt), payload);
        }
    }

    /** A single-use lease for handing a due entry to the existing XMPP outbox. */
    public static final class Claim {
        public final Entry entry;
        private final int account;
        private final String owner;
        private final long generation;
        private final String token;

        private Claim(Entry entry, int account, String owner, long generation, String token) {
            this.entry = entry;
            this.account = account;
            this.owner = owner;
            this.generation = generation;
            this.token = token;
        }
    }

    /** Durable completion result; a null next entry means a committed one-shot delivery. */
    public static final class Completion {
        public final boolean committed;
        public final Entry next;

        private Completion(boolean committed, Entry next) {
            this.committed = committed;
            this.next = next;
        }
    }

    /**
     * Captures the representable portion of a native text request.  Media must stay in the
     * regular media draft/outbox path; pretending that a file upload is durable here would lose
     * its local URI after an app restart.
     */
    public static Entry fromRequest(int id, int localId, long dueAt, int repeatPeriod, String destination,
            TLRPC.TL_messages_sendMessage request, String encryption, boolean manualEncryption,
            String payload) {
        if (request == null) throw new IllegalArgumentException("Missing scheduled-message request");
        long peer = DialogObject.getPeerDialogId(request.peer);
        if (id <= 0 || localId >= 0 || peer == 0 || dueAt <= 0) throw new IllegalArgumentException("Invalid scheduled-message descriptor");
        return new Entry(id, localId, peer, destination, request.message,
                request.entities, request.reply_to, encryption, manualEncryption, request.no_webpage,
                request.silent, System.currentTimeMillis(), dueAt, repeatPeriod, 0, request.random_id, payload);
    }

    /** Allocates a stable positive ID for the native scheduled-message projection. */
    public static int allocateId(int account, String owner, long generation) {
        if (!valid(owner, generation)) return 0;
        synchronized (lock(account)) {
            SharedPreferences preferences = preferences(account);
            Journal journal = read(account, owner);
            int id = Math.max(1, preferences.getInt(idKey(owner), 1));
            while (journal.entries.containsKey(id)) id = nextId(id);
            if (!preferences.edit().putInt(idKey(owner), nextId(id)).commit()) return 0;
            return id;
        }
    }

    /** Adds or replaces an entry.  The owner must be the current account JID, never a phone slot. */
    public static boolean put(int account, String owner, long generation, Entry entry) {
        if (!valid(owner, generation) || entry == null || entry.id <= 0 || entry.localId >= 0 || entry.peer == 0 || entry.dueAt <= 0) return false;
        synchronized (lock(account)) {
            Journal journal = read(account, owner);
            journal.entries.put(entry.id, new Row(entry));
            return write(account, owner, journal);
        }
    }

    public static List<Entry> list(int account, String owner, long generation, long peer) {
        if (!valid(owner, generation)) return Collections.emptyList();
        synchronized (lock(account)) {
            Journal journal = read(account, owner);
            ArrayList<Entry> result = new ArrayList<>();
            for (Row row : journal.entries.values()) if (peer == 0 || row.entry.peer == peer) result.add(row.entry);
            result.sort(Comparator.comparingLong((Entry entry) -> entry.dueAt).thenComparingInt(entry -> entry.id));
            return result;
        }
    }

    /** No polling while a live outbox owns a claim. A restarted process retries only after its persisted lease expires. */
    public static long nextWakeAt(int account,String owner,long generation,long now) {
        if(!valid(owner,generation)) return Long.MAX_VALUE;
        synchronized(lock(account)) {
            long earliest=Long.MAX_VALUE;
            for(Row row:read(account,owner).entries.values()) {
                if(row.claimed) {
                    if(LIVE_CLAIMS.contains(claimKey(account,owner,row.token))) continue;
                    earliest=Math.min(earliest,Math.max(row.entry.dueAt,row.claimedAt+CLAIM_TIMEOUT_MS));
                } else earliest=Math.min(earliest,row.entry.dueAt);
            }
            return earliest;
        }
    }
    private static String claimKey(int account,String owner,String token) { return account+"\n"+owner+"\n"+token; }

    /** Claims entries that are due.  Expired claims are retried, never discarded. */
    public static List<Claim> claimDue(int account, String owner, long generation, long now, int limit) {
        if (!valid(owner, generation) || limit <= 0) return Collections.emptyList();
        synchronized (lock(account)) {
            Journal journal = read(account, owner);
            ArrayList<Row> candidates = new ArrayList<>();
            for (Row row : journal.entries.values()) {
                if (row.claimed && !LIVE_CLAIMS.contains(claimKey(account,owner,row.token)) && row.claimedAt + CLAIM_TIMEOUT_MS <= now) row.release(now, retryDelay(row.entry.attempts));
                if (!row.claimed && row.entry.dueAt <= now) candidates.add(row);
            }
            candidates.sort(Comparator.comparingLong(row -> row.entry.dueAt));
            ArrayList<Claim> result = new ArrayList<>();
            for (Row row : candidates) {
                if (result.size() >= limit) break;
                row.claimed = true;
                row.claimedAt = now;
                row.generation = generation;
                row.token = UUID.randomUUID().toString();
                result.add(new Claim(row.entry, account, owner, generation, row.token));
            }
            if (!write(account, owner, journal)) return Collections.emptyList();
            for(Claim claim:result) LIVE_CLAIMS.add(claimKey(account,owner,claim.token));
            return result;
        }
    }

    /** Makes selected messages due immediately; the controller then calls {@link #claimDue}. */
    public static Set<Integer> sendNow(int account, String owner, long generation, long peer, List<Integer> ids, long now) {
        return updateDue(account, owner, generation, peer, ids, now, false);
    }

    public static Set<Integer> cancel(int account, String owner, long generation, long peer, List<Integer> ids) {
        if (!valid(owner, generation) || ids == null || ids.isEmpty()) return Collections.emptySet();
        synchronized (lock(account)) {
            Journal journal = read(account, owner);
            HashSet<Integer> changed = new HashSet<>();
            for (Integer id : ids) {
                Row row = journal.entries.get(id);
                if (row != null && (peer == 0 || row.entry.peer == peer)) {
                    // A live claim already belongs to the ordinary outbox.  Removing it here
                    // would report a successful cancellation while the message still sends.
                    if (row.claimed && LIVE_CLAIMS.contains(claimKey(account, owner, row.token))) {
                        throw new IllegalStateException("Scheduled message is already being sent");
                    }
                    journal.entries.remove(id);
                    if(row.claimed) LIVE_CLAIMS.remove(claimKey(account,owner,row.token));
                    changed.add(id);
                }
            }
            if (!changed.isEmpty() && !write(account, owner, journal)) return Collections.emptySet();
            return changed;
        }
    }

    /** Edits a queued row in place and returns false once it has been claimed for delivery. */
    public static boolean edit(int account, String owner, long generation, Entry entry) {
        if (!valid(owner, generation) || entry == null) return false;
        synchronized (lock(account)) {
            Journal journal = read(account, owner);
            Row old = journal.entries.get(entry.id);
            if (old == null || old.claimed || old.entry.peer != entry.peer) return false;
            journal.entries.put(entry.id, new Row(entry.withDue(entry.dueAt, old.entry.attempts)));
            return write(account, owner, journal);
        }
    }

    /** Completes one delivery. Repeating messages keep their identity and advance from their prior due time. */
    public static Completion complete(Claim claim, long now) {
        if (claim == null || !valid(claim.owner, claim.generation)) return new Completion(false, null);
        synchronized (lock(claim.account)) {
            Journal journal = read(claim.account, claim.owner);
            Row row = journal.entries.get(claim.entry.id);
            if (!matches(row, claim)) { LIVE_CLAIMS.remove(claimKey(claim.account,claim.owner,claim.token));return new Completion(false, null); }
            Entry next = null;
            if (row.entry.repeatPeriod == 0) journal.entries.remove(row.entry.id);
            else {
                next = row.entry.nextOccurrence(nextDue(row.entry.occurrenceAt, row.entry.repeatPeriod, now));
                journal.entries.put(row.entry.id, new Row(next));
            }
            boolean saved=write(claim.account, claim.owner, journal);
            if(saved) LIVE_CLAIMS.remove(claimKey(claim.account,claim.owner,claim.token));
            return new Completion(saved, saved ? next : null);
        }
    }

    /** Releases one failed delivery for retry while retaining its selected encryption mode and draft. */
    public static boolean fail(Claim claim, long now) {
        if (claim == null || !valid(claim.owner, claim.generation)) return false;
        synchronized (lock(claim.account)) {
            Journal journal = read(claim.account, claim.owner);
            Row row = journal.entries.get(claim.entry.id);
            if (!matches(row, claim)) { LIVE_CLAIMS.remove(claimKey(claim.account,claim.owner,claim.token));return false; }
            row.release(now, retryDelay(row.entry.attempts));
            boolean saved=write(claim.account, claim.owner, journal);
            if(saved) LIVE_CLAIMS.remove(claimKey(claim.account,claim.owner,claim.token));
            return saved;
        }
    }

    /** Drops stale in-flight leases after logout/account replacement without deleting the durable draft. */
    public static void releaseGeneration(int account, String owner, long generation) {
        if (owner == null || owner.isEmpty()) return;
        synchronized (lock(account)) {
            Journal journal = read(account, owner);
            boolean changed = false;
            for (Row row : journal.entries.values()) if (row.claimed && row.generation == generation) {
                LIVE_CLAIMS.remove(claimKey(account,owner,row.token));
                row.claimed = false; row.claimedAt = 0; row.generation = 0; row.token = ""; changed = true;
            }
            if (changed) write(account, owner, journal);
        }
    }

    /** Native scheduled-history projection. The existing UI supplies users/chats from its normal cache. */
    public static TLRPC.TL_messages_messages history(int account, String owner, long generation, long peer) {
        TLRPC.TL_messages_messages result = new TLRPC.TL_messages_messages();
        for (Entry entry : list(account, owner, generation, peer)) result.messages.add(project(entry));
        return result;
    }

    public static TLRPC.Message project(Entry entry) { return project(entry, 0); }

    /** Builds a native scheduled projection with the real local sender when known. */
    public static TLRPC.Message project(Entry entry, long self) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = entry.id;
        message.dialog_id = entry.peer;
        message.date = (int) Math.min(Integer.MAX_VALUE, Math.max(0, entry.dueAt / 1000L));
        message.message = entry.body;
        message.out = true;
        message.from_scheduled = true;
        message.silent = entry.silent;
        message.entities = new ArrayList<>(entry.entities);
        if (!message.entities.isEmpty()) message.flags |= 128;
        message.peer_id = peer(entry.peer);
        if (self > 0) {
            TLRPC.TL_peerUser sender = new TLRPC.TL_peerUser(); sender.user_id = self;
            message.from_id = sender;
            message.flags |= 256;
        }
        if (entry.repeatPeriod > 0) {
            message.schedule_repeat_period = entry.repeatPeriod;
            message.flags2 |= 1024;
        }
        message.send_state = 0;
        message.params = new HashMap<>();
        message.params.put("monogram-scheduled", "1");
        message.params.put("monogram-scheduled-encryption", entry.encryption);
        if (entry.manualEncryption) message.params.put("monogram-scheduled-manual-encryption", "1");
        return message;
    }

    private static Set<Integer> updateDue(int account, String owner, long generation, long peer, List<Integer> ids, long dueAt, boolean resetAttempts) {
        if (!valid(owner, generation) || ids == null || ids.isEmpty()) return Collections.emptySet();
        synchronized (lock(account)) {
            Journal journal = read(account, owner);
            HashSet<Integer> changed = new HashSet<>();
            for (Integer id : ids) {
                Row row = journal.entries.get(id);
                if (row == null || peer != 0 && row.entry.peer != peer || row.claimed && LIVE_CLAIMS.contains(claimKey(account,owner,row.token))) continue;
                int attempts = resetAttempts ? 0 : row.entry.attempts;
                row.entry = row.entry.withDue(dueAt, attempts);
                row.claimed = false; row.claimedAt = 0; row.generation = 0; row.token = "";
                changed.add(id);
            }
            if (!changed.isEmpty() && !write(account, owner, journal)) return Collections.emptySet();
            return changed;
        }
    }

    private static boolean matches(Row row, Claim claim) {
        return row != null && row.claimed && row.generation == claim.generation && claim.token.equals(row.token);
    }

    private static long nextDue(long previous, int periodSeconds, long now) {
        long period = Math.max(1L, periodSeconds) * 1000L;
        long next = previous + period;
        if (next > now) return next;
        long missed = Math.max(0L, (now - next) / period) + 1L;
        return next + missed * period;
    }

    private static long stableRandomId(int id, long createdAt, long occurrenceAt) {
        long value = (((long) id) << 32) ^ createdAt ^ Long.rotateLeft(occurrenceAt, 17) ^ 0x6a09e667f3bcc909L;
        value ^= value >>> 33; value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33; value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return value == 0 ? 1 : value;
    }

    private static long retryDelay(int attempts) {
        int bounded = Math.min(6, Math.max(0, attempts));
        return Math.min(RETRY_MAX_MS, RETRY_MIN_MS << bounded);
    }

    private static TLRPC.Peer peer(long dialog) {
        if (dialog > 0) {
            TLRPC.TL_peerUser peer = new TLRPC.TL_peerUser(); peer.user_id = dialog; return peer;
        }
        TLRPC.TL_peerChat peer = new TLRPC.TL_peerChat(); peer.chat_id = -dialog; return peer;
    }

    private static boolean valid(String owner, long generation) {
        // accountEpoch starts at zero for a freshly created controller; zero is a valid lease
        // generation until the first account replacement increments it.
        return owner != null && !owner.isEmpty();
    }

    private static Object lock(int account) {
        synchronized (LOCKS) { return LOCKS.computeIfAbsent(account, ignored -> new Object()); }
    }

    private static SharedPreferences preferences(int account) {
        return ApplicationLoader.applicationContext.getSharedPreferences("monogram-scheduled-" + account, Context.MODE_PRIVATE);
    }

    private static String key(String owner) { return "v" + VERSION + "\n" + owner; }
    private static String idKey(String owner) { return key(owner) + "\nnext-id"; }

    private static int nextId(int id) {
        return id == Integer.MAX_VALUE ? 1 : Math.max(1, id + 1);
    }

    private static Journal read(int account, String owner) {
        Journal journal = new Journal();
        String encoded = preferences(account).getString(key(owner), null);
        if (encoded == null || encoded.isEmpty()) return journal;
        try {
            JSONArray rows = new JSONArray(encoded);
            for (int index = 0; index < rows.length(); index++) {
                Row row = Row.decode(rows.getJSONObject(index));
                if (row == null || row.entry.peer == 0) continue;
                // Builds before positive native projections persisted the temporary negative
                // outbox id as the row id. Keep those drafts during the one-way migration.
                if (row.entry.id < 0) {
                    int migrated = row.entry.id == Integer.MIN_VALUE ? Integer.MAX_VALUE : -row.entry.id;
                    while (journal.entries.containsKey(migrated)) migrated = nextId(migrated);
                    Entry legacy = row.entry;
                    row.entry = new Entry(migrated, legacy.id, legacy.peer, legacy.destination, legacy.body,
                            legacy.entities, legacy.replyTo, legacy.encryption, legacy.manualEncryption,
                            legacy.noWebpage, legacy.silent, legacy.createdAt, legacy.dueAt,
                            legacy.repeatPeriod, legacy.attempts, legacy.randomId, legacy.payload, legacy.occurrenceAt);
                }
                if (row.entry.id > 0 && row.entry.localId < 0) journal.entries.put(row.entry.id, row);
            }
        } catch (Exception ignored) {
            // A corrupt local queue must not prevent logging in or erase valid rows on read.
        }
        return journal;
    }

    private static boolean write(int account, String owner, Journal journal) {
        try {
            JSONArray rows = new JSONArray();
            ArrayList<Row> ordered = new ArrayList<>(journal.entries.values());
            ordered.sort(Comparator.comparingLong(row -> row.entry.dueAt));
            for (Row row : ordered) rows.put(row.encode());
            return preferences(account).edit().putString(key(owner), rows.toString()).commit();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static final class Journal {
        final HashMap<Integer, Row> entries = new HashMap<>();
    }

    private static final class Row {
        Entry entry;
        boolean claimed;
        long claimedAt, generation;
        String token = "";

        Row(Entry entry) { this.entry = entry; }

        void release(long now, long delay) {
            entry = entry.withRetryAt(Math.max(entry.dueAt, now + delay), entry.attempts + 1);
            claimed = false; claimedAt = 0; generation = 0; token = "";
        }

        JSONObject encode() throws Exception {
            JSONObject row = new JSONObject();
            row.put("id", entry.id).put("local", entry.localId).put("peer", entry.peer).put("to", entry.destination).put("body", entry.body)
                    .put("entities", encodeObjects(entry.entities)).put("reply", encodeObject(entry.replyTo))
                    .put("encryption", entry.encryption).put("manual", entry.manualEncryption)
                    .put("noWebpage", entry.noWebpage).put("silent", entry.silent).put("created", entry.createdAt)
                    .put("due", entry.dueAt).put("occurrence", entry.occurrenceAt).put("repeat", entry.repeatPeriod).put("attempts", entry.attempts)
                    .put("payload", entry.payload).put("rid", entry.randomId).put("claimed", claimed).put("claimedAt", claimedAt)
                    .put("generation", generation).put("token", token);
            return row;
        }

        static Row decode(JSONObject row) {
            try {
                int id = row.getInt("id"); long peer = row.getLong("peer");
                Entry entry = new Entry(id, row.optInt("local"), peer, row.optString("to"), row.optString("body"),
                        decodeEntities(row.optString("entities")), decodeReply(row.optString("reply")),
                        row.optString("encryption"), row.optBoolean("manual"), row.optBoolean("noWebpage"),
                        row.optBoolean("silent"), row.optLong("created"), row.getLong("due"),
                        row.optInt("repeat"), row.optInt("attempts"), row.optLong("rid"), row.optString("payload"),
                        row.optLong("occurrence", row.getLong("due")));
                Row result = new Row(entry);
                result.claimed = row.optBoolean("claimed"); result.claimedAt = row.optLong("claimedAt");
                result.generation = row.optLong("generation"); result.token = row.optString("token");
                return result;
            } catch (Exception ignored) { return null; }
        }
    }

    private static String encodeObjects(List<? extends TLObject> objects) throws Exception {
        JSONArray result = new JSONArray();
        if (objects != null) for (TLObject object : objects) result.put(encodeObject(object));
        return result.toString();
    }

    private static String encodeObject(TLObject object) throws Exception {
        if (object == null) return "";
        NativeByteBuffer buffer = new NativeByteBuffer(object.getObjectSize());
        try {
            object.serializeToStream(buffer);
            byte[] bytes = new byte[buffer.position()]; buffer.position(0); buffer.readBytes(bytes, true);
            return Base64.encodeToString(bytes, Base64.NO_WRAP);
        } finally { buffer.reuse(); }
    }

    private static ArrayList<TLRPC.MessageEntity> decodeEntities(String encoded) {
        ArrayList<TLRPC.MessageEntity> result = new ArrayList<>();
        try {
            JSONArray values = new JSONArray(encoded);
            for (int index = 0; index < values.length(); index++) {
                byte[] bytes = Base64.decode(values.optString(index), Base64.NO_WRAP);
                NativeByteBuffer buffer = new NativeByteBuffer(bytes.length);
                try {
                    buffer.writeBytes(bytes); buffer.position(0);
                    TLRPC.MessageEntity entity = TLRPC.MessageEntity.TLdeserialize(buffer, buffer.readInt32(true), false);
                    if (entity != null) result.add(entity);
                } finally { buffer.reuse(); }
            }
        } catch (Exception ignored) { result.clear(); }
        return result;
    }

    private static TLRPC.InputReplyTo decodeReply(String encoded) {
        if (encoded == null || encoded.isEmpty()) return null;
        try {
            byte[] bytes = Base64.decode(encoded, Base64.NO_WRAP);
            NativeByteBuffer buffer = new NativeByteBuffer(bytes.length);
            try {
                buffer.writeBytes(bytes); buffer.position(0);
                return TLRPC.InputReplyTo.TLdeserialize(buffer, buffer.readInt32(true), false);
            } finally { buffer.reuse(); }
        } catch (Exception ignored) { return null; }
    }
}
