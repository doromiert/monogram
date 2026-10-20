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
        long nameEmoji=user!=null && user.color!=null && (user.color.flags & 2)!=0 ? user.color.background_emoji_id : 0;
        long profileEmoji=user!=null && user.profile_color!=null && (user.profile_color.flags & 2)!=0 ? user.profile_color.background_emoji_id : 0;
        return save(account,expectedOwner,user,name,profile,nameEmoji,profileEmoji);
    }
    public static boolean save(int account,String expectedOwner,TLRPC.User user,int name,int profile,long nameEmoji,long profileEmoji) {
        if(user==null || !user.self || expectedOwner==null || expectedOwner.isEmpty() || !expectedOwner.equals(owner(account)) || name<0 || name>6 || profile< -1 || profile>7) return false;
        try {
            MonogramController controller=MonogramController.get(account);long generation=controller.accountGeneration();
            String peer=controller.jid(user.id);if(!expectedOwner.equals(peer)) return false;
            if(!validEmoji(account,nameEmoji) || !validEmoji(account,profileEmoji)) return false;
            JSONObject value=new JSONObject().put("name",name).put("profile",profile).put("nameEmoji",nameEmoji).put("profileEmoji",profileEmoji);
            if(generation!=controller.accountGeneration() || !expectedOwner.equals(owner(account))) return false;
            if(!preferences().edit().putString(key("monogram-"+account+".db",expectedOwner,peer),value.toString()).commit()) return false;
            project("monogram-"+account+".db",expectedOwner,peer,user);return true;
        } catch(Exception failed) { FileLog.e("Could not save local profile colors");return false; }
    }
    /** A self background may reference existing local custom emoji only, never a remote Telegram ID. */
    public static boolean validEmoji(int account,long id) {
        if(id==0) return true;
        if(!MonogramPacks.localId(id)) return false;
        TLRPC.Document document=MonogramPacks.document(account,id);
        return document!=null && document.id==id && validEmojiDocument(document);
    }
    /** For already owner-scoped pack snapshots: validate without rescanning the entire library per row. */
    public static boolean validEmojiDocument(TLRPC.Document document) {
        if(document==null || !MonogramPacks.localId(document.id) || document.dc_id!=Integer.MIN_VALUE || document.localPath==null) return false;
        boolean emoji=false;
        for(TLRPC.DocumentAttribute attribute:document.attributes) if(attribute instanceof TLRPC.TL_documentAttributeCustomEmoji) { emoji=true;break; }
        java.io.File file=new java.io.File(document.localPath);
        return emoji && file.isFile() && file.length()>0;
    }
    /** Store projection must never construct a Controller or read another account's user cache. */
    public static void project(String database,String owner,String peer,TLRPC.User user) {
        if(user==null || !user.self) return;
        try {
            String raw=preferences().getString(key(database,owner,peer),null);if(raw==null) return;
            JSONObject value=new JSONObject(raw);int name=value.getInt("name"),profile=value.getInt("profile");
            if(name<0 || name>6 || profile< -1 || profile>7) return;
            long nameEmoji=value.optLong("nameEmoji",0),profileEmoji=value.optLong("profileEmoji",0);
            // Projection is pure and must not initialize a Controller/pack cache while the store is opening.
            if(nameEmoji!=0 && !MonogramPacks.localId(nameEmoji)) nameEmoji=0;
            if(profileEmoji!=0 && !MonogramPacks.localId(profileEmoji)) profileEmoji=0;
            user.color=new TLRPC.TL_peerColor();user.color.flags=1;user.color.color=name;user.flags2|=256;
            if(nameEmoji!=0) { user.color.flags|=2;user.color.background_emoji_id=nameEmoji; }
            user.profile_color=new TLRPC.TL_peerColor();user.flags2|=512;
            if(profile>=0) { user.profile_color.flags=1;user.profile_color.color=profile; }
            if(profileEmoji!=0) { user.profile_color.flags|=2;user.profile_color.background_emoji_id=profileEmoji; }
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
