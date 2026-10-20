package com.negzero.monogram.xmpp;

import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.*;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smackx.omemo.MonogramSignalStore;
import org.jivesoftware.smackx.omemo.element.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import org.jxmpp.jid.impl.JidCreate;

class LegacyReceiveOnlyJournalTest {
    @TempDir Path directory;
    @Test void currentTrustAndJournalProvenanceFenceRecoveredPlaintextAndDiscardOldProtocolObligations() throws Exception {
        LegacyIncomingJournalTest fixture=new LegacyIncomingJournalTest();fixture.directory=directory;
        MonogramSignalStore store=fixture.store();LegacyIncomingJournal original=new LegacyIncomingJournal(store,fixture.own);
        Message wire=fixture.wire("archived");
        LegacyIncomingJournal.Context route=LegacyIncomingJournal.Context.direct(wire,fixture.own.getJid().asEntityBareJidOrThrow(),false,null,null);
        Message completion=new Message(fixture.peer.getJid());completion.setStanzaId("old-completion");
        completion.addExtension(new OmemoElement_VAxolotl(new OmemoHeaderElement_VAxolotl(101,Collections.singletonList(new OmemoKeyElement(new byte[]{7},202,false)),new byte[16]),null));
        try(LegacyOmemoRecords tx=store.begin(fixture.own)) {
            store.withTransaction(fixture.own,tx,() -> original.append(tx,route,original.digest(route),fixture.clear(wire,"Recovered"),completion,true));tx.commit();
        }
        IdentityTrust trust=new IdentityTrust(directory.resolve("current-trust.properties").toFile());
        trust.setTrust(fixture.peer,fixture.clear(wire,"x").getSendersFingerprint(),true);
        LegacyIncomingJournal recovery=new LegacyIncomingJournal(store,fixture.own,trust);
        LegacyIncomingJournal.Received saved=recovery.pending().get(0);
        assertEquals("Recovered",saved.message.body);assertNull(saved.completion);assertFalse(saved.replenish);assertFalse(saved.receiptEligible());assertSame(recovery,saved.journal);
        assertFalse(original.accept(saved),"A copied epoch must not authorize another journal's frame");
        trust.setTrust(fixture.peer,fixture.clear(wire,"x").getSendersFingerprint(),false);
        assertThrows(GeneralSecurityException.class,recovery::pending);assertThrows(GeneralSecurityException.class,() -> recovery.accept(saved));
        trust.setTrust(fixture.peer,fixture.clear(wire,"x").getSendersFingerprint(),true);
        assertTrue(recovery.accept(saved));assertTrue(recovery.pending().isEmpty(),"Accepting archive content must erase obsolete completion/publication obligations");
    }
    @Test void savedRoomMarkerTagsSurviveReceiveOnlyReplayWithoutResolvingCurrentOccupants() throws Exception {
        LegacyIncomingJournalTest fixture=new LegacyIncomingJournalTest();fixture.directory=directory;MonogramSignalStore store=fixture.store();
        Message wire=fixture.wire("room-source");wire.setType(Message.Type.groupchat);
        wire.setFrom(JidCreate.entityFullFrom("friends@conference.example.org/OldNick"));wire.setTo(JidCreate.entityBareFrom("friends@conference.example.org"));
        XmppRoomMessage snapshot=XmppRoomMessage.archived(wire,JidCreate.entityBareFrom("friends@conference.example.org"),fixture.own.getJid().asEntityBareJidOrThrow(),"Alice",null,true,false,"mam-id",new Date(100));
        Map<String,String> ids=new LinkedHashMap<>(snapshot.ids);ids.put(RoomDisplayedMarker.LOCAL_ID,"historical-room-id");
        snapshot=snapshot.withAliases(ids);
        LegacyIncomingJournal original=new LegacyIncomingJournal(store,fixture.own);
        fixture.append(store,original,LegacyIncomingJournal.Context.room(wire,fixture.peer.getJid().toString(),"Alice",snapshot,false),"Saved room body");
        IdentityTrust trust=new IdentityTrust(directory.resolve("current-trust.properties").toFile());trust.setTrust(fixture.peer,fixture.clear(wire,"x").getSendersFingerprint(),true);
        LegacyIncomingJournal.Received recovered=new LegacyIncomingJournal(store,fixture.own,trust).pending().get(0);
        assertEquals("historical-room-id",recovered.roomMessage.ids.get(RoomDisplayedMarker.LOCAL_ID));assertEquals("Saved room body",recovered.roomMessage.body);
    }
}
