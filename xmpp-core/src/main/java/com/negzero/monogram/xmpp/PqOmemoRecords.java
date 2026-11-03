// SPDX-License-Identifier: AGPL-3.0-only
package com.negzero.monogram.xmpp;

import java.util.Set;

/** Account-scoped PQ-only transaction. Never point this at a classical OMEMO database. */
public interface PqOmemoRecords extends AutoCloseable {
    interface Factory { PqOmemoRecords begin(); }
    /** Returns a caller-owned copy. Missing records return null, corruption must throw. */
    byte[] get(String key);
    /** Copies value; null deletes. */
    void put(String key, byte[] value);
    Set<String> keys(String prefix);
    /** Durable atomic commit before releasing ciphertext or decrypted content to the caller. */
    void commit();
    /** Roll back if not committed, including consumed prekeys and replay tuples. */
    @Override void close();
}
