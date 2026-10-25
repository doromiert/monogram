package com.negzero.monogram.xmpp;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class XmppProfileTest {
    @org.junit.jupiter.api.BeforeAll static void initializeEncoding() {
        org.jivesoftware.smack.util.stringencoder.Base64.setEncoder(org.jivesoftware.smack.util.stringencoder.java7.Java7Base64Encoder.getInstance());
    }
    static final String CARD = "<vCard xmlns='vcard-temp' version='3.0'><FN>Alice Example</FN>"
            + "<N><GIVEN>Alice</GIVEN><MIDDLE>Middle</MIDDLE><FAMILY>Example</FAMILY><PREFIX>Dr</PREFIX></N>"
            + "<NICKNAME>ali</NICKNAME><DESC>Old bio</DESC><BDAY>1990-02-03</BDAY>"
            + "<EMAIL><HOME/><USERID>home@example.org</USERID></EMAIL><EMAIL><WORK/><USERID>work@example.org</USERID></EMAIL>"
            + "<ADR><HOME/><STREET>Somewhere</STREET></ADR><ORG><ORGNAME>Example</ORGNAME><ORGUNIT>Team</ORGUNIT></ORG>"
            + "<extra xmlns='urn:example:profile' label='keep'><nested>Keep &amp; escape</nested></extra></vCard>";
    @Test void editsPreserveOtherClientsFieldsAndRosterAliases() throws Exception {
        XmppProfile original = new XmppProfile(CARD, null, null);
        assertEquals("ali", original.displayName(null, "alice@example.org"));
        assertEquals("Local alias", original.displayName("Local alias", "alice@example.org"));
        assertEquals("ali", original.displayName("alice@example.org", "alice@example.org"));
        XmppProfile bio = original.edit(null, null, "New <bio> & text");
        assertEquals("ali", bio.nickname); assertEquals("Alice Example", bio.field("FN"));
        assertEquals("New <bio> & text", new XmppProfile(bio.xml(), null, null).field("DESC"));
        XmppProfile changed = bio.edit("Alicia", null, null);
        assertEquals("Alicia Example", changed.field("FN"));
        assertEquals("Middle", changed.field("N", "MIDDLE")); assertEquals("Dr", changed.field("N", "PREFIX"));
        assertEquals("1990-02-03", changed.field("BDAY"));
        assertEquals(2, changed.card.getElements("EMAIL").size());
        assertEquals("Team", changed.field("ORG", "ORGUNIT"));
        assertEquals("3.0", changed.card.getAttributeValue("version"));
        assertEquals("Keep & escape", changed.card.getFirstElement("extra", "urn:example:profile").getFirstElement("nested").getText());
        XmppProfile removed = changed.avatar(new byte[]{1, 2, 3}, "image/png").avatar(new byte[0], "image/png");
        assertEquals(0, removed.avatar.length); assertEquals(2, removed.card.getElements("EMAIL").size());
    }
    @Test void publicFieldEditsPreserveRepeatedEntriesUnknownNamespacesAndAvatar() throws Exception {
        XmppProfile original=new XmppProfile(CARD,null,null);
        XmppProfile changed=original.editField("EMAIL/USERID","new@example.org").editField("ORG/ORGNAME","New org").editField("URL","https://example.org");
        assertEquals("new@example.org",changed.field("EMAIL","USERID"));
        assertEquals(2,changed.card.getElements("EMAIL").size());
        assertEquals("work@example.org",changed.card.getElements("EMAIL").get(1).getFirstElement("USERID").getText());
        assertEquals("Team",changed.field("ORG","ORGUNIT"));assertEquals("New org",changed.field("ORG","ORGNAME"));
        assertEquals("https://example.org",changed.field("URL"));assertEquals("ali",changed.nickname);
        assertNotNull(changed.card.getFirstElement("extra","urn:example:profile"));
        assertEquals("",changed.editField("URL","").field("URL"));
        assertThrows(IllegalArgumentException.class,() -> original.editField("PHOTO/BINVAL","changed"));
    }
    @Test void invalidAndOversizedAvatarsDoNotBecomeUnboundedPayloads() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new XmppProfile("<vCard xmlns='wrong'/>", null, null));
        XmppProfile invalid = new XmppProfile("<vCard xmlns='vcard-temp'><PHOTO><TYPE>image/png</TYPE><BINVAL>!</BINVAL></PHOTO></vCard>", null, null);
        assertEquals(0, invalid.avatar.length);
        assertThrows(IllegalArgumentException.class, () -> invalid.avatar(new byte[XmppProfile.MAX_AVATAR_BYTES + 1], "image/png"));
        assertEquals("alice@example.org", new XmppProfile("<vCard xmlns='vcard-temp'/>", "", null).displayName(null, "alice@example.org"));
    }
}
