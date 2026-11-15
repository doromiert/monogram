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
   String identity=field(NativeMesh.inspect(b,"state"),"id");
   NativeMesh.stop(b);b=0;
   b=NativeMesh.start(root.resolve("b").toString(),key,"Android fixture","[\""+ip+"\"]");
   if(!identity.equals(field(NativeMesh.inspect(b,"state"),"id")))throw new AssertionError("Restart replaced the device identity");
   String invite=NativeMesh.invite(a);
   long stale=Long.parseLong(NativeMesh.pairing(b,"token",0,""));long boundary=Long.parseLong(NativeMesh.pairing(b,"invalidate",0,""));NativeMesh.pairing(b,"cancel",boundary,"");
   boolean cancelled=false;try{NativeMesh.pairing(b,"join",stale,invite);}catch(IllegalStateException expected){cancelled=true;}if(!cancelled)throw new AssertionError("Cancelled JNI work resumed");
   NativeMesh.join(b,invite);
   String owner="fixture@example.invalid",id="giphy",itemKey="[\"token\",\""+owner+"\",\""+id+"\"]";
   NativeMesh.putJson(a,"token",owner,id,"{\"value\":\"fictional-test-key\"}");long deadline=System.nanoTime()+12_000_000_000L;
   while(!NativeMesh.inspect(b,"metas").contains("giphy")){if(System.nanoTime()>deadline)throw new AssertionError("JNI transfer timed out");Thread.sleep(30);}
   Path exported=Paths.get(NativeMesh.read(b,itemKey));String text=new String(Files.readAllBytes(exported),java.nio.charset.StandardCharsets.UTF_8);Files.delete(exported);if(!text.equals("{\"value\":\"fictional-test-key\"}"))throw new AssertionError("JNI content mismatch");
   String pending=NativeMesh.inspect(b,"pending"),digest=field(pending,"digest");NativeMesh.applied(b,itemKey,digest);NativeMesh.signature(b,itemKey,"fixture-native-signature");if(!NativeMesh.signature(b,itemKey,"").equals("fixture-native-signature"))throw new AssertionError("JNI import tracking failed");
   String order="{\"emojis\":[\"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa\"],\"stickers\":[\"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb\"]}";
   String orderKey="[\"pack-order\",\""+owner+"\",\"order\"]";NativeMesh.putJson(a,"pack-order",owner,"order",order);deadline=System.nanoTime()+12_000_000_000L;
   while(!NativeMesh.inspect(b,"metas").contains("pack-order")){if(System.nanoTime()>deadline)throw new AssertionError("Pack order transfer timed out");Thread.sleep(30);}
   exported=Paths.get(NativeMesh.read(b,orderKey));text=new String(Files.readAllBytes(exported),java.nio.charset.StandardCharsets.UTF_8);Files.delete(exported);if(!order.equals(text))throw new AssertionError("Pack order changed during encrypted transfer");
   String packId="aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",itemId="bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
   String metadata="{\"auto\":false,\"connected\":false,\"emojis\":true,\"id\":\""+packId+"\",\"installed\":true,\"items\":[{\"emoji\":\"🙂\",\"h\":64,\"id\":\""+itemId+"\",\"mime\":\"image/png\",\"name\":\"smile\",\"w\":64}],\"name\":\"Fixture\",\"private\":false,\"source\":\"local:fixture\"}";
   String metadataKey="[\"pack-meta\",\""+owner+"\",\""+packId+"\"]";NativeMesh.putJson(a,"pack-meta",owner,packId,metadata);deadline=System.nanoTime()+12_000_000_000L;
   while(!NativeMesh.inspect(b,"metas").contains("pack-meta")){if(System.nanoTime()>deadline)throw new AssertionError("Pack metadata transfer timed out");Thread.sleep(30);}
   exported=Paths.get(NativeMesh.read(b,metadataKey));text=new String(Files.readAllBytes(exported),java.nio.charset.StandardCharsets.UTF_8);Files.delete(exported);if(!metadata.equals(text))throw new AssertionError("Pack metadata changed during encrypted transfer");
   String clockOwner="revisions@example.invalid";long edited=System.currentTimeMillis();
   String latest=NativeMesh.putJsonAt(a,"pack-order",clockOwner,"order",order,edited);
   String delayed=NativeMesh.putJsonAt(a,"pack-order",clockOwner,"order","{\"stickers\":[],\"emojis\":[]}",edited-1000);
   if(!latest.equals(delayed))throw new AssertionError("Late stale JNI export replaced actual newer user edit");
   String repeated=NativeMesh.putJsonAt(a,"pack-order",clockOwner,"order",order,edited+1);if(!repeated.contains("\"stamp\":"+(edited+1)))throw new AssertionError("Actual edit with unchanged snapshot did not advance revision");if(NativeMesh.appliedVersion(a,latest))throw new AssertionError("Stale same-digest acknowledgement accepted");if(!NativeMesh.appliedVersion(a,repeated))throw new AssertionError("Current revision acknowledgement failed");
   String contactPeer="friend@example.invalid";StringBuilder contactIdBuilder=new StringBuilder();for(byte part:java.security.MessageDigest.getInstance("SHA-256").digest(contactPeer.getBytes(java.nio.charset.StandardCharsets.UTF_8)))contactIdBuilder.append(String.format(java.util.Locale.ROOT,"%02x",part&255));String contactId=contactIdBuilder.toString();
   String contact="{\"avatar\":null,\"nickname\":\"Local friend\",\"note\":\"Private note\",\"peer\":\""+contactPeer+"\"}";
   String contactMeta=NativeMesh.putJsonAt(a,"contact",owner,contactId,contact,edited+2);String contactKey="[\"contact\",\""+owner+"\",\""+contactId+"\"]";deadline=System.nanoTime()+12_000_000_000L;
   while(!NativeMesh.inspect(b,"metas").contains(contactId)){if(System.nanoTime()>deadline)throw new AssertionError("Contact metadata transfer timed out");Thread.sleep(30);}
   exported=Paths.get(NativeMesh.read(b,contactKey));text=new String(Files.readAllBytes(exported),java.nio.charset.StandardCharsets.UTF_8);Files.delete(exported);if(!contact.equals(text))throw new AssertionError("Portable contact changed during encrypted transfer");
   if(!NativeMesh.appliedVersion(b,contactMeta))throw new AssertionError("Exact contact acknowledgement failed");
   String staleContact=NativeMesh.putJsonAt(a,"contact",owner,contactId,contact.replace("Local friend","Old name"),edited+1);if(!contactMeta.equals(staleContact))throw new AssertionError("Delayed contact export overwrote newer user edit");
   NativeMesh.account(b,owner,false);if(NativeMesh.inspect(b,"metas").contains("giphy"))throw new AssertionError("Removed account retained secrets");NativeMesh.account(b,owner,true);
   boolean rejected=false;try{NativeMesh.validate("token",owner,"unknown","{\"value\":\"fixture\"}");}catch(IllegalStateException expected){rejected=true;}if(!rejected)throw new AssertionError("JNI schema bypass");
   System.out.println("PASS production JNI immediate restart, pairing, TLS settings/pack-order/metadata transfer and actual user-edit revisions, staged plaintext cleanup, import tracking, account removal and validation");
  }finally{if(a!=0)NativeMesh.stop(a);if(b!=0)NativeMesh.stop(b);try(java.util.stream.Stream<Path> files=Files.walk(root)){files.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});}}
 }
}
