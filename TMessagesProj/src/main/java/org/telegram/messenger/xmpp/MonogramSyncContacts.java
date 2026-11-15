// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;
import android.database.Cursor;
import android.content.SharedPreferences;
import android.util.Base64;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;
/** Portable local annotations only; never roster/profile writes or live trust state. */
final class MonogramSyncContacts {
    static String id(String peer)throws Exception {
        StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(peer.getBytes(StandardCharsets.UTF_8)))out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();
    }
    static JSONArray snapshot(int account,String owner,MonogramStore store)throws Exception {
        synchronized(MonogramContactDetails.class) {
            Set<String> peers=new TreeSet<>();String prefix=owner+"\n";SharedPreferences prefs=MonogramContactDetails.prefs(account);
            for(String key:prefs.getAll().keySet())if(key.startsWith(prefix))for(String suffix:new String[]{":note",":avatar",":revision"})if(key.endsWith(suffix))peers.add(key.substring(prefix.length(),key.length()-suffix.length()));
            try(Cursor rows=store.getReadableDatabase().rawQuery("SELECT peer FROM local_contact_names",null)){while(rows.moveToNext())peers.add(store.jid(rows.getLong(0)));}
            JSONArray result=new JSONArray();
            for(String peer:peers) {
                long local=store.knownPeer(peer);if(local<=0||owner.equals(peer))continue;
                JSONObject value=new JSONObject().put("peer",peer).put("nickname",store.localName(local)).put("note",MonogramContactDetails.note(account,owner,peer)).put("avatar",JSONObject.NULL);
                File avatar=MonogramContactDetails.avatar(account,owner,peer);
                if(avatar!=null){byte[] bytes;try(InputStream input=new FileInputStream(avatar)){bytes=MonogramPackArchive.read(input,1024*1024,()->{});}value.put("avatar",Base64.encodeToString(bytes,Base64.NO_WRAP));}
                result.put(new JSONObject().put("id",id(peer)).put("revision",MonogramContactDetails.revision(account,owner,peer)).put("value",value));
            }return result;
        }
    }
    static boolean apply(int account,String owner,MonogramStore store,JSONObject value,long revision)throws Exception {
        synchronized(MonogramContactDetails.class) {
            String peer=value.getString("peer");if(owner.equals(peer)||revision<=0)throw new IllegalArgumentException("Invalid synchronized contact");
            if(MonogramContactDetails.revision(account,owner,peer)>revision)return false;
            long local=store.peer(peer);if(local<=0)throw new IllegalArgumentException("Choose another contact");
            String name=value.getString("nickname"),note=value.getString("note");if(note.length()>8192)throw new IllegalArgumentException("Contact note is too long");
            File avatar=null;
            if(!value.isNull("avatar")) {
                byte[] bytes=Base64.decode(value.getString("avatar"),Base64.NO_WRAP);
                if(bytes.length>1024*1024)throw new IllegalArgumentException("Contact avatar is too large");
                // Decode/re-encode on this worker; paired data never supplies a local file path.
                avatar=MonogramProfiles.cache(org.telegram.messenger.ApplicationLoader.applicationContext,"local-contact-"+account+"-"+Integer.toHexString(owner.hashCode()),MonogramProfiles.png(bytes));
            }
            store.localName(local,name);
            SharedPreferences.Editor edit=MonogramContactDetails.prefs(account).edit();String key=MonogramContactDetails.key(owner,peer);
            edit.putString(key+":note",note).putLong(key+":revision",revision);
            if(avatar==null)edit.remove(key+":avatar");else edit.putString(key+":avatar",avatar.getAbsolutePath());
            if(!edit.commit())throw new IOException("Could not save synced contact details");return true;
        }
    }
}
