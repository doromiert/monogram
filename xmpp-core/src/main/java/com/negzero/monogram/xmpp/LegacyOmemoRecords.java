// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.util.SortedSet;

/** One owner/device transaction for existing Signal bytes and their authenticated incoming journal. */
public interface LegacyOmemoRecords extends AutoCloseable {
    int MAX_RECORD_BYTES=8*1024*1024+1;
    int IDENTITY_PAIR=0, IDENTITY=1, PRE_KEY=2, SIGNED_PRE_KEY=3, SESSION=4, MESSAGE_COUNTER=5,
            LAST_RECEIVED=6, LAST_PUBLISHED=7, LAST_RENEWAL=8, DEVICE_LIST=9, PURGED=10, INCOMING=11;
    interface Factory {
        LegacyOmemoRecords begin(String owner,int device) throws Exception;
        /** Overlay devices only; the typed store merges these with its read-only bootstrap backend. */
        SortedSet<Integer> localDevices(String owner) throws Exception;
    }
    /** Detached bytes, null when the overlay has no row. Bootstrap/tombstones belong to the typed store. */
    byte[] load(int kind,String peer,int id) throws Exception;
    /** Detached sorted identifiers within this exact kind/peer scope. */
    int[] ids(int kind,String peer) throws Exception;
    /** Copy bytes; null deletes an overlay row. Never interpret typed tombstones in this raw adapter. */
    void save(int kind,String peer,int id,byte[] value) throws Exception;
    /** Delete this owner/device's complete overlay. Typed store then writes its bootstrap-suppression marker. */
    void purge() throws Exception;
    void commit() throws Exception;
    /** Close without commit rolls back every write, including Signal callbacks and incoming plaintext. */
    @Override void close() throws Exception;
}
