package com.negzero.monogram.xmpp;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
public final class GeneratePayloadVectors {
 public static void main(String[] args)throws Exception {
  byte[] key=new byte[32];for(int i=0;i<32;i++)key[i]=(byte)i;
  String[] cases={"", "Mobile payload 🌿 · Καλημέρα · こんにちは", "<envelope xmlns='urn:xmpp:sce:1'><content><body xmlns='jabber:client'>Authenticated 🌿</body><reply xmlns='urn:xmpp:reply:0' to='bob@example.invalid' id='original'/></content><rpad/><from jid='alice@example.invalid'/><to jid='bob@example.invalid'/></envelope>"};
  System.out.println("[");
  for(int i=0;i<cases.length;i++) {try(Omemo2Payload.Sealed sealed=Omemo2Payload.seal(cases[i].getBytes(StandardCharsets.UTF_8),new SecureRandom(){public void nextBytes(byte[] target){System.arraycopy(key,0,target,0,target.length);}})) {
   System.out.print("{\"plaintext\":\""+Base64.getEncoder().encodeToString(cases[i].getBytes(StandardCharsets.UTF_8))+"\",\"material\":\""+Base64.getEncoder().encodeToString(sealed.material)+"\",\"ciphertext\":\""+Base64.getEncoder().encodeToString(sealed.ciphertext)+"\"}"+(i+1<cases.length?",":"")+"\n");
  }} System.out.println("]");
 }
}
