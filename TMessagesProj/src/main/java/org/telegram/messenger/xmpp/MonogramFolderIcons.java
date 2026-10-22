// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.Context;
import android.content.SharedPreferences;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;

/** Local folder artwork uses stable names; Android resource ids can change between APKs. */
public final class MonogramFolderIcons {
    public static final int NAMES_ONLY=0, ICONS_ONLY=1, ICONS_AND_NAMES=2;
    private MonogramFolderIcons() {}
    public static int[] choices() { return DRAWABLES.clone(); }
    public static int displayMode(int account) {
        String owner=MonogramController.get(account).accountJid();
        if(owner.isEmpty()) return ICONS_AND_NAMES;
        int mode=preferences(account).getInt(owner+"\nmode",ICONS_AND_NAMES);
        return mode>=NAMES_ONLY && mode<=ICONS_AND_NAMES ? mode : ICONS_AND_NAMES;
    }
    public static void setDisplayMode(int account,int mode) {
        if(mode<NAMES_ONLY || mode>ICONS_AND_NAMES) throw new IllegalArgumentException("Unknown folder display mode");
        String owner=MonogramController.get(account).accountJid();
        if(!owner.isEmpty()) preferences(account).edit().putInt(owner+"\nmode",mode).apply();
    }
    private static final String[] NAMES={"cat","book","money","game","light","like","note","palette","travel","sport","favorite","study","airplane","private","group","all","unread","bots","crown","flower","home","love","mask","party","trade","work","unmuted","channels","custom","setup"};
    private static final int[] DRAWABLES={R.drawable.filter_cat,R.drawable.filter_book,R.drawable.filter_money,R.drawable.filter_game,R.drawable.filter_light,R.drawable.filter_like,R.drawable.filter_note,R.drawable.filter_palette,R.drawable.filter_travel,R.drawable.filter_sport,R.drawable.filter_favorite,R.drawable.filter_study,R.drawable.filter_airplane,R.drawable.filter_private,R.drawable.filter_group,R.drawable.filter_all,R.drawable.filter_unread,R.drawable.filter_bots,R.drawable.filter_crown,R.drawable.filter_flower,R.drawable.filter_home,R.drawable.filter_love,R.drawable.filter_mask,R.drawable.filter_party,R.drawable.filter_trade,R.drawable.filter_work,R.drawable.filter_unmuted,R.drawable.filter_channels,R.drawable.filter_custom,R.drawable.filter_setup};
    private static SharedPreferences preferences(int account) {
        return ApplicationLoader.applicationContext.getSharedPreferences("monogram-folder-icons-"+account,Context.MODE_PRIVATE);
    }
    private static String key(int account,int folderId) {
        String owner=MonogramController.get(account).accountJid();
        return owner.isEmpty() || folderId<=0 ? null : owner+"\n"+folderId;
    }
    /** folderId is DialogFilter.id, never its transient localId. */
    public static int get(int account,int folderId) {
        String key=key(account,folderId); if(key==null) return 0;
        String name=preferences(account).getString(key,"");
        for(int i=0;i<NAMES.length;i++) if(NAMES[i].equals(name)) return DRAWABLES[i];
        return 0;
    }
    public static void set(int account,int folderId,int drawable) {
        String key=key(account,folderId); if(key==null) return;
        if(drawable==0) { remove(account,folderId); return; }
        for(int i=0;i<DRAWABLES.length;i++) if(DRAWABLES[i]==drawable) {
            preferences(account).edit().putString(key,NAMES[i]).apply(); return;
        }
        throw new IllegalArgumentException("Unknown folder icon");
    }
    public static void remove(int account,int folderId) {
        String key=key(account,folderId); if(key!=null) preferences(account).edit().remove(key).apply();
    }
}
