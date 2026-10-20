package org.jivesoftware.smackx.omemo;

import com.negzero.monogram.xmpp.Account;
import com.negzero.monogram.xmpp.OmemoSendException;
import com.negzero.monogram.xmpp.XmppSession;
import java.io.File;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smackx.omemo.element.OmemoDeviceListElement_VAxolotl;
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;
import org.jivesoftware.smackx.omemo.signal.SignalOmemoService;
import org.jxmpp.jid.EntityBareJid;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MONOGRAM_IT",matches="1")
class MonogramDeviceListsSessionIntegrationTest {
    final File root=new File(System.getenv("MONOGRAM_IT_STATE"));
    XmppSession session(String user,int device) throws Exception {
        return new XmppSession(new Account("session"+user+"@localhost","127.0.0.1",Integer.parseInt(System.getenv("MONOGRAM_IT_PORT")),"ListSession-"+device),
                "monogram-test",device,new File(root,"list-session-trust-"+device),(from,id,body,encrypted) -> {});
    }
    @Test void remoteSessionRefreshDoesNotHoldManagerMonitorOrOverwriteNewerPepList() throws Exception {
        XmppSession.initializeOmemo(new File(root,"keys"),com.negzero.monogram.xmpp.LegacyOmemoTestDatabase.shared());
        try(XmppSession alice=session("alice",6801);XmppSession bob=session("bob",6802)) {
            alice.connect();bob.connect();
            OmemoManager manager=OmemoManager.getInstanceFor(alice.connection(),6801);
            MonogramDeviceLists store=(MonogramDeviceLists)SignalOmemoService.getInstance().getOmemoStoreBackend();
            EntityBareJid contact=JidCreate.entityBareFrom("sessionbob@localhost");
            AtomicBoolean updateDuringFetch=new AtomicBoolean();AtomicInteger requests=new AtomicInteger();
            Thread caller=Thread.currentThread();
            alice.connection().addStanzaInterceptor(stanza -> {
                if(Thread.currentThread()!=caller || stanza.getTo()==null || !contact.equals(stanza.getTo().asBareJid())
                        || !stanza.toXML().toString().contains("eu.siacs.conversations.axolotl.devicelist")) return;
                assertFalse(Thread.holdsLock(manager),"Remote device-list IQ must not hold the encryption monitor");requests.incrementAndGet();
                if(updateDuringFetch.compareAndSet(true,false)) {
                    try { store.mergeCachedDeviceList(manager.getOwnDevice(),contact,new OmemoDeviceListElement_VAxolotl(Collections.singleton(6899))); }
                    catch(Exception error) { throw new AssertionError(error); }
                }
            },stanza -> stanza instanceof IQ);
            Set<OmemoDevice> advertised=alice.devices(contact.toString());
            assertTrue(advertised.contains(new OmemoDevice(contact,6802)));assertTrue(requests.get()>0);
            updateDuringFetch.set(true);
            OmemoSendException changed=assertThrows(OmemoSendException.class,() -> alice.devices(contact.toString()));
            assertEquals(OmemoSendException.Reason.ROOM_CHANGED,changed.reason);
            assertFalse(updateDuringFetch.get(),"The PEP update must occur after snapshot, while fetch is in flight");
            assertEquals(Collections.singleton(new OmemoDevice(contact,6899)),manager.getDevicesOf(contact));
            assertEquals(advertised,alice.devices(contact.toString()),"A new explicit refresh may apply against the updated revision");
        }
    }
}
