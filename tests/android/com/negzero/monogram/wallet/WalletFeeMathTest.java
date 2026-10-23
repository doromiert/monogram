package com.negzero.monogram.wallet;
import org.junit.Test;import static org.junit.Assert.*;
import java.math.BigInteger;import java.util.*;
public class WalletFeeMathTest {
    private static BigInteger n(long value){return BigInteger.valueOf(value);}
    @Test public void medianIsNotDistortedByOneLargeTransaction(){assertEquals(n(30),WalletFeeMath.median(Arrays.asList(n(99999),n(20),n(30))));}
    @Test public void evenMedianRoundsUpOneAtomicUnit(){assertEquals(n(11),WalletFeeMath.median(Arrays.asList(n(10),n(11))));}
    @Test public void suggestionAddsExplicitHeadroom(){assertEquals(n(60),WalletFeeMath.suggestedLimit(n(30)));}
    @Test public void unsignedBoundaryDoesNotOverflow(){assertEquals(WalletFeeMath.MAX,WalletFeeMath.suggestedLimit(WalletFeeMath.MAX));}
    @Test(expected=IllegalArgumentException.class) public void missingHistoryIsNotAZeroFee(){WalletFeeMath.median(Collections.emptyList());}
    @Test(expected=IllegalArgumentException.class) public void zeroEstimateCannotBecomeAFreeSuggestion(){WalletFeeMath.suggestedLimit(BigInteger.ZERO);}
    @Test(expected=IllegalArgumentException.class) public void rejectOutOfRangeFee(){WalletFeeMath.median(Arrays.asList(WalletFeeMath.MAX.add(BigInteger.ONE)));}
}
