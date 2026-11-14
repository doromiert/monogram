package org.telegram.ui;
import static org.junit.Assert.*;
import org.junit.Test;import org.junit.runner.RunWith;import org.robolectric.RobolectricTestRunner;import org.robolectric.annotation.*;
import java.util.*;
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows=MonogramPairingScanTest.Ui.class)
public class MonogramPairingScanTest {
 @Implements(value=org.telegram.messenger.AndroidUtilities.class,isInAndroidSdk=false)
 public static class Ui {
  @Implementation public static void __staticInitializer__(){}
  @Implementation public static void runOnUIThread(Runnable task){task.run();}
  @Implementation public static void runOnUIThread(Runnable task,long delay){task.run();}
 }
 @Test public void cameraCompletesThenPairsExactlyOnceAfterSheetCloses(){
  List<String> joined=new ArrayList<>(),errors=new ArrayList<>();boolean[] completed={false};
  MonogramPairingScan scan=new MonogramPairingScan(joined::add,errors::add);
  assertTrue(scan.processQr("monogram-sync:fixture",()->completed[0]=true));assertTrue(completed[0]);
  scan.didFindQr(" monogram-sync:fixture ");assertTrue(joined.isEmpty());
  scan.onDismiss();scan.onDismiss();assertEquals(Collections.singletonList("monogram-sync:fixture"),joined);assertTrue(errors.isEmpty());
 }
 @Test public void galleryUsesSameCallbackAndCancelDoesNothing(){
  List<String> joined=new ArrayList<>(),errors=new ArrayList<>();
  new MonogramPairingScan(joined::add,errors::add).onDismiss();assertTrue(joined.isEmpty());
  MonogramPairingScan scan=new MonogramPairingScan(joined::add,errors::add);scan.didFindQr("https://example.invalid/");scan.onDismiss();
  assertTrue(joined.isEmpty());assertEquals(1,errors.size());
 }
}
