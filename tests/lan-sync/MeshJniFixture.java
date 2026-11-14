// SPDX-License-Identifier: GPL-3.0-or-later
import com.negzero.monogram.sync.NativeMesh;
import java.nio.file.*;import java.net.*;import java.util.*;import java.util.regex.*;
/** Exercises the production JNI ABI, not a mock or alternate protocol implementation. */
public final class MeshJniFixture {
 static String field(String json,String name){Matcher m=Pattern.compile("\\\""+name+"\\\":\\\"([^\\\"]+)\\\"").matcher(json);if(!m.find())throw new AssertionError("Missing fixture field "+name);return m.group(1);}
 public static void main(String[]args)throws Exception {
  String ip=null;for(NetworkInterface i:Collections.list(NetworkInterface.getNetworkInterfaces()))if(i.isUp())for(InetAddress a:Collections.list(i.getInetAddresses()))if(a instanceof Inet4Address&&a.isSiteLocalAddress())ip=a.getHostAddress();if(ip==null)throw new AssertionError("LAN interface needed for fixture");
  Path root=Files.createTempDirectory("monogram-jni-mesh-");long a=0,b=0;
  try{
   a=NativeMesh.start(root.resolve("a").toString(),new byte[32],"Desktop fixture","[\""+ip+"\"]");byte[] key=new byte[32];Arrays.fill(key,(byte)1);b=NativeMesh.start(root.resolve("b").toString(),key,"Android fixture","[\""+ip+"\"]");
   String invite=NativeMesh.invite(a);NativeMesh.join(b,invite);
   String owner="fixture@example.invalid",id="giphy",itemKey="[\"token\",\""+owner+"\",\""+id+"\"]";
   NativeMesh.putJson(a,"token",owner,id,"{\"value\":\"fictional-test-key\"}");long deadline=System.nanoTime()+12_000_000_000L;
   while(!NativeMesh.inspect(b,"metas").contains("giphy")){if(System.nanoTime()>deadline)throw new AssertionError("JNI transfer timed out");Thread.sleep(30);}
   Path exported=Paths.get(NativeMesh.read(b,itemKey));String text=new String(Files.readAllBytes(exported),java.nio.charset.StandardCharsets.UTF_8);Files.delete(exported);if(!text.equals("{\"value\":\"fictional-test-key\"}"))throw new AssertionError("JNI content mismatch");
   String pending=NativeMesh.inspect(b,"pending"),digest=field(pending,"digest");NativeMesh.applied(b,itemKey,digest);NativeMesh.signature(b,itemKey,"fixture-native-signature");if(!NativeMesh.signature(b,itemKey,"").equals("fixture-native-signature"))throw new AssertionError("JNI import tracking failed");
   NativeMesh.account(b,owner,false);if(NativeMesh.inspect(b,"metas").contains("giphy"))throw new AssertionError("Removed account retained secrets");NativeMesh.account(b,owner,true);
   boolean rejected=false;try{NativeMesh.validate("token",owner,"unknown","{\"value\":\"fixture\"}");}catch(IllegalStateException expected){rejected=true;}if(!rejected)throw new AssertionError("JNI schema bypass");
   System.out.println("PASS production JNI pairing, TLS transfer, staged plaintext cleanup, import tracking, account removal and validation");
  }finally{if(a!=0)NativeMesh.stop(a);if(b!=0)NativeMesh.stop(b);try(java.util.stream.Stream<Path> files=Files.walk(root)){files.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});}}
 }
}
