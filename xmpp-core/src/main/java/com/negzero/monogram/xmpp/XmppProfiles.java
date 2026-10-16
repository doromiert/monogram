// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException.XMPPErrorException;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.provider.IQProvider;
import org.jivesoftware.smack.provider.ProviderManager;
import org.jivesoftware.smack.parsing.StandardExtensionElementProvider;
import org.jivesoftware.smack.util.stringencoder.Base64;
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager;
import org.jivesoftware.smackx.pep.PepManager;
import org.jivesoftware.smackx.pubsub.*;
import org.jxmpp.jid.EntityBareJid;
import org.jxmpp.jid.impl.JidCreate;

/** Standard public profiles. Avatar data is inline PEP/vCard only, never a remote URL. */
public final class XmppProfiles {
    public static final String NICK = "http://jabber.org/protocol/nick";
    public static final String META = "urn:xmpp:avatar:metadata", DATA = "urn:xmpp:avatar:data";
    private final XMPPConnection connection;
    private volatile String ownAvatarHash;
    private static final class CardIq extends IQ {
        final StandardExtensionElement card;
        CardIq(StandardExtensionElement card) { super("vCard", XmppProfile.VCARD); this.card = card; }
        @Override protected IQChildElementXmlStringBuilder getIQChildElementBuilder(IQChildElementXmlStringBuilder xml) {
            for (java.util.Map.Entry<String, String> attr : card.getAttributes().entrySet()) xml.attribute(attr.getKey(), attr.getValue());
            xml.rightAngleBracket();
            for (StandardExtensionElement child : card.getElements()) xml.append(child.toXML(XmppProfile.VCARD));
            return xml;
        }
    }
    public XmppProfiles(XMPPConnection connection, Consumer<EntityBareJid> changed) {
        this.connection = connection;
        // Smack's VCard provider drops unknown and repeated fields on save.
        ProviderManager.addIQProvider("vCard", XmppProfile.VCARD, new IQProvider<CardIq>() {
            @Override public CardIq parse(org.jivesoftware.smack.xml.XmlPullParser parser, int depth, XmlEnvironment env)
                    throws java.io.IOException, org.jivesoftware.smack.xml.XmlPullParserException {
                return new CardIq(StandardExtensionElementProvider.INSTANCE.parse(parser, depth, env));
            }
        });
        ServiceDiscoveryManager disco = ServiceDiscoveryManager.getInstanceFor(connection);
        disco.addFeature(XmppProfile.VCARD); disco.addFeature(NICK + "+notify"); disco.addFeature(META + "+notify");
        PepManager.getInstanceFor(connection).addPepListener((from, event, message) -> {
            String node = event.getEvent().getNode();
            if (NICK.equals(node) || META.equals(node)) changed.accept(from);
        });
        connection.addStanzaInterceptor(stanza -> {
            if (stanza instanceof Presence && ((Presence) stanza).getType() == Presence.Type.available && ownAvatarHash != null) {
                stanza.overrideExtension(StandardExtensionElement.builder("x", "vcard-temp:x:update").addElement("photo", ownAvatarHash).build());
            }
        }, stanza -> stanza instanceof Presence);
        connection.addAsyncStanzaListener(stanza -> {
            if (stanza.getFrom() != null && stanza.getFrom().asEntityBareJidIfPossible() != null) changed.accept(stanza.getFrom().asEntityBareJidIfPossible());
        }, stanza -> stanza instanceof Presence && stanza.hasExtension("x", "vcard-temp:x:update"));
    }
    private XmppProfile card(EntityBareJid jid, boolean editing) throws Exception {
        CardIq request = new CardIq(StandardExtensionElement.builder("vCard", XmppProfile.VCARD).build());
        request.setType(IQ.Type.get);
        if (!jid.equals(connection.getUser().asEntityBareJid())) request.setTo(jid);
        try {
            CardIq result = connection.createStanzaCollectorAndSend(request).nextResultOrThrow();
            return new XmppProfile(result.card, null, null);
        } catch (XMPPErrorException absent) {
            if (!optional(absent) || editing && absent.getStanzaError().getCondition() != StanzaError.Condition.item_not_found) throw absent;
            return new XmppProfile(request.card, null, null);
        }
    }
    private static boolean optional(XMPPErrorException error) {
        StanzaError.Condition c = error.getStanzaError().getCondition();
        return c == StanzaError.Condition.item_not_found || c == StanzaError.Condition.service_unavailable
                || c == StanzaError.Condition.feature_not_implemented || c == StanzaError.Condition.forbidden;
    }
    private StandardExtensionElement item(EntityBareJid jid, String node, String id) throws Exception {
        try {
            LeafNode leaf = PubSubManager.getInstanceFor(connection, jid).getLeafNode(node);
            List<PayloadItem<ExtensionElement>> items = id == null ? leaf.getItems(1) : leaf.getItems(Collections.singletonList(id));
            if (items.isEmpty()) return null;
            ExtensionElement payload = items.get(0).getPayload();
            if (payload == null) return null;
            return StandardExtensionElementProvider.INSTANCE.parse(org.jivesoftware.smack.util.PacketParserUtils.getParserFor(payload.toXML().toString()));
        } catch (XMPPErrorException absent) { if (optional(absent)) return null; throw absent; }
    }
    public XmppProfile load(String address) throws Exception {
        EntityBareJid jid = JidCreate.entityBareFrom(address);
        XmppProfile card = card(jid, false);
        StandardExtensionElement nick = item(jid, NICK, null);
        String nickname = nick != null && NICK.equals(nick.getNamespace()) && "nick".equals(nick.getElementName()) ? nick.getText() : null;
        byte[] avatar = null;
        StandardExtensionElement meta = item(jid, META, null);
        if (meta != null && META.equals(meta.getNamespace()) && "metadata".equals(meta.getElementName())) {
            if (meta.getElements().isEmpty()) avatar = new byte[0];
            for (StandardExtensionElement info : meta.getElements()) {
                String id = info.getAttributeValue("id");
                if (!"info".equals(info.getElementName()) || !META.equals(info.getNamespace()) || id == null || !id.matches("[0-9a-fA-F]{40}")) continue;
                long size;
                try { size = Long.parseLong(info.getAttributeValue("bytes")); } catch (RuntimeException bad) { continue; }
                if (size <= 0 || size > XmppProfile.MAX_AVATAR_BYTES) continue;
                StandardExtensionElement data = item(jid, DATA, id);
                if (data == null || !DATA.equals(data.getNamespace()) || !"data".equals(data.getElementName()) || data.getText() == null
                        || data.getText().length() > XmppProfile.MAX_AVATAR_BYTES * 4 / 3 + 4096) continue;
                try {
                    byte[] bytes = Base64.decode(data.getText().replaceAll("\\s", ""));
                    if (bytes != null && bytes.length == size && sha1(bytes).equalsIgnoreCase(id)) { avatar = bytes; break; }
                } catch (IllegalArgumentException invalid) { /* Try another advertised representation. */ }
            }
        }
        XmppProfile result = new XmppProfile(card.card, nickname, avatar);
        return result;
    }
    public void useOwnProfile(XmppProfile profile) throws Exception {
        String hash = sha1OrEmpty(profile.cardAvatar());
        if (hash.equals(ownAvatarHash)) return;
        ownAvatarHash = hash;
        connection.sendStanza(new Presence(Presence.Type.available));
    }
    private void saveCard(XmppProfile profile) throws Exception {
        CardIq request = new CardIq(profile.card); request.setType(IQ.Type.set);
        connection.createStanzaCollectorAndSend(request).nextResultOrThrow();
    }
    public XmppProfile edit(String first, String last, String about) throws Exception {
        // Always retrieve immediately before writing; cached cards may be stale or incomplete.
        XmppProfile changed = card(connection.getUser().asEntityBareJid(), true).edit(first, last, about);
        saveCard(changed);
        if ((first != null || last != null) && PepManager.getInstanceFor(connection).isSupported()) PepManager.getInstanceFor(connection).publish(NICK,
                new PayloadItem<>(StandardExtensionElement.builder("nick", NICK).setText(changed.nickname).build()));
        return load(connection.getUser().asEntityBareJid().toString());
    }
    public XmppProfile avatar(byte[] png) throws Exception {
        XmppProfile.checkedAvatar(png);
        XmppProfile changed = card(connection.getUser().asEntityBareJid(), true).avatar(png, "image/png");
        saveCard(changed);
        if (PepManager.getInstanceFor(connection).isSupported()) {
            PepManager pep = PepManager.getInstanceFor(connection);
            String hash = sha1OrEmpty(png);
            if (png.length > 0) pep.publish(DATA, new PayloadItem<>(hash, StandardExtensionElement.builder("data", DATA).setText(Base64.encodeToString(png)).build()));
            StandardExtensionElement.Builder meta = StandardExtensionElement.builder("metadata", META);
            if (png.length > 0) meta.addElement(StandardExtensionElement.builder("info", META)
                    .addAttribute("id", hash).addAttribute("bytes", Integer.toString(png.length)).addAttribute("type", "image/png").build());
            pep.publish(META, new PayloadItem<>(hash.isEmpty() ? "current" : hash, meta.build()));
        }
        ownAvatarHash = sha1OrEmpty(png);
        connection.sendStanza(new Presence(Presence.Type.available));
        return load(connection.getUser().asEntityBareJid().toString());
    }
    private static String sha1OrEmpty(byte[] data) throws Exception { return data.length == 0 ? "" : sha1(data); }
    public static String sha1(byte[] data) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : java.security.MessageDigest.getInstance("SHA-1").digest(data)) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }
}
