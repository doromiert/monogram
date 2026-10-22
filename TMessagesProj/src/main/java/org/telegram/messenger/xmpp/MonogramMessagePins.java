// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.tgnet.*;

/** Private device-local message pins. No pin metadata is sent to other participants. */
public final class MonogramMessagePins {
    private MonogramMessagePins() {}
    private static SharedPreferences prefs(int account) {
        return ApplicationLoader.applicationContext.getSharedPreferences("monogram-message-pins-"+account, Context.MODE_PRIVATE);
    }
    private static String key(String owner, String peer) {
        if(owner==null || owner.isEmpty() || peer==null || peer.isEmpty()) throw new IllegalArgumentException("Unknown pin owner or chat");
        return owner+"\n"+peer;
    }
    static synchronized Set<String> ids(int account,String owner,String peer) {
        return new HashSet<>(prefs(account).getStringSet(key(owner,peer),Collections.emptySet()));
    }
    static synchronized void save(int account,String owner,String peer,int id,boolean pin,boolean clear) {
        if(!owner.equals(MonogramController.get(account).accountJid())) throw new IllegalStateException("Account changed");
        Set<String> ids=clear ? new HashSet<>() : ids(account,owner,peer);
        if(!clear) {
            if(id<=0) throw new IllegalArgumentException("Only sent or received messages can be pinned");
            if(pin) ids.add(Integer.toString(id)); else ids.remove(Integer.toString(id));
        }
        if(!prefs(account).edit().putStringSet(key(owner,peer),ids).commit()) throw new IllegalStateException("Could not save message pins");
    }
    private static long peer(TLRPC.InputPeer peer,long self) {
        if(peer instanceof TLRPC.TL_inputPeerSelf) return self;
        return peer==null ? 0 : peer.channel_id!=0 ? -peer.channel_id : peer.chat_id!=0 ? -peer.chat_id : peer.user_id;
    }
    static TLRPC.TL_messages_messagesSlice search(int account,String owner,MonogramStore store,long self,TLRPC.TL_messages_search request) {
        long peer=peer(request.peer,self);
        TLRPC.TL_messages_messagesSlice result=new TLRPC.TL_messages_messagesSlice();
        ArrayList<TLRPC.Message> found=new ArrayList<>();
        for(String value:ids(account,owner,store.jid(peer))) {
            try {
                int id=Integer.parseInt(value);if(store.hiddenMessage(id)) continue;
                TLRPC.Message message=store.getMessage(id,self);
                if(message.dialog_id!=peer) continue;
                message.pinned=true;found.add(message);
            } catch(IllegalArgumentException unavailable) { /* Deleted or unavailable messages are not pins. */ }
        }
        found.sort((a,b)->Integer.compare(b.id,a.id));result.count=found.size();
        int limit=Math.max(1,Math.min(request.limit,100)),skip=Math.max(0,request.add_offset);
        for(TLRPC.Message message:found) if(request.offset_id==0 || message.id<request.offset_id) {
            if(skip>0) { skip--;continue; }
            if(result.messages.size()>=limit) break;result.messages.add(message);
        }
        return result;
    }
    static TLObject handle(int account,String owner,MonogramStore store,long self,TLObject request) {
        if(request instanceof TLRPC.TL_messages_updatePinnedMessage) {
            TLRPC.TL_messages_updatePinnedMessage pin=(TLRPC.TL_messages_updatePinnedMessage)request;
            long peer=peer(pin.peer,self);TLRPC.Message message=store.getMessage(pin.id,self);
            if(message.dialog_id!=peer || store.hiddenMessage(pin.id)) throw new IllegalArgumentException("Message is unavailable in this chat");
            save(account,owner,store.jid(peer),pin.id,!pin.unpin,false);
            MonogramController.Updates result=new MonogramController.Updates();result.date=(int)(System.currentTimeMillis()/1000);return result;
        }
        if(request instanceof TLRPC.TL_messages_unpinAllMessages) {
            long peer=peer(((TLRPC.TL_messages_unpinAllMessages)request).peer,self);
            save(account,owner,store.jid(peer),0,false,true);
            return new TLRPC.TL_messages_affectedHistory();
        }
        return null;
    }
}
