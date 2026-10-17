// SPDX-License-Identifier: GPL-3.0-or-later
package org.jivesoftware.smackx.omemo;

import java.io.IOException;
import java.util.*;
import org.jivesoftware.smackx.omemo.element.OmemoDeviceListElement;
import org.jivesoftware.smackx.omemo.element.OmemoDeviceListElement_VAxolotl;
import org.jivesoftware.smackx.omemo.internal.OmemoCachedDeviceList;
import org.jivesoftware.smackx.omemo.internal.OmemoDevice;
import org.jivesoftware.smackx.omemo.signal.SignalFileBasedOmemoStore;
import org.jivesoftware.smackx.omemo.signal.SignalOmemoKeyUtil;
import org.jxmpp.jid.BareJid;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.SessionCipher;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.ecc.ECPublicKey;
import org.whispersystems.libsignal.state.PreKeyBundle;
import org.whispersystems.libsignal.state.PreKeyRecord;
import org.whispersystems.libsignal.state.SessionRecord;
import org.whispersystems.libsignal.state.SignedPreKeyRecord;

/** Orders remote fetch results against Smack's PEP merges without holding a lock across network I/O. */
public final class MonogramDeviceLists extends CachingOmemoStore<IdentityKeyPair,IdentityKey,PreKeyRecord,
        SignedPreKeyRecord,SessionRecord,SignalProtocolAddress,ECPublicKey,PreKeyBundle,SessionCipher> {
    private final Object listLock=new Object();
    private final Map<OmemoDevice,Map<BareJid,Long>> revisions=new HashMap<>();
    private final Map<OmemoDevice,Long> resets=new HashMap<>();
    private long clock;

    public MonogramDeviceLists(SignalFileBasedOmemoStore persistent) { super(persistent); }
    public MonogramDeviceLists() { super(new SignalOmemoKeyUtil()); }

    public static final class Snapshot {
        public final BareJid contact;
        public final long revision;
        public final Set<OmemoDevice> devices;
        private final MonogramDeviceLists store;
        private final OmemoDevice own;
        private final OmemoManager manager;
        private Snapshot(MonogramDeviceLists store,OmemoDevice own,BareJid contact,long revision,OmemoCachedDeviceList list,OmemoManager manager) {
            this.store=store;this.own=own;this.contact=contact;this.revision=revision;this.manager=manager;
            this.devices=devices(contact,list);
        }
    }
    public static Snapshot snapshot(OmemoManager manager,BareJid contact) throws IOException {
        return store(manager).snapshot(manager.getOwnDevice(),contact,manager);
    }
    public static Set<OmemoDevice> apply(OmemoManager manager,Snapshot snapshot,OmemoDeviceListElement fetched) throws IOException {
        MonogramDeviceLists store=store(manager);
        if(snapshot==null || snapshot.manager!=manager || snapshot.store!=store || !snapshot.own.equals(manager.getOwnDevice()))
            throw new ConcurrentModificationException("OMEMO session changed during device-list lookup");
        return store.applyIfCurrent(snapshot,fetched);
    }
    private static MonogramDeviceLists store(OmemoManager manager) {
        Object store=manager.getOmemoService().getOmemoStoreBackend();
        if(!(store instanceof MonogramDeviceLists)) throw new IllegalStateException("Tracked OMEMO device-list store is not installed");
        return (MonogramDeviceLists)store;
    }
    public Snapshot snapshot(OmemoDevice own,BareJid contact) throws IOException { return snapshot(own,contact,null); }
    private Snapshot snapshot(OmemoDevice own,BareJid contact,OmemoManager manager) throws IOException {
        if(own==null || contact==null || own.getJid().equals(contact)) throw new IllegalArgumentException("Use Smack's own-device-list lifecycle");
        synchronized(listLock) {
            return new Snapshot(this,own,contact,revision(own,contact),super.loadCachedDeviceList(own,contact),manager);
        }
    }
    public Set<OmemoDevice> applyIfCurrent(Snapshot snapshot,OmemoDeviceListElement fetched) throws IOException {
        synchronized(listLock) {
            if(snapshot==null || snapshot.store!=this || revision(snapshot.own,snapshot.contact)!=snapshot.revision)
                throw new ConcurrentModificationException("OMEMO device list changed during lookup; retry");
            OmemoDeviceListElement list=fetched==null ? new OmemoDeviceListElement_VAxolotl(Collections.emptySet()) : fetched;
            return devices(snapshot.contact,mergeCachedDeviceList(snapshot.own,snapshot.contact,list));
        }
    }
    private static Set<OmemoDevice> devices(BareJid contact,OmemoCachedDeviceList list) {
        Set<OmemoDevice> devices=new HashSet<>();
        for(int id:list.getActiveDevices()) devices.add(new OmemoDevice(contact,id));
        return Collections.unmodifiableSet(devices);
    }
    private long revision(OmemoDevice own,BareJid contact) {
        Map<BareJid,Long> contacts=revisions.get(own);
        return Math.max(resets.getOrDefault(own,0L),contacts==null ? 0L : contacts.getOrDefault(contact,0L));
    }
    private long nextRevision() { clock=Math.incrementExact(clock);return clock; }
    private void changed(OmemoDevice own,BareJid contact) { revisions.computeIfAbsent(own,key -> new HashMap<>()).put(contact,nextRevision()); }

    // PEP callbacks call this package-private hook directly, without taking OmemoManager's monitor.
    @Override OmemoCachedDeviceList mergeCachedDeviceList(OmemoDevice own,BareJid contact,OmemoDeviceListElement list) throws IOException {
        synchronized(listLock) {
            changed(own,contact);
            return super.mergeCachedDeviceList(own,contact,list);
        }
    }
    @Override public OmemoCachedDeviceList loadCachedDeviceList(OmemoDevice own,BareJid contact) throws IOException {
        synchronized(listLock) { return super.loadCachedDeviceList(own,contact); }
    }
    @Override public void storeCachedDeviceList(OmemoDevice own,BareJid contact,OmemoCachedDeviceList list) throws IOException {
        synchronized(listLock) {
            changed(own,contact);super.storeCachedDeviceList(own,contact,list);
        }
    }
    @Override public void purgeOwnDeviceKeys(OmemoDevice own) {
        synchronized(listLock) {
            resets.put(own,nextRevision());revisions.remove(own);super.purgeOwnDeviceKeys(own);
        }
    }
}
