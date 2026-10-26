// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.util.Base64;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.telegram.tgnet.TLRPC;

/**
 * A bounded, data-only checklist envelope for XMPP.
 *
 * <p>The wire data deliberately is not a serialized {@link TLRPC.TodoList}. Telegram constructors
 * and file references are not an interoperable XMPP protocol and accepting them from the network
 * would couple a received stanza to arbitrary TL deserialization. The readable body is Markdown;
 * this envelope is only a projection hint for Monogram clients and must be carried in an
 * authenticated stanza (OMEMO plaintext, or a server-authenticated cleartext stanza).</p>
 */
public final class MonogramChecklists {
    public static final String NAMESPACE = "urn:monogram:checklist:1";
    public static final String ELEMENT = "checklist";
    public static final String MUTATION_ELEMENT = "checklist-update";
    public static final int MAX_ITEMS = 512;
    public static final int MAX_TEXT_BYTES = 32 * 1024;
    public static final int MAX_TEXT_FIELD_BYTES = 4096;
    private static final int FORMAT = 1;
    private static final int MUTATION_FORMAT = 1;
    private static final SecureRandom RANDOM = new SecureRandom();

    private MonogramChecklists() {}

    public static final class Invalid extends IllegalArgumentException {
        private Invalid(String message) { super(message); }
        private Invalid(String message, Throwable cause) { super(message, cause); }
    }

    /** Immutable checklist state. {@code owner} is the authenticated bare JID of the creator. */
    public static final class Checklist {
        public final String owner;
        public final long revision;
        public final boolean othersCanAppend;
        public final boolean othersCanComplete;
        public final String title;
        public final List<Item> items;

        public Checklist(String owner, long revision, boolean othersCanAppend, boolean othersCanComplete,
                String title, List<Item> items) {
            this.owner = bare(owner);
            if (revision < 1) throw new Invalid("Invalid checklist revision");
            this.revision = revision;
            this.othersCanAppend = othersCanAppend;
            this.othersCanComplete = othersCanComplete;
            this.title = text(title, "checklist title");
            if (items == null || items.size() > MAX_ITEMS) throw new Invalid("Too many checklist items");
            ArrayList<Item> copy = new ArrayList<>(items.size());
            Set<Integer> ids = new HashSet<>();
            for (Item item : items) {
                if (item == null || item.id <= 0 || !ids.add(item.id)) throw new Invalid("Invalid checklist item");
                copy.add(item);
            }
            if (encodedBytes(this.owner, this.title, copy) > MAX_TEXT_BYTES) throw new Invalid("Checklist is too large");
            this.items = Collections.unmodifiableList(copy);
        }
    }

    /** Completed-by is an authenticated bare JID, not a Telegram peer id. */
    public static final class Item {
        public final int id;
        public final String title;
        public final boolean completed;
        public final String completedBy;
        public final int completedAt;

        public Item(int id, String title, boolean completed, String completedBy, int completedAt) {
            if (id <= 0) throw new Invalid("Invalid checklist item id");
            this.id = id;
            this.title = text(title, "checklist item");
            if (completed) {
                this.completedBy = bare(completedBy);
                if (completedAt < 0) throw new Invalid("Invalid checklist completion time");
                this.completedAt = completedAt;
            } else {
                this.completedBy = null;
                this.completedAt = 0;
            }
            this.completed = completed;
        }
    }

    /** Resolves a completion's authenticated bare JID to the native local peer id used by the UI. */
    public interface CompletionPeerResolver {
        long resolve(String bareJid);
    }

    public enum MutationType { TOGGLE, APPEND }

    /**
     * A mutation references the XMPP stanza id of the checklist. The caller must bind
     * {@code actor} to the authenticated stanza sender; it is intentionally not stored here.
     */
    public static final class Mutation {
        public final MutationType type;
        public final String reference;
        public final long baseRevision;
        public final long nonce;
        public final int itemId;
        public final boolean completed;
        public final String itemTitle;

        private Mutation(MutationType type, String reference, long baseRevision, long nonce,
                int itemId, boolean completed, String itemTitle) {
            this.type = Objects.requireNonNull(type);
            this.reference = reference(reference);
            if (baseRevision < 1) throw new Invalid("Invalid checklist mutation revision");
            this.baseRevision = baseRevision;
            if (nonce == 0) throw new Invalid("Invalid checklist mutation nonce");
            this.nonce = nonce;
            if (itemId <= 0) throw new Invalid("Invalid checklist item id");
            this.itemId = itemId;
            this.completed = completed;
            this.itemTitle = type == MutationType.APPEND ? text(itemTitle, "checklist item") : null;
        }
    }

    public static Checklist from(TLRPC.TodoList todo, String owner) {
        if (todo == null) throw new Invalid("Missing checklist");
        ArrayList<Item> items = new ArrayList<>();
        for (TLRPC.TodoItem input : todo.list == null ? Collections.<TLRPC.TodoItem>emptyList() : todo.list) {
            if (input == null || input.title == null) throw new Invalid("Invalid checklist item");
            items.add(new Item(input.id, input.title.text, false, null, 0));
        }
        if (todo.title == null) throw new Invalid("Missing checklist title");
        return new Checklist(owner, 1, todo.others_can_append, todo.others_can_complete, todo.title.text, items);
    }

    /** Converts the data-only model back to the native media consumed by the existing checklist UI. */
    public static TLRPC.TL_messageMediaToDo media(Checklist checklist, long completionUserId) {
        if (completionUserId < 0) throw new Invalid("Invalid completion user");
        return media(checklist, ignored -> completionUserId);
    }

    /**
     * Projects each completion through the caller's existing XMPP-contact mapping. A failed lookup
     * uses peer id 0 only for rendering; the authoritative JID stays in the authenticated envelope.
     */
    public static TLRPC.TL_messageMediaToDo media(Checklist checklist, CompletionPeerResolver peers) {
        if (checklist == null || peers == null) throw new Invalid("Missing checklist peer mapping");
        TLRPC.TL_messageMediaToDo media = new TLRPC.TL_messageMediaToDo();
        media.todo = new TLRPC.TodoList();
        media.todo.others_can_append = checklist.othersCanAppend;
        media.todo.others_can_complete = checklist.othersCanComplete;
        media.todo.title = textWithEntities(checklist.title);
        for (Item source : checklist.items) {
            TLRPC.TodoItem item = new TLRPC.TodoItem();
            item.id = source.id;
            item.title = textWithEntities(source.title);
            media.todo.list.add(item);
            if (source.completed) {
                TLRPC.TL_todoCompletion completion = new TLRPC.TL_todoCompletion();
                completion.id = source.id;
                completion.date = source.completedAt;
                TLRPC.TL_peerUser peer = new TLRPC.TL_peerUser();
                long peerId = peers.resolve(source.completedBy);
                peer.user_id = peerId > 0 ? peerId : 0;
                completion.completed_by = peer;
                media.completions.add(completion);
            }
        }
        if (!media.completions.isEmpty()) media.flags |= 1;
        return media;
    }

    /** Applies the native media projection without changing the interoperable Markdown body. */
    public static boolean project(TLRPC.Message message, Checklist checklist, long completionUserId) {
        if (message == null || checklist == null) return false;
        message.media = media(checklist, completionUserId);
        message.flags |= TLRPC.MESSAGE_FLAG_HAS_MEDIA;
        return true;
    }

    public static boolean project(TLRPC.Message message, Checklist checklist, CompletionPeerResolver peers) {
        if (message == null || checklist == null || peers == null) return false;
        message.media = media(checklist, peers);
        message.flags |= TLRPC.MESSAGE_FLAG_HAS_MEDIA;
        return true;
    }

    /** Readable fallback for non-Monogram XMPP clients. */
    public static String markdown(Checklist checklist) {
        if (checklist == null) throw new Invalid("Missing checklist");
        StringBuilder out = new StringBuilder();
        if (!checklist.title.isEmpty()) out.append("## ").append(markdownText(checklist.title)).append("\n\n");
        for (Item item : checklist.items) {
            out.append(item.completed ? "- [x] " : "- [ ] ").append(markdownText(item.title)).append('\n');
        }
        if (out.length() == 0) out.append("## Checklist\n");
        return out.toString().trim();
    }

    public static String encode(Checklist checklist) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(0x4d434c31); // MCL1
            output.writeByte(FORMAT);
            writeText(output, checklist.owner);
            output.writeLong(checklist.revision);
            output.writeBoolean(checklist.othersCanAppend);
            output.writeBoolean(checklist.othersCanComplete);
            writeText(output, checklist.title);
            output.writeShort(checklist.items.size());
            for (Item item : checklist.items) {
                output.writeInt(item.id);
                writeText(output, item.title);
                output.writeBoolean(item.completed);
                if (item.completed) {
                    writeText(output, item.completedBy);
                    output.writeInt(item.completedAt);
                }
            }
            output.flush();
            byte[] raw = bytes.toByteArray();
            if (raw.length > MAX_TEXT_BYTES) throw new Invalid("Checklist is too large");
            return Base64.encodeToString(raw, Base64.NO_WRAP | Base64.URL_SAFE);
        } catch (IOException impossible) {
            throw new Invalid("Unable to encode checklist", impossible);
        }
    }

    public static Checklist decode(String value) {
        if (value == null || value.length() > MAX_TEXT_BYTES * 2) return null;
        try {
            byte[] raw = Base64.decode(value, Base64.NO_WRAP | Base64.URL_SAFE);
            if (raw.length > MAX_TEXT_BYTES) return null;
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(raw));
            if (input.readInt() != 0x4d434c31 || input.readUnsignedByte() != FORMAT) return null;
            String owner = readText(input);
            long revision = input.readLong();
            boolean append = input.readBoolean();
            boolean complete = input.readBoolean();
            String title = readText(input);
            int count = input.readUnsignedShort();
            if (count > MAX_ITEMS) return null;
            ArrayList<Item> items = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int id = input.readInt();
                String itemTitle = readText(input);
                boolean completed = input.readBoolean();
                items.add(new Item(id, itemTitle, completed, completed ? readText(input) : null,
                        completed ? input.readInt() : 0));
            }
            if (input.available() != 0) return null;
            return new Checklist(owner, revision, append, complete, title, items);
        } catch (IllegalArgumentException | IOException invalid) {
            return null;
        }
    }

    public static Mutation toggle(String reference, Checklist checklist, int itemId, boolean completed) {
        if (checklist == null || find(checklist, itemId) == null) throw new Invalid("Unknown checklist item");
        return new Mutation(MutationType.TOGGLE, reference, checklist.revision, nonce(), itemId, completed, null);
    }

    public static Mutation append(String reference, Checklist checklist, int itemId, String title) {
        if (checklist == null || find(checklist, itemId) != null) throw new Invalid("Duplicate checklist item");
        return new Mutation(MutationType.APPEND, reference, checklist.revision, nonce(), itemId, false, title);
    }

    public static String encode(Mutation mutation) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(0x4d435531); // MCU1
            output.writeByte(MUTATION_FORMAT);
            output.writeByte(mutation.type.ordinal());
            writeText(output, mutation.reference);
            output.writeLong(mutation.baseRevision);
            output.writeLong(mutation.nonce);
            output.writeInt(mutation.itemId);
            output.writeBoolean(mutation.completed);
            if (mutation.type == MutationType.APPEND) writeText(output, mutation.itemTitle);
            output.flush();
            byte[] raw = bytes.toByteArray();
            if (raw.length > MAX_TEXT_BYTES) throw new Invalid("Checklist update is too large");
            return Base64.encodeToString(raw, Base64.NO_WRAP | Base64.URL_SAFE);
        } catch (IOException impossible) {
            throw new Invalid("Unable to encode checklist update", impossible);
        }
    }

    public static Mutation decodeMutation(String value) {
        if (value == null || value.length() > MAX_TEXT_BYTES * 2) return null;
        try {
            byte[] raw = Base64.decode(value, Base64.NO_WRAP | Base64.URL_SAFE);
            if (raw.length > MAX_TEXT_BYTES) return null;
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(raw));
            if (input.readInt() != 0x4d435531 || input.readUnsignedByte() != MUTATION_FORMAT) return null;
            int type = input.readUnsignedByte();
            if (type < 0 || type >= MutationType.values().length) return null;
            Mutation mutation = new Mutation(MutationType.values()[type], readText(input), input.readLong(),
                    input.readLong(), input.readInt(), input.readBoolean(),
                    type == MutationType.APPEND.ordinal() ? readText(input) : null);
            return input.available() == 0 ? mutation : null;
        } catch (IllegalArgumentException | IOException invalid) {
            return null;
        }
    }

    /**
     * Returns a new state only for a mutation from an authenticated actor who is allowed by the
     * checklist creator. Callers must persist mutation nonces per checklist to make retries idempotent.
     */
    public static Checklist apply(Checklist current, Mutation mutation, String authenticatedActor, int now) {
        if (current == null || mutation == null || mutation.baseRevision != current.revision) return null;
        String actor;
        try { actor = bare(authenticatedActor); } catch (Invalid invalid) { return null; }
        if (now < 0) return null;
        ArrayList<Item> items = new ArrayList<>(current.items);
        if (mutation.type == MutationType.TOGGLE) {
            if (!current.owner.equals(actor) && !current.othersCanComplete) return null;
            int index = indexOf(items, mutation.itemId);
            if (index < 0) return null;
            Item previous = items.get(index);
            if (previous.completed == mutation.completed) return current;
            items.set(index, new Item(previous.id, previous.title, mutation.completed,
                    mutation.completed ? actor : null, mutation.completed ? now : 0));
        } else if (mutation.type == MutationType.APPEND) {
            if (!current.owner.equals(actor) && !current.othersCanAppend) return null;
            if (indexOf(items, mutation.itemId) >= 0 || items.size() >= MAX_ITEMS) return null;
            items.add(new Item(mutation.itemId, mutation.itemTitle, false, null, 0));
        } else {
            return null;
        }
        if (current.revision == Long.MAX_VALUE) return null;
        try {
            return new Checklist(current.owner, current.revision + 1, current.othersCanAppend,
                    current.othersCanComplete, current.title, items);
        } catch (Invalid invalid) { return null; }
    }

    /** Owners may replace title, permissions, and items via an XEP-0308 message replacement. */
    public static boolean canReplace(Checklist current, String authenticatedActor) {
        if (current == null) return false;
        try { return current.owner.equals(bare(authenticatedActor)); }
        catch (Invalid invalid) { return false; }
    }

    public static Checklist replacement(Checklist previous, TLRPC.TodoList replacement, String authenticatedActor) {
        if (!canReplace(previous, authenticatedActor) || replacement == null || replacement.title == null) return null;
        ArrayList<Item> items = new ArrayList<>();
        for (TLRPC.TodoItem item : replacement.list == null ? Collections.<TLRPC.TodoItem>emptyList() : replacement.list) {
            if (item == null || item.title == null) return null;
            Item old = find(previous, item.id);
            items.add(new Item(item.id, item.title.text, old != null && old.completed,
                    old == null ? null : old.completedBy, old == null ? 0 : old.completedAt));
        }
        try {
            return new Checklist(previous.owner, previous.revision + 1, replacement.others_can_append,
                    replacement.others_can_complete, replacement.title.text, items);
        } catch (Invalid invalid) {
            return null;
        }
    }

    private static TLRPC.TL_textWithEntities textWithEntities(String value) {
        TLRPC.TL_textWithEntities result = new TLRPC.TL_textWithEntities();
        result.text = value;
        return result;
    }

    private static Item find(Checklist checklist, int id) {
        for (Item item : checklist.items) if (item.id == id) return item;
        return null;
    }

    private static int indexOf(List<Item> items, int id) {
        for (int i = 0; i < items.size(); i++) if (items.get(i).id == id) return i;
        return -1;
    }

    private static long nonce() {
        long value;
        do { value = RANDOM.nextLong(); } while (value == 0);
        return value;
    }

    private static String markdownText(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ("\\`*_{}[]<>#|".indexOf(c) >= 0) escaped.append('\\');
            escaped.append(c);
        }
        return escaped.toString().replace("\n", "<br>");
    }

    private static int encodedBytes(String owner, String title, List<Item> items) {
        // Include magic/version, revisions/flags, count, every length prefix and item metadata.
        // Counting only text accepted states which encode() could not put on the wire.
        int size = 21 + bytes(owner) + bytes(title);
        for (Item item : items) size += 7 + bytes(item.title) + (item.completed ? 6 + bytes(item.completedBy) : 0);
        return size;
    }

    private static int bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }

    private static String text(String value, String name) {
        if (value == null) throw new Invalid("Missing " + name);
        if (bytes(value) > MAX_TEXT_FIELD_BYTES || value.codePoints().anyMatch(Character::isISOControl))
            throw new Invalid("Invalid " + name);
        return value;
    }

    private static String bare(String value) {
        if (value == null) throw new Invalid("Missing checklist owner");
        if (value.length() > 1024 || value.codePoints().anyMatch(Character::isISOControl))
            throw new Invalid("Invalid checklist owner");
        try { return org.jxmpp.jid.impl.JidCreate.entityFrom(value).asEntityBareJid().toString(); }
        catch (org.jxmpp.stringprep.XmppStringprepException invalid) { throw new Invalid("Invalid checklist owner", invalid); }
    }

    private static String reference(String value) {
        if (value == null || value.isEmpty() || value.length() > 1024 || value.codePoints().anyMatch(Character::isISOControl))
            throw new Invalid("Invalid checklist message reference");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x21 || c > 0x7e || c == '\'' || c == '"' || c == '<' || c == '>' || c == '&')
                throw new Invalid("Invalid checklist message reference");
        }
        return value;
    }

    private static void writeText(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT_FIELD_BYTES) throw new Invalid("Checklist text is too long");
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    private static String readText(DataInputStream input) throws IOException {
        int length = input.readUnsignedShort();
        if (length > MAX_TEXT_FIELD_BYTES || length > input.available()) throw new EOFException();
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        String value = new String(bytes, StandardCharsets.UTF_8);
        if (!java.util.Arrays.equals(bytes, value.getBytes(StandardCharsets.UTF_8))) throw new IOException("Invalid UTF-8");
        return value;
    }
}
