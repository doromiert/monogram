// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.io.File;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.Date;
import java.util.Collections;
import java.util.UUID;
import org.jivesoftware.smack.ConnectionConfiguration.SecurityMode;
import org.jivesoftware.smack.ReconnectionManager;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.StandardExtensionElement;
import org.jivesoftware.smack.packet.Stanza;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration;
import org.jivesoftware.smackx.carbons.CarbonManager;
import org.jivesoftware.smackx.mam.MamManager;
import org.jivesoftware.smackx.omemo.OmemoManager;
import org.jivesoftware.smackx.omemo.OmemoMessage;
import org.jivesoftware.smackx.omemo.element.OmemoElement;
import org.jivesoftware.smackx.omemo.element.OmemoElement_VAxolotl;
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;
import org.jivesoftware.smackx.omemo.listener.OmemoMessageListener;
import org.jivesoftware.smackx.omemo.signal.SignalCachingOmemoStore;
import org.jivesoftware.smackx.omemo.signal.SignalFileBasedOmemoStore;
import org.jivesoftware.smackx.omemo.signal.SignalOmemoService;
import org.jivesoftware.smackx.omemo.trust.OmemoFingerprint;
import org.jivesoftware.smackx.receipts.DeliveryReceiptManager;
import org.jivesoftware.smackx.sid.element.OriginIdElement;
import org.jxmpp.jid.EntityBareJid;
import org.jxmpp.jid.impl.JidCreate;

/** Blocking API: callers must use a worker thread. No helper is needed for these operations. */
public final class XmppSession implements AutoCloseable {
    public interface Listener {
        void message(EntityBareJid sender, String id, String body, boolean encrypted);
        default void sentCarbon(EntityBareJid recipient, String id, String body, boolean encrypted) {}
        default void receipt(MessageReceipt receipt) {}
        default void profileChanged(EntityBareJid jid) {}
        default void profileChanged(org.jxmpp.jid.Jid jid) { profileChanged(jid.asEntityBareJidIfPossible()); }
        default void roomMessage(XmppRoomMessage message) {}
        default void roomMessagePending(XmppRoomMessage message) { roomMessage(message); }
        default void messagePending(XmppMessage message) { message(message); }
        default void roomChanged(XmppRooms.State room) {}
        default void roomInvitation(String room,String inviter,String reason,String password) {}
        default void roomInviteFailed(String room,String target,String condition) {}
        default void roomMetadataChanged(String room) {}
        default void bookmarksChanged() {}
        default void reactionRejected(String from,String id) {}
        default void contactRequest(String address,boolean pending) {}
        default void typing(EntityBareJid jid, boolean composing) {}
        default void roomTyping(org.jxmpp.jid.EntityFullJid occupant, boolean composing) {}
        default void disconnected() {}
        default void disconnected(Exception error) { disconnected(); }
        default void reconnected(boolean resumed) {}
        /** Call accepted only once the message has been stored; ratchet acknowledgements follow it. */
        default void message(XmppMessage message, Runnable accepted) { message(message); accepted.run(); }
        default void message(XmppMessage message) {
            if (!message.readable || message.reactions != null) return;
            String id = message.ids.get("stanza");
            if (message.outgoing) sentCarbon(message.peer, id, message.body, message.encrypted);
            else message(message.peer, id, message.body, message.encrypted);
        }
    }
    private static File keyRoot;
    private static final class ManagedConnection extends XMPPTCPConnection {
        ManagedConnection(XMPPTCPConnectionConfiguration config) { super(config); }
        void pingTimedOut() { notifyConnectionError(new java.net.SocketTimeoutException("XMPP server ping timed out")); }
        void setupFailed(Exception error) { notifyConnectionError(error); }
    }
    private final ManagedConnection connection;
    public final XmppProfiles profiles;
    public final XmppRooms rooms;
    public final XmppBookmarks bookmarks;
    public final XmppContacts contacts;
    public final XmppInvitations invitations;
    public final XmppChatStates chatStates;
    public final XmppFingerprints fingerprints;
    private final OmemoManager omemo;
    private final XmppLegacyDiscovery legacyDiscovery;
    private final java.util.Map<String,Long> deviceListRefresh=new java.util.concurrent.ConcurrentHashMap<>();
    private final IdentityTrust trust;
    private final XmppOmemo2 modern;
    private final Listener listener;
    private final java.util.concurrent.ExecutorService crypto = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r,"Monogram OMEMO2"); thread.setDaemon(true); return thread;
    });
    private volatile Exception modernFailure;
    private volatile boolean started, closed;
    private boolean displayedMarkersEnabled;
    private final java.util.LinkedHashSet<String> displayedMarkersSent=new java.util.LinkedHashSet<>();
    private final java.util.concurrent.atomic.AtomicLong lifecycle = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicBoolean checkingConnection = new java.util.concurrent.atomic.AtomicBoolean();

    /** Android may change networks while the old TCP socket still appears authenticated. */
    public void checkConnection() {
        if (closed || !started || !connection.isAuthenticated() || !checkingConnection.compareAndSet(false,true)) return;
        final long generation = lifecycle.get();
        org.jivesoftware.smack.util.Async.go(() -> {
            try {
                boolean alive = org.jivesoftware.smackx.ping.PingManager.getInstanceFor(connection).pingMyServer(false,10000);
                if (!alive && !closed && lifecycle.get()==generation && connection.isAuthenticated()) connection.pingTimedOut();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (org.jivesoftware.smack.SmackException.NotConnectedException disconnected) {
                if (!closed && lifecycle.get()==generation) connection.setupFailed(disconnected);
            } finally { checkingConnection.set(false); }
        }, "Monogram connection check");
    }

    public static synchronized void initializeOmemo(File privateDirectory) throws Exception {
        File root = privateDirectory.getCanonicalFile();
        if (keyRoot != null) {
            if (!root.equals(keyRoot)) throw new IllegalStateException("OMEMO store already initialized elsewhere");
            return;
        }
        if (!root.isDirectory() && !root.mkdirs()) throw new java.io.IOException("Cannot create OMEMO key store");
        OmemoCryptoProvider.install();
        SignalOmemoService.acknowledgeLicense();
        SignalOmemoService.setup();
        ((SignalOmemoService) SignalOmemoService.getInstance()).setOmemoStoreBackend(
                new SignalCachingOmemoStore(new SignalFileBasedOmemoStore(root)));
        keyRoot = root;
    }

    public XmppSession(Account account, String password, int deviceId, File trustFile, Listener listener) throws Exception {
        this(account,password,deviceId,trustFile,null,listener);
    }
    public XmppSession(Account account, String password, int deviceId, File trustFile, Omemo2Device modernDevice, Listener listener) throws Exception {
        if (keyRoot == null) throw new IllegalStateException("Initialize the private OMEMO store first");
        if (deviceId < 1) throw new IllegalArgumentException("OMEMO device ID must be positive and persistent");
        XMPPTCPConnectionConfiguration.Builder config = XMPPTCPConnectionConfiguration.builder()
                .setXmppDomain(account.jid.getDomain().toString()).setUsernameAndPassword(account.jid.getLocalpart(), password)
                .setResource(account.resource).setSecurityMode(SecurityMode.required)
                .setSendPresence(false).setCompressionEnabled(false).setPort(account.port);
        if (account.host != null) config.setHost(account.host);
        connection = new ManagedConnection(config.build());
        connection.setReplyTimeout(15000);
        this.listener = listener;
        org.jivesoftware.smack.provider.ProviderManager.addExtensionProvider("encrypted",XmppOmemo2.NS,
                org.jivesoftware.smack.parsing.StandardExtensionElementProvider.INSTANCE);
        modern = modernDevice == null ? null : new XmppOmemo2(connection,modernDevice,jid -> {});
        ReplyReference.initialize();
        MessageCorrection.initialize();
        MessageReactions.initialize();
        MessageRetraction.initialize();
        FileShare.initialize();
        org.jivesoftware.smackx.disco.ServiceDiscoveryManager.getInstanceFor(connection).addFeature(MessageRetraction.NAMESPACE);
        org.jivesoftware.smackx.disco.ServiceDiscoveryManager.getInstanceFor(connection).addFeature(MessageRetraction.LEGACY);
        profiles = new XmppProfiles(connection, listener::profileChanged);
        org.jivesoftware.smackx.disco.ServiceDiscoveryManager.getInstanceFor(connection).addFeature(ReplyReference.NAMESPACE);
        org.jivesoftware.smackx.disco.ServiceDiscoveryManager.getInstanceFor(connection).addFeature(MessageCorrection.NAMESPACE);
        org.jivesoftware.smackx.disco.ServiceDiscoveryManager.getInstanceFor(connection).addFeature(MessageReactions.NAMESPACE);
        org.jivesoftware.smackx.disco.ServiceDiscoveryManager.getInstanceFor(connection).addFeature(FileShare.NAMESPACE);
        org.jivesoftware.smackx.disco.ServiceDiscoveryManager.getInstanceFor(connection).addFeature(MessageReceipt.MARKERS);
        org.jivesoftware.smackx.disco.ServiceDiscoveryManager.getInstanceFor(connection).addFeature(ReplyReference.FALLBACK);
        ReconnectionManager.getInstanceFor(connection).enableAutomaticReconnection();
        DeliveryReceiptManager receipts = DeliveryReceiptManager.getInstanceFor(connection);
        receipts.setAutoReceiptMode(DeliveryReceiptManager.AutoReceiptMode.ifIsSubscribed);
        trust = new IdentityTrust(trustFile);
        omemo = OmemoManager.getInstanceFor(connection, deviceId);
        omemo.setTrustCallback(trust);
        legacyDiscovery=new XmppLegacyDiscovery(connection,deviceId);
        fingerprints=new XmppFingerprints((XmppFingerprints.ProgressSource)(address,useModern,progress) -> {
            java.util.Map<OmemoDevice,OmemoFingerprint> found=new java.util.LinkedHashMap<>();
            if(useModern) for(java.util.Map.Entry<Integer,Omemo2Bundle> entry:modern().discover(address).entrySet())
                found.put(new OmemoDevice(JidCreate.entityBareFrom(address),entry.getKey()),new OmemoFingerprint(hex(entry.getValue().identity())));
            else {
                java.util.List<String> failures=new java.util.ArrayList<>();
                for(OmemoDevice device:legacyDiscovery.devices(JidCreate.bareFrom(address))) {
                    if(device.getJid().toString().equals(ownJid()) && device.getDeviceId()==deviceId) continue;
                    try { found.put(device,legacyDiscovery.fingerprint(device)); progress.accept(found); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw interrupted; } catch(Exception missingBundle) { failures.add("Device "+device.getDeviceId()+": "+XmppRequestException.describe(missingBundle)); }
                }
                if(!failures.isEmpty()) throw new XmppFingerprints.Partial(found,String.join("; ",failures));
            }
            return found;
        });
        rooms=new XmppRooms(connection,omemo,listener,contact -> sendingDevices(contact.toString()));
        invitations=new XmppInvitations(connection);
        chatStates = new XmppChatStates(connection, listener::typing, rooms, listener::roomTyping);
        rooms.chatStates = chatStates;
        contacts=new XmppContacts(connection,rooms,listener::contactRequest);
        bookmarks=new XmppBookmarks(connection,listener::bookmarksChanged);
        connection.addConnectionListener(new org.jivesoftware.smack.ConnectionListener() {
            @Override public void connectionClosed() { disconnected(null); }
            @Override public void connectionClosedOnError(Exception error) { disconnected(error); }
            private void disconnected(Exception error) {
                chatStates.reset();
                lifecycle.incrementAndGet();
                if (!closed) listener.disconnected(error);
            }
            @Override public void authenticated(org.jivesoftware.smack.XMPPConnection ignored, boolean resumed) {
                if (!started || closed) return;
                long generation = lifecycle.incrementAndGet();
                org.jivesoftware.smack.util.Async.go(() -> {
                    try {
                        if (!resumed) enableFeatures();
                        if (!closed && lifecycle.get() == generation && connection.isAuthenticated()) listener.reconnected(resumed);
                    } catch (Exception failed) {
                        if (!closed && lifecycle.get() == generation) connection.setupFailed(failed);
                    }
                }, "Monogram reconnect setup");
            }
        });
        org.jivesoftware.smackx.ping.PingManager ping = org.jivesoftware.smackx.ping.PingManager.getInstanceFor(connection);
        ping.setPingInterval(120);
        ping.registerPingFailedListener(() -> { if (!closed) connection.pingTimedOut(); });
        omemo.addOmemoMessageListener(new OmemoMessageListener() {
            @Override public void onOmemoMessageReceived(Stanza stanza, OmemoMessage.Received decrypted) {
                if (!decrypted.isKeyTransportMessage() && stanza instanceof Message && !isModern((Message)stanza) && !rooms.known(stanza.getFrom())) {
                    deliver(listener, (Message) stanza, decrypted.getBody(), true,decrypted.getSendersFingerprint().toString());
                }
            }
            @Override public void onOmemoCarbonCopyReceived(org.jivesoftware.smackx.carbons.packet.CarbonExtension.Direction direction,
                    Message carbon, Message wrapping, OmemoMessage.Received decrypted) {
                if (validCarbon(wrapping) && !isModern(carbon) && !decrypted.isKeyTransportMessage()) deliver(listener, carbon, decrypted.getBody(), true,decrypted.getSendersFingerprint().toString());
            }
        });
        CarbonManager.getInstanceFor(connection).addCarbonCopyReceivedListener((direction, carbon, wrapping) -> {
            if (validCarbon(wrapping) && !rooms.known(carbon.getFrom()) && !rooms.known(carbon.getTo())) {
                receipt(carbon);
                if (isModern(carbon)) deliverModern(carbon);
                else if (!encrypted(carbon)) deliver(listener, carbon, carbon.getBody(), false);
                else legacyPending(carbon);
            }
        });
        connection.addAsyncStanzaListener(stanza -> receipt((Message) stanza), stanza -> stanza instanceof Message
                && (((Message) stanza).hasExtension("received", "urn:xmpp:receipts") || ((Message) stanza).hasExtension("displayed", MessageReceipt.MARKERS)));
        connection.addAsyncStanzaListener(stanza -> {
            if(stanza.getFrom()!=null && stanza.getStanzaId()!=null)
                listener.reactionRejected(stanza.getFrom().toString(),stanza.getStanzaId());
        }, stanza -> stanza instanceof Message && ((Message)stanza).getType()==Message.Type.error);
        connection.addAsyncStanzaListener(stanza -> {
            Message message = (Message) stanza;
            if (rooms.known(message.getFrom())) return;
            if (isModern(message)) deliverModern(message);
            else if (!encrypted(message)) deliver(listener, message, message.getBody(), false);
            else legacyPending(message);
        }, stanza -> stanza instanceof Message && (((Message) stanza).getType() == Message.Type.chat
                || ((Message) stanza).getType() == Message.Type.normal));
    }

    private void legacyPending(Message stanza) {
        if(rooms.known(stanza.getFrom()) || rooms.known(stanza.getTo())) return;
        OmemoElement element=stanza.getExtension(OmemoElement.NAME_ENCRYPTED,OmemoElement_VAxolotl.NAMESPACE);
        if(element==null || element.isKeyTransportElement() || connection.getUser()==null || closed) return;
        XmppMessage message=XmppMessage.from(stanza,connection.getUser().asEntityBareJid(),null,true,false,null,null);
        if(message!=null) {
            if(message.reactions!=null) listener.message(message); else listener.messagePending(message);
        }
    }

    private void receipt(Message stanza) {
        if (closed || connection.getUser() == null || rooms.known(stanza.getFrom()) || rooms.known(stanza.getTo())) return;
        for (MessageReceipt receipt : MessageReceipt.from(stanza, connection.getUser().asEntityBareJid())) listener.receipt(receipt);
    }

    /** Disabled by default; serialize the switch with sending so queued work cannot disclose reads after disabling. */
    public synchronized void setDisplayedMarkersEnabled(boolean enabled) { displayedMarkersEnabled=enabled; }

    /** The caller supplies a persisted incoming markableId after the native chat has displayed that message. */
    public synchronized boolean sendDisplayed(String jid,String id) throws Exception {
        org.jxmpp.jid.Jid address;
        try { address=JidCreate.from(jid); }
        catch(Exception invalid) { throw new IllegalArgumentException("Invalid displayed-marker recipient",invalid); }
        if(!address.isEntityBareJid() || !MessageReceipt.valid(id)) throw new IllegalArgumentException("Invalid displayed-marker reference");
        EntityBareJid peer=address.asEntityBareJidOrThrow();
        if(rooms.known(peer) || connection.getUser()!=null && peer.equals(connection.getUser().asEntityBareJid()))
            throw new IllegalArgumentException("Displayed markers require a direct contact");
        if(closed || !displayedMarkersEnabled || !connection.isAuthenticated() || connection.getUser()==null) return false;
        org.jivesoftware.smack.roster.RosterEntry contact=org.jivesoftware.smack.roster.Roster.getInstanceFor(connection).getEntry(peer);
        if(contact==null || !contact.canSeeMyPresence()) return false;
        String key=peer+"\n"+id;
        if(displayedMarkersSent.contains(key)) return false;
        Message marker=new Message(peer,Message.Type.chat);
        marker.addExtension(new org.jivesoftware.smackx.chat_markers.element.ChatMarkersElements.DisplayedExtension(id));
        connection.sendStanza(marker);
        displayedMarkersSent.add(key);
        while(displayedMarkersSent.size()>1024) displayedMarkersSent.remove(displayedMarkersSent.iterator().next());
        return true;
    }

    private static boolean encrypted(Message message) {
        return isModern(message) || message.hasExtension(OmemoElement.NAME_ENCRYPTED, OmemoElement_VAxolotl.NAMESPACE);
    }
    private static boolean isModern(Message message) { return message.hasExtension("encrypted",XmppOmemo2.NS); }
    private static boolean modernPayload(Message message) {
        org.jivesoftware.smack.packet.ExtensionElement extension = message.getExtension("encrypted",XmppOmemo2.NS);
        return extension instanceof org.jivesoftware.smack.packet.StandardExtensionElement
                && ((org.jivesoftware.smack.packet.StandardExtensionElement)extension).getFirstElement("payload",XmppOmemo2.NS) != null;
    }
    private void deliverModern(Message stanza) {
        if(rooms.known(stanza.getFrom()) || rooms.known(stanza.getTo())) return;
        if (closed) return;
        if (connection.getUser()!=null && modernPayload(stanza)) {
            XmppMessage pending=XmppMessage.modern(stanza,null,connection.getUser().asEntityBareJid(),null,null);
            if (pending!=null) listener.messagePending(pending);
        }
        try {
        crypto.execute(() -> {
            if (closed || connection.getUser() == null) return;
            XmppOmemo2.Received received = null;
            try { if (modernAvailable()) received = modern.receive(stanza); }
            catch (Exception error) { modernFailure = error; }
            XmppMessage message = XmppMessage.modern(stanza,received == null ? null : received.content,
                    connection.getUser().asEntityBareJid(),null,null);
            if(message!=null && received!=null) message=message.withSenderFingerprint(received.senderFingerprint);
            final XmppOmemo2.Received decoded = received;
            java.util.concurrent.atomic.AtomicBoolean accepted = new java.util.concurrent.atomic.AtomicBoolean();
            if (message != null && modernPayload(stanza)) listener.message(message,() -> { if (accepted.compareAndSet(false,true)) afterModern(decoded); });
            else afterModern(decoded);
        });
        } catch (java.util.concurrent.RejectedExecutionException shuttingDown) { /* The session closed before dispatch. */ }
    }
    private void afterModern(XmppOmemo2.Received received) {
        if (received == null || closed) return;
        try {
            crypto.execute(() -> {
                if (closed || !connection.isAuthenticated()) return;
                try {
                    if (received.acknowledgement != null) connection.sendStanza(received.acknowledgement);
                    if (received.replenish) modern.replenish();
                } catch (Exception error) { modernFailure = error; }
            });
        } catch (java.util.concurrent.RejectedExecutionException shuttingDown) { /* The session closed after acceptance. */ }
    }
    public boolean modernAvailable() { return modern != null && modern.ready(); }
    public Exception modernFailure() { return modernFailure; }
    public XmppOmemo2 modern() {
        if (modern == null) throw new IllegalStateException("Modern OMEMO was not configured"); return modern;
    }
    private boolean validCarbon(Message wrapping) {
        return connection.getUser() != null && connection.getUser().asEntityBareJid().equals(wrapping.getFrom());
    }
    private void deliver(Listener listener, Message stanza, String body, boolean encrypted) { deliver(listener,stanza,body,encrypted,null); }
    private void deliver(Listener listener, Message stanza, String body, boolean encrypted,String fingerprint) {
        if(rooms.known(stanza.getFrom()) || rooms.known(stanza.getTo())) return;
        if (connection.getUser() == null) return;
        // Public legacy reactions were already delivered by the raw/carbon listener.
        if (encrypted && MessageReactions.publicLegacy(stanza)) return;
        XmppMessage message = XmppMessage.from(stanza, connection.getUser().asEntityBareJid(), body, encrypted, true, null, null);
        if (message != null) listener.message(message.withSenderFingerprint(fingerprint));
    }

    public static final class ArchivePage {
        public final List<XmppMessage> messages;
        public final String firstId, lastId;
        public final boolean complete;
        private final List<Runnable> accepted;
        private final java.util.concurrent.atomic.AtomicBoolean stored = new java.util.concurrent.atomic.AtomicBoolean();
        ArchivePage(List<XmppMessage> messages, String firstId, String lastId, boolean complete) {
            this(messages,firstId,lastId,complete,Collections.emptyList());
        }
        ArchivePage(List<XmppMessage> messages, String firstId, String lastId, boolean complete, List<Runnable> accepted) {
            this.messages = Collections.unmodifiableList(messages);
            this.firstId = firstId; this.lastId = lastId; this.complete = complete;
            this.accepted = Collections.unmodifiableList(new ArrayList<>(accepted));
        }
        public void stored() { if (stored.compareAndSet(false,true)) for (Runnable action : accepted) action.run(); }
    }
    /** Newest page when before is null; otherwise the page preceding that archive UID. */
    public ArchivePage archiveBefore(String peer, String before, int limit) throws Exception {
        return archive(peer, before == null ? "" : before, null, limit);
    }
    public ArchivePage archiveAfter(String after, int limit) throws Exception {
        return archiveAfter(null, after, limit);
    }
    public ArchivePage archiveAfter(String peer, String after, int limit) throws Exception {
        if (after == null || after.isEmpty()) throw new IllegalArgumentException("Missing archive cursor");
        return archive(peer, null, after, limit);
    }
    private ArchivePage archive(String peer, String before, String after, int limit) throws Exception {
        MamManager.MamQueryArgs.Builder args = MamManager.MamQueryArgs.builder()
                .setResultPageSizeTo(Math.min(Math.max(limit, 1), 100));
        if (peer != null) args.limitResultsToJid(JidCreate.entityBareFrom(peer));
        if (before != null) args.beforeUid(before);
        if (after != null) args.afterUid(after);
        MamManager.MamQuery query = MamManager.getInstanceFor(connection).queryArchive(args.build());
        List<XmppMessage> messages = new ArrayList<>();
        List<Runnable> accepted = new ArrayList<>();
        for (org.jivesoftware.smackx.mam.element.MamElements.MamResultExtension result : query.getMamResultExtensions()) {
            org.jivesoftware.smackx.forward.packet.Forwarded<Message> forwarded = result.getForwarded();
            Message stanza = forwarded.getForwardedStanza();
            if (rooms.known(stanza.getFrom()) || rooms.known(stanza.getTo())) continue;
            if (forwarded.getDelayInformation() == null) throw new java.io.IOException("Archive result has no timestamp");
            EntityBareJid own = connection.getUser().asEntityBareJid();
            Date time = forwarded.getDelayInformation().getStamp();
            if (isModern(stanza)) {
                if (!modernPayload(stanza) || XmppMessage.modern(stanza,null,own,result.getId(),time) == null) continue;
                XmppOmemo2.Received received = null;
                try { if (modernAvailable()) received = modern.receive(stanza,time); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw interrupted; }
                catch (Exception unavailable) { modernFailure = unavailable; }
                XmppMessage message = XmppMessage.modern(stanza,received == null ? null : received.content,own,result.getId(),time);
                if(message!=null && received!=null) message=message.withSenderFingerprint(received.senderFingerprint);
                if (message != null) messages.add(message);
                final XmppOmemo2.Received decoded = received; accepted.add(() -> afterModern(decoded));
                continue;
            }
            boolean encrypted = encrypted(stanza), readable = true;
            if (MessageReactions.publicLegacy(stanza)) {
                XmppMessage reaction=XmppMessage.from(stanza,own,null,true,false,result.getId(),time);
                if(reaction!=null) messages.add(reaction);
                continue;
            }
            String body = stanza.getBody(); String senderFingerprint=null;
            // Reject unrelated and room messages before attempting decryption.
            if (XmppMessage.from(stanza, own, body, encrypted, !encrypted, result.getId(), time) == null) continue;
            if (encrypted) {
                OmemoElement element = stanza.getExtension(OmemoElement.NAME_ENCRYPTED, OmemoElement_VAxolotl.NAMESPACE);
                if (element.isKeyTransportElement()) continue;
                try {
                    OmemoMessage.Received plaintext = omemo.decrypt(stanza.getFrom().asBareJid(), element);
                    if (plaintext.isKeyTransportMessage()) continue;
                    body = plaintext.getBody(); senderFingerprint=plaintext.getSendersFingerprint().toString();
                } catch (org.jivesoftware.smackx.omemo.exceptions.CorruptedOmemoKeyException
                        | org.jivesoftware.smackx.omemo.exceptions.NoRawSessionException
                        | org.jivesoftware.smackx.omemo.exceptions.CryptoFailedException e) {
                    // A new device (or an already-consumed ratchet key) cannot recover every archived ciphertext.
                    body = null; readable = false;
                }
            }
            XmppMessage message = XmppMessage.from(stanza, own, body, encrypted, readable, result.getId(), time);
            if (message != null) messages.add(message.withSenderFingerprint(senderFingerprint));
        }
        org.jivesoftware.smackx.rsm.packet.RSMSet rsm = query.getPage().getMamFinIq().getRSMSet();
        return new ArchivePage(messages, rsm == null ? null : rsm.getFirst(), rsm == null ? null : rsm.getLast(), query.isComplete(),accepted);
    }

    public void connect() throws Exception {
        try {
            connection.connect().login();
            enableFeatures(); started = true;
        } catch (Exception e) { close(); throw e; }
    }
    private void enableFeatures() throws Exception {
        if (org.jivesoftware.smackx.pep.PepManager.getInstanceFor(connection).isSupported()) {
            omemo.initialize();
            // Room members need public key bundles without requiring a roster subscription.
            for (String node:new String[]{"eu.siacs.conversations.axolotl.devicelist","eu.siacs.conversations.axolotl.bundles:"+omemo.getDeviceId()}) {
                try {
                    org.jivesoftware.smackx.pubsub.Node published=org.jivesoftware.smackx.pubsub.PubSubManager.getInstanceFor(connection,connection.getUser().asEntityBareJid()).getNode(node);
                    org.jivesoftware.smackx.pubsub.form.FillableConfigureForm form=published.getNodeConfiguration().getFillableForm();
                    form.setAccessModel(org.jivesoftware.smackx.pubsub.AccessModel.open); published.sendConfigurationForm(form);
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw interrupted; }
                catch (org.jivesoftware.smack.XMPPException.XMPPErrorException unsupported) {
                    // Providers without configurable PEP can still serve existing roster contacts.
                }
            }
        }
        if (modern != null) {
            try { modern.initialize(); modernFailure = null; }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw interrupted; }
            catch (Exception unsupported) { modernFailure = unsupported; }
        }
        CarbonManager carbons = CarbonManager.getInstanceFor(connection);
        if (carbons.isSupportedByServer()) carbons.enableCarbons();
        connection.sendStanza(new org.jivesoftware.smack.packet.Presence(org.jivesoftware.smack.packet.Presence.Type.available));
    }
    public String send(String jid, String body, boolean encrypted) throws Exception {
        return send(jid, body, encrypted, false, null);
    }
    public String send(String jid, String body, boolean encrypted, ReplyReference reply) throws Exception {
        return send(jid, body, encrypted, false, reply);
    }
    public String send(String jid, String body, XmppMessage.Encryption encryption, ReplyReference reply) throws Exception {
        return encryption == XmppMessage.Encryption.OMEMO2 ? sendModern(jid,body,reply,null,null)
                : send(jid,body,encryption != XmppMessage.Encryption.PLAIN,reply);
    }
    public String correct(String jid, String body, XmppMessage.Encryption encryption, ReplyReference reply, MessageCorrection correction) throws Exception {
        if (correction == null) throw new IllegalArgumentException("Missing correction reference");
        return encryption == XmppMessage.Encryption.OMEMO2 ? sendModern(jid,body,reply,correction,null)
                : correct(jid,body,encryption != XmppMessage.Encryption.PLAIN,reply,correction);
    }
    public String sendFile(String jid, FileLink link, boolean encrypted) throws Exception {
        return sendFile(jid, link, encrypted, null);
    }
    public String sendFile(String jid, FileLink link, boolean encrypted, ReplyReference reply) throws Exception {
        if (link.encrypted() != encrypted) throw new IllegalArgumentException("Attachment encryption mode mismatch");
        return send(jid, link.wireUrl, encrypted, !encrypted, reply);
    }
    private String send(String jid, String body, boolean encrypted, boolean attachment, ReplyReference reply) throws Exception {
        return send(jid, body, encrypted, attachment, reply, null);
    }
    public String correct(String jid, String body, boolean encrypted, ReplyReference reply, MessageCorrection correction) throws Exception {
        if (correction == null) throw new IllegalArgumentException("Missing correction reference");
        return send(jid, body, encrypted, false, reply, correction);
    }
    private String send(String jid, String body, boolean encrypted, boolean attachment, ReplyReference reply, MessageCorrection correction) throws Exception {
        return send(jid, body, encrypted, attachment, reply, correction, null);
    }
    public String sendFile(String jid, FileShare sharing, String caption, ReplyReference reply) throws Exception {
        if (sharing.link.encrypted()) return sendModern(jid,sharing.body(caption),reply,null,sharing);
        return send(jid, sharing.body(caption), false, false, reply, null, sharing);
    }
    public String correctFile(String jid, FileShare sharing, String caption, XmppMessage.Encryption encryption, ReplyReference reply, MessageCorrection correction) throws Exception {
        if (sharing == null || correction == null || encryption == XmppMessage.Encryption.LEGACY_OMEMO
                || sharing.link.encrypted() != (encryption == XmppMessage.Encryption.OMEMO2))
            throw new IllegalArgumentException("Caption encryption mode mismatch");
        return encryption == XmppMessage.Encryption.OMEMO2 ? sendModern(jid,sharing.body(caption),reply,correction,sharing)
                : send(jid,sharing.body(caption),false,false,reply,correction,sharing);
    }
    private String sendModern(String jid, String body, ReplyReference reply, MessageCorrection correction, FileShare sharing) throws Exception {
        return sendModern(jid,body,reply,correction,sharing,null);
    }
    private String sendModern(String jid,String body,ReplyReference reply,MessageCorrection correction,FileShare sharing,MessageRetraction retraction) throws Exception {
        if (!modernAvailable()) throw new OmemoSendException(OmemoSendException.Reason.MODERN_UNAVAILABLE);
        if (body == null || body.isEmpty()) throw new IllegalArgumentException("Empty message");
        Message clear = new Message(directRecipient(jid),Message.Type.chat);
        clear.setFrom(connection.getUser()); String id = UUID.randomUUID().toString(); clear.setStanzaId(id);
        if (reply != null) { body = reply.wireBody(body); reply.addTo(clear); }
        clear.setBody(body); if (correction != null) correction.addTo(clear); if (sharing != null) sharing.addTo(clear,body);
        if(retraction!=null) retraction.addMetadataTo(clear);
        Message wire = modern.encrypt(clear);
        if(retraction!=null) wire.addExtension(StandardExtensionElement.builder("store","urn:xmpp:hints").build());
        wire.addExtension(new OriginIdElement(id)); DeliveryReceiptManager.addDeliveryReceiptRequest(wire); chatStates.decorate(wire);
        if (retraction == null) wire.addExtension(StandardExtensionElement.builder("markable", MessageReceipt.MARKERS).build());
        connection.sendStanza(wire); chatStates.sent(wire); return id;
    }
    private String send(String jid, String body, boolean encrypted, boolean attachment, ReplyReference reply, MessageCorrection correction, FileShare sharing) throws Exception {
        return send(jid,body,encrypted,attachment,reply,correction,sharing,null);
    }
    private String send(String jid,String body,boolean encrypted,boolean attachment,ReplyReference reply,MessageCorrection correction,FileShare sharing,MessageRetraction retraction) throws Exception {
        if (body == null || body.isEmpty()) throw new IllegalArgumentException("Empty message");
        EntityBareJid recipient = directRecipient(jid);
        String attachmentUrl = body;
        if (reply != null) body = reply.wireBody(body);
        Message message;
        if (encrypted) {
            try {
                if (sendingDevices(recipient.toString()).isEmpty()) throw new OmemoSendException(OmemoSendException.Reason.NO_DEVICES);
            } catch (org.jivesoftware.smack.XMPPException.XMPPErrorException e) {
                if (e.getStanzaError().getCondition() == org.jivesoftware.smack.packet.StanzaError.Condition.item_not_found) {
                    throw new OmemoSendException(OmemoSendException.Reason.NO_DEVICES);
                }
                throw e;
            }
            OmemoMessage.Sent ciphertext = omemo.encrypt(recipient, body);
            for (OmemoDevice skipped : ciphertext.getSkippedDevices().keySet()) {
                if (trust.getTrust(skipped, omemo.getFingerprint(skipped)) != org.jivesoftware.smackx.omemo.trust.TrustState.untrusted) {
                    throw new OmemoSendException(OmemoSendException.Reason.DEVICE_FAILURE);
                }
            }
            boolean recipientIncluded = false;
            for (OmemoDevice intended : ciphertext.getIntendedDevices()) {
                if (intended.getJid().equals(recipient) && !ciphertext.getSkippedDevices().containsKey(intended)) recipientIncluded = true;
            }
            if (!recipientIncluded) throw new OmemoSendException(OmemoSendException.Reason.NO_TRUSTED_DEVICES);
            message = ciphertext.buildMessage(connection.getStanzaFactory().buildMessageStanza().ofType(Message.Type.chat), recipient);
        } else {
            message = new Message(recipient, Message.Type.chat);
            message.setBody(body);
        }
        if (attachment) message.addExtension(org.jivesoftware.smack.packet.StandardExtensionElement.builder("x", "jabber:x:oob")
                .addElement("url", attachmentUrl).build());
        if (reply != null) reply.addTo(message);
        if (correction != null) correction.addTo(message);
        if (sharing != null) sharing.addTo(message, body);
        if(retraction!=null) {
            retraction.addMetadataTo(message);
            message.addExtension(StandardExtensionElement.builder("store","urn:xmpp:hints").build());
        }
        String id = UUID.randomUUID().toString();
        message.setStanzaId(id);
        message.addExtension(new OriginIdElement(id));
        DeliveryReceiptManager.addDeliveryReceiptRequest(message);
        if (retraction == null) message.addExtension(StandardExtensionElement.builder("markable", MessageReceipt.MARKERS).build());
        chatStates.decorate(message);
        connection.sendStanza(message);
        chatStates.sent(message);
        return id;
    }
    /** Select the peer's advertised retraction format independently of message encryption. */
    public boolean legacyRetractions(String jid) throws Exception {
        org.jivesoftware.smack.packet.Presence presence=org.jivesoftware.smack.roster.Roster.getInstanceFor(connection).getPresence(directRecipient(jid));
        if(presence.isAvailable() && presence.getFrom()!=null && presence.getFrom().hasResource()) try {
            org.jivesoftware.smackx.disco.packet.DiscoverInfo info=org.jivesoftware.smackx.disco.ServiceDiscoveryManager.getInstanceFor(connection).discoverInfo(presence.getFrom());
            return !info.containsFeature(MessageRetraction.NAMESPACE) && info.containsFeature(MessageRetraction.LEGACY);
        } catch(org.jivesoftware.smack.XMPPException.XMPPErrorException | org.jivesoftware.smack.SmackException.NoResponseException unsupported) { }
        return false;
    }
    public void sendRetraction(String jid,MessageRetraction request,XmppMessage.Encryption encryption) throws Exception {
        if(encryption==XmppMessage.Encryption.OMEMO2) sendModern(jid,MessageRetraction.FALLBACK_BODY,null,null,null,request);
        else send(jid,MessageRetraction.FALLBACK_BODY,encryption!=XmppMessage.Encryption.PLAIN,false,null,null,null,request);
    }
    public String sendReactions(String jid,MessageReactions reactions,XmppMessage.Encryption encryption) throws Exception {
        if(encryption==XmppMessage.Encryption.LEGACY_OMEMO)
            throw new UnsupportedOperationException("Legacy OMEMO cannot encrypt reaction metadata");
        Message clear=new Message(directRecipient(jid),Message.Type.chat);
        clear.setFrom(connection.getUser()); String id=UUID.randomUUID().toString(); clear.setStanzaId(id);
        reactions.addTo(clear);
        Message wire=clear;
        if(encryption==XmppMessage.Encryption.OMEMO2) {
            if(!modernAvailable()) throw new OmemoSendException(OmemoSendException.Reason.MODERN_UNAVAILABLE);
            wire=modern.encrypt(clear);
        }
        wire.addExtension(new OriginIdElement(id));
        wire.addExtension(org.jivesoftware.smack.packet.StandardExtensionElement.builder("store","urn:xmpp:hints").build());
        connection.sendStanza(wire); return id;
    }
    private EntityBareJid directRecipient(String address) throws Exception {
        org.jxmpp.jid.Jid jid=JidCreate.from(address);
        if (jid.hasResource() || rooms.known(jid)) throw new UnsupportedOperationException("Use room messaging for room occupants");
        return JidCreate.entityBareFrom(address);
    }
    public Set<OmemoDevice> devices(String jid) throws Exception {
        EntityBareJid contact = JidCreate.entityBareFrom(jid);
        try {
            omemo.requestDeviceListUpdateFor(contact);
            deviceListRefresh.put(contact.toString(),System.nanoTime());
            return omemo.getDevicesOf(contact);
        } catch (org.jivesoftware.smack.XMPPException.XMPPErrorException e) {
            if (e.getStanzaError().getCondition() == org.jivesoftware.smack.packet.StanzaError.Condition.item_not_found) return java.util.Collections.emptySet();
            if(e.getStanzaError().getCondition()==org.jivesoftware.smack.packet.StanzaError.Condition.subscription_required
                    || e.getStanzaError().getExtension("presence-subscription-required","http://jabber.org/protocol/pubsub#errors")!=null) {
                OmemoSendException required=new OmemoSendException(OmemoSendException.Reason.CONTACT_AUTHORIZATION_REQUIRED,contact.toString()); required.initCause(e); throw required;
            }
            throw e;
        }
    }
    private final java.util.concurrent.ExecutorService deviceRefreshWorker=java.util.concurrent.Executors.newSingleThreadExecutor(r -> { Thread thread=new Thread(r,"Monogram device lists"); thread.setDaemon(true); return thread; });
    private final Set<String> pendingDeviceRefresh=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private Set<OmemoDevice> sendingDevices(String address) throws Exception {
        Set<OmemoDevice> cached=omemo.getDevicesOf(JidCreate.entityBareFrom(address));
        if(cached.isEmpty()) return devices(address);
        Long refreshed=deviceListRefresh.get(address);
        if((refreshed==null || System.nanoTime()-refreshed>java.util.concurrent.TimeUnit.MINUTES.toNanos(1)) && pendingDeviceRefresh.add(address)) {
            try { deviceRefreshWorker.execute(() -> {
                try { devices(address); }
                catch(Exception unavailable) { deviceListRefresh.put(address,System.nanoTime()); }
                finally { pendingDeviceRefresh.remove(address); }
            }); } catch(java.util.concurrent.RejectedExecutionException stopped) { pendingDeviceRefresh.remove(address); }
        }
        return cached;
    }
    public OmemoFingerprint fingerprint(OmemoDevice device) throws Exception { return omemo.getFingerprint(device); }
    static String hex(byte[] bytes) { StringBuilder result=new StringBuilder(); for(byte value:bytes) result.append(String.format(java.util.Locale.ROOT,"%02x",value&255)); return result.toString(); }
    public String ownJid() { return omemo.getOwnJid().toString(); }
    public int ownDeviceId() { return omemo.getDeviceId(); }
    public org.jivesoftware.smackx.omemo.trust.TrustState trustState(OmemoDevice device, OmemoFingerprint fingerprint) { return trust.getTrust(device, fingerprint); }
    public String senderFingerprint(XmppMessage.Encryption encryption) throws Exception {
        return encryption==XmppMessage.Encryption.PLAIN ? null : encryption==XmppMessage.Encryption.OMEMO2 ? modern().ownFingerprint() : ownFingerprint().toString();
    }
    public OmemoFingerprint ownFingerprint() throws Exception { return legacyDiscovery.ownFingerprint(); }
    public void decide(OmemoDevice device, OmemoFingerprint fingerprint, boolean trusted) { trust.setTrust(device, fingerprint, trusted); }
    public void decideAll(java.util.Map<OmemoDevice, OmemoFingerprint> identities, boolean trusted) { trust.setTrustAll(identities, trusted); }
    public boolean hasArchive() throws Exception { return MamManager.getInstanceFor(connection).isSupported(); }
    public XMPPTCPConnection connection() { return connection; }
    @Override public void close() {
        closed = true; started = false; lifecycle.incrementAndGet();
        if (modern != null) modern.close(); crypto.shutdownNow(); deviceRefreshWorker.shutdownNow();
        chatStates.close(); contacts.close(); rooms.close(); bookmarks.close(); fingerprints.close();
        ReconnectionManager manager = ReconnectionManager.getInstanceFor(connection);
        manager.disableAutomaticReconnection(); manager.abortPossiblyRunningReconnection();
        connection.disconnect();
    }
}
