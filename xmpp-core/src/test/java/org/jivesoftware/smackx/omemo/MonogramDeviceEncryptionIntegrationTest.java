package org.jivesoftware.smackx.omemo;

import com.negzero.monogram.xmpp.Account;
import com.negzero.monogram.xmpp.XmppSession;
import java.io.File;
import java.util.Collections;
import java.util.concurrent.*;
import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smackx.omemo.element.OmemoBundleElement;
import org.jivesoftware.smackx.omemo.exceptions.NoIdentityKeyException;
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;
import org.jivesoftware.smackx.omemo.signal.SignalCachingOmemoStore;
import org.jivesoftware.smackx.omemo.trust.OmemoFingerprint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MONOGRAM_IT",matches="1")
class MonogramDeviceEncryptionIntegrationTest {
    final File root=new File(System.getenv("MONOGRAM_IT_STATE"));
    XmppSession session(String user,int device) throws Exception {
        return new XmppSession(new Account("session"+user+"@localhost","127.0.0.1",Integer.parseInt(System.getenv("MONOGRAM_IT_PORT")),"Bridge-"+device),
                "monogram-test",device,new File(root,"bridge-trust-"+device),(from,id,body,encrypted) -> {});
    }
    @Test void coldLocalApplyEncryptsWithoutIqAndNeverResetsSessionLearnedDuringFetch() throws Exception {
        XmppSession.initializeOmemo(new File(root,"keys"));
        ExecutorService learner=Executors.newSingleThreadExecutor();
        ThreadLocal<Boolean> localOnly=ThreadLocal.withInitial(() -> false);
        try(XmppSession alice=session("alice",5901);XmppSession bob=session("bob",5902)) {
            alice.connect();bob.connect();
            OmemoManager manager=OmemoManager.getInstanceFor(alice.connection(),5901);
            OmemoManager receiver=OmemoManager.getInstanceFor(bob.connection(),5902);
            OmemoDevice device=receiver.getOwnDevice();
            SignalCachingOmemoStore store=(SignalCachingOmemoStore)manager.getOmemoService().getOmemoStoreBackend();
            alice.connection().addStanzaInterceptor(stanza -> {
                assertFalse(localOnly.get(),"Local apply/fingerprint must not fall back to IQ lookups");
                if(stanza.getTo()!=null && stanza.getTo().asBareJid().equals(device.getJid())
                        && stanza.toXML().toString().contains(device.getBundleNodeName()))
                    assertFalse(Thread.holdsLock(manager),"Bundle network fetch must not hold the manager monitor");
            },stanza -> stanza instanceof IQ);
            assertTrue(MonogramDeviceEncryption.needsSession(manager,device));
            OmemoBundleElement fetched=MonogramDeviceEncryption.fetchBundle(alice.connection(),device);
            assertNotNull(fetched);assertTrue(MonogramDeviceEncryption.needsSession(manager,device),"Fetching cannot install a session");
            OmemoFingerprint fingerprint=learner.submit(() -> {
                localOnly.set(true);
                try { return MonogramDeviceEncryption.prepare(manager,device,fetched); }
                finally { localOnly.remove(); }
            }).get(5,TimeUnit.SECONDS);
            assertNotNull(fingerprint);
            byte[] learned=store.loadRawSession(manager.getOwnDevice(),device).serialize();
            localOnly.set(true);
            assertFalse(MonogramDeviceEncryption.needsSession(manager,device));
            assertEquals(fingerprint,MonogramDeviceEncryption.prepare(manager,device,null));
            assertArrayEquals(learned,store.loadRawSession(manager.getOwnDevice(),device).serialize(),"Late bundle application must preserve the learned ratchet");
            alice.decide(device,fingerprint,true);
            OmemoMessage.Sent ciphertext=MonogramDeviceEncryption.encrypt(manager,Collections.singleton(device),"Cold bridge message");
            assertTrue(ciphertext.getSkippedDevices().isEmpty());
            localOnly.remove();
            assertEquals("Cold bridge message",receiver.decrypt(manager.getOwnJid(),ciphertext.getElement()).getBody());

            // An existing raw session with no identity must fail closed, rather than invoke manager.getFingerprint's network fallback.
            OmemoDevice orphan=new OmemoDevice(device.getJid(),5903);
            store.storeRawSession(manager.getOwnDevice(),orphan,store.loadRawSession(manager.getOwnDevice(),device));
            localOnly.set(true);
            assertFalse(MonogramDeviceEncryption.needsSession(manager,orphan));
            assertThrows(NoIdentityKeyException.class,() -> MonogramDeviceEncryption.prepare(manager,orphan,null));
            localOnly.remove();
            store.removeRawSession(manager.getOwnDevice(),orphan);
        } finally { localOnly.remove();learner.shutdownNow(); }
    }
}
