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
import org.jivesoftware.smackx.omemo.exceptions.ReadOnlyDeviceException;
import org.jivesoftware.smackx.omemo.signal.SignalFileBasedOmemoStore;
import org.jivesoftware.smackx.omemo.MonogramLegacyOmemoService;
import org.jivesoftware.smackx.omemo.trust.OmemoFingerprint;
import org.jivesoftware.smackx.receipts.DeliveryReceiptManager;
import org.jivesoftware.smackx.sid.element.OriginIdElement;
import org.jxmpp.jid.EntityBareJid;
import org.jxmpp.jid.impl.JidCreate;

/** Blocking API: callers must use a worker thread. No helper is needed for these operations. */
public final class XmppSession implements AutoCloseable {
    public interface Listener {
        void message(EntityBareJid sender, String id, String body, boolean encrypted);
        default void debugMessageXml(boolean outgoing,Message stanza) {}
        default void sentCarbon(EntityBareJid recipient, String id, String body, boolean encrypted) {}
        default void receipt(MessageReceipt receipt) {}
        default void roomDisplayed(RoomDisplayedMarker marker) {}
        default void ownDisplayed(EntityBareJid peer,String id) {}
        default void profileChanged(EntityBareJid jid) {}
        default void profileChanged(org.jxmpp.jid.Jid jid) { profileChanged(jid.asEntityBareJidIfPossible()); }
        default void roomMessage(XmppRoomMessage message) {}
        default void roomMessage(XmppRoomMessage message,Runnable accepted) { roomMessage(message);accepted.run(); }
        default void roomMessagePending(XmppRoomMessage message) { roomMessage(message); }
        default void messagePending(XmppMessage message) { message(message); }
        default void roomChanged(XmppRooms.State room) {}
        default void roomInvitation(String room,String inviter,String reason,String password) {}
        default void roomInviteFailed(String room,String target,String condition) {}
        default void roomMetadataChanged(String room) {}
        default void bookmarksChanged() {}
        default void storiesChanged(String owner) {}
        default void readSyncChanged() {}
        default void blockedChanged(List<String> addresses) {}
        default void reactionRejected(String from,String id) {}
        default void contactRequest(String address,boolean pending) {}
        default void typing(EntityBareJid jid, boolean composing) {}
        default void roomTyping(org.jxmpp.jid.EntityFullJid occupant, boolean composing) {}
        default void disconnected() {}
        default void disconnected(Exception error) { disconnected(); }
        default void reconnected(boolean resumed) {}
        /** Call accepted only once the message has been stored; protocol acknowledgements follow it. */
        default void message(XmppMessage message, Runnable accepted) { message(message); accepted.run(); }
        default void message(XmppMessage message) {
            if (!message.readable || message.reactions != null) return;
            String id = message.ids.get("stanza");
            if (message.outgoing) sentCarbon(message.peer, id, message.body, message.encrypted);
            else message(message.peer, id, message.body, message.encrypted);
        }
    }
    private static final class IncomingCapacity {
        private static final java.util.Map<String,java.util.Set<XmppSession>> listeners=new java.util.HashMap<>();
        static synchronized void register(String key,XmppSession session) {
            if(key!=null) listeners.computeIfAbsent(key,ignored -> java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>())).add(session);
        }
        static synchronized void unregister(String key,XmppSession session) {
            java.util.Set<XmppSession> found=listeners.get(key);if(found==null) return;
            found.remove(session);if(found.isEmpty()) listeners.remove(key);
        }
        static void changed(String key,XmppSession origin) {
            if(key==null) return;List<XmppSession> copy;
            synchronized(IncomingCapacity.class) {
                java.util.Set<XmppSession> found=listeners.get(key);if(found==null) return;copy=new ArrayList<>(found);
            }
            for(XmppSession session:copy) session.capacityChanged(key,origin!=session);
        }
    }
    private static File keyRoot;
    private static LegacyOmemoRecords.Factory legacyRecordsFactory;
    private static final class ManagedConnection extends XMPPTCPConnection {
        ManagedConnection(XMPPTCPConnectionConfiguration config) { super(config); }
        @Override protected void parseAndProcessStanza(org.jivesoftware.smack.xml.XmlPullParser parser)
                throws org.jivesoftware.smack.xml.XmlPullParserException, java.io.IOException, InterruptedException {
            try { super.parseAndProcessStanza(parser); }
            catch (AssertionError failedRecovery) {
                // Smack's recovery parser can assert after the original XML parser failed mid-token.
                // The stream is no longer usable: let its existing IOException path disconnect and
                // reconnect, rather than continuing at an unknown boundary or crashing Android.
                if (!stanzaRecoveryAssertion(failedRecovery)) throw failedRecovery;
                throw new java.io.IOException("Incoming XMPP stanza recovery failed", failedRecovery);
            }
        }
        void pingTimedOut() { notifyConnectionError(new java.net.SocketTimeoutException("XMPP server ping timed out")); }
        void setupFailed(Exception error) { notifyConnectionError(error); }
    }
    static boolean stanzaRecoveryAssertion(AssertionError error) {
        boolean element=false, recovery=false;
        for(StackTraceElement frame:error.getStackTrace()) {
            if("org.jivesoftware.smack.util.XmlStringBuilder".equals(frame.getClassName())
                    && "halfOpenElement".equals(frame.getMethodName())) element=true;
            if("org.jivesoftware.smack.util.PacketParserUtils".equals(frame.getClassName())
                    && "parseContentDepthWithoutRoundtrip".equals(frame.getMethodName())) recovery=true;
        }
        return element && recovery;
    }
    private final ManagedConnection connection;
    public final com.negzero.monogram.xmpp.calls.XmppCalls calls;
    public final XmppProfiles profiles;
    public final XmppRooms rooms;
    public final XmppBookmarks bookmarks;
    public final XmppReadSync readSync;
    public final XmppContacts contacts;
    public final XmppBlocking blocking;
    public final XmppInvitations invitations;
    public final XmppChatStates chatStates;
    public final XmppFingerprints fingerprints;
    private final OmemoManager omemo;
    private final XmppLegacyDiscovery legacyDiscovery;
    private final java.util.Map<String,Long> deviceListRefresh=new java.util.concurrent.ConcurrentHashMap<>();
    private final IdentityTrust trust;
    private final XmppOmemo2 modern;
    private final LegacyIncomingJournal legacyIncoming;
    private final String legacyCapacityKey,modernCapacityKey;
    private boolean foreignAckScheduled;
    private long foreignAckRevision;
    private volatile Exception legacyFailure;
    private final Object legacyInboxLock=new Object();
    private final java.util.ArrayDeque<LegacyIncomingJournal.Context> legacyInbox=new java.util.ArrayDeque<>();
    private long legacyInboxBytes,legacyAccepted;
    private boolean legacyBlocked,legacyRetrying,foreignLegacyScheduled;
    private long foreignLegacyRevision;
    private final java.util.concurrent.ExecutorService legacyWorker=java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread thread=new Thread(r,"Monogram legacy recovery");thread.setDaemon(true);return thread;
    });
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
    private static final int MAX_MODERN_SETUP_MESSAGES=128;
    private static final long MAX_MODERN_SETUP_BYTES=4L*1024*1024;
    private final Object modernInboxLock=new Object();
    private static final class IncomingModern {
        final Message stanza;
        final DeliveryAcknowledgement receipt;
        IncomingModern(Message stanza,DeliveryAcknowledgement receipt) { this.stanza=stanza;this.receipt=receipt; }
    }
    private final java.util.ArrayDeque<IncomingModern> modernInbox=new java.util.ArrayDeque<>();
    private long modernInboxBytes;
    private boolean journalBlocked;
    private long incomingAccepted;
    private boolean modernSetup=true;

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
        throw new IllegalStateException("Legacy OMEMO requires an explicit durable LegacyOmemoRecords.Factory");
    }
    public static synchronized void initializeOmemo(File privateDirectory,LegacyOmemoRecords.Factory factory) throws Exception {
        if(factory==null) throw new IllegalArgumentException("A durable legacy OMEMO records factory is required");
        File root = privateDirectory.getCanonicalFile();
        if (keyRoot != null) {
            if (!root.equals(keyRoot) || legacyRecordsFactory!=factory) throw new IllegalStateException("OMEMO store already initialized with another root or records factory");
            return;
        }
        if (!root.isDirectory() && !root.mkdirs()) throw new java.io.IOException("Cannot create OMEMO key store");
        OmemoCryptoProvider.install();
        org.jivesoftware.smackx.omemo.signal.SignalOmemoService.acknowledgeLicense();
        MonogramLegacyOmemoService.setup(new org.jivesoftware.smackx.omemo.MonogramDeviceLists(new SignalFileBasedOmemoStore(root),factory));
        keyRoot = root;legacyRecordsFactory=factory;
    }

    public XmppSession(Account account, String password, int deviceId, File trustFile, Listener listener) throws Exception {
        this(account,password,deviceId,trustFile,null,listener);
    }
    public XmppSession(Account account, String password, int deviceId, File trustFile, Omemo2Device modernDevice, Listener listener) throws Exception {
        this(account,password,deviceId,trustFile,modernDevice,null,listener);
    }
    public XmppSession(Account account,String password,int deviceId,File trustFile,Omemo2Device modernDevice,XmppRecovery recovery,Listener listener) throws Exception {
        if (keyRoot == null) throw new IllegalStateException("Initialize the private OMEMO store first");
        if (deviceId < 1) throw new IllegalArgumentException("OMEMO device ID must be positive and persistent");
        XMPPTCPConnectionConfiguration.Builder config = XMPPTCPConnectionConfiguration.builder()
                .setXmppDomain(account.jid.getDomain().toString()).setUsernameAndPassword(account.jid.getLocalpart(), password)
                .setResource(account.resource).setSecurityMode(SecurityMode.required)
                .setSendPresence(false).setCompressionEnabled(false).setPort(account.port);
        if (account.host != null) config.setHost(account.host);
        if(account.proxy!=null)config.setProxyInfo(account.proxy);
        connection = new ManagedConnection(config.build());
        XmppStories.initialize(connection);
        connection.setReplyTimeout(15000);
        this.listener = listener;
        connection.addAsyncStanzaListener(stanza -> {
            org.jivesoftware.smackx.pubsub.EventElement event=org.jivesoftware.smackx.pubsub.EventElement.from(stanza);
            if(event!=null && (XmppStories.NODE.equals(event.getEvent().getNode()) || XmppStories.PROFILE_NODE.equals(event.getEvent().getNode())) && stanza.getFrom()!=null
                    && stanza.getFrom().asEntityBareJidIfPossible()!=null)
                listener.storiesChanged(stanza.getFrom().asEntityBareJidIfPossible().toString());
        },stanza -> stanza.hasExtension("event","http://jabber.org/protocol/pubsub#event"));
        connection.addAsyncStanzaListener(stanza -> debugMessageXml(false,(Message)stanza),stanza -> stanza instanceof Message);
        connection.addStanzaSendingListener(stanza -> debugMessageXml(true,(Message)stanza),stanza -> stanza instanceof Message);
        legacyCapacityKey="legacy\n"+account.jid+"\n"+deviceId;
        modernCapacityKey=modernDevice==null ? null : "modern\n"+modernDevice.jid+"\n"+modernDevice.id;
        org.jivesoftware.smack.provider.ProviderManager.addExtensionProvider("encrypted",XmppOmemo2.NS,
                org.jivesoftware.smack.parsing.StandardExtensionElementProvider.INSTANCE);
        modern = modernDevice == null ? null : recovery==null ? new XmppOmemo2(connection,modernDevice,jid -> {}) : XmppOmemo2.restored(connection,modernDevice,jid -> {},recovery.modern(modernDevice));
        ReplyReference.initialize();
        MessageCorrection.initialize();
        MessageReactions.initialize();
        MessageRetraction.initialize();
        FileShare.initialize();
        MessageMarkup.initialize();
        org.jivesoftware.smackx.disco.ServiceDiscoveryManager.getInstanceFor(connection).addFeature("urn:xmpp:forward:0");
        org.jivesoftware.smackx.disco.ServiceDiscoveryManager.getInstanceFor(connection).addFeature(MessageMarkup.NAMESPACE);
        org.jivesoftware.smackx.disco.ServiceDiscoveryManager.getInstanceFor(connection).addFeature(MessageMarkup.SPOILER);
        org.jivesoftware.smackx.disco.ServiceDiscoveryManager.getInstanceFor(connection).addFeature(MessageMarkup.SPOILER_SPAN);
        org.jivesoftware.smackx.disco.ServiceDiscoveryManager.getInstanceFor(connection).addFeature(MessageMentions.NAMESPACE);
        org.jivesoftware.smackx.disco.ServiceDiscoveryManager.getInstanceFor(connection).addFeature(MessageMarkup.STYLING);
        org.jivesoftware.smackx.disco.ServiceDiscoveryManager.getInstanceFor(connection).addFeature(MessageMarkup.CONTENT);
        org.jivesoftware.smackx.disco.ServiceDiscoveryManager.getInstanceFor(connection).addFeature(MessageRetraction.NAMESPACE);
        org.jivesoftware.smackx.disco.ServiceDiscoveryManager.getInstanceFor(connection).addFeature(MessageRetraction.LEGACY);
        calls = new com.negzero.monogram.xmpp.calls.XmppCalls(connection);
        profiles = new XmppProfiles(connection, listener::profileChanged);
        org.jivesoftware.smackx.disco.ServiceDiscoveryManager.getInstanceFor(connection).addFeature(ReplyReference.NAMESPACE);
        org.jivesoftware.smackx.disco.ServiceDiscoveryManager.getInstanceFor(connection).addFeature(MessageCorrection.NAMESPACE);
        org.jivesoftware.smackx.disco.ServiceDiscoveryManager.getInstanceFor(connection).addFeature(MessageReactions.NAMESPACE);
        org.jivesoftware.smackx.disco.ServiceDiscoveryManager.getInstanceFor(connection).addFeature(FileShare.NAMESPACE);
        org.jivesoftware.smackx.disco.ServiceDiscoveryManager.getInstanceFor(connection).addFeature(MessageReceipt.MARKERS);
        org.jivesoftware.smackx.disco.ServiceDiscoveryManager.getInstanceFor(connection).addFeature(ReplyReference.FALLBACK);
        ReconnectionManager.getInstanceFor(connection).enableAutomaticReconnection();
        DeliveryReceiptManager receipts = DeliveryReceiptManager.getInstanceFor(connection);
        receipts.setAutoReceiptMode(DeliveryReceiptManager.AutoReceiptMode.disabled);
        // Smack's request provider discards malformed attributes/children before we can validate them.
        org.jivesoftware.smack.provider.ProviderManager.addExtensionProvider("request","urn:xmpp:receipts",
                org.jivesoftware.smack.parsing.StandardExtensionElementProvider.INSTANCE);
        trust = new IdentityTrust(trustFile);
        omemo = OmemoManager.getInstanceFor(connection, deviceId);
        omemo.setTrustCallback(trust);
        legacyIncoming=new LegacyIncomingJournal(MonogramLegacyOmemoService.instance().records(),new OmemoDevice(account.jid,deviceId));
        legacyDiscovery=new XmppLegacyDiscovery(connection,deviceId);
        fingerprints=new XmppFingerprints((XmppFingerprints.ProgressSource)(address,useModern,progress) -> {
            java.util.Map<OmemoDevice,OmemoFingerprint> found=new java.util.LinkedHashMap<>();
            if(useModern) {
                org.jxmpp.jid.EntityBareJid fingerprintJid=JidCreate.entityBareFrom(address);
                modern().discoverFingerprints(address,bundles->{
                    found.clear();
                    for(java.util.Map.Entry<Integer,Omemo2Bundle> entry:bundles.entrySet())found.put(new OmemoDevice(fingerprintJid,entry.getKey()),new OmemoFingerprint(hex(entry.getValue().identity())));
                    progress.accept(new java.util.LinkedHashMap<>(found));
                });
            }
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
        rooms.legacySession=this;
        invitations=new XmppInvitations(connection);
        chatStates = new XmppChatStates(connection, listener::typing, rooms, listener::roomTyping);
        rooms.chatStates = chatStates;
        contacts=new XmppContacts(connection,rooms,listener::contactRequest);
        blocking=new XmppBlocking(connection,listener::blockedChanged);
        bookmarks=new XmppBookmarks(connection,listener::bookmarksChanged);
        readSync=new XmppReadSync(connection,listener::readSyncChanged);
        connection.addConnectionListener(new org.jivesoftware.smack.ConnectionListener() {
            @Override public void connectionClosed() { disconnected(null); }
            @Override public void connectionClosedOnError(Exception error) { disconnected(error); }
            private void disconnected(Exception error) {
                chatStates.reset();
                beginModernSetup();
                if (!closed) listener.disconnected(error);
            }
            @Override public void authenticated(org.jivesoftware.smack.XMPPConnection ignored, boolean resumed) {
                if (!started || closed) return;
                long generation = beginModernSetup();
                org.jivesoftware.smack.util.Async.go(() -> {
                    try {
                        if (!resumed) enableFeatures();
                        finishModernSetup(generation);
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
        MonogramLegacyOmemoService.instance().register(omemo,legacyIncoming,new MonogramLegacyOmemoService.Receiver() {
            public LegacyIncomingJournal.Context context(Message stanza,boolean live) throws Exception {
                if(closed || connection.getUser()==null || isModern(stanza) || MessageReactions.publicLegacy(stanza)) return null;
                if(stanza.getType()==Message.Type.groupchat) return live ? rooms.legacyContext(stanza,true,null,null) : null;
                if(rooms.known(stanza.getFrom()) || rooms.known(stanza.getTo())) return null;
                return LegacyIncomingJournal.Context.direct(stanza,connection.getUser().asEntityBareJid(),live,null,null);
            }
            public void received(LegacyIncomingJournal.Received received) { deliverLegacy(received); }
            public long capacityRevision() { synchronized(legacyInboxLock) { return legacyAccepted; } }
            public void capacityChanged() { IncomingCapacity.changed(legacyCapacityKey,XmppSession.this); }
            public void blocked(LegacyIncomingJournal.Context context,long revision) { queueLegacy(context,revision); }
            public void failed(Exception error) { legacyFailure=error; }
        },trust);
        if(recovery!=null) MonogramLegacyOmemoService.instance().configureRecoveries(omemo,recovery.legacy(
                MonogramLegacyOmemoService.instance().records(),legacyIncoming.own,trust));
        CarbonManager.getInstanceFor(connection).addCarbonCopyReceivedListener((direction, carbon, wrapping) -> {
            if (validCarbon(wrapping) && !rooms.known(carbon.getFrom()) && !rooms.known(carbon.getTo())) {
                receipt(carbon);
                if(direction==org.jivesoftware.smackx.carbons.packet.CarbonExtension.Direction.received
                        && connection.getUser().equals(wrapping.getTo())) chatStates.receivedCarbon(carbon);
                if(direction==org.jivesoftware.smackx.carbons.packet.CarbonExtension.Direction.sent) {
                    MessageReceipt read=MessageReceipt.ownDisplayed(carbon,connection.getUser().asEntityBareJid());
                    if(read!=null) listener.ownDisplayed(read.peer,read.id);
                }
                if (isModern(carbon)) deliverModern(carbon,false);
                else if (!encrypted(carbon)) deliver(listener, carbon, carbon.getBody(), false,null,false);
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
            if (isModern(message)) deliverModern(message,true);
            else if (!encrypted(message)) deliver(listener, message, message.getBody(), false,null,true);
            else legacyPending(message);
        }, stanza -> stanza instanceof Message && (((Message) stanza).getType() == Message.Type.chat
                || ((Message) stanza).getType() == Message.Type.normal));
        IncomingCapacity.register(legacyCapacityKey,this);IncomingCapacity.register(modernCapacityKey,this);
    }

    private void legacyPending(Message stanza) {
        if(rooms.known(stanza.getFrom()) || rooms.known(stanza.getTo())) return;
        OmemoElement element=stanza.getExtension(OmemoElement.NAME_ENCRYPTED,OmemoElement_VAxolotl.NAMESPACE);
        if(element==null || element.isKeyTransportElement() || connection.getUser()==null || closed) return;
        XmppMessage message=XmppMessage.from(stanza,connection.getUser().asEntityBareJid(),null,true,false,null,null);
        if(message!=null) {
            try { message=message.withAliases(legacyIncoming.aliases(LegacyIncomingJournal.Context.direct(stanza,connection.getUser().asEntityBareJid(),false,null,null))); }
            catch(Exception invalidRoute) { /* Keep the original provisional identity when no trustworthy digest can be made. */ }
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
        boolean room=rooms.known(peer);
        if(connection.getUser()!=null && peer.equals(connection.getUser().asEntityBareJid()))
            throw new IllegalArgumentException("Displayed markers require another conversation");
        if(closed || !displayedMarkersEnabled || !connection.isAuthenticated() || connection.getUser()==null) return false;
        if(!room) {
            org.jivesoftware.smack.roster.RosterEntry contact=org.jivesoftware.smack.roster.Roster.getInstanceFor(connection).getEntry(peer);
            if(contact==null || !contact.canSeeMyPresence()) return false;
        }
        String key=peer+"\n"+id;
        if(displayedMarkersSent.contains(key)) return false;
        if(room) { if(!rooms.sendDisplayed(peer.toString(),id)) return false; }
        else {
            Message marker=new Message(peer,Message.Type.chat);
            marker.addExtension(new org.jivesoftware.smackx.chat_markers.element.ChatMarkersElements.DisplayedExtension(id));
            connection.sendStanza(marker);
        }
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
    private void deliverModern(Message stanza,boolean live) {
        if(rooms.known(stanza.getFrom()) || rooms.known(stanza.getTo())) return;
        if (closed) return;
        if (connection.getUser()!=null && modernPayload(stanza)) {
            XmppMessage pending=XmppMessage.modern(stanza,null,connection.getUser().asEntityBareJid(),null,null);
            if (pending!=null) listener.messagePending(pending);
        }
        queueModern(new IncomingModern(stanza,live ? DeliveryAcknowledgement.from(stanza,connection.getUser()==null ? null : connection.getUser().asEntityBareJid()) : null));
    }
    private long beginModernSetup() {
        synchronized(modernInboxLock) { modernSetup=true;modernInboxLock.notifyAll();return lifecycle.incrementAndGet(); }
    }
    private void finishModernSetup(long generation) {
        synchronized(modernInboxLock) {
            if(closed || lifecycle.get()!=generation || !connection.isAuthenticated()) return;
            modernSetup=false;modernInboxLock.notifyAll();
            try { crypto.execute(this::recoverIncoming); }
            catch(java.util.concurrent.RejectedExecutionException stopped) { /* Session closed during setup completion. */ }
            retryLegacyInbox();
            if(journalBlocked) retryModernInbox();
            else {
                while(!modernInbox.isEmpty()) decryptModern(modernInbox.removeFirst(),false);
                modernInboxBytes=0;
            }
        }
    }
    private void queueModern(IncomingModern incoming) {
        Message stanza=incoming.stanza;
        java.io.IOException overflow=null;
        synchronized(modernInboxLock) {
            if(closed) return;
            if(!modernSetup && !journalBlocked) { decryptModern(incoming,false);return; }
            long bytes=2L*stanza.toXML().length();
            if(modernInbox.size()<MAX_MODERN_SETUP_MESSAGES && bytes<=MAX_MODERN_SETUP_BYTES-modernInboxBytes) {
                modernInbox.addLast(incoming);modernInboxBytes+=bytes;return;
            }
            overflow=new java.io.IOException("OMEMO2 incoming inbox exceeded its bounded capacity; reconnect and recover history");
            modernFailure=overflow;modernSetup=false;
            // Preserve every provisional ID as unreadable; do not consume ratchets or acknowledge overflowed data.
            while(!modernInbox.isEmpty()) decryptModern(modernInbox.removeFirst(),true);
            modernInboxBytes=0;decryptModern(incoming,true);
        }
        connection.setupFailed(overflow);
    }
    private void decryptModern(IncomingModern incoming,boolean setupOverflow) {
        Message stanza=incoming.stanza;
        try {
        crypto.execute(() -> {
            if (closed || connection.getUser() == null) return;
            XmppOmemo2.Received received = null;
            Exception failed=null;
            long revision;
            synchronized(modernInboxLock) { revision=incomingAccepted; }
            try { if (!setupOverflow && modernAvailable()) received = modern.receive(stanza); }
            catch (Omemo2IncomingJournal.FullException full) {
                boolean recover;
                synchronized(modernInboxLock) {
                    recover=!journalBlocked;journalBlocked=true;
                }
                queueModern(incoming);
                if(recover) recoverIncoming();
                synchronized(modernInboxLock) { if(revision!=incomingAccepted) retryModernInbox(); }
                return;
            }
            catch (Exception error) { failed=error; }
            if(received==null && !setupOverflow) {
                boolean settingUp;
                synchronized(modernInboxLock) { settingUp=modernSetup; }
                if(settingUp) { queueModern(incoming);return; }
            }
            if(failed!=null) modernFailure=failed;
            XmppMessage message = received==null ? XmppMessage.modern(stanza,null,
                    connection.getUser().asEntityBareJid(),null,null) : received.message;
            if (message != null && modernPayload(stanza)) deliverDecodedModern(message,received,incoming.receipt);
            else afterModern(received);
        });
        } catch (java.util.concurrent.RejectedExecutionException shuttingDown) { /* The session closed before dispatch. */ }
    }
    private void deliverDecodedModern(XmppMessage message,XmppOmemo2.Received received,DeliveryAcknowledgement receipt) {
        java.util.concurrent.atomic.AtomicBoolean accepted=new java.util.concurrent.atomic.AtomicBoolean();
        listener.message(message,() -> { if(accepted.compareAndSet(false,true)) afterModern(received,receipt!=null && receipt.accepts(message) ? receipt : null); });
    }
    /** Explicit cold-migration opt-in, before connecting. Never activates or publishes an account. */
    public void configureLegacyRecovery(org.jivesoftware.smackx.omemo.MonogramLegacyReceiveOnly recovery) {
        if(closed) throw new IllegalStateException("Session is closed");
        try { MonogramLegacyOmemoService.instance().configureRecovery(omemo,recovery); }
        catch(Exception failure) { throw new IllegalStateException("Could not configure legacy recovery",failure); }
    }
    /** Local recovery also runs before connecting: authenticated plaintext must not depend on network availability. */
    public void recoverIncoming() {
        if(closed) return;
        try { for(LegacyIncomingJournal.Received pending:MonogramLegacyOmemoService.instance().pending(omemo,legacyIncoming)) { if(closed) return;deliverLegacy(pending); } }
        catch(Exception failure) { legacyFailure=failure; }
        if(modern==null) return;
        try {
            for(XmppOmemo2.Received pending:modern.pendingIncoming()) {
                if(closed) return;
                if(pending.message!=null) deliverDecodedModern(pending.message,pending,null);else afterModern(pending);
            }
        } catch(Exception failure) { modernFailure=failure; }
    }
    /** Retry only a room whose native metadata has just become available. No connection or join is needed. */
    public void recoverRoomIncoming(String room) {
        if(closed || room==null) return;
        try { for(LegacyIncomingJournal.Received pending:MonogramLegacyOmemoService.instance().pending(omemo,legacyIncoming))
            if(!closed && pending.roomMessage!=null && room.equals(pending.roomMessage.room.toString())) deliverLegacy(pending);
        } catch(Exception failure) { legacyFailure=failure; }
    }
    private void afterModern(XmppOmemo2.Received received) { afterModern(received,null); }
    private void afterModern(XmppOmemo2.Received received,DeliveryAcknowledgement receipt) {
        if (received == null || modern==null) return;
        try { if(!modern.acceptIncoming(received)) return; }
        catch(Exception failure) { modernFailure=failure;return; }
        if(received.message!=null) incomingCapacityChanged();
        if(receipt!=null) acknowledgeDelivery(receipt);
        if(closed) return;
        try {
            crypto.execute(() -> completeModern(received));
        } catch (java.util.concurrent.RejectedExecutionException shuttingDown) { /* The session closed after acceptance. */ }
    }
    private void completeModern(XmppOmemo2.Received received) {
        if(closed || !connection.isAuthenticated()) return;
        try {
            if(received.acknowledgement!=null) connection.sendStanza(received.acknowledgement);
            modern.acknowledgeIncoming(received);incomingCapacityChanged();
            if(received.replenish) modern.replenish();
        } catch(Exception error) { modernFailure=error; }
    }
    private void incomingCapacityChanged() { IncomingCapacity.changed(modernCapacityKey,this); }
    private void capacityChanged(String key,boolean foreign) {
        if(closed) return;
        if(key.equals(legacyCapacityKey)) {
            synchronized(legacyInboxLock) {
                legacyAccepted++;legacyInboxLock.notifyAll();retryLegacyInbox();
                if(foreign) { foreignLegacyRevision++;scheduleForeignLegacyCompletion(); }
            }
        } else {
            synchronized(modernInboxLock) {
                incomingAccepted++;modernInboxLock.notifyAll();retryModernInbox();
                if(foreign) { foreignAckRevision++;scheduleForeignAcknowledgements(); }
            }
        }
    }
    /** A closed predecessor can leave newly accepted ACK-only rows in the replacement's full journal. */
    private void scheduleForeignAcknowledgements() {
        if(closed || modern==null || foreignAckScheduled) return;foreignAckScheduled=true;
        try { crypto.execute(() -> {
            long revision;
            do {
                synchronized(modernInboxLock) { revision=foreignAckRevision; }
                try {
                    for(XmppOmemo2.Received pending:modern.pendingIncoming())
                        if(pending.message==null && modern.acceptIncoming(pending)) completeModern(pending);
                } catch(Exception failure) { modernFailure=failure; }
                synchronized(modernInboxLock) {
                    if(closed || revision==foreignAckRevision) { foreignAckScheduled=false;return; }
                }
            } while(true);
        }); } catch(java.util.concurrent.RejectedExecutionException stopped) { foreignAckScheduled=false; }
    }
    private void scheduleForeignLegacyCompletion() {
        if(closed || foreignLegacyScheduled) return;foreignLegacyScheduled=true;
        try { legacyWorker.execute(() -> {
            long revision;
            do {
                synchronized(legacyInboxLock) { revision=foreignLegacyRevision; }
                try {
                    for(LegacyIncomingJournal.Received pending:MonogramLegacyOmemoService.instance().pending(omemo,legacyIncoming))
                        if(pending.message==null && pending.roomMessage==null)
                            MonogramLegacyOmemoService.instance().completePending(omemo,legacyIncoming,pending);
                } catch(Exception failure) { legacyFailure=failure; }
                synchronized(legacyInboxLock) {
                    if(closed || revision==foreignLegacyRevision) { foreignLegacyScheduled=false;return; }
                }
            } while(true);
        }); } catch(java.util.concurrent.RejectedExecutionException stopped) { foreignLegacyScheduled=false; }
    }
    private void retryModernInbox() {
        synchronized(modernInboxLock) {
            if(closed || modernSetup || !journalBlocked) return;
            if(!modernInbox.isEmpty()) {
                IncomingModern incoming=modernInbox.removeFirst();modernInboxBytes-=2L*incoming.stanza.toXML().length();
                decryptModern(incoming,false);
            }
            if(modernInbox.isEmpty()) journalBlocked=false;
        }
    }
    public boolean modernAvailable() { return modern != null && modern.ready(); }
    public Exception modernFailure() { return modernFailure; }
    public XmppOmemo2 modern() {
        if (modern == null) throw new IllegalStateException("Modern OMEMO was not configured"); return modern;
    }
    private boolean validCarbon(Message wrapping) {
        return connection.getUser() != null && connection.getUser().asEntityBareJid().equals(wrapping.getFrom());
    }
    private void debugMessageXml(boolean outgoing,Message stanza) {
        try { listener.debugMessageXml(outgoing,stanza); }
        catch(RuntimeException diagnosticFailure) { /* Optional debugging must not interrupt messaging. */ }
    }
    private void deliver(Listener listener, Message stanza, String body, boolean encrypted,String fingerprint,boolean live) {
        if(rooms.known(stanza.getFrom()) || rooms.known(stanza.getTo())) return;
        if (connection.getUser() == null) return;
        // Public legacy reactions were already delivered by the raw/carbon listener.
        if (encrypted && MessageReactions.publicLegacy(stanza)) return;
        XmppMessage message = XmppMessage.from(stanza, connection.getUser().asEntityBareJid(), body, encrypted, true, null, null);
        if (message == null) return;
        message=message.withSenderFingerprint(fingerprint);
        DeliveryAcknowledgement receipt=live ? DeliveryAcknowledgement.from(stanza,connection.getUser().asEntityBareJid()) : null;
        if(receipt==null || !receipt.accepts(message)) { listener.message(message);return; }
        java.util.concurrent.atomic.AtomicBoolean accepted=new java.util.concurrent.atomic.AtomicBoolean();
        listener.message(message,() -> { if(accepted.compareAndSet(false,true)) acknowledgeDelivery(receipt); });
    }

    private void acknowledgeDelivery(DeliveryAcknowledgement receipt) {
        long generation=lifecycle.get();
        try {
            crypto.execute(() -> {
                if(closed || lifecycle.get()!=generation || !connection.isAuthenticated() || connection.getUser()==null
                        || !receipt.own.equals(connection.getUser().asEntityBareJid())
                        || rooms.known(receipt.sender) || rooms.known(receipt.peer)
                        || !org.jivesoftware.smack.roster.Roster.getInstanceFor(connection).isSubscribedToMyPresence(receipt.sender)) return;
                try { connection.sendStanza(receipt.response()); }
                catch(org.jivesoftware.smack.SmackException.NotConnectedException disconnected) { /* Retry requires a fresh live delivery. */ }
                catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
        } catch(java.util.concurrent.RejectedExecutionException stopped) { /* Session closed after storage acceptance. */ }
    }

    private void deliverLegacy(LegacyIncomingJournal.Received received) {
        if(closed) return;
        java.util.concurrent.atomic.AtomicBoolean accepted=new java.util.concurrent.atomic.AtomicBoolean();
        Runnable stored=() -> { if(accepted.compareAndSet(false,true)) acceptLegacy(received); };
        if(received.roomMessage!=null) listener.roomMessage(received.roomMessage,stored);
        else if(received.message!=null) listener.message(received.message,stored);
        else acceptLegacy(received);
    }
    void acceptLegacy(LegacyIncomingJournal.Received received) {
        try {
            LegacyIncomingJournal source=MonogramLegacyOmemoService.instance().source(omemo,legacyIncoming,received);
            if(!source.accept(received)) return;
            if(received.message!=null || received.roomMessage!=null) IncomingCapacity.changed(legacyCapacityKey,this);
            if(!closed) try { legacyWorker.execute(() -> {
                try { MonogramLegacyOmemoService.instance().completePending(omemo,legacyIncoming,received); }
                catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                catch(Exception failure) { legacyFailure=failure; }
            }); } catch(java.util.concurrent.RejectedExecutionException stopped) { /* Session closed after local acceptance. */ }
            if(received.receiptEligible()) acknowledgeDelivery(received.context.receipt);
        } catch(Exception failure) { legacyFailure=failure; }
    }
    java.util.Map<String,String> legacyAliases(LegacyIncomingJournal.Context context) throws Exception { return legacyIncoming.aliases(context); }
    public Exception legacyFailure() { return legacyFailure; }
    private void queueLegacy(LegacyIncomingJournal.Context context,long revision) {
        boolean recover=false;java.io.IOException overflow=null;
        synchronized(legacyInboxLock) {
            if(closed) return;long bytes=context.retainedBytes();
            if(legacyInbox.size()>=MAX_MODERN_SETUP_MESSAGES || bytes>MAX_MODERN_SETUP_BYTES-legacyInboxBytes) {
                overflow=new java.io.IOException("Legacy incoming inbox is full; reconnect and recover history");legacyFailure=overflow;
            } else {
                recover=!legacyBlocked;legacyBlocked=true;legacyInbox.addLast(context);legacyInboxBytes+=bytes;
                if(revision!=legacyAccepted) retryLegacyInbox();
            }
        }
        if(overflow!=null) connection.setupFailed(overflow);
        else if(recover) recoverIncoming();
    }
    private void retryLegacyInbox() {
        synchronized(legacyInboxLock) {
            if(closed || legacyRetrying || legacyInbox.isEmpty() || !connection.isAuthenticated()) return;
            LegacyIncomingJournal.Context context=legacyInbox.removeFirst();legacyInboxBytes-=context.retainedBytes();legacyRetrying=true;
            long revision=legacyAccepted;
            try { legacyWorker.execute(() -> {
                boolean completed=false;
                try { completed=MonogramLegacyOmemoService.instance().retry(omemo,context); }
                finally {
                    synchronized(legacyInboxLock) {
                        legacyRetrying=false;if(legacyInbox.isEmpty()) legacyBlocked=false;
                        if(completed || revision!=legacyAccepted) retryLegacyInbox();
                    }
                }
            }); } catch(java.util.concurrent.RejectedExecutionException stopped) { legacyRetrying=false; }
        }
    }
    LegacyIncomingJournal.Received receiveLegacyArchive(LegacyIncomingJournal.Context context) throws Exception {
        awaitModernSetup();
        long generation=lifecycle.get(),deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
        while(true) {
            if(interruptedModernSetup(generation)) throw new java.io.IOException("Legacy archive interrupted by connection setup");
            long revision;synchronized(legacyInboxLock) { revision=legacyAccepted; }
            try { return MonogramLegacyOmemoService.instance().receive(omemo,legacyIncoming,context); }
            catch(LegacyIncomingJournal.FullException full) {
                recoverIncoming();
                synchronized(legacyInboxLock) {
                    long remaining=deadline-System.nanoTime();if(remaining<=0) throw full;
                    if(revision==legacyAccepted) java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(legacyInboxLock,Math.min(remaining,java.util.concurrent.TimeUnit.SECONDS.toNanos(1)));
                }
            }
        }
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
    /** First page on/after a calendar date; does not change any client sync cursor. */
    public ArchivePage archiveSince(String peer,Date since,int limit) throws Exception {
        if(since==null || since.getTime()<0) throw new IllegalArgumentException("Invalid archive date");
        return archive(peer,null,null,limit,new Date(since.getTime()));
    }
    private void awaitModernSetup() throws Exception {
        // This setup generation also covers legacy initialization when no modern device is configured.
        long timeout=Math.max(1000,Math.min(60000,connection.getReplyTimeout()*2));
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeout);
        synchronized(modernInboxLock) {
            while(modernSetup) {
                if(closed || !connection.isAuthenticated()) throw new java.io.IOException("Archive interrupted by connection setup");
                long remaining=deadline-System.nanoTime();
                if(remaining<=0) throw new java.io.IOException("Archive is waiting for encryption setup");
                java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(modernInboxLock,remaining);
            }
        }
    }
    private boolean interruptedModernSetup(long generation) {
        synchronized(modernInboxLock) { return closed || modernSetup || lifecycle.get()!=generation || !connection.isAuthenticated(); }
    }
    private XmppOmemo2.Received receiveArchivedModern(Message stanza,Date time,String archiveId,long generation) throws Exception {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                Math.max(1000,Math.min(60000,connection.getReplyTimeout()*2)));
        while(true) {
            long revision;
            synchronized(modernInboxLock) { revision=incomingAccepted; }
            try { return modernAvailable() ? modern.receive(stanza,time,archiveId) : null; }
            catch(Omemo2IncomingJournal.FullException full) {
                // Archive decoding runs outside crypto: storage and ACK work must be able to free capacity.
                recoverIncoming();
                synchronized(modernInboxLock) {
                    if(interruptedModernSetup(generation)) throw new java.io.IOException("Archive interrupted by encryption setup",full);
                    long remaining=deadline-System.nanoTime();
                    if(remaining<=0) throw full;
                    if(revision==incomingAccepted) java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(modernInboxLock,
                            Math.min(remaining,java.util.concurrent.TimeUnit.SECONDS.toNanos(1)));
                }
            }
        }
    }
    private ArchivePage archive(String peer, String before, String after, int limit) throws Exception {
        return archive(peer,before,after,limit,null);
    }
    private ArchivePage archive(String peer,String before,String after,int limit,Date since) throws Exception {
        awaitModernSetup();
        EntityBareJid own=connection.getUser().asEntityBareJid();
        MamManager.MamQueryArgs.Builder args = MamManager.MamQueryArgs.builder()
                .setResultPageSizeTo(Math.min(Math.max(limit, 1), 100));
        if (peer != null) args.limitResultsToJid(JidCreate.entityBareFrom(peer));
        if(since!=null) args.limitResultsSince(since);
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
            Date time = forwarded.getDelayInformation().getStamp();
            if (isModern(stanza)) {
                if (!modernPayload(stanza) || XmppMessage.modern(stanza,null,own,result.getId(),time) == null) continue;
                awaitModernSetup();long generation=lifecycle.get();
                XmppOmemo2.Received received = null;
                try { received = receiveArchivedModern(stanza,time,result.getId(),generation); }
                catch (Omemo2IncomingJournal.FullException full) { modernFailure=full;throw full; }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw interrupted; }
                catch (Exception unavailable) {
                    modernFailure = unavailable;
                    if(interruptedModernSetup(generation)) throw new java.io.IOException("Archive interrupted by encryption setup",unavailable);
                }
                if(received==null && interruptedModernSetup(generation)) throw new java.io.IOException("Archive interrupted by encryption setup");
                XmppMessage message = received==null ? XmppMessage.modern(stanza,null,own,result.getId(),time) : received.message;
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
                    LegacyIncomingJournal.Received decoded=receiveLegacyArchive(LegacyIncomingJournal.Context.direct(stanza,own,false,result.getId(),time));
                    if(decoded.message!=null) messages.add(decoded.message);
                    else {
                        XmppMessage placeholder=XmppMessage.from(stanza,own,null,true,false,result.getId(),time);
                        if(placeholder!=null) messages.add(placeholder.withAliases(legacyIncoming.aliases(LegacyIncomingJournal.Context.direct(stanza,own,false,result.getId(),time))));
                    }
                    accepted.add(() -> acceptLegacy(decoded));continue;
                } catch (org.jivesoftware.smackx.omemo.exceptions.CorruptedOmemoKeyException
                        | org.jivesoftware.smackx.omemo.exceptions.NoRawSessionException
                        | org.jivesoftware.smackx.omemo.exceptions.CryptoFailedException e) {
                    // A new device (or an already-consumed ratchet key) cannot recover every archived ciphertext.
                    body = null; readable = false;
                }
            }
            XmppMessage message = XmppMessage.from(stanza, own, body, encrypted, readable, result.getId(), time);
            if (message != null) {
                if(encrypted) try { message=message.withAliases(legacyIncoming.aliases(LegacyIncomingJournal.Context.direct(stanza,own,false,result.getId(),time))); }
                catch(Exception invalidRoute) { /* Preserve the explicit placeholder without an unverified private alias. */ }
                messages.add(message.withSenderFingerprint(senderFingerprint));
            }
        }
        org.jivesoftware.smackx.rsm.packet.RSMSet rsm = query.getPage().getMamFinIq().getRSMSet();
        return new ArchivePage(messages, rsm == null ? null : rsm.getFirst(), rsm == null ? null : rsm.getLast(), query.isComplete(),accepted);
    }

    public void connect() throws Exception {
        try {
            connection.connect().login();
            long generation=lifecycle.get();
            enableFeatures();finishModernSetup(generation);started = true;
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
    public String send(String jid,String body,XmppMessage.Encryption encryption,ReplyReference reply,MessageMarkup markup) throws Exception {
        return encryption==XmppMessage.Encryption.OMEMO2 ? sendModern(jid,body,reply,null,null,null,markup)
                : send(jid,body,encryption!=XmppMessage.Encryption.PLAIN,false,reply,null,null,null,markup);
    }
    public String correct(String jid,String body,XmppMessage.Encryption encryption,ReplyReference reply,MessageCorrection correction,MessageMarkup markup) throws Exception {
        if(correction==null) throw new IllegalArgumentException("Missing correction reference");
        return encryption==XmppMessage.Encryption.OMEMO2 ? sendModern(jid,body,reply,correction,null,null,markup)
                : send(jid,body,encryption!=XmppMessage.Encryption.PLAIN,false,reply,correction,null,null,markup);
    }
    public String send(String jid,String body,XmppMessage.Encryption encryption,ReplyReference reply,MessageMarkup markup,InlineEmoji inline) throws Exception {
        return encryption==XmppMessage.Encryption.OMEMO2 ? sendModern(jid,body,reply,null,null,null,markup,inline)
                : send(jid,body,encryption!=XmppMessage.Encryption.PLAIN,false,reply,null,null,null,markup,inline);
    }
    public String correct(String jid,String body,XmppMessage.Encryption encryption,ReplyReference reply,MessageCorrection correction,MessageMarkup markup,InlineEmoji inline) throws Exception {
        if(correction==null) throw new IllegalArgumentException("Missing correction reference");
        return encryption==XmppMessage.Encryption.OMEMO2 ? sendModern(jid,body,reply,correction,null,null,markup,inline)
                : send(jid,body,encryption!=XmppMessage.Encryption.PLAIN,false,reply,correction,null,null,markup,inline);
    }
    public String sendFile(String jid, FileLink link, boolean encrypted) throws Exception {
        return sendFile(jid, link, encrypted, null);
    }
    public String sendFile(String jid, FileLink link, boolean encrypted, ReplyReference reply) throws Exception {
        if (link.encrypted() != encrypted) throw new IllegalArgumentException("Attachment encryption mode mismatch");
        return send(jid, link.wireUrl, encrypted, !encrypted, reply);
    }
    public String sendFile(String jid, FileLink link, boolean encrypted, ReplyReference reply, boolean displayStickerHint) throws Exception {
        return sendFile(jid,link,encrypted,reply,displayStickerHint,false);
    }
    public String sendFile(String jid,FileLink link,boolean encrypted,ReplyReference reply,boolean displayStickerHint,boolean spoiler) throws Exception {
        if (link.encrypted() != encrypted || displayStickerHint && !encrypted) throw new IllegalArgumentException("Attachment encryption mode mismatch");
        // XEP-0454 receivers require a bare aesgcm URI; attribution belongs in a separate caption.
        String body=link.wireUrl;
        return send(jid,body,encrypted,!encrypted,reply,null,null,null,MessageMarkup.body(body).withSpoiler(spoiler),null,displayStickerHint);
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
    public String sendFile(String jid,FileShare sharing,String caption,ReplyReference reply,MessageMarkup markup) throws Exception {
        if(markup!=null && !markup.body.equals(caption==null ? "" : caption)) throw new IllegalArgumentException("Markup belongs to another caption");
        String body=sharing.body(caption);MessageMarkup full=markup==null ? null : markup.inBody(body,0);
        return sharing.link.encrypted() ? sendModern(jid,body,reply,null,sharing,null,full)
                : send(jid,body,false,false,reply,null,sharing,null,full);
    }
    public String correctFile(String jid,FileShare sharing,String caption,XmppMessage.Encryption encryption,ReplyReference reply,MessageCorrection correction,MessageMarkup markup) throws Exception {
        if(markup!=null && !markup.body.equals(caption==null ? "" : caption)) throw new IllegalArgumentException("Markup belongs to another caption");
        if(sharing==null || correction==null || encryption==XmppMessage.Encryption.LEGACY_OMEMO || sharing.link.encrypted()!=(encryption==XmppMessage.Encryption.OMEMO2))
            throw new IllegalArgumentException("Caption encryption mode mismatch");
        String body=sharing.body(caption);MessageMarkup full=markup==null ? null : markup.inBody(body,0);
        return encryption==XmppMessage.Encryption.OMEMO2 ? sendModern(jid,body,reply,correction,sharing,null,full)
                : send(jid,body,false,false,reply,correction,sharing,null,full);
    }
    private String sendModern(String jid, String body, ReplyReference reply, MessageCorrection correction, FileShare sharing) throws Exception {
        return sendModern(jid,body,reply,correction,sharing,null);
    }
    private String sendModern(String jid,String body,ReplyReference reply,MessageCorrection correction,FileShare sharing,MessageRetraction retraction) throws Exception {
        return sendModern(jid,body,reply,correction,sharing,retraction,null);
    }
    private String sendModern(String jid,String body,ReplyReference reply,MessageCorrection correction,FileShare sharing,MessageRetraction retraction,MessageMarkup markup) throws Exception {
        return sendModern(jid,body,reply,correction,sharing,retraction,markup,null);
    }
    private String sendModern(String jid,String body,ReplyReference reply,MessageCorrection correction,FileShare sharing,MessageRetraction retraction,MessageMarkup markup,InlineEmoji inline) throws Exception {
        if(inline!=null && !body.equals(inline.body)) throw new IllegalArgumentException("Emoji belong to another body");
        if(markup!=null && !markup.body.equals(body)) throw new IllegalArgumentException("Markup belongs to another body");
        int prefix=reply==null ? 0 : reply.wireBody("").codePointCount(0,reply.wireBody("").length());
        if (!modernAvailable()) throw new OmemoSendException(OmemoSendException.Reason.MODERN_UNAVAILABLE);
        if (body == null || body.isEmpty()) throw new IllegalArgumentException("Empty message");
        Message clear = new Message(directRecipient(jid),Message.Type.chat);
        clear.setFrom(connection.getUser()); String id = UUID.randomUUID().toString(); clear.setStanzaId(id);
        if (reply != null) { body = reply.wireBody(body); reply.addTo(clear); }
        clear.setBody(body); if (correction != null) correction.addTo(clear); if (sharing != null) sharing.addTo(clear,body);
        if(markup!=null) markup.inBody(body,prefix).addTo(clear);
        if(inline!=null) inline.inBody(body,prefix).addTo(clear);
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
        return send(jid,body,encrypted,attachment,reply,correction,sharing,retraction,null);
    }
    private String send(String jid,String body,boolean encrypted,boolean attachment,ReplyReference reply,MessageCorrection correction,FileShare sharing,MessageRetraction retraction,MessageMarkup markup) throws Exception {
        return send(jid,body,encrypted,attachment,reply,correction,sharing,retraction,markup,null);
    }
    private String send(String jid,String body,boolean encrypted,boolean attachment,ReplyReference reply,MessageCorrection correction,FileShare sharing,MessageRetraction retraction,MessageMarkup markup,InlineEmoji inline) throws Exception {
        return send(jid,body,encrypted,attachment,reply,correction,sharing,retraction,markup,inline,false);
    }
    private String send(String jid,String body,boolean encrypted,boolean attachment,ReplyReference reply,MessageCorrection correction,FileShare sharing,MessageRetraction retraction,MessageMarkup markup,InlineEmoji inline,boolean displayStickerHint) throws Exception {
        if(inline!=null && !body.equals(inline.body)) throw new IllegalArgumentException("Emoji belong to another body");
        if(markup!=null && !markup.body.equals(body)) throw new IllegalArgumentException("Markup belongs to another body");
        int prefix=reply==null ? 0 : reply.wireBody("").codePointCount(0,reply.wireBody("").length());
        if (body == null || body.isEmpty()) throw new IllegalArgumentException("Empty message");
        EntityBareJid recipient = directRecipient(jid);
        String attachmentUrl = ForwardedContent.payload(body);
        if (reply != null) body = reply.wireBody(body);
        Message message;
        if (encrypted) {
            OmemoMessage.Sent ciphertext = encryptLegacy(recipient,body);
            message = ciphertext.buildMessage(connection.getStanzaFactory().buildMessageStanza().ofType(Message.Type.chat), recipient);
        } else {
            message = new Message(recipient, Message.Type.chat);
            message.setBody(body);
        }
        if (displayStickerHint && encrypted) StickerHint.addTo(message);
        if (attachment) message.addExtension(org.jivesoftware.smack.packet.StandardExtensionElement.builder("x", "jabber:x:oob")
                .addElement("url", attachmentUrl).build());
        if (reply != null) reply.addTo(message);
        if (correction != null) correction.addTo(message);
        if (sharing != null) sharing.addTo(message, body);
        if(!encrypted && markup!=null) markup.inBody(body,prefix).addTo(message);
        if(encrypted && markup!=null) { markup.addContentHint(message);markup.inBody(body,prefix).addLegacySpoilers(message); }
        if(!encrypted && inline!=null) inline.inBody(body,prefix).addTo(message);
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
    private OmemoMessage.Sent encryptLegacy(EntityBareJid recipient,String body) throws Exception {
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
            // Smack intentionally excludes devices silent for 400 sends, including our own old clients.
            if (ciphertext.getSkippedDevices().get(skipped) instanceof ReadOnlyDeviceException) continue;
            if (trust.getTrust(skipped, omemo.getFingerprint(skipped)) != org.jivesoftware.smackx.omemo.trust.TrustState.untrusted) {
                throw new OmemoSendException(OmemoSendException.Reason.DEVICE_FAILURE,
                    "Device "+skipped.getDeviceId()+": "+ciphertext.getSkippedDevices().get(skipped).getClass().getSimpleName());
            }
        }
        boolean recipientIncluded = false;
        for (OmemoDevice intended : ciphertext.getIntendedDevices()) {
            if (intended.getJid().equals(recipient) && !ciphertext.getSkippedDevices().containsKey(intended)) recipientIncluded = true;
        }
        if (!recipientIncluded) throw new OmemoSendException(OmemoSendException.Reason.NO_TRUSTED_DEVICES);
        return ciphertext;
    }
    /** Exercise encryption locally, without transmitting a probe message or its ciphertext. */
    public boolean probeEncryption(String jid,XmppMessage.Encryption mode,boolean room,java.util.Set<String> excluded,boolean automatic) throws Exception {
        if(!connection.isAuthenticated()) throw new org.jivesoftware.smack.SmackException.NotConnectedException();
        if(room) {
            if(mode==XmppMessage.Encryption.OMEMO2) return false;
            rooms.probeEncryption(jid,excluded,automatic);return true;
        }
        if(mode==XmppMessage.Encryption.OMEMO2) {
            awaitModernSetup();
            if(!modernAvailable()) return false;
            Message clear=new Message(directRecipient(jid),Message.Type.chat);clear.setFrom(connection.getUser());
            clear.setStanzaId(java.util.UUID.randomUUID().toString());clear.setBody("Monogram encryption check");
            modern.encrypt(clear);return true;
        }
        encryptLegacy(directRecipient(jid),"Monogram encryption check");return true;
    }
    private EntityBareJid directRecipient(String address) throws Exception {
        org.jxmpp.jid.Jid jid=JidCreate.from(address);
        if (jid.hasResource() || rooms.known(jid)) throw new UnsupportedOperationException("Use room messaging for room occupants");
        return JidCreate.entityBareFrom(address);
    }
    public Set<OmemoDevice> devices(String jid) throws Exception {
        EntityBareJid contact = JidCreate.entityBareFrom(jid);
        try {
            if(connection.getUser()!=null && !contact.equals(connection.getUser().asBareJid())) return refreshRemoteDevices(contact);
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
    private Set<OmemoDevice> refreshRemoteDevices(EntityBareJid contact) throws Exception {
        long generation=lifecycle.get();
        if(closed || !connection.isAuthenticated()) throw new OmemoSendException(OmemoSendException.Reason.ROOM_CHANGED);
        org.jivesoftware.smackx.omemo.MonogramDeviceLists.Snapshot snapshot=org.jivesoftware.smackx.omemo.MonogramDeviceLists.snapshot(omemo,contact);
        org.jivesoftware.smackx.omemo.element.OmemoDeviceListElement fetched;
        try { fetched=legacyDiscovery.fetchDeviceList(contact); }
        catch(Exception failure) {
            if(closed || generation!=lifecycle.get() || !connection.isAuthenticated()) throw new OmemoSendException(OmemoSendException.Reason.ROOM_CHANGED);
            throw failure;
        }
        synchronized(omemo) {
            if(closed || generation!=lifecycle.get() || !connection.isAuthenticated()) throw new OmemoSendException(OmemoSendException.Reason.ROOM_CHANGED);
            Set<OmemoDevice> devices;
            try { devices=org.jivesoftware.smackx.omemo.MonogramDeviceLists.apply(omemo,snapshot,fetched); }
            catch(java.util.ConcurrentModificationException changed) {
                OmemoSendException retry=new OmemoSendException(OmemoSendException.Reason.ROOM_CHANGED,"Device list changed during lookup; retry the message.");retry.initCause(changed);throw retry;
            }
            if(closed || generation!=lifecycle.get()) throw new OmemoSendException(OmemoSendException.Reason.ROOM_CHANGED);
            deviceListRefresh.put(contact.toString(),System.nanoTime());return devices;
        }
    }
    private final java.util.concurrent.ExecutorService deviceRefreshWorker=java.util.concurrent.Executors.newSingleThreadExecutor(r -> { Thread thread=new Thread(r,"Monogram device lists"); thread.setDaemon(true); return thread; });
    private final Set<String> pendingDeviceRefresh=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private Set<OmemoDevice> sendingDevices(String address) throws Exception {
        EntityBareJid contact=JidCreate.entityBareFrom(address);
        Set<OmemoDevice> cached=omemo.getDevicesOf(contact);
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
    public void setBlindTrustPolicy(java.util.function.Predicate<String> policy){
        trust.setBlindTrustPolicy(policy);
        if(modern!=null)modern.device.setBlindTrustPolicy(policy);
    }
    public org.jivesoftware.smackx.omemo.trust.TrustState trustState(OmemoDevice device, OmemoFingerprint fingerprint) { return trust.getTrust(device, fingerprint); }
    public String senderFingerprint(XmppMessage.Encryption encryption) throws Exception {
        return encryption==XmppMessage.Encryption.PLAIN ? null : encryption==XmppMessage.Encryption.OMEMO2 ? modern().ownFingerprint() : ownFingerprint().toString();
    }
    public OmemoFingerprint ownFingerprint() throws Exception { return legacyDiscovery.ownFingerprint(); }
    public void decide(OmemoDevice device, OmemoFingerprint fingerprint, boolean trusted) { decideAll(java.util.Collections.singletonMap(device,fingerprint),trusted); }
    public void decideAll(java.util.Map<OmemoDevice, OmemoFingerprint> identities, boolean trusted) {
        java.util.Map<OmemoDevice,OmemoFingerprint> selected=java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(identities));
        if(!trusted) { synchronized(omemo) { if(closed) throw new IllegalStateException("Session is closed");trust.setTrustAll(selected,false); }return; }
        long generation=lifecycle.get(),trustRevision=trust.revision();
        org.jivesoftware.smackx.omemo.MonogramSignalStore records=MonogramLegacyOmemoService.instance().records();
        java.util.List<org.jivesoftware.smackx.omemo.MonogramLegacyRotation.Verified> replacements=new java.util.ArrayList<>();
        try {
            java.util.Map<OmemoDevice,String> observed=new java.util.LinkedHashMap<>();String expectedOwn;
            synchronized(omemo) { expectedOwn=records.getFingerprint(omemo.getOwnDevice()).toString(); }
            for(java.util.Map.Entry<OmemoDevice,OmemoFingerprint> entry:selected.entrySet()) {
                org.jivesoftware.smackx.omemo.MonogramLegacyRotation.Expected expected;
                synchronized(omemo) {
                    if(closed || lifecycle.get()!=generation) throw new java.util.ConcurrentModificationException("Account changed during approval");
                    org.whispersystems.libsignal.IdentityKey old=entry.getKey().equals(omemo.getOwnDevice())?records.loadOmemoIdentityKeyPair(omemo.getOwnDevice()).getPublicKey():records.loadOmemoIdentityKey(omemo.getOwnDevice(),entry.getKey());
                    observed.put(entry.getKey(),old==null?null:records.keyUtil().getFingerprintOfIdentityKey(old).toString());
                    expected=org.jivesoftware.smackx.omemo.MonogramLegacyRotation.replacement(records,omemo.getOwnDevice(),entry.getKey(),entry.getValue());
                }
                if(expected!=null) replacements.add(org.jivesoftware.smackx.omemo.MonogramLegacyRotation.verify(expected,entry.getValue(),
                        org.jivesoftware.smackx.omemo.MonogramDeviceEncryption.fetchBundle(connection,entry.getKey())));
            }
            synchronized(omemo) {
                org.jivesoftware.smackx.omemo.MonogramLegacyRotation.apply(records,omemo.getOwnDevice(),legacyIncoming,trust,selected,replacements,observed,expectedOwn,
                        () -> !closed && lifecycle.get()==generation && trust.revision()==trustRevision);
                MonogramLegacyOmemoService.instance().refreshRetired(omemo);
            }
        } catch(Exception failure) { throw new IllegalStateException("Could not safely approve replacement identity",failure); }
        // Replay failures (including a revoked historical fingerprint) must not misreport a committed approval.
        if(!replacements.isEmpty()) try {
            for(LegacyIncomingJournal.Received pending:MonogramLegacyOmemoService.instance().retiredPending(omemo)) { if(closed) break;deliverLegacy(pending); }
        } catch(Exception failure) { legacyFailure=failure; }
    }
    public boolean hasArchive() throws Exception { return MamManager.getInstanceFor(connection).isSupported(); }
    public XMPPTCPConnection connection() { return connection; }
    @Override public void close() {
        synchronized(omemo) { closed = true; started = false; lifecycle.incrementAndGet(); }
        IncomingCapacity.unregister(legacyCapacityKey,this);IncomingCapacity.unregister(modernCapacityKey,this);
        synchronized(modernInboxLock) { modernInbox.clear();modernInboxBytes=0;modernInboxLock.notifyAll(); }
        synchronized(legacyInboxLock) { legacyInbox.clear();legacyInboxBytes=0;legacyInboxLock.notifyAll(); }
        MonogramLegacyOmemoService.instance().unregister(omemo);legacyWorker.shutdownNow();
        if (modern != null) modern.close(); crypto.shutdownNow(); deviceRefreshWorker.shutdownNow();
        calls.close(); chatStates.close(); contacts.close(); blocking.close(); rooms.close(); bookmarks.close(); readSync.close(); fingerprints.close();
        ReconnectionManager manager = ReconnectionManager.getInstanceFor(connection);
        manager.disableAutomaticReconnection(); manager.abortPossiblyRunningReconnection();
        connection.disconnect();
    }
}
