package org.telegram.ui;

import java.util.*;
import org.junit.Test;
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;
import org.jivesoftware.smackx.omemo.trust.OmemoFingerprint;
import org.jxmpp.jid.impl.JidCreate;
import static org.junit.Assert.*;

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(manifest=org.robolectric.annotation.Config.NONE,sdk=36,application=android.app.Application.class,shadows=MonogramTrustPromptTest.Ui.class)
public class MonogramTrustPromptTest {
    @org.robolectric.annotation.Implements(value=org.telegram.messenger.AndroidUtilities.class,isInAndroidSdk=false)
    public static class Ui {
        static long delay;
        @org.robolectric.annotation.Implementation public static void __staticInitializer__(){}
        @org.robolectric.annotation.Implementation public static void cancelRunOnUIThread(Runnable task){}
        @org.robolectric.annotation.Implementation public static void runOnUIThread(Runnable task,long delayMillis){delay=delayMillis;}
    }
    @Test public void ordinaryMessagesCannotDeferAnUrgentMemberReviewOrReactToAnotherRoom() {
        MonogramTrustPrompt prompt=new MonogramTrustPrompt();
        try {
            org.robolectric.util.ReflectionHelpers.setField(prompt,"active",true);
            org.robolectric.util.ReflectionHelpers.setField(prompt,"peer",-7L);
            org.robolectric.util.ReflectionHelpers.setField(prompt,"lastScan",android.os.SystemClock.elapsedRealtime());
            prompt.changed(false,true);assertTrue(Ui.delay<=5000);
            prompt.changed(false);assertTrue(Ui.delay<=5000);
            Ui.delay=123;prompt.devicesChanged(-8L);assertEquals(123,Ui.delay);
            prompt.pause();Ui.delay=123;prompt.changed(true);assertEquals(123,Ui.delay);
        }finally{prompt.close();}
    }

    @Test public void reviewBatchIsBoundedAndNewFingerprintsAreNotHiddenByOlderDismissals()throws Exception {
        Map<OmemoDevice,OmemoFingerprint> keys=new LinkedHashMap<>();Set<String> shown=new HashSet<>();
        OmemoFingerprint first=new OmemoFingerprint(String.join("",Collections.nCopies(64,"a")));
        for(int id=1;id<=20;id++)keys.put(new OmemoDevice(JidCreate.entityBareFrom("bob@example.org"),id),first);
        assertEquals(16,MonogramTrustPrompt.unseen(keys,shown,false).size());
        OmemoDevice device=keys.keySet().iterator().next();shown.add("false:"+device+":"+first);
        assertFalse(MonogramTrustPrompt.unseen(keys,shown,false).containsKey(device));assertTrue(MonogramTrustPrompt.unseen(keys,shown,true).containsKey(device));
        keys.put(device,new OmemoFingerprint(String.join("",Collections.nCopies(64,"b"))));assertTrue(MonogramTrustPrompt.unseen(keys,shown,false).containsKey(device));
    }
}
