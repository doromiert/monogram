package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import android.app.KeyguardManager;
import android.content.Context;
import android.os.PowerManager;
import com.negzero.monogram.sync.NativeMesh;
import com.negzero.monogram.wallet.WalletVault;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.messenger.ApplicationLoader;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,
 shadows={MonogramLanSyncTest.Ui.class,MonogramLanSyncTest.Mesh.class,MonogramLanSyncTest.Vault.class})
public class MonogramLanSyncTest {
 @Implements(value=org.telegram.messenger.AndroidUtilities.class,isInAndroidSdk=false)
 public static class Ui {
  @Implementation public static void __staticInitializer__(){}
  @Implementation public static void runOnUIThread(Runnable task){task.run();}
 }
 @Implements(value=NativeMesh.class,isInAndroidSdk=false)
 public static class Mesh {
  static int starts,pairs,stops;static boolean lockOnStart;
  @Implementation public static void __staticInitializer__(){}
  @Implementation public static long start(String root,byte[] key,String name,String options){starts++;if(lockOnStart)Shadows.shadowOf((KeyguardManager)ApplicationLoader.applicationContext.getSystemService(Context.KEYGUARD_SERVICE)).setIsDeviceLocked(true);return 17;}
  @Implementation public static void stop(long handle){stops++;}
  @Implementation public static String pairing(long handle,String action,long token,String value){if(action.equals("token"))return "0";pairs++;return "monogram-sync:fixture";}
 }
 @Implements(value=WalletVault.class,isInAndroidSdk=false)
 public static class Vault {
  static boolean requireAuth;static int accesses;
  private static char[] key(){return android.util.Base64.encodeToString(new byte[32],android.util.Base64.NO_WRAP).toCharArray();}
  @Implementation public WalletVault.Authorization authorizeRestore()throws Exception {
   accesses++;if(requireAuth)throw new WalletVault.AuthenticationRequired();
   return ReflectionHelpers.callConstructor(WalletVault.Authorization.class,
     ReflectionHelpers.ClassParameter.from(JSONObject.class,new JSONObject().put("fixture",true)),
     ReflectionHelpers.ClassParameter.from(char[].class,key()));
  }
  @Implementation public char[] unlock(JSONObject sealed)throws Exception {accesses++;if(requireAuth)throw new WalletVault.AuthenticationRequired();return key();}
 }
 private Context context;private KeyguardManager guard;private PowerManager power;
 @Before public void setup(){
  context=RuntimeEnvironment.getApplication();ApplicationLoader.applicationContext=context;
  guard=(KeyguardManager)context.getSystemService(Context.KEYGUARD_SERVICE);power=(PowerManager)context.getSystemService(Context.POWER_SERVICE);
  Shadows.shadowOf(guard).setIsDeviceSecure(true);Shadows.shadowOf(guard).setIsDeviceLocked(false);Shadows.shadowOf(power).setIsInteractive(true);
  context.getSharedPreferences("monogram-device-sync",0).edit().clear().putBoolean("enabled",true).commit();
  ReflectionHelpers.setStaticField(MonogramLanSync.class,"handle",0L);ReflectionHelpers.setStaticField(MonogramLanSync.class,"screenLocked",false);
  Mesh.starts=Mesh.pairs=Mesh.stops=Vault.accesses=0;Mesh.lockOnStart=false;Vault.requireAuth=false;
 }
 @After public void cleanup(){MonogramLanSync.stopNow();}
 private String[] invite()throws Exception {
  CountDownLatch done=new CountDownLatch(1);AtomicReference<String[]> result=new AtomicReference<>();
  MonogramLanSync.action("invite","",(value,error)->{result.set(new String[]{value,error});done.countDown();});
  assertTrue("Pairing callback never completed",done.await(3,TimeUnit.SECONDS));return result.get();
 }
 @Test public void staleScreenOffFlagCannotDisablePairingOnAnUnlockedPhone()throws Exception {
  ReflectionHelpers.setStaticField(MonogramLanSync.class,"screenLocked",true);
  String[] result=invite();assertNull(result[1]);assertEquals("monogram-sync:fixture",result[0]);assertTrue(MonogramLanSync.enabled());assertEquals(1,Mesh.starts);assertEquals(1,Mesh.pairs);
 }
 @Test public void lockedPhoneRequestsUnlockWithoutReadingTheVault()throws Exception {
  Shadows.shadowOf(guard).setIsDeviceLocked(true);String[] result=invite();assertNull(result[0]);assertTrue(result[1].contains("Unlock"));assertTrue(MonogramLanSync.needsUnlock());assertTrue(MonogramLanSync.enabled());assertEquals(0,Vault.accesses);assertEquals(0,Mesh.starts);
 }
 @Test public void screenOffPhoneCannotStartEvenBeforeKeyguardLocks()throws Exception {
  Shadows.shadowOf(power).setIsInteractive(false);String[] result=invite();assertTrue(result[1].contains("Unlock"));assertEquals(0,Vault.accesses);assertEquals(0,Mesh.starts);
 }
 @Test public void credentialAuthenticationStillRequiredAfterClearingStaleFlag()throws Exception {
  ReflectionHelpers.setStaticField(MonogramLanSync.class,"screenLocked",true);Vault.requireAuth=true;
  String[] result=invite();assertTrue(result[1].contains("device credential"));assertTrue(MonogramLanSync.needsUnlock());assertEquals(0,Mesh.starts);assertEquals(0,Mesh.pairs);
 }
 @Test public void lockingDuringStartupDiscardsTheNewNativeSession()throws Exception {
  Mesh.lockOnStart=true;String[] result=invite();assertTrue(result[1].contains("Unlock"));assertEquals(1,Mesh.starts);assertEquals(1,Mesh.stops);assertEquals(0,Mesh.pairs);
 }
 @Test public void disabledPreferenceReallyRequiresEnabling()throws Exception {
  context.getSharedPreferences("monogram-device-sync",0).edit().putBoolean("enabled",false).commit();
  String[] result=invite();assertEquals("Enable device sync first.",result[1]);assertFalse(MonogramLanSync.enabled());assertEquals(0,Vault.accesses);
 }
}
