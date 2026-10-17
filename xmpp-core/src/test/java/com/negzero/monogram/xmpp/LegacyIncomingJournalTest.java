package com.negzero.monogram.xmpp;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.*;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.StandardExtensionElement;
import org.jivesoftware.smack.provider.ProviderManager;
import org.jivesoftware.smackx.omemo.*;
import org.jivesoftware.smackx.omemo.element.*;
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;
import org.jivesoftware.smackx.omemo.signal.*;
import org.jivesoftware.smackx.omemo.trust.OmemoFingerprint;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.whispersystems.libsignal.state.SessionRecord;

class LegacyIncomingJournalTest {
    @TempDir Path directory;
    final LegacyOmemoTestDatabase database=new LegacyOmemoTestDatabase();
    final OmemoDevice own=new OmemoDevice(JidCreate.bareFromOrThrowUnchecked("alice@example.org"),101);
    final OmemoDevice peer=new OmemoDevice(JidCreate.bareFromOrThrowUnchecked("bob@example.org"),202);
    MonogramSignalStore store() throws Exception {
        ProviderManager.addExtensionProvider("x","http://jabber.org/protocol/muc#user",new org.jivesoftware.smackx.muc.provider.MUCUserProvider());
        ProviderManager.addExtensionProvider("encrypted",OmemoElement_VAxolotl.NAMESPACE,new org.jivesoftware.smackx.omemo.provider.OmemoVAxolotlProvider());
        MonogramSignalStore store=new MonogramSignalStore(new SignalFileBasedOmemoStore(directory.toFile()),database);
        if(store.loadOmemoIdentityKeyPair(own)==null) store.storeOmemoIdentityKeyPair(own,new SignalOmemoKeyUtil().generateOmemoIdentityKeyPair());return store;
    }
    Message wire(String id) throws Exception {
        Message wire=new Message(own.getJid(),Message.Type.chat);wire.setFrom(JidCreate.entityFullFrom("bob@example.org/phone"));wire.setStanzaId(id);
        wire.addExtension(new OmemoElement_VAxolotl(new OmemoHeaderElement_VAxolotl(202,Collections.singletonList(new OmemoKeyElement(new byte[]{1,2,3},101,true)),new byte[16]),new byte[]{4,5,6}));return wire;
    }
    OmemoMessage.Received clear(Message wire,String body) throws Exception {
        Constructor<OmemoMessage.Received> constructor=OmemoMessage.Received.class.getDeclaredConstructor(OmemoElement.class,byte[].class,byte[].class,String.class,OmemoFingerprint.class,OmemoDevice.class,boolean.class);
        constructor.setAccessible(true);
        return constructor.newInstance(wire.getExtension("encrypted",OmemoElement_VAxolotl.NAMESPACE),new byte[16],new byte[16],body,new OmemoFingerprint("11223344556677889900112233445566778899001122334455667788990011223344"),peer,true);
    }
    LegacyIncomingJournal.Received append(MonogramSignalStore store,LegacyIncomingJournal journal,LegacyIncomingJournal.Context context,String body) throws Exception {
        try(LegacyOmemoRecords tx=store.begin(own)) {
            LegacyIncomingJournal.Received result=store.withTransaction(own,tx,() -> journal.append(tx,context,journal.digest(context),clear(context.outer,body)));tx.commit();return result;
        }
    }
    @Test void canonicalIdlessBodyReplyAndStylingSurviveReopenWithFreshArchiveAliasAndEpochFence() throws Exception {
        MonogramSignalStore store=store();LegacyIncomingJournal journal=new LegacyIncomingJournal(store,own);Message wire=wire(null);
        ReplyReference reply=new ReplyReference(peer.getJid().toString(),"earlier");reply.addTo(wire);
        String body=reply.wireBody("*Private 😀 body*");
        LegacyIncomingJournal.Context context=LegacyIncomingJournal.Context.direct(wire,own.getJid().asEntityBareJidOrThrow(),true,null,null);
        LegacyIncomingJournal.Received original=append(store,journal,context,body);
        assertNotNull(original.message.ids.get("local"));assertNotNull(original.message.ids.get("legacy-cipher"));assertEquals("earlier",original.message.reply.id);
        LegacyIncomingJournal reopened=new LegacyIncomingJournal(store(),own);LegacyIncomingJournal.Received replay=reopened.pending().get(0);
        assertEquals(original.message.ids,replay.message.ids);assertEquals(original.message.timestamp,replay.message.timestamp);
        assertEquals(body,replay.message.body);assertEquals(original.message.markup.display().body,replay.message.markup.display().body);assertFalse(replay.context.live);assertFalse(replay.receiptEligible());
        Message changed=wire(null);changed.setFrom(JidCreate.entityFullFrom("bob@example.org/new-resource"));reply.addTo(changed);
        LegacyIncomingJournal.Context mam=LegacyIncomingJournal.Context.direct(changed,own.getJid().asEntityBareJidOrThrow(),false,"fresh-mam",new Date(123456789));
        try(LegacyOmemoRecords tx=store.begin(own)) {
            LegacyIncomingJournal.Received cached=store.withTransaction(own,tx,() -> reopened.find(tx,mam,reopened.digest(mam)));
            assertNotNull(cached);assertEquals("fresh-mam",cached.message.ids.get("archive"));assertEquals(original.message.ids.get("local"),cached.message.ids.get("local"));assertEquals(123456789,cached.message.timestamp);
        }
        try(LegacyOmemoRecords tx=store.begin(own)) { LegacyIncomingJournal.rotate(tx);tx.commit(); }
        assertFalse(reopened.accept(original));assertTrue(reopened.pending().isEmpty());
    }
    @Test void nativeRatchetMutationAndJournalAppendRollbackTogetherOnCommitFailure() throws Exception {
        MonogramSignalStore store=store();LegacyIncomingJournal journal=new LegacyIncomingJournal(store,own);Message wire=wire("rollback");
        LegacyIncomingJournal.Context context=LegacyIncomingJournal.Context.direct(wire,own.getJid().asEntityBareJidOrThrow(),false,null,null);
        SessionRecord next=new SessionRecord();next.getSessionState().setLocalRegistrationId(77);
        try(LegacyOmemoRecords tx=store.begin(own)) {
            store.withTransaction(own,tx,() -> { store.storeRawSession(own,peer,next);journal.append(tx,context,journal.digest(context),clear(wire,"Keep this"));return null; });
            database.failNextCommit=true;assertThrows(IOException.class,tx::commit);
        }
        assertNull(store.loadRawSession(own,peer));assertTrue(journal.pending().isEmpty());
        LegacyIncomingJournal.Received result=append(store,journal,context,"Keep this");assertTrue(journal.accept(result));assertFalse(journal.accept(result));assertTrue(journal.pending().isEmpty());
    }
    @Test void roomSnapshotRetainsVerifiedHistoricalSenderOccupantAndMamWithoutCurrentNicknameLookup() throws Exception {
        MonogramSignalStore store=store();LegacyIncomingJournal journal=new LegacyIncomingJournal(store,own);Message wire=wire("room-id");
        wire.setType(Message.Type.groupchat);wire.setFrom(JidCreate.entityFullFrom("friends@conference.example.org/OldNick"));
        wire.setTo(JidCreate.entityBareFrom("friends@conference.example.org"));
        wire.addExtension(StandardExtensionElement.builder("x","http://jabber.org/protocol/muc#user").addElement(StandardExtensionElement.builder("item","http://jabber.org/protocol/muc#user").addAttribute("jid",peer.getJid().toString()).build()).build());
        wire=org.jivesoftware.smack.util.PacketParserUtils.parseStanza(wire.toXML().toString());
        XmppRoomMessage snapshot=XmppRoomMessage.archived(wire,JidCreate.entityBareFrom("friends@conference.example.org"),own.getJid().asEntityBareJidOrThrow(),"Alice",null,true,false,"mam-old",new Date(100));
        snapshot=snapshot.canonical(snapshot.ids,100,true,false,peer.getJid().toString(),true,"occupant-stable");
        LegacyIncomingJournal.Context context=LegacyIncomingJournal.Context.room(wire,peer.getJid().toString(),"Alice",snapshot,false);
        LegacyIncomingJournal.Received original=append(store,journal,context,"Private room body");
        LegacyIncomingJournal.Received recovered=new LegacyIncomingJournal(store(),own).pending().get(0);
        assertEquals(original.roomMessage.ids,recovered.roomMessage.ids);assertEquals("mam-old",recovered.roomMessage.ids.get("mam"));
        assertEquals("friends@conference.example.org/OldNick",recovered.roomMessage.sender);assertEquals(peer.getJid().toString(),recovered.roomMessage.realSender);
        assertEquals("occupant-stable",recovered.roomMessage.occupantId);assertTrue(recovered.roomMessage.realSenderVerified);assertFalse(recovered.roomMessage.outgoing);assertTrue(recovered.roomMessage.archived);
    }
    @Test void fullJournalRollsBackNewRatchetAndCannotEvictAuthenticatedPendingContent() throws Exception {
        MonogramSignalStore store=store();LegacyIncomingJournal journal=new LegacyIncomingJournal(store,own);
        for(int i=0;i<LegacyIncomingJournal.MAX_ENTRIES;i++) {
            Message wire=wire("pending-"+i);wire.removeExtension("encrypted",OmemoElement_VAxolotl.NAMESPACE);
            wire.addExtension(new OmemoElement_VAxolotl(new OmemoHeaderElement_VAxolotl(202,Collections.singletonList(new OmemoKeyElement(new byte[]{1,2,3},101,true)),new byte[16]),new byte[]{(byte)i,5,6}));
            append(store,journal,LegacyIncomingJournal.Context.direct(wire,own.getJid().asEntityBareJidOrThrow(),false,null,null),"Pending "+i);
        }
        Message wire=wire("overflow");LegacyIncomingJournal.Context context=LegacyIncomingJournal.Context.direct(wire,own.getJid().asEntityBareJidOrThrow(),false,null,null);
        try(LegacyOmemoRecords tx=store.begin(own)) {
            assertThrows(LegacyIncomingJournal.FullException.class,() -> store.withTransaction(own,tx,() -> {
                store.storeRawSession(own,peer,new SessionRecord());return journal.append(tx,context,journal.digest(context),clear(wire,"Cannot evict"));
            }));
        }
        assertNull(store.loadRawSession(own,peer));assertEquals(LegacyIncomingJournal.MAX_ENTRIES,journal.pending().size());
    }
    @Test void acceptedContentFreesPlaintextButRetainsStableProtocolUntilEachDurableObligationCompletes() throws Exception {
        MonogramSignalStore store=store();LegacyIncomingJournal journal=new LegacyIncomingJournal(store,own);Message wire=wire("accepted-ack");
        LegacyIncomingJournal.Context context=LegacyIncomingJournal.Context.direct(wire,own.getJid().asEntityBareJidOrThrow(),true,null,null);
        Message completion=new Message(peer.getJid());completion.setStanzaId("stable-completion-id");
        completion.addExtension(new OmemoElement_VAxolotl(new OmemoHeaderElement_VAxolotl(101,Collections.singletonList(new OmemoKeyElement(new byte[]{7,8,9},202)),new byte[12]),null));
        LegacyIncomingJournal.Received original;
        try(LegacyOmemoRecords tx=store.begin(own)) {
            original=store.withTransaction(own,tx,() -> journal.append(tx,context,journal.digest(context),clear(wire,"Secret no longer retained"),completion,true));tx.commit();
        }
        assertNull(journal.protocol(original),"Protocol response cannot be sent before storage acceptance");
        assertTrue(journal.accept(original));LegacyIncomingJournal.Received pending=journal.pending().get(0);
        assertNull(pending.message);assertNull(pending.roomMessage);assertTrue(pending.replenish);assertEquals(completion.toXML().toString(),pending.completion.toXML().toString());
        try(LegacyOmemoRecords tx=store.begin(own)) {
            String raw=new String(tx.load(LegacyOmemoRecords.INCOMING,"",1),java.nio.charset.StandardCharsets.UTF_8);
            assertFalse(raw.contains("Secret no longer retained"));assertFalse(raw.contains("accepted-ack"));
        }
        database.failNextCommit=true;assertThrows(IOException.class,() -> journal.completed(pending,true,false));
        assertEquals("stable-completion-id",journal.pending().get(0).completion.getStanzaId(),"Failed cleanup must replay identical ciphertext/id");
        assertTrue(journal.completed(pending,true,false));assertNull(journal.pending().get(0).completion);assertTrue(journal.pending().get(0).replenish);
        assertTrue(journal.completed(pending,false,true));assertTrue(journal.pending().isEmpty());
    }

    @Test void oneConfirmedPublicationClearsOnlySnapshotObligationsIncludingUnacceptedBodies() throws Exception {
        MonogramSignalStore store=store();LegacyIncomingJournal journal=new LegacyIncomingJournal(store,own);Message first=wire("before-publication");
        LegacyIncomingJournal.Context old=LegacyIncomingJournal.Context.direct(first,own.getJid().asEntityBareJidOrThrow(),false,null,null);
        try(LegacyOmemoRecords tx=store.begin(own)) { store.withTransaction(own,tx,() -> journal.append(tx,old,journal.digest(old),clear(first,"Before"),null,true));tx.commit(); }
        List<LegacyIncomingJournal.Received> snapshot=journal.pending();Message later=wire("after-publication-snapshot");
        later.removeExtension("encrypted",OmemoElement_VAxolotl.NAMESPACE);later.addExtension(new OmemoElement_VAxolotl(new OmemoHeaderElement_VAxolotl(202,Collections.singletonList(new OmemoKeyElement(new byte[]{1,2,3},101,true)),new byte[16]),new byte[]{9,5,6}));
        LegacyIncomingJournal.Context fresh=LegacyIncomingJournal.Context.direct(later,own.getJid().asEntityBareJidOrThrow(),false,null,null);
        try(LegacyOmemoRecords tx=store.begin(own)) { store.withTransaction(own,tx,() -> journal.append(tx,fresh,journal.digest(fresh),clear(later,"After"),null,true));tx.commit(); }
        assertTrue(journal.replenished(snapshot));List<LegacyIncomingJournal.Received> pending=journal.pending();assertEquals(2,pending.size());
        assertFalse(pending.get(0).replenish);assertEquals("Before",pending.get(0).message.body);
        assertTrue(pending.get(1).replenish);assertEquals("After",pending.get(1).message.body);
    }

}
