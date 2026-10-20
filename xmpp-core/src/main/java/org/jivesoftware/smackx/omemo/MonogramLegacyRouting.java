// SPDX-License-Identifier: GPL-3.0-or-later
package org.jivesoftware.smackx.omemo;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.jivesoftware.smackx.omemo.exceptions.*;
import org.whispersystems.libsignal.InvalidKeyIdException;
import org.whispersystems.libsignal.InvalidMessageException;
import org.whispersystems.libsignal.NoSessionException;

/** Only installed Signal key/session mismatches may try the other isolated receive store. */
final class MonogramLegacyRouting {
    private MonogramLegacyRouting() { }
    @FunctionalInterface interface Attempt<T> { T run() throws Exception; }
    static <T> T receive(boolean prekey,Attempt<T> active,Attempt<T> archive) throws Exception {
        Attempt<T> first=prekey?archive:active,second=prekey?active:archive;
        try { return first.run(); }
        catch(Exception failure) {
            if(!recoverable(failure)) throw failure;
            return second.run();
        }
    }
    static boolean recoverable(Throwable failure) {
        return recoverable(failure,Collections.newSetFromMap(new IdentityHashMap<>()),0);
    }
    private static boolean recoverable(Throwable failure,Set<Throwable> seen,int depth) {
        if(failure==null || depth>16 || !seen.add(failure)) return false;
        // Never infer eligibility from messages, broad Exception wrappers, or just one child
        // of a mixed aggregate. The installed Smack aggregator has its own child-list API.
        for(Throwable suppressed:failure.getSuppressed()) if(!recoverable(suppressed,seen,depth+1)) return false;
        if(failure instanceof MultipleCryptoFailedException) {
            java.util.List<CryptoFailedException> children=((MultipleCryptoFailedException)failure).getCryptoFailedExceptions();
            if(children.isEmpty() || failure.getCause()!=null) return false;
            for(CryptoFailedException child:children) if(!recoverable(child,seen,depth+1)) return false;
            return true;
        }
        if(failure.getClass()==CryptoFailedException.class || failure.getClass()==NoRawSessionException.class)
            return recoverable(failure.getCause(),seen,depth+1);
        if(failure.getClass()==InvalidKeyIdException.class || failure.getClass()==InvalidMessageException.class || failure.getClass()==NoSessionException.class)
            return failure.getCause()==null || recoverable(failure.getCause(),seen,depth+1);
        return false;
    }
}
