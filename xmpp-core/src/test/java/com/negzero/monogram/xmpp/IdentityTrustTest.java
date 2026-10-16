package com.negzero.monogram.xmpp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
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
    @Test void bulkTrustPersistsOnlyTheDisplayedFingerprints() throws Exception {
        File file = new File(dir, "bulk.properties");
        IdentityTrust trust = new IdentityTrust(file);
        OmemoDevice contact = new OmemoDevice(JidCreate.entityBareFrom("bob@example.org"), 42);
        OmemoDevice ownOtherDevice = new OmemoDevice(JidCreate.entityBareFrom("alice@example.org"), 99);
        OmemoDevice laterDevice = new OmemoDevice(JidCreate.entityBareFrom("bob@example.org"), 43);
        OmemoFingerprint first = new OmemoFingerprint("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        OmemoFingerprint changed = new OmemoFingerprint("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        trust.setTrust(contact, first, false);
        Map<OmemoDevice, OmemoFingerprint> displayed = new LinkedHashMap<>();
        displayed.put(contact, first);
        displayed.put(ownOtherDevice, first);
        trust.setTrustAll(displayed, true);
        IdentityTrust restarted = new IdentityTrust(file);
        assertTrue(restarted.isTrusted(contact, first));
        assertTrue(restarted.isTrusted(ownOtherDevice, first));
        assertFalse(restarted.isDecided(contact, changed), "A replacement identity was not displayed");
        assertFalse(restarted.isDecided(laterDevice, first), "A new device was not displayed");
    }
    @Test void failedBulkSaveLeavesAllPreviousDecisionsIntact() throws Exception {
        File file = new File(dir, "trust.properties");
        IdentityTrust trust = new IdentityTrust(file);
        OmemoDevice contact = new OmemoDevice(JidCreate.entityBareFrom("bob@example.org"), 42);
        OmemoDevice other = new OmemoDevice(JidCreate.entityBareFrom("alice@example.org"), 99);
        OmemoFingerprint fingerprint = new OmemoFingerprint("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        trust.setTrust(contact, fingerprint, false);
        File saved = new File(dir, "saved.properties");
        Files.move(file.toPath(), saved.toPath());
        assertTrue(file.mkdir());
        assertTrue(new File(file, "keep").createNewFile());
        Map<OmemoDevice, OmemoFingerprint> displayed = new LinkedHashMap<>();
        displayed.put(contact, fingerprint);
        displayed.put(other, fingerprint);
        assertThrows(IllegalStateException.class, () -> trust.setTrustAll(displayed, true));
        assertFalse(trust.isTrusted(contact, fingerprint));
        assertTrue(trust.isDecided(contact, fingerprint));
        assertFalse(trust.isDecided(other, fingerprint));
        IdentityTrust persisted = new IdentityTrust(saved);
        assertFalse(persisted.isTrusted(contact, fingerprint));
        assertFalse(persisted.isDecided(other, fingerprint));
        assertEquals(0, dir.listFiles((parent, name) -> name.startsWith("trust-") && name.endsWith(".tmp")).length);
    }
    @Test void rejectsMalformedAccounts() {
        assertThrows(Exception.class, () -> new Account("example.org", null, 5222, "Monogram"));
        assertThrows(Exception.class, () -> new Account("a@example.org/resource", null, 5222, "Monogram"));
        assertThrows(Exception.class, () -> new Account("a@example.org", null, 0, "Monogram"));
    }
}
