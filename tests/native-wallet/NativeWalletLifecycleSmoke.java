// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.wallet;
import java.io.File;import java.util.Arrays;
/** Private delivery fixture: only fresh, unfunded disposable wallets. Never a user path. */
public final class NativeWalletLifecycleSmoke {
 public static void main(String[] args)throws Exception{
  // Standalone ART has no Application binding. Supply the platform's normal default
  // network security config before using Conscrypt; never install a permissive trust manager.
  android.content.pm.ApplicationInfo info=new android.content.pm.ApplicationInfo();info.targetSdkVersion=36;
  Class<?> configClass=Class.forName("android.security.net.config.NetworkSecurityConfig");
  Object builder=configClass.getMethod("getDefaultBuilder",android.content.pm.ApplicationInfo.class).invoke(null,info);
  Object config=builder.getClass().getMethod("build").invoke(builder);
  Class<?> source=Class.forName("android.security.net.config.ConfigSource");
  Object sourceImpl=java.lang.reflect.Proxy.newProxyInstance(source.getClassLoader(),new Class<?>[]{source},(proxy,method,values)->method.getName().equals("getDefaultConfig") ? config : null);
  Class<?> appConfigClass=Class.forName("android.security.net.config.ApplicationConfig");
  Object appConfig=appConfigClass.getConstructor(source).newInstance(sourceImpl);
  appConfigClass.getMethod("setDefaultInstance",appConfigClass).invoke(null,appConfig);
  WalletNative.load();File root=new File(args[0]);if(!root.mkdirs())throw new AssertionError("Use a fresh fixture directory");
  long first=0,second=0,restored=0;char[] seed=null;char[] password="temporary unfunded fixture password".toCharArray();
  try(WalletTlsBridge bridge=new WalletTlsBridge(args[1])){
   first=WalletNative.createWallet(new File(root,"first").getAbsolutePath(),password,0,bridge.endpoint(),bridge.user,bridge.password);
   String[] initial=WalletNative.snapshot(first);if(Long.parseLong(initial[7])<=0)throw new AssertionError("New wallet restore height missing");
   seed=WalletNative.recoveryPhrase(first);if(new String(seed).split(" ").length!=16)throw new AssertionError("Expected 16-word Polyseed");
   boolean refused=false;try{WalletNative.claimSeed(first);}catch(IllegalStateException expected){refused=true;}if(!refused)throw new AssertionError("Claim path exported a main wallet seed");
   second=WalletNative.createWallet(new File(root,"second").getAbsolutePath(),password,0,bridge.endpoint(),bridge.user,bridge.password);
   if(initial[0].equals(WalletNative.snapshot(second)[0]))throw new AssertionError("Independent wallets reused an address");
   restored=WalletNative.restore(new File(root,"restored").getAbsolutePath(),password,seed,new char[0],0,Long.parseLong(initial[7]),true);
   if(!initial[0].equals(WalletNative.snapshot(restored)[0]))throw new AssertionError("Recovery phrase restored a different wallet");
   WalletNative.close(first);first=0;WalletNative.close(second);second=0;WalletNative.close(restored);restored=0;
   first=WalletNative.open(new File(root,"first").getAbsolutePath(),password,0);
   if(!initial[0].equals(WalletNative.snapshot(first)[0]))throw new AssertionError("Reopening changed wallet identity");
   System.out.println("PASS: two independent fresh wallets, saved creation height, local phrase restore, reopen identity, main-seed rejection by claim API");
  }finally{if(seed!=null)Arrays.fill(seed,'\0');Arrays.fill(password,'\0');if(first!=0)WalletNative.close(first);if(second!=0)WalletNative.close(second);if(restored!=0)WalletNative.close(restored);}
 }
}
