package com.negzero.monogram.xmpp;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.Arrays;
import javax.net.ssl.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "MONOGRAM_IT", matches = "1")
class MediaHttpIntegrationTest {
    @Test void automaticDownloadsUseCallerPolicyAndKnownBoundedSize() throws Exception {
        File state = new File(System.getenv("MONOGRAM_IT_STATE"));
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(new File(state, "certs/server.p12"))) { keys.load(in, "monogram-test".toCharArray()); }
        KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        km.init(keys, "monogram-test".toCharArray());
        SSLContext tls = SSLContext.getInstance("TLS"); tls.init(km.getKeyManagers(), null, null);
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        byte[] small = new byte[511], large = new byte[4096]; Arrays.fill(small, (byte) 3);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            exchange.getResponseHeaders().set("Content-Type", path.equals("/not-media.jpg") ? "text/html"
                    : path.equals("/video") ? "video/mp4" : "application/octet-stream");
            boolean chunked = path.equals("/unknown.mp3") || path.equals("/dishonest.mp3");
            byte[] body = path.equals("/large.mp3") || path.equals("/dishonest.mp3") ? large : small;
            if (path.equals("/dishonest.mp3")) exchange.getResponseHeaders().set("Content-Length", "511");
            try {
                exchange.sendResponseHeaders(200, chunked ? 0 : body.length);
                exchange.getResponseBody().write(body);
            } catch (IOException clientRejectedBody) { /* Rejected responses may close before the body is sent. */ }
            finally { exchange.close(); }
        });
        server.start();
        File destination = new File(state, "automatic-http-file");
        try {
            String base = "https://localhost:" + server.getAddress().getPort();
            for (String path : new String[]{"/image.jpg", "/music.mp3", "/video"}) {
                assertTrue(FileTransfer.download(FileLink.parse(base + path), destination, new FileTransfer.Job(), null, (type, size) -> 1024));
                assertArrayEquals(small, Files.readAllBytes(destination.toPath()));
            }
            for (String path : new String[]{"/unknown.mp3", "/large.mp3"}) {
                assertFalse(FileTransfer.download(FileLink.parse(base + path), destination, new FileTransfer.Job(), null, (type, size) -> 1024));
                assertArrayEquals(small, Files.readAllBytes(destination.toPath()));
            }
            for (String path : new String[]{"/not-media.jpg", "/document.bin"}) {
                assertFalse(FileTransfer.download(FileLink.parse(base + path), destination, new FileTransfer.Job(), null, (type, size) -> 0));
                assertArrayEquals(small, Files.readAllBytes(destination.toPath()));
            }
            try {
                assertFalse(FileTransfer.download(FileLink.parse(base + "/dishonest.mp3"), destination, new FileTransfer.Job(), null, (type, size) -> 1024),
                        "A chunked response must not bypass the stream limit with a false Content-Length");
            } catch (IOException rejected) { /* Rejecting contradictory framing or oversized bytes is also correct. */ }
            assertArrayEquals(small, Files.readAllBytes(destination.toPath()), "Rejected media must preserve the verified copy");
            FileShare verified = FileShare.describe(FileLink.parse(base + "/image.jpg"), destination, "photo.jpg", "image/jpeg");
            assertTrue(FileTransfer.download(verified, destination, new FileTransfer.Job(), null, null));
            String correctHash = java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(small));
            FileShare wrongHash = new FileShare(verified.xml().replace(correctHash, java.util.Base64.getEncoder().encodeToString(new byte[32])));
            FileShare wrongSize = new FileShare(verified.xml().replace("<size>511</size>", "<size>510</size>"));
            for (FileShare invalid : new FileShare[]{wrongHash, wrongSize}) {
                FileTransfer.Failure failure = assertThrows(FileTransfer.Failure.class, () -> FileTransfer.download(invalid, destination, new FileTransfer.Job(), null, null));
                assertEquals("INTEGRITY", failure.reason);
                assertArrayEquals(small, Files.readAllBytes(destination.toPath()), "Invalid SFS downloads must not replace the verified cache");
            }
            assertEquals(0, Arrays.stream(state.listFiles()).filter(f -> f.getName().endsWith(".part")).count());
        } finally { server.stop(0); destination.delete(); }
    }
}
