// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.Context;
import android.content.SharedPreferences;
import org.telegram.messenger.ApplicationLoader;

/** Device-local appearance and recorder preferences, isolated by account owner. */
public final class MonogramUiPreferences {
    public static final int ROUND_FRONT=0, ROUND_BACK=1, ROUND_ASK=2;
    private MonogramUiPreferences() {}
    private static SharedPreferences preferences(int account) {
        return ApplicationLoader.applicationContext.getSharedPreferences("monogram-ui-"+account,Context.MODE_PRIVATE);
    }
    private static String owner(int account) { return MonogramController.get(account).accountJid(); }
    public static int roundVideoCamera(int account) {
        String owner=owner(account); if(owner.isEmpty()) return ROUND_FRONT;
        int value=preferences(account).getInt(owner+"\nround-camera",ROUND_FRONT);
        return value>=ROUND_FRONT && value<=ROUND_ASK ? value : ROUND_FRONT;
    }
    public static void setRoundVideoCamera(int account,int value) {
        if(value<ROUND_FRONT || value>ROUND_ASK) throw new IllegalArgumentException("Unknown camera mode");
        String owner=owner(account);if(!owner.isEmpty()) preferences(account).edit().putInt(owner+"\nround-camera",value).apply();
    }
    public static boolean collapseHomeSearch(int account) { return getBoolean(account,"collapse-home-search",false); }
    public static void setCollapseHomeSearch(int account,boolean value) { setBoolean(account,"collapse-home-search",value); }
    public static boolean centerHomeTitle(int account) { return getBoolean(account,"center-title",false); }
    public static boolean hideComposeButton(int account) { return getBoolean(account,"hide-compose",false); }
    public static void setCenterHomeTitle(int account,boolean value) { setBoolean(account,"center-title",value); }
    public static void setHideComposeButton(int account,boolean value) { setBoolean(account,"hide-compose",value); }
    private static boolean getBoolean(int account,String name,boolean fallback) {
        String owner=owner(account); return owner.isEmpty() ? fallback : preferences(account).getBoolean(owner+"\n"+name,fallback);
    }
    private static void setBoolean(int account,String name,boolean value) {
        String owner=owner(account);if(!owner.isEmpty()) preferences(account).edit().putBoolean(owner+"\n"+name,value).apply();
    }

    public static int avatarCorners(int account) {
        String owner=owner(account);return owner.isEmpty()?28:Math.max(0,Math.min(28,preferences(account).getInt(owner+"\navatar-corners",28)));
    }
    public static void setAvatarCorners(int account,int value) {
        if(value<0 || value>28) throw new IllegalArgumentException("Invalid avatar corners");
        String owner=owner(account);if(!owner.isEmpty()) preferences(account).edit().putInt(owner+"\navatar-corners",value).apply();
    }

}
