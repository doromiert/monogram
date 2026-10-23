// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

/** Telegram's inputPeerSelf has no wire ID; the XMPP adapter needs the account-local peer ID. */
public final class MonogramSelfChat {
    private MonogramSelfChat() {}
    public static TLRPC.InputPeer resolve(TLRPC.InputPeer peer,long self) {
        if(peer instanceof TLRPC.TL_inputPeerSelf) {
            if(self<=0) throw new IllegalStateException("Sign in before opening Saved Messages");
            peer.user_id=self;
        }
        return peer;
    }
    static void resolve(TLObject request,long self) {
        TLRPC.InputPeer peer=null;
        if(request instanceof TLRPC.TL_messages_sendMessage) peer=((TLRPC.TL_messages_sendMessage)request).peer;
        else if(request instanceof TLRPC.TL_messages_sendMedia) peer=((TLRPC.TL_messages_sendMedia)request).peer;
        else if(request instanceof TLRPC.TL_messages_getHistory) peer=((TLRPC.TL_messages_getHistory)request).peer;
        else if(request instanceof TLRPC.TL_messages_search) {
            TLRPC.TL_messages_search query=(TLRPC.TL_messages_search)request;
            peer=query.peer;resolve(query.from_id,self);
        }
        else if(request instanceof TLRPC.TL_messages_editMessage) peer=((TLRPC.TL_messages_editMessage)request).peer;
        else if(request instanceof TLRPC.TL_messages_getMessageEditData) peer=((TLRPC.TL_messages_getMessageEditData)request).peer;
        else if(request instanceof TLRPC.TL_messages_sendVote) peer=((TLRPC.TL_messages_sendVote)request).peer;
        else if(request instanceof TLRPC.TL_messages_getPollResults) peer=((TLRPC.TL_messages_getPollResults)request).peer;
        else if(request instanceof TLRPC.TL_messages_getPollVotes) peer=((TLRPC.TL_messages_getPollVotes)request).peer;
        else if(request instanceof TLRPC.TL_messages_sendReaction) peer=((TLRPC.TL_messages_sendReaction)request).peer;
        else if(request instanceof TLRPC.TL_messages_getMessagesReactions) peer=((TLRPC.TL_messages_getMessagesReactions)request).peer;
        else if(request instanceof TLRPC.TL_messages_getMessageReactionsList) peer=((TLRPC.TL_messages_getMessageReactionsList)request).peer;
        else if(request instanceof TLRPC.TL_messages_translateText) peer=((TLRPC.TL_messages_translateText)request).peer;
        resolve(peer,self);
    }
}
