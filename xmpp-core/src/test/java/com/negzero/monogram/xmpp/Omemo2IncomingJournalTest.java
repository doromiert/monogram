package com.negzero.monogram.xmpp;

import java.util.*;
import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.negzero.monogram.xmpp.Omemo2Records.*;

class Omemo2IncomingJournalTest {
    final String own="bob@example.org",peer="alice@example.org",fingerprint=String.join("",Collections.nCopies(64,"a"));
    Omemo2TestDatabase database;Omemo2Device device;Omemo2IncomingJournal journal;
    @BeforeEach void setup() throws Exception {
        database=new Omemo2TestDatabase();device=new Omemo2Device(own,202,database);
        try(Omemo2Records tx=database.begin()) { tx.save(IDENTITY,"",1,new byte[32]);tx.commit(); }
        journal=new Omemo2IncomingJournal(device);
        FileShare.initialize();MessageMarkup.initialize();MessageCorrection.initialize();MessageReactions.initialize();MessageRetraction.initialize();
    }
    Message outer(String id,int sequence) throws Exception {
        Message message=new Message(JidCreate.entityFullFrom(own+"/phone"),Message.Type.chat);
        message.setFrom(JidCreate.entityFullFrom(peer+"/laptop"));message.setStanzaId(id);message.setBody("Server fallback");
        message.addExtension(StandardExtensionElement.builder("encrypted",XmppOmemo2.NS)
                .addElement(StandardExtensionElement.builder("header",XmppOmemo2.NS).addAttribute("sid","101")
                        .addElement(StandardExtensionElement.builder("keys",XmppOmemo2.NS).addAttribute("jid",own)
                                .addElement(StandardExtensionElement.builder("key",XmppOmemo2.NS).addAttribute("rid","202").addAttribute("kex","true").setText("a2V5"+sequence).build()).build()).build())
                .addElement("payload",Base64.getEncoder().encodeToString(new byte[16])).build());
        if(id!=null) message.addExtension(StandardExtensionElement.builder("markable",MessageReceipt.MARKERS).build());
        return message;
    }
    Message clear(Message outer,String body) {
        Message clear=new Message(outer.getTo(),outer.getType());clear.setFrom(outer.getFrom());clear.setStanzaId(outer.getStanzaId());clear.setBody(body);return clear;
    }
    Message ack() throws Exception {
        Message ack=new Message(JidCreate.entityFullFrom(peer+"/laptop"),Message.Type.chat);ack.setFrom(JidCreate.entityBareFrom(own));
        ack.addExtension(StandardExtensionElement.builder("encrypted",XmppOmemo2.NS).addElement("header","fixture").build());return ack;
    }
    XmppOmemo2.Received append(Omemo2Records tx,Message outer,Message content,Message ack) throws Exception {
        XmppMessage model=XmppMessage.modern(outer,content,JidCreate.entityBareFrom(own),null,new Date(1234567)).withSenderFingerprint(fingerprint);
        return journal.append(tx,Omemo2IncomingJournal.envelope(outer).digest,outer,content,ack,model,fingerprint,true);
    }
    XmppOmemo2.Received save(Message outer,Message content,Message ack) throws Exception {
        try(Omemo2Records tx=database.begin()) { XmppOmemo2.Received received=append(tx,outer,content,ack);tx.commit();return received; }
    }
    @Test void reopenRestoresCanonicalOpaqueIdsTimestampAndProtectedMarkupThenAddsMamAlias() throws Exception {
        Message outer=outer(null,1);MessageMarkup markup=new MessageMarkup("Private 😀",Collections.singletonList(new MessageMarkup.Range(0,9,Collections.singleton(MessageMarkup.Style.STRONG),null))).styled();
        Message clear=clear(outer,markup.body);markup.addTo(clear);
        XmppOmemo2.Received original=save(outer,clear,ack());
        assertNotNull(original.message.ids.get("local"));assertNotNull(original.message.ids.get("omemo2-cipher"));
        journal=new Omemo2IncomingJournal(new Omemo2Device(own,202,database));
        XmppOmemo2.Received restored=journal.pending().get(0);
        assertEquals(original.message.ids,restored.message.ids);assertEquals(1234567,restored.message.timestamp);
        assertEquals("Private 😀",restored.message.markup.display().body);assertEquals(fingerprint,restored.senderFingerprint);
        try(Omemo2Records tx=database.begin()) {
            XmppOmemo2.Received mam=journal.find(tx,Omemo2IncomingJournal.envelope(outer).digest,new Date(7654321),"new-mam");
            assertEquals(original.message.ids.get("local"),mam.message.ids.get("local"));assertEquals("new-mam",mam.message.ids.get("archive"));
            assertEquals(original.message.ids.get("omemo2-cipher"),mam.message.ids.get("omemo2-cipher"));assertEquals(7654321,mam.message.timestamp);assertTrue(mam.message.archived);
        }
    }
    @Test void journalAdmissionAndRatchetCallbacksRollbackTogetherOnCommitFailure() throws Exception {
        Message outer=outer("atomic",2);Message clear=clear(outer,"Recoverable");
        try(Omemo2Records tx=database.begin()) { tx.save(PRE_KEY,"",11,new byte[]{7});tx.commit(); }
        database.failCommit=true;
        assertThrows(java.io.IOException.class,() -> {
            try(Omemo2Records tx=database.begin()) {
                tx.save(PRE_KEY,"",11,null);tx.save(SESSION,peer,101,new byte[]{9});append(tx,outer,clear,null);tx.commit();
            }
        });
        database.failCommit=false;assertTrue(journal.pending().isEmpty());
        try(Omemo2Records tx=database.begin()) { assertArrayEquals(new byte[]{7},tx.load(PRE_KEY,"",11));assertNull(tx.load(SESSION,peer,101)); }
        save(outer,clear,null);assertEquals("Recoverable",journal.pending().get(0).message.body);
    }
    @Test void acceptanceFreesPlaintextButExactAckSurvivesReopenAndDuplicateAcceptance() throws Exception {
        Message outer=outer("stored",3);XmppOmemo2.Received received=save(outer,clear(outer,"Private content"),ack());
        String ackId=received.acknowledgement.getStanzaId(),ackXml=received.acknowledgement.toXML().toString();assertNotNull(ackId);
        journal.acknowledge(received.token);assertNotNull(journal.pending().get(0).message,"ACK cleanup must not remove unaccepted plaintext");
        assertTrue(journal.accept(received.token));assertTrue(journal.accept(received.token));
        Omemo2IncomingJournal reopened=new Omemo2IncomingJournal(device);XmppOmemo2.Received pending=reopened.pending().get(0);
        assertNull(pending.content);assertNull(pending.message);assertEquals(ackId,pending.acknowledgement.getStanzaId());assertEquals(ackXml,pending.acknowledgement.toXML().toString());
        reopened.acknowledge(pending.token);assertTrue(reopened.pending().isEmpty());assertFalse(reopened.accept(received.token));
    }
    @Test void reusedSlotAndLogoutEpochRejectStaleCallbacksAndInFlightAdmission() throws Exception {
        Message first=outer("old",4);XmppOmemo2.Received old=save(first,clear(first,"Old"),null);
        assertTrue(journal.accept(old.token));Message second=outer("new",5);XmppOmemo2.Received current=save(second,clear(second,"New"),null);
        assertFalse(journal.accept(old.token));journal.acknowledge(old.token);assertEquals("New",journal.pending().get(0).message.body);
        try(Omemo2Records tx=database.begin()) { Omemo2IncomingJournal.rotate(tx);tx.commit(); }
        assertTrue(journal.pending().isEmpty());assertFalse(journal.accept(current.token));
        assertThrows(java.security.GeneralSecurityException.class,() -> { try(Omemo2Records tx=database.begin()) { append(tx,second,clear(second,"Stale"),null);tx.commit(); } });
        assertTrue(new Omemo2IncomingJournal(device).pending().isEmpty());
    }
    @Test void privateCipherAliasIgnoresStanzaMamAndResourceRewritesButBindsRoutingAndCiphertext() throws Exception {
        Message outer=outer(null,6);byte[] digest=Omemo2IncomingJournal.envelope(outer).digest;
        XmppMessage provisional=XmppMessage.modern(outer,null,JidCreate.entityBareFrom(own),null,null);
        outer.setStanzaId("server-id");outer.setFrom(JidCreate.entityFullFrom(peer+"/renamed"));outer.setTo(JidCreate.entityBareFrom(own));
        assertArrayEquals(digest,Omemo2IncomingJournal.envelope(outer).digest);
        XmppMessage mam=XmppMessage.modern(outer,null,JidCreate.entityBareFrom(own),"mam-id",new Date(1000));
        assertEquals(provisional.ids.get("omemo2-cipher"),mam.ids.get("omemo2-cipher"));
        outer.setType(Message.Type.normal);assertFalse(Arrays.equals(digest,Omemo2IncomingJournal.envelope(outer).digest));
        assertFalse(Arrays.equals(digest,Omemo2IncomingJournal.envelope(outer(null,7)).digest));
    }
    @Test void historicalSenderTrustChangesDoNotEraseCommittedContentButLocalIdentityMismatchFailsClosed() throws Exception {
        Message outer=outer("trusted-before",8);save(outer,clear(outer,"Already authenticated"),null);
        try(Omemo2Records tx=database.begin()) { tx.save(TRUSTED,peer,101,null);tx.save(REJECTED,peer,101,new byte[32]);tx.commit(); }
        assertEquals(fingerprint,journal.pending().get(0).senderFingerprint);
        try(Omemo2Records tx=database.begin()) { byte[] changed=new byte[32];changed[0]=1;tx.save(IDENTITY,"",1,changed);tx.commit(); }
        assertThrows(java.security.GeneralSecurityException.class,() -> journal.pending());
        assertTrue(new Omemo2IncomingJournal(new Omemo2Device("another@example.org",203,new Omemo2TestDatabase())).pending().isEmpty());
    }
    @Test void fullJournalNeverEvictsPendingContentOrCommitsRatchetChangesAndAcceptanceFreesCapacity() throws Exception {
        XmppOmemo2.Received first=null;
        for(int i=0;i<Omemo2IncomingJournal.MAX_ENTRIES;i++) {
            Message outer=outer("bounded-"+i,i+20);XmppOmemo2.Received saved=save(outer,clear(outer,"Pending "+i),null);if(first==null) first=saved;
        }
        Message overflow=outer("overflow",1000);
        assertThrows(Omemo2IncomingJournal.FullException.class,() -> { try(Omemo2Records tx=database.begin()) { tx.save(SESSION,peer,101,new byte[]{99});append(tx,overflow,clear(overflow,"Do not consume"),null);tx.commit(); } });
        assertEquals(Omemo2IncomingJournal.MAX_ENTRIES,journal.pending().size());
        try(Omemo2Records tx=database.begin()) { assertNull(tx.load(SESSION,peer,101)); }
        assertTrue(journal.accept(first.token));save(overflow,clear(overflow,"Retry succeeds"),null);
        assertEquals(Omemo2IncomingJournal.MAX_ENTRIES,journal.pending().size());
    }
    @Test void protectedCaptionReplyCorrectionReactionAndRetractionSurviveReopen() throws Exception {
        Message fileOuter=outer("file",11);String url="aesgcm://example.org/photo.jpg#"+String.join("",Collections.nCopies(88,"a"));
        FileShare sharing=new FileShare("<file-sharing xmlns='urn:xmpp:sfs:0'><file xmlns='urn:xmpp:file:metadata:0'><name>Private.jpg</name></file><sources><url-data xmlns='http://jabber.org/protocol/url-data' target='"+url+"'/></sources></file-sharing>");
        MessageMarkup caption=new MessageMarkup("Caption 😀",Collections.singletonList(new MessageMarkup.Range(0,9,Collections.singleton(MessageMarkup.Style.STRONG),null))).styled();
        ReplyReference reply=new ReplyReference(peer,"reply-id").quoted("Quoted 🦄");String payload=sharing.body(caption.body),body=reply.wireBody(payload);
        Message file=clear(fileOuter,body);reply.addTo(file);sharing.addTo(file,body);new MessageCorrection("edited-file").addTo(file);
        caption.inBody(body,body.codePointCount(0,body.length()-payload.length())).addTo(file);save(fileOuter,file,null);
        Message reactionOuter=outer("reaction",12),reaction=clear(reactionOuter,null);
        new MessageReactions("target",Arrays.asList("👍","🐢")).addTo(reaction);save(reactionOuter,reaction,null);
        Message retractionOuter=outer("retract",13),retraction=clear(retractionOuter,MessageRetraction.FALLBACK_BODY);
        new MessageRetraction("remove-target",false).addMetadataTo(retraction);save(retractionOuter,retraction,null);
        List<XmppOmemo2.Received> replay=new Omemo2IncomingJournal(device).pending();assertEquals(3,replay.size());
        XmppMessage restoredFile=replay.get(0).message;assertEquals("Private.jpg",restoredFile.sharing.name);assertEquals(url,restoredFile.attachment.wireUrl);
        assertEquals("Caption 😀",restoredFile.captionMarkup.display().body);assertEquals("reply-id",restoredFile.reply.id);assertEquals("edited-file",restoredFile.correction.id);
        assertEquals("file",restoredFile.markableId);
        assertNull(replay.get(1).message.body);assertEquals(new LinkedHashSet<>(Arrays.asList("👍","🐢")),replay.get(1).message.reactions.emojis);
        assertNull(replay.get(2).message.body);assertEquals("remove-target",replay.get(2).message.retraction.id);assertNull(replay.get(2).message.markableId);
    }
    @Test void aggregateByteLimitRejectsAdmissionBeforeCountLimitAndPreservesPreviouslyCommittedEntries() throws Exception {
        String fallback=String.join("",Collections.nCopies(512*1024,"x")),body=String.join("",Collections.nCopies(128*1024,"y"));
        int committed=0;
        for(int i=0;i<Omemo2IncomingJournal.MAX_ENTRIES;i++) {
            Message outer=outer("bytes-"+i,i+2000);outer.setBody(fallback);
            try { save(outer,clear(outer,body),null);committed++; }
            catch(Omemo2IncomingJournal.FullException full) { break; }
        }
        assertTrue(committed>0);assertTrue(committed<Omemo2IncomingJournal.MAX_ENTRIES);
        assertEquals(committed,journal.pending().size());
    }
}
