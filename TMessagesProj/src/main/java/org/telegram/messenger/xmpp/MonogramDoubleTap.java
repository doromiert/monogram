// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.SharedPreferences;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;

/** Stable action IDs; incoming messages omit Edit, as in Exteragram. */
public final class MonogramDoubleTap {
    public static final int DISABLED=0,REACTION=1,REPLY=2,COPY=3,FORWARD=4,EDIT=5,SAVE=6,REPEAT=7,DELETE=8,TRANSLATE=9;
    private MonogramDoubleTap() {}
    public static int[] actions(boolean own) {
        return own ? new int[]{0,1,2,3,4,5,6,7,8,9} : new int[]{0,1,2,3,4,6,7,8,9};
    }
    public static int action(int account,boolean own) { return action(MessagesController.getMainSettings(account),own); }
    static int action(SharedPreferences settings,boolean own) {
        int value=settings.getInt(own ? "monogram-double-tap-own" : "monogram-double-tap-incoming",REACTION);
        return value<DISABLED || value>TRANSLATE || !own && value==EDIT ? REACTION : value;
    }
    public static void save(int account,boolean own,int action) { save(MessagesController.getMainSettings(account),own,action); }
    static void save(SharedPreferences settings,boolean own,int action) {
        if(action<DISABLED || action>TRANSLATE || !own && action==EDIT) throw new IllegalArgumentException("Invalid double-tap action");
        settings.edit().putInt(own ? "monogram-double-tap-own" : "monogram-double-tap-incoming",action).apply();
    }
    public static String label(int action) {
        int[] labels={R.string.Disable,R.string.Reactions,R.string.Reply,R.string.Copy,R.string.Forward,R.string.Edit,R.string.Save,R.string.MonogramRepeatMessage,R.string.Delete,R.string.TranslateMessage};
        return LocaleController.getString(labels[action<0 || action>=labels.length ? REACTION : action]);
    }
}
