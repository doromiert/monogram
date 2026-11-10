// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.util.PacketParserUtils;
import org.jxmpp.jid.impl.JidCreate;

/** Actual mobile file model and transfer cipher; disposable fixture bytes only. */
public final class GenerateFileVectors {
    static String repeat(String text,int count){StringBuilder out=new StringBuilder();for(int i=0;i<count;i++)out.append(text);return out.toString();}
    static String b64(String text){return b64(text.getBytes(StandardCharsets.UTF_8));}
    static String b64(byte[] value){return Base64.getEncoder().encodeToString(value);}
    static Message stanza(String body,String extra)throws Exception {
        Message m=PacketParserUtils.parseStanza("<message xmlns='jabber:client' from='alice@example.org/phone' to='bob@example.org' type='chat' id='file'>"+extra+"</message>");if(body!=null)m.setBody(body);return m;
    }
    static void emit(Message wire,boolean encrypted)throws Exception {
        wire=PacketParserUtils.parseStanza(wire.toXML().toString());
        Message content=wire;
        byte[] sce=null;
        if(encrypted){sce=Omemo2Payload.envelope(wire,new java.security.SecureRandom());content=Omemo2Payload.content(wire,sce);}
        XmppMessage m=encrypted?XmppMessage.modern(wire,content,JidCreate.entityBareFrom("bob@example.org"),null,null):XmppMessage.from(wire,JidCreate.entityBareFrom("bob@example.org"),wire.getBody(),false,true,null,null);
        if(m==null)throw new AssertionError("Missing received file model");
        System.out.print("{\"xml\":\""+b64(wire.toXML().toString())+"\",\"encrypted\":"+encrypted+",\"attachment\":"+(m.attachment!=null));
        if(m.attachment!=null){String caption=m.sharing==null?"":m.caption;ForwardedContent forward=ForwardedContent.parse(caption);if(forward!=null)caption=forward.body;
            System.out.print(",\"url\":\""+b64(m.attachment.wireUrl)+"\",\"name\":\""+b64(m.sharing==null?m.attachment.fileName():m.sharing.name)+"\",\"mime\":\""+b64(m.sharing==null?"application/octet-stream":m.sharing.mime)+"\",\"size\":"+(m.sharing==null?-1:m.sharing.size)+",\"manual\":"+(m.sharing!=null&&m.sharing.manual)+",\"sticker\":"+(m.sharing!=null&&m.sharing.sticker)+",\"caption\":\""+b64(caption)+"\"");
        }
        if(sce!=null)System.out.print(",\"sce\":\""+b64(sce)+"\"");System.out.println("}");
    }
    static String description(String url,String metadata){return "<file-sharing xmlns='urn:xmpp:sfs:0' disposition='inline'><file xmlns='urn:xmpp:file:metadata:0'><name>photo.jpg</name><media-type>image/jpeg</media-type>"+metadata+"</file><sources><url-data xmlns='http://jabber.org/protocol/url-data' target='"+url+"'/></sources></file-sharing>";}
    static void ciphers()throws Exception {
        java.lang.reflect.Method cipher=FileTransfer.class.getDeclaredMethod("cipher",boolean.class,byte[].class,byte[].class);cipher.setAccessible(true);
        java.lang.reflect.Method copy=FileTransfer.class.getDeclaredMethod("copy",InputStream.class,OutputStream.class,long.class,long.class,GCMBlockCipher.class,FileTransfer.Job.class,FileTransfer.Progress.class);copy.setAccessible(true);
        byte[] key=new byte[32];for(int i=0;i<key.length;i++)key[i]=(byte)i;
        System.out.println("[");boolean first=true;
        for(int nonce:new int[]{12,16})for(int length:new int[]{0,4352}){
            byte[] iv=new byte[nonce],body=new byte[length];for(int i=0;i<iv.length;i++)iv[i]=(byte)(i+32);for(int i=0;i<body.length;i++)body[i]=(byte)i;
            ByteArrayOutputStream out=new ByteArrayOutputStream();copy.invoke(null,new ByteArrayInputStream(body),out,body.length+16L,(long)body.length,cipher.invoke(null,true,iv,key),new FileTransfer.Job(),null);
            if(!first)System.out.println(",");first=false;
            System.out.print("{\"iv\":\""+b64(iv)+"\",\"key\":\""+b64(key)+"\",\"plain\":\""+b64(body)+"\",\"ciphertext\":\""+b64(out.toByteArray())+"\",\"hashes\":{");
            String[] names={"sha-256","sha3-256","id-blake2b256"};
            org.bouncycastle.crypto.Digest[] digests={new org.bouncycastle.crypto.digests.SHA256Digest(),new org.bouncycastle.crypto.digests.SHA3Digest(256),new org.bouncycastle.crypto.digests.Blake2bDigest(256)};
            for(int i=0;i<names.length;i++){digests[i].update(body,0,body.length);byte[] hash=new byte[32];digests[i].doFinal(hash,0);if(i>0)System.out.print(",");System.out.print("\""+names[i]+"\":\""+b64(hash)+"\"");}System.out.println("}}");
        }
        System.out.println("]");
    }
    public static void main(String[] args)throws Exception {
        if(args.length>0&&"cipher".equals(args[0])){ciphers();return;}
        FileShare.initialize();PollDefinition.initialize();
        String plain="https://files.example.org/tr%C3%A8s%20cool.bin?token=fixture";
        String secret="aesgcm"+plain.substring(5)+"#"+repeat("00",44);
        File local=Files.createTempFile("file-reference-",".bin").toFile();Files.write(local.toPath(),"Actual mobile bytes 😀\n".getBytes(StandardCharsets.UTF_8));
        System.out.println("[");boolean first=true;
        try {
            for(boolean encrypted:new boolean[]{false,true}){
                FileShare sharing=FileShare.describe(FileLink.parse(encrypted?secret:plain),local,"très cool.bin","application/octet-stream");
                for(String caption:new String[]{"","Caption 😀","First\nSecond 🐈\n","Literal "+plain,ForwardedContent.of("Original",0,"Caption 😀").wireBody()})for(boolean quoted:new boolean[]{false,true}){
                    ReplyReference reply=quoted?new ReplyReference("alice@example.org","previous").quoted("Quoted 🦄"):null;
                    String body=sharing.body(caption);if(reply!=null)body=reply.wireBody(body);
                    Message m=stanza(body,"");if(reply!=null)reply.addTo(m);sharing.addTo(m,body);
                    if(!first)System.out.println(",");first=false;emit(m,encrypted);
                }
                Message bare=stanza(encrypted?secret:plain,encrypted?"":"<x xmlns='jabber:x:oob'><url>"+plain+"</url></x>");System.out.println(",");emit(bare,encrypted);
                Message bodyless=stanza(null,description(encrypted?secret:plain,"<size>22</size>"));System.out.println(",");emit(bodyless,encrypted);
            }
            for(String metadata:new String[]{"<size>-2</size>","<size>4</size><size>5</size>","<hash xmlns='urn:xmpp:hashes:2' algo='sha-256'>broken</hash>","<hash xmlns='urn:xmpp:hashes:2' algo='unknown'>broken</hash>"}){System.out.println(",");emit(stanza("Caption",description(plain,metadata)),false);}
            System.out.println(",");emit(stanza("Visible caption",description(secret,"")),false);
            System.out.println(",");emit(stanza(secret,""),false);
            System.out.println(",");emit(stanza("Visible caption",description(plain,"")),true);
            System.out.println(",");Message malformed=stanza("Visible caption",description(plain,""));malformed.addExtension(org.jivesoftware.smack.packet.StandardExtensionElement.builder("fallback","urn:xmpp:fallback:0").addAttribute("for",FileShare.NAMESPACE).addElement(org.jivesoftware.smack.packet.StandardExtensionElement.builder("body","urn:xmpp:fallback:0").build()).build());emit(malformed,false);
        } finally {local.delete();}
        System.out.println("]");
    }
}
