package com.negzero.monogram.xmpp;

import java.io.File;
import java.util.concurrent.*;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smackx.chatstates.ChatState;
import org.jivesoftware.smackx.chatstates.packet.ChatStateExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MONOGRAM_IT",matches="1")
class XmppPresentationIntegrationTest {
    final File root=new File(System.getenv("MONOGRAM_IT_STATE"));
    void initialize() throws Exception { XmppSession.initializeOmemo(new File(root,"keys"),LegacyOmemoTestDatabase.shared()); }
    XmppSession session(String user,int device,XmppSession.Listener listener) throws Exception {
        return new XmppSession(new Account(user+"@localhost","127.0.0.1",Integer.parseInt(System.getenv("MONOGRAM_IT_PORT")),"Presentation-"+device),"monogram-test",device,new File(root,"presentation-"+device),listener);
    }
    @Test void typingAddressedToDesktopAlsoReachesMobileViaValidatedCarbons() throws Exception {
        initialize();BlockingQueue<Boolean> mobileTyping=new LinkedBlockingQueue<>();
        XmppSession.Listener ignore=(from,id,body,encrypted)->{};
        try(XmppSession mobile=session("presentationtypingalice",1701,new XmppSession.Listener() {
            public void message(org.jxmpp.jid.EntityBareJid from,String id,String body,boolean encrypted) {}
            public void typing(org.jxmpp.jid.EntityBareJid from,boolean composing) {mobileTyping.add(composing);}
        });XmppSession desktop=session("presentationtypingalice",1702,ignore);XmppSession bob=session("presentationtypingbob",1703,ignore)) {
            mobile.connect();desktop.connect();bob.connect();
            Message composing=new Message(desktop.connection().getUser(),Message.Type.chat);composing.addExtension(new ChatStateExtension(ChatState.composing));bob.connection().sendStanza(composing);
            assertEquals(Boolean.TRUE,mobileTyping.poll(8,TimeUnit.SECONDS),"A live received carbon must replace the mobile header status with typing");
            Message paused=new Message(desktop.connection().getUser(),Message.Type.chat);paused.addExtension(new ChatStateExtension(ChatState.paused));bob.connection().sendStanza(paused);
            assertEquals(Boolean.FALSE,mobileTyping.poll(8,TimeUnit.SECONDS),"Paused carbon restores the normal status");
            Message foreign=new Message(org.jxmpp.jid.impl.JidCreate.entityFullFrom("someoneelse@localhost/device"),Message.Type.chat);
            foreign.setFrom(bob.connection().getUser());foreign.addExtension(new ChatStateExtension(ChatState.composing));
            mobile.chatStates.receivedCarbon(foreign);
            assertNull(mobileTyping.poll(300,TimeUnit.MILLISECONDS),"Relaxing resource routing must not accept a foreign account recipient");
        }
    }
    @Test void profilePostsPersistAndPublicFieldsAreSavedThroughFreshVcards() throws Exception {
        initialize();XmppSession.Listener ignore=(from,id,body,encrypted)->{};
        try(XmppSession alice=session("presentationalice",1711,ignore);XmppSession bob=session("presentationbob",1712,ignore)) {
            alice.connect();bob.connect();String owner="presentationalice@localhost";
            XmppStories.Story old=new XmppStories.Story("saved-profile-test",owner,"https://example.org/profile.jpg","image/jpeg","Saved post",System.currentTimeMillis()-2*XmppStories.LIFETIME);
            XmppStories.saveToProfile(alice.connection(),old);
            assertEquals(1,XmppStories.fetchProfile(alice.connection(),owner).size());
            assertTrue(XmppStories.fetch(alice.connection(),owner).isEmpty(),"Saving a post must not create a new active story");
            assertTrue(XmppStories.fetchProfile(bob.connection(),owner).isEmpty(),"Saving must retain subscribed-contact access");
            XmppStories.removeFromProfile(alice.connection(),old.id);assertTrue(XmppStories.fetchProfile(alice.connection(),owner).isEmpty());
            XmppProfile first=alice.profiles.editField("ORG/ORGNAME","Example org",null);
            alice.profiles.editField("URL","https://example.org",first);
            XmppProfile fetched=alice.profiles.load(owner);
            assertEquals("Example org",fetched.field("ORG","ORGNAME"));assertEquals("https://example.org",fetched.field("URL"));
        }
    }
}
