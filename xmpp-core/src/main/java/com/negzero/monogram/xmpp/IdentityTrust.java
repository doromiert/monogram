// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.io.*;
import java.util.Properties;
import org.jivesoftware.smackx.omemo.trust.OmemoTrustCallback;
import org.jivesoftware.smackx.omemo.trust.TrustState;
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;
import org.jivesoftware.smackx.omemo.trust.OmemoFingerprint;

/** Decisions are bound to the fingerprint, so a replacement identity always needs a new decision. */
public final class IdentityTrust implements OmemoTrustCallback {
    private final File file;
    private final Properties decisions = new Properties();

    public IdentityTrust(File file) throws IOException {
        this.file = file;
        if (file.isFile()) try (InputStream in = new FileInputStream(file)) { decisions.load(in); }
    }

    private String key(OmemoDevice device, OmemoFingerprint fingerprint) {
        return device.getJid() + ":" + device.getDeviceId() + ":" + fingerprint;
    }

    @Override public synchronized TrustState getTrust(OmemoDevice d, OmemoFingerprint f) {
        String value = decisions.getProperty(key(d, f));
        return value == null ? TrustState.undecided : "true".equals(value) ? TrustState.trusted : TrustState.untrusted;
    }
    @Override public void setTrust(OmemoDevice d, OmemoFingerprint f, TrustState state) {
        if (state == TrustState.undecided) throw new IllegalArgumentException("An explicit trust decision is required");
        setTrust(d, f, state == TrustState.trusted);
    }
    public synchronized boolean isTrusted(OmemoDevice d, OmemoFingerprint f) {
        return "true".equals(decisions.getProperty(key(d, f)));
    }
    public synchronized boolean isDecided(OmemoDevice d, OmemoFingerprint f) {
        return decisions.containsKey(key(d, f));
    }
    public synchronized void setTrust(OmemoDevice d, OmemoFingerprint f, boolean trusted) {
        String key = key(d, f);
        String previous = decisions.getProperty(key);
        decisions.setProperty(key, Boolean.toString(trusted));
        try {
            File parent = file.getAbsoluteFile().getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create trust directory");
            File tmp = File.createTempFile("trust-", ".tmp", parent);
            try {
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    decisions.store(out, "Monogram OMEMO fingerprint decisions");
                    out.getFD().sync();
                }
                if (!tmp.renameTo(file)) throw new IOException("Could not replace trust file");
            } finally { tmp.delete(); }
        } catch (IOException e) {
            if (previous == null) decisions.remove(key); else decisions.setProperty(key, previous);
            throw new IllegalStateException("Could not persist identity decision", e);
        }
    }
}
