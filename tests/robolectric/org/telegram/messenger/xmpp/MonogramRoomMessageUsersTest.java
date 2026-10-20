package org.telegram.messenger.xmpp;

import com.negzero.monogram.xmpp.MessageFixture;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows={MonogramReactionsTest.Controller.class,MonogramMessageRegressionTest.Device.class})
public class MonogramRoomMessageUsersTest {
    final String room="incremental@conference.example.org";
    MonogramStore store;
    MonogramController controller;
    long self,dialog;
    @Before public void setup() {
        android.content.Context context=RuntimeEnvironment.getApplication();ApplicationLoader.applicationContext=context;
        ApplicationLoader.applicationLoaderInstance=new ApplicationLoader();context.deleteDatabase("room-message-users.db");
        store=new MonogramStore(context,"room-message-users.db");self=store.peer("alice@example.org");dialog=store.room(room,"Alice");
        for(int i=0;i<40;i++) store.roomUser(dialog,room+"/Unrelated"+i,"unrelated"+i+"@example.org",self);
        controller=ReflectionHelpers.callConstructor(MonogramController.class,ReflectionHelpers.ClassParameter.from(int.class,0));
        ReflectionHelpers.setField(controller,"store",store);ReflectionHelpers.setField(controller,"self",self);
    }
    @After public void close() { store.close();RuntimeEnvironment.getApplication().deleteDatabase("room-message-users.db"); }
    TLRPC.Message message(String nick,String id) throws Exception {
        int saved=store.saveRoom(MessageFixture.roomText(room,nick,id,"server-"+id,id,nick.toLowerCase(Locale.ROOT)+"@example.org",nick,100000,false,null,null),self);
        return store.getMessage(saved,self);
    }
    Set<Long> users(ArrayList<TLRPC.Update> updates) {
        ArrayList<TLRPC.User> users=ReflectionHelpers.callInstanceMethod(controller,"roomUpdateUsers",ReflectionHelpers.ClassParameter.from(ArrayList.class,updates));
        Set<Long> ids=new LinkedHashSet<>();for(TLRPC.User user:users) assertTrue("Duplicate user",ids.add(user.id));return ids;
    }
    @Test public void ordinaryIncomingMessageIncludesOnlyOwnUserAndItsAuthorWhileKeepingFullRosterAvailable() throws Exception {
        TLRPC.Message message=message("Bob","ordinary");
        TL_update.TL_updateNewMessage update=new TL_update.TL_updateNewMessage();update.message=message;
        assertEquals(new LinkedHashSet<>(Arrays.asList(self,message.from_id.user_id)),users(new ArrayList<>(Collections.singletonList(update))));
        assertTrue("Participant screens and OMEMO still have the full cached roster",store.roomUsers(dialog,self).size()>40);
    }
    @Test public void editedAndNewMessagesKeepReplyForwardMentionAndReactionAuthorsWithoutUnrelatedUsers() throws Exception {
        TLRPC.Message reply=message("Carol","reply"),message=message("Bob","new");
        message.replyMessage=reply;reply.replyMessage=message; // A repeated embedded object must not expand indefinitely.
        long forward=store.peer("forward@example.org"),mentioned=store.peer("mentioned@example.org"),reactor=store.peer("reactor@example.org");
        message.fwd_from=new TLRPC.TL_messageFwdHeader();message.fwd_from.from_id=new TLRPC.TL_peerUser();message.fwd_from.from_id.user_id=forward;
        TLRPC.TL_messageEntityMentionName mention=new TLRPC.TL_messageEntityMentionName();mention.user_id=mentioned;message.entities.add(mention);
        TLRPC.TL_messageEntityMentionName missing=new TLRPC.TL_messageEntityMentionName();missing.user_id=Long.MAX_VALUE;message.entities.add(missing);
        message.reactions=new TLRPC.TL_messageReactions();TLRPC.TL_messagePeerReaction reaction=new TLRPC.TL_messagePeerReaction();
        reaction.peer_id=new TLRPC.TL_peerUser();reaction.peer_id.user_id=reactor;message.reactions.recent_reactions.add(reaction);
        TL_update.TL_updateNewMessage incoming=new TL_update.TL_updateNewMessage();incoming.message=message;
        TL_update.TL_updateEditMessage edited=new TL_update.TL_updateEditMessage();edited.message=reply;
        TL_update.TL_updateDeleteMessages hidden=new TL_update.TL_updateDeleteMessages();hidden.messages.add(17);
        ArrayList<TLRPC.Update> updates=new ArrayList<>(Arrays.asList(incoming,edited,hidden,new TL_update.TL_updateNewMessage()));
        assertEquals(new HashSet<>(Arrays.asList(self,message.from_id.user_id,reply.from_id.user_id,forward,mentioned,reactor)),users(updates));
    }
}
