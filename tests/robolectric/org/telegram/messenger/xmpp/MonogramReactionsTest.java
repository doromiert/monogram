package org.telegram.messenger.xmpp;

import android.content.Context;
import com.negzero.monogram.xmpp.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.tgnet.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramReactionsTest {
    Context context; MonogramStore store; long self,peer;
    @Before public void setup() {
        context=RuntimeEnvironment.getApplication(); ApplicationLoader.applicationContext=context;
        ApplicationLoader.applicationLoaderInstance=new ApplicationLoader(); context.deleteDatabase("reactions.db");
        store=new MonogramStore(context,"reactions.db"); self=store.peer("alice@example.org"); peer=store.peer("bob@example.org");
    }
    @After public void close() { store.close(); context.deleteDatabase("reactions.db"); }
    void react(String wire,String target,long time,boolean outgoing,boolean modern,String... emojis) throws Exception {
        store.reactions.save(MessageFixture.reaction(wire,target,Arrays.asList(emojis),outgoing,modern,time),self);
    }
    int text(String id,boolean outgoing) throws Exception {
        return store.save(MessageFixture.replyMessage(id,id,null,"A visible message",true,outgoing,100000,"phone",false,null));
    }
    Map<String,Integer> counts(int id) {
        Map<String,Integer> result=new LinkedHashMap<>();
        for(TLRPC.ReactionCount count:store.getMessage(id,self).reactions.results) result.put(((TLRPC.TL_reactionEmoji)count.reaction).emoticon,count.count);
        return result;
    }
    @Test public void replacesEachSenderSetIgnoresOlderCopiesAndRollsBackRejectedOwnUpdate() throws Exception {
        int original=text("original",false);
        react("their-new","original",300000,false,false,"👍","👍","🐢");
        react("their-old","original",200000,false,false,"👎");
        react("mine","original",400000,true,false,"👍");
        assertEquals(Integer.valueOf(2),counts(original).get("👍")); assertEquals(Integer.valueOf(1),counts(original).get("🐢")); assertFalse(counts(original).containsKey("👎"));
        react("their-empty","original",500000,false,false);
        assertEquals(Collections.singletonMap("👍",1),counts(original));
        react("mine-rejected","original",600000,true,false,"🔥");
        assertTrue(store.reactions.reject("mallory@example.org","mine-rejected",self).isEmpty()); assertTrue(counts(original).containsKey("🔥"));
        assertEquals(Collections.singleton(original),store.reactions.reject("bob@example.org/phone","mine-rejected",self));
        assertEquals(Collections.singletonMap("👍",1),counts(original));
        assertEquals(1,store.history(peer,self,0,100).size());
        store.close(); store=new MonogramStore(context,"reactions.db"); assertEquals(Collections.singletonMap("👍",1),counts(original));
        TLRPC.Message message=store.getMessage(original,self); SerializedData bytes=new SerializedData(); message.serializeToStream(bytes);
        SerializedData input=new SerializedData(bytes.toByteArray()); TLRPC.Message restored=TLRPC.Message.TLdeserialize(input,input.readInt32(true),true); bytes.cleanup(); input.cleanup();
        assertTrue(restored.reactions.results.get(0).chosen); assertEquals(1,restored.reactions.results.get(0).count);
    }
    @Test public void resolvesLateAndCorrectedTargetsRejectsAmbiguityAndHidesRecoveredControls() throws Exception {
        react("early","original",200000,false,false,"👍"); assertTrue(store.history(peer,self,0,100).isEmpty());
        int original=text("original",false); assertEquals(Collections.singleton(original),store.reactions.reconcile(peer)); assertTrue(counts(original).containsKey("👍"));
        int edit=store.save(MessageFixture.correction("edit","edit",null,"Edited",true,false,250000,"phone",false,null,"original")); store.reconcileCorrections(peer);
        react("corrected-target","edit",300000,false,false,"❤️"); assertEquals(Collections.singletonMap("❤️",1),counts(original)); assertTrue(store.hiddenCorrection(edit));
        text("original",true); store.reactions.reconcile(peer);
        assertFalse(counts(original).containsKey("👍"));
        int protectedOriginal=store.save(MessageFixture.modern("protected",null,"Secret text",false,100000,null,null,null));
        int placeholder=store.save(MessageFixture.modern("cipher-control",null,null,false,350000,null,null,null));
        react("cipher-control","protected",350000,false,true,"🔥");
        assertTrue(store.hiddenCorrection(placeholder)); assertTrue(store.reactions.hidden(peer).contains(placeholder));
        assertEquals(0,store.save(MessageFixture.modern("cipher-control",null,null,false,350000,null,null,null)));
        react("forged-plain-replacement","protected",450000,false,false,"👎"); assertEquals(Collections.singletonMap("🔥",1),counts(protectedOriginal));
        assertFalse(store.history(peer,self,0,100).stream().anyMatch(m -> m.id==placeholder));
        assertEquals(0,store.newerMessages(peer,protectedOriginal));
    }
    @Test public void roomTargetsRequireRoomIdsAndStableActorsSurviveRenamesAndNicknameReuse() throws Exception {
        String room="friends@conference.example.org"; long dialog=store.room(room,"Alice");
        int original=store.saveRoom(MessageFixture.roomArchive(room,"Bob","client-original","room-original","mam-original","Hello room",100000,false),self);
        assertEquals("room-original",store.reactions.target(original,dialog,self));
        store.reactions.save(MessageFixture.roomReaction(room,"Bob","r1","room-original","bob-stable",Arrays.asList("👍"),200000),self);
        store.reactions.save(MessageFixture.roomReaction(room,"NewNick","r2","room-original","bob-stable",Arrays.asList("❤","❤️"),300000),self);
        store.reactions.save(MessageFixture.roomReaction(room,"Bob","r3","room-original","different-person",Arrays.asList("❤️"),400000),self);
        assertEquals(Collections.singletonMap("❤️",2),counts(original));
        store.reactions.save(MessageFixture.roomReaction(room,"Bob","unknown","client-original","different-person",Arrays.asList("🔥"),500000),self);
        assertEquals(Collections.singletonMap("❤️",2),counts(original));
        int noRoomId=store.saveRoom(MessageFixture.roomArchive(room,"Bob","not-yet-echoed",null,"archive-only","No stable room ID",150000,false),self);
        try { store.reactions.target(noRoomId,dialog,self); fail("A MAM UID is not a room stanza ID"); } catch(IllegalArgumentException expected) {}
        assertEquals(2,store.history(dialog,self,0,100).size());
        store.reactions.save(MessageFixture.disclosedReaction(room,"CarolPhone","c1","room-original","carol-phone","carol@example.org",Arrays.asList("👍"),600000),self);
        store.reactions.save(MessageFixture.disclosedReaction(room,"CarolLaptop","c2","room-original","carol-laptop","carol@example.org",Arrays.asList("🔥"),700000),self);
        assertFalse(counts(original).containsKey("👍")); assertEquals(Integer.valueOf(1),counts(original).get("🔥"));
    }
}
