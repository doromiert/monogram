// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import org.telegram.messenger.voip.VoIPService;

public final class MonogramCallActionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent) {
        MonogramCallService.Controller c=MonogramCallService.matching(intent);
        if(c==null || !MonogramCallService.END.equals(intent.getStringExtra(MonogramCallService.ACTION))) return;
        if(c.getCallState()==VoIPService.STATE_WAITING_INCOMING && !c.accepted) c.declineIncomingCall();else c.hangUp();
    }
}
