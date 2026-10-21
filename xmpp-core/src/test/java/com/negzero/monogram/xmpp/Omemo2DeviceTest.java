package com.negzero.monogram.xmpp;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.jivesoftware.smack.parsing.StandardExtensionElementProvider;
import org.jivesoftware.smack.util.PacketParserUtils;
import static com.negzero.monogram.xmpp.Omemo2Records.*;

@EnabledIfEnvironmentVariable(named="MONOGRAM_OMEMO2",matches="1")
class Omemo2DeviceTest {
    private Omemo2Bundle parse(String xml) throws Exception {
        return Omemo2Bundle.parse(StandardExtensionElementProvider.INSTANCE.parse(PacketParserUtils.getParserFor(xml)));
    }
    @Test void unusablePrekeyIdsDoNotInvalidateOtherSignedBundleKeys() throws Exception {
        Omemo2Bundle original=new Omemo2Device("alice@example.org",101,new Omemo2TestDatabase()).prepare();
        String xml=original.xml().toXML().toString();
        String bad="<pk id='0'>not-a-valid-key</pk><pk id='2147483648'>invalid</pk><pk>invalid</pk>";
        xml=xml.replaceFirst("(<prekeys[^>]*>)","$1"+bad);
        Omemo2Bundle parsed=parse(xml);
        assertArrayEquals(original.identity(),parsed.identity());assertEquals(original.signedId,parsed.signedId);
        assertArrayEquals(original.preKeys(),parsed.preKeys());
        for(int id:parsed.preKeys()) assertTrue(id>0);
    }
    @Test void invalidOnlyBundlesDuplicateIdsAndInvalidSignedKeysRemainRejected() throws Exception {
        Omemo2Bundle original=new Omemo2Device("alice@example.org",101,new Omemo2TestDatabase()).prepare();
        String xml=original.xml().toXML().toString();
        String none=xml.replaceAll("(<pk[^>]*?id=)['\"][0-9]+['\"]","$1'0'");
        assertThrows(java.security.GeneralSecurityException.class,()->parse(none));
        String signed=xml.replaceAll("(<spk[^>]*?id=)['\"][0-9]+['\"]","$1'0'");
        assertThrows(java.security.GeneralSecurityException.class,()->parse(signed));
        String duplicate=xml.replaceFirst("(<prekeys[^>]*>)","$1<pk id='1'>"+java.util.Base64.getEncoder().encodeToString(original.preKey(1))+"</pk>");
        assertThrows(java.security.GeneralSecurityException.class,()->parse(duplicate));
    }
    @Test void identitySurvivesReopenAndConsumedPrekeysAreReplenishedWithoutReuse() throws Exception {
        Omemo2TestDatabase database = new Omemo2TestDatabase(); Omemo2Device first = new Omemo2Device("alice@example.org",101,database);
        Omemo2Bundle bundle = first.prepare(1000000); assertEquals(100,bundle.preKeys().length);
        Omemo2Bundle parsed = Omemo2Bundle.parse(StandardExtensionElementProvider.INSTANCE.parse(PacketParserUtils.getParserFor(bundle.xml().toXML().toString())));
        assertArrayEquals(bundle.identity(),parsed.identity()); assertEquals(100,parsed.preKeys().length);
        assertEquals(bundle.xml().toXML().toString(),new Omemo2Device("alice@example.org",101,database).prepare(1000001).xml().toXML().toString());
        int consumed = bundle.preKeys()[0];
        try (Omemo2Records tx = database.begin()) { tx.save(PRE_KEY,"",consumed,null); tx.commit(); }
        Omemo2Bundle refreshed = first.prepare(1000002); assertEquals(100,refreshed.preKeys().length);
        assertFalse(Arrays.stream(refreshed.preKeys()).anyMatch(id -> id==consumed)); assertArrayEquals(bundle.identity(),refreshed.identity());
        try (Omemo2Records tx = database.begin()) { assertNull(tx.load(PRE_PUBLIC,"",consumed)); }
    }
    @Test void signedPrekeysRotateAndRetainPriorPrivateKeysForDelayedMessages() throws Exception {
        Omemo2TestDatabase database = new Omemo2TestDatabase(); Omemo2Device device = new Omemo2Device("alice@example.org",101,database);
        Omemo2Bundle original = device.prepare(1000000), rotated = device.prepare(1000000+Omemo2Device.ROTATION);
        assertNotEquals(original.signedId,rotated.signedId); assertArrayEquals(original.identity(),rotated.identity());
        try (Omemo2Records tx = database.begin()) { assertNotNull(tx.load(SIGNED_PRE_KEY,"",original.signedId)); }
        device.prepare(1000000+Omemo2Device.ROTATION*2);
        try (Omemo2Records tx = database.begin()) { assertNull(tx.load(SIGNED_PRE_KEY,"",original.signedId)); assertNotNull(tx.load(SIGNED_PRE_KEY,"",rotated.signedId)); }
    }
    @Test void incompleteStateAndFailedCommitNeverReplaceAnExistingIdentity() throws Exception {
        Omemo2TestDatabase database = new Omemo2TestDatabase(); Omemo2Device device = new Omemo2Device("alice@example.org",101,database);
        database.failCommit = true; assertThrows(java.io.IOException.class,device::prepare); database.failCommit = false;
        try (Omemo2Records tx = database.begin()) { assertNull(tx.load(IDENTITY,"",0)); }
        device.prepare(); byte[] identity;
        try (Omemo2Records tx = database.begin()) { identity = tx.load(IDENTITY,"",0); tx.save(META,"",0,null); tx.commit(); }
        assertThrows(java.security.GeneralSecurityException.class,device::prepare);
        try (Omemo2Records tx = database.begin()) { assertArrayEquals(identity,tx.load(IDENTITY,"",0)); }
    }
    @Test void staleIdentityApprovalIsRejectedAndTrustIsNeverCopiedBetweenDevices() throws Exception {
        Omemo2TestDatabase database = new Omemo2TestDatabase(); Omemo2Device alice = new Omemo2Device("alice@example.org",101,database);
        Omemo2Bundle first = new Omemo2Device("bob@example.org",202,new Omemo2TestDatabase()).prepare();
        Omemo2Bundle changed = new Omemo2Device("bob@example.org",202,new Omemo2TestDatabase()).prepare();
        alice.observe("bob@example.org",202,first); alice.decide("bob@example.org",202,first.identity(),true);
        alice.observe("bob@example.org",202,changed);
        assertThrows(java.security.GeneralSecurityException.class,() -> alice.decide("bob@example.org",202,first.identity(),true));
        try (Omemo2Records tx = database.begin()) { assertArrayEquals(first.identity(),tx.load(TRUSTED,"bob@example.org",202)); assertNull(tx.load(TRUSTED,"bob@example.org",203)); }
        alice.decide("bob@example.org",202,first.identity(),false);
        try (Omemo2Records tx = database.begin()) { assertNull(tx.load(TRUSTED,"bob@example.org",202)); }
    }
    @Test void explicitRefusalsSurviveKeyChangesReopenAndFailedApproval() throws Exception {
        Omemo2TestDatabase database = new Omemo2TestDatabase(); Omemo2Device alice = new Omemo2Device("alice@example.org",101,database);
        String bob = "bob@example.org";
        Omemo2Bundle first = new Omemo2Device(bob,202,new Omemo2TestDatabase()).prepare();
        Omemo2Bundle changed = new Omemo2Device(bob,202,new Omemo2TestDatabase()).prepare();
        assertEquals(Omemo2Device.Trust.UNDECIDED,alice.trust(bob,202,first.identity()));
        alice.observe(bob,202,first); alice.decide(bob,202,first.identity(),false);
        alice.observe(bob,202,changed); alice.decide(bob,202,changed.identity(),false);
        Omemo2Device reopened = new Omemo2Device("alice@example.org",101,database);
        assertEquals(Omemo2Device.Trust.REJECTED,reopened.trust(bob,202,first.identity()));
        assertEquals(Omemo2Device.Trust.REJECTED,reopened.trust(bob,202,changed.identity()));
        assertEquals(Omemo2Device.Trust.UNDECIDED,reopened.trust(bob,203,first.identity()));
        assertThrows(java.security.GeneralSecurityException.class,() -> reopened.decide(bob,202,first.identity(),true));
        database.failCommit = true;
        assertThrows(java.io.IOException.class,() -> reopened.decide(bob,202,changed.identity(),true)); database.failCommit = false;
        assertEquals(Omemo2Device.Trust.REJECTED,reopened.trust(bob,202,changed.identity()));
        reopened.decide(bob,202,changed.identity(),true);
        assertEquals(Omemo2Device.Trust.TRUSTED,reopened.trust(bob,202,changed.identity()));
        assertEquals(Omemo2Device.Trust.REJECTED,reopened.trust(bob,202,first.identity()));
        reopened.decide(bob,202,changed.identity(),false);
        assertEquals(Omemo2Device.Trust.REJECTED,reopened.trust(bob,202,changed.identity()));
    }
    @Test void batchApprovalIsAtomicAndBoundToEveryDisplayedFingerprint() throws Exception {
        Omemo2TestDatabase database=new Omemo2TestDatabase(); String bob="bob@example.org",own="alice@example.org";
        Omemo2Device alice=new Omemo2Device(own,101,database);
        Omemo2Bundle contact=new Omemo2Device(bob,202,new Omemo2TestDatabase()).prepare();
        Omemo2Bundle other=new Omemo2Device(own,303,new Omemo2TestDatabase()).prepare();
        Omemo2Bundle changed=new Omemo2Device(own,303,new Omemo2TestDatabase()).prepare();
        alice.observe(bob,202,contact); alice.observe(own,303,other); alice.decide(bob,202,contact.identity(),false);
        java.util.Map<String,java.util.Map<Integer,byte[]>> displayed=new java.util.LinkedHashMap<>();
        displayed.put(bob,java.util.Collections.singletonMap(202,contact.identity())); displayed.put(own,java.util.Collections.singletonMap(303,other.identity()));
        alice.observe(own,303,changed);
        assertThrows(java.security.GeneralSecurityException.class,() -> alice.decideAll(displayed,true));
        assertEquals(Omemo2Device.Trust.REJECTED,alice.trust(bob,202,contact.identity()));
        alice.observe(own,303,other); database.failCommit=true;
        assertThrows(java.io.IOException.class,() -> alice.decideAll(displayed,true)); database.failCommit=false;
        assertEquals(Omemo2Device.Trust.REJECTED,alice.trust(bob,202,contact.identity()));
        alice.decideAll(displayed,true);
        assertEquals(Omemo2Device.Trust.TRUSTED,alice.trust(bob,202,contact.identity()));
        assertEquals(Omemo2Device.Trust.TRUSTED,alice.trust(own,303,other.identity()));
        assertEquals(Omemo2Device.Trust.UNDECIDED,alice.trust(bob,203,contact.identity()));
    }
}
