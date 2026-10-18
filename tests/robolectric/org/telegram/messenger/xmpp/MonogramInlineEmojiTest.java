package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import android.content.Context;
import com.negzero.monogram.xmpp.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.jxmpp.jid.impl.JidCreate;
import org.telegram.tgnet.TLRPC;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramInlineEmojiTest {
    private static final String DATABASE="inline-emoji-fixture.db",OWN="alice@example.org",PEER="bob@example.org",BODY="A 😀 B 😀";
    private Context context;private MonogramStore store;private long self;
    @Before public void setup() { context=RuntimeEnvironment.getApplication();context.deleteDatabase(DATABASE);store=new MonogramStore(context,DATABASE);self=store.peer(OWN); }
    @After public void cleanup() { MonogramEmojis.clear(store.getWritableDatabase(),context,DATABASE);store.close();context.deleteDatabase(DATABASE); }
    private InlineEmoji model() {
        byte[] png=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aDPsAAAAASUVORK5CYII=");
        InlineEmoji.Asset asset=new InlineEmoji.Asset("image/png",png);
        return new InlineEmoji(BODY,Arrays.asList(new InlineEmoji.Range(2,3,asset),new InlineEmoji.Range(6,7,asset)));
    }
    private XmppMessage sent(String stanza,boolean legacy) throws Exception { return XmppMessage.sent(JidCreate.entityFullFrom(OWN+"/phone"),PEER,stanza,BODY,legacy); }
    private ArrayList<TLRPC.TL_messageEntityCustomEmoji> emojis(TLRPC.Message message) {
        ArrayList<TLRPC.TL_messageEntityCustomEmoji> result=new ArrayList<>();for(TLRPC.MessageEntity entity:message.entities) if(entity instanceof TLRPC.TL_messageEntityCustomEmoji) result.add((TLRPC.TL_messageEntityCustomEmoji)entity);return result;
    }
    @Test public void ownLegacyImagesSurviveReopenAndFallbackReplayWithoutPublishingMetadata() throws Exception {
        XmppMessage original=sent("own-legacy",true).withInlineEmoji(model());int id=Math.abs(store.save(original));
        TLRPC.Message projected=store.getMessage(id,self);ArrayList<TLRPC.TL_messageEntityCustomEmoji> entities=emojis(projected);assertEquals(2,entities.size());
        assertEquals(2,entities.get(0).offset);assertEquals(2,entities.get(0).length);assertEquals(7,entities.get(1).offset);
        long document=entities.get(0).document_id;assertEquals(document,entities.get(1).document_id);
        TLRPC.Document asset=MonogramEmojis.document(store.getReadableDatabase(),context,DATABASE,document);assertNotNull(asset);assertTrue(new java.io.File(asset.localPath).isFile());
        assertTrue(MonogramEmojis.isThumb(asset.thumbs.get(0).location.volume_id));
        store.close();store=new MonogramStore(context,DATABASE);assertEquals(document,emojis(store.getMessage(id,self)).get(0).document_id);
        store.save(sent("own-legacy",true));assertEquals("Body-only archive replay must preserve our known local images",2,emojis(store.getMessage(id,self)).size());
        MonogramEmojis.remove(store.getWritableDatabase(),id);assertTrue(emojis(store.getMessage(id,self)).isEmpty());
        String literal="*😀*";InlineEmoji.Asset image=model().ranges.get(0).asset;
        InlineEmoji inline=new InlineEmoji(literal,Collections.singletonList(new InlineEmoji.Range(1,2,image)));
        XmppMessage local=XmppMessage.sent(JidCreate.entityFullFrom(OWN+"/phone"),PEER,"literal-stars",literal,true)
                .withMarkup(MessageMarkup.body(literal).preservingBody()).withInlineEmoji(inline);
        int literalId=Math.abs(store.save(local));TLRPC.Message literalMessage=store.getMessage(literalId,self);
        assertEquals(literal,literalMessage.message);assertEquals(1,emojis(literalMessage).get(0).offset);
    }
    @Test public void unresolvedThenResolvedReplyKeepsEmojiAtTheRenderedOffsets() throws Exception {
        ReplyReference reply=new ReplyReference(PEER,"missing-target").quoted("Quoted 😀");
        String wire=reply.wireBody(BODY);
        XmppMessage incoming=MessageFixture.replyMessage("reply","reply",null,wire,true,false,200000,"phone",false,reply)
                .withMarkup(new MessageMarkup(BODY,Collections.emptyList()).preservingBody()).withInlineEmoji(model());
        int id=store.save(incoming);TLRPC.Message unresolved=store.getMessage(id,self);
        assertEquals(wire,unresolved.message);assertEquals(2,emojis(unresolved).size());
        assertEquals(wire.length()-BODY.length()+2,emojis(unresolved).get(0).offset);
        int target=store.save(MessageFixture.replyMessage("missing-target","missing-target",null,"Quoted 😀",true,false,100000,"phone",false,null));
        TLRPC.Message resolved=store.getMessage(id,self);assertEquals(BODY,resolved.message);assertEquals(target,resolved.reply_to.reply_to_msg_id);
        assertEquals(2,emojis(resolved).size());assertEquals(2,emojis(resolved).get(0).offset);
        store.clear();assertNull(store.customEmoji(emojis(resolved).get(0).document_id));
        try(android.database.Cursor row=store.getReadableDatabase().rawQuery("SELECT count(*) FROM message_formatting",null)) { assertTrue(row.moveToFirst());assertEquals(0,row.getInt(0)); }
    }
    @Test public void metadataOnlyEnrichmentRepairsCacheButCannotChangeAnotherBodyOrTombstone() throws Exception {
        int id=Math.abs(store.save(sent("plain",false)));assertTrue(emojis(store.getMessage(id,self)).isEmpty());
        assertTrue(MonogramEmojis.save(store.getWritableDatabase(),context,DATABASE,id,model()));assertFalse(MonogramEmojis.save(store.getWritableDatabase(),context,DATABASE,id,model()));
        assertFalse(MonogramEmojis.save(store.getWritableDatabase(),context,DATABASE,id,null));assertEquals(2,emojis(store.getMessage(id,self)).size());
        long document=emojis(store.getMessage(id,self)).get(0).document_id;java.io.File file=new java.io.File(MonogramEmojis.document(store.getReadableDatabase(),context,DATABASE,document).localPath);assertTrue(file.delete());
        assertTrue(MonogramEmojis.save(store.getWritableDatabase(),context,DATABASE,id,model()));assertTrue(file.isFile());
        store.getWritableDatabase().execSQL("UPDATE messages SET body='Changed text' WHERE id=?",new Object[]{id});assertFalse(MonogramEmojis.save(store.getWritableDatabase(),context,DATABASE,id,model()));assertTrue(emojis(store.getMessage(id,self)).isEmpty());
        store.getWritableDatabase().execSQL("UPDATE messages SET body=?,deleted=1 WHERE id=?",new Object[]{BODY,id});assertFalse(MonogramEmojis.save(store.getWritableDatabase(),context,DATABASE,id,model()));
    }
}
