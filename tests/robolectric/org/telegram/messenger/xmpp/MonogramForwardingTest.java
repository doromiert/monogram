package org.telegram.messenger.xmpp;

import java.util.HashMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.messenger.SendMessagesHelper.SendMessageParams;
import org.telegram.tgnet.TLRPC;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,
        shadows={MonogramMessageRegressionTest.Device.class})
public class MonogramForwardingTest {
    @Test public void textGetsNewDestinationWithoutSourceReplyOrEncryptionPolicy() {
        TLRPC.Message original=new TLRPC.TL_message();
        original.message="hello";original.dialog_id=42;
        original.reply_to=new TLRPC.TL_messageReplyHeader();original.reply_to.reply_to_msg_id=7;
        original.params=new HashMap<>();original.params.put("monogram-encryption","PLAIN");
        TLRPC.TL_messageEntityBold bold=new TLRPC.TL_messageEntityBold();bold.length=5;original.entities.add(bold);
        SendMessageParams send=MonogramForwarding.copy(original,-99,true);
        assertEquals(-99,send.peer);assertEquals("hello",send.message);
        assertEquals(1,send.entities.size());assertNotSame(original.entities,send.entities);
        assertNull(send.replyToMsg);assertNull(send.retryMessageObject);
        assertTrue(send.params.isEmpty());assertEquals(42,original.dialog_id);
    }
    @Test public void mediaCopiesAttributesAndOnlyCarriesPrivateDownloadReference() {
        TLRPC.Message original=new TLRPC.TL_message();original.id=12;original.message="caption";
        original.media=new TLRPC.TL_messageMediaDocument();
        TLRPC.TL_document document=new TLRPC.TL_document();document.id=33;document.mime_type="video/mp4";
        document.file_reference=new byte[0];document.size=100;
        TLRPC.TL_documentAttributeVideo video=new TLRPC.TL_documentAttributeVideo();video.round_message=true;
        document.attributes.add(video);original.media.document=document;
        original.params=new HashMap<>();original.params.put(MonogramAttachments.PARAM,"https://example.org/private-file");
        original.params.put("monogram-encryption","PLAIN");
        SendMessageParams send=MonogramForwarding.copy(original,77,true);
        assertEquals("",send.caption);assertEquals(77,send.peer);assertNotSame(document,send.document);
        assertTrue(send.document.attributes.get(0).round_message);
        assertEquals("12",send.params.get(MonogramForwarding.SOURCE));
        assertEquals(original.params.get(MonogramAttachments.PARAM),send.params.get(MonogramForwarding.ADDRESS));
        assertFalse(send.params.containsKey(MonogramAttachments.PARAM));assertFalse(send.params.containsKey("monogram-encryption"));
        assertNull(send.replyToMsg);assertEquals("caption",original.message);
        assertEquals("caption",MonogramForwarding.copy(original,77,false).caption);
    }
    @Test public void oldFailedForwardKeepsItsLocalIdentityAndAcquiresDownloadSourceOnRetry() {
        TLRPC.Message pending=new TLRPC.TL_message();pending.id=-17;pending.random_id=123;
        pending.message="caption";pending.fwd_from=new TLRPC.TL_messageFwdHeader();pending.flags|=TLRPC.MESSAGE_FLAG_FWD;
        pending.params=new HashMap<>();pending.params.put("fwd_id","12");pending.params.put("fwd_peer","42");
        pending.params.put("monogram-encryption","OMEMO");
        TLRPC.Message source=new TLRPC.TL_message();source.id=12;source.params=new HashMap<>();
        source.params.put(MonogramAttachments.PARAM,"https://example.org/private-file");
        MonogramForwarding.retry(pending,source);
        assertEquals(-17,pending.id);assertEquals(123,pending.random_id);assertEquals("caption",pending.message);
        assertNull(pending.fwd_from);assertEquals(0,pending.flags&TLRPC.MESSAGE_FLAG_FWD);
        assertFalse(pending.params.containsKey("fwd_id"));assertFalse(pending.params.containsKey("fwd_peer"));
        assertEquals("12",pending.params.get(MonogramForwarding.SOURCE));assertEquals("OMEMO",pending.params.get("monogram-encryption"));
    }
}
