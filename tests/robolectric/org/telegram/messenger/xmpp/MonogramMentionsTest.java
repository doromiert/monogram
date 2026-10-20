package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import android.content.Context;
import com.negzero.monogram.xmpp.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.tgnet.TLRPC;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramMentionsTest {
    private Context context;private MonogramStore store;private long self,dialog,bob;
    private static final String ROOM="friends@conference.example.org";
    @Before public void setup() {
        context=RuntimeEnvironment.getApplication();ApplicationLoader.applicationContext=context;context.deleteDatabase("mentions.db");
        store=new MonogramStore(context,"mentions.db");self=store.peer("alice@example.org");dialog=store.room(ROOM,"Alice");
        bob=store.roomUser(dialog,ROOM+"/Bob in room","bob@example.org",self);
    }
    @After public void cleanup() { store.close();context.deleteDatabase("mentions.db"); }
    private TLRPC.TL_messageEntityMentionName entity(long user,int start,int length) {
        TLRPC.TL_messageEntityMentionName entity=new TLRPC.TL_messageEntityMentionName();entity.user_id=user;entity.offset=start;entity.length=length;return entity;
    }
    private TLRPC.Message incoming(String id,String text,boolean encrypted,MessageMarkup markup) throws Exception {
        XmppRoomMessage wire=MessageFixture.roomText(ROOM,"Bob in room",id,"server-"+id,text,"bob@example.org","bob",100000,encrypted,null,null);
        if(markup!=null) wire=wire.withMarkup(markup);
        return store.getMessage(store.saveRoom(wire,self),self);
    }
    @Test public void nativeCompositionUsesRealJidMetadataAndActualNicknameFallback() throws Exception {
        String text="😀 Bob in room hello";
        MessageMarkup markup=MonogramMentions.fromEntities(store,dialog,text,Collections.singleton(entity(bob,3,12)));
        assertEquals(1,markup.mentions.size());assertEquals("bob@example.org",markup.mentions.get(0).address);
        assertEquals(2,markup.mentions.get(0).start);assertEquals(13,markup.mentions.get(0).end);
        assertEquals("Bob in room",store.mentionNames(dialog,self).get(bob));
        store.localName(bob,"Private contact alias");
        store.profile(bob,new XmppProfile("<vCard xmlns='vcard-temp'><FN>Real profile identity</FN></vCard>","Real published nickname",new byte[0]));
        assertEquals("Member",store.roomMentionFallback(bob));
        assertEquals("Bob in room",store.mentionNames(dialog,self).get(bob));
        long anonymous=store.roomUser(dialog,ROOM+"/Hidden",null,self);
        assertTrue(MonogramMentions.fromEntities(store,dialog,"Hidden",Collections.singleton(entity(anonymous,0,6))).mentions.isEmpty());
        store.localName(anonymous,"Private anonymous-contact alias");
        store.profile(anonymous,new XmppProfile("<vCard xmlns='vcard-temp'><FN>Anonymous real profile identity</FN></vCard>","Identifying nickname",new byte[0]));
        assertEquals("Hidden",store.mentionNames(dialog,self).get(anonymous));assertEquals("Hidden",store.roomMentionFallback(anonymous));
    }
    @Test public void encryptedNicknameHighlightUsesNativeMentionFlagAndClickableIdentityAndPersists() throws Exception {
        TLRPC.Message message=incoming("legacy","😀 @Alice: hello",true,null);
        assertTrue(message.mentioned);assertEquals(16,message.flags&16);
        TLRPC.TL_messageEntityMentionName mention=(TLRPC.TL_messageEntityMentionName)message.entities.stream().filter(entity->entity instanceof TLRPC.TL_messageEntityMentionName).findFirst().get();
        assertEquals(self,mention.user_id);assertEquals(4,mention.offset);assertEquals(5,mention.length);
        int id=message.id;store.close();store=new MonogramStore(context,"mentions.db");assertTrue(store.getMessage(id,self).mentioned);
        assertFalse(incoming("substring","malice alice_2",true,null).mentioned);
    }
    @Test public void explicitReferenceCanHighlightAccountEvenIfVisibleLabelDiffers() throws Exception {
        String text="Hi owner";MessageMarkup markup=MessageMarkup.body(text).withMentions(Collections.singleton(new MessageMentions.Mention(3,8,"alice@example.org")));
        TLRPC.Message message=incoming("explicit",text,false,markup);assertTrue(message.mentioned);
        assertTrue(message.entities.stream().anyMatch(entity->entity instanceof TLRPC.TL_messageEntityMentionName && ((TLRPC.TL_messageEntityMentionName)entity).user_id==self));
        MessageMarkup other=MessageMarkup.body("Alice").withMentions(Collections.singleton(new MessageMentions.Mention(0,5,"bob@example.org")));
        assertFalse(incoming("same-label","Alice",false,other).mentioned);
    }
    @Test public void codeAndQuoteNicknamesDoNotCreateFalseHighlights() throws Exception {
        String text="Alice";
        for(MessageMarkup.Style style:Arrays.asList(MessageMarkup.Style.CODE,MessageMarkup.Style.PRE,MessageMarkup.Style.QUOTE)) {
            MessageMarkup markup=new MessageMarkup(text,Collections.singleton(new MessageMarkup.Range(0,5,Collections.singleton(style),null))).preservingBody();
            assertFalse(incoming("literal-"+style,text,false,markup).mentioned);
        }
        TLRPC.Message edited=incoming("edit-original","Alice, hello",true,null);assertTrue(edited.mentioned);
        store.deleteMessages(Collections.singleton(edited.id));assertFalse(store.getMessage(edited.id,self).mentioned);
    }
}
