package com.negzero.monogram.wallet;
import java.math.BigInteger;
import org.junit.Test;
import static org.junit.Assert.*;
public class ClaimDepositPolicyTest {
    private static BigInteger n(long value){return BigInteger.valueOf(value);}
    @Test public void zeroBalanceNeedsSpendEvidence(){
        assertEquals("unknown",ClaimDepositPolicy.observed(n(0),n(0),false,false));
        assertEquals("claimed",ClaimDepositPolicy.observed(n(0),n(0),true,false));
        assertEquals("depositing",ClaimDepositPolicy.observed(n(0),n(0),true,true));
    }
    @Test public void confirmedFundingIsNotSpendableUntilUnlocked(){
        assertEquals("waiting",ClaimDepositPolicy.observed(n(100),n(0),false,false));
        assertEquals("waiting",ClaimDepositPolicy.observed(n(100),n(90),true,false));
        assertEquals("ready",ClaimDepositPolicy.observed(n(100),n(100),false,false));
    }
    @Test public void capIncludesBoundaryAndLargeAtomicValues(){
        BigInteger total=new BigInteger("18446744073709551615");
        ClaimDepositPolicy.checkApproval("destination","destination",total,total.subtract(n(10)),n(10),n(10));
    }
    @Test(expected=IllegalStateException.class) public void rejectFeeAboveUserLimit(){ClaimDepositPolicy.checkApproval("a","a",n(100),n(89),n(11),n(10));}
    @Test(expected=IllegalStateException.class) public void rejectChangedDestination(){ClaimDepositPolicy.checkApproval("a","b",n(100),n(90),n(10),n(10));}
    @Test(expected=IllegalStateException.class) public void rejectChangedSweepAmount(){ClaimDepositPolicy.checkApproval("a","a",n(100),n(91),n(10),n(10));}
    @Test(expected=IllegalStateException.class) public void rejectEmptyDeposit(){ClaimDepositPolicy.checkApproval("a","a",n(10),n(0),n(10),n(10));}
    @Test(expected=IllegalStateException.class) public void rejectMissingApproval(){ClaimDepositPolicy.checkApproval("a","a",n(100),n(90),n(10),null);}
    @Test(expected=IllegalStateException.class) public void rejectNegativeFee(){ClaimDepositPolicy.checkApproval("a","a",n(100),n(101),n(-1),n(10));}
}
