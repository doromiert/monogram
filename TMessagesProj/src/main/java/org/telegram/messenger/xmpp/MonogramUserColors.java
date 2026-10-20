// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.Context;
import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import org.json.JSONObject;
import org.telegram.messenger.*;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;

/** Native self name/reply/profile swatches. Local presentation only, never a vCard publication. */
public final class MonogramUserColors {
    private MonogramUserColors() {}
    private static SharedPreferences preferences() { return ApplicationLoader.applicationContext.getSharedPreferences("monogram-user-colors",Context.MODE_PRIVATE); }
    private static String key(String database,String owner,String peer) throws Exception {
        if(owner==null || owner.isEmpty() || peer==null || peer.isEmpty()) throw new IllegalArgumentException();
        byte[] bytes=MessageDigest.getInstance("SHA-256").digest((database+"\n"+owner+"\n"+peer).getBytes(StandardCharsets.UTF_8));
        StringBuilder out=new StringBuilder();for(byte b:bytes) out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();
    }
    public static String owner(int account) { return ApplicationLoader.applicationContext.getSharedPreferences("monogram-"+account,Context.MODE_PRIVATE).getString("jid",""); }
    public static boolean save(int account,String expectedOwner,TLRPC.User user,int name,int profile) {
        if(user==null || !user.self || expectedOwner.isEmpty() || !expectedOwner.equals(owner(account)) || name<0 || name>6 || profile< -1 || profile>7) return false;
        try {
            String peer=MonogramController.get(account).jid(user.id);if(!expectedOwner.equals(peer)) return false;
            JSONObject value=new JSONObject().put("name",name).put("profile",profile);
            if(!preferences().edit().putString(key("monogram-"+account+".db",expectedOwner,peer),value.toString()).commit()) return false;
            project("monogram-"+account+".db",expectedOwner,peer,user);return true;
        } catch(Exception failed) { FileLog.e("Could not save local profile colors");return false; }
    }
    /** Store projection must never construct a Controller or read another account's user cache. */
    public static void project(String database,String owner,String peer,TLRPC.User user) {
        if(user==null || !user.self) return;
        try {
            String raw=preferences().getString(key(database,owner,peer),null);if(raw==null) return;
            JSONObject value=new JSONObject(raw);int name=value.getInt("name"),profile=value.getInt("profile");
            if(name<0 || name>6 || profile< -1 || profile>7) return;
            user.color=new TLRPC.TL_peerColor();user.color.flags=1;user.color.color=name;user.flags2|=256;
            user.profile_color=new TLRPC.TL_peerColor();user.flags2|=512;
            if(profile>=0) { user.profile_color.flags=1;user.profile_color.color=profile; }
        } catch(Exception invalid) { FileLog.e("Could not restore local profile colors"); }
    }
    private static MessagesController.PeerColors names,profiles;
    /** Native built-in avatar/name swatches; no remote catalog or boost levels. */
    public static synchronized void ensurePalette(MessagesController controller,int account) {
        if(!MonogramFreeFeatures.local(account)) return;
        if(names==null) {
            TLRPC.TL_help_peerColors nameList=new TLRPC.TL_help_peerColors(),profileList=new TLRPC.TL_help_peerColors();
            for(int i=0;i<8;i++) {
                int color=Theme.getDefaultColor(i<7 ? Theme.keys_avatar_nameInMessage[i] : Theme.key_avatar_backgroundGray);
                TLRPC.TL_help_peerColorOption option=new TLRPC.TL_help_peerColorOption();option.color_id=i;
                TLRPC.TL_help_peerColorSet set=new TLRPC.TL_help_peerColorSet();set.colors.add(color);option.colors=option.dark_colors=set;
                profileList.colors.add(option);if(i<7) nameList.colors.add(option);
            }
            names=MessagesController.PeerColors.fromTL(MessagesController.PeerColors.TYPE_NAME,nameList);
            profiles=MessagesController.PeerColors.fromTL(MessagesController.PeerColors.TYPE_PROFILE,profileList);
        }
        controller.peerColors=names;controller.profilePeerColors=profiles;
    }
}
