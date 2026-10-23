// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/** Bearer link for one independent wallet. Never includes the sender's main wallet keys. */
public final class MoneroClaim implements AutoCloseable {
    public static final String PREFIX="monogram-xmr:claim?";
    public final MoneroPayment payment;
    public final long height;
    private final char[] seed;
    public MoneroClaim(MoneroPayment payment,long height,char[] seed){
        if(payment==null || payment.atomicAmount==null || height<0 || height>100000000L)throw invalid();
        this.payment=MoneroPayment.parse(payment.uri(),payment.network);
        if(!payment.description.isEmpty() || !payment.recipientName.isEmpty())throw invalid();
        if(seed==null || seed.length<100 || seed.length>400)throw invalid();
        int spaces=0;
        for(int i=0;i<seed.length;i++){
            char c=seed[i];if(c==' '){if(i==0 || i==seed.length-1 || seed[i-1]==' ')throw invalid();spaces++;}
            else if(c<'a' || c>'z')throw invalid();
        }
        if(spaces!=24)throw invalid(); // SDK-generated English legacy mnemonic; SDK verifies checksum on restore.
        this.height=height;this.seed=seed.clone();
    }
    public char[] seed(){return seed.clone();}
    public String uri(){
        byte[] bytes=new byte[seed.length];for(int i=0;i<seed.length;i++)bytes[i]=(byte)seed[i];
        try{return PREFIX+"v=1&network="+payment.network.name()+"&height="+height+"&address="+payment.address+"&amount="+MoneroPayment.format(payment.atomicAmount)+"#"+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
        finally{Arrays.fill(bytes,(byte)0);}
    }
    /** Documented wallet-restore URI, supported by Cake and compatible wallets. */
    public String externalWalletUri(){
        if(payment.network!=MoneroPayment.Network.MAINNET)
            throw new IllegalStateException("Restore test-network claims manually using the stated network.");
        return "monero-wallet:?seed="+new String(seed).replace(" ","+")+"&height="+height;
    }
    public String compatibilityText(){
        return "Claim with another wallet:\n"
            +(payment.network==MoneroPayment.Network.MAINNET ? externalWalletUri()+"\n\n" : "")
            +"Restore a separate Monero wallet using this Legacy (25 words) recovery phrase:\n"
            +new String(seed)+"\nNetwork: "+payment.network.name()+"\nRestore height: "+height
            +"\nAfter syncing, send its available balance to your own wallet. Anyone with this recovery phrase can spend the funds; do not reuse this wallet.";
    }
    public String id(){return id(payment);}
    public static String id(MoneroPayment payment){
        try{byte[] hash=MessageDigest.getInstance("SHA-256").digest((payment.network.name()+":"+payment.address).getBytes(StandardCharsets.US_ASCII));StringBuilder result=new StringBuilder();for(byte b:hash)result.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return result.toString();}
        catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    public static MoneroClaim parse(String uri,MoneroPayment.Network expected){
        byte[] decoded=null;char[] seed=null;
        try{
            if(uri==null || uri.length()>2048 || !uri.startsWith(PREFIX))throw invalid();
            int hash=uri.indexOf('#');if(hash<0 || hash!=uri.lastIndexOf('#'))throw invalid();
            Map<String,String> fields=new HashMap<>();
            for(String pair:uri.substring(PREFIX.length(),hash).split("&",-1)){
                int equal=pair.indexOf('=');if(equal<1 || equal!=pair.lastIndexOf('='))throw invalid();
                if(fields.put(pair.substring(0,equal),pair.substring(equal+1))!=null)throw invalid();
            }
            if(fields.size()!=5 || !"1".equals(fields.get("v")))throw invalid();
            MoneroPayment.Network network=MoneroPayment.Network.valueOf(fields.get("network"));
            if(expected!=null && expected!=network)throw new IllegalArgumentException("This claim link uses a different Monero network.");
            String rawHeight=fields.get("height");if(rawHeight==null || !rawHeight.matches("0|[1-9][0-9]{0,8}"))throw invalid();
            MoneroPayment payment=MoneroPayment.create(fields.get("address"),fields.get("amount"),"","",network);
            String secret=uri.substring(hash+1);if(!secret.matches("[A-Za-z0-9_-]+"))throw invalid();
            decoded=Base64.getUrlDecoder().decode(secret);
            if(!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(secret))throw invalid();
            seed=new char[decoded.length];for(int i=0;i<decoded.length;i++)seed[i]=(char)(decoded[i]&255);
            return new MoneroClaim(payment,Long.parseLong(rawHeight),seed);
        }catch(Exception error){throw invalid();}
        finally{if(decoded!=null)Arrays.fill(decoded,(byte)0);if(seed!=null)Arrays.fill(seed,'\0');}
    }
    @Override public void close(){Arrays.fill(seed,'\0');}
    @Override public String toString(){return "MoneroClaim[redacted]";}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid Monogram XMR claim link.");}
}
