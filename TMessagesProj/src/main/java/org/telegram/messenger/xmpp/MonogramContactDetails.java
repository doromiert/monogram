// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;
import android.content.*;
import java.io.File;
import org.telegram.messenger.ApplicationLoader;
/** Device-local annotations and avatar overrides; never published to the contact or roster. */
public final class MonogramContactDetails {
    private MonogramContactDetails() {}
    private static SharedPreferences prefs(int account) { return ApplicationLoader.applicationContext.getSharedPreferences("monogram-contact-details-"+account,Context.MODE_PRIVATE); }
    private static String key(String owner,String peer) { if(owner.isEmpty() || peer.isEmpty()) throw new IllegalArgumentException("Choose a contact");return owner+"\n"+peer; }
    static String note(int account,String owner,String peer) { return prefs(account).getString(key(owner,peer)+":note",""); }
    static File avatar(int account,String owner,String peer) { String path=prefs(account).getString(key(owner,peer)+":avatar","");return path.isEmpty() || !new File(path).isFile() ? null : new File(path); }
    static void note(int account,String owner,String peer,String note) {
        if(note.length()>8192) throw new IllegalArgumentException("Notes can contain up to 8192 characters");
        if(!prefs(account).edit().putString(key(owner,peer)+":note",note).commit()) throw new IllegalStateException("Could not save contact notes");
    }
    static void avatar(int account,String owner,String peer,File avatar) {
        SharedPreferences.Editor edit=prefs(account).edit();String key=key(owner,peer)+":avatar";
        if(avatar==null) edit.remove(key);else edit.putString(key,avatar.getAbsolutePath());
        if(!edit.commit()) throw new IllegalStateException("Could not save contact photo");
    }
}
