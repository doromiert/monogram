package com.negzero.monogram.xmpp;

import java.io.File;
import java.util.concurrent.*;
import org.jivesoftware.smack.XMPPException.XMPPErrorException;
import org.jivesoftware.smackx.pubsub.PubSubManager;
import org.jivesoftware.smackx.bookmarks.BookmarkManager;
import org.jxmpp.jid.impl.JidCreate;
import org.jxmpp.jid.parts.Resourcepart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MONOGRAM_IT",matches="1")
class XmppBookmarksIntegrationTest {
    final File root=new File(System.getenv("MONOGRAM_IT_STATE"));
    XmppSession session(String user,int device,BlockingQueue<Boolean> changes) throws Exception {
        return new XmppSession(new Account(user+"@localhost","127.0.0.1",Integer.parseInt(System.getenv("MONOGRAM_IT_PORT")),"Bookmarks-"+device),"monogram-test",device,new File(root,"bookmark-trust-"+device),new XmppSession.Listener() {
            public void message(org.jxmpp.jid.EntityBareJid from,String id,String body,boolean encrypted) {}
            public void bookmarksChanged() { changes.add(true); }
        });
    }
    @Test void privateModernBookmarksPreserveMetadataNotifyOwnDevicesAndImportLegacyRooms() throws Exception {
        XmppSession.initializeOmemo(new File(root,"keys"),com.negzero.monogram.xmpp.LegacyOmemoTestDatabase.shared());
        BlockingQueue<Boolean> changes=new LinkedBlockingQueue<>();
        try(XmppSession alice=session("bookmarkmodern",801,new LinkedBlockingQueue<>()); XmppSession desktop=session("bookmarkmodern",802,changes); XmppSession bob=session("bookmarklegacy",803,new LinkedBlockingQueue<>())) {
            alice.connect(); desktop.connect(); bob.connect();
            assertTrue(alice.bookmarks.load().writable); desktop.bookmarks.load();
            String room="private@conference.localhost";
            XmppBookmarks.Bookmark original=XmppBookmarks.Bookmark.parse(room,"<conference xmlns='urn:xmpp:bookmarks:1' name='Private friends' autojoin='1'><nick>Alice</nick><password>private-room-password</password><extensions><state xmlns='urn:other-client:test' minimized='true'/></extensions></conference>");
            alice.bookmarks.save(original);
            XmppBookmarks.Bookmark received=desktop.bookmarks.load().bookmarks.get(0);
            assertEquals(room,received.jid); assertEquals("Private friends",received.name); assertTrue(received.autojoin);
            assertEquals("private-room-password",received.password);
            desktop.bookmarks.save(received.edit("Alice2",false));
            XmppBookmarks.Bookmark updated=alice.bookmarks.load().bookmarks.get(0);
            assertEquals("Alice2",updated.nickname); assertFalse(updated.autojoin); assertEquals(original.password,updated.password);
            assertTrue(updated.xml().contains("urn:other-client:test")); assertTrue(updated.xml().contains("minimized"));
            assertThrows(XMPPErrorException.class,() -> PubSubManager.getInstanceFor(bob.connection(),JidCreate.entityBareFrom("bookmarkmodern@localhost")).getLeafNode(XmppBookmarks.NS).getItems());
            assertNotNull(changes.poll(5,TimeUnit.SECONDS),"Other own devices must receive bookmark change notifications");
            BookmarkManager.getBookmarkManager(bob.connection()).addBookmarkedConference("An existing room",JidCreate.entityBareFrom("existing@conference.localhost"),true,Resourcepart.from("Bob"),"old-password");
            XmppBookmarks.Snapshot legacy=bob.bookmarks.load(); assertFalse(legacy.writable); assertEquals(1,legacy.bookmarks.size());
            assertEquals("existing@conference.localhost",legacy.bookmarks.get(0).jid); assertEquals("Bob",legacy.bookmarks.get(0).nickname); assertTrue(legacy.bookmarks.get(0).autojoin);
        }
    }
}
