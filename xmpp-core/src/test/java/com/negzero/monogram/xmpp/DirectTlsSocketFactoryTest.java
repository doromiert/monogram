// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;import java.nio.file.*;import java.io.*;import java.net.*;import java.security.*;import java.security.cert.*;import javax.net.ssl.*;import java.util.concurrent.*;
class DirectTlsSocketFactoryTest {
 private static void command(String...args)throws Exception{Process process=new ProcessBuilder(args).redirectErrorStream(true).start();try(InputStream input=process.getInputStream()){byte[] buffer=new byte[4096];while(input.read(buffer)!=-1){}}assertEquals(0,process.waitFor());}
 @Test void verifiedServiceDomainIsRequiredBeforeApplicationData()throws Exception {
  Path root=Files.createTempDirectory("monogram-direct-tls-");
  try{
   Path cert=root.resolve("cert.pem"),key=root.resolve("key.pem"),p12=root.resolve("server.p12");
   command("openssl","req","-x509","-newkey","rsa:2048","-nodes","-keyout",key.toString(),"-out",cert.toString(),"-days","1","-subj","/CN=fixture.invalid","-addext","subjectAltName=DNS:fixture.invalid");
   command("openssl","pkcs12","-export","-in",cert.toString(),"-inkey",key.toString(),"-out",p12.toString(),"-passout","pass:fixture");
   KeyStore store=KeyStore.getInstance("PKCS12");try(InputStream input=Files.newInputStream(p12)){store.load(input,"fixture".toCharArray());}KeyManagerFactory keys=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());keys.init(store,"fixture".toCharArray());SSLContext server=SSLContext.getInstance("TLS");server.init(keys.getKeyManagers(),null,null);
   X509Certificate approved;try(InputStream input=Files.newInputStream(cert)){approved=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(input);}
   X509TrustManager trust=new X509TrustManager(){public void checkClientTrusted(X509Certificate[]c,String a)throws CertificateException{throw new CertificateException();}public void checkServerTrusted(X509Certificate[]c,String a)throws CertificateException{if(c.length!=1||!c[0].equals(approved))throw new CertificateException();}public X509Certificate[]getAcceptedIssuers(){return new X509Certificate[]{approved};}};
   for(boolean correct:new boolean[]{true,false})try(SSLServerSocket listener=(SSLServerSocket)server.getServerSocketFactory().createServerSocket(0,1,InetAddress.getLoopbackAddress())){
    CompletableFuture<Integer> received=new CompletableFuture<>();Thread thread=new Thread(()->{try(SSLSocket socket=(SSLSocket)listener.accept()){socket.setSoTimeout(3000);socket.startHandshake();received.complete(socket.getInputStream().read());}catch(Exception e){received.complete(-1);}});thread.start();
    Account account=new Account("alice@"+(correct?"fixture.invalid":"wrong.invalid"),"127.0.0.1",listener.getLocalPort(),"Fixture",null,trust,true);DirectTlsSocketFactory factory=new DirectTlsSocketFactory(account);
    try(Socket socket=factory.createSocket()){
     assertFalse(factory.secure());if(correct){socket.connect(new InetSocketAddress("127.0.0.1",listener.getLocalPort()),3000);assertTrue(factory.secure());socket.getOutputStream().write(42);socket.getOutputStream().flush();assertEquals(42,received.get(4,TimeUnit.SECONDS));}
     else{assertThrows(IOException.class,()->socket.connect(new InetSocketAddress("127.0.0.1",listener.getLocalPort()),3000));assertFalse(factory.secure());assertEquals(-1,received.get(4,TimeUnit.SECONDS));}
    }thread.join(4000);assertFalse(thread.isAlive());
   }
  }finally{try(java.util.stream.Stream<Path> paths=Files.walk(root)){paths.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(IOException ignored){}});}}
 }
}
