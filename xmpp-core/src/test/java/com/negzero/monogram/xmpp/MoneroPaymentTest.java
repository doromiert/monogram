package com.negzero.monogram.xmpp;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MoneroPaymentTest {
    // Official Monero unit_tests/base58.cpp and uri.cpp public test vectors. Never fund these.
    private static final String MAIN="4AzKEX4gXdJdNeM6dfiBFL7kqund3HYGvMBF3ttsNd9SfzgYB6L7ep1Yg1osYJzLdaKAYSLVh6e6jKnAuzj3bw1oGy9kXCb";
    private static final String TEST="9tTLtauaEKSj7xoVXytVH32R1pLZBk4VV4mZFGEh4wkXhDWqw1soPyf3fGixf1kni31VznEZkWNEza9d5TvjWwq5PaohYHC";
    private static final String INTEGRATED="A4A1uPj4qaxj7xoVXytVH32R1pLZBk4VV4mZFGEh4wkXhDWqw1soPyf3fGixf1kni31VznEZkWNEza9d5TvjWwq5acaPMJfMbn3ReTsBpp";
    // Synthetic public base-point addresses, generated independently. Never fund these either.
    private static final String SUB="85oYs3QM9oBJ8QgRfFWTzmJ8QgRfFWTzmJ8QgRfFWTzmJ7suhUXwdrDJ8QgRfFWTzmJ8QgRfFWTzmJ8QgRfFWTzmCa641Hz";
    private static final String STAGE="75bWnCVPWC5J8QgRfFWTzmJ8QgRfFWTzmJ8QgRfFWTzmJ7suhUXwdrDJ8QgRfFWTzmJ8QgRfFWTzmJ8QgRfFWTzmCWkFDcY";
    @Test void acceptsOfficialAddressesAndChecksNetworksAndTypes() {
        assertEquals(MoneroPayment.AddressType.STANDARD,MoneroPayment.address(MAIN,MoneroPayment.Network.MAINNET).type);
        assertEquals(MoneroPayment.Network.TESTNET,MoneroPayment.address(TEST,MoneroPayment.Network.TESTNET).network);
        assertEquals(MoneroPayment.AddressType.INTEGRATED,MoneroPayment.address(INTEGRATED,MoneroPayment.Network.TESTNET).type);
        assertEquals(MoneroPayment.AddressType.SUBADDRESS,MoneroPayment.address(SUB,MoneroPayment.Network.MAINNET).type);
        assertEquals(MoneroPayment.Network.STAGENET,MoneroPayment.address(STAGE,MoneroPayment.Network.STAGENET).network);
        assertThrows(IllegalArgumentException.class,()->MoneroPayment.address(TEST,MoneroPayment.Network.MAINNET));
        assertThrows(IllegalArgumentException.class,()->MoneroPayment.address(STAGE,MoneroPayment.Network.MAINNET));
    }
    @Test void rejectsBadChecksumsAlphabetOverflowAndTruncatedAddresses() {
        for(String invalid:new String[]{MAIN.substring(0,94)+"1","0"+MAIN.substring(1),MAIN.substring(1),new String(new char[95]).replace('\0','z'),SUB+";"+MAIN})
            assertThrows(IllegalArgumentException.class,()->MoneroPayment.address(invalid,null),invalid);
    }
    @Test void atomicAmountsNeverUseFloatingPointAndHaveAnExplicitUnsignedBound() {
        assertEquals(BigInteger.ONE,MoneroPayment.amount("0.000000000001"));
        assertEquals(new BigInteger("1234567890123"),MoneroPayment.amount("1.234567890123"));
        assertEquals("18446744.073709551615",MoneroPayment.format(MoneroPayment.MAX_ATOMIC));
        assertEquals(MoneroPayment.MAX_ATOMIC,MoneroPayment.amount("18446744.073709551615"));
        for(String invalid:new String[]{"0","-1","+1","1e-12",".1","1.","1,2"," 1","0.0000000000001","18446744.073709551616"})
            assertThrows(IllegalArgumentException.class,()->MoneroPayment.amount(invalid),invalid);
    }
    @Test void smallExistingBalanceKeepsAllAtomicUnits() {
        assertEquals(new BigInteger("39874810000"),MoneroPayment.amount("0.03987481"));
        assertEquals("0.03987481",MoneroPayment.format(new BigInteger("39874810000")));
    }
    @Test void zeroBalanceFormatsButZeroPaymentIsStillRejected() {
        assertEquals("0",MoneroPayment.format(BigInteger.ZERO));
        assertThrows(IllegalArgumentException.class,()->MoneroPayment.create(SUB,"0","","",MoneroPayment.Network.MAINNET));
    }
    @Test void canonicalUriKeepsTheSameDestinationAmountAndUtf8Labels() {
        MoneroPayment original=MoneroPayment.create(SUB,"0.012345678901","Alice & Bob","Lunch ☕ + cake",MoneroPayment.Network.MAINNET);
        assertTrue(original.uri().contains("Alice%20%26%20Bob"));assertTrue(original.uri().contains("%2B"));
        MoneroPayment parsed=MoneroPayment.parse(original.uri(),MoneroPayment.Network.MAINNET);
        assertEquals(original.uri(),parsed.uri());assertEquals(SUB,parsed.address);
        assertEquals(new BigInteger("12345678901"),parsed.atomicAmount);assertEquals("Lunch ☕ + cake",parsed.description);
        assertEquals("Alice & Bob",parsed.recipientName);
    }
    @Test void paymentNotesPreserveMultilineTextAndDoNotAlterAmountOrDestination() {
        String note="I wanna trade, gimme a link to pay you in fiat\nUSD / EUR / PLN & coffee ☕";
        MoneroPayment payment=MoneroPayment.create(SUB,"0.123456789012","",note,MoneroPayment.Network.MAINNET);
        MoneroPayment restored=MoneroPayment.parse(payment.uri(),MoneroPayment.Network.MAINNET);
        assertEquals(note,restored.description);
        assertEquals(payment.atomicAmount,restored.atomicAmount);
        assertEquals(SUB,restored.address);
        assertTrue(payment.uri().contains("%0A"));
        assertThrows(IllegalArgumentException.class,()->MoneroPayment.note("hidden\u0000note"));
        assertThrows(IllegalArgumentException.class,()->MoneroPayment.note("hidden\u202enote"));
    }
    @Test void refusesAmbiguousUnsafeAndUnsupportedPaymentInstructions() {
        for(String suffix:new String[]{"?tx_amount=1&tx_amount=2","?tx_amount=1&tx%5famount=2","?tx_amount=","?tx_amount=0","?tx_amount=1&address="+MAIN,
                "?tx_payment_id=1234","?tx_description=%ff","?tx_description=%00other","?tx_description=%0dother","?tx_description=%E2%80%AEother","?tx_description=%","?tx_amount=1#x",";"+MAIN,"?tx_amount=1&&recipient_name=x"})
            assertThrows(IllegalArgumentException.class,()->MoneroPayment.parse("monero:"+SUB+suffix,null),suffix);
        assertThrows(IllegalArgumentException.class,()->MoneroPayment.parse("monero://"+SUB,null));
        assertThrows(IllegalArgumentException.class,()->MoneroPayment.parse("https://example.org/monero:"+SUB,null));
    }
}
