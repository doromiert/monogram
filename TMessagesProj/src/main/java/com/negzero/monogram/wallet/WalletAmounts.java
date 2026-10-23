// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.wallet;

import java.math.BigDecimal;
import java.math.BigInteger;

/** Exact boundary between Monero atomic amounts and Telegram's signed-long UI models. */
public final class WalletAmounts {
    public static final int DECIMALS = 12;
    public static final long ATOMIC_PER_XMR = 1_000_000_000_000L;
    private WalletAmounts() {}

    public static BigDecimal coins(long atomic) {
        return BigDecimal.valueOf(atomic, DECIMALS);
    }

    /** Display only: the exact atomic value is never modified. */
    public static String displayBalance(long atomic,int decimals) {
        if(atomic<0 || decimals<0 || decimals>DECIMALS)throw new IllegalArgumentException("Invalid balance precision.");
        BigDecimal value=coins(atomic).setScale(decimals,java.math.RoundingMode.DOWN);
        if(atomic>0 && value.signum()==0)return "<"+BigDecimal.ONE.movePointLeft(decimals).toPlainString();
        return value.stripTrailingZeros().toPlainString();
    }
    public static long checkedAtomic(BigInteger atomic) {
        if (atomic.signum() < 0 || atomic.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException("This amount cannot be represented by the wallet screen.");
        }
        return atomic.longValueExact();
    }

    /** Invalid/stale input disables sending; it must never saturate to a different payment. */
    public static long inputAtomic(BigDecimal entered, boolean fiat, WalletFiat.Rate rate) {
        if (entered == null || entered.signum() <= 0) return 0;
        try {
            return checkedAtomic(fiat
                    ? rate == null ? BigInteger.ZERO : rate.atomsFor(entered, System.currentTimeMillis())
                    : entered.movePointRight(DECIMALS).toBigIntegerExact());
        } catch (IllegalArgumentException | ArithmeticException | IllegalStateException invalid) {
            return 0;
        }
    }
}
