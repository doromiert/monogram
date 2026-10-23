package org.telegram.messenger.xmpp;

import android.content.Context;
import com.negzero.monogram.xmpp.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.tgnet.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows=MonogramReactionsTest.Controller.class)
public class MonogramPollsTest {
    Context context;MonogramStore store;long self,peer;
    @Before public void setup() {
        context=RuntimeEnvironment.getApplication();ApplicationLoader.applicationContext=context;ApplicationLoader.applicationLoaderInstance=new ApplicationLoader();
        context.deleteDatabase("polls.db");store=new MonogramStore(context,"polls.db");self=store.peer("alice@example.org");peer=store.peer("bob@example.org");
    }
    @After public void close() { store.close();context.deleteDatabase("polls.db"); }
    void reopen() { store.close();store=new MonogramStore(context,"polls.db"); }
    TLRPC.InputPeer input(long peer) { TLRPC.InputPeer p=peer<0?new TLRPC.TL_inputPeerChat():new TLRPC.TL_inputPeerUser();if(peer<0) p.chat_id=-peer;else p.user_id=peer;return p; }
    String body(boolean multiple) { return new MonogramPolls.Poll("Which *one*? 😀",Arrays.asList("First\nwith detail","Second"),multiple,false).body(); }
    int poll(String wire,boolean multiple,boolean outgoing,boolean encrypted) throws Exception {
        String body=body(multiple);
        return store.save(MessageFixture.replyMessage(wire,wire,null,body,true,outgoing,100000,"phone",encrypted,null)
                .withMarkup(MessageMarkup.body(body).preservingBody()));
    }
    TLRPC.TL_messageMediaPoll media(int id) { return (TLRPC.TL_messageMediaPoll)store.getMessage(id,self).media; }
    void react(String wire,String target,long time,boolean outgoing,String... emojis) throws Exception {
        store.reactions.save(MessageFixture.reaction(wire,target,Arrays.asList(emojis),outgoing,false,time),self);store.reactions.reconcile(peer);
    }
    TLRPC.TL_messages_sendVote vote(int id,long dialog,byte[]... options) { TLRPC.TL_messages_sendVote request=new TLRPC.TL_messages_sendVote();request.peer=input(dialog);request.msg_id=id;request.options.addAll(Arrays.asList(options));return request; }

    @Test public void readableFallbackRoundTripsMultilineTextAndAllOptionEmojisAreUsable() {
        String question="Why?\n\n😀 *literal* >text";
        MonogramPolls.Poll data=new MonogramPolls.Poll(question,Arrays.asList("first\n\n1️⃣ literal","second"),false,false);
        MonogramPolls.Poll parsed=MonogramPolls.parse(data.body());assertNotNull(parsed);assertEquals(question,parsed.question);assertEquals(data.answers,parsed.answers);
        assertNull(MonogramPolls.parse(data.body()+"\nextra"));assertNull(MonogramPolls.parse(data.body().replace("2️⃣ ","1️⃣ ")));
        assertThrows(MonogramPolls.Invalid.class,() -> new MonogramPolls.Poll("Q",Collections.singletonList("one"),false,false));
        for(String emoji:MonogramPolls.EMOJI) assertTrue(emoji,MonogramReactions.isEmoji(emoji));
    }
    @Test public void nativeCreationUsesNormalOutboxFieldsAndRejectsFalseAnonymousPromises() {
        TLRPC.TL_messages_sendMedia source=new TLRPC.TL_messages_sendMedia();source.peer=input(peer);source.random_id=99;source.schedule_date=123;source.silent=true;
        TLRPC.TL_inputMediaPoll media=new TLRPC.TL_inputMediaPoll();source.media=media;media.poll=new TLRPC.TL_poll();media.poll.public_voters=true;media.poll.question.text="Question";
        for(String text:Arrays.asList("Yes","No")) { TLRPC.TL_pollAnswer answer=new TLRPC.TL_pollAnswer();answer.text.text=text;media.poll.answers.add(answer); }
        TLRPC.TL_messages_sendMessage text=MonogramPolls.text(source);assertSame(source.peer,text.peer);assertEquals(99,text.random_id);assertEquals(123,text.schedule_date);assertTrue(text.silent);assertTrue(text.no_webpage);assertNotNull(MonogramPolls.parse(text.message));
        media.poll.public_voters=false;assertThrows(MonogramPolls.Invalid.class,() -> MonogramPolls.text(source));
        media.poll.public_voters=true;media.poll.quiz=true;assertThrows(MonogramPolls.Invalid.class,() -> MonogramPolls.text(source));
    }
    @Test public void historyReplayEarlyVotesChangingAndRetractionPersistWithoutChatBubbles() throws Exception {
        react("early","poll",200000,false,MonogramPolls.EMOJI[0]);int id=poll("poll",false,true,false);store.reactions.reconcile(peer);
        assertEquals(1,media(id).results.total_voters);assertEquals(1,media(id).results.results.get(0).voters);
        react("mine","poll",250000,true,MonogramPolls.EMOJI[1]);assertEquals(2,media(id).results.total_voters);assertTrue(media(id).results.results.get(1).chosen);
        react("change","poll",300000,false,MonogramPolls.EMOJI[1]);assertEquals(0,media(id).results.results.get(0).voters);assertEquals(2,media(id).results.results.get(1).voters);
        react("early","poll",200000,false,MonogramPolls.EMOJI[0]);assertEquals(0,media(id).results.results.get(0).voters);
        react("remove","poll",400000,false);reopen();assertEquals(1,media(id).results.total_voters);assertEquals(1,store.history(peer,self,0,100).size());
        assertEquals("",store.getMessage(id,self).message);assertNotNull(MonogramPolls.parse(MonogramPolls.body(store.getMessage(id,self))));
    }
    @Test public void multipleChoicesCountPeopleAndSerializedNativeResultsKeepChosenState() throws Exception {
        int id=poll("multi",true,true,false);react("mine","multi",200000,true,MonogramPolls.EMOJI[0],MonogramPolls.EMOJI[1]);
        TLRPC.TL_messageMediaPoll original=media(id);assertEquals(1,original.results.total_voters);assertEquals(1,original.results.results.get(0).voters);assertEquals(1,original.results.results.get(1).voters);
        SerializedData bytes=new SerializedData();original.serializeToStream(bytes);SerializedData input=new SerializedData(bytes.toByteArray());
        TLRPC.TL_messageMediaPoll parsed=(TLRPC.TL_messageMediaPoll)TLRPC.MessageMedia.TLdeserialize(input,input.readInt32(true),true);
        assertEquals(1,parsed.results.total_voters);assertEquals(2,parsed.results.results.size());assertTrue(parsed.results.results.get(0).chosen);bytes.cleanup();input.cleanup();
        int single=poll("single",false,true,false);react("invalid","single",250000,false,MonogramPolls.EMOJI[0],MonogramPolls.EMOJI[1]);assertEquals(0,media(single).results.total_voters);
    }
    @Test public void voteConversionPreservesUnrelatedReactionsAndRejectsCrossDialogAndInvalidOptions() throws Exception {
        int id=poll("convert",false,true,false);react("mine","convert",200000,true,"🔥",MonogramPolls.EMOJI[0]);
        TLRPC.Message message=store.getMessage(id,self);byte[] second=media(id).poll.answers.get(1).option;
        TLRPC.TL_messages_sendReaction request=MonogramPolls.vote(vote(id,peer,second),message,self);
        assertEquals(2,request.reaction.size());assertTrue(request.reaction.stream().anyMatch(r -> ((TLRPC.TL_reactionEmoji)r).emoticon.equals("🔥")));
        assertFalse(request.reaction.stream().anyMatch(r -> ((TLRPC.TL_reactionEmoji)r).emoticon.equals(MonogramPolls.EMOJI[0])));
        assertEquals(1,MonogramPolls.vote(vote(id,peer),message,self).reaction.size());
        assertThrows(MonogramPolls.Invalid.class,() -> MonogramPolls.vote(vote(id,peer,new byte[]{120}),message,self));
        assertThrows(MonogramPolls.Invalid.class,() -> MonogramPolls.vote(vote(id,self,second),message,self));
    }
    @Test public void closingFreezesResultsAtAuthorCorrectionAcrossReloadAndLateVotes() throws Exception {
        int id=poll("close",false,true,false);react("before","close",200000,false,MonogramPolls.EMOJI[0]);
        TLRPC.TL_messages_editMessage close=new TLRPC.TL_messages_editMessage();close.peer=input(peer);close.id=id;
        TLRPC.TL_inputMediaPoll input=new TLRPC.TL_inputMediaPoll();input.poll=new TLRPC.TL_poll();input.poll.closed=true;close.media=input;
        MonogramPolls.close(close,store.getMessage(id,self),self);assertNull(close.media);
        store.save(MessageFixture.correction("closed","closed",null,close.message,true,true,300000,"phone",false,null,"close").withMarkup(MessageMarkup.body(close.message).preservingBody()));store.reconcileCorrections(peer);
        assertTrue(media(id).poll.closed);react("after","close",400000,false,MonogramPolls.EMOJI[1]);reopen();
        assertEquals(1,media(id).results.results.get(0).voters);assertEquals(0,media(id).results.results.get(1).voters);
        assertThrows(MonogramPolls.Invalid.class,() -> MonogramPolls.vote(vote(id,peer),store.getMessage(id,self),self));
        assertFalse(MonogramPolls.parse(MonogramPolls.freshBody(store.getMessage(id,self))).closed);
    }
    @Test public void roomVotesUseStableRoomIdsAndActorIdentityAcrossNickChanges() throws Exception {
        String room="polls@conference.example.org";long dialog=store.room(room,"Alice");
        store.reactions.save(MessageFixture.roomReaction(room,"Bob","early","room-poll","stable-bob",Arrays.asList(MonogramPolls.EMOJI[0]),200000),self);
        int id=store.saveRoom(MessageFixture.roomText(room,"Alice","own-poll","room-poll",body(false),"alice@example.org","stable-self",100000,false,null,null).withMarkup(MessageMarkup.body(body(false)).preservingBody()),self);
        store.reactions.reconcile(dialog);assertEquals(1,media(id).results.total_voters);
        store.reactions.save(MessageFixture.roomReaction(room,"NewBob","change","room-poll","stable-bob",Arrays.asList(MonogramPolls.EMOJI[1]),300000),self);
        reopen();assertEquals(1,media(id).results.total_voters);assertEquals(0,media(id).results.results.get(0).voters);assertEquals(1,media(id).results.results.get(1).voters);
    }
    @Test public void legacyBodyMarkersStayLiteralAndVotersAreFilteredAndPaginatedLocally() throws Exception {
        int id=poll("encrypted",true,true,true);assertEquals("Which *one*? 😀",media(id).poll.question.text);
        react("their","encrypted",200000,false,MonogramPolls.EMOJI[0]);react("mine","encrypted",250000,true,MonogramPolls.EMOJI[0],MonogramPolls.EMOJI[1]);
        TLRPC.TL_messages_getPollVotes query=new TLRPC.TL_messages_getPollVotes();query.peer=input(peer);query.id=id;query.limit=1;
        TLRPC.TL_messages_votesList first=MonogramPolls.voters(query,store.getMessage(id,self),store,self);assertEquals(2,first.count);assertEquals(1,first.votes.size());assertEquals("1",first.next_offset);
        query.offset=first.next_offset;assertEquals(1,MonogramPolls.voters(query,store.getMessage(id,self),store,self).votes.size());
        query.offset="";query.option=media(id).poll.answers.get(1).option;assertEquals(1,MonogramPolls.voters(query,store.getMessage(id,self),store,self).count);
    }
}
