package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.tgnet.TLRPC;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramSelfChatTest {
    @Test public void selfHistoryAndSearchResolveToOneAccountInsteadOfGlobalHistory() {
        TLRPC.TL_messages_getHistory history=new TLRPC.TL_messages_getHistory();history.peer=new TLRPC.TL_inputPeerSelf();
        MonogramSelfChat.resolve(history,41);assertEquals(41,history.peer.user_id);assertTrue(history.peer instanceof TLRPC.TL_inputPeerSelf);
        TLRPC.TL_messages_search search=new TLRPC.TL_messages_search();search.peer=new TLRPC.TL_inputPeerSelf();search.from_id=new TLRPC.TL_inputPeerSelf();
        MonogramSelfChat.resolve(search,42);assertEquals(42,search.peer.user_id);assertEquals(42,search.from_id.user_id);
    }
    @Test public void queuedTextAndMediaHaveSelfDestinationAndCanBeReboundForAnotherAccount() {
        TLRPC.TL_messages_sendMessage text=new TLRPC.TL_messages_sendMessage();text.peer=new TLRPC.TL_inputPeerSelf();
        MonogramSelfChat.resolve(text,41);assertEquals(41,text.peer.user_id);
        MonogramSelfChat.resolve(text,42);assertEquals(42,text.peer.user_id);
        TLRPC.TL_messages_sendMedia media=new TLRPC.TL_messages_sendMedia();media.peer=new TLRPC.TL_inputPeerSelf();
        MonogramSelfChat.resolve(media,42);assertEquals(42,media.peer.user_id);
        TLRPC.TL_messages_editMessage edit=new TLRPC.TL_messages_editMessage();edit.peer=new TLRPC.TL_inputPeerSelf();
        MonogramSelfChat.resolve(edit,42);assertEquals(42,edit.peer.user_id);
    }
    @Test public void ownNotesAndMediaReopenAsSelfHistoryWithoutLeakingIntoOtherChats() throws Exception {
        android.content.Context context=org.robolectric.RuntimeEnvironment.getApplication();
        org.telegram.messenger.ApplicationLoader.applicationContext=context;
        String name="saved-messages.db";context.deleteDatabase(name);
        long self;int note,media;
        try(MonogramStore store=new MonogramStore(context,name)) {
            self=store.peer("alice@example.org");
            org.jxmpp.jid.Jid own=org.jxmpp.jid.impl.JidCreate.entityFullFrom("alice@example.org/phone");
            note=store.save(com.negzero.monogram.xmpp.XmppMessage.sent(own,"alice@example.org","note","remember this",true));
            media=store.save(com.negzero.monogram.xmpp.XmppMessage.sentFile(own,"alice@example.org","media",com.negzero.monogram.xmpp.FileLink.parse("https://files.example.org/photo.png"),false));
            assertTrue(store.peers().contains(self));assertEquals(self,store.getMessage(note,self).dialog_id);
        }
        try(MonogramStore reopened=new MonogramStore(context,name)) {
            assertEquals(2,reopened.history(self,self,0,50).size());
            assertTrue(reopened.getMessage(note,self).out);assertEquals("remember this",reopened.getMessage(note,self).message);
            assertTrue(MonogramAttachments.has(reopened.getMessage(media,self)));
            assertEquals(1,reopened.search(self,self,"remember",0,0,50,0,0,0,null,false,false).count);
            long other=reopened.peer("bob@example.org");assertTrue(reopened.history(other,self,0,50).isEmpty());
            reopened.deleteMessages(java.util.Collections.singleton(note));assertEquals(1,reopened.history(self,self,0,50).size());
        } finally { context.deleteDatabase(name); }
    }
    @Test public void ordinaryPeersGlobalSearchAndSignedOutAccountsStayDistinct() {
        TLRPC.InputPeer user=new TLRPC.TL_inputPeerUser();user.user_id=88;
        assertSame(user,MonogramSelfChat.resolve(user,41));assertEquals(88,user.user_id);
        TLRPC.InputPeer global=new TLRPC.TL_inputPeerEmpty();assertSame(global,MonogramSelfChat.resolve(global,41));assertEquals(0,global.user_id);
        assertNull(MonogramSelfChat.resolve((TLRPC.InputPeer)null,41));
        assertThrows(IllegalStateException.class,()->MonogramSelfChat.resolve(new TLRPC.TL_inputPeerSelf(),0));
    }
}
