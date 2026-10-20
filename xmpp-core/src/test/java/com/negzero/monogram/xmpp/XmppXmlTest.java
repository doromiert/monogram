package com.negzero.monogram.xmpp;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.provider.ProviderManager;
import org.jivesoftware.smack.util.*;
import org.jivesoftware.smackx.omemo.element.*;
import org.jivesoftware.smackx.omemo.provider.OmemoVAxolotlProvider;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;

class XmppXmlTest {
    @Test void nestedXmlIsFlattenedWithoutCharacterByCharacterTraversal() {
        XmlStringBuilder child=new XmlStringBuilder() {
            @Override public char charAt(int index) { throw new AssertionError("Nested XML must be streamed"); }
        };
        child.openElement("header");
        for(int i=0;i<1000;i++) child.element("key","ciphertext");
        child.closeElement("header");
        XmlStringBuilder outer=new XmlStringBuilder().openElement("encrypted").append(child).closeElement("encrypted");
        Element element=environment -> outer;
        String xml=XmppXml.string(element);
        assertTrue(xml.startsWith("<encrypted><header><key>ciphertext</key>"));
        assertEquals(1000,xml.split("<key>",-1).length-1);
        assertTrue(xml.endsWith("</header></encrypted>"));
    }

    @Test void encryptedEnvelopeRetainsKeysPayloadAndNamespaces() throws Exception {
        ProviderManager.addExtensionProvider("encrypted",OmemoElement_VAxolotl.NAMESPACE,new OmemoVAxolotlProvider());
        List<OmemoKeyElement> keys=new ArrayList<>();
        for(int i=1;i<=500;i++) keys.add(new OmemoKeyElement(new byte[]{1,2,(byte)i},i,true));
        byte[] iv=new byte[16],payload=new byte[]{4,5,6};
        Message original=new Message(JidCreate.entityBareFrom("alice@example.org"),Message.Type.chat);
        original.setFrom(JidCreate.entityFullFrom("bob@example.org/phone"));original.setStanzaId("message-1");
        original.setBody("fallback < & >");
        original.addExtension(new OmemoElement_VAxolotl(new OmemoHeaderElement_VAxolotl(202,keys,iv),payload));
        Message parsed=PacketParserUtils.parseStanza(XmppXml.string(original));
        assertEquals(original.getFrom(),parsed.getFrom());assertEquals(original.getTo(),parsed.getTo());
        assertEquals(original.getStanzaId(),parsed.getStanzaId());assertEquals(original.getBody(),parsed.getBody());
        OmemoElement encrypted=parsed.getExtension("encrypted",OmemoElement_VAxolotl.NAMESPACE);
        assertEquals(202,encrypted.getHeader().getSid());assertArrayEquals(iv,encrypted.getHeader().getIv());
        assertArrayEquals(payload,encrypted.getPayload());assertEquals(keys.size(),encrypted.getHeader().getKeys().size());
        for(int i=0;i<keys.size();i++) {
            OmemoKeyElement actual=encrypted.getHeader().getKeys().get(i);
            assertEquals(keys.get(i).getId(),actual.getId());assertTrue(actual.isPreKey());assertArrayEquals(keys.get(i).getData(),actual.getData());
        }
    }
}
