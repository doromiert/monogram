// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.jivesoftware.smack.XMPPException.XMPPErrorException;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smackx.blocking.BlockingCommandManager;
import org.jivesoftware.smackx.blocking.element.*;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MONOGRAM_IT",matches="1")
class XmppBlockingIntegrationTest {
    final File root=new File(System.getenv("MONOGRAM_IT_STATE"));
    XmppSession session(String user,int device,BlockingQueue<List<String>> changes,BlockingQueue<String> messages) throws Exception {
        return new XmppSession(new Account(user+"@localhost","127.0.0.1",Integer.parseInt(System.getenv("MONOGRAM_IT_PORT")),"Blocking-"+device),
                "monogram-test",device,new File(root,"blocking-trust-"+device),new XmppSession.Listener() {
            public void message(org.jxmpp.jid.EntityBareJid from,String id,String body,boolean encrypted) { messages.add(body); }
            public void blockedChanged(List<String> addresses) { changes.add(addresses); }
        });
    }
    void await(BooleanSupplier ready) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(!ready.getAsBoolean() && System.nanoTime()<end) Thread.sleep(20);
        assertTrue(ready.getAsBoolean());
    }
    @Test void acknowledgedBlockingPersistsAndPushesAcrossResourcesWithoutAcceptingForgedUpdates() throws Exception {
        XmppSession.initializeOmemo(new File(root,"keys"),LegacyOmemoTestDatabase.shared());
        BlockingQueue<List<String>> changes=new LinkedBlockingQueue<>(),otherChanges=new LinkedBlockingQueue<>();
        BlockingQueue<String> messages=new LinkedBlockingQueue<>();
        String bob="blockingbob@localhost",domain="blocked.example",full=bob+"/specific device ";
        try(XmppSession alice=session("blockingalice",1901,changes,messages);
                XmppSession other=session("blockingalice",1902,otherChanges,new LinkedBlockingQueue<>());
                XmppSession sender=session("blockingbob",1903,new LinkedBlockingQueue<>(),new LinkedBlockingQueue<>())) {
            alice.connect(); other.connect(); sender.connect();
            assertNull(alice.blocking.snapshot());
            assertTrue(BlockingCommandManager.getInstanceFor(alice.connection()).isSupportedByServer());
            assertEquals(Collections.emptyList(),alice.blocking.list());
            assertEquals(Collections.emptyList(),other.blocking.list());
            List<String> blocked=alice.blocking.block(bob);
            assertEquals(Collections.singletonList(bob),blocked);
            assertThrows(UnsupportedOperationException.class,() -> blocked.add("mutated.example"));
            await(() -> Collections.singletonList(bob).equals(other.blocking.snapshot()));
            List<String> push=otherChanges.poll(10,TimeUnit.SECONDS);
            while(push!=null && push.isEmpty()) push=otherChanges.poll(10,TimeUnit.SECONDS);
            assertEquals(Collections.singletonList(bob),push);
            final List<String> immutablePush=push;
            assertThrows(UnsupportedOperationException.class,() -> immutablePush.clear());

            sender.send("blockingalice@localhost","blocked-message",false);
            sender.send("blockingalice@localhost","blocked-message-2",false);
            assertNull(messages.poll(350,TimeUnit.MILLISECONDS));
            assertEquals(Collections.emptyList(),alice.blocking.unblock(bob));
            await(() -> Collections.emptyList().equals(other.blocking.snapshot()));
            sender.send("blockingalice@localhost","allowed-message",false);
            assertEquals("allowed-message",messages.poll(10,TimeUnit.SECONDS));

            alice.blocking.block(domain); alice.blocking.block(full);
            assertEquals(new TreeSet<>(Arrays.asList(domain,full)),new TreeSet<>(alice.blocking.list()));
            await(() -> other.blocking.snapshot()!=null && other.blocking.snapshot().contains(full));
            alice.blocking.unblock(domain);
            assertEquals(Collections.singletonList(full),alice.blocking.list());
            assertEquals(Collections.emptyList(),alice.blocking.unblock(full),"Resource whitespace must survive unblock as well as block");
            assertEquals(Collections.singletonList(full),alice.blocking.block(full));

            UnblockContactsIQ forged=new UnblockContactsIQ(); forged.setTo(alice.connection().getUser());
            XMPPErrorException denied=assertThrows(XMPPErrorException.class,() -> sender.connection().createStanzaCollectorAndSend(forged).nextResultOrThrow());
            assertEquals(StanzaError.Condition.not_allowed,denied.getStanzaError().getCondition());
            assertEquals(Collections.singletonList(full),alice.blocking.snapshot());
            BlockContactsIQ forgedBlock=new BlockContactsIQ(Collections.singletonList(JidCreate.from("forged.example")));
            forgedBlock.setTo(alice.connection().getUser());
            assertThrows(XMPPErrorException.class,() -> sender.connection().createStanzaCollectorAndSend(forgedBlock).nextResultOrThrow());
            assertEquals(Collections.singletonList(full),alice.blocking.snapshot());

            // A fresh resource must obtain the server's durable list, rather than an app-local flag.
            try(XmppSession fresh=session("blockingalice",1904,new LinkedBlockingQueue<>(),new LinkedBlockingQueue<>())) {
                fresh.connect(); assertEquals(Collections.singletonList(full),fresh.blocking.list());
            }
            BlockingCommandManager.getInstanceFor(other.connection()).unblockAll();
            await(() -> Collections.emptyList().equals(alice.blocking.snapshot()));
            assertEquals(Collections.emptyList(),alice.blocking.list());
            assertThrows(IllegalArgumentException.class,() -> alice.blocking.block(""));
            assertThrows(IllegalArgumentException.class,() -> alice.blocking.unblock("\n"));
        }
    }
}
