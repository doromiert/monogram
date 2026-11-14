package com.negzero.monogram.smacksmoke;
import android.app.Instrumentation;import android.content.Context;import android.os.Bundle;
import dalvik.system.DexClassLoader;import java.lang.reflect.*;import java.util.*;import java.util.concurrent.*;
/** Loads the installed production APK in an isolated process; never opens UI, accounts or sockets. */
public final class SmackSmokeInstrumentation extends Instrumentation {
 private Bundle options;public void onCreate(Bundle args){options=args;start();}
 public void onStart(){Bundle result=new Bundle();ExecutorService pool=null;
  try {
   Context context=getTargetContext();String apk=options!=null&&options.containsKey("apk")?options.getString("apk"):context.getPackageManager().getApplicationInfo("com.negzero.monogram.beta",0).sourceDir;
   ClassLoader loader=new DexClassLoader(apk,context.getCodeCacheDir().getAbsolutePath(),null,context.getClassLoader().getParent());
   ClassLoader sterile=new ClassLoader(null){};pool=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r);t.setContextClassLoader(sterile);return t;});
   boolean reproduced=pool.submit(()->{try{Class.forName("org.jivesoftware.smack.xml.SmackXmlParser",true,loader).getMethod("getXmlPullParserFactory").invoke(null);return false;}catch(InvocationTargetException e){if(!(e.getCause() instanceof IllegalStateException))throw e;return true;}}).get(15,TimeUnit.SECONDS);
   if(!reproduced)throw new AssertionError("Baseline did not reproduce missing context-loader provider");
   result.putBoolean("raw_discovery_reproduced",true);
   if(options!=null&&"true".equals(options.getString("baseline"))){finish(-1,result);return;}
   List<Future<Boolean>> jobs=new ArrayList<>();for(int i=0;i<2;i++)jobs.add(pool.submit(()->{
    Class.forName("org.telegram.messenger.xmpp.MonogramSmack",true,loader).getMethod("initialize",Context.class).invoke(null,context);
    Class.forName("org.jivesoftware.smack.ConnectionConfiguration",true,loader);
    Object parser=Class.forName("org.jivesoftware.smack.util.PacketParserUtils",true,loader).getMethod("getParserFor",String.class).invoke(null,"<message xmlns='jabber:client'><body>fixture</body></message>");
    return "message".equals(Class.forName("org.jivesoftware.smack.xml.XmlPullParser",true,loader).getMethod("getName").invoke(parser));
   }));for(Future<Boolean> job:jobs)if(!job.get(20,TimeUnit.SECONDS))throw new AssertionError("Parser content mismatch");
   result.putString("result","PASS packaged APK sterile-loader concurrent Smack startup and XML parse");finish(-1,result);
  }catch(Throwable error){StringBuilder chain=new StringBuilder();for(Throwable e=error;e!=null;e=e.getCause())chain.append(e.getClass().getName()).append(" > ");result.putString("failure",chain.toString());finish(0,result);}
  finally{if(pool!=null)pool.shutdownNow();}
 }
}
