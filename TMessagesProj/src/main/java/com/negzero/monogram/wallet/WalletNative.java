// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.wallet;

/** Narrow bridge to the pinned upstream wallet engine. Loaded only for an explicit wallet operation. */
public final class WalletNative {
    private static boolean loaded;
    private WalletNative() {}
    public static synchronized void load() {
        if(loaded) return;
        System.loadLibrary("monogram_wallet");loaded=true;
    }
    public interface Progress {void block(long height,boolean last);}
    public static native long restore(String path,char[] password,char[] seed,char[] passphrase,int network,long height,boolean polyseed);
    public static native long open(String path,char[] password,int network);
    public static native long createWallet(String path,char[] password,int network,String endpoint,String user,String localPassword);
    public static native char[] recoveryPhrase(long handle);
    public static native long createClaim(String path,char[] password,int network,long height);
    public static native long openClaim(String path,char[] password,int network,long height);
    public static native char[] claimSeed(long handle);
    public static native String[] prepareClaimSweep(long handle,String destination);
    public static native String[] prepareClaimSweepPriority(long handle,String destination,int priority);
    /** Default/high-priority one-input estimates only; never constructs or broadcasts a transaction. */
    public static native String[] estimateDepositFees(long handle,String destination);
    public static native void close(long handle);
    public static native void stop(long handle);
    /** address, balance atomic, unlocked atomic, wallet height, daemon height, synchronized, address count, scan start height. */
    public static native String[] snapshot(long handle);
    /** Fresh public address and its minor index. Persisted before being returned. */
    public static native String[] newAddress(long handle,long minimumIndex,String label);
    public static native void connect(long handle,String endpoint,String user,String password);
    /** One safely saved scan slice; false means checkpointed, true means fully synchronized. */
    public static native boolean sync(long handle,Progress progress);
    public static native boolean rescan(long handle,Progress progress);
    /** Quote ID, exact amount, fee, transaction count, comma-separated transaction hashes. */
    public static native String[] prepare(long handle,String destination,String atomicAmount);
    /** broadcast/unknown, hashes, cacheSaved, error. Never a false "not sent" after broadcast. */
    public static native String[] commit(long handle,long quote);
    public static native void cancel(long handle,long quote);
    public static native String[] history(long handle);
}
