// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

/** Protocol-specific records in one local account/device transaction. Never reuse legacy OMEMO records. */
public interface Omemo2Records extends AutoCloseable {
    int IDENTITY = 0, PRE_KEY = 1, SIGNED_PRE_KEY = 2, SESSION = 3, SESSION_IDENTITY = 4, TRUSTED = 5,
            META = 6, PRE_PUBLIC = 7, SIGNED_PUBLIC = 8, SEEN = 9, DEVICES = 10, REJECTED = 11, INCOMING = 12;
    interface Factory { Omemo2Records begin() throws Exception; }
    /** Fresh caller-owned copy; callers may erase private material after use. */
    byte[] load(int kind, String peer, int id) throws Exception;
    /** Local record identifiers only; device discovery uses the authenticated server's PEP list. */
    int[] ids(int kind) throws Exception;
    /** Copies the bytes; null deletes a record. A trusted-identity change invalidates that device's session. */
    void save(int kind, String peer, int id, byte[] value) throws Exception;
    /** Commit before exposing ciphertext; close without commit rolls back all native callbacks. */
    void commit() throws Exception;
    @Override void close() throws Exception;
}
