// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.wallet;
import java.io.*;import java.net.*;import java.nio.charset.StandardCharsets;
import java.security.*;import java.security.cert.*;import javax.net.ssl.*;
/** Delivery fixture: no wallet, seed, funds or public node is involved. */
public final class WalletTlsBridgeTest {
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 static byte[] request(WalletTlsBridge bridge,String path,boolean authenticate)throws Exception{
  URI uri=URI.create(bridge.endpoint());
  try(Socket socket=new Socket(uri.getHost(),uri.getPort())){
   socket.setSoTimeout(5000);
   String auth=authenticate ? "Authorization: Basic "+java.util.Base64.getEncoder().encodeToString((bridge.user+":"+bridge.password).getBytes(StandardCharsets.US_ASCII))+"\r\n" : "";
   socket.getOutputStream().write(("POST "+path+" HTTP/1.1\r\nHost: localhost\r\n"+auth+"Content-Length: 4\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
   socket.getOutputStream().write(new byte[]{0,1,(byte)255,10});socket.getOutputStream().flush();
   ByteArrayOutputStream out=new ByteArrayOutputStream();
   if(!authenticate){int next;while((next=socket.getInputStream().read())!=-1){out.write(next);if(text(out.toByteArray()).endsWith("\r\n\r\n"))break;}return out.toByteArray();}
   byte[] b=new byte[8192];int n;while((n=socket.getInputStream().read(b))!=-1)out.write(b,0,n);return out.toByteArray();
  }
 }
 static String text(byte[] b){return new String(b,StandardCharsets.ISO_8859_1);}
 public static void main(String[] args)throws Exception{
  KeyStore store=KeyStore.getInstance(KeyStore.getDefaultType());store.load(null);
  try(InputStream in=new FileInputStream(args[1])){store.setCertificateEntry("fixture",CertificateFactory.getInstance("X.509").generateCertificate(in));}
  TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(store);
  SSLContext context=SSLContext.getInstance("TLS");context.init(null,tm.getTrustManagers(),null);
  try(WalletTlsBridge bridge=new WalletTlsBridge(args[0],context.getSocketFactory())){
   check(text(request(bridge,"/plain",false)).contains("401 Unauthorized"),"Unauthenticated local requests must not reach node");
   for(int i=0;i<4;i++){
    String response=text(request(bridge,i%2==0 ? "/plain" : "/chunked",true));
    check(response.startsWith("HTTP/1.1 200"),"RPC response: "+response);
    check(response.endsWith(i%2==0 ? "\u0000\u0001\u00ff\n" : "4\r\n\u0000\u0001\u00ff\n\r\n0\r\n\r\n"),"Binary response framing");
   }
  }
  try(WalletTlsBridge bridge=new WalletTlsBridge(args[0])){
   check(text(request(bridge,"/plain",true)).startsWith("HTTP/1.1 502"),"Untrusted certificate accepted");
   check(bridge.lastFailure!=null,"Certificate failure missing");
  }
  try(WalletTlsBridge bridge=new WalletTlsBridge(args[0].replace("localhost","127.0.0.1"),context.getSocketFactory())){
   check(text(request(bridge,"/plain",true)).startsWith("HTTP/1.1 502"),"Wrong certificate hostname accepted");
  }
  System.out.println("PASS: binary framing, chunked framing, local auth, trusted TLS, rejected untrusted TLS, rejected hostname mismatch");
 }
}
