package com.negzero.monogram.smacksmoke;
import android.app.Instrumentation;import android.content.Context;import android.os.Bundle;
import dalvik.system.DexClassLoader;import java.lang.reflect.*;import java.util.*;import java.util.concurrent.*;
/** Loads the installed production APK in an isolated process; never opens UI, accounts or sockets. */
public final class SmackSmokeInstrumentation extends Instrumentation {
 private Bundle options;public void onCreate(Bundle args){options=args;start();}
 private void inactive(Method method,Object...args)throws Exception {try{method.invoke(null,args);throw new AssertionError("Inactive native handle unexpectedly accepted");}catch(InvocationTargetException error){Throwable cause=error.getCause();if(!(cause instanceof IllegalStateException)||cause.getMessage()==null||!cause.getMessage().toLowerCase(java.util.Locale.ROOT).contains("sync"))throw error;}}
 public void onStart(){Bundle result=new Bundle();ExecutorService pool=null;
  try {
   Context context=getTargetContext();String apk=options!=null&&options.containsKey("apk")?options.getString("apk"):context.getPackageManager().getApplicationInfo("com.negzero.monogram.beta",0).sourceDir;
   android.content.pm.PackageInfo installed=context.getPackageManager().getPackageInfo("com.negzero.monogram.beta",android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
   result.putString("installed_version",installed.versionName);StringBuilder certificate=new StringBuilder();for(byte value:java.security.MessageDigest.getInstance("SHA-256").digest(installed.signingInfo.getApkContentsSigners()[0].toByteArray()))certificate.append(String.format(java.util.Locale.ROOT,"%02x",value&255));result.putString("installed_certificate_sha256",certificate.toString());
   ClassLoader loader=new DexClassLoader(apk,context.getCodeCacheDir().getAbsolutePath(),context.getPackageManager().getApplicationInfo("com.negzero.monogram.beta",0).nativeLibraryDir,context.getClassLoader().getParent());
   ClassLoader sterile=new ClassLoader(null){};pool=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r);t.setContextClassLoader(sterile);return t;});
   boolean minified=options!=null&&options.containsKey("configClass");
   boolean reproduced=!minified&&pool.submit(()->{try{Class.forName("org.jivesoftware.smack.xml.SmackXmlParser",true,loader).getMethod("getXmlPullParserFactory").invoke(null);return false;}catch(InvocationTargetException e){if(!(e.getCause() instanceof IllegalStateException))throw e;return true;}}).get(15,TimeUnit.SECONDS);
   if(!reproduced&&!minified)throw new AssertionError("Baseline did not reproduce missing context-loader provider");
   result.putBoolean("raw_discovery_reproduced",reproduced);
   if(options!=null&&"true".equals(options.getString("baseline"))){finish(-1,result);return;}
   List<Future<Boolean>> jobs=new ArrayList<>();for(int i=0;i<(minified?1:2);i++)jobs.add(pool.submit(()->{
    Class.forName("org.telegram.messenger.xmpp.MonogramSmack",true,loader).getMethod("initialize",Context.class).invoke(null,context);
    Class.forName(minified?options.getString("configClass"):"org.jivesoftware.smack.ConnectionConfiguration",true,loader);
    // Account restore reaches these extension initializers after core Smack startup.
    // R8 once kept class names but stripped the QName fields used through reflection.
    Class<?> event=Class.forName("org.jivesoftware.smackx.pubsub.EventElement",true,loader);
    Object qname=Class.forName("org.jivesoftware.smack.util.XmppElementUtil",true,loader).getMethod("getQNameFor",Class.class).invoke(null,event);
    if(!"{http://jabber.org/protocol/pubsub#event}event".equals(qname.toString()))throw new AssertionError("PubSub event QName missing");
    for(String name:new String[]{"pubsub.filter.EventItemsExtensionFilter","pep.PepManager","pubsub.PubSubManager","bookmarks.BookmarkManager"})Class.forName("org.jivesoftware.smackx."+name,true,loader);
    if(minified)return true; // SmackInitialization itself parses the packaged XML startup configuration.

    Object parser=Class.forName("org.jivesoftware.smack.util.PacketParserUtils",true,loader).getMethod("getParserFor",String.class).invoke(null,"<message xmlns='jabber:client'><body>fixture</body></message>");
    return "message".equals(Class.forName("org.jivesoftware.smack.xml.XmlPullParser",true,loader).getMethod("getName").invoke(parser));
   }));for(Future<Boolean> job:jobs)if(!job.get(20,TimeUnit.SECONDS))throw new AssertionError("Parser content mismatch");
   if(options!=null&&"true".equals(options.getString("revision"))){
    Class<?> nativeMesh=Class.forName("com.negzero.monogram.sync.NativeMesh",true,loader);
    inactive(nativeMesh.getMethod("putJsonAt",long.class,String.class,String.class,String.class,String.class,long.class),0L,"pack-order","fixture@example.invalid","order","{\"stickers\":[],\"emojis\":[]}",1L);
    inactive(nativeMesh.getMethod("appliedVersion",long.class,String.class),0L,"{\"kind\":\"pack-order\",\"owner\":\"fixture@example.invalid\",\"id\":\"order\",\"stamp\":1,\"origin\":\"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa\",\"digest\":\"0000000000000000000000000000000000000000000000000000000000000000\",\"size\":2}");
    result.putString("revision_abi","PASS installed native putJsonAt/appliedVersion dispatch without opening a store");
   }
   result.putString("result",minified?"PASS minified packaged APK Smack core/PEP/PubSub/bookmark startup":"PASS packaged APK sterile-loader concurrent Smack extension startup and XML parse");finish(-1,result);
  }catch(Throwable error){StringBuilder chain=new StringBuilder();for(Throwable e=error;e!=null;e=e.getCause())chain.append(e.toString()).append(" > ");result.putString("failure",chain.toString());finish(0,result);}
  finally{if(pool!=null)pool.shutdownNow();}
 }
}
