// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import org.telegram.tgnet.TLRPC;

/** Merge the native durable outbox with XMPP history without recreating a second send queue. */
final class MonogramOutbox {
    private final LinkedHashMap<Integer,TLRPC.Message> completed = new LinkedHashMap<>();
    synchronized void completed(int localId,TLRPC.Message message) {
        if(localId>=0 || message.id<=0 || !message.out) return;
        completed.put(localId,message);
        while(completed.size()>1024) completed.remove(completed.keySet().iterator().next());
    }
    synchronized void clear() { completed.clear(); }
    synchronized void merge(long peer,ArrayList<TLRPC.Message> history,ArrayList<TLRPC.Message> pending) {
        HashSet<Integer> ids=new HashSet<>();
        for(TLRPC.Message message:history) ids.add(message.id);
        ArrayList<TLRPC.Message> outgoing=new ArrayList<>();
        for(TLRPC.Message queued:pending) {
            if(queued.dialog_id!=peer || queued.id>=0 || !queued.out || queued.send_state==0) continue;
            TLRPC.Message message=completed.getOrDefault(queued.id,queued);
            if(message.dialog_id==peer && ids.add(message.id)) outgoing.add(message);
        }
        outgoing.sort((a,b) -> a.date!=b.date ? Integer.compare(b.date,a.date) : Integer.compare(a.id,b.id));
        ArrayList<TLRPC.Message> combined=new ArrayList<>(); int h=0;
        for(TLRPC.Message message:outgoing) {
            while(h<history.size() && history.get(h).date>message.date) combined.add(history.get(h++));
            combined.add(message);
        }
        while(h<history.size()) combined.add(history.get(h++));
        history.clear(); history.addAll(combined);
    }
}
