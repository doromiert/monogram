package com.negzero.monogram.xmpp;

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Bounded reconnection at the production HTTP boundary, with no external service. */
class FileTransferRecoveryTest extends FileTransferHeadersTest {
    static class Sequence extends Http {
        final List<Http> attempts;
        int opened;
        Sequence(Http... attempts) throws Exception { this.attempts=Arrays.asList(attempts); }
        @Override URL transport() throws Exception {
            return new URL(null,url.toExternalForm(),new URLStreamHandler() {
                @Override protected URLConnection openConnection(URL url) {
                    assertTrue(opened<attempts.size(),"Recovery must remain bounded");
                    return attempts.get(opened++);
                }
            });
        }
    }
    Http timeout() throws Exception { return new Http() {
        @Override public int getResponseCode() throws SocketTimeoutException { throw new SocketTimeoutException(); }
    }; }
    Http ready() throws Exception { Http http=new Http();http.response=new byte[]{1,2,3};http.length="3";return http; }
    @Test void twoHeaderFailuresReconnectThenKeepNormalBodyTimeout() throws Exception {
        Http a=timeout(),b=timeout(),c=ready();Sequence sequence=new Sequence(a,b,c);
        File target=new File(directory,"reconnected");int[] updates={0};
        assertTrue(FileTransfer.download(transport(FileLink.parse("https://uploads.example.org/file"),sequence),target,
                new FileTransfer.Job(),(bytes,total)->{if(bytes==0&&total==-1)updates[0]++;},null));
        assertEquals(3,sequence.opened);assertEquals(2,updates[0]);
        assertEquals(30000,c.getReadTimeout());assertArrayEquals(c.response,Files.readAllBytes(target.toPath()));
        assertTrue(a.disconnected&&b.disconnected&&c.disconnected);
    }
    @Test void headerDeadlineActuallyDisconnectsAndRecovers() throws Exception {
        CountDownLatch released=new CountDownLatch(1);
        Http stalled=new Http() {
            @Override public int getResponseCode() throws IOException {
                try { assertTrue(released.await(5,TimeUnit.SECONDS)); }
                catch(InterruptedException e){throw new InterruptedIOException();}
                throw new SocketException("Disconnected");
            }
            @Override public void disconnect(){super.disconnect();released.countDown();}
        };
        Sequence sequence=new Sequence(stalled,ready());
        assertTrue(FileTransfer.download(transport(FileLink.parse("https://uploads.example.org/file"),sequence),
                new File(directory,"deadline"),new FileTransfer.Job(),null,null));
        assertEquals(2,sequence.opened);
    }
    @Test void httpRefusalAndCancellationNeverReconnect() throws Exception {
        Http denied=new Http();denied.status=403;Sequence refusal=new Sequence(denied);
        assertEquals("DOWNLOAD_HTTP_403",assertThrows(FileTransfer.Failure.class,()->FileTransfer.download(
                transport(FileLink.parse("https://uploads.example.org/file"),refusal),new File(directory,"denied"),new FileTransfer.Job(),null,null)).reason);
        FileTransfer.Job job=new FileTransfer.Job();Http cancelled=new Http(){
            @Override public int getResponseCode() throws SocketException {job.cancel();throw new SocketException();}
        };Sequence cancellation=new Sequence(cancelled);
        assertThrows(InterruptedIOException.class,()->FileTransfer.download(transport(FileLink.parse("https://uploads.example.org/file"),cancellation),
                new File(directory,"cancelled"),job,null,null));
        assertEquals(1,refusal.opened);assertEquals(1,cancellation.opened);
    }
    @Test void exhaustedDownloadsAndTlsFailuresDoNotLoop() throws Exception {
        Sequence failures=new Sequence(timeout(),timeout(),timeout());
        assertThrows(SocketTimeoutException.class,()->FileTransfer.download(transport(FileLink.parse("https://uploads.example.org/file"),failures),
                new File(directory,"exhausted"),new FileTransfer.Job(),null,null));assertEquals(3,failures.opened);
        Http tls=new Http(){@Override public int getResponseCode() throws javax.net.ssl.SSLHandshakeException {throw new javax.net.ssl.SSLHandshakeException("invalid fixture certificate");}};
        Sequence insecure=new Sequence(tls);
        assertThrows(javax.net.ssl.SSLHandshakeException.class,()->FileTransfer.download(transport(FileLink.parse("https://uploads.example.org/file"),insecure),
                new File(directory,"tls"),new FileTransfer.Job(),null,null));assertEquals(1,insecure.opened);
    }
    @Test void redirectBudgetAndBodyFailuresCannotBecomeReconnectLoops() throws Exception {
        Http[] redirects=new Http[6];
        for(int i=0;i<redirects.length;i++) redirects[i]=new Http(){
            @Override public String getHeaderField(String name){return "Location".equals(name) ? "/next" : super.getHeaderField(name);}
            @Override public int getResponseCode(){return 302;}
        };
        Sequence sequence=new Sequence(redirects);
        assertEquals("DOWNLOAD_REDIRECT",assertThrows(FileTransfer.Failure.class,()->FileTransfer.download(
                transport(FileLink.parse("https://uploads.example.org/file"),sequence),new File(directory,"redirects"),new FileTransfer.Job(),null,null)).reason);
        assertEquals(6,sequence.opened);
        Http body=new Http(){@Override public InputStream getInputStream(){return new InputStream(){
            @Override public int read() throws IOException {throw new SocketTimeoutException();}
        };}};body.length="3";Sequence partial=new Sequence(body);
        assertThrows(SocketTimeoutException.class,()->FileTransfer.download(transport(FileLink.parse("https://uploads.example.org/file"),partial),
                new File(directory,"body"),new FileTransfer.Job(),null,null));assertEquals(1,partial.opened);
    }
    @Test void encryptedPutReplaysIdenticalBytesAfterSourceMutation() throws Exception {
        byte[] original={1,3,5,7,9};File source=new File(directory,"original");Files.write(source.toPath(),original);
        Http first=new Http(){@Override public int getResponseCode() throws IOException {
            Files.write(source.toPath(),new byte[]{2,4,6,8,0});throw new SocketTimeoutException();
        }};
        Http second=new Http();second.status=201;Sequence sequence=new Sequence(first,second);
        FileLink link=FileTransfer.upload(new Connection(sequence),source,"original.bin","application/octet-stream",true,new FileTransfer.Job(),null);
        assertEquals(2,sequence.opened);assertArrayEquals(first.uploaded.toByteArray(),second.uploaded.toByteArray());
        assertArrayEquals(original,crypt(second.uploaded.toByteArray(),link,Cipher.DECRYPT_MODE));
        assertEquals(first.getRequestProperties(),second.getRequestProperties());
        assertEquals(0,Arrays.stream(directory.listFiles()).filter(f->f.getName().startsWith("monogram-wire-")).count());
    }
    @Test void putRefusalCancellationAndRepeatedBlackholesStayBounded() throws Exception {
        File source=new File(directory,"put");Files.write(source.toPath(),new byte[]{1,2,3});
        Http denied=new Http();denied.status=403;Sequence refusal=new Sequence(denied);
        assertEquals("UPLOAD_HTTP_403",assertThrows(FileTransfer.Failure.class,()->FileTransfer.upload(new Connection(refusal),source,"image/jpeg",true,new FileTransfer.Job(),null)).reason);
        Sequence failures=new Sequence(timeout(),timeout());
        assertThrows(SocketTimeoutException.class,()->FileTransfer.upload(new Connection(failures),source,"image/jpeg",true,new FileTransfer.Job(),null));
        FileTransfer.Job job=new FileTransfer.Job();Http cancelled=new Http(){@Override public int getResponseCode() throws SocketException {job.cancel();throw new SocketException();}};
        Sequence cancellation=new Sequence(cancelled);
        assertThrows(InterruptedIOException.class,()->FileTransfer.upload(new Connection(cancellation),source,"image/jpeg",true,job,null));
        assertEquals(1,refusal.opened);assertEquals(2,failures.opened);assertEquals(1,cancellation.opened);
    }
    @Test void uploadBodyTimeDoesNotStartAcknowledgementDeadline() throws Exception {
        File source=new File(directory,"slow");Files.write(source.toPath(),new byte[]{1,2,3});
        Http slow=new Http(){
            @Override public OutputStream getOutputStream(){return new ByteArrayOutputStream(){
                @Override public synchronized void write(byte[] bytes,int offset,int length){
                    try {Thread.sleep(3200);}catch(InterruptedException e){throw new AssertionError(e);}
                    assertFalse(disconnected,"A healthy body write must not be cut off by the header deadline");super.write(bytes,offset,length);
                }
            };}
        };slow.status=201;
        FileTransfer.upload(new Connection(new Sequence(slow)),source,"image/jpeg",false,new FileTransfer.Job(),null);
    }
}
