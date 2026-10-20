// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Arrays;

/** XEP-0454 URL/key syntax. The secret fragment is never part of an HTTP request. */
public final class FileLink {
    public final String wireUrl;
    public final URL downloadUrl;
    final byte[] iv, key;

    private FileLink(String wireUrl, URL downloadUrl, byte[] iv, byte[] key) {
        this.wireUrl = wireUrl; this.downloadUrl = downloadUrl; this.iv = iv; this.key = key;
    }
    public boolean encrypted() { return key != null; }
    public static FileLink parse(String value) throws java.io.IOException {
        try {
            URI uri = new URI(value);
            boolean encrypted = "aesgcm".equals(uri.getScheme());
            if ((!encrypted && !"https".equals(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getPort() > 65535 || uri.getPort() < -1) throw new IllegalArgumentException();
            byte[] iv = null, key = null;
            if (encrypted) {
                String hex = uri.getRawFragment();
                if (hex == null || !hex.matches("(?i)([0-9a-f]{88}|[0-9a-f]{96})")) throw new IllegalArgumentException();
                byte[] bytes = new byte[hex.length() / 2];
                for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(hex.substring(2*i, 2*i+2), 16);
                iv = Arrays.copyOf(bytes, bytes.length - 32); key = Arrays.copyOfRange(bytes, bytes.length - 32, bytes.length);
            } else if (uri.getRawFragment() != null) throw new IllegalArgumentException();
            String address = "https://" + uri.getRawAuthority() + (uri.getRawPath() == null ? "" : uri.getRawPath())
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            return new FileLink(value, new URL(address), iv, key);
        } catch (Exception e) { throw new java.io.IOException("Invalid attachment URL"); }
    }
    static FileLink encrypted(URL get, byte[] iv, byte[] key) throws java.io.IOException {
        FileLink plain = parse(get.toExternalForm());
        StringBuilder secret = new StringBuilder();
        for (byte b : iv) secret.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        for (byte b : key) secret.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return parse("aesgcm" + plain.wireUrl.substring(5) + "#" + secret);
    }
    public String fileName() {
        try {
            String path = downloadUrl.getPath();
            String name = URLDecoder.decode(path.substring(path.lastIndexOf('/') + 1).replace("+", "%2B"), "UTF-8");
            name = name.replaceAll("[\\\\/\\p{Cntrl}]", "_");
            return name.isEmpty() || name.equals(".") || name.equals("..") ? "attachment" : name.substring(0, Math.min(name.length(), 200));
        } catch (Exception e) { return "attachment"; }
    }
}
