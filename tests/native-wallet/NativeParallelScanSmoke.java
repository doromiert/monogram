// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.wallet;
import java.io.File;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
/** Delivery check: concurrently scans only fresh, unfunded disposable wallets. */
public final class NativeParallelScanSmoke {
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
  WalletNative.load();File root=new File(args[0]);if(!root.mkdirs())throw new AssertionError("Fresh fixture directory required");
  char[] password="unfunded parallel scan fixture".toCharArray();long first=0,second=0;
  ExecutorService workers=Executors.newFixedThreadPool(2);
  try(WalletTlsBridge a=new WalletTlsBridge(args[1]);WalletTlsBridge b=new WalletTlsBridge(args[1])){
   first=WalletNative.createWallet(new File(root,"first").getAbsolutePath(),password,0,a.endpoint(),a.user,a.password);
   second=WalletNative.createWallet(new File(root,"second").getAbsolutePath(),password,0,b.endpoint(),b.user,b.password);
   final long one=first,two=second;CountDownLatch start=new CountDownLatch(2);AtomicInteger active=new AtomicInteger(),peak=new AtomicInteger();
   java.util.function.LongFunction<Callable<Boolean>> scan=handle->()->{
    start.countDown();if(!start.await(10,TimeUnit.SECONDS))throw new AssertionError("Scan worker not started");
    int running=active.incrementAndGet();peak.accumulateAndGet(running,Math::max);
    try{for(int slice=0;slice<3;slice++)if(WalletNative.sync(handle,(height,last)->{}))return true;return false;}
    finally{active.decrementAndGet();}
   };
   Future<Boolean> x=workers.submit(scan.apply(one)),y=workers.submit(scan.apply(two));
   if(!x.get(120,TimeUnit.SECONDS) || !y.get(120,TimeUnit.SECONDS))throw new AssertionError("Disposable scans did not finish");
   if(peak.get()!=2)throw new AssertionError("Scans did not overlap");
   if(!"true".equals(WalletNative.snapshot(first)[5]) || !"true".equals(WalletNative.snapshot(second)[5]))throw new AssertionError("Native scan state incomplete");
   System.out.println("PASS: two independent unfunded wallets synchronized concurrently through separate verified TLS bridges");
  }finally{
   if(first!=0)WalletNative.stop(first);if(second!=0)WalletNative.stop(second);
   workers.shutdown();boolean stopped=workers.awaitTermination(40,TimeUnit.SECONDS);
   if(stopped){if(first!=0)WalletNative.close(first);if(second!=0)WalletNative.close(second);}
   java.util.Arrays.fill(password,'\0');
  }
 }
}
