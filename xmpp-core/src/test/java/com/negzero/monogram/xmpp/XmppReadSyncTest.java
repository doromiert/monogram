package com.negzero.monogram.xmpp;

import java.util.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smackx.sid.element.StanzaIdElement;
import org.jxmpp.jid.EntityBareJid;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class XmppReadSyncTest {
    @Test void privateReferencesRoundTripAndRejectMalformedOrMixedPayloads() throws Exception {
        XmppReadSync.Entry entry=XmppReadSync.Entry.create("bob@example.org","alice@example.org","opaque&<id");
        XmppReadSync.Entry parsed=XmppReadSync.Entry.parse(entry.chat,entry.xml());
        assertEquals(entry.by,parsed.by);assertEquals(entry.id,parsed.id);
        assertEquals("room@conference.example.org/Bob",XmppReadSync.Entry.create("room@conference.example.org/Bob","alice@example.org","id").chat);
        for(String xml:new String[]{"<displayed xmlns='wrong'/>","<displayed xmlns='"+XmppReadSync.NS+"'/>",
                "<displayed xmlns='"+XmppReadSync.NS+"'>text<stanza-id xmlns='urn:xmpp:sid:0' by='alice@example.org' id='a'/></displayed>",
                "<displayed xmlns='"+XmppReadSync.NS+"'><stanza-id xmlns='urn:xmpp:sid:0' by='alice@example.org' id='a'/><stanza-id xmlns='urn:xmpp:sid:0' by='alice@example.org' id='b'/></displayed>",
                "<!DOCTYPE displayed><displayed xmlns='"+XmppReadSync.NS+"'/>"}) {
            assertThrows(Exception.class,() -> XmppReadSync.Entry.parse("bob@example.org",xml));
        }
        assertThrows(Exception.class,() -> XmppReadSync.Entry.create("example.org","alice@example.org","id"));
        assertThrows(Exception.class,() -> XmppReadSync.Entry.create("bob@example.org","alice@example.org/phone","id"));
    }
    @Test void actualServerIdsRemainSeparateFromMamIdsAndAmbiguousAssertionsCannotBePublished() throws Exception {
        EntityBareJid own=JidCreate.entityBareFrom("alice@example.org");
        Message wire=new Message(own,Message.Type.chat);wire.setFrom(JidCreate.entityFullFrom("bob@example.org/phone"));wire.setBody("hello");wire.setStanzaId("sender-id");
        assertNull(XmppReadSync.assignedId(wire,own));
        wire.addExtension(new StanzaIdElement("foreign","bob@example.org"));assertNull(XmppReadSync.assignedId(wire,own));
        wire.addExtension(new StanzaIdElement("server-id",own.toString()));
        XmppMessage model=XmppMessage.from(wire,own,"hello",false,true,"different-mam-id",new Date(100000));
        assertEquals("server-id",model.ids.get(XmppReadSync.LOCAL_ID));assertEquals("different-mam-id",model.ids.get("archive"));assertEquals("different-mam-id",model.ids.get("mam"));
        XmppMessage live=XmppMessage.from(wire,own,"hello",false,true,null,null);
        assertNull(live.ids.get("mam"));assertEquals("journal-mam",live.archived("journal-mam",new Date(200000)).ids.get("mam"));
        assertEquals("server-id",model.withSenderFingerprint("fingerprint").ids.get(XmppReadSync.LOCAL_ID));
        wire.addExtension(new StanzaIdElement("second-server-id",own.toString()));assertNull(XmppReadSync.assignedId(wire,own));
        assertNull(XmppMessage.from(wire,own,"hello",false,true,"mam",null).ids.get(XmppReadSync.LOCAL_ID));
    }
    @Test void publicationFormsAlwaysRequirePrivatePersistentUnboundedState() {
        StandardExtensionElement form=XmppReadSync.form("http://jabber.org/protocol/pubsub#publish-options");
        Map<String,String> values=new HashMap<>();
        for(StandardExtensionElement field:form.getElements()) values.put(field.getAttributeValue("var"),field.getFirstElement("value").getText());
        assertEquals("whitelist",values.get("pubsub#access_model"));assertEquals("max",values.get("pubsub#max_items"));
        assertEquals("true",values.get("pubsub#persist_items"));assertEquals("never",values.get("pubsub#send_last_published_item"));
        assertEquals("http://jabber.org/protocol/pubsub#publish-options",values.get("FORM_TYPE"));
    }
}
