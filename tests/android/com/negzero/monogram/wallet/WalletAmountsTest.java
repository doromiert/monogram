package com.negzero.monogram.wallet;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;
import org.junit.Test;
import static org.junit.Assert.*;

public class WalletAmountsTest {
    @Test public void xmrRetainsTwelveDecimalsAndSmallBalances() {
        assertEquals(1L, WalletAmounts.inputAtomic(new BigDecimal("0.000000000001"), false, null));
        assertEquals(39874810000L, WalletAmounts.inputAtomic(new BigDecimal("0.03987481"), false, null));
        assertEquals("0.03987481", WalletAmounts.coins(39874810000L).stripTrailingZeros().toPlainString());
    }
    @Test public void displayPrecisionDoesNotChangeThePaymentAmount() {
        long atomic=39874818461L;
        assertEquals("0.0398",WalletAmounts.displayBalance(atomic,4));
        assertEquals("0.039874818461",WalletAmounts.displayBalance(atomic,12));
        assertEquals("<0.0001",WalletAmounts.displayBalance(1,4));
        assertEquals("0.000000000001",WalletAmounts.displayBalance(1,12));
        assertEquals(atomic,WalletAmounts.inputAtomic(WalletAmounts.coins(atomic),false,null));
    }
    @Test public void fiatDoesNotRoundTheResultToTwoCoinDecimals() {
        WalletFiat.Rate rate = new WalletFiat.Rate("PLN", new BigDecimal("3"), System.currentTimeMillis());
        assertEquals(3333333333333L, WalletAmounts.inputAtomic(new BigDecimal("10"), true, rate));
        assertEquals(0L, WalletAmounts.inputAtomic(new BigDecimal("10"), true, null));
    }
    @Test public void staleAndFutureRatesCannotAuthorizeFiatAmounts() {
        long now = System.currentTimeMillis();
        assertEquals(0L, WalletAmounts.inputAtomic(BigDecimal.ONE, true,
                new WalletFiat.Rate("USD", BigDecimal.ONE, now - WalletFiat.MAX_AGE_MILLIS - 60000)));
        assertEquals(0L, WalletAmounts.inputAtomic(BigDecimal.ONE, true,
                new WalletFiat.Rate("USD", BigDecimal.ONE, now + 300000)));
    }
    @Test public void unsupportedAmountsNeverSaturateToAnotherPayment() {
        assertEquals(0L, WalletAmounts.inputAtomic(new BigDecimal("0.0000000000001"), false, null));
        assertEquals(0L, WalletAmounts.inputAtomic(new BigDecimal("9223372.036854775808"), false, null));
        assertEquals(Long.MAX_VALUE, WalletAmounts.inputAtomic(new BigDecimal("9223372.036854775807"), false, null));
        assertEquals(0L, WalletAmounts.inputAtomic(new BigDecimal("-1"), false, null));
        try { WalletAmounts.checkedAtomic(BigInteger.ONE.shiftLeft(63)); fail(); }
        catch (IllegalArgumentException expected) {}
    }
    @Test public void currencySymbolsFollowTheirLocales() {
        WalletFiat.Format dollars = new WalletFiat.Format("USD", Locale.US);
        WalletFiat.Format zloty = new WalletFiat.Format("PLN", Locale.forLanguageTag("pl-PL"));
        assertTrue(dollars.symbolLeft);
        assertFalse(zloty.symbolLeft);
        assertTrue(zloty.spaceBetween);
        assertEquals(2, zloty.exp);
    }
}
