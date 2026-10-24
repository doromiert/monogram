package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import android.graphics.Typeface;
import android.text.Spanned;
import android.text.style.StyleSpan;
import androidx.collection.LongSparseArray;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows={MonogramMediaDraftsTest.Ui.class})
public class MonogramActionsTest {
    private MessageObject shell() {
        try {
            Class<?> allocator=Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field field=allocator.getDeclaredField("theUnsafe");field.setAccessible(true);
            return (MessageObject)allocator.getMethod("allocateInstance",Class.class).invoke(field.get(null),MessageObject.class);
        } catch (Exception failure) { throw new AssertionError(failure); }
    }
    private TLRPC.Message message(String body) {
        TLRPC.Message message=new TLRPC.TL_message();message.message=body;
        message.from_id=new TLRPC.TL_peerUser();message.from_id.user_id=42;
        message.params=new HashMap<>();message.params.put("monogram-actual-encryption","2");return message;
    }
    private void update(MessageObject object,String name) {
        TLRPC.TL_user sender=new TLRPC.TL_user();sender.id=42;sender.first_name=name;
        HashMap<Long,TLRPC.User> users=new HashMap<>();users.put(42L,sender);
        ReflectionHelpers.callInstanceMethod(object,"updateMessageText",
            ReflectionHelpers.ClassParameter.from(AbstractMap.class,users),
            ReflectionHelpers.ClassParameter.from(AbstractMap.class,new HashMap<Long,TLRPC.Chat>()),
            ReflectionHelpers.ClassParameter.from(LongSparseArray.class,null),
            ReflectionHelpers.ClassParameter.from(LongSparseArray.class,null));
    }
    @Test public void onlyExactLeadingCommandMatches() {
        assertTrue(MonogramActions.isAction(message("/me waves")));
        assertTrue(MonogramActions.isAction(message("/me ")));
        for(String body:new String[]{"/me","/ME waves"," /me waves","/meshrugs","/me's asleep","hello /me waves","/me\twaves"})
            assertFalse(body,MonogramActions.isAction(message(body)));
    }
    @Test public void incomingOutgoingAndEncryptedModesKeepOriginalBodyAndUseServiceRenderer() {
        for(String mode:new String[]{"0","1","2"}) for(boolean outgoing:new boolean[]{false,true}) {
            TLRPC.Message message=message("/me waves 👋\nthen leaves");message.out=outgoing;message.params.put("monogram-actual-encryption",mode);
            MessageObject object=shell();object.messageOwner=message;
            update(object,"Alex");object.setType();
            assertEquals(MessageObject.TYPE_DATE,object.type);assertEquals(1,object.contentType);
            assertTrue(message.action instanceof TLRPC.TL_messageActionCustomAction);
            assertEquals("* Alex waves 👋\nthen leaves",object.messageText.toString());
            assertEquals("/me waves 👋\nthen leaves",message.message);
            StyleSpan[] spans=((Spanned)object.messageText).getSpans(0,object.messageText.length(),StyleSpan.class);
            assertEquals(1,spans.length);assertEquals(Typeface.ITALIC,spans[0].getStyle());
        }
    }
    @Test public void refreshedSenderNameUpdatesPresentationWithoutChangingWireText() {
        MessageObject object=shell();object.messageOwner=message("/me smiles");
        update(object,"Old nickname");update(object,"New nickname");object.setType();
        assertEquals("* New nickname smiles",object.messageText.toString());assertEquals("/me smiles",object.messageOwner.message);
    }
    @Test public void queuedMessagesUseSameRenderingAndRetainSendingState() {
        TLRPC.Message message=message("/me waves");message.params.clear();message.params.put("monogram-encryption","OMEMO2");
        message.id=-7;message.send_state=MessageObject.MESSAGE_SEND_STATE_SENDING;
        MessageObject object=shell();object.messageOwner=message;update(object,"Alex");object.setType();
        assertEquals(MessageObject.TYPE_DATE,object.type);assertEquals(-7,message.id);assertEquals(MessageObject.MESSAGE_SEND_STATE_SENDING,message.send_state);
    }
    @Test public void captionsForwardedMessagesAndRealSystemEventsAreNotActions() {
        TLRPC.Message caption=message("/me waves");caption.media=new TLRPC.TL_messageMediaDocument();assertFalse(MonogramActions.isAction(caption));
        TLRPC.Message forwarded=message("/me waves");forwarded.fwd_from=new TLRPC.TL_messageFwdHeader();assertFalse(MonogramActions.isAction(forwarded));
        TLRPC.Message event=new TLRPC.TL_messageService();event.message="/me waves";event.params=message("").params;assertFalse(MonogramActions.isAction(event));
        TLRPC.Message ordinary=message("/me waves");ordinary.params.clear();assertFalse(MonogramActions.isAction(ordinary));
    }
}
