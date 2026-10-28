// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.ChatObject;
import org.telegram.tgnet.TLRPC;

/** Local presentation model for Telegram's group-call sheet. Never an RPC call handle. */
public final class MonogramMujiCall extends ChatObject.Call {
    public MonogramMujiCall(int account, long room, long self, String title, boolean video) {
        currentAccount = AccountInstance.getInstance(account);
        chatId = -room;
        call = new TLRPC.TL_groupCall();
        call.id = android.os.SystemClock.elapsedRealtime();
        call.title = title;
        call.version = 1;
        call.can_start_video = video;
        call.unmuted_video_limit = 8;
        membersLoadEndReached = true;
        selfPeer = new TLRPC.TL_peerUser();
        selfPeer.user_id = self;
        createNoVideoParticipant();
    }

    public TLRPC.GroupCallParticipant participant(long id, boolean self) {
        TLRPC.GroupCallParticipant p = participants.get(id);
        if (p == null) {
            p = new TLRPC.TL_groupCallParticipant();
            p.peer = new TLRPC.TL_peerUser();
            p.peer.user_id = id;
            p.self = self;
            p.can_self_unmute = true;
            p.volume = 10000;
            p.date = (int) (System.currentTimeMillis() / 1000);
            participants.put(id, p);
        }
        return p;
    }

    public static void video(TLRPC.GroupCallParticipant p, String endpoint, boolean active) {
        if (active) {
            if (p.video == null) p.video = new TLRPC.TL_groupCallParticipantVideo();
            p.video.endpoint = endpoint;
            p.videoEndpoint = endpoint;
            p.video.paused = false;
            p.video_joined = true;
        } else {
            p.video = null;
            p.videoEndpoint = null;
            p.hasCameraFrame = ChatObject.VIDEO_FRAME_NO_FRAME;
        }
    }

    @Override public void loadMembers(boolean fromBegin) {}
    @Override public void reloadGroupCall() {}
    @Override public void setTitle(String title) {}
    @Override public void toggleRecord(String title, int type) {}
    @Override public TLRPC.InputGroupCall getInputGroupCall() { return null; }
    @Override public TLRPC.InputGroupCall getInputGroupCall(boolean bySlug) { return null; }
}
