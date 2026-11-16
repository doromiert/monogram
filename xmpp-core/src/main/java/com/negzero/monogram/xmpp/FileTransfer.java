// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.io.*;
import java.net.*;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.*;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smackx.httpfileupload.HttpFileUploadManager;
import org.jivesoftware.smackx.httpfileupload.element.Slot;

/** Blocking, bounded-memory HTTPS transfers, used on a file-I/O executor. */
public final class FileTransfer {
    public static final long MAX_BYTES = 4000L * 1024 * 1024;
    public interface Progress { void update(long bytes, long total); }
    public interface DownloadPolicy { long limit(String contentType, long plainSize); }
    /** Called after final response headers, before opening the response body; size is plaintext bytes or -1. */
    public interface Metadata { void received(String contentType, long plainSize); }
    public static final class Job {
        private volatile boolean cancelled;
        private volatile HttpURLConnection connection;
        private volatile String stage = "queued", recovery;
        private volatile int attempt;
        private volatile long stageStarted = System.nanoTime();
        private void stage(String value) { stage=value;stageStarted=System.nanoTime(); }
        public String recoveryDiagnostic() { return recovery; }
        /** Compiled frames and fixed phases only: exception messages may contain signed URLs. */
        public String diagnostic(Throwable error) {
            StringBuilder text=new StringBuilder("Monogram transfer phase=").append(stage)
                    .append(" attempt=").append(attempt).append(" phase_ms=")
                    .append(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-stageStarted));
            java.util.Set<Throwable> seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for(int depth=0;error!=null&&depth<4&&seen.add(error);depth++,error=error.getCause()) {
                text.append("\n").append(error.getClass().getName());
                StackTraceElement[] frames=error.getStackTrace();
                for(int i=0;i<Math.min(12,frames.length);i++)text.append("\n  at ").append(frames[i]);
            }
            if(recovery!=null)text.append("\nPrevious recovery: ").append(recovery);
            return text.toString();
        }
        public void cancel() { cancelled = true; HttpURLConnection c = connection; if (c != null) c.disconnect(); }
        public boolean cancelled() { return cancelled; }
        public void check() throws InterruptedIOException {
            if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Transfer cancelled");
        }
    }
    public static final class Failure extends IOException {
        public final String reason;
        Failure(String reason) { super(reason); this.reason = reason; }
    }
    private static final ScheduledExecutorService HEADER_TIMEOUTS = Executors.newScheduledThreadPool(2, task -> {
        Thread thread = new Thread(task, "file-response-deadline"); thread.setDaemon(true); return thread;
    });
    /** Disconnect only while waiting for headers, leaving healthy body reads on their normal timeout. */
    private static final class HeaderDeadline implements AutoCloseable {
        private final ScheduledFuture<?> task;
        private boolean closed;
        private volatile boolean expired;
        HeaderDeadline(HttpURLConnection http) {this(http,3);}
        HeaderDeadline(HttpURLConnection http,long seconds) {
            task = HEADER_TIMEOUTS.schedule(() -> {
                synchronized (this) { if (!closed) { expired = true; http.disconnect(); } }
            }, seconds, TimeUnit.SECONDS);
        }
        public synchronized void close() { closed = true; task.cancel(false); }
    }
    /** Bound only an inactive first body attempt; slow transfers keep resetting this deadline. */
    private static final class IdleDeadline implements AutoCloseable {
        private final java.util.concurrent.atomic.AtomicLong activity = new java.util.concurrent.atomic.AtomicLong(System.nanoTime());
        private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        private final ScheduledFuture<?> task;
        private volatile boolean expired;
        IdleDeadline(HttpURLConnection http) {
            task = HEADER_TIMEOUTS.scheduleWithFixedDelay(() -> {
                if (System.nanoTime()-activity.get() >= TimeUnit.SECONDS.toNanos(5) && closed.compareAndSet(false,true)) {
                    expired=true;http.disconnect();
                }
            },250,250,TimeUnit.MILLISECONDS);
        }
        void touch() { activity.set(System.nanoTime()); }
        public void close() { closed.set(true);task.cancel(false); }
    }
    private static Progress activeProgress(Progress progress,IdleDeadline deadline) {
        return (bytes,total) -> {if(deadline!=null)deadline.touch();if(progress!=null)progress.update(bytes,total);};
    }
    private static void retryDiagnostic(Job job,String stage,IOException error,IdleDeadline deadline) {
        job.recovery="phase="+job.stage+" stage="+stage+" inactive="+(deadline!=null&&deadline.expired)+" cause="+error.getClass().getSimpleName();
        System.err.println("Monogram transfer reconnect stage="+stage+" inactive="+(deadline!=null&&deadline.expired)+" cause="+error.getClass().getSimpleName());
    }
    private static boolean reconnect(IOException error, HeaderDeadline deadline) {
        if (error instanceof javax.net.ssl.SSLException || error instanceof Failure) return false;
        return (deadline != null && deadline.expired) || error instanceof SocketTimeoutException
                || error instanceof SocketException || error instanceof EOFException;
    }
    private FileTransfer() {}
    /** Private immutable upload input; the caller deletes it after committing or abandoning the transfer. */
    public static File snapshot(File source, File directory, Job job) throws Exception {
        job.stage("snapshot");
        job.check(); long length = source.length();
        if (!source.isFile() || length <= 0 || length > MAX_BYTES) throw new Failure("FILE_SIZE");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create upload directory");
        File snapshot = File.createTempFile("monogram-upload-", ".part", directory);
        try {
            try (InputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(snapshot)) {
                copy(in, out, MAX_BYTES, length, null, job, null); out.getFD().sync();
            }
            job.check(); return snapshot;
        } catch (Exception error) { if (!snapshot.delete()) snapshot.deleteOnExit(); throw error; }
    }
    public static String uploadName(String original,String mime,boolean generated) {
        if(!generated && !original.matches("-?[0-9]+_-?[0-9]+(?:_[0-9]+)?\\.[A-Za-z0-9]{1,10}")) return original;
        String extension=original.replaceFirst("^.*(?=\\.[A-Za-z0-9]{1,10}$)","");
        if(!extension.matches("\\.[A-Za-z0-9]{1,10}")) extension="image/jpeg".equals(mime) ? ".jpg" : "audio/ogg".equals(mime) ? ".ogg" : ".bin";
        SecureRandom random=new SecureRandom(); StringBuilder suffix=new StringBuilder();
        for(int i=0;i<8;i++) suffix.append((char)('a'+random.nextInt(26)));
        return (System.currentTimeMillis()/1000)+"_"+suffix+extension.toLowerCase(java.util.Locale.ROOT);
    }
    /** Check the provider before making an immutable private copy of a potentially large input. */
    public static void checkUploadSize(XMPPConnection connection,long length,boolean encrypted,Job job) throws Exception {
        uploadService(connection,length,encrypted,job);
    }
    private static HttpFileUploadManager uploadService(XMPPConnection connection,long length,boolean encrypted,Job job) throws Exception {
        job.stage("upload-service");
        job.check();
        if(length<=0 || length>MAX_BYTES) throw new Failure("FILE_SIZE");
        long size=length+(encrypted ? 16 : 0);
        HttpFileUploadManager manager=HttpFileUploadManager.getInstanceFor(connection);
        try {
            if(!manager.isUploadServiceDiscovered() && !manager.discoverUploadService()) throw new Failure("NO_UPLOAD_SERVICE");
        } catch(org.jivesoftware.smack.XMPPException.XMPPErrorException | org.jivesoftware.smack.SmackException.NoResponseException error) {
            throw new XmppRequestException("File upload service discovery",error);
        }
        job.check();
        if(!manager.getDefaultUploadService().acceptsFileOfSize(size)) throw new Failure("SERVER_SIZE_LIMIT");
        return manager;
    }
    public static FileLink upload(XMPPConnection connection, File file, String mime, boolean encrypted, Job job, Progress progress) throws Exception {
        return upload(connection, file, file.getName(), mime, encrypted, job, progress);
    }
    public static FileLink upload(XMPPConnection connection, File file, String filename, String mime, boolean encrypted, Job job, Progress progress) throws Exception {
        job.check();
        long length = file.length();
        if (!file.isFile()) throw new Failure("FILE_SIZE");
        HttpFileUploadManager manager = uploadService(connection,length,encrypted,job);
        long size = length + (encrypted ? 16 : 0);
        // The caller selects opaque MIME for modern encrypted sharing. Legacy clients
        // need the original type on the slot and PUT, as in Conversations/Monocles.
        String contentType = mime == null || mime.trim().isEmpty() ? "application/octet-stream" : mime;
        job.stage("upload-slot");
        Slot slot=requestSlot(manager,filename,size,contentType);
        return uploadToSlot(slot,file,contentType,encrypted,job,progress,
                () -> { job.stage("upload-slot");return requestSlot(manager,uploadName(filename,contentType,true),size,contentType); });
    }
    private interface SlotRefresh { Slot request() throws Exception; }
    private static Slot requestSlot(HttpFileUploadManager manager,String filename,long size,String contentType) throws Exception {
        try { return manager.requestSlot(filename,size,contentType); }
        catch(org.jivesoftware.smack.XMPPException.XMPPErrorException | org.jivesoftware.smack.SmackException.NoResponseException error) {
            throw new XmppRequestException("File upload slot",error);
        }
    }
    private static FileLink validateSlot(Slot slot) throws Exception {
        FileLink.parse(slot.getPutUrl().toExternalForm());
        FileLink link=FileLink.parse(slot.getGetUrl().toExternalForm());
        for(Map.Entry<String,String> header:slot.getHeaders().entrySet()) {
            String name=header.getKey(),value=header.getValue();
            if(!(name.equalsIgnoreCase("Authorization")||name.equalsIgnoreCase("Cookie")||name.equalsIgnoreCase("Expires"))||value.indexOf('\r')>=0||value.indexOf('\n')>=0)throw new Failure("UPLOAD_HEADERS");
        }
        return link;
    }
    private static boolean uploadPresent(URL url,long size,Job job)throws Exception {
        job.stage("upload-confirmation");
        HttpURLConnection confirmation=open(url,job);
        try(HeaderDeadline deadline=new HeaderDeadline(confirmation)) {
            confirmation.setRequestMethod("HEAD");confirmation.setUseCaches(false);
            confirmation.setRequestProperty("Accept-Encoding","identity");confirmation.setRequestProperty("Connection","close");
            boolean present=confirmation.getResponseCode()==200 && contentLength(confirmation)==size;
            System.err.println("Monogram upload response confirmation present="+present);return present;
        }catch(IOException error){job.check();System.err.println("Monogram upload confirmation cause="+error.getClass().getSimpleName());return false;}
        finally{confirmation.disconnect();job.connection=null;}
    }
    private static FileLink uploadToSlot(Slot slot, File file, String contentType, boolean encrypted, Job job, Progress progress) throws Exception {
        return uploadToSlot(slot,file,contentType,encrypted,job,progress,null);
    }
    private static FileLink uploadToSlot(Slot slot, File file, String contentType, boolean encrypted, Job job, Progress progress,SlotRefresh refresh) throws Exception {
        job.check();long length=file.length(),size=length+(encrypted?16:0);
        if(!file.isFile()||length<=0||length>MAX_BYTES)throw new Failure("FILE_SIZE");
        validateSlot(slot);
        byte[] iv = null, key = null;
        if (encrypted) { SecureRandom random = new SecureRandom(); iv = new byte[12]; key = new byte[32]; random.nextBytes(iv); random.nextBytes(key); }
        job.stage("upload-encryption");
        // Encrypt once: replay must never read changed plaintext under the same GCM nonce.
        File wire = File.createTempFile("monogram-wire-", ".part", file.getAbsoluteFile().getParentFile());
        try {
            if (!wire.setReadable(false, false) || !wire.setWritable(false, false)
                    || !wire.setReadable(true, true) || !wire.setWritable(true, true)) throw new IOException("Cannot protect upload snapshot");
            try (InputStream in = new FileInputStream(file); FileOutputStream out = new FileOutputStream(wire)) {
                copy(in, out, size, length, encrypted ? cipher(true, iv, key) : null, job, null); out.getFD().sync();
            }
            try (FileInputStream input = new FileInputStream(wire)) {
                if (!wire.delete()) throw new IOException("Cannot unlink upload snapshot");
                for (int attempt = 0; attempt < 2; attempt++) {
                    FileLink link=validateSlot(slot);
                    input.getChannel().position(0); job.check();
                    job.attempt=attempt+1;job.stage("upload-connect");
                    HttpURLConnection http = open(slot.getPutUrl(), job);
                    IdleDeadline idle=attempt==0?new IdleDeadline(http):null;
                    boolean bodySent=false;HeaderDeadline responseDeadline=null;
                    try {
                        http.setRequestMethod("PUT"); http.setDoOutput(true); http.setFixedLengthStreamingMode(size);
                        http.setRequestProperty("Content-Type", contentType);
                        http.setRequestProperty("Connection","close");
                        for (Map.Entry<String, String> header : slot.getHeaders().entrySet()) http.addRequestProperty(header.getKey(), header.getValue());
                        try (IdleDeadline waiting=idle; OutputStream out = http.getOutputStream()) {
                            job.stage("upload-body");
                            copy(input, out, size, size, null, job, activeProgress(progress,idle)); out.flush();
                            job.stage("upload-response");
                        }
                        bodySent=true;job.check();
                        if(attempt==0)responseDeadline=new HeaderDeadline(http,5);
                        int status = http.getResponseCode();
                        if (status != 200 && status != 201 && status != 204) throw new Failure("UPLOAD_HTTP_" + status);
                        return encrypted ? FileLink.encrypted(link.downloadUrl, iv, key) : link;
                    } catch (IOException error) {
                        job.check();
                        boolean conflict=error instanceof Failure&&"UPLOAD_HTTP_409".equals(((Failure)error).reason);
                        if(responseDeadline!=null)responseDeadline.close();
                        retryDiagnostic(job,bodySent?"upload-response":"upload-body",error,idle);
                        if(bodySent && !(error instanceof Failure) && !(error instanceof javax.net.ssl.SSLException)) {
                            http.disconnect();job.connection=null;
                            if(uploadPresent(slot.getGetUrl(),size,job))return encrypted?FileLink.encrypted(link.downloadUrl,iv,key):link;
                        }
                        if(attempt==1||refresh==null||(!conflict&&!reconnect(error,responseDeadline)&&!(idle!=null&&idle.expired)))throw error;
                        // Upload slots are single-use. Never replay a possibly accepted PUT to one.
                        String used=slot.getPutUrl().toExternalForm();
                        http.disconnect();job.connection=null;job.check();
                        slot=refresh.request();validateSlot(slot);job.check();
                        if(used.equals(slot.getPutUrl().toExternalForm()))throw error;
                        if (progress != null) progress.update(0, size);
                    } finally {
                        if(idle!=null)idle.close();if(responseDeadline!=null)responseDeadline.close();
                        http.disconnect(); job.connection = null;
                    }
                }
            }
            throw new IOException("Upload attempts exhausted");
        } finally { if (wire.exists() && !wire.delete()) wire.deleteOnExit(); }
    }
    public static void download(FileLink link, File destination, Job job, Progress progress) throws Exception {
        download(link, destination, job, progress, null);
    }
    /** The application supplies its existing auto-download policy after receiving size/type headers. */
    public static boolean download(FileLink link, File destination, Job job, Progress progress, DownloadPolicy policy) throws Exception {
        return download(link, destination, job, progress, policy, null, null);
    }
    public static boolean download(FileShare sharing, File destination, Job job, Progress progress, DownloadPolicy policy) throws Exception {
        return download(sharing.link, destination, job, progress, policy, sharing, null);
    }
    public static boolean download(FileLink link, File destination, Job job, Progress progress, DownloadPolicy policy, Metadata metadata) throws Exception {
        return download(link, destination, job, progress, policy, null, metadata);
    }
    public static boolean download(FileShare sharing, File destination, Job job, Progress progress, DownloadPolicy policy, Metadata metadata) throws Exception {
        return download(sharing.link, destination, job, progress, policy, sharing, metadata);
    }
    private static boolean download(FileLink link, File destination, Job job, Progress progress, DownloadPolicy policy, FileShare sharing, Metadata metadata) throws Exception {
        job.check();
        File parent = destination.getAbsoluteFile().getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create attachment directory");
        File partial = File.createTempFile("monogram-", ".part", parent);
        URL url = link.downloadUrl;
        int recoveries = 2;
        try {
            for (int redirects = 0; redirects <= 5;) {
                FileLink.parse(url.toExternalForm());
                HttpURLConnection http = open(url, job);
                HeaderDeadline deadline = null;
                IdleDeadline idle=null;
                try {
                    http.setRequestMethod("GET"); http.setRequestProperty("Accept-Encoding", "identity");
                    int status;
                    try {
                        if (recoveries > 0) deadline = new HeaderDeadline(http);
                        status = http.getResponseCode();
                    } catch (IOException error) {
                        job.check();
                        if (recoveries == 0 || !reconnect(error, deadline)) throw error;
                        recoveries--;
                        retryDiagnostic(job,"download-headers",error,null);
                        if (progress != null) progress.update(0, -1);
                        continue;
                    } finally { if (deadline != null) deadline.close(); }
                    if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                        String location = http.getHeaderField("Location");
                        if (location == null) throw new Failure("DOWNLOAD_REDIRECT");
                        url = new URL(url, location); redirects++; continue;
                    }
                    if (status != 200) throw new Failure("DOWNLOAD_HTTP_" + status);
                    long length = contentLength(http);
                    long plainSize = length < 0 ? (sharing == null ? -1 : sharing.size)
                            : Math.max(0, length - (link.encrypted() ? 16 : 0));
                    String contentType = sharing == null ? http.getContentType() : sharing.mime;
                    if (length > MAX_BYTES + (link.encrypted() ? 16 : 0)) throw new Failure("SERVER_SIZE_LIMIT");
                    if (metadata != null) metadata.received(contentType, plainSize);
                    job.check();
                    long maximum = MAX_BYTES;
                    if (policy != null) {
                        if (plainSize <= 0) return false;
                        maximum = Math.min(MAX_BYTES, policy.limit(contentType, plainSize));
                        if (maximum <= 0 || plainSize > maximum) return false;
                    }
                    idle=recoveries>0?new IdleDeadline(http):null;
                    try (FileOutputStream out = new FileOutputStream(partial)) {
                        try (IdleDeadline waiting=idle; InputStream in = http.getInputStream()) {
                            copy(in, out, maximum + (link.encrypted() ? 16 : 0), length, link.encrypted() ? cipher(false, link.iv, link.key) : null, job, activeProgress(progress,idle));
                        }
                        out.getFD().sync();
                    }
                    job.check();
                    if (sharing != null) sharing.verify(partial, job);
                    job.check();
                    if (!partial.renameTo(destination)) throw new IOException("Cannot commit attachment");
                    return true;
                } catch(IOException error) {
                    job.check();
                    if(recoveries==0 || !reconnect(error,null) && !(idle!=null&&idle.expired && !(error instanceof Failure) && !(error instanceof javax.net.ssl.SSLException)))throw error;
                    recoveries--;retryDiagnostic(job,"download-body",error,idle);
                    if(progress!=null)progress.update(0,-1);
                } finally {if(idle!=null)idle.close(); http.disconnect(); job.connection = null; }
            }
            throw new Failure("DOWNLOAD_REDIRECT");
        } finally { if (partial.exists() && !partial.delete()) partial.deleteOnExit(); }
    }
    private static long contentLength(HttpURLConnection http) throws Failure {
        // Android 23 has no getContentLengthLong; the int getter overflows for large files.
        String value=http.getHeaderField("Content-Length");
        if(value==null) return -1;
        value=value.trim();
        if(!value.matches("[0-9]+")) throw new Failure("TRANSFER_SIZE");
        try { return Long.parseLong(value); } catch(NumberFormatException invalid) { throw new Failure("TRANSFER_SIZE"); }
    }
    private static HttpURLConnection open(URL url, Job job) throws IOException {
        job.check();
        HttpURLConnection http = (HttpURLConnection) url.openConnection();
        http.setConnectTimeout(15000); http.setReadTimeout(30000); http.setInstanceFollowRedirects(false);
        job.connection = http;
        try { job.check(); }
        catch (InterruptedIOException cancelled) { http.disconnect(); job.connection = null; throw cancelled; }
        return http;
    }
    private static GCMBlockCipher cipher(boolean encrypt, byte[] iv, byte[] key) {
        GCMBlockCipher cipher = new GCMBlockCipher(AESEngine.newInstance());
        cipher.init(encrypt, new AEADParameters(new KeyParameter(key), 128, iv));
        return cipher;
    }
    private static void copy(InputStream in, OutputStream out, long maximum, long expected, GCMBlockCipher cipher,
                             Job job, Progress progress) throws Exception {
        byte[] bytes = new byte[32768], processed = new byte[32800];
        long count = 0;
        for (int n; (n = in.read(bytes)) != -1;) {
            job.check(); count += n;
            if (count > maximum) throw new Failure("SERVER_SIZE_LIMIT");
            if (cipher == null) out.write(bytes, 0, n);
            else { int written = cipher.processBytes(bytes, 0, n, processed, 0); out.write(processed, 0, written); }
            if (progress != null) progress.update(count, expected);
        }
        job.check();
        if (expected >= 0 && count != expected) throw new Failure("TRANSFER_SIZE");
        if (cipher != null) {
            try { int written = cipher.doFinal(processed, 0); out.write(processed, 0, written); }
            catch (org.bouncycastle.crypto.InvalidCipherTextException e) { throw new Failure("AUTHENTICATION"); }
        }
    }
}
