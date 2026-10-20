package com.negzero.monogram.xmpp;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class FileLinkTest {
    @Test void strictSecretUrlParsingAndSafeNames() throws Exception {
        String secret = "8c3d050e9386ec173861778f68e9af38a97aaf82faa4063b4d0878a61261534410c8a84331eaac851759f587";
        FileLink link = FileLink.parse("aesgcm://example.org/tr%C3%A8s%20cool.jpg?token=1#" + secret);
        assertEquals("https://example.org/tr%C3%A8s%20cool.jpg?token=1", link.downloadUrl.toString());
        assertTrue(link.encrypted()); assertEquals(12, link.iv.length); assertEquals(32, link.key.length);
        assertEquals("très cool.jpg", link.fileName());
        assertEquals(link.wireUrl, FileLink.encrypted(link.downloadUrl, link.iv, link.key).wireUrl);
        assertEquals("a+b.jpg", FileLink.parse("https://example.org/a+b.jpg").fileName());
        assertEquals(".._bad_name", FileLink.parse("https://example.org/..%2fbad%5cname").fileName());
        for (String invalid : new String[]{"http://example.org/file", "file:///etc/passwd", "https://user:pass@example.org/file",
                "https://example.org/file#secret", "aesgcm://example.org/file#abcd", "aesgcm://example.org/file#" + secret + "00",
                "https://example.org:99999/file", "https://example.org/file\nother"}) {
            assertThrows(IOException.class, () -> FileLink.parse(invalid), "Must reject unsafe or malformed attachment references");
        }
    }
}
