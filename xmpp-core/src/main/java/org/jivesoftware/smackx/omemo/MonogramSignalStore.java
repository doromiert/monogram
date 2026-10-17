// SPDX-License-Identifier: GPL-3.0-or-later
package org.jivesoftware.smackx.omemo;

import com.negzero.monogram.xmpp.LegacyOmemoRecords;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import org.jivesoftware.smackx.omemo.exceptions.CorruptedOmemoKeyException;
import org.jivesoftware.smackx.omemo.internal.OmemoCachedDeviceList;
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;
import org.jivesoftware.smackx.omemo.signal.SignalFileBasedOmemoStore;
import org.jivesoftware.smackx.omemo.signal.SignalOmemoKeyUtil;
import org.jxmpp.jid.BareJid;
import org.whispersystems.libsignal.*;
import org.whispersystems.libsignal.ecc.ECPublicKey;
import org.whispersystems.libsignal.state.*;
import static com.negzero.monogram.xmpp.LegacyOmemoRecords.*;

/** Detached Signal records; old private files are read-only bootstrap, never a second writer. */
public class MonogramSignalStore extends OmemoStore<IdentityKeyPair,IdentityKey,PreKeyRecord,
        SignedPreKeyRecord,SessionRecord,SignalProtocolAddress,ECPublicKey,PreKeyBundle,SessionCipher> {
    protected final ReentrantLock storageLock=new ReentrantLock();
    private final SignalFileBasedOmemoStore bootstrap;
    private final LegacyOmemoRecords.Factory records;
    private final SignalOmemoKeyUtil keys=new SignalOmemoKeyUtil();
    private final ThreadLocal<Context> active=new ThreadLocal<>();
    private final Map<LegacyOmemoRecords,Context> transactions=new IdentityHashMap<>();
    @FunctionalInterface public interface Operation<T> { T run() throws Exception; }
    private static final class Context {
        final OmemoDevice own;final LegacyOmemoRecords tx;IOException failure;
        Context(OmemoDevice own,LegacyOmemoRecords tx) { this.own=own;this.tx=tx; }
    }
    public MonogramSignalStore(SignalFileBasedOmemoStore bootstrap,LegacyOmemoRecords.Factory records) {
        this.bootstrap=Objects.requireNonNull(bootstrap);this.records=Objects.requireNonNull(records);
    }
    /** Acquire before SQLite, also ordering list PEP/CAS changes against crypto transactions. Caller closes. */
    public LegacyOmemoRecords begin(OmemoDevice own) throws Exception {
        storageLock.lock();
        final LegacyOmemoRecords tx;
        try { tx=records.begin(own.getJid().toString(),own.getDeviceId()); }
        catch(Exception failure) { storageLock.unlock();throw failure; }
        LegacyOmemoRecords wrapped=new LegacyOmemoRecords() {
            final Thread owner=Thread.currentThread();boolean closed;
            void check() { if(closed || Thread.currentThread()!=owner) throw new IllegalStateException("Legacy transaction belongs to its opening thread"); }
            public byte[] load(int kind,String peer,int id) throws Exception { check();return tx.load(kind,peer,id); }
            public int[] ids(int kind,String peer) throws Exception { check();return tx.ids(kind,peer); }
            public void save(int kind,String peer,int id,byte[] value) throws Exception {
                check();try { tx.save(kind,peer,id,value); } catch(Exception failure) { poison(failure);throw failure; }
            }
            public void purge() throws Exception { check();try { tx.purge(); } catch(Exception failure) { poison(failure);throw failure; } }
            void poison(Exception failure) { Context context=transactions.get(this);if(context!=null && context.failure==null) context.failure=new IOException("Legacy transaction write failed",failure); }
            public void commit() throws Exception {
                check();try { Context context=transactions.get(this);if(context.failure!=null) throw context.failure;tx.commit(); } finally { close(); }
            }
            public void close() throws Exception {
                if(closed) return;check();closed=true;
                try { tx.close(); } finally { transactions.remove(this);storageLock.unlock(); }
            }
        };
        transactions.put(wrapped,new Context(own,wrapped));return wrapped;
    }
    /** Use a transaction obtained from begin(), under the caller's OmemoManager monitor; commit after return. */
    public <T> T withTransaction(OmemoDevice own,LegacyOmemoRecords tx,Operation<T> operation) throws Exception {
        if(active.get()!=null) throw new IllegalStateException("Nested legacy transaction context");
        if(!storageLock.isHeldByCurrentThread()) throw new IllegalStateException("Use this store's begin() on the opening thread");
        Context context=transactions.get(tx);
        if(context==null || !context.own.equals(own)) throw new IllegalArgumentException("Legacy transaction belongs to another store or device");
        active.set(context);
        try { T value=operation.run();if(context.failure!=null) throw context.failure;return value; }
        finally { active.remove(); }
    }
    protected final boolean transactionActive() { return active.get()!=null; }
    private <T> T access(OmemoDevice own,Operation<T> operation) throws Exception {
        Context context=active.get();
        if(context!=null) {
            if(!context.own.equals(own)) throw new IOException("Legacy transaction belongs to another device");
            return operation.run();
        }
        try(LegacyOmemoRecords tx=begin(own)) {
            T value=withTransaction(own,tx,operation);tx.commit();return value;
        }
    }
    private IOException failed(Exception cause) {
        IOException failure=cause instanceof IOException ? (IOException)cause : new IOException("Legacy OMEMO storage failed",cause);
        Context context=active.get();if(context!=null && context.failure==null) context.failure=failure;return failure;
    }
    private <T> T io(OmemoDevice own,Operation<T> operation) throws IOException {
        try { return access(own,operation); } catch(Exception failure) { throw failed(failure); }
    }
    private <T> T keyIo(OmemoDevice own,Operation<T> operation) throws IOException,CorruptedOmemoKeyException {
        try { return access(own,operation); }
        catch(CorruptedOmemoKeyException corrupt) { failed(corrupt);throw corrupt; }
        catch(Exception failure) { throw failed(failure); }
    }
    private void remove(OmemoDevice own,Operation<Void> operation) {
        try { io(own,operation); } catch(IOException failure) { throw new IllegalStateException("Legacy OMEMO removal failed",failure); }
    }
    private boolean purged() throws Exception { return active.get().tx.load(PURGED,"",0)!=null; }
    private byte[] read(int kind,String peer,int id,Operation<byte[]> old) throws Exception {
        byte[] encoded=active.get().tx.load(kind,peer,id);
        if(encoded==null) {
            byte[] bytes=purged() ? null : old.run();
            if(bytes!=null && bytes.length>MAX_RECORD_BYTES-1) throw new IOException("Bootstrap legacy record exceeds capacity");
            return bytes;
        }
        if(encoded.length==1 && encoded[0]==0) return null;
        if(encoded.length<2 || encoded[0]!=1 || encoded.length>MAX_RECORD_BYTES) throw new IOException("Invalid legacy overlay record");
        return Arrays.copyOfRange(encoded,1,encoded.length);
    }
    private void write(int kind,String peer,int id,byte[] bytes) throws Exception {
        if(bytes!=null && bytes.length>MAX_RECORD_BYTES-1) throw new IOException("Legacy Signal record exceeds capacity");
        byte[] encoded=bytes==null ? new byte[]{0} : new byte[bytes.length+1];
        if(bytes!=null) { encoded[0]=1;System.arraycopy(bytes,0,encoded,1,bytes.length); }
        active.get().tx.save(kind,peer,id,encoded);
    }
    private byte[] number(long value) { return ByteBuffer.allocate(8).putLong(value).array(); }
    private Long number(byte[] bytes) throws IOException {
        if(bytes==null) return null;if(bytes.length!=8) throw new IOException("Invalid legacy numeric record");return ByteBuffer.wrap(bytes).getLong();
    }
    private byte[] date(Date date) { return date==null ? null : number(date.getTime()); }
    private Date date(byte[] bytes) throws IOException { Long value=number(bytes);return value==null ? null : new Date(value); }
    @Override public SignalOmemoKeyUtil keyUtil() { return keys; }
    @Override public SortedSet<Integer> localDeviceIdsOf(BareJid owner) {
        storageLock.lock();
        try {
            SortedSet<Integer> ids=new TreeSet<>(bootstrap.localDeviceIdsOf(owner));ids.addAll(records.localDevices(owner.toString()));
            SortedSet<Integer> initialized=new TreeSet<>();
            for(int id:ids) if(loadOmemoIdentityKeyPair(new OmemoDevice(owner,id))!=null) initialized.add(id);
            return initialized;
        } catch(Exception failure) { throw new IllegalStateException("Cannot enumerate legacy identities",failure); }
        finally { storageLock.unlock(); }
    }
    @Override public IdentityKeyPair loadOmemoIdentityKeyPair(OmemoDevice own) throws IOException,CorruptedOmemoKeyException {
        return keyIo(own,() -> {
            byte[] bytes=read(IDENTITY_PAIR,"",0,() -> { IdentityKeyPair value=bootstrap.loadOmemoIdentityKeyPair(own);return value==null ? null : keys.identityKeyPairToBytes(value); });
            return bytes==null ? null : keys.identityKeyPairFromBytes(bytes);
        });
    }
    @Override public void storeOmemoIdentityKeyPair(OmemoDevice own,IdentityKeyPair value) throws IOException { io(own,() -> { write(IDENTITY_PAIR,"",0,value==null ? null : keys.identityKeyPairToBytes(value));return null; }); }
    @Override public void removeOmemoIdentityKeyPair(OmemoDevice own) { remove(own,() -> { write(IDENTITY_PAIR,"",0,null);return null; }); }
    @Override public IdentityKey loadOmemoIdentityKey(OmemoDevice own,OmemoDevice contact) throws IOException,CorruptedOmemoKeyException {
        return keyIo(own,() -> {
            byte[] bytes=read(IDENTITY,contact.getJid().toString(),contact.getDeviceId(),() -> { IdentityKey value=bootstrap.loadOmemoIdentityKey(own,contact);return value==null ? null : keys.identityKeyToBytes(value); });
            return bytes==null ? null : keys.identityKeyFromBytes(bytes);
        });
    }
    @Override public void storeOmemoIdentityKey(OmemoDevice own,OmemoDevice contact,IdentityKey value) throws IOException { io(own,() -> { write(IDENTITY,contact.getJid().toString(),contact.getDeviceId(),value==null ? null : keys.identityKeyToBytes(value));return null; }); }
    @Override public void removeOmemoIdentityKey(OmemoDevice own,OmemoDevice contact) { remove(own,() -> { write(IDENTITY,contact.getJid().toString(),contact.getDeviceId(),null);return null; }); }
    @Override public PreKeyRecord loadOmemoPreKey(OmemoDevice own,int id) throws IOException {
        return io(own,() -> {
            byte[] bytes=read(PRE_KEY,"",id,() -> { PreKeyRecord value=bootstrap.loadOmemoPreKey(own,id);return value==null ? null : keys.preKeyToBytes(value); });
            return bytes==null ? null : keys.preKeyFromBytes(bytes);
        });
    }
    @Override public void storeOmemoPreKey(OmemoDevice own,int id,PreKeyRecord value) throws IOException { io(own,() -> { write(PRE_KEY,"",id,value==null ? null : keys.preKeyToBytes(value));return null; }); }
    @Override public void removeOmemoPreKey(OmemoDevice own,int id) { remove(own,() -> { write(PRE_KEY,"",id,null);return null; }); }
    @Override public SignedPreKeyRecord loadOmemoSignedPreKey(OmemoDevice own,int id) throws IOException {
        return io(own,() -> {
            byte[] bytes=read(SIGNED_PRE_KEY,"",id,() -> { SignedPreKeyRecord value=bootstrap.loadOmemoSignedPreKey(own,id);return value==null ? null : keys.signedPreKeyToBytes(value); });
            return bytes==null ? null : keys.signedPreKeyFromBytes(bytes);
        });
    }
    @Override public void storeOmemoSignedPreKey(OmemoDevice own,int id,SignedPreKeyRecord value) throws IOException { io(own,() -> { write(SIGNED_PRE_KEY,"",id,value==null ? null : keys.signedPreKeyToBytes(value));return null; }); }
    @Override public void removeOmemoSignedPreKey(OmemoDevice own,int id) { remove(own,() -> { write(SIGNED_PRE_KEY,"",id,null);return null; }); }
    @Override public SessionRecord loadRawSession(OmemoDevice own,OmemoDevice contact) throws IOException {
        return io(own,() -> {
            byte[] bytes=read(SESSION,contact.getJid().toString(),contact.getDeviceId(),() -> { SessionRecord value=bootstrap.loadRawSession(own,contact);return value==null ? null : keys.rawSessionToBytes(value); });
            return bytes==null ? null : keys.rawSessionFromBytes(bytes);
        });
    }
    @Override public void storeRawSession(OmemoDevice own,OmemoDevice contact,SessionRecord value) throws IOException { io(own,() -> { write(SESSION,contact.getJid().toString(),contact.getDeviceId(),value==null ? null : keys.rawSessionToBytes(value));return null; }); }
    @Override public void removeRawSession(OmemoDevice own,OmemoDevice contact) { remove(own,() -> { write(SESSION,contact.getJid().toString(),contact.getDeviceId(),null);return null; }); }
    @Override public Date getDateOfLastReceivedMessage(OmemoDevice own,OmemoDevice contact) throws IOException { return io(own,() -> date(read(LAST_RECEIVED,contact.getJid().toString(),contact.getDeviceId(),() -> date(bootstrap.getDateOfLastReceivedMessage(own,contact))))); }
    @Override public void setDateOfLastReceivedMessage(OmemoDevice own,OmemoDevice contact,Date value) throws IOException { io(own,() -> { write(LAST_RECEIVED,contact.getJid().toString(),contact.getDeviceId(),date(value));return null; }); }
    @Override public Date getDateOfLastDeviceIdPublication(OmemoDevice own,OmemoDevice contact) throws IOException { return io(own,() -> date(read(LAST_PUBLISHED,contact.getJid().toString(),contact.getDeviceId(),() -> date(bootstrap.getDateOfLastDeviceIdPublication(own,contact))))); }
    @Override public void setDateOfLastDeviceIdPublication(OmemoDevice own,OmemoDevice contact,Date value) throws IOException { io(own,() -> { write(LAST_PUBLISHED,contact.getJid().toString(),contact.getDeviceId(),date(value));return null; }); }
    @Override public Date getDateOfLastSignedPreKeyRenewal(OmemoDevice own) throws IOException { return io(own,() -> date(read(LAST_RENEWAL,"",0,() -> date(bootstrap.getDateOfLastSignedPreKeyRenewal(own))))); }
    @Override public void setDateOfLastSignedPreKeyRenewal(OmemoDevice own,Date value) throws IOException { io(own,() -> { write(LAST_RENEWAL,"",0,date(value));return null; }); }
    @Override public int loadOmemoMessageCounter(OmemoDevice own,OmemoDevice contact) throws IOException {
        return io(own,() -> { Long value=number(read(MESSAGE_COUNTER,contact.getJid().toString(),contact.getDeviceId(),() -> number(bootstrap.loadOmemoMessageCounter(own,contact))));return value==null ? 0 : Math.toIntExact(value); });
    }
    @Override public void storeOmemoMessageCounter(OmemoDevice own,OmemoDevice contact,int counter) throws IOException { io(own,() -> { write(MESSAGE_COUNTER,contact.getJid().toString(),contact.getDeviceId(),number(counter));return null; }); }
    @Override public TreeMap<Integer,PreKeyRecord> loadOmemoPreKeys(OmemoDevice own) throws IOException {
        return io(own,() -> {
            TreeMap<Integer,PreKeyRecord> values=purged() ? new TreeMap<>() : bootstrap.loadOmemoPreKeys(own);
            for(int id:active.get().tx.ids(PRE_KEY,"")) {
                byte[] bytes=read(PRE_KEY,"",id,() -> null);
                if(bytes==null) values.remove(id);else values.put(id,keys.preKeyFromBytes(bytes));
            }
            return values;
        });
    }
    @Override public TreeMap<Integer,SignedPreKeyRecord> loadOmemoSignedPreKeys(OmemoDevice own) throws IOException {
        return io(own,() -> {
            TreeMap<Integer,SignedPreKeyRecord> values=purged() ? new TreeMap<>() : bootstrap.loadOmemoSignedPreKeys(own);
            for(int id:active.get().tx.ids(SIGNED_PRE_KEY,"")) {
                byte[] bytes=read(SIGNED_PRE_KEY,"",id,() -> null);
                if(bytes==null) values.remove(id);else values.put(id,keys.signedPreKeyFromBytes(bytes));
            }
            return values;
        });
    }
    @Override public HashMap<Integer,SessionRecord> loadAllRawSessionsOf(OmemoDevice own,BareJid peer) throws IOException {
        return io(own,() -> {
            HashMap<Integer,SessionRecord> values=purged() ? new HashMap<>() : bootstrap.loadAllRawSessionsOf(own,peer);
            for(int id:active.get().tx.ids(SESSION,peer.toString())) {
                byte[] bytes=read(SESSION,peer.toString(),id,() -> null);
                if(bytes==null) values.remove(id);else values.put(id,keys.rawSessionFromBytes(bytes));
            }
            return values;
        });
    }
    @Override public void removeAllRawSessionsOf(OmemoDevice own,BareJid peer) {
        remove(own,() -> { for(int id:loadAllRawSessionsOf(own,peer).keySet()) write(SESSION,peer.toString(),id,null);return null; });
    }
    @Override public boolean containsRawSession(OmemoDevice own,OmemoDevice contact) {
        try { return loadRawSession(own,contact)!=null; } catch(IOException failure) { throw new IllegalStateException("Cannot read legacy session",failure); }
    }
    private byte[] encodeList(OmemoCachedDeviceList list) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        for(Set<Integer> ids:Arrays.asList(list.getActiveDevices(),list.getInactiveDevices())) { out.writeInt(ids.size());for(int id:new TreeSet<>(ids)) out.writeInt(id); }
        return bytes.toByteArray();
    }
    private OmemoCachedDeviceList decodeList(byte[] bytes) throws IOException {
        if(bytes==null) return new OmemoCachedDeviceList();
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));List<Set<Integer>> sets=new ArrayList<>();
        for(int part=0;part<2;part++) {
            int count=in.readInt();if(count<0 || count>in.available()/4) throw new IOException("Invalid legacy device list");
            Set<Integer> ids=new HashSet<>();for(int i=0;i<count;i++) ids.add(in.readInt());sets.add(ids);
        }
        if(in.read()!=-1) throw new IOException("Trailing legacy device-list data");return new OmemoCachedDeviceList(sets.get(0),sets.get(1));
    }
    @Override public OmemoCachedDeviceList loadCachedDeviceList(OmemoDevice own,BareJid peer) throws IOException {
        Objects.requireNonNull(peer);return io(own,() -> decodeList(read(DEVICE_LIST,peer.toString(),0,() -> encodeList(bootstrap.loadCachedDeviceList(own,peer)))));
    }
    @Override public void storeCachedDeviceList(OmemoDevice own,BareJid peer,OmemoCachedDeviceList list) throws IOException {
        if(peer==null) return;io(own,() -> { write(DEVICE_LIST,peer.toString(),0,list==null ? null : encodeList(list));return null; });
    }
    @Override public void purgeOwnDeviceKeys(OmemoDevice own) {
        remove(own,() -> { active.get().tx.purge();active.get().tx.save(PURGED,"",0,new byte[]{1});return null; });
    }
}
