package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import android.os.Handler;
import android.os.Looper;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.FutureTask;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.telegram.messenger.AndroidUtilities;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,
        shadows=MonogramLoginAttemptTest.Ui.class)
public class MonogramLoginAttemptTest {
    @Implements(value=AndroidUtilities.class,isInAndroidSdk=false)
    public static class Ui {
        @Implementation public static void __staticInitializer__() {}
        @Implementation public static void runOnUIThread(Runnable task) {new Handler(Looper.getMainLooper()).post(task);}
        @Implementation public static void runOnUIThread(Runnable task,long delay) {new Handler(Looper.getMainLooper()).postDelayed(task,delay);}
        @Implementation public static void cancelRunOnUIThread(Runnable task) {new Handler(Looper.getMainLooper()).removeCallbacks(task);}
    }
    private final ArrayList<Exception> results=new ArrayList<>();
    @Test public void totalDeadlineCancelsWorkerAndRejectsLateSuccess() {
        MonogramLoginAttempt attempt=new MonogramLoginAttempt(results::add);
        FutureTask<Void> work=new FutureTask<>(()->null);attempt.task(work);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(45));
        assertTrue(work.isCancelled());assertEquals(1,results.size());assertTrue(results.get(0) instanceof SocketTimeoutException);
        assertThrows(CancellationException.class,attempt::check);
        attempt.complete(null);Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(1,results.size());
    }
    @Test public void cancelBeforeWorkerStartsDoesNotAffectRetry() {
        MonogramLoginAttempt first=new MonogramLoginAttempt(results::add);first.cancel();
        FutureTask<Void> work=new FutureTask<>(()->null);first.task(work);assertTrue(work.isCancelled());
        assertThrows(CancellationException.class,first::check);
        MonogramLoginAttempt retry=new MonogramLoginAttempt(results::add);retry.check();retry.complete(null);
        first.complete(null);Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(46));
        assertEquals(2,results.size());assertTrue(results.get(0) instanceof CancellationException);assertNull(results.get(1));
    }
    @Test public void successIsNotCancelledByTheOldDeadline() {
        MonogramLoginAttempt attempt=new MonogramLoginAttempt(results::add);
        FutureTask<Void> work=new FutureTask<>(()->null);attempt.task(work);attempt.complete(null);attempt.cancel();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(46));
        assertFalse(work.isCancelled());assertEquals(1,results.size());assertNull(results.get(0));
    }
}
