package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingResult;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BillingController;
import org.telegram.messenger.BuildVars;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,
        shadows={MonogramBillingDisabledTest.App.class,MonogramBillingDisabledTest.Build.class})
public class MonogramBillingDisabledTest {
    @Implements(value=ApplicationLoader.class,isInAndroidSdk=false)
    public static class App { @Implementation public static void __staticInitializer__() {} }
    @Implements(value=BuildVars.class,isInAndroidSdk=false)
    public static class Build { @Implementation public static void __staticInitializer__() {} }
    @Before public void setup() {
        ApplicationLoader.applicationContext=RuntimeEnvironment.getApplication();BuildVars.IS_BILLING_UNAVAILABLE=true;
    }
    @Test public void disabledBillingNeverConstructsClientAndReturnsExplicitUnavailableResults() {
        BillingController billing=BillingController.getInstance();
        assertNull(ReflectionHelpers.getField(billing,"billingClient"));assertFalse(billing.isReady());
        AtomicInteger delivered=new AtomicInteger();
        billing.queryProductDetails(Collections.emptyList(),(result,items)->{
            assertEquals(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,result.getResponseCode());assertTrue(items.isEmpty());delivered.incrementAndGet();
        });
        billing.queryPurchases(BillingClient.ProductType.INAPP,(result,items)->{
            assertEquals(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,result.getResponseCode());assertTrue(items.isEmpty());delivered.incrementAndGet();
        });
        billing.addResultListener("unused",result->{assertEquals(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,result.getResponseCode());delivered.incrementAndGet();});
        assertEquals(3,delivered.get());assertTrue(((Map<?,?>)ReflectionHelpers.getField(billing,"resultListeners")).isEmpty());
    }
    @Test public void lateCallbacksDoNotStartPurchasesReconnectsOrSubscriptionNavigation() {
        BillingController billing=BillingController.getInstance();AtomicInteger finished=new AtomicInteger();
        BillingResult ok=BillingResult.newBuilder().setResponseCode(BillingClient.BillingResponseCode.OK).build();
        billing.onPurchasesUpdatedInternal(ok,null,finished::incrementAndGet);
        billing.consumeGiftPurchase(null,null,finished::incrementAndGet);
        billing.onBillingSetupFinished(ok);billing.onBillingServiceDisconnected();billing.startConnection();
        billing.whenSetuped(()->fail("Disabled billing must not announce setup success"));
        assertEquals(2,finished.get());assertTrue(((java.util.List<?>)ReflectionHelpers.getField(billing,"setupListeners")).isEmpty());
        assertFalse(billing.startManageSubscription(RuntimeEnvironment.getApplication(),"unused"));
        assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity());
        assertNull(ReflectionHelpers.getField(billing,"billingClient"));assertFalse(billing.isReady());
    }
}
