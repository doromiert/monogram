// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.wallet;
import java.math.BigInteger;
import java.util.*;
public final class WalletFeeMath {
    public static final BigInteger MAX=new BigInteger("18446744073709551615");
    private WalletFeeMath(){}
    public static BigInteger median(List<BigInteger> values){
        if(values.isEmpty())throw new IllegalArgumentException("No fee samples.");
        ArrayList<BigInteger> sorted=new ArrayList<>(values);
        for(BigInteger value:sorted)if(value.signum()<=0 || value.compareTo(MAX)>0)throw new IllegalArgumentException("Invalid fee sample.");
        Collections.sort(sorted);int middle=sorted.size()/2;
        return sorted.size()%2==1 ? sorted.get(middle) : sorted.get(middle-1).add(sorted.get(middle)).add(BigInteger.ONE).divide(BigInteger.valueOf(2));
    }
    public static BigInteger suggestedLimit(BigInteger estimate){
        if(estimate==null || estimate.signum()<=0 || estimate.compareTo(MAX)>0)throw new IllegalArgumentException("Fee estimate unavailable.");
        return estimate.multiply(BigInteger.valueOf(2)).min(MAX);
    }
}
