package org.jivesoftware.smackx.omemo;

import java.io.*;
import com.negzero.monogram.xmpp.LegacyOmemoRecords;
import com.negzero.monogram.xmpp.LegacyOmemoTestDatabase;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.jivesoftware.smackx.omemo.element.OmemoDeviceListElement_VAxolotl;
import org.jivesoftware.smackx.omemo.internal.OmemoCachedDeviceList;
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;
import org.jivesoftware.smackx.omemo.signal.SignalFileBasedOmemoStore;
import org.jxmpp.jid.BareJid;
import org.jxmpp.jid.impl.JidCreate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MonogramDeviceListsTest {
    final OmemoDevice own=new OmemoDevice(JidCreate.bareFromOrThrowUnchecked("alice@example.org"),100);
    final BareJid contact=JidCreate.bareFromOrThrowUnchecked("bob@example.org");
    @TempDir Path directory;
    OmemoDeviceListElement_VAxolotl list(Integer... devices) { return new OmemoDeviceListElement_VAxolotl(new HashSet<>(Arrays.asList(devices))); }
    Set<Integer> ids(Set<OmemoDevice> devices) { Set<Integer> ids=new HashSet<>();for(OmemoDevice device:devices) ids.add(device.getDeviceId());return ids; }

    MonogramDeviceLists store() { return new MonogramDeviceLists(new SignalFileBasedOmemoStore(directory.toFile()),new LegacyOmemoTestDatabase()); }

    @Test void originalMergePreservesInactiveHistoryPublicationDatesAndSignalStoreCompatibility() throws Exception {
        MonogramDeviceLists store=store();assertTrue(store instanceof MonogramSignalStore);
        store.mergeCachedDeviceList(own,contact,list(11,12));Date original=new Date(12345);
        store.setDateOfLastDeviceIdPublication(own,new OmemoDevice(contact,11),original);
        MonogramDeviceLists.Snapshot before=store.snapshot(own,contact);
        assertThrows(UnsupportedOperationException.class,() -> before.devices.clear());
        store.mergeCachedDeviceList(own,contact,list(12,13));
        assertEquals(Collections.singleton(11),store.loadCachedDeviceList(own,contact).getInactiveDevices());
        assertThrows(ConcurrentModificationException.class,() -> store.applyIfCurrent(before,list(11)));
        Set<OmemoDevice> applied=store.applyIfCurrent(store.snapshot(own,contact),list(11,13));
        assertEquals(new HashSet<>(Arrays.asList(11,13)),ids(applied));
        assertEquals(Collections.singleton(12),store.loadCachedDeviceList(own,contact).getInactiveDevices());
        assertEquals(original,store.getDateOfLastDeviceIdPublication(own,new OmemoDevice(contact,11)));
        assertNotNull(store.getDateOfLastDeviceIdPublication(own,new OmemoDevice(contact,13)));
    }
    @Test void identicalAndAbaPepMergesInvalidateFetchTokensEvenWhenActiveSetMatchesAgain() throws Exception {
        MonogramDeviceLists store=store();store.mergeCachedDeviceList(own,contact,list(11));
        MonogramDeviceLists.Snapshot identical=store.snapshot(own,contact);
        store.mergeCachedDeviceList(own,contact,list(11));
        assertTrue(store.snapshot(own,contact).revision>identical.revision);
        assertThrows(ConcurrentModificationException.class,() -> store.applyIfCurrent(identical,list(99)));
        MonogramDeviceLists.Snapshot aba=store.snapshot(own,contact);
        store.mergeCachedDeviceList(own,contact,list(12));store.mergeCachedDeviceList(own,contact,list(11));
        assertEquals(aba.devices,store.snapshot(own,contact).devices);
        assertThrows(ConcurrentModificationException.class,() -> store.applyIfCurrent(aba,list(99)));
        assertEquals(Collections.singleton(11),ids(store.snapshot(own,contact).devices));
    }
    @Test void directWritesAndPurgeFenceTokensWithoutInvalidatingOtherContactsOrAccounts() throws Exception {
        MonogramDeviceLists store=store();MonogramDeviceLists.Snapshot original=store.snapshot(own,contact);
        store.storeCachedDeviceList(own,contact,new OmemoCachedDeviceList(Collections.singleton(11),Collections.emptySet()));
        assertThrows(ConcurrentModificationException.class,() -> store.applyIfCurrent(original,list(99)));
        MonogramDeviceLists.Snapshot isolated=store.snapshot(own,contact);
        BareJid other=JidCreate.bareFrom("other@example.org");OmemoDevice anotherOwn=new OmemoDevice(own.getJid(),101);
        store.mergeCachedDeviceList(own,other,list(21));store.mergeCachedDeviceList(anotherOwn,contact,list(31));
        assertEquals(Collections.singleton(11),ids(store.applyIfCurrent(isolated,list(11))));
        MonogramDeviceLists.Snapshot otherPurged=store.snapshot(own,other);
        MonogramDeviceLists.Snapshot purged=store.snapshot(own,contact);store.purgeOwnDeviceKeys(own);
        assertThrows(ConcurrentModificationException.class,() -> store.applyIfCurrent(purged,list(99)));
        assertThrows(ConcurrentModificationException.class,() -> store.applyIfCurrent(otherPurged,list(99)));
        assertTrue(store.snapshot(own,contact).devices.isEmpty());
        assertEquals(Collections.singleton(31),ids(store.snapshot(anotherOwn,contact).devices));
        MonogramDeviceLists replacement=store();
        assertThrows(ConcurrentModificationException.class,() -> replacement.applyIfCurrent(store.snapshot(own,contact),list(99)));
    }
    @Test void ownListKeepsOriginalLifecycleAndMissingRemotePublicationMergesAsEmpty() throws Exception {
        MonogramDeviceLists store=store();
        assertThrows(IllegalArgumentException.class,() -> store.snapshot(own,own.getJid()));
        store.mergeCachedDeviceList(own,own.getJid(),list(100,101));
        assertEquals(new HashSet<>(Arrays.asList(100,101)),store.loadCachedDeviceList(own,own.getJid()).getActiveDevices());
        store.mergeCachedDeviceList(own,contact,list(11));
        assertTrue(store.applyIfCurrent(store.snapshot(own,contact),null).isEmpty());
        assertEquals(Collections.singleton(11),store.loadCachedDeviceList(own,contact).getInactiveDevices());
    }
    @Test void transactionRollbackCannotPublishSpeculativeListObjectsOrKeepStaleCache() throws Exception {
        MonogramDeviceLists store=store();store.mergeCachedDeviceList(own,contact,list(11));
        OmemoCachedDeviceList detached=store.loadCachedDeviceList(own,contact);detached.addDevice(99);
        assertEquals(Collections.singleton(11),store.loadCachedDeviceList(own,contact).getActiveDevices());
        MonogramDeviceLists.Snapshot before=store.snapshot(own,contact);
        try(LegacyOmemoRecords tx=store.begin(own)) {
            store.withTransaction(own,tx,() -> { store.storeCachedDeviceList(own,contact,new OmemoCachedDeviceList(Collections.singleton(12),Collections.singleton(11)));return null; });
        }
        assertEquals(Collections.singleton(11),store.loadCachedDeviceList(own,contact).getActiveDevices());
        assertThrows(ConcurrentModificationException.class,() -> store.applyIfCurrent(before,list(13)));
    }
    static final class BlockingDatabase extends LegacyOmemoTestDatabase {
        final CountDownLatch writing=new CountDownLatch(1),release=new CountDownLatch(1);volatile boolean block;
        @Override public LegacyOmemoRecords begin(String owner,int device) throws Exception {
            LegacyOmemoRecords tx=super.begin(owner,device);
            return new LegacyOmemoRecords() {
                public byte[] load(int kind,String peer,int id) throws Exception { return tx.load(kind,peer,id); }
                public int[] ids(int kind,String peer) throws Exception { return tx.ids(kind,peer); }
                public void save(int kind,String peer,int id,byte[] value) throws Exception {
                    if(kind==DEVICE_LIST && block) {
                        block=false;writing.countDown();
                        if(!release.await(3,TimeUnit.SECONDS)) throw new IOException("Test write timed out");
                    }
                    tx.save(kind,peer,id,value);
                }
                public void purge() throws Exception { tx.purge(); }
                public void commit() throws Exception { tx.commit(); }
                public void close() throws Exception { tx.close(); }
            };
        }
    }
    @Test void pepMergeCannotInterleaveBetweenRevisionCheckAndOriginalMergeAndFilesRemainCompatible() throws Exception {
        SignalFileBasedOmemoStore backend=new SignalFileBasedOmemoStore(directory.toFile());
        BlockingDatabase database=new BlockingDatabase();MonogramDeviceLists store=new MonogramDeviceLists(backend,database);
        Date published=new Date(12345),received=new Date(54321);OmemoDevice first=new OmemoDevice(contact,11);
        backend.setDateOfLastDeviceIdPublication(own,first,published);backend.setDateOfLastReceivedMessage(own,first,received);
        backend.storeCachedDeviceList(own,contact,new OmemoCachedDeviceList(Collections.singleton(11),Collections.emptySet()));
        store.mergeCachedDeviceList(own,contact,list(11));MonogramDeviceLists.Snapshot token=store.snapshot(own,contact);
        ExecutorService threads=Executors.newFixedThreadPool(2);CountDownLatch pepStarted=new CountDownLatch(1);database.block=true;
        try {
            Future<?> applying=threads.submit(() -> { try { store.applyIfCurrent(token,list(12)); } catch(IOException error) { throw new CompletionException(error); } });
            assertTrue(database.writing.await(3,TimeUnit.SECONDS));
            Future<?> pep=threads.submit(() -> { pepStarted.countDown();try { store.mergeCachedDeviceList(own,contact,list(13)); } catch(IOException error) { throw new CompletionException(error); } });
            assertTrue(pepStarted.await(3,TimeUnit.SECONDS));assertFalse(pep.isDone());database.release.countDown();
            applying.get(3,TimeUnit.SECONDS);pep.get(3,TimeUnit.SECONDS);
            assertEquals(Collections.singleton(13),ids(store.snapshot(own,contact).devices));
            MonogramDeviceLists reopened=new MonogramDeviceLists(new SignalFileBasedOmemoStore(directory.toFile()),database);
            assertEquals(Collections.singleton(13),ids(reopened.snapshot(own,contact).devices));
            assertEquals(new HashSet<>(Arrays.asList(11,12)),reopened.loadCachedDeviceList(own,contact).getInactiveDevices());
            assertEquals(published,reopened.getDateOfLastDeviceIdPublication(own,first));
            assertEquals(received,reopened.getDateOfLastReceivedMessage(own,first));
            assertEquals(Collections.singleton(11),backend.loadCachedDeviceList(own,contact).getActiveDevices(),"Bootstrap files must remain read-only");
        } finally { database.release.countDown();threads.shutdownNow();assertTrue(threads.awaitTermination(3,TimeUnit.SECONDS)); }
    }
}
