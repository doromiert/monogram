package org.telegram.messenger;

import java.util.HashMap;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.tgnet.TLRPC;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows={
    org.telegram.ui.MonogramNavigationTest.App.class,
    org.telegram.messenger.xmpp.MonogramUploadProgressTest.Loader.class,
    org.telegram.messenger.xmpp.MonogramMessageRegressionTest.Device.class})
public class MonogramVideoPreparationTest {
    private Object[] last;
    private final NotificationCenter.NotificationCenterDelegate listener=(id,account,args)->last=args;
    private static <T> T shell(Class<T> type) {
        try {
            Class<?> allocator=Class.forName("sun.misc.Unsafe");java.lang.reflect.Field field=allocator.getDeclaredField("theUnsafe");field.setAccessible(true);
            return type.cast(allocator.getMethod("allocateInstance",Class.class).invoke(field.get(null),type));
        } catch(Exception failed) { throw new AssertionError(failed); }
    }
    private SendMessagesHelper helper;
    private MessageObject message;
    private HashMap<MessageObject,SendMessagesHelper.VideoConversion> conversions;
    private int sent;
    @Before public void setup(){
        ApplicationLoader.applicationContext=RuntimeEnvironment.getApplication();
        ApplicationLoader.applicationHandler=new android.os.Handler(android.os.Looper.getMainLooper());
        helper=shell(SendMessagesHelper.class);
        ReflectionHelpers.setField(helper,"parentAccountInstance",shell(AccountInstance.class));
        NotificationCenter.getInstance(0).addObserver(listener,NotificationCenter.fileUploadProgressChanged);
        conversions=new HashMap<>();ReflectionHelpers.setField(helper,"monogramVideoConversions",conversions);
        message=shell(MessageObject.class);message.messageOwner=new TLRPC.TL_message();
        message.messageOwner.id=-41;message.messageOwner.attachPath="/private/converting.mp4";
        conversions.put(message,new SendMessagesHelper.VideoConversion(()->sent++));last=null;
    }
    @After public void cleanup(){NotificationCenter.getInstance(0).removeObserver(listener,NotificationCenter.fileUploadProgressChanged);}
    private void chunk(float progress){helper.didReceivedNotification(NotificationCenter.fileNewChunkAvailable,0,message,"/private/converting.mp4",1234L,0L,progress);}
    @Test public void intermediateChunksPublishPreparationBeforeAnyUpload(){
        chunk(.42f);assertEquals(.42f,helper.videoPreparationProgress(-41),.0001f);assertEquals(0,sent);
        assertEquals(message.messageOwner.attachPath,last[0]);assertEquals(420L,last[1]);assertEquals(1000L,last[2]);
        assertEquals(-1f,helper.videoPreparationProgress(-42),0f);
    }
    @Test public void badProgressCannotResetPreparationOrMarkUploadComplete(){
        chunk(.6f);chunk(.2f);chunk(Float.NaN);chunk(Float.POSITIVE_INFINITY);
        assertEquals(.6f,helper.videoPreparationProgress(-41),.0001f);
        chunk(2f);assertTrue(helper.videoPreparationProgress(-41)<1f);assertEquals(0,sent);
    }
    @Test public void removingConversionStopsReportingPreparation(){
        chunk(.5f);conversions.remove(message);assertEquals(-1f,helper.videoPreparationProgress(-41),0f);
    }
}
