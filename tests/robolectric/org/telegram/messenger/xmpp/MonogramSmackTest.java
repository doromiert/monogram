package org.telegram.messenger.xmpp;
import static org.junit.Assert.*;
import java.util.concurrent.*;
import org.junit.Test;import org.junit.runner.RunWith;
import org.robolectric.*;import org.robolectric.annotation.Config;
import org.jivesoftware.smack.xml.*;
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration;
import org.jivesoftware.smack.util.PacketParserUtils;
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramSmackTest {
 @Test public void accountWorkersBootstrapRealParserWithoutContextLoaderServices()throws Exception {
  ClassLoader sterile=new ClassLoader(null){};ExecutorService pool=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r);t.setContextClassLoader(sterile);return t;});
  try {
   // Reproduce the failing discovery before SmackInitialization poisons its class.
   assertTrue(pool.submit(()->{try{SmackXmlParser.getXmlPullParserFactory();return false;}catch(IllegalStateException expected){return true;}}).get(10,TimeUnit.SECONDS));
   java.util.List<Future<Boolean>> results=new java.util.ArrayList<>();
   for(int i=0;i<2;i++)results.add(pool.submit(()->{
    MonogramSmack.initialize(RuntimeEnvironment.getApplication());
    assertTrue(SmackXmlParser.getXmlPullParserFactory() instanceof org.jivesoftware.smack.xml.xpp3.Xpp3XmlPullParserFactory);
    assertEquals("message",PacketParserUtils.getParserFor("<message xmlns='jabber:client'><body>fixture</body></message>").getName());
    // Initializes Smack's real config/provider chain, the exact former crash path. No socket opens.
    return XMPPTCPConnectionConfiguration.builder().setXmppDomain("fixture.invalid").build()!=null;
   }));
   for(Future<Boolean> result:results)assertTrue(result.get(20,TimeUnit.SECONDS));
  }finally{pool.shutdownNow();}
 }
}
