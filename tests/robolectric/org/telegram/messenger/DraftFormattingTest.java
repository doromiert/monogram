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
