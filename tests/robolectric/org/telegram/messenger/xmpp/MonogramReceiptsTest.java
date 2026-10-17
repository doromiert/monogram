package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import com.negzero.monogram.xmpp.*;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.StandardExtensionElement;
import org.jivesoftware.smackx.chat_markers.element.ChatMarkersElements.DisplayedExtension;
import org.jivesoftware.smackx.receipts.DeliveryReceipt;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramReceiptsTest {
    private MonogramStore store(String name) {
        ApplicationLoader.applicationLoaderInstance=new ApplicationLoader();
        ApplicationLoader.applicationContext=RuntimeEnvironment.getApplication();
        return new MonogramStore(RuntimeEnvironment.getApplication(),name);
    }
    private XmppMessage sent(String id,long time,String resource) throws Exception {
        return MessageFixture.replyMessage(id,null,null,"Message",true,true,time,resource,false,null);
    }
    @Test public void deliveryIsNotReadAndDisplayedStateSurvivesReopeningAndNativeReadMaxima() throws Exception {
        String file="receipt-persistence.db"; RuntimeEnvironment.getApplication().deleteDatabase(file);
        long self,peer; int id;
        try {
            try(MonogramStore store=store(file)) {
                self=store.peer("alice@example.org"); peer=store.peer("bob@example.org");
                id=store.save(sent("one",100000,"phone"));
                assertTrue(MessageObject.isUnread(store.history(peer,self,0,50).get(0)));
                assertEquals(java.util.Collections.singletonList(id),store.receipt(peer,"one",false));
                TLRPC.Message delivered=store.getMessage(id,self);
                assertEquals("true",delivered.params.get("monogram-delivered"));
                delivered.unread=false; // Native numeric maxima cannot override the peer's displayed state.
                assertTrue(MessageObject.isUnread(delivered)); assertEquals(0,MessageObject.getUnreadFlags(delivered)&1);
                assertTrue(store.receipt(peer,"one",false).isEmpty());
                assertEquals(java.util.Collections.singletonList(id),store.receipt(peer,"one",true));
                assertFalse(MessageObject.isUnread(store.getMessage(id,self)));
            }
            try(MonogramStore reopened=store(file)) {
                assertFalse(MessageObject.isUnread(reopened.history(peer,self,0,50).get(0)));
                assertTrue(reopened.receipt(peer,"one",false).isEmpty());
                assertFalse(MessageObject.isUnread(reopened.getMessage(id,self)));
                reopened.clear();
                try (android.database.Cursor c=reopened.getReadableDatabase().rawQuery("SELECT count(*) FROM message_receipts",null)) {
                    assertTrue(c.moveToFirst()); assertEquals(0,c.getInt(0));
                }
            }
        } finally { RuntimeEnvironment.getApplication().deleteDatabase(file); }
    }
    @Test public void displayedUsesTimelineAndReceiptsCannotCrossPeersDirectionsOrAmbiguousIds() throws Exception {
        String file="receipt-scope.db"; RuntimeEnvironment.getApplication().deleteDatabase(file);
        try(MonogramStore store=store(file)) {
            long self=store.peer("alice@example.org"),peer=store.peer("bob@example.org"),other=store.peer("eve@example.org");
            int newer=store.save(sent("new",200000,"phone")),older=store.save(sent("old",100000,"phone"));
            assertTrue(older>newer);
            assertTrue(store.receipt(other,"old",true).isEmpty());
            assertTrue(store.receipt(peer,"missing",true).isEmpty());
            assertEquals(java.util.Collections.singletonList(older),store.receipt(peer,"old",true));
            assertTrue(MessageObject.isUnread(store.getMessage(newer,self)));
            assertFalse(MessageObject.isUnread(store.getMessage(older,self)));
            int incoming=store.save(MessageFixture.replyMessage("incoming",null,null,"Incoming",true,false,300000,"phone",false,null));
            assertTrue(store.receipt(peer,"incoming",true).isEmpty());
            assertTrue(store.getMessage(incoming,self).unread);
            int a=store.save(sent("reused",400000,"phone")),b=store.save(sent("reused",500000,"laptop"));
            assertNotEquals(a,b); assertTrue(store.receipt(peer,"reused",true).isEmpty());
            assertTrue(MessageObject.isUnread(store.getMessage(a,self))); assertTrue(MessageObject.isUnread(store.getMessage(b,self)));
            assertEquals(java.util.Collections.singletonList(newer),store.receipt(peer,"new",true));
            assertTrue(store.receipt(peer,"old",true).isEmpty());
            int archivedOlder=store.save(sent("arrives-later",150000,"phone"));
            assertFalse(MessageObject.isUnread(store.getMessage(archivedOlder,self)));
            int future=store.save(sent("future",600000,"phone"));
            assertTrue(MessageObject.isUnread(store.getMessage(future,self)));
        } finally { RuntimeEnvironment.getApplication().deleteDatabase(file); }
    }
    @Test public void schemaSixteenMigrationPreservesExistingMessagesWithoutInventingReadReceipts() throws Exception {
        String file="receipt-migration.db"; RuntimeEnvironment.getApplication().deleteDatabase(file); int id; long self;
        try {
            try(MonogramStore store=store(file)) {
                self=store.peer("alice@example.org"); id=store.save(sent("existing",100000,"phone"));
                MonogramStoreDeviceTest.legacyRoomDescription(store.getWritableDatabase()); store.getWritableDatabase().execSQL("DROP TABLE message_receipts"); store.getWritableDatabase().setVersion(16);
            }
            try(MonogramStore reopened=store(file)) {
                assertEquals(20,reopened.getReadableDatabase().getVersion());
                assertEquals("Message",reopened.getMessage(id,self).message); assertTrue(MessageObject.isUnread(reopened.getMessage(id,self)));
            }
        } finally { RuntimeEnvironment.getApplication().deleteDatabase(file); }
    }
    @Test public void receiptParserRejectsWrongRoutingAndKeepsIndependentPiggybackedAcknowledgements() throws Exception {
        org.jxmpp.jid.EntityBareJid own=JidCreate.entityBareFrom("alice@example.org");
        Message message=new Message(own,Message.Type.chat); message.setFrom(JidCreate.entityFullFrom("bob@example.org/phone"));
        message.addExtension(new DeliveryReceipt("one"));
        assertFalse(MessageReceipt.from(message,own).get(0).displayed);
        message.addExtension(new DisplayedExtension("one")); assertTrue(MessageReceipt.from(message,own).get(1).displayed);
        message.setTo(JidCreate.entityBareFrom("eve@example.org")); assertTrue(MessageReceipt.from(message,own).isEmpty()); message.setTo(own);
        message.setFrom(JidCreate.entityFullFrom("alice@example.org/other")); assertTrue(MessageReceipt.from(message,own).isEmpty());
        message.setFrom(JidCreate.entityFullFrom("bob@example.org/phone")); message.setType(Message.Type.groupchat); assertTrue(MessageReceipt.from(message,own).isEmpty());
        message.setType(Message.Type.chat); message.addExtension(StandardExtensionElement.builder("x","http://jabber.org/protocol/muc#user").build()); assertTrue(MessageReceipt.from(message,own).isEmpty());
        Message conflicting=new Message(own,Message.Type.chat); conflicting.setFrom(JidCreate.entityBareFrom("bob@example.org"));
        conflicting.addExtension(new DeliveryReceipt("one")); conflicting.addExtension(new DisplayedExtension("two"));
        java.util.List<MessageReceipt> combined = MessageReceipt.from(conflicting,own);
        assertEquals(2,combined.size()); assertEquals("one",combined.get(0).id); assertFalse(combined.get(0).displayed);
        assertEquals("two",combined.get(1).id); assertTrue(combined.get(1).displayed);
    }
}
