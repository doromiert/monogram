// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.io.*;
import java.net.*;
import java.security.SecureRandom;
import java.util.Map;
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
    public static final class Job {
        private volatile boolean cancelled;
        private volatile HttpURLConnection connection;
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
    private FileTransfer() {}
    /** Private immutable upload input; the caller deletes it after committing or abandoning the transfer. */
    public static File snapshot(File source, File directory, Job job) throws Exception {
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
        String contentType = encrypted ? "application/octet-stream" : mime;
        Slot slot;
        try { slot=manager.requestSlot(filename, size, contentType); }
        catch(org.jivesoftware.smack.XMPPException.XMPPErrorException | org.jivesoftware.smack.SmackException.NoResponseException error) {
            throw new XmppRequestException("File upload slot",error);
        }
        FileLink.parse(slot.getPutUrl().toExternalForm());
        FileLink link = FileLink.parse(slot.getGetUrl().toExternalForm());
        byte[] iv = null, key = null;
        if (encrypted) { SecureRandom random = new SecureRandom(); iv = new byte[12]; key = new byte[32]; random.nextBytes(iv); random.nextBytes(key); }
        HttpURLConnection http = open(slot.getPutUrl(), job);
        try {
            http.setRequestMethod("PUT"); http.setDoOutput(true); http.setFixedLengthStreamingMode(size);
            http.setRequestProperty("Content-Type", contentType);
            for (Map.Entry<String, String> header : slot.getHeaders().entrySet()) {
                String name = header.getKey(); String value = header.getValue();
                if (!(name.equalsIgnoreCase("Authorization") || name.equalsIgnoreCase("Cookie") || name.equalsIgnoreCase("Expires"))
                        || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) throw new Failure("UPLOAD_HEADERS");
                http.addRequestProperty(name, value);
            }
            try (InputStream in = new FileInputStream(file); OutputStream out = http.getOutputStream()) {
                copy(in, out, size, length, encrypted ? cipher(true, iv, key) : null, job, progress);
            }
            job.check(); int status = http.getResponseCode();
            if (status != 200 && status != 201 && status != 204) throw new Failure("UPLOAD_HTTP_" + status);
        } finally { http.disconnect(); job.connection = null; }
        return encrypted ? FileLink.encrypted(link.downloadUrl, iv, key) : link;
    }
    public static void download(FileLink link, File destination, Job job, Progress progress) throws Exception {
        download(link, destination, job, progress, null);
    }
    /** The application supplies its existing auto-download policy after receiving size/type headers. */
    public static boolean download(FileLink link, File destination, Job job, Progress progress, DownloadPolicy policy) throws Exception {
        return download(link, destination, job, progress, policy, null);
    }
    public static boolean download(FileShare sharing, File destination, Job job, Progress progress, DownloadPolicy policy) throws Exception {
        return download(sharing.link, destination, job, progress, policy, sharing);
    }
    private static boolean download(FileLink link, File destination, Job job, Progress progress, DownloadPolicy policy, FileShare sharing) throws Exception {
        job.check();
        File parent = destination.getAbsoluteFile().getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create attachment directory");
        File partial = File.createTempFile("monogram-", ".part", parent);
        URL url = link.downloadUrl;
        try {
            for (int redirects = 0; redirects <= 5; redirects++) {
                FileLink.parse(url.toExternalForm());
                HttpURLConnection http = open(url, job);
                try {
                    http.setRequestMethod("GET"); http.setRequestProperty("Accept-Encoding", "identity");
                    int status = http.getResponseCode();
                    if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                        String location = http.getHeaderField("Location");
                        if (location == null) throw new Failure("DOWNLOAD_REDIRECT");
                        url = new URL(url, location); continue;
                    }
                    if (status != 200) throw new Failure("DOWNLOAD_HTTP_" + status);
                    long length = contentLength(http);
                    long maximum = MAX_BYTES;
                    if (policy != null) {
                        long plainSize = length < 0 && sharing != null ? sharing.size
                                : length - (link.encrypted() ? 16 : 0);
                        if (plainSize <= 0) return false;
                        maximum = Math.min(MAX_BYTES, policy.limit(http.getContentType(), plainSize));
                        if (maximum <= 0 || plainSize > maximum) return false;
                    }
                    if (length > MAX_BYTES + (link.encrypted() ? 16 : 0)) throw new Failure("SERVER_SIZE_LIMIT");
                    try (InputStream in = http.getInputStream(); FileOutputStream out = new FileOutputStream(partial)) {
                        copy(in, out, maximum + (link.encrypted() ? 16 : 0), length, link.encrypted() ? cipher(false, link.iv, link.key) : null, job, progress);
                        out.getFD().sync();
                    }
                    job.check();
                    if (sharing != null) sharing.verify(partial, job);
                    job.check();
                    if (!partial.renameTo(destination)) throw new IOException("Cannot commit attachment");
                    return true;
                } finally { http.disconnect(); job.connection = null; }
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
