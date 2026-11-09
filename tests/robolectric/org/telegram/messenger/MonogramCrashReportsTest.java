package org.telegram.messenger;
import static org.junit.Assert.*;
import android.content.ClipboardManager;
import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramCrashReportsTest {
    @Test public void reportOmitsExceptionSecretsAndLimitsHugeStacks() {
        RuntimeException cause=new RuntimeException("wallet seed password https://secret.example/token");
        RuntimeException error=new RuntimeException("PRIVATE_MESSAGE alice@example.org",cause);
        StackTraceElement[] frames=new StackTraceElement[10000];
        java.util.Arrays.fill(frames,new StackTraceElement("Example","method","Example.java",42));error.setStackTrace(frames);
        String report=MonogramCrashReports.report(error);
        assertTrue(report.contains("RuntimeException"));assertTrue(report.contains("Example.java:42"));assertTrue(report.contains("Caused by:"));
        assertFalse(report.contains("PRIVATE_MESSAGE"));assertFalse(report.contains("alice@example.org"));assertFalse(report.contains("wallet seed"));assertFalse(report.contains("https://secret"));
        assertTrue(report.getBytes(StandardCharsets.UTF_8).length<=MonogramCrashReports.MAX_BYTES);
    }
    @Test public void handlerCopiesAndPersistsThenDelegatesWithoutCrashingTheTestProcess() {
        Context context=RuntimeEnvironment.getApplication();AtomicInteger delegated=new AtomicInteger();
        MonogramCrashReports.Handler handler=new MonogramCrashReports.Handler(context,(thread,error)->delegated.incrementAndGet());
        handler.uncaughtException(Thread.currentThread(),new IllegalStateException("SECRET_TO_OMIT"));
        assertEquals(1,delegated.get());
        String saved=MonogramCrashReports.last(context);assertNotNull(saved);assertTrue(saved.contains("IllegalStateException"));assertFalse(saved.contains("SECRET_TO_OMIT"));
        ClipboardManager clipboard=(ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE);
        assertEquals(saved,clipboard.getPrimaryClip().getItemAt(0).getText().toString());
        assertTrue(new File(context.getNoBackupFilesDir(),"monogram-crash-pending").exists());
    }
    @Test public void causeCyclesCannotLoopForever() {
        RuntimeException a=new RuntimeException(),b=new RuntimeException();a.initCause(b);b.initCause(a);
        assertTrue(MonogramCrashReports.report(a).length()<MonogramCrashReports.MAX_BYTES);
    }
}
