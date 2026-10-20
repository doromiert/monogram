package com.negzero.monogram.xmpp;

import static org.junit.jupiter.api.Assertions.*;
import java.io.File;
import java.util.Date;
import java.util.concurrent.*;
import org.jxmpp.jid.EntityBareJid;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="MONOGRAM_IT",matches="1")
class ArchiveDateIntegrationTest {
    private final File root=new File(System.getenv("MONOGRAM_IT_STATE"));
    private XmppSession session(String user,int device,BlockingQueue<String> direct,BlockingQueue<String> room) throws Exception {
        return new XmppSession(new Account(user+"@localhost","127.0.0.1",Integer.parseInt(System.getenv("MONOGRAM_IT_PORT")),"Calendar-"+device),
                "monogram-test",device,new File(root,"calendar-trust-"+device),new XmppSession.Listener() {
            public void message(EntityBareJid from,String id,String body,boolean encrypted) { direct.add(body); }
            public void roomMessage(XmppRoomMessage message) { if(message.readable && message.body!=null) room.add(message.body); }
        });
    }
    private void received(BlockingQueue<String> queue,String body) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<until) if(body.equals(queue.poll(100,TimeUnit.MILLISECONDS))) return;
        fail("Expected fixture delivery: "+body);
    }
    @Test void calendarQueriesDirectAndRoomArchivesFromSelectedDateAndKeepCursorPaging() throws Exception {
        XmppSession.initializeOmemo(new File(root,"keys"),LegacyOmemoTestDatabase.shared());
        BlockingQueue<String> direct=new LinkedBlockingQueue<>(),room=new LinkedBlockingQueue<>();
        try(XmppSession alice=session("callsalice",2101,new LinkedBlockingQueue<>(),room);
                XmppSession bob=session("callsbob",2102,direct,new LinkedBlockingQueue<>())) {
            alice.connect();bob.connect();
            alice.send("callsbob@localhost","Before calendar",false);received(direct,"Before calendar");
            Thread.sleep(20);Date boundary=new Date();Thread.sleep(20);
            alice.send("callsbob@localhost","After calendar",false);received(direct,"After calendar");
            XmppSession.ArchivePage after=alice.archiveSince("callsbob@localhost",boundary,50);
            assertEquals(1,after.messages.size());assertEquals("After calendar",after.messages.get(0).body);
            assertTrue(after.messages.get(0).timestamp>=boundary.getTime());assertNotNull(after.firstId);after.stored();
            XmppSession.ArchivePage before=alice.archiveBefore("callsbob@localhost",after.firstId,50);
            assertEquals(1,before.messages.size());assertEquals("Before calendar",before.messages.get(0).body);before.stored();
            assertTrue(alice.archiveSince("callsbob@localhost",new Date(System.currentTimeMillis()+60000),50).messages.isEmpty());

            String address="calendar@conference.localhost";
            alice.rooms.create(address,"Alice","Calendar fixture");
            alice.rooms.send(address,"Room before calendar",false);received(room,"Room before calendar");
            Thread.sleep(20);Date roomBoundary=new Date();Thread.sleep(20);
            alice.rooms.send(address,"Room after calendar",false);received(room,"Room after calendar");
            XmppRooms.ArchivePage roomAfter=alice.rooms.archiveSince(address,roomBoundary,50);
            assertEquals(1,roomAfter.messages.size());assertEquals("Room after calendar",roomAfter.messages.get(0).body);
            assertNotNull(roomAfter.firstId);roomAfter.stored();
            XmppRooms.ArchivePage roomBefore=alice.rooms.archive(address,roomAfter.firstId,null,50);
            assertEquals(1,roomBefore.messages.size());assertEquals("Room before calendar",roomBefore.messages.get(0).body);roomBefore.stored();
            assertTrue(alice.rooms.archiveSince(address,new Date(System.currentTimeMillis()+60000),50).messages.isEmpty());
        }
    }
}
