// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.wallet;
import java.io.File;import java.util.Arrays;import java.math.BigInteger;
/** Delivery fixture: new unfunded wallets and public node fee samples only. */
public final class NativeFeeHistorySmoke {
 public static void main(String[] args)throws Exception{
  android.content.pm.ApplicationInfo info=new android.content.pm.ApplicationInfo();info.targetSdkVersion=36;
  Class<?> configClass=Class.forName("android.security.net.config.NetworkSecurityConfig");
  Object builder=configClass.getMethod("getDefaultBuilder",android.content.pm.ApplicationInfo.class).invoke(null,info);
  Object config=builder.getClass().getMethod("build").invoke(builder);
  Class<?> source=Class.forName("android.security.net.config.ConfigSource");
  Object sourceImpl=java.lang.reflect.Proxy.newProxyInstance(source.getClassLoader(),new Class<?>[]{source},(proxy,method,values)->method.getName().equals("getDefaultConfig") ? config : null);
  Class<?> appConfigClass=Class.forName("android.security.net.config.ApplicationConfig");
  Object appConfig=appConfigClass.getConstructor(source).newInstance(sourceImpl);
  appConfigClass.getMethod("setDefaultInstance",appConfigClass).invoke(null,appConfig);

  if(!args[0].startsWith("/data/local/tmp/monogram-xmr37-"))throw new AssertionError("Use a disposable fixture path");
  WalletNative.load();File root=new File(args[0]);if(!root.mkdirs())throw new AssertionError("Use a fresh directory");
  long main=0,claim=0;char[] password="unfunded delivery fixture".toCharArray();
  try(WalletTlsBridge bridge=new WalletTlsBridge(args[1])){
   main=WalletNative.createWallet(new File(root,"main").getAbsolutePath(),password,0,bridge.endpoint(),bridge.user,bridge.password);
   String[] snapshot=WalletNative.snapshot(main);String destination=snapshot[0];
   String[] estimates=WalletNative.estimateDepositFees(main,destination);
   BigInteger normal=new BigInteger(estimates[0]),asap=new BigInteger(estimates[1]);
   if(normal.signum()<=0 || asap.compareTo(normal)<0)throw new AssertionError("Invalid native fee estimates");
   claim=WalletNative.createClaim(new File(root,"claim").getAbsolutePath(),password,0,Long.parseLong(snapshot[7]));
   WalletNative.connect(claim,bridge.endpoint(),bridge.user,bridge.password);
   while(!WalletNative.sync(claim,(height,last)->{})){}
   try{WalletNative.prepareClaimSweepPriority(claim,destination,1);throw new AssertionError("Unapproved priority accepted");}
   catch(IllegalStateException expected){if(!expected.getMessage().contains("Invalid deposit priority"))throw expected;}
   try{WalletNative.prepareClaimSweepPriority(claim,destination,3);throw new AssertionError("Unfunded claim produced a payment");}
   catch(IllegalStateException expected){if(!expected.getMessage().contains("No claim funds"))throw expected;}
   System.out.println("PASS native default/high fee estimates: "+normal+" / "+asap+" atomic XMR; invalid priority refused; empty ASAP claim cannot pay");
   WalletNative.close(main);main=0;WalletNative.close(claim);claim=0;
   WalletFeeHistory.Snapshot history=WalletFeeHistory.fetch(args[1],com.negzero.monogram.xmpp.MoneroPayment.Network.MAINNET);
   if(history.points.isEmpty() || history.points.size()>24)throw new AssertionError("Invalid graph sample count");
   long now=System.currentTimeMillis()/1000;
   for(WalletFeeHistory.Point point:history.points)if(point.time>now || point.time<now-86500 || point.fee.signum()<=0 || point.samples<1 || point.samples>3)throw new AssertionError("Invalid historical point");
   System.out.println("PASS live public fee history: "+history.points.size()+" hourly medians in the past 24 hours; no user wallet opened; no transaction broadcast");
  }finally{Arrays.fill(password,'\0');if(main!=0)WalletNative.close(main);if(claim!=0)WalletNative.close(claim);}
 }
}
