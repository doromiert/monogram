package org.telegram.messenger.xmpp;

import android.content.Context;
import android.database.Cursor;
import com.negzero.monogram.xmpp.*;
import java.util.*;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.tgnet.TLRPC;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramFormattingTest {
    Context context;MonogramStore store;long self,peer;
    @Before public void setup() {
        context=RuntimeEnvironment.getApplication();ApplicationLoader.applicationContext=context;ApplicationLoader.applicationLoaderInstance=new ApplicationLoader();
        context.deleteDatabase("formatting.db");store=new MonogramStore(context,"formatting.db");
        self=store.peer("alice@example.org");peer=store.peer("bob@example.org");
    }
    @After public void close() { store.close();context.deleteDatabase("formatting.db"); }
    void reopen() { store.close();store=new MonogramStore(context,"formatting.db"); }
    <T extends TLRPC.MessageEntity> T span(T entity,int start,int length) { entity.offset=start;entity.length=length;return entity; }
    MessageMarkup styled(String text,TLRPC.MessageEntity entity) { return MonogramFormatting.fromEntities(text,Collections.singletonList(entity)).styled(); }
    boolean covers(List<TLRPC.MessageEntity> entities,Class<?> type,int offset) {
        for(TLRPC.MessageEntity entity:entities) if(type.isInstance(entity) && entity.offset<=offset && offset<entity.offset+entity.length) return true;return false;
    }
    int rows() { try(Cursor rows=store.getReadableDatabase().rawQuery("SELECT count(*) FROM message_formatting",null)) { rows.moveToFirst();return rows.getInt(0); } }
    FileShare sharing() throws Exception {
        byte[] hash=new byte[32];hash[0]=1;
        return new FileShare("<file-sharing xmlns='urn:xmpp:sfs:0'><file xmlns='urn:xmpp:file:metadata:0'><name>note.bin</name><media-type>application/octet-stream</media-type><size>3</size><hash xmlns='urn:xmpp:hashes:2' algo='sha-256'>"+Base64.getEncoder().encodeToString(hash)+"</hash></file><sources><url-data xmlns='http://jabber.org/protocol/url-data' target='https://files.example.org/note.bin'/></sources></file-sharing>");
    }

    @Test public void utf16AstralBoundariesAndOverlappingInlineStylesRoundTripWithoutUnsupportedTags() {
        String text="😀 hello world";
        ArrayList<TLRPC.MessageEntity> source=new ArrayList<>(Arrays.asList(span(new TLRPC.TL_messageEntityBold(),3,5),span(new TLRPC.TL_messageEntityItalic(),6,5),
                span(new TLRPC.TL_messageEntityCode(),1,1),span(new TLRPC.TL_messageEntityUnderline(),0,2),span(new TLRPC.TL_messageEntitySpoiler(),0,2)));
        MessageMarkup markup=MonogramFormatting.fromEntities(text,source);ArrayList<TLRPC.MessageEntity> restored=MonogramFormatting.entities(markup);
        for(int i=0;i<text.length();i++) {
            assertEquals(i>=3 && i<8,covers(restored,TLRPC.TL_messageEntityBold.class,i));
            assertEquals(i>=6 && i<11,covers(restored,TLRPC.TL_messageEntityItalic.class,i));
        }
        assertTrue(restored.stream().noneMatch(entity -> entity instanceof TLRPC.TL_messageEntityCode || entity instanceof TLRPC.TL_messageEntityUnderline || entity instanceof TLRPC.TL_messageEntitySpoiler));
        TLRPC.Message nativeMessage=new TLRPC.TL_message();nativeMessage.message=text;
        TLRPC.TL_messageEntityTextUrl link=span(new TLRPC.TL_messageEntityTextUrl(),3,5);link.url="https://example.org";nativeMessage.entities.add(link);
        MonogramFormatting.apply(nativeMessage,markup.styled());
        assertEquals(text,nativeMessage.message);assertTrue(nativeMessage.entities.contains(link));
        assertTrue((nativeMessage.flags&TLRPC.MESSAGE_FLAG_HAS_ENTITIES)!=0);
    }
    @Test public void directReloadCorrectionReplayAndResolvedReplyProjectTheCurrentPayload() throws Exception {
        MessageMarkup original=styled("😀 hello",span(new TLRPC.TL_messageEntityBold(),0,8));
        int id=store.save(MessageFixture.replyMessage("original","original",null,original.body,true,false,100000,"phone",true,null).withMarkup(original));
        assertEquals("😀 hello",store.getMessage(id,self).message);reopen();
        assertTrue(covers(store.getMessage(id,self).entities,TLRPC.TL_messageEntityBold.class,0));
        store.save(MessageFixture.replyMessage("original","original","archived",null,false,false,100000,"phone",true,null));
        assertEquals("😀 hello",store.getMessage(id,self).message);
        MessageMarkup edited=styled("Later",span(new TLRPC.TL_messageEntityStrike(),0,5));
        store.save(MessageFixture.correction("edit","edit",null,edited.body,true,false,200000,"phone",true,null,"original").withMarkup(edited));
        store.reconcileCorrections(peer);assertEquals("Later",store.getMessage(id,self).message);
        assertTrue(covers(store.getMessage(id,self).entities,TLRPC.TL_messageEntityStrike.class,2));
        ReplyReference reply=store.replyTo(id,peer,self,true,null,0).quoted("😀 repeated quote");
        int response=store.save(MessageFixture.replyMessage("response","response",null,reply.wireBody(original.body),true,false,300000,"phone",true,reply).withMarkup(original));
        TLRPC.Message nativeReply=store.getMessage(response,self);
        assertEquals("😀 hello",nativeReply.message);assertEquals(id,nativeReply.reply_to.reply_to_msg_id);
        assertTrue(covers(nativeReply.entities,TLRPC.TL_messageEntityBold.class,0));assertEquals("Later",nativeReply.replyMessage.message);
        assertTrue(covers(nativeReply.replyMessage.entities,TLRPC.TL_messageEntityStrike.class,0));
        reopen();assertEquals("😀 hello",store.getMessage(response,self).message);
    }
    @Test public void attachmentCaptionsKeepMarkupAcrossEditsAndDeletionRemovesStoredFormatting() throws Exception {
        FileShare file=sharing();
        MessageMarkup caption=styled("😀 caption",span(new TLRPC.TL_messageEntityBold(),0,10));
        int id=store.save(XmppMessage.sentFile(JidCreate.entityFullFrom("alice@example.org/phone"),"bob@example.org","file",file,caption.body,null,null).withMarkup(caption));
        TLRPC.Message shown=store.getMessage(id,self);assertEquals("😀 caption",shown.message);
        assertTrue(covers(shown.entities,TLRPC.TL_messageEntityBold.class,0));assertTrue(MonogramAttachments.has(shown));
        reopen();assertEquals("😀 caption",store.getMessage(id,self).message);
        MessageMarkup edited=styled("new caption",span(new TLRPC.TL_messageEntityItalic(),0,11));
        store.save(XmppMessage.sentFile(JidCreate.entityFullFrom("alice@example.org/phone"),"bob@example.org","file-edit",file,edited.body,null,new MessageCorrection("file")).withMarkup(edited));
        store.reconcileCorrections(peer);shown=store.getMessage(id,self);assertEquals("new caption",shown.message);
        assertTrue(covers(shown.entities,TLRPC.TL_messageEntityItalic.class,3));assertEquals(file.link.wireUrl,shown.params.get(MonogramAttachments.PARAM));
        assertTrue(rows()>=2);store.deleteMessages(Collections.singleton(id));assertEquals(0,rows());
    }
    @Test public void roomQuoteAndMonospaceEntitiesSurviveNativeProjectionAndReopen() throws Exception {
        String room="formatting@conference.example.org";long dialog=store.room(room,"Alice");
        String text="quoted\ncode";TLRPC.TL_messageEntityPre pre=span(new TLRPC.TL_messageEntityPre(),7,4);pre.language="java";
        MessageMarkup markup=MonogramFormatting.fromEntities(text,Arrays.asList(span(new TLRPC.TL_messageEntityBlockquote(),0,6),pre)).styled();
        int id=store.saveRoom(MessageFixture.roomText(room,"Bob","room-text","room-server",markup.body,"bob@example.org","bob",100000,false,null,null).withMarkup(markup),self);
        TLRPC.Message nativeMessage=store.getMessage(id,self);assertEquals(text,nativeMessage.message);assertEquals(dialog,nativeMessage.dialog_id);
        assertTrue(covers(nativeMessage.entities,TLRPC.TL_messageEntityBlockquote.class,1));assertTrue(covers(nativeMessage.entities,TLRPC.TL_messageEntityPre.class,8));
        reopen();assertEquals(text,store.getMessage(id,self).message);assertTrue(covers(store.getMessage(id,self).entities,TLRPC.TL_messageEntityPre.class,8));
    }
    @Test public void schema22MigrationPreservesExistingMessagesWithoutInventingFormatting() throws Exception {
        int id=store.save(MessageFixture.replyMessage("old","old",null,"Keep me",true,false,100000,"phone",false,null));
        store.getWritableDatabase().execSQL("DROP TRIGGER clear_message_formatting");store.getWritableDatabase().execSQL("DROP TRIGGER delete_message_formatting");
        store.getWritableDatabase().execSQL("DROP TABLE message_formatting");store.getWritableDatabase().execSQL("DROP TABLE IF EXISTS call_events");store.getWritableDatabase().setVersion(22);reopen();
        assertEquals(26,store.getReadableDatabase().getVersion());assertEquals("Keep me",store.getMessage(id,self).message);assertEquals(0,rows());
    }
    @Test public void searchMatchesDisplayedPhrasesAcrossFormattingAndCurrentCorrectionsAndCaptions() throws Exception {
        MessageMarkup first=styled("hello world",span(new TLRPC.TL_messageEntityBold(),0,5));
        int original=store.save(MessageFixture.replyMessage("phrase","phrase",null,first.body,true,false,100000,"phone",false,null).withMarkup(first));
        assertEquals(original,store.search(self,peer,"hello world",0,0,10,0,0,0,null,false,false).messages.get(0).id);
        MessageMarkup edit=styled("fresh phrase",span(new TLRPC.TL_messageEntityBold(),0,5));
        store.save(MessageFixture.correction("phrase-edit","phrase-edit",null,edit.body,true,false,200000,"phone",false,null,"phrase").withMarkup(edit));
        store.reconcileCorrections(peer);
        assertEquals(0,store.search(self,peer,"hello world",0,0,10,0,0,0,null,false,false).count);
        assertEquals(original,store.search(self,peer,"fresh phrase",0,0,10,0,0,0,null,false,false).messages.get(0).id);
        MessageMarkup caption=styled("caption phrase",span(new TLRPC.TL_messageEntityItalic(),0,7));
        int file=store.save(XmppMessage.sentFile(JidCreate.entityFullFrom("alice@example.org/phone"),"bob@example.org","phrase-file",sharing(),caption.body,null,null).withMarkup(caption));
        assertEquals(file,store.search(self,peer,"caption phrase",0,0,10,0,0,0,null,false,false).messages.get(0).id);
        store.getWritableDatabase().execSQL("UPDATE message_formatting SET markup='corrupt',caption_markup='corrupt' WHERE message=?",new Object[]{file});
        assertEquals(1,store.search(self,peer,caption.body,0,0,10,0,0,0,null,false,false).count);
        store.getWritableDatabase().execSQL("UPDATE message_formatting SET body='mismatched' WHERE message=?",new Object[]{file});
        assertEquals(1,store.search(self,peer,caption.body,0,0,10,0,0,0,null,false,false).count);
    }
}
