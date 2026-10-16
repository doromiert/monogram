package org.telegram.messenger.xmpp;

import com.negzero.monogram.xmpp.FileLink;
import com.negzero.monogram.xmpp.FileTransfer;
import java.io.*;
import java.net.InetAddress;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.Arrays;
import javax.net.ssl.*;
import org.jivesoftware.smack.ConnectionConfiguration;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration;

/** Isolated Android process using only the fixture's synthetic account and temporary TLS root. */
public final class FileTransferDeviceTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static byte[] read(File file) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = new FileInputStream(file)) { byte[] b = new byte[8192]; for (int n; (n = in.read(b)) != -1;) out.write(b, 0, n); }
        return out.toByteArray();
    }
    public static void main(String[] args) {
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(60000); } catch (InterruptedException done) { return; }
            for (java.util.Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                System.out.println("Timed out thread: " + entry.getKey().getName());
                for (StackTraceElement frame : entry.getValue()) System.out.println("  " + frame);
            }
            System.exit(1);
        }, "fixture-watchdog");
        watchdog.setDaemon(true); watchdog.start();
        try { run(args); } catch (Throwable error) { error.printStackTrace(System.out); System.exit(1); }
        finally { watchdog.interrupt(); }
        System.exit(0); // Smack's process-wide executors outlive the isolated test connection.
    }
    private static void run(String[] args) throws Exception {
        File root = new File(args[0]); root.mkdirs();
        System.out.println("Android fixture: TLS initialization");
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType()); trust.load(null);
        try (InputStream cert = new FileInputStream(args[1])) { trust.setCertificateEntry("local-fixture", CertificateFactory.getInstance("X.509").generateCertificate(cert)); }
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); factory.init(trust);
        SSLContext tls = SSLContext.getInstance("TLS"); tls.init(null, factory.getTrustManagers(), null);
        HttpsURLConnection.setDefaultSSLSocketFactory(tls.getSocketFactory());
        System.out.println("Android fixture: XMPP initialization");
        XMPPTCPConnection connection = new XMPPTCPConnection(XMPPTCPConnectionConfiguration.builder()
                .setXmppDomain("localhost").setHostAddress(InetAddress.getByAddress(new byte[]{127,0,0,1}))
                .setPort(Integer.parseInt(args[2])).setUsernameAndPassword("alice", "monogram-test")
                .setResource("Monogram-Android-file-test")
                .setCustomX509TrustManager((X509TrustManager) factory.getTrustManagers()[0])
                .setSecurityMode(ConnectionConfiguration.SecurityMode.required).setSendPresence(false).build());
        File input = new File(root, "fixture-image.jpg"), downloaded = new File(root, "verified-file");
        byte[] expected = new byte[65537]; new java.util.Random(7).nextBytes(expected);
        try (OutputStream out = new FileOutputStream(input)) { out.write(expected); }
        try {
            System.out.println("Android fixture: connect");
            connection.connect().login(); check(connection.isSecureConnection(), "Synthetic XMPP connection must use TLS");
            for (boolean encrypted : new boolean[]{false, true}) {
                FileLink link = FileTransfer.upload(connection, input, "image/jpeg", encrypted, new FileTransfer.Job(), null);
                check(link.encrypted() == encrypted, "Upload must retain the selected encryption mode");
                FileTransfer.download(link, downloaded, new FileTransfer.Job(), null);
                check(Arrays.equals(expected, read(downloaded)), "Android HTTPS/encryption round-trip must preserve bytes");
                check(FileTransfer.download(link, downloaded, new FileTransfer.Job(), null, (type, size) -> expected.length), "Small media must download automatically");
                check(!FileTransfer.download(link, downloaded, new FileTransfer.Job(), null, (type, size) -> expected.length - 1), "The media threshold must be enforced excluding the GCM tag");
                check(!FileTransfer.download(link, downloaded, new FileTransfer.Job(), null, (type, size) -> 1024), "Large media must stay manual");
                check(Arrays.equals(expected, read(downloaded)), "Skipped automatic downloads must preserve cached bytes");
                if (encrypted) {
                    String url = link.wireUrl;
                    FileLink wrong = FileLink.parse(url.substring(0, url.length() - 1) + (url.endsWith("0") ? "1" : "0"));
                    try { FileTransfer.download(wrong, downloaded, new FileTransfer.Job(), null); throw new AssertionError("Wrong key must fail"); }
                    catch (FileTransfer.Failure failure) { check(failure.reason.equals("AUTHENTICATION"), "Wrong key must fail authentication"); }
                    check(Arrays.equals(expected, read(downloaded)), "Failed authentication must preserve the last verified file");
                    FileTransfer.Job cancelled = new FileTransfer.Job();
                    try {
                        FileTransfer.download(link, downloaded, cancelled, (bytes, total) -> cancelled.cancel());
                        throw new AssertionError("An in-flight cancellation must stop the download");
                    } catch (IOException failure) { check(cancelled.cancelled(), "Cancellation must be retained"); }
                    check(Arrays.equals(expected, read(downloaded)), "Cancellation must preserve the verified file");
                }
            }
            check(Arrays.stream(root.listFiles()).noneMatch(f -> f.getName().endsWith(".part")), "No partial file may remain");
            System.out.println("PASS: Android TLS XMPP slot discovery, HTTPS plain/encrypted upload/download, automatic media size limits, authentication failure, cancellation, atomic replacement");
        } finally { connection.disconnect(); input.delete(); downloaded.delete(); }
    }
}
