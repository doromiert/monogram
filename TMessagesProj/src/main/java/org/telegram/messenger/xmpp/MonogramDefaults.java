// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;
import android.content.SharedPreferences;
/** Apply once per account: upgrades enable features, later user opt-outs persist. */
final class MonogramDefaults {
    static synchronized void apply(SharedPreferences preferences){
        String jid=preferences.getString("jid","");if(jid.isEmpty()||preferences.getBoolean("chat-defaults-v1-"+jid,false))return;
        SharedPreferences.Editor edit=preferences.edit().putBoolean("read-receipts-enabled-"+jid,true)
            .putBoolean("group-read-receipts-enabled-"+jid,true).putBoolean("typing-enabled",true)
            .putBoolean("link-previews-enabled-"+jid,true).putBoolean("link-previews-encrypted-"+jid,true);
        String prefix="blind-trust-"+jid+"\n";
        for(String key:preferences.getAll().keySet())if(key.startsWith(prefix))edit.putBoolean(key,true);
        edit.putBoolean("chat-defaults-v1-"+jid,true).apply();
    }
}
