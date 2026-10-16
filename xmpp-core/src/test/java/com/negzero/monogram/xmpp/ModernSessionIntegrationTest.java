package com.negzero.monogram.xmpp;

import static org.junit.jupiter.api.Assertions.*;
import java.io.File;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.StandardExtensionElement;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="MONOGRAM_IT",matches="1")
@EnabledIfEnvironmentVariable(named="MONOGRAM_OMEMO2",matches="1")
class ModernSessionIntegrationTest {
    static final class Inbox implements XmppSession.Listener {
        final BlockingQueue<XmppMessage> messages=new LinkedBlockingQueue<>();
        final BlockingQueue<Runnable> accepted=new LinkedBlockingQueue<>();
        @Override public void message(org.jxmpp.jid.EntityBareJid jid,String id,String body,boolean encrypted) {}
        @Override public void message(XmppMessage message) { messages.add(message); }
        @Override public void message(XmppMessage message,Runnable stored) { messages.add(message); accepted.add(stored); }
        XmppMessage next() throws Exception { XmppMessage message=messages.poll(15,TimeUnit.SECONDS); assertNotNull(message); return message; }
        void store() throws Exception { Runnable stored=accepted.poll(5,TimeUnit.SECONDS); assertNotNull(stored); stored.run(); }
    }
    private XmppSession session(String user,int id,boolean modern,Inbox inbox) throws Exception {
        File state=new File(System.getenv("MONOGRAM_IT_STATE")); XmppSession.initializeOmemo(new File(state,"keys"));
        Account account=new Account(user+"@localhost","127.0.0.1",Integer.parseInt(System.getenv("MONOGRAM_IT_PORT")),"Session-"+id);
        return new XmppSession(account,"monogram-test",id,new File(state,user+".trust"),
                modern ? new Omemo2Device(user+"@localhost",id+1000,new Omemo2TestDatabase()) : null,inbox);
    }
    private void trust(XmppSession own,String peer) throws Exception {
        for (Map.Entry<Integer,Omemo2Bundle> device:own.modern().discover(peer).entrySet()) own.modern().device.decide(peer,device.getKey(),device.getValue().identity(),true);
    }
    @Test void nativeSessionDeliversCaptionsAndWaitsForStorageBeforeAcknowledging() throws Exception {
        Inbox a=new Inbox(),b=new Inbox();
        try (XmppSession alice=session("sessionalice",411,true,a); XmppSession bob=session("sessionbob",422,true,b)) {
            alice.connect(); bob.connect(); assertTrue(alice.modernAvailable()); assertTrue(bob.modernAvailable());
            trust(alice,"sessionbob@localhost"); trust(bob,"sessionalice@localhost");
            BlockingQueue<Message> controls=new LinkedBlockingQueue<>();
            alice.connection().addAsyncStanzaListener(stanza -> controls.add((Message)stanza),stanza -> stanza instanceof Message
                    && stanza.hasExtension("encrypted",XmppOmemo2.NS)
                    && ((StandardExtensionElement)stanza.getExtension("encrypted",XmppOmemo2.NS)).getFirstElement("payload",XmppOmemo2.NS)==null);
            ReplyReference reply=new ReplyReference("sessionbob@localhost","original");
            String id=alice.send("sessionbob@localhost","Modern native session 😀",XmppMessage.Encryption.OMEMO2,reply);
            XmppMessage first=b.next(); assertTrue(first.readable); assertEquals(XmppMessage.Encryption.OMEMO2,first.encryption);
            assertEquals("original",first.reply.id); assertEquals(id,first.ids.get("stanza"));
            assertNull(controls.poll(200,TimeUnit.MILLISECONDS),"No ACK before the Android adapter commits the message");
            b.store(); assertNotNull(controls.poll(10,TimeUnit.SECONDS));
            String url="aesgcm://example.org/friends.png#"+new String(new char[88]).replace('\0','a');
            FileShare sharing=new FileShare("<file-sharing xmlns='urn:xmpp:sfs:0'><file xmlns='urn:xmpp:file:metadata:0'><name>Friends.png</name><media-type>image/png</media-type></file><sources><url-data xmlns='http://jabber.org/protocol/url-data' target='"+url+"'/></sources></file-sharing>");
            String media=alice.sendFile("sessionbob@localhost",sharing,"One private caption 🦄",reply);
            XmppMessage photo=b.next(); assertTrue(photo.readable); assertEquals("One private caption 🦄",photo.caption);
            assertEquals(url,photo.attachment.wireUrl); assertEquals(XmppMessage.Encryption.OMEMO2,photo.encryption); b.store();
            assertEquals("One private caption 🦄",XmppMessage.sentFile(alice.connection().getUser(),"sessionbob@localhost",media,sharing,"One private caption 🦄",reply).caption);
            alice.correct("sessionbob@localhost","Modern edited text",XmppMessage.Encryption.OMEMO2,reply,new MessageCorrection(id));
            XmppMessage edit=b.next(); assertEquals(id,edit.correction.id); assertEquals("Modern edited text",edit.body); b.store();
            XmppSession.ArchivePage page=bob.archiveBefore("sessionalice@localhost",null,100);
            XmppMessage archived=page.messages.stream().filter(m -> id.equals(m.ids.get("stanza"))).findFirst().orElseThrow(AssertionError::new);
            assertEquals(XmppMessage.Encryption.OMEMO2,archived.encryption); assertFalse(archived.readable); page.stored(); page.stored();
            bob.sendReactions("sessionalice@localhost",new MessageReactions(id,java.util.Arrays.asList("👍","🐢")),XmppMessage.Encryption.PLAIN);
            XmppMessage plainReaction=a.next(); assertNull(plainReaction.body); assertFalse(plainReaction.encrypted); assertEquals(id,plainReaction.reactions.id);
            for(java.util.List<String> emojis:java.util.Arrays.asList(java.util.Arrays.asList("❤️","🔥"),java.util.Collections.<String>emptyList())) {
                bob.sendReactions("sessionalice@localhost",new MessageReactions(id,emojis),XmppMessage.Encryption.OMEMO2);
                XmppMessage reaction=a.next(); assertNull(reaction.body); assertTrue(reaction.encrypted); assertEquals(id,reaction.reactions.id); assertEquals(new java.util.LinkedHashSet<>(emojis),reaction.reactions.emojis); a.store();
            }
            assertThrows(UnsupportedOperationException.class,() -> bob.sendReactions("sessionalice@localhost",new MessageReactions(id,java.util.Collections.singleton("👍")),XmppMessage.Encryption.LEGACY_OMEMO));
            alice.modern().close();
            OmemoSendException failure=assertThrows(OmemoSendException.class,() -> alice.send("sessionbob@localhost","Never downgrade",XmppMessage.Encryption.OMEMO2,null));
            assertEquals(OmemoSendException.Reason.MODERN_UNAVAILABLE,failure.reason); assertNull(b.messages.poll(200,TimeUnit.MILLISECONDS));
        }
    }
    @Test void unsupportedModernPayloadIsStoredAsEncryptedInsteadOfItsPublicFallback() throws Exception {
        Inbox a=new Inbox(),b=new Inbox();
        try (XmppSession alice=session("fallbackalice",433,false,a); XmppSession bob=session("fallbackbob",444,false,b)) {
            alice.connect(); bob.connect();
            Message packet=new Message(JidCreate.entityBareFrom("fallbackbob@localhost"),Message.Type.chat); packet.setStanzaId("unsupported-modern");
            packet.setBody("This message is OMEMO encrypted."); packet.addExtension(StandardExtensionElement.builder("encrypted",XmppOmemo2.NS).addElement("payload","invalid-test-content").build());
            new MessageCorrection("forged-edit").addTo(packet); alice.connection().sendStanza(packet);
            XmppMessage received=b.next(); assertFalse(received.readable); assertTrue(received.encrypted);
            assertEquals(XmppMessage.Encryption.OMEMO2,received.encryption); assertNull(received.body); assertNull(received.correction); b.store();
            XmppSession.ArchivePage page=bob.archiveBefore("fallbackalice@localhost",null,100);
            assertTrue(page.messages.stream().anyMatch(m -> m.encryption==XmppMessage.Encryption.OMEMO2 && !m.readable && m.body==null));
        }
    }
}
