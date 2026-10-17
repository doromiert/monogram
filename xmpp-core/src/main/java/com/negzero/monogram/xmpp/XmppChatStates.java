// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.packet.ExtensionElement;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Presence;
import org.jivesoftware.smack.packet.StandardExtensionElement;
import org.jivesoftware.smack.provider.ProviderManager;
import org.jivesoftware.smackx.chatstates.ChatState;
import org.jivesoftware.smackx.chatstates.packet.ChatStateExtension;
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager;
import org.jxmpp.jid.EntityBareJid;
import org.jxmpp.jid.EntityFullJid;
import org.jxmpp.jid.Jid;
import org.jxmpp.jid.impl.JidCreate;

/** Ephemeral XEP-0085 state. No bodies, archive records, receipts or OMEMO payloads are generated here. */
public final class XmppChatStates implements AutoCloseable {
    public static final String NAMESPACE = "http://jabber.org/protocol/chatstates";
    public static final int TYPING_TIMEOUT_MILLIS = 120000;
    private final XMPPConnection connection;
    private final BiConsumer<EntityBareJid, Boolean> listener;
    private final XmppRooms rooms;
    private final BiConsumer<EntityFullJid, Boolean> roomListener;
    private final Map<EntityFullJid, RoomTyping> roomComposing = new LinkedHashMap<>();
    private static final class RoomTyping {
        final String identity;
        final long until;
        RoomTyping(String identity, long until) { this.identity = identity; this.until = until; }
    }
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "Monogram chat states"); thread.setDaemon(true); return thread;
    });
    private final Map<EntityBareJid, Conversation> conversations = new LinkedHashMap<>();
    private boolean enabled = true, closed;
    private long generation, nextTypingRefresh;
    private static final class Conversation {
        final Map<String, Long> composing = new LinkedHashMap<>();
        Jid target;
        Boolean supported;
        boolean explicit, discovering;
        ChatState desired, sent;
        long inputAt, activityAt;
    }
    XmppChatStates(XMPPConnection connection, BiConsumer<EntityBareJid, Boolean> listener) {
        this(connection, listener, null, (occupant, composing) -> {});
    }
    XmppChatStates(XMPPConnection connection, BiConsumer<EntityBareJid, Boolean> listener, XmppRooms rooms, BiConsumer<EntityFullJid, Boolean> roomListener) {
        this.connection = connection; this.listener = listener; this.rooms = rooms; this.roomListener = roomListener;
        for (ChatState state : ChatState.values()) ProviderManager.addExtensionProvider(state.name(), NAMESPACE,
                org.jivesoftware.smack.parsing.StandardExtensionElementProvider.INSTANCE);
        ServiceDiscoveryManager.getInstanceFor(connection).addFeature(NAMESPACE);
        connection.addSyncStanzaListener(stanza -> receive((Message) stanza), stanza -> stanza instanceof Message);
        connection.addSyncStanzaListener(stanza -> unavailable((Presence) stanza),
                stanza -> stanza instanceof Presence && ((Presence) stanza).getType() == Presence.Type.unavailable);
        worker.scheduleAtFixedRate(() -> tick(now()), 1, 1, TimeUnit.SECONDS);
    }
    private static long now() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()); }
    private static EntityBareJid bare(String jid) {
        try { return JidCreate.entityBareFrom(jid); }
        catch (Exception invalid) { throw new IllegalArgumentException("Invalid chat address", invalid); }
    }
    private Conversation conversation(EntityBareJid peer) {
        Conversation chat = conversations.get(peer);
        if (chat == null) {
            if (conversations.size() >= 256) {
                EntityBareJid oldest = conversations.keySet().iterator().next();
                Conversation removed = conversations.remove(oldest);
                if (!removed.composing.isEmpty()) listener.accept(oldest, false);
            }
            conversations.put(peer, chat = new Conversation());
        }
        return chat;
    }
    public void open(String jid) { change(bare(jid), ChatState.active); }
    public void input(String jid, boolean composing) { change(bare(jid), composing ? ChatState.composing : ChatState.active); }
    public void inactive(String jid) { change(bare(jid), ChatState.inactive); }
    public void gone(String jid) { change(bare(jid), ChatState.gone); }
    private synchronized void change(EntityBareJid peer, ChatState state) {
        if (closed || !enabled || connection.getUser() == null || peer.equals(connection.getUser().asEntityBareJid())) return;
        Conversation chat = conversation(peer);
        if (rooms != null && rooms.known(peer)) {
            if (!rooms.canSendChatState(peer)) return;
            // XEP-0085 §5.5: closing a room view must not announce departure from the room.
            if (state == ChatState.gone) state = ChatState.inactive;
            chat.target = peer; chat.supported = true;
        }
        chat.inputAt = chat.activityAt = now();
        boolean changed = chat.desired != state;
        chat.desired = state;
        if (chat.target == null) {
            Presence presence = org.jivesoftware.smack.roster.Roster.getInstanceFor(connection).getPresence(peer);
            if (presence.isAvailable() && presence.getFrom() != null && presence.getFrom().isEntityFullJid()) chat.target = presence.getFrom();
        }
        if (chat.target != null && chat.supported == null && !chat.discovering) {
            chat.discovering = true;
            Jid target = chat.target; long epoch = generation;
            worker.execute(() -> discover(peer, target, epoch));
        }
        if (changed) worker.execute(() -> flush(peer));
    }
    private void discover(EntityBareJid peer, Jid target, long epoch) {
        Boolean supported = null;
        try { supported = ServiceDiscoveryManager.getInstanceFor(connection).supportsFeature(target, NAMESPACE); }
        catch (Exception unavailable) { /* Implicit negotiation through live content still works. */ }
        synchronized (this) {
            Conversation chat = conversations.get(peer);
            if (closed || epoch != generation || chat == null || !target.equals(chat.target)) return;
            chat.discovering = false;
            if (chat.supported == null && supported != null) { chat.supported = supported; chat.explicit = true; }
        }
        flush(peer);
    }
    /** Called before sending a content message, including encrypted content. */
    public synchronized void decorate(Message message) {
        if (closed || !enabled || message.getTo() == null || (message.getType() != Message.Type.chat && message.getType() != Message.Type.groupchat)) return;
        EntityBareJid peer = message.getTo().asEntityBareJidIfPossible();
        if (peer == null || connection.getUser() == null || peer.equals(connection.getUser().asEntityBareJid())) return;
        if (message.getType() == Message.Type.groupchat && (rooms == null || !rooms.canSendChatState(peer))) return;
        Conversation chat = conversation(peer);
        if (!Boolean.FALSE.equals(chat.supported)) message.addExtension(new ChatStateExtension(ChatState.active));
    }
    public synchronized void sent(Message message) {
        if (!message.hasExtension(NAMESPACE) || message.getTo() == null) return;
        Conversation chat = conversations.get(message.getTo().asEntityBareJidIfPossible());
        if (chat != null) { chat.sent = chat.desired = ChatState.active; chat.activityAt = now(); }
    }
    static ChatState read(Message message) {
        ChatState state = null; int count = 0;
        for (ExtensionElement element : message.getExtensions()) {
            if (!NAMESPACE.equals(element.getNamespace())) continue;
            if (++count > 1) return null;
            try { state = ChatState.valueOf(element.getElementName()); }
            catch (IllegalArgumentException unknown) { return null; }
            if (element instanceof StandardExtensionElement) {
                StandardExtensionElement value = (StandardExtensionElement) element;
                if (!value.getAttributes().isEmpty() || !value.getElements().isEmpty()
                        || value.getText() != null && !value.getText().trim().isEmpty()) return null;
            }
        }
        boolean content = message.getBody() != null || message.getSubject() != null
                || message.hasExtension("encrypted", "eu.siacs.conversations.axolotl") || message.hasExtension("encrypted", "urn:xmpp:omemo:2");
        return content && state != ChatState.active ? null : state;
    }
    private boolean stale(Message message) {
        return message.hasExtension("urn:xmpp:delay") || message.hasExtension("jabber:x:delay")
                || message.hasExtension("urn:xmpp:forward:0") || message.hasExtension("urn:xmpp:carbons:2")
                || message.hasExtension("urn:xmpp:mam:2") || message.hasExtension("urn:xmpp:mam:1");
    }
    private void receiveRoom(Message message) {
        EntityFullJid from = message.getFrom() == null ? null : message.getFrom().asEntityFullJidIfPossible();
        if (closed || rooms == null || connection.getUser() == null || from == null || !rooms.acceptsChatState(from) || stale(message)
                || message.getTo() != null && !connection.getUser().asBareJid().equals(message.getTo().asBareJid())
                || message.getTo() != null && message.getTo().isEntityFullJid() && !message.getTo().equals(connection.getUser())) return;
        String identity = rooms.chatStateIdentity(from);
        if (identity == null || !rooms.matchesChatStateIdentity(message)) return;
        ChatState state = read(message);
        if (state == ChatState.gone) return;
        boolean content = message.getBody() != null || message.hasExtension("encrypted", "eu.siacs.conversations.axolotl") || message.hasExtension("encrypted", "urn:xmpp:omemo:2");
        if (state == null && !content) return;
        if (state == ChatState.composing) {
            if (roomComposing.size() >= 256 && !roomComposing.containsKey(from)) {
                EntityFullJid oldest = roomComposing.keySet().iterator().next();
                roomComposing.remove(oldest); roomListener.accept(oldest, false);
            }
            RoomTyping previous = roomComposing.put(from, new RoomTyping(identity, now() + TYPING_TIMEOUT_MILLIS));
            boolean added = previous == null || !identity.equals(previous.identity);
            if (added) roomListener.accept(from, true);
        } else if (roomComposing.remove(from) != null) roomListener.accept(from, false);
    }
    synchronized void receive(Message message) {
        if (message.getType() == Message.Type.groupchat) { receiveRoom(message); return; }
        if (closed || connection.getUser() == null || message.getType() != Message.Type.chat || message.getFrom() == null) return;
        EntityBareJid peer = message.getFrom().asEntityBareJidIfPossible();
        if (peer == null || rooms != null && rooms.known(peer) || peer.equals(connection.getUser().asEntityBareJid())
                || message.getTo() != null && !connection.getUser().asEntityBareJid().equals(message.getTo().asEntityBareJidIfPossible())
                || message.getTo() != null && message.getTo().isEntityFullJid() && !message.getTo().equals(connection.getUser())
                || message.hasExtension("urn:xmpp:delay") || message.hasExtension("jabber:x:delay")
                || message.hasExtension("urn:xmpp:forward:0") || message.hasExtension("urn:xmpp:carbons:2")
                || message.hasExtension("urn:xmpp:mam:2") || message.hasExtension("urn:xmpp:mam:1")) return;
        ChatState state = read(message);
        boolean content = message.getBody() != null || message.hasExtension("encrypted", "eu.siacs.conversations.axolotl") || message.hasExtension("encrypted", "urn:xmpp:omemo:2");
        if (state == null && !content) return;
        Conversation chat = conversation(peer);
        boolean wasTyping = !chat.composing.isEmpty();
        String resource = message.getFrom().toString();
        if (state == ChatState.composing) {
            if (chat.composing.size() >= 16 && !chat.composing.containsKey(resource)) chat.composing.remove(chat.composing.keySet().iterator().next());
            chat.composing.put(resource, now() + TYPING_TIMEOUT_MILLIS);
        } else chat.composing.remove(resource);
        if (!message.getFrom().equals(chat.target)) {
            chat.target = message.getFrom(); chat.supported = null; chat.explicit = false; chat.discovering = false; chat.sent = null;
        }
        if (state != null) chat.supported = true;
        else if (content && !message.hasExtension(NAMESPACE) && !chat.explicit) chat.supported = false;
        if (wasTyping != !chat.composing.isEmpty()) listener.accept(peer, !chat.composing.isEmpty());
        if (chat.desired != null && enabled) worker.execute(() -> flush(peer));
    }
    synchronized void unavailable(Presence presence) {
        if (presence.getFrom() == null) return;
        EntityFullJid occupant = presence.getFrom().asEntityFullJidIfPossible();
        if (rooms != null && rooms.ownChatStateOccupant(occupant)) roomLeft(occupant.asEntityBareJid());
        if (occupant != null && roomComposing.remove(occupant) != null) roomListener.accept(occupant, false);
        EntityBareJid peer = presence.getFrom().asEntityBareJidIfPossible();
        Conversation chat = conversations.get(peer);
        if (chat == null) return;
        boolean wasTyping = !chat.composing.isEmpty();
        if (presence.getFrom().isEntityBareJid()) chat.composing.clear(); else chat.composing.remove(presence.getFrom().toString());
        if (wasTyping && chat.composing.isEmpty()) listener.accept(peer, false);
        if (presence.getFrom().equals(chat.target) || presence.getFrom().isEntityBareJid()) {
            chat.target = null; chat.supported = null; chat.explicit = false; chat.sent = null;
        }
    }
    /** A room session cannot carry typing state or queued sends into a later join. */
    synchronized void roomLeft(EntityBareJid room) {
        conversations.remove(room);
        for (EntityFullJid occupant : new ArrayList<>(roomComposing.keySet())) {
            if (occupant.asEntityBareJid().equals(room)) {
                roomComposing.remove(occupant); roomListener.accept(occupant, false);
            }
        }
    }
    void tick(long time) {
        ArrayList<EntityBareJid> peers;
        synchronized (this) {
            if (closed) return;
            for (EntityFullJid occupant : new ArrayList<>(roomComposing.keySet())) {
                RoomTyping typing = roomComposing.get(occupant);
                if (typing.until <= time || rooms == null || !typing.identity.equals(rooms.chatStateIdentity(occupant))) {
                    roomComposing.remove(occupant); roomListener.accept(occupant, false);
                }
            }
            peers = new ArrayList<>(conversations.keySet());
            boolean refresh = time >= nextTypingRefresh;
            if (refresh) nextTypingRefresh = time + 4000;
            if (refresh) for (EntityFullJid occupant : roomComposing.keySet()) roomListener.accept(occupant, true);
            for (EntityBareJid peer : peers) {
                Conversation chat = conversations.get(peer);
                boolean wasTyping = !chat.composing.isEmpty();
                chat.composing.values().removeIf(until -> until <= time);
                if (wasTyping && chat.composing.isEmpty()) listener.accept(peer, false);
                else if (refresh && !chat.composing.isEmpty()) listener.accept(peer, true);
                if (chat.desired == ChatState.composing && time - chat.inputAt >= 5000) chat.desired = ChatState.paused;
                if (chat.desired != null && chat.desired != ChatState.gone && time - chat.activityAt >= 120000) chat.desired = ChatState.inactive;
            }
        }
        for (EntityBareJid peer : peers) flush(peer);
    }
    private void flush(EntityBareJid peer) {
        Message message; ChatState state; Jid target; long epoch; Conversation pending;
        synchronized (this) {
            Conversation chat = conversations.get(peer);
            if (closed || !enabled || !connection.isAuthenticated() || chat == null || !Boolean.TRUE.equals(chat.supported)
                    || chat.target == null || chat.desired == null || chat.desired == chat.sent) return;
            state = chat.desired; target = chat.target; epoch = generation;
            pending = chat;
            boolean room = rooms != null && rooms.known(peer);
            if (room && !rooms.canSendChatState(peer)) return;
            message = new Message(target, room ? Message.Type.groupchat : Message.Type.chat); message.addExtension(new ChatStateExtension(state));
            org.jivesoftware.smackx.hints.element.NoStoreHint.set(message);
        }
        try {
            // Serialize with the privacy switch so disabling cannot leave a queued state send behind.
            synchronized (this) {
                Conversation chat = conversations.get(peer);
                if (closed || !enabled || epoch != generation || chat != pending || chat.desired != state || !target.equals(chat.target) || chat.sent == state
                        || rooms != null && rooms.known(peer) && !rooms.canSendChatState(peer)) return;
                connection.sendStanza(message); chat.sent = state;
            }
        } catch (Exception disconnected) { /* Typing never becomes an offline message or a failed chat bubble. */ }
    }
    public synchronized void setEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value; generation++;
        if (!value) for (Conversation chat : conversations.values()) { chat.desired = null; chat.sent = null; chat.discovering = false; }
    }
    public synchronized void reset() {
        generation++;
        for (Map.Entry<EntityBareJid, Conversation> chat : conversations.entrySet()) if (!chat.getValue().composing.isEmpty()) listener.accept(chat.getKey(), false);
        conversations.clear();
        for (EntityFullJid occupant : roomComposing.keySet()) roomListener.accept(occupant, false);
        roomComposing.clear();
    }
    @Override public synchronized void close() { closed = true; reset(); worker.shutdownNow(); }
}
