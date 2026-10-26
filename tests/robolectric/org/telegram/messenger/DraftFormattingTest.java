package org.telegram.messenger;

import org.junit.Test;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.telegram.tgnet.TLRPC;
import java.util.ArrayList;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 36, application = android.app.Application.class,
        shadows = org.telegram.messenger.xmpp.MonogramMessageRegressionTest.Device.class)
public class DraftFormattingTest {
    @Before public void setup() {
        ApplicationLoader.applicationContext = RuntimeEnvironment.getApplication();
        ApplicationLoader.applicationLoaderInstance = new ApplicationLoader();
    }
    private ArrayList<TLRPC.MessageEntity> bold(int offset, int length) {
        ArrayList<TLRPC.MessageEntity> entities = new ArrayList<>();
        TLRPC.TL_messageEntityBold entity = new TLRPC.TL_messageEntityBold();
        entity.offset = offset;
        entity.length = length;
        entities.add(entity);
        return entities;
    }

    @Test public void formattingOnlyEditsAreDistinctButRecreatedEntitiesAreEqual() {
        assertTrue(MediaDataController.draftEntitiesEqual(null, new ArrayList<>()));
        assertTrue(MediaDataController.draftEntitiesEqual(bold(0, 4), bold(0, 4)));
        assertFalse(MediaDataController.draftEntitiesEqual(bold(0, 4), null));
        assertFalse(MediaDataController.draftEntitiesEqual(bold(0, 4), bold(1, 3)));
        ArrayList<TLRPC.MessageEntity> linked = new ArrayList<>();
        TLRPC.TL_messageEntityTextUrl link = new TLRPC.TL_messageEntityTextUrl();
        link.offset = 0; link.length = 4; link.url = "https://example.org/first";
        linked.add(link);
        ArrayList<TLRPC.MessageEntity> changed = new ArrayList<>();
        TLRPC.TL_messageEntityTextUrl other = new TLRPC.TL_messageEntityTextUrl();
        other.offset = 0; other.length = 4; other.url = "https://example.org/second";
        changed.add(other);
        assertFalse(MediaDataController.draftEntitiesEqual(linked, changed));
    }

    @Test public void formattingInsideUrlEntitiesIsLiteralAndDoesNotShiftLaterStyles() {
        String url="https://x.com/__lyraaa__/status/123";
        ArrayList<TLRPC.MessageEntity> entities=new ArrayList<>();
        TLRPC.TL_messageEntityUrl link=new TLRPC.TL_messageEntityUrl();link.offset=0;link.length=url.length();entities.add(link);
        GenericProvider<Void,TLRPC.MessageEntity> provider=unused->new TLRPC.TL_messageEntityItalic();
        CharSequence result=org.robolectric.util.ReflectionHelpers.callStaticMethod(MediaDataController.class,"parsePattern",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(CharSequence.class,url+" __outside__"),
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(java.util.regex.Pattern.class,java.util.regex.Pattern.compile("__(.+?)__")),
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(ArrayList.class,entities),
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(GenericProvider.class,provider));
        assertEquals(url+" outside",result.toString());assertEquals(url.length(),link.length);
        assertEquals(2,entities.size());assertTrue(entities.get(1) instanceof TLRPC.TL_messageEntityItalic);
        assertEquals(url.length()+1,entities.get(1).offset);assertEquals(7,entities.get(1).length);
    }

    @Test public void movingOrFormattingQuoteDoesNotDiscardDraftChange() {
        TLRPC.TL_inputReplyToMessage a = new TLRPC.TL_inputReplyToMessage();
        TLRPC.TL_inputReplyToMessage b = new TLRPC.TL_inputReplyToMessage();
        a.reply_to_msg_id = b.reply_to_msg_id = 42;
        a.quote_text = b.quote_text = "same";
        assertTrue(MediaDataController.replyToEquals(a, b));
        b.quote_offset = 5;
        assertFalse(MediaDataController.replyToEquals(a, b));
        a.quote_offset = 5;
        a.quote_entities = bold(0, 4);
        assertFalse(MediaDataController.replyToEquals(a, b));
        b.quote_entities = bold(0, 4);
        assertTrue(MediaDataController.replyToEquals(a, b));
    }
}
