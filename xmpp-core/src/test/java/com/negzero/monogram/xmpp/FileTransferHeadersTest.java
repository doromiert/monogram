package com.negzero.monogram.xmpp;

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.tcp.*;
import org.jivesoftware.smackx.disco.packet.DiscoverInfo;
import org.jivesoftware.smackx.httpfileupload.element.*;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** In-memory HTTP/XMPP exchanges; no account, service or network access. */
class FileTransferHeadersTest {
    @TempDir File directory;
    static class Http extends HttpURLConnection {
        byte[] response=new byte[0];
        String length="0",mime="image/jpeg";
        boolean bodyOpened,disconnected;
        int status=200;
        final ByteArrayOutputStream uploaded=new ByteArrayOutputStream();
        Http() throws Exception { super(new URL("https://uploads.example.org/file")); }
        URL transport() throws Exception {
            return new URL(null,url.toExternalForm(),new URLStreamHandler() {
                @Override protected URLConnection openConnection(URL url) { return Http.this; }
            });
        }
        @Override public String getHeaderField(String name) {
            if("Content-Length".equalsIgnoreCase(name)) return length;
            return "Content-Type".equalsIgnoreCase(name) ? mime : null;
        }
        @Override public int getResponseCode() { return status; }
        @Override public InputStream getInputStream() { bodyOpened=true;return new ByteArrayInputStream(response); }
        @Override public OutputStream getOutputStream() { return uploaded; }
        @Override public void disconnect() { disconnected=true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() {}
    }
    static class Connection extends XMPPTCPConnection {
        final Http http;
        SlotRequest slot;
        Connection(Http http) throws Exception {
            super(XMPPTCPConnectionConfiguration.builder().setXmppDomain("example.org").build());
            this.http=http;connected=true;authenticated=true;
            user=JidCreate.entityFullFrom("alice@example.org/test");setReplyTimeout(1000);
        }
        @Override protected void sendStanzaInternal(Stanza stanza) throws SmackException.NotConnectedException,InterruptedException {
            IQ request=(IQ)stanza;IQ answer;
            if(request instanceof DiscoverInfo) {
                DiscoverInfo info=new DiscoverInfo();
                info.addIdentity(new DiscoverInfo.Identity("store","Uploads","file"));
                info.addFeature("urn:xmpp:http:upload:0");answer=info;
            } else if(request instanceof SlotRequest) {
                slot=(SlotRequest)request;
                try { answer=new Slot(http.transport(),new URL("https://uploads.example.org/file")); }
                catch(Exception invalid) { throw new AssertionError(invalid); }
            } else throw new AssertionError(request.toXML());
            answer.setType(IQ.Type.result);answer.setStanzaId(request.getStanzaId());
            answer.setFrom(request.getTo()==null ? user.asBareJid() : request.getTo());answer.setTo(user);
            invokeStanzaCollectorsAndNotifyRecvListeners(answer);
        }
    }
    FileLink transport(FileLink original,Http http) throws Exception {
        java.lang.reflect.Constructor<FileLink> constructor=FileLink.class.getDeclaredConstructor(String.class,URL.class,byte[].class,byte[].class);
        constructor.setAccessible(true);
        return constructor.newInstance(original.wireUrl,http.transport(),original.iv,original.key);
    }
    byte[] crypt(byte[] bytes,FileLink link,int mode) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode,new SecretKeySpec(link.key,"AES"),new GCMParameterSpec(128,link.iv));
        return cipher.doFinal(bytes);
    }
    FileLink encrypted() throws Exception {
        return FileLink.encrypted(new URL("https://uploads.example.org/file"),new byte[12],new byte[32]);
    }
    @Test void slotAndPutKeepCallerMimeWhileEncryptedBytesStayEncrypted() throws Exception {
        byte[] original={1,3,5,7,9};File source=new File(directory,"photo.jpg");Files.write(source.toPath(),original);
        for(int mode=0;mode<3;mode++) {
            Http http=new Http();http.status=201;Connection connection=new Connection(http);
            String mime=mode==2 ? "application/octet-stream" : "image/jpeg";
            FileLink link=FileTransfer.upload(connection,source,"photo.jpg",mime,mode!=0,new FileTransfer.Job(),null);
            assertEquals(mime,connection.slot.getContentType());
            assertTrue(connection.slot.toXML().toString().contains("content-type='"+mime+"'"));
            assertEquals(mime,http.getRequestProperty("Content-Type"));
            assertEquals(original.length+(mode==0 ? 0 : 16),connection.slot.getSize());
            assertEquals(mode!=0,link.encrypted());
            byte[] uploaded=http.uploaded.toByteArray();
            assertEquals(connection.slot.getSize(),uploaded.length);
            if(mode!=0) {
                assertFalse(Arrays.equals(original,uploaded));
                assertArrayEquals(original,crypt(uploaded,link,Cipher.DECRYPT_MODE));
            } else assertArrayEquals(original,uploaded);
            assertTrue(http.disconnected);
        }
    }
    @Test void reportsPlaintextSizeAndTypeBeforeReadingEncryptedBody() throws Exception {
        byte[] original=new byte[32769];Arrays.fill(original,(byte)4);
        FileLink link=encrypted();Http http=new Http();http.response=crypt(original,link,Cipher.ENCRYPT_MODE);
        http.length=Integer.toString(http.response.length);http.mime="image/jpeg; charset=binary";
        boolean[] received={false};File destination=new File(directory,"download");
        assertTrue(FileTransfer.download(transport(link,http),destination,new FileTransfer.Job(),null,null,(mime,size) -> {
            assertFalse(http.bodyOpened);assertFalse(destination.exists());
            assertEquals(http.mime,mime);assertEquals(original.length,size);received[0]=true;
        }));
        assertTrue(received[0]);assertTrue(http.bodyOpened);assertTrue(http.disconnected);
        assertArrayEquals(original,Files.readAllBytes(destination.toPath()));
    }
    @Test void rejectedOrUnknownSizesStillPublishHeadersWithoutOpeningTheBody() throws Exception {
        for(String length:new String[]{"10016",null}) {
            Http http=new Http();http.length=length;http.mime="video/mp4";
            boolean[] received={false};File destination=new File(directory,"rejected");
            Files.write(destination.toPath(),new byte[]{9});
            assertFalse(FileTransfer.download(transport(encrypted(),http),destination,new FileTransfer.Job(),null,(mime,size)->1024,(mime,size)-> {
                assertFalse(http.bodyOpened);assertEquals("video/mp4",mime);
                assertEquals(length==null ? -1 : 10000,size);received[0]=true;
            }));
            assertTrue(received[0]);assertFalse(http.bodyOpened);assertTrue(http.disconnected);
            assertArrayEquals(new byte[]{9},Files.readAllBytes(destination.toPath()));
        }
    }
    @Test void headersCannotBypassByteBoundsAuthenticationOrCancellation() throws Exception {
        File destination=new File(directory,"verified");Files.write(destination.toPath(),new byte[]{9});
        Http dishonest=new Http();dishonest.length="10";dishonest.response=new byte[11];
        assertEquals("SERVER_SIZE_LIMIT",assertThrows(FileTransfer.Failure.class,()->FileTransfer.download(
                transport(FileLink.parse("https://uploads.example.org/file"),dishonest),destination,new FileTransfer.Job(),null,(mime,size)->10,(mime,size)->{})).reason);
        Http corrupted=new Http();corrupted.response=new byte[32];corrupted.length="32";
        assertEquals("AUTHENTICATION",assertThrows(FileTransfer.Failure.class,()->FileTransfer.download(
                transport(encrypted(),corrupted),destination,new FileTransfer.Job(),null,null,(mime,size)->{})).reason);
        Http cancelled=new Http();FileTransfer.Job job=new FileTransfer.Job();
        assertThrows(InterruptedIOException.class,()->FileTransfer.download(transport(encrypted(),cancelled),destination,job,null,null,(mime,size)->job.cancel()));
        assertFalse(cancelled.bodyOpened);
        assertArrayEquals(new byte[]{9},Files.readAllBytes(destination.toPath()));
        assertEquals(0,Arrays.stream(directory.listFiles()).filter(f->f.getName().endsWith(".part")).count());
    }
}
