// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import com.negzero.monogram.wallet.MonogramWallet;
import org.telegram.ui.ActionBar.BaseFragment;

/** System-owned strong biometrics / device credential UI. Never reads a PIN or biometric. */
public final class MonogramWalletAuth {
    public interface Result {void done(boolean success,Exception error);}
    private final BaseFragment host;
    private final MonogramWallet wallet;
    private final int requestCode;
    private final boolean management;
    private MonogramWallet.Authentication guard;
    private BiometricPrompt prompt;
    private Result callback,localUnlockCallback;
    private boolean localUnlockComplete;
    private Exception localUnlockFailure;
    private int localUnlockGeneration;
    private String title,subtitle;
    private boolean resumed,waitingCredential,completed;
    private boolean success;
    private Exception failure;
    private int generation;
    public MonogramWalletAuth(BaseFragment host,MonogramWallet wallet,int requestCode){this(host,wallet,requestCode,false);}
    public MonogramWalletAuth(BaseFragment host,MonogramWallet wallet,int requestCode,boolean management){this.host=host;this.wallet=wallet;this.requestCode=requestCode;this.management=management;}
    public boolean pending(){return callback!=null || localUnlockCallback!=null;}
    public void resume(){resumed=true;deliver();deliverLocalUnlock();}
    public void pause(){resumed=false;}
    public void destroy(){generation++;callback=null;localUnlockCallback=null;localUnlockFailure=null;localUnlockComplete=false;waitingCredential=false;completed=false;if(prompt!=null){prompt.cancelAuthentication();prompt=null;}if(guard!=null){guard.close();guard=null;}}
    public void authenticate(String title,String subtitle,boolean credentialOnly,Result result){
        if(pending())return;
        this.title=title;this.subtitle=subtitle;callback=result;completed=false;failure=null;success=false;int current=++generation;
        try{
            KeyguardManager keyguard=(KeyguardManager)host.getContext().getSystemService(Context.KEYGUARD_SERVICE);
            if(keyguard==null || !keyguard.isDeviceSecure())throw new IllegalStateException("Set a device PIN, pattern or password to protect your wallet.");
            guard=wallet.beginSystemAuthentication(management);
            int allowed=BiometricManager.Authenticators.BIOMETRIC_STRONG;
            if(Build.VERSION.SDK_INT>=30)allowed|=BiometricManager.Authenticators.DEVICE_CREDENTIAL;
            if(credentialOnly || BiometricManager.from(host.getContext()).canAuthenticate(allowed)!=BiometricManager.BIOMETRIC_SUCCESS){credential();return;}
            prompt=new BiometricPrompt((androidx.fragment.app.FragmentActivity)host.getParentActivity(),ContextCompat.getMainExecutor(host.getContext()),new BiometricPrompt.AuthenticationCallback(){
                @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result){if(current==generation && !waitingCredential)complete(true,null);}
                @Override public void onAuthenticationError(int code,CharSequence message){
                    if(current!=generation || !pending() || waitingCredential)return;
                    if(code==BiometricPrompt.ERROR_NEGATIVE_BUTTON || code==BiometricPrompt.ERROR_LOCKOUT || code==BiometricPrompt.ERROR_LOCKOUT_PERMANENT || code==BiometricPrompt.ERROR_HW_NOT_PRESENT || code==BiometricPrompt.ERROR_HW_UNAVAILABLE || code==BiometricPrompt.ERROR_NO_BIOMETRICS){credential();}
                    else if(code==BiometricPrompt.ERROR_CANCELED || code==BiometricPrompt.ERROR_USER_CANCELED)complete(false,null);
                    else complete(false,new IllegalStateException(message.toString()));
                }
            });
            BiometricPrompt.PromptInfo.Builder info=new BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle(subtitle).setAllowedAuthenticators(allowed).setConfirmationRequired(true);
            if(Build.VERSION.SDK_INT<30)info.setNegativeButtonText("Use PIN / password");
            prompt.authenticate(info.build());
        }catch(Exception error){complete(false,error);}
    }
    /** Reuse only Android Keystore's still-valid system authentication, not an app timer. */
    public void unlockWithRecentDeviceAuthentication(Result result){
        if(wallet.unlocked()){result.done(true,null);return;}
        try{
            openLocally(wallet.authorizeUnlock(),result);
        }catch(com.negzero.monogram.wallet.WalletVault.AuthenticationRequired expired){unlock(result);}
        catch(Exception failure){result.done(false,failure);}
    }
    public void unlock(Result result){unlock(false,result);}
    private void unlock(boolean credentialOnly,Result result){
        if(wallet.unlocked()){result.done(true,null);return;}
        authenticate("Unlock Monero wallet","Use a fingerprint, device PIN, pattern or password",credentialOnly,(approved,error)->{
            if(!approved){result.done(false,error);return;}
            try{
                openLocally(wallet.authorizeUnlock(),result);
            }catch(com.negzero.monogram.wallet.WalletVault.AuthenticationRequired required){
                if(!credentialOnly)unlock(true,result);else result.done(false,new IllegalStateException("The device credential did not authorize this wallet key. Unlock your phone and retry."));
            }catch(Exception failure){result.done(false,failure);}
        });
    }
    private void openLocally(char[] password,Result result) throws Exception{
        localUnlockCallback=result;localUnlockComplete=false;localUnlockFailure=null;
        final int operation=localUnlockGeneration=generation;
        try{wallet.unlock(password,(value,failure)->{
            if(operation!=generation || localUnlockCallback==null)return;
            localUnlockFailure=failure;localUnlockComplete=true;deliverLocalUnlock();
        });}catch(Exception failure){localUnlockCallback=null;java.util.Arrays.fill(password,'\0');throw failure;}
    }
    private void deliverLocalUnlock(){
        if(!resumed || !localUnlockComplete || localUnlockCallback==null || localUnlockGeneration!=generation)return;
        Result result=localUnlockCallback;Exception failure=localUnlockFailure;
        localUnlockCallback=null;localUnlockFailure=null;localUnlockComplete=false;
        result.done(failure==null,failure);
    }
    private void credential(){try{
        waitingCredential=true;
        KeyguardManager keyguard=(KeyguardManager)host.getContext().getSystemService(Context.KEYGUARD_SERVICE);
        Intent intent=keyguard.createConfirmDeviceCredentialIntent(title,subtitle);
        if(intent==null)throw new IllegalStateException("Set a device PIN, pattern or password first.");
        host.startActivityForResult(intent,requestCode);
    }catch(Exception error){waitingCredential=false;complete(false,error);}}
    public boolean activityResult(int code,int result){if(code!=requestCode || !waitingCredential)return false;waitingCredential=false;complete(result==Activity.RESULT_OK,null);return true;}
    private void complete(boolean success,Exception error){if(!pending() || completed)return;this.success=success;failure=error;completed=true;if(!success && guard!=null){guard.close();guard=null;}deliver();}
    private void deliver(){
        if(!resumed || !completed || callback==null)return;
        Result result=callback;callback=null;completed=false;generation++;prompt=null;
        boolean valid=guard!=null && guard.valid();if(guard!=null){guard.close();guard=null;}
        result.done(success && valid,success && !valid ? new IllegalStateException("Authentication expired. Unlock and review again.") : failure);
    }
}
