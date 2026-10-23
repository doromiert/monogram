// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.wallet;

import android.content.Context;
import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import org.telegram.messenger.*;
import org.telegram.messenger.xmpp.MonogramController;

/** Last locally verified public status. No bearer URI, seed, or remote sender assertion. */
public final class ClaimStatusStore {
    private static SharedPreferences prefs(){return ApplicationLoader.applicationContext.getSharedPreferences("monogram-claim-status",Context.MODE_PRIVATE);}
    public static String key(int account,String address){
        try{return account+"-"+Utilities.bytesToHex(java.security.MessageDigest.getInstance("SHA-256").digest((MonogramController.get(account).accountJid()+"\n"+address).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception error){throw new IllegalStateException("Cannot identify payment link.",error);}
    }
    public static String state(int account,String address){return prefs().getString(key(account,address),"unknown");}
    public static void pending(String key,boolean pending){prefs().edit().putBoolean(key+"-pending",pending).commit();}
    public static void put(int account,String address,String state){
        putKey(account,key(account,address),state);
    }
    public static void putKey(int account,String key,String state){
        boolean changed=!state.equals(prefs().getString(key,"unknown"));
        prefs().edit().putString(key,state).putLong(key+"-checked",System.currentTimeMillis()).commit();
        if(changed)AndroidUtilities.runOnUIThread(()->NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.walletUpdate));
    }
    public static String label(int account,String address){
        String state=state(account,address);
        if(prefs().getBoolean(key(account,address)+"-pending",false) && !ClaimDepositService.active(account,address)){
            if("depositing".equals(state))return "Deposit pending · reopen to check";
            if(!"claimed".equals(state) && !"uncertain".equals(state))return ClaimDepositPolicy.label("paused");
        }
        if("queued".equals(state) && !ClaimDepositService.active(account,address))state="paused";
        if("waiting".equals(state) && !ClaimDepositService.active(account,address))return "Waiting · last checked";
        return ClaimDepositPolicy.label(state);
    }
}
