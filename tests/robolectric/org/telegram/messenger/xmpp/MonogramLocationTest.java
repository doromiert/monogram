package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.tgnet.TLRPC;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramLocationTest {
    @Test public void staticVenueUsesNativeQueueReplyRandomIdAndProtectedBodyThenProjectsBack() {
        TLRPC.TL_messages_sendMedia source=new TLRPC.TL_messages_sendMedia();source.peer=new TLRPC.TL_inputPeerChat();source.peer.chat_id=9;source.random_id=1234;
        source.reply_to=new TLRPC.TL_inputReplyToMessage();source.silent=true;source.media=new TLRPC.TL_inputMediaVenue();source.media.title="A place";source.media.address="An address";
        source.media.geo_point=new TLRPC.TL_inputGeoPoint();source.media.geo_point.lat=51.5;source.media.geo_point._long=-.12;source.media.geo_point.flags=1;source.media.geo_point.accuracy_radius=9;
        TLRPC.TL_messages_sendMessage converted=MonogramLocation.text(source);
        assertSame(source.peer,converted.peer);assertSame(source.reply_to,converted.reply_to);assertEquals(source.random_id,converted.random_id);assertTrue(converted.silent);assertTrue(converted.message.startsWith("geo:"));
        TLRPC.Message message=new TLRPC.TL_message();message.media=new TLRPC.TL_messageMediaEmpty();message.message=converted.message;MonogramLocation.decorate(message);
        assertTrue(message.media instanceof TLRPC.TL_messageMediaVenue);assertEquals(51.5,message.media.geo.lat,1e-7);assertEquals("A place",message.media.title);assertEquals(9,message.media.geo.accuracy_radius);
        assertEquals(converted.message,message.message); // Copy/reply excerpts retain canonical coordinates.
        org.telegram.messenger.SendMessagesHelper.SendMessageParams forwarded=MonogramForwarding.copy(message,23,true);
        assertTrue(forwarded.location instanceof TLRPC.TL_messageMediaVenue);assertNotSame(message.media,forwarded.location);assertEquals(51.5,forwarded.location.geo.lat,1e-7);
    }
    @Test public void nativeLocationSuppressesTransportCaptionButKeepsCanonicalBody() throws Exception {
        TLRPC.Message message=new TLRPC.TL_message();message.message="geo:51.5,-0.12\nA place\nAn address";message.media=new TLRPC.TL_messageMediaEmpty();
        MonogramLocation.decorate(message);assertTrue(message.media instanceof TLRPC.TL_messageMediaVenue);
        Class<?> allocator=Class.forName("sun.misc.Unsafe");java.lang.reflect.Field field=allocator.getDeclaredField("theUnsafe");field.setAccessible(true);
        org.telegram.messenger.MessageObject object=(org.telegram.messenger.MessageObject)allocator.getMethod("allocateInstance",Class.class).invoke(field.get(null),org.telegram.messenger.MessageObject.class);
        object.messageOwner=message;object.type=org.telegram.messenger.MessageObject.TYPE_GEO;object.caption="stale transport text";object.generateCaption();
        assertNull(object.caption);assertTrue(message.message.startsWith("geo:"));
    }
    @Test public void liveAndInvalidPointsCannotTurnIntoSuccessfulStaticShares() {
        TLRPC.TL_messages_sendMedia live=new TLRPC.TL_messages_sendMedia();live.media=new TLRPC.TL_inputMediaGeoLive();assertFalse(MonogramLocation.supports(live));
        try { MonogramLocation.text(live);fail(); } catch(IllegalArgumentException expected) { }
        TLRPC.TL_messages_sendMedia invalid=new TLRPC.TL_messages_sendMedia();invalid.media=new TLRPC.TL_inputMediaGeoPoint();invalid.media.geo_point=new TLRPC.TL_inputGeoPoint();invalid.media.geo_point.lat=999;
        try { MonogramLocation.text(invalid);fail(); } catch(IllegalArgumentException expected) { }
        TLRPC.Message text=new TLRPC.TL_message();text.message="hello geo:1,2";text.media=new TLRPC.TL_messageMediaEmpty();MonogramLocation.decorate(text);assertTrue(text.media instanceof TLRPC.TL_messageMediaEmpty);
    }
}
