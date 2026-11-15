// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;
import android.content.*;
import java.io.File;
import org.telegram.messenger.ApplicationLoader;
/** Device-local annotations and avatar overrides; never published to the contact or roster. */
public final class MonogramContactDetails {
    private MonogramContactDetails() {}
    static SharedPreferences prefs(int account) { return ApplicationLoader.applicationContext.getSharedPreferences("monogram-contact-details-"+account,Context.MODE_PRIVATE); }
    static String key(String owner,String peer) { if(owner.isEmpty() || peer.isEmpty()) throw new IllegalArgumentException("Choose a contact");return owner+"\n"+peer; }
    static synchronized long revision(int account,String owner,String peer) { return prefs(account).getLong(key(owner,peer)+":revision",0); }
    private static long next(int account,String owner,String peer) { return Math.max(System.currentTimeMillis(),revision(account,owner,peer)+1); }
    static synchronized void changed(int account,String owner,String peer) {
        if(!prefs(account).edit().putLong(key(owner,peer)+":revision",next(account,owner,peer)).commit()) throw new IllegalStateException("Could not save contact revision");
    }
    static String note(int account,String owner,String peer) { return prefs(account).getString(key(owner,peer)+":note",""); }
    static File avatar(int account,String owner,String peer) { String path=prefs(account).getString(key(owner,peer)+":avatar","");return path.isEmpty() || !new File(path).isFile() ? null : new File(path); }
    static synchronized void note(int account,String owner,String peer,String note) {
        if(note.length()>8192) throw new IllegalArgumentException("Notes can contain up to 8192 characters");
        if(!prefs(account).edit().putString(key(owner,peer)+":note",note).putLong(key(owner,peer)+":revision",next(account,owner,peer)).commit()) throw new IllegalStateException("Could not save contact notes");
    }
    static synchronized void avatar(int account,String owner,String peer,File avatar) {
        SharedPreferences.Editor edit=prefs(account).edit();String key=key(owner,peer)+":avatar";
        if(avatar==null) edit.remove(key);else edit.putString(key,avatar.getAbsolutePath());
        edit.putLong(key(owner,peer)+":revision",next(account,owner,peer));
        if(!edit.commit()) throw new IllegalStateException("Could not save contact photo");
    }
}
