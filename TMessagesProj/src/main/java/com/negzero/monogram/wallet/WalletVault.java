// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.wallet;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.UserNotAuthenticatedException;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Wraps a random native-wallet password using a device-authenticated Android Keystore key. */
public final class WalletVault {
    public static final class AuthenticationRequired extends Exception {
        public AuthenticationRequired() {super("Unlock with your device credential to access this wallet.");}
    }
    public static final class Authorization implements AutoCloseable {
        public final JSONObject sealed;
        public final char[] password;
        Authorization(JSONObject sealed,char[] password) {this.sealed=sealed;this.password=password;}
        @Override public void close() {Arrays.fill(password,'\0');}
    }
    private final Context context;
    private final String alias;
    private final byte[] associatedData;
    public WalletVault(Context context,String accountScope,String network) {
        this.context=context.getApplicationContext();alias="monogram-xmr-wallet-"+accountScope+"-"+network;
        associatedData=("Monogram wallet password v1\n"+accountScope+"\n"+network).getBytes(StandardCharsets.UTF_8);
    }
    public void remove() throws Exception {KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);store.deleteEntry(alias);}
    private SecretKey key() throws Exception {
        KeyguardManager guard=(KeyguardManager)context.getSystemService(Context.KEYGUARD_SERVICE);
        if(guard==null || !guard.isDeviceSecure()) throw new IllegalStateException("Set a device screen lock before restoring a wallet.");
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(!store.containsAlias(alias)) {
            KeyGenParameterSpec.Builder spec=new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setUserAuthenticationRequired(true);
            if(Build.VERSION.SDK_INT>=30) spec.setUserAuthenticationParameters(300,KeyProperties.AUTH_DEVICE_CREDENTIAL|KeyProperties.AUTH_BIOMETRIC_STRONG);
            else spec.setUserAuthenticationValidityDurationSeconds(300);
            if(Build.VERSION.SDK_INT>=24) spec.setInvalidatedByBiometricEnrollment(false);
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");generator.init(spec.build());generator.generateKey();
        }
        return (SecretKey)store.getKey(alias,null);
    }
    public Authorization authorizeRestore() throws Exception {
        byte[] random=new byte[32];new SecureRandom().nextBytes(random);
        byte[] password=Base64.encode(random,Base64.NO_WRAP);Arrays.fill(random,(byte)0);
        try {
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());cipher.updateAAD(associatedData);
            byte[] encrypted=cipher.doFinal(password);
            JSONObject sealed=new JSONObject().put("version",1).put("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)).put("ciphertext",Base64.encodeToString(encrypted,Base64.NO_WRAP));
            char[] value=new char[password.length];for(int i=0;i<password.length;i++)value[i]=(char)(password[i]&255);
            return new Authorization(sealed,value);
        } catch(UserNotAuthenticatedException auth) {throw new AuthenticationRequired();}
        finally {Arrays.fill(password,(byte)0);}
    }
    public char[] unlock(JSONObject sealed) throws Exception {
        if(sealed.getInt("version")!=1) throw new IllegalStateException("Unsupported wallet password format.");
        byte[] decoded=null;
        try {
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(sealed.getString("iv"),Base64.NO_WRAP)));
            cipher.updateAAD(associatedData);decoded=cipher.doFinal(Base64.decode(sealed.getString("ciphertext"),Base64.NO_WRAP));
            char[] password=new char[decoded.length];for(int i=0;i<decoded.length;i++)password[i]=(char)(decoded[i]&255);return password;
        } catch(UserNotAuthenticatedException auth) {throw new AuthenticationRequired();}
        finally {if(decoded!=null)Arrays.fill(decoded,(byte)0);}
    }
}
