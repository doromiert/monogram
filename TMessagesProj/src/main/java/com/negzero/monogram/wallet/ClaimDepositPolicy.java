// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.wallet;

import java.math.BigInteger;

/** No keys or network access. Zero balance alone never proves that a link was claimed. */
public final class ClaimDepositPolicy {
    private ClaimDepositPolicy() {}
    public static String observed(BigInteger total,BigInteger unlocked,boolean outgoingConfirmed,boolean outgoingPending){
        if(total.signum()>0)return total.equals(unlocked) ? "ready" : "waiting";
        if(outgoingPending)return "depositing";
        return outgoingConfirmed ? "claimed" : "unknown";
    }
    public static void checkApproval(String destination,String quotedDestination,BigInteger total,BigInteger amount,BigInteger fee,BigInteger limit){
        if(destination==null || !destination.equals(quotedDestination))throw new IllegalStateException("Deposit destination changed. Approve again.");
        if(limit==null || limit.signum()<=0 || fee.signum()<0 || fee.compareTo(limit)>0)throw new IllegalStateException("The network fee exceeds your approved limit.");
        if(amount.signum()<=0 || !amount.add(fee).equals(total))throw new IllegalStateException("The deposit amount changed. Check this link again.");
    }
    public static String label(String state){
        switch(state){
            case "waiting":return "Waiting for confirmations";
            case "ready":return "Ready to deposit · last checked";
            case "depositing":return "Deposit pending";
            case "claimed":return "Already claimed";
            case "queued":return "Background deposit queued";
            case "paused":return "Deposit paused · reopen to resume";
            case "fee-limit":return "Fee limit reached · review again";
            case "uncertain":return "Check deposit status";
            default:return "Tap to check link status";
        }
    }
}
