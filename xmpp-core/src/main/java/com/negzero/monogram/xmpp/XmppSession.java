// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.io.File;
import java.util.Set;
import java.util.UUID;
import org.jivesoftware.smack.ConnectionConfiguration.SecurityMode;
import org.jivesoftware.smack.ReconnectionManager;
import org.jivesoftware.smack.packet.Message;
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
    }
    private static File keyRoot;
    private final XMPPTCPConnection connection;
    private final OmemoManager omemo;
    private final IdentityTrust trust;

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
        if (keyRoot == null) throw new IllegalStateException("Initialize the private OMEMO store first");
        if (deviceId < 1) throw new IllegalArgumentException("OMEMO device ID must be positive and persistent");
        XMPPTCPConnectionConfiguration.Builder config = XMPPTCPConnectionConfiguration.builder()
                .setXmppDomain(account.jid.getDomain().toString()).setUsernameAndPassword(account.jid.getLocalpart(), password)
                .setResource(account.resource).setSecurityMode(SecurityMode.required)
                .setSendPresence(false).setCompressionEnabled(false).setPort(account.port);
        if (account.host != null) config.setHost(account.host);
        connection = new XMPPTCPConnection(config.build());
        ReconnectionManager.getInstanceFor(connection).enableAutomaticReconnection();
        DeliveryReceiptManager receipts = DeliveryReceiptManager.getInstanceFor(connection);
        receipts.setAutoReceiptMode(DeliveryReceiptManager.AutoReceiptMode.ifIsSubscribed);
        trust = new IdentityTrust(trustFile);
        omemo = OmemoManager.getInstanceFor(connection, deviceId);
        omemo.setTrustCallback(trust);
        omemo.addOmemoMessageListener(new OmemoMessageListener() {
            @Override public void onOmemoMessageReceived(Stanza stanza, OmemoMessage.Received decrypted) {
                if (!decrypted.isKeyTransportMessage()) listener.message(
                        stanza.getFrom().asEntityBareJidIfPossible(), stanza.getStanzaId(), decrypted.getBody(), true);
            }
            @Override public void onOmemoCarbonCopyReceived(org.jivesoftware.smackx.carbons.packet.CarbonExtension.Direction direction,
                    Message carbon, Message wrapping, OmemoMessage.Received decrypted) {
                if (decrypted.isKeyTransportMessage()) return;
                if (direction == org.jivesoftware.smackx.carbons.packet.CarbonExtension.Direction.sent) {
                    listener.sentCarbon(carbon.getTo().asEntityBareJidIfPossible(), carbon.getStanzaId(), decrypted.getBody(), true);
                } else {
                    listener.message(carbon.getFrom().asEntityBareJidIfPossible(), carbon.getStanzaId(), decrypted.getBody(), true);
                }
            }
        });
        connection.addAsyncStanzaListener(stanza -> {
            Message message = (Message) stanza;
            if (message.getBody() != null && !message.hasExtension(OmemoElement.NAME_ENCRYPTED, OmemoElement_VAxolotl.NAMESPACE)) {
                EntityBareJid sender = message.getFrom() == null ? null : message.getFrom().asEntityBareJidIfPossible();
                if (sender != null) listener.message(sender, message.getStanzaId(), message.getBody(), false);
            }
        }, stanza -> stanza instanceof Message && ((Message) stanza).getType() == Message.Type.chat);
    }

    public void connect() throws Exception {
        try {
            connection.connect().login();
            if (org.jivesoftware.smackx.pep.PepManager.getInstanceFor(connection).isSupported()) omemo.initialize();
            CarbonManager carbons = CarbonManager.getInstanceFor(connection);
            if (carbons.isSupportedByServer()) carbons.enableCarbons();
            connection.sendStanza(new org.jivesoftware.smack.packet.Presence(org.jivesoftware.smack.packet.Presence.Type.available));
        } catch (Exception e) { connection.disconnect(); throw e; }
    }
    public String send(String jid, String body, boolean encrypted) throws Exception {
        if (body == null || body.isEmpty()) throw new IllegalArgumentException("Empty message");
        EntityBareJid recipient = JidCreate.entityBareFrom(jid);
        Message message;
        if (encrypted) {
            try {
                if (!omemo.contactSupportsOmemo(recipient)) throw new OmemoSendException(OmemoSendException.Reason.NO_DEVICES);
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
        String id = UUID.randomUUID().toString();
        message.setStanzaId(id);
        message.addExtension(new OriginIdElement(id));
        DeliveryReceiptManager.addDeliveryReceiptRequest(message);
        connection.sendStanza(message);
        return id;
    }
    public Set<OmemoDevice> devices(String jid) throws Exception {
        EntityBareJid contact = JidCreate.entityBareFrom(jid);
        try {
            omemo.requestDeviceListUpdateFor(contact);
            return omemo.getDevicesOf(contact);
        } catch (org.jivesoftware.smack.XMPPException.XMPPErrorException e) {
            if (e.getStanzaError().getCondition() == org.jivesoftware.smack.packet.StanzaError.Condition.item_not_found) return java.util.Collections.emptySet();
            throw e;
        }
    }
    public OmemoFingerprint fingerprint(OmemoDevice device) throws Exception { return omemo.getFingerprint(device); }
    public String ownJid() { return omemo.getOwnJid().toString(); }
    public int ownDeviceId() { return omemo.getDeviceId(); }
    public org.jivesoftware.smackx.omemo.trust.TrustState trustState(OmemoDevice device, OmemoFingerprint fingerprint) { return trust.getTrust(device, fingerprint); }
    public OmemoFingerprint ownFingerprint() throws Exception { return omemo.getOwnFingerprint(); }
    public void decide(OmemoDevice device, OmemoFingerprint fingerprint, boolean trusted) { trust.setTrust(device, fingerprint, trusted); }
    public void decideAll(java.util.Map<OmemoDevice, OmemoFingerprint> identities, boolean trusted) { trust.setTrustAll(identities, trusted); }
    public boolean hasArchive() throws Exception { return MamManager.getInstanceFor(connection).isSupported(); }
    public XMPPTCPConnection connection() { return connection; }
    @Override public void close() { connection.disconnect(); }
}
