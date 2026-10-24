package org.telegram.messenger.xmpp;

import com.negzero.monogram.xmpp.MoneroClaim;
import com.negzero.monogram.xmpp.MoneroPayment;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class MonogramXmrMessageTest {
    // Official public address vector; synthetic mnemonic is for envelope parsing only.
    private static final String ADDRESS = "4AzKEX4gXdJdNeM6dfiBFL7kqund3HYGvMBF3ttsNd9SfzgYB6L7ep1Yg1osYJzLdaKAYSLVh6e6jKnAuzj3bw1oGy9kXCb";
    @Test public void requestsAndBroadcastsRemainDistinct() {
        MoneroPayment payment = MoneroPayment.create(ADDRESS, "0.03987481", "", "Lunch", null);
        assertEquals(MonogramXmrMessage.Kind.REQUEST, MonogramXmrMessage.parse("XMR payment request\n" + payment.uri()).kind);
        String hashes = String.join("", Collections.nCopies(64, "a"));
        MonogramXmrMessage broadcast = MonogramXmrMessage.parse("XMR payment broadcast\n" + MonogramXmrMessage.transfer(payment, hashes));
        assertEquals(MonogramXmrMessage.Kind.TRANSFER, broadcast.kind);
        assertEquals("Lunch", broadcast.comment);
        assertEquals(hashes, broadcast.hashes);
    }
    @Test public void requestAndTransferCardsKeepRecipientNotes() {
        String note = "I wanna trade, gimme a link to pay you in fiat\nUSD / EUR / PLN & coffee ☕";
        MoneroPayment payment = MoneroPayment.create(ADDRESS, "0.123456789012", "", note, null);
        MonogramXmrMessage request = MonogramXmrMessage.parse("XMR payment request\n" + payment.uri());
        MonogramXmrMessage transfer = MonogramXmrMessage.parse("XMR payment broadcast\n" + MonogramXmrMessage.transfer(payment, String.join("", Collections.nCopies(64, "a"))));
        assertNotNull(request); assertNotNull(transfer);
        assertEquals(note, request.comment); assertEquals(note, transfer.comment);
        assertEquals(payment.atomicAmount, request.payment.atomicAmount);
        assertEquals(payment.address, transfer.payment.address);
    }
    @Test public void claimNotesCannotAlterTheBearerUri() {
        MoneroPayment payment = MoneroPayment.create(ADDRESS, "0.1", "", "", null);
        char[] synthetic = String.join(" ", Collections.nCopies(25, "abbey")).toCharArray();
        try (MoneroClaim link = new MoneroClaim(payment, 3790000, synthetic)) {
            String uri = link.uri();
            MonogramXmrMessage parsed = MonogramXmrMessage.parse("XMR claim link — anyone with this link can claim the funds\n" + uri + "\n\nDinner\nThank you");
            assertEquals(MonogramXmrMessage.Kind.CLAIM, parsed.kind);
            assertEquals(uri, parsed.uri);
            assertEquals("Dinner\nThank you", parsed.comment);
            assertFalse(parsed.toString().contains("abbey"));
        } finally { java.util.Arrays.fill(synthetic, '\0'); }
    }
    @Test public void portableClaimKeepsCardAndExternalRestoreInAgreement() {
        char[] synthetic=String.join(" ",Collections.nCopies(25,"abbey")).toCharArray();
        try(MoneroClaim claim=new MoneroClaim(MoneroPayment.create(ADDRESS,"0.1","","",null),3790000,synthetic)){
            String body=MonogramXmrMessage.claimBody(claim,"Dinner\nThank you");
            assertTrue(body.contains("monero-wallet:?seed=abbey+abbey"));
            assertTrue(body.contains("&height=3790000"));
            MonogramXmrMessage parsed=MonogramXmrMessage.parse(body);
            assertNotNull(parsed);assertEquals(claim.uri(),parsed.uri);
            assertEquals("Dinner\nThank you",parsed.comment);
            assertNull(MonogramXmrMessage.parse(body.replace("&height=3790000","&height=1")));
            assertNull(MonogramXmrMessage.parse(body+"\nUnverified extra instructions"));
        }finally{java.util.Arrays.fill(synthetic,'\0');}
    }
    @Test public void publicRequestsFollowConversationEncryptionIncludingOldQueuedRequests() {
        MoneroPayment payment=MoneroPayment.create(ADDRESS,"1000000","","A request, not a spending key",null);
        String request="XMR payment request\n"+payment.uri();
        assertFalse(MonogramXmrMessage.requiresEncryption(request,true));
        assertFalse(MonogramXmrMessage.requiresEncryption(request,false));
    }
    @Test public void bearerClaimsNeverAcceptAPlaintextOverride() {
        char[] synthetic=String.join(" ",Collections.nCopies(25,"abbey")).toCharArray();
        try(MoneroClaim claim=new MoneroClaim(MoneroPayment.create(ADDRESS,"0.1","","",null),3790000,synthetic)) {
            assertTrue(MonogramXmrMessage.requiresEncryption(MonogramXmrMessage.claimBody(claim,""),false));
            assertTrue(MonogramXmrMessage.requiresEncryption(claim.uri(),false));
        }finally{java.util.Arrays.fill(synthetic,'\0');}
        assertTrue(MonogramXmrMessage.requiresEncryption("unrecognized payment payload",true));
    }
    @Test public void invalidBodiesRemainOrdinaryMessages() {
        assertNull(MonogramXmrMessage.parse("monero:" + ADDRESS + "?tx_amount=0"));
        assertNull(MonogramXmrMessage.parse("monogram-xmr:claim?v=1#not-a-wallet"));
        assertNull(MonogramXmrMessage.parse("an ordinary message"));
    }
}
