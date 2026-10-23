// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.wallet;
import java.io.File;
/** Public disposable fixture seed; never open a real user wallet from this runner. */
public final class NativeConnectSmoke {
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
  WalletNative.load();File directory=new File(args[0]);if(!directory.mkdirs())throw new AssertionError("Use a fresh fixture directory");
  long handle=WalletNative.restore(new File(directory,"wallet").getAbsolutePath(),"public fixture password".toCharArray(),"crim bam scamp gna limi woma wron tuit birth mundane donuts square cohesive dolphin titans narrate fue saved wrap aloof magic mirr toget upda wra".toCharArray(),new char[0],0,0,false);
  try(WalletTlsBridge bridge=new WalletTlsBridge(args[1])){
   Thread monitor=new Thread(()->{try{for(int n=0;n<15;n++){Thread.sleep(2000);System.out.println("PHASE "+bridge.phase+(bridge.lastFailure==null ? "" : " FAILURE "+bridge.lastFailure));}bridge.close();WalletNative.stop(handle);}catch(Exception ignored){}});monitor.setDaemon(true);monitor.start();
   long started=System.nanoTime();WalletNative.connect(handle,bridge.endpoint(),bridge.user,bridge.password);
   System.out.println("PASS native connect seconds="+(System.nanoTime()-started)/1e9+" target="+WalletNative.snapshot(handle)[4]+" phase="+bridge.phase+" failure="+bridge.lastFailure);
   if(Long.parseLong(WalletNative.snapshot(handle)[4])<=0)throw new AssertionError("Connected without a usable daemon height");
  }finally{WalletNative.close(handle);}
 }
}
