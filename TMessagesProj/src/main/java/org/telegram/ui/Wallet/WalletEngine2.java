// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui.Wallet;

import com.negzero.monogram.xmpp.MoneroPayment;

/** Source-UI compatibility: address validation only; signing lives in the Monero SDK. */
public final class WalletEngine2 {
    private WalletEngine2(){}
    public static boolean isValidRecipientAddress(String address){try{MoneroPayment.address(address,null);return true;}catch(Exception invalid){return false;}}
    public static boolean isValidAddress(String address){return isValidRecipientAddress(address);}
    public static String toUserFriendlyAddress(String address){return MoneroPayment.address(address,null).address;}
    public enum SendPhase {
        IDLE,VALIDATING,AUTHORIZING,PREPARING,PERSISTING,READY_TO_SUBMIT,SUBMITTING,SUBMISSION_UNKNOWN,
        SUBMITTED,HANDED_OFF,CONFIRMED,REPLACED,SEQUENCE_NUMBER_CONSUMED,EXPIRED,SUPERSEDED,FAILED,CANCELLED
    }
    public static boolean isFinalPhase(SendPhase phase){return phase==SendPhase.CONFIRMED || phase==SendPhase.FAILED || phase==SendPhase.CANCELLED;}
}
