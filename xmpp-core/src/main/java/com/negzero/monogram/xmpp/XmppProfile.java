// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jivesoftware.smack.packet.StandardExtensionElement;
import org.jivesoftware.smack.parsing.StandardExtensionElementProvider;
import org.jivesoftware.smack.util.PacketParserUtils;
import org.jivesoftware.smack.util.stringencoder.Base64;

/** Keep the complete card, including fields that this client does not edit. */
public final class XmppProfile {
    public static final String VCARD = "vcard-temp";
    public static final int MAX_AVATAR_BYTES = 1024 * 1024;
    final StandardExtensionElement card;
    public final String nickname;
    public final byte[] avatar;

    public XmppProfile(String xml, String nickname, byte[] avatar) throws Exception {
        this(StandardExtensionElementProvider.INSTANCE.parse(PacketParserUtils.getParserFor(xml)), nickname, avatar);
    }
    XmppProfile(StandardExtensionElement card, String nickname, byte[] avatar) {
        if (!"vCard".equals(card.getElementName()) || !VCARD.equals(card.getNamespace())) throw new IllegalArgumentException("Invalid vCard");
        if (card.toXML().length() > 2 * 1024 * 1024) throw new IllegalArgumentException("Profile too large");
        this.card = card;
        this.nickname = nickname == null ? field("NICKNAME") : nickname;
        this.avatar = avatar == null ? cardAvatar() : checkedAvatar(avatar);
    }
    public String xml() { return card.toXML().toString(); }
    public String field(String... path) {
        StandardExtensionElement element = card;
        for (String name : path) {
            element = element.getFirstElement(name, VCARD);
            if (element == null) return "";
        }
        return element.getText() == null ? "" : element.getText();
    }
    public String displayName(String rosterName, String jid) {
        if (rosterName != null && !rosterName.isEmpty() && !rosterName.equals(jid)) return rosterName;
        if (!nickname.trim().isEmpty()) return nickname;
        if (!field("FN").trim().isEmpty()) return field("FN");
        String name = (field("N", "GIVEN") + " " + field("N", "FAMILY")).trim();
        return name.isEmpty() ? jid : name;
    }
    byte[] cardAvatar() {
        String encoded = field("PHOTO", "BINVAL");
        if (encoded.isEmpty() || encoded.length() > (MAX_AVATAR_BYTES * 4 / 3 + 4096)) return new byte[0];
        try { return checkedAvatar(Base64.decode(encoded.replaceAll("\\s", ""))); }
        catch (IllegalArgumentException invalid) { return new byte[0]; }
    }
    static byte[] checkedAvatar(byte[] bytes) {
        if (bytes == null) throw new IllegalArgumentException("Invalid avatar encoding");
        if (bytes.length > MAX_AVATAR_BYTES) throw new IllegalArgumentException("Avatar too large");
        return bytes.clone();
    }
    static StandardExtensionElement replace(StandardExtensionElement parent, Map<String, StandardExtensionElement> changes) {
        StandardExtensionElement.Builder result = StandardExtensionElement.builder(parent.getElementName(), parent.getNamespace()).addAttributes(parent.getAttributes());
        if (parent.getText() != null) result.setText(parent.getText());
        for (StandardExtensionElement child : parent.getElements()) {
            if (!VCARD.equals(child.getNamespace()) || !changes.containsKey(child.getElementName())) result.addElement(child);
        }
        for (StandardExtensionElement child : changes.values()) if (child != null) result.addElement(child);
        return result.build();
    }
    static StandardExtensionElement text(String name, String value) {
        return StandardExtensionElement.builder(name, VCARD).setText(value == null ? "" : value).build();
    }
    public XmppProfile edit(String first, String last, String about) {
        Map<String, StandardExtensionElement> fields = new LinkedHashMap<>();
        if (first != null || last != null) {
            if (first == null) first = field("N", "GIVEN").isEmpty() ? field("FN") : field("N", "GIVEN");
            if (last == null) last = field("N", "FAMILY");
            Map<String, StandardExtensionElement> names = new LinkedHashMap<>();
            names.put("GIVEN", text("GIVEN", first)); names.put("FAMILY", text("FAMILY", last));
            StandardExtensionElement n = card.getFirstElement("N");
            if (n == null) n = StandardExtensionElement.builder("N", VCARD).build();
            fields.put("N", replace(n, names)); fields.put("FN", text("FN", (first + " " + last).trim()));
            fields.put("NICKNAME", text("NICKNAME", (first + " " + last).trim()));
        }
        if (about != null) fields.put("DESC", text("DESC", about));
        return new XmppProfile(replace(card, fields), null, avatar);
    }
    public XmppProfile avatar(byte[] bytes, String mime) {
        checkedAvatar(bytes);
        Map<String, StandardExtensionElement> fields = new LinkedHashMap<>();
        fields.put("PHOTO", bytes.length == 0 ? null : StandardExtensionElement.builder("PHOTO", VCARD)
                .addElement("TYPE", mime).addElement("BINVAL", Base64.encodeToString(bytes)).build());
        return new XmppProfile(replace(card, fields), nickname, bytes);
    }
}
