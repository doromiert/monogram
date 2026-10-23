// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.wallet;
import android.content.*;import com.negzero.monogram.xmpp.MoneroPayment;import org.telegram.messenger.ApplicationLoader;import org.json.*;import java.util.*;
/** Per-account/network names and selection only; keys and native caches stay in separate vaults. */
public final class WalletRegistry {
    public static final String DEFAULT="default";
    private WalletRegistry(){}
    public static void validate(String id){if(!DEFAULT.equals(id) && (id==null || !id.matches("[a-f0-9-]{36}")))throw new IllegalArgumentException("Invalid wallet identifier.");}
    private static SharedPreferences prefs(int account,String owner){
        try{byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest((account+"\n"+owner).getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder scope=new StringBuilder();for(byte b:hash)scope.append(String.format(Locale.ROOT,"%02x",b&255));return ApplicationLoader.applicationContext.getSharedPreferences("monogram-wallets-"+scope,Context.MODE_PRIVATE);}catch(Exception e){throw new IllegalStateException("Cannot open wallet list.",e);}
    }
    public static String active(int account,String owner,MoneroPayment.Network network){String id=prefs(account,owner).getString(network.name()+"-active",DEFAULT);validate(id);return id;}
    public static void select(int account,String owner,MoneroPayment.Network network,String id){validate(id);if(!prefs(account,owner).edit().putString(network.name()+"-active",id).commit())throw new IllegalStateException("Could not save wallet selection.");}
    private static JSONObject names(int account,String owner,MoneroPayment.Network network){try{return new JSONObject(prefs(account,owner).getString(network.name()+"-names","{}"));}catch(Exception e){throw new IllegalStateException("Could not read wallet list.",e);}}
    public static String name(int account,String owner,MoneroPayment.Network network,String id){return names(account,owner,network).optString(id,DEFAULT.equals(id) ? "Wallet 1" : "Wallet");}
    public static List<String> ids(int account,String owner,MoneroPayment.Network network){List<String> ids=new ArrayList<>();ids.add(DEFAULT);Iterator<String> keys=names(account,owner,network).keys();while(keys.hasNext()){String id=keys.next();validate(id);if(!DEFAULT.equals(id))ids.add(id);}return ids;}
    public static synchronized void rename(int account,String owner,MoneroPayment.Network network,String id,String name)throws Exception{validate(id);String value=name.trim();if(value.isEmpty() || value.length()>80 || value.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Use a wallet name of 1–80 characters.");JSONObject next=names(account,owner,network).put(id,value);if(!prefs(account,owner).edit().putString(network.name()+"-names",next.toString()).commit())throw new IllegalStateException("Could not save wallet name.");}
    public static synchronized String add(int account,String owner,MoneroPayment.Network network,String name)throws Exception{String id=UUID.randomUUID().toString();rename(account,owner,network,id,name);return id;}
    public static synchronized void remove(int account,String owner,MoneroPayment.Network network,String id)throws Exception{JSONObject next=names(account,owner,network);next.remove(id);SharedPreferences.Editor edit=prefs(account,owner).edit().putString(network.name()+"-names",next.toString());if(id.equals(active(account,owner,network))){String replacement=DEFAULT;Iterator<String> keys=next.keys();if(keys.hasNext())replacement=keys.next();edit.putString(network.name()+"-active",replacement);}if(!edit.commit())throw new IllegalStateException("Could not update wallet list.");}
}
