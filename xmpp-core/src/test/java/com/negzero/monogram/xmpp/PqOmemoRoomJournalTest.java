// SPDX-License-Identifier: AGPL-3.0-only
package com.negzero.monogram.xmpp;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.jivesoftware.smack.packet.Message;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;

class PqOmemoRoomJournalTest {
    @Test void replayRetainsVerifiedAuthorRoomAndAliasesAfterNicknameChanges() throws Exception {
        String room="group@conference.example.org",owner="bob@example.org",author="alice@example.org";
        Message outer=new Message(JidCreate.entityBareFrom(owner),Message.Type.groupchat);
        outer.setFrom(JidCreate.entityFullFrom(room+"/old-nickname"));outer.setStanzaId("original");
        XmppRoomMessage snapshot=XmppRoomMessage.archived(outer,JidCreate.entityBareFrom(room),JidCreate.entityBareFrom(owner),"Bob",null,true,false,"mam-id",new Date());
        RoomCryptoContext route=new RoomCryptoContext(LegacyIncomingJournal.Context.room(outer,author,"Bob",snapshot,false),owner);
        Message content=new Message();content.setBody("decrypted");
        String digest=String.join("",Collections.nCopies(64,"a"));
        PqOmemoNativeTest.Database db=new PqOmemoNativeTest.Database();
        try(PqOmemoRecords tx=db.begin()){
            PqOmemoJournal.save(tx,new PqOmemoJournal.Entry(digest,String.join("",Collections.nCopies(128,"b")),outer,content,route.timestamp,"mam-id",route));tx.commit();
        }
        try(PqOmemoRecords tx=db.begin()){
            PqOmemoJournal.Entry saved=PqOmemoJournal.pending(tx).get(0);
            XmppRoomMessage replay=saved.room.model(saved.content,XmppMessage.Encryption.PQ_OMEMO,saved.fingerprint,saved.archive,new Date(saved.timestamp));
            assertEquals(author,replay.realSender);assertTrue(replay.realSenderVerified);assertEquals(room,replay.room.toString());
            assertEquals("mam-id",replay.ids.get("mam"));assertEquals("decrypted",replay.body);assertEquals(XmppMessage.Encryption.PQ_OMEMO,replay.encryption);
        }
    }
}
