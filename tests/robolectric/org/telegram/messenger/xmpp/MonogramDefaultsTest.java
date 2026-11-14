package org.telegram.messenger.xmpp;
import static org.junit.Assert.*;import android.content.SharedPreferences;import org.junit.Test;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.Config;
@RunWith(RobolectricTestRunner.class) @Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramDefaultsTest {
 @Test public void oldSettingsUpgradeOnceAndFutureOptOutPersists(){
  SharedPreferences p=RuntimeEnvironment.getApplication().getSharedPreferences("defaults-test",0);String jid="alice@example.invalid",blind="blind-trust-"+jid+"\nbob@example.invalid";
  p.edit().clear().putString("jid",jid).putBoolean("read-receipts-enabled-"+jid,false).putBoolean("typing-enabled",false).putBoolean(blind,false).commit();MonogramDefaults.apply(p);
  for(String key:new String[]{"typing-enabled","read-receipts-enabled-"+jid,"group-read-receipts-enabled-"+jid,"link-previews-enabled-"+jid,"link-previews-encrypted-"+jid,blind})assertTrue(key,p.getBoolean(key,false));
  p.edit().putBoolean("typing-enabled",false).putBoolean(blind,false).commit();MonogramDefaults.apply(p);assertFalse(p.getBoolean("typing-enabled",true));assertFalse(p.getBoolean(blind,true));
 }
}
