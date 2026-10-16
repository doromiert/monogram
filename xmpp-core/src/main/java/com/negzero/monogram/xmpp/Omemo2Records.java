// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

/** Protocol-specific records in one local account/device transaction. Never reuse legacy OMEMO records. */
public interface Omemo2Records extends AutoCloseable {
    int IDENTITY = 0, PRE_KEY = 1, SIGNED_PRE_KEY = 2, SESSION = 3, SESSION_IDENTITY = 4, TRUSTED = 5;
    byte[] load(int kind, String peer, int id) throws Exception;
    /** Null deletes a record. A trusted-identity change must invalidate that device's session. */
    void save(int kind, String peer, int id, byte[] value) throws Exception;
    /** Commit before exposing ciphertext; close without commit rolls back all native callbacks. */
    void commit() throws Exception;
    @Override void close() throws Exception;
}
