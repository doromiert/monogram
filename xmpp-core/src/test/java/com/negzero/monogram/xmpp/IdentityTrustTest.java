package com.negzero.monogram.xmpp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import org.jxmpp.jid.impl.JidCreate;
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;
import org.jivesoftware.smackx.omemo.trust.OmemoFingerprint;
import static org.junit.jupiter.api.Assertions.*;

class IdentityTrustTest {
    @TempDir File dir;
    @Test void decisionsSurviveRestartAndNeverCarryOverToChangedKeys() throws Exception {
        File file = new File(dir, "trust.properties");
        OmemoDevice device = new OmemoDevice(JidCreate.entityBareFrom("bob@example.org"), 42);
        OmemoFingerprint first = new OmemoFingerprint("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        OmemoFingerprint changed = new OmemoFingerprint("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        IdentityTrust trust = new IdentityTrust(file);
        assertFalse(trust.isDecided(device, first));
        assertFalse(trust.isTrusted(device, first));
        trust.setTrust(device, first, true);
        IdentityTrust restarted = new IdentityTrust(file);
        assertTrue(restarted.isTrusted(device, first));
        assertFalse(restarted.isDecided(device, changed));
        assertFalse(restarted.isTrusted(device, changed));
        restarted.setTrust(device, first, false);
        assertTrue(new IdentityTrust(file).isDecided(device, first));
        assertFalse(new IdentityTrust(file).isTrusted(device, first));
    }
    @Test void rejectsMalformedAccounts() {
        assertThrows(Exception.class, () -> new Account("example.org", null, 5222, "Monogram"));
        assertThrows(Exception.class, () -> new Account("a@example.org/resource", null, 5222, "Monogram"));
        assertThrows(Exception.class, () -> new Account("a@example.org", null, 0, "Monogram"));
    }
}
