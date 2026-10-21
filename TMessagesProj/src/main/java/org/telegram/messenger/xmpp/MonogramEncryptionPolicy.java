// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.SharedPreferences;
import com.negzero.monogram.xmpp.XmppMessage;

/** Persistent 24-hour results per account and conversation, without a background timer. */
final class MonogramEncryptionPolicy {
    static final long DAY=24L*60*60*1000;
    private final SharedPreferences preferences;
    MonogramEncryptionPolicy(SharedPreferences preferences) { this.preferences=preferences; }
    private String key(String own,String peer) { return "daily-encryption-"+own+"\n"+peer; }
    boolean automatic(String own,String peer) { return preferences.getBoolean(key(own,peer)+"-auto",true); }
    void automatic(String own,String peer,boolean enabled) {
        if(!preferences.edit().putBoolean(key(own,peer)+"-auto",enabled).remove(key(own,peer)+"-checked").commit()) throw new IllegalStateException("Cannot save encryption policy");
    }
    XmppMessage.Encryption cached(String own,String peer,long now) {
        String key=key(own,peer);long checked=preferences.getLong(key+"-checked",0);
        if(checked<=0 || now<checked || now-checked>=DAY) return null;
        try { return XmppMessage.Encryption.valueOf(preferences.getString(key+"-mode","")); }
        catch(IllegalArgumentException invalid) { return null; }
    }
    void selected(String own,String peer,long id,XmppMessage.Encryption mode,long now) {
        String key=key(own,peer);
        if(!preferences.edit().putString(key+"-mode",mode.name()).putLong(key+"-checked",now)
                .putBoolean("omemo-"+id,mode!=XmppMessage.Encryption.PLAIN)
                .putInt("omemo-version-"+id,mode==XmppMessage.Encryption.OMEMO2 ? 2 : 1).commit()) throw new IllegalStateException("Cannot save selected encryption");
    }
    boolean needsWarning(String own,String peer,long now) {
        long previous=preferences.getLong(key(own,peer)+"-warned",0);
        return previous==0 || now<previous || now-previous>=DAY;
    }
    void warned(String own,String peer,long now) { preferences.edit().putLong(key(own,peer)+"-warned",now).apply(); }
}
