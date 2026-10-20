// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.io.*;
import java.util.Properties;
import java.util.Map;
import java.util.Collections;
import java.util.Objects;
import org.jivesoftware.smackx.omemo.trust.OmemoTrustCallback;
import org.jivesoftware.smackx.omemo.trust.TrustState;
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;
import org.jivesoftware.smackx.omemo.trust.OmemoFingerprint;

/** Decisions are bound to the fingerprint, so a replacement identity always needs a new decision. */
public final class IdentityTrust implements OmemoTrustCallback {
    private final File file;
    private final Properties decisions = new Properties();
    private long revision;
    public synchronized long revision() { return revision; }

    public IdentityTrust(File file) throws IOException {
        this.file = file;
        if (file.isFile()) try (InputStream in = new FileInputStream(file)) { decisions.load(in); }
    }

    private String key(OmemoDevice device, OmemoFingerprint fingerprint) {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(fingerprint, "fingerprint");
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
        setTrustAll(Collections.singletonMap(d, f), trusted);
    }
    public synchronized void setTrustAll(Map<OmemoDevice, OmemoFingerprint> identities, boolean trusted) {
        if (identities.isEmpty()) return;
        Properties updated = new Properties();
        updated.putAll(decisions);
        for (Map.Entry<OmemoDevice, OmemoFingerprint> identity : identities.entrySet()) {
            updated.setProperty(key(identity.getKey(), identity.getValue()), Boolean.toString(trusted));
        }
        try {
            File parent = file.getAbsoluteFile().getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create trust directory");
            File tmp = File.createTempFile("trust-", ".tmp", parent);
            try {
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    updated.store(out, "Monogram OMEMO fingerprint decisions");
                    out.getFD().sync();
                }
                if (!tmp.renameTo(file)) throw new IOException("Could not replace trust file");
            } finally { tmp.delete(); }
        } catch (IOException e) {
            throw new IllegalStateException("Could not persist identity decision", e);
        }
        decisions.clear();
        decisions.putAll(updated);revision++;
    }
}
