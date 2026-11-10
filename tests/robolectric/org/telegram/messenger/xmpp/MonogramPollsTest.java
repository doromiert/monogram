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
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows={MonogramReactionsTest.Controller.class,MonogramPollsTest.App.class})
public class MonogramPollsTest {
    private static String repeatText(String text,int count){StringBuilder value=new StringBuilder();for(int i=0;i<count;i++)value.append(text);return value.toString();}
    private static String b64(String text){return java.util.Base64.getEncoder().encodeToString(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    @Test public void exportLegacyCodecVectorsForDesktop()throws Exception {
        List<String> cases=new ArrayList<>();List<String> two=Arrays.asList("First\n\nwith detail","Second");
        for(boolean multiple:new boolean[]{false,true})for(boolean closed:new boolean[]{false,true})cases.add(new MonogramPolls.Poll("Which *literal*?\n\n😀", "Description\n\n📝 literal",two,multiple,closed).body());
        List<String> all=new ArrayList<>();for(int i=0;i<32;i++)all.add("Choice "+i);cases.add(new MonogramPolls.Poll("32 options",all,true,false).body());
        cases.add(new MonogramPolls.Poll("\u00a0",Arrays.asList("\u00a0","Spaces"),false,false).body());
        cases.add(new MonogramPolls.Poll(repeatText("😀",2048),Arrays.asList(repeatText("🦊",512),"B"),false,false).body());
        String base=new MonogramPolls.Poll("Question",two,false,false).body();
        for(String invalid:Arrays.asList(base+"\nextra",base.replace("2️⃣ ","1️⃣ "),base.replace("2️⃣ ","2⃣ "),base.replace("  with detail"," with detail"),base.replace("Choose one.","Choose any."),base.replace("Question",repeatText("😀",2049)),base.replace("Second",repeatText("🦊",513)),base.replace("Second","\t"),base.replace("Question","\rQuestion"),base.replace("Question","\u0085Question"),base.replace("Question"," ")))cases.add(invalid);
        List<String> large=new ArrayList<>();for(int i=0;i<32;i++)large.add(repeatText("x",1024));String question=repeatText("q",4096);
        String withoutDescription=new MonogramPolls.Poll(question,large,false,false).body();int descriptionLength=40000-withoutDescription.length()-5;
        assertTrue(descriptionLength>0&&descriptionLength<=4096);String maximum=new MonogramPolls.Poll(question,repeatText("d",descriptionLength),large,false,false).body();assertEquals(40000,maximum.length());cases.add(maximum);cases.add(maximum+"\nVoting closed.");
        org.json.JSONArray vectors=new org.json.JSONArray();int valid=0,invalid=0;
        for(String body:cases){MonogramPolls.Poll parsed=MonogramPolls.parse(body);org.json.JSONObject value=new org.json.JSONObject().put("body",b64(body)).put("valid",parsed!=null);
            if(parsed!=null){valid++;value.put("question",b64(parsed.question)).put("description",b64(parsed.description)).put("multiple",parsed.multiple).put("closed",parsed.closed);org.json.JSONArray options=new org.json.JSONArray();for(int i=0;i<parsed.answers.size();i++)options.put(new org.json.JSONArray().put(b64(parsed.emojis.get(i))).put(b64(parsed.answers.get(i))));value.put("options",options);String closed=new MonogramPolls.Poll(parsed.question,parsed.description,parsed.answers,parsed.multiple,true).body();boolean canClose=MonogramPolls.parse(closed)!=null;value.put("can_close",canClose);if(canClose)value.put("closed_body",b64(closed));}else invalid++;
            vectors.put(value);
        }
        assertTrue(valid>=8);assertTrue(invalid>=10);String output=System.getProperty("monogram.legacy.poll.vectors");if(output!=null)java.nio.file.Files.write(java.nio.file.Paths.get(output),(vectors.toString(2)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    @org.robolectric.annotation.Implements(value=ApplicationLoader.class,isInAndroidSdk=false)
    public static class App { @org.robolectric.annotation.Implementation public static boolean isAndroidTestEnvironment(){return false;} }
    @Test public void structuredOutboxRoundTripPreservesDelimitersAndCustomMapping()throws Exception {
        PollDefinition custom=PollDefinition.fromXml(STRUCTURED.replace("Pick food 😀","Literal || |=| 😀"));
        TLRPC.Message pending=new TLRPC.TL_message();pending.id=-17;pending.out=true;pending.date=100;pending.message="Description only";
        pending.peer_id=new TLRPC.TL_peerUser();pending.peer_id.user_id=peer;pending.from_id=new TLRPC.TL_peerUser();pending.from_id.user_id=self;pending.flags|=256;
        pending.media=new TLRPC.TL_messageMediaPoll();((TLRPC.TL_messageMediaPoll)pending.media).poll=new TLRPC.TL_poll();((TLRPC.TL_messageMediaPoll)pending.media).poll.question.text="Native field must not replace XML";((TLRPC.TL_messageMediaPoll)pending.media).results=new TLRPC.TL_pollResults();pending.flags|=512;
        pending.params=new HashMap<>();MonogramPolls.putXml(pending.params,custom.xml());MonogramPolls.putAccompanying(pending.params,"Note || |=| 😀");MonogramPolls.prepare(pending);
        SerializedData encoded=new SerializedData();pending.serializeToStream(encoded);SerializedData input=new SerializedData(encoded.toByteArray());
        TLRPC.Message restored=TLRPC.Message.TLdeserialize(input,input.readInt32(true),true);restored.readAttachPath(input,self);
        assertTrue(custom.sameDefinition(PollDefinition.fromXml(MonogramPolls.xml(restored.params))));assertEquals("Note || |=| 😀",MonogramPolls.accompanying(restored.params));assertEquals("Tonight",MonogramPolls.retryDescription(restored));encoded.cleanup();input.cleanup();
        restored.params.put(MonogramPolls.XML,null);assertEquals("",MonogramPolls.xml(restored.params));assertNull(MonogramPolls.data(restored));
    }
    @Test public void structuredForwardStartsFreshAndClosureKeepsTheOriginalDefinition()throws Exception {
        int id=store.save(MessageFixture.structuredPoll("structured-forward",STRUCTURED,true,false,100000,null));
        TLRPC.Message original=store.getMessage(id,self);org.telegram.messenger.SendMessagesHelper.SendMessageParams send=MonogramForwarding.copy(original,peer,false);
        PollDefinition fresh=PollDefinition.fromXml(MonogramPolls.xml(send.params));assertFalse(fresh.closed);assertEquals("🍕",fresh.options.get(0).emoji);assertEquals("",send.message);assertNull(send.replyToMsg);
        TLRPC.TL_messages_editMessage request=new TLRPC.TL_messages_editMessage();request.id=id;request.peer=input(peer);TLRPC.TL_inputMediaPoll closing=new TLRPC.TL_inputMediaPoll();closing.poll=new TLRPC.TL_poll();closing.poll.closed=true;request.media=closing;
        PollDefinition closed=MonogramPolls.close(request,original,self);assertTrue(closed.closed);assertTrue(closed.sameDefinition(fresh));assertEquals("",request.message);assertNull(request.media);
        int event=store.save(XmppMessage.sentPoll(org.jxmpp.jid.impl.JidCreate.entityFullFrom("alice@example.org/mobile"),"bob@example.org","closed-typed",closed,"",false,XmppMessage.Encryption.PLAIN,null,new MessageCorrection("structured-forward")));
        store.reconcileCorrections(peer);assertTrue(media(id).poll.closed);
        org.telegram.messenger.SendMessagesHelper.SendMessageParams again=MonogramForwarding.copy(store.getMessage(id,self),peer,false);assertFalse(PollDefinition.fromXml(MonogramPolls.xml(again.params)).closed);assertFalse(PollDefinition.fromXml(MonogramPolls.xml(original.params)).closed);
    }
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

    static final String STRUCTURED="<poll xmlns='urn:xmpp:polls:0' type='single'><question>Pick food 😀</question><description>Tonight</description><option emoji='🍕'>Pizza</option><option emoji='🍜'>Noodles</option></poll>";
    @Test public void upgradeFrom35KeepsHistoryAndAddsStructuredMetadataTable()throws Exception {
        int old=poll("legacy-before-upgrade",false,true,false);
        store.getWritableDatabase().execSQL("DROP TABLE message_polls");store.getWritableDatabase().setVersion(35);reopen();
        assertEquals(36,store.getReadableDatabase().getVersion());assertEquals("Which *one*? 😀",media(old).poll.question.text);
        int id=store.save(MessageFixture.structuredPoll("after-upgrade",STRUCTURED,false,false,200000,null));assertEquals("Pick food 😀",media(id).poll.question.text);
    }
    @Test public void structuredOptionsAndBodylessDefinitionSurviveStorageAndVotes()throws Exception {
        store.reactions.save(MessageFixture.reaction("early","structured",Arrays.asList("🍜"),false,true,100000),self);store.reactions.reconcile(peer);
        int id=store.save(MessageFixture.structuredPoll("structured",STRUCTURED,true,true,200000,null));store.reactions.reconcile(peer);
        assertEquals("Pick food 😀",media(id).poll.question.text);assertEquals("Pizza",media(id).poll.answers.get(0).text.text);assertEquals(1,media(id).results.results.get(1).voters);
        react("downgrade","structured",250000,false,"🍕");assertEquals(1,media(id).results.results.get(1).voters);assertEquals(0,media(id).results.results.get(0).voters);
        TLRPC.Message message=store.getMessage(id,self);assertEquals("Tonight",message.message);assertTrue(MonogramPolls.visibleReactions(message).isEmpty());
        TLRPC.TL_messages_sendVote vote=new TLRPC.TL_messages_sendVote();vote.peer=input(peer);vote.msg_id=id;vote.options.add(new byte[]{'0'});
        assertEquals("🍕",((TLRPC.TL_reactionEmoji)MonogramPolls.vote(vote,message,self).reaction.get(0)).emoticon);
        reopen();assertEquals("Noodles",media(id).poll.answers.get(1).text.text);assertEquals(1,media(id).results.total_voters);
        store.deleteMessages(Collections.singleton(id));assertEquals(0,android.database.DatabaseUtils.longForQuery(store.getReadableDatabase(),"SELECT count(*) FROM message_polls WHERE message=?",new String[]{Integer.toString(id)}));
    }
    @Test public void structuredPollNeverReadsOptionsFromFallbackAndMalformedMetadataDoesNotGuessLegacy()throws Exception {
        String misleading="<body>Completely different text choices.</body>"+STRUCTURED+"<fallback xmlns='urn:xmpp:fallback:0' for='urn:xmpp:polls:0'><body/></fallback>";
        int id=store.save(MessageFixture.structuredPoll("translated",misleading,false,false,200000,null));assertEquals("Pick food 😀",media(id).poll.question.text);assertEquals("Tonight",store.getMessage(id,self).message);
        int invalid=store.save(MessageFixture.structuredPoll("invalid","<body>"+body(false)+"</body>"+STRUCTURED.replace("single","quiz"),false,false,300000,null));assertFalse(store.getMessage(invalid,self).media instanceof TLRPC.TL_messageMediaPoll);
    }
    @Test public void structuredClosureRequiresUnchangedDefinitionAndFreezesFirstAcceptedTime()throws Exception {
        int id=store.save(MessageFixture.structuredPoll("base",STRUCTURED,true,false,100000,null));
        react("before","base",200000,false,"🍕");
        store.save(MessageFixture.structuredPoll("bad-close",STRUCTURED.replace("type='single'","type='single' closed='true'").replace("Pizza","Changed"),true,false,300000,"base"));store.reconcileCorrections(peer);assertFalse(media(id).poll.closed);
        String closed=STRUCTURED.replace("type='single'","type='single' closed='true'");
        store.save(MessageFixture.structuredPoll("close",closed,true,false,400000,"base"));store.reconcileCorrections(peer);assertTrue(media(id).poll.closed);
        react("late","base",500000,false,"🍜");assertEquals(1,media(id).results.results.get(0).voters);assertEquals(0,media(id).results.results.get(1).voters);
        store.save(MessageFixture.structuredPoll("close-again",closed,true,false,600000,"base"));store.reconcileCorrections(peer);assertEquals(1,media(id).results.results.get(0).voters);reopen();assertTrue(media(id).poll.closed);
    }
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
        TLRPC.TL_messages_sendMessage text=MonogramPolls.text(source);assertSame(source.peer,text.peer);assertEquals(99,text.random_id);assertEquals(123,text.schedule_date);assertTrue(text.silent);assertTrue(text.no_webpage);assertEquals("",text.message);assertEquals("Question",MonogramPolls.definition(source).question);
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
    private TLRPC.TL_messages_sendMedia composerRequest(String description) {
        TLRPC.TL_messageMediaPoll nativePoll=new TLRPC.TL_messageMediaPoll();nativePoll.poll=new TLRPC.TL_poll();nativePoll.results=new TLRPC.TL_pollResults();
        nativePoll.poll.question.text="love?";nativePoll.poll.public_voters=true;nativePoll.poll.multiple_choice=true;nativePoll.poll.creator=true;
        for(int i=0;i<2;i++) { TLRPC.TL_pollAnswer answer=new TLRPC.TL_pollAnswer();answer.text.text="love";answer.option=new byte[]{(byte)('0'+i)};nativePoll.poll.answers.add(answer); }
        // Same constructor used by ChatActivity.sendPoll, including its nonempty caption field.
        org.telegram.ui.Components.poll.PollSendParams prepared=new org.telegram.ui.Components.poll.PollSendParams(
                new org.telegram.ui.Components.poll.PollAttachedMediaPack(),nativePoll,91,description,new ArrayList<>(),new ArrayList<>());
        TLRPC.TL_messages_sendMedia request=new TLRPC.TL_messages_sendMedia();request.peer=input(peer);request.random_id=91;
        request.media=prepared.inputMediaPoll;request.message=prepared.caption;request.entities=prepared.entities;return request;
    }
    @Test public void composerDescriptionAndStoredPollRetryBecomeTheSameSendablePoll() {
        TLRPC.TL_messages_sendMedia request=composerRequest("love?");
        TLRPC.TL_messages_sendMessage sent=MonogramPolls.text(request);PollDefinition parsed=MonogramPolls.definition(request);
        assertNotNull(parsed);assertEquals("love?",parsed.question);assertEquals("love?",parsed.description);assertTrue(parsed.multiple);
        assertEquals(Arrays.asList("love","love"),Arrays.asList(parsed.options.get(0).label,parsed.options.get(1).label));assertEquals("",sent.message);
        TLRPC.Message failed=new TLRPC.TL_message();failed.message=request.message;
        failed.media=new TLRPC.TL_messageMediaPoll();((TLRPC.TL_messageMediaPoll)failed.media).poll=((TLRPC.TL_inputMediaPoll)request.media).poll;
        assertEquals("love?",MonogramPolls.retryDescription(failed));
        request.message=MonogramPolls.retryDescription(failed);
        failed.id=-11;failed.out=true;failed.flags=TLRPC.MESSAGE_FLAG_HAS_MEDIA;failed.date=100;
        failed.peer_id=new TLRPC.TL_peerUser();failed.peer_id.user_id=peer;
        ((TLRPC.TL_messageMediaPoll)failed.media).results=new TLRPC.TL_pollResults();
        // The durable outbox stores a native message, not a client request (whose TL reader is intentionally absent).
        SerializedData bytes=new SerializedData();failed.serializeToStream(bytes);SerializedData encoded=new SerializedData(bytes.toByteArray());
        TLRPC.Message restored=TLRPC.Message.TLdeserialize(encoded,encoded.readInt32(true),true);
        TLRPC.TL_messages_sendMedia retry=new TLRPC.TL_messages_sendMedia();retry.peer=input(peer);retry.random_id=91;
        TLRPC.TL_inputMediaPoll retryPoll=new TLRPC.TL_inputMediaPoll();retryPoll.poll=((TLRPC.TL_messageMediaPoll)restored.media).poll;retry.media=retryPoll;
        retry.message=MonogramPolls.retryDescription(restored);
        assertEquals(sent.message,MonogramPolls.text(retry).message);bytes.cleanup();encoded.cleanup();
        assertEquals("",MonogramPolls.definition(composerRequest(null)).description);
        assertEquals("",MonogramPolls.definition(composerRequest("")).description);
    }
    @Test public void descriptionsSurviveReloadCloseForwardingAndNativeSendAcknowledgement() throws Exception {
        String description="A description\n\n📝 with emoji and *literal* text";
        PollDefinition definition=MonogramPolls.definition(composerRequest(description));String wire="";
        int id=store.save(XmppMessage.sentPoll(org.jxmpp.jid.impl.JidCreate.entityFullFrom("alice@example.org/mobile"),"bob@example.org","description",definition,"",true,XmppMessage.Encryption.PLAIN,null,null));
        reopen();TLRPC.Message shown=store.getMessage(id,self);
        assertEquals(description,shown.message);assertEquals("love?",((TLRPC.TL_messageMediaPoll)shown.media).poll.question.text);
        assertEquals(wire,MonogramPolls.body(shown));assertEquals(description,MonogramPolls.retryDescription(shown));
        MonogramPolls.completeSent(shown);assertEquals(description,shown.message);assertEquals(wire,MonogramPolls.body(shown));
        assertEquals(description,MonogramPolls.parse(MonogramPolls.freshBody(shown)).description);
        TLRPC.TL_messages_editMessage close=new TLRPC.TL_messages_editMessage();close.id=id;close.peer=input(peer);
        TLRPC.TL_inputMediaPoll closing=new TLRPC.TL_inputMediaPoll();closing.poll=new TLRPC.TL_poll();closing.poll.closed=true;close.media=closing;
        PollDefinition closed=MonogramPolls.close(close,shown,self);
        assertTrue(closed.closed);assertEquals(description,closed.description);
    }

}
