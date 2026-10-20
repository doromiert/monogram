// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Coalesce one account/connection/address without allowing an old fetch to finish its replacement. */
final class MonogramProfileLoads {
    static final class Load {
        final Object connection;
        final long owner,epoch;
        final String address;
        final List<Runnable> callbacks=new ArrayList<>();
        Load(Object connection,long owner,long epoch,String address) { this.connection=connection;this.owner=owner;this.epoch=epoch;this.address=address; }
    }
    private final Map<Long,Load> loads=new HashMap<>();
    synchronized Load begin(long peer,Object connection,long owner,long epoch,String address,Runnable callback) {
        Load previous=loads.get(peer);
        if(previous!=null && previous.owner==owner && previous.epoch==epoch && previous.connection==connection && previous.address.equals(address)) {
            if(callback!=null) previous.callbacks.add(callback);return null;
        }
        Load load=new Load(connection,owner,epoch,address);
        if(previous!=null && previous.owner==owner && previous.address.equals(address)) {
            load.callbacks.addAll(previous.callbacks);previous.callbacks.clear();
        }
        if(callback!=null) load.callbacks.add(callback);
        loads.put(peer,load);return load;
    }
    synchronized boolean current(long peer,Load load) { return loads.get(peer)==load; }
    synchronized List<Runnable> finish(long peer,Load load) {
        if(!current(peer,load)) return null;
        loads.remove(peer);return new ArrayList<>(load.callbacks);
    }
    synchronized void clear() { loads.clear(); }
}
