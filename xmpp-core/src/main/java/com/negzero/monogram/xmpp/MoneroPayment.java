// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.bouncycastle.crypto.digests.KeccakDigest;

/** Public destinations and exact payment URIs only. This class never holds wallet keys. */
public final class MoneroPayment {
    public enum Network { MAINNET, STAGENET, TESTNET }
    public enum AddressType { STANDARD, SUBADDRESS, INTEGRATED }
    public static final BigInteger MAX_ATOMIC=new BigInteger("18446744073709551615");
    private static final String ALPHABET="123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final int[] BLOCK_SIZES={0,2,3,5,6,7,9,10,11};
    private static final BigInteger BASE=BigInteger.valueOf(58);
    public final String address,recipientName,description;
    public final Network network;
    public final AddressType type;
    public final BigInteger atomicAmount;

    private MoneroPayment(String address,Network network,AddressType type,BigInteger amount,String name,String description) {
        this.address=address;this.network=network;this.type=type;this.atomicAmount=amount;
        this.recipientName=name;this.description=description;
    }
    public static MoneroPayment address(String value,Network expected) {
        if(value==null || value.length()!=95 && value.length()!=106) throw invalid("Enter a complete Monero address.");
        byte[] decoded=decode(value);
        if(decoded.length!=69 && decoded.length!=77) throw invalid("Invalid Monero address length.");
        byte[] data=Arrays.copyOf(decoded,decoded.length-4),hash=new byte[32];
        KeccakDigest digest=new KeccakDigest(256);digest.update(data,0,data.length);digest.doFinal(hash,0);
        for(int i=0;i<4;i++) if(hash[i]!=decoded[data.length+i]) throw invalid("The Monero address checksum does not match.");
        Network network;AddressType type;
        switch(decoded[0]&255) {
            case 18:network=Network.MAINNET;type=AddressType.STANDARD;break;
            case 19:network=Network.MAINNET;type=AddressType.INTEGRATED;break;
            case 42:network=Network.MAINNET;type=AddressType.SUBADDRESS;break;
            case 24:network=Network.STAGENET;type=AddressType.STANDARD;break;
            case 25:network=Network.STAGENET;type=AddressType.INTEGRATED;break;
            case 36:network=Network.STAGENET;type=AddressType.SUBADDRESS;break;
            case 53:network=Network.TESTNET;type=AddressType.STANDARD;break;
            case 54:network=Network.TESTNET;type=AddressType.INTEGRATED;break;
            case 63:network=Network.TESTNET;type=AddressType.SUBADDRESS;break;
            default:throw invalid("Unsupported Monero address network.");
        }
        if(decoded.length!=(type==AddressType.INTEGRATED ? 77 : 69)) throw invalid("Invalid Monero address type.");
        if(!org.bouncycastle.math.ec.rfc8032.Ed25519.validatePublicKeyPartial(decoded,1)
                || !org.bouncycastle.math.ec.rfc8032.Ed25519.validatePublicKeyPartial(decoded,33))
            throw invalid("The Monero address contains an invalid public key.");
        if(expected!=null && network!=expected) throw invalid("This address uses "+networkName(network)+", not "+networkName(expected)+".");
        return new MoneroPayment(value,network,type,null,"","");
    }
    public static MoneroPayment create(String destination,String amount,String name,String description,Network expected) {
        MoneroPayment address=address(destination,expected);
        return new MoneroPayment(address.address,address.network,address.type,amount==null || amount.isEmpty() ? null : amount(amount),text(name),note(description));
    }
    public static BigInteger amount(String value) {
        if(value==null || value.length()>32 || !value.matches("[0-9]+(?:\\.[0-9]{1,12})?")) throw invalid("Use a positive XMR amount with up to 12 decimal places.");
        BigInteger amount=new BigDecimal(value).movePointRight(12).toBigIntegerExact();
        if(amount.signum()<=0 || amount.compareTo(MAX_ATOMIC)>0) throw invalid("The XMR amount is outside the supported range.");
        return amount;
    }
    public static String format(BigInteger amount) {
        if(amount==null) return "";
        if(amount.signum()<0 || amount.compareTo(MAX_ATOMIC)>0) throw invalid("Invalid XMR amount.");
        return new BigDecimal(amount,12).stripTrailingZeros().toPlainString();
    }
    public static MoneroPayment parse(String uri,Network expected) {
        if(uri==null || uri.length()>8192 || !uri.regionMatches(true,0,"monero:",0,7) || uri.indexOf('#')>=0
                || uri.codePoints().anyMatch(Character::isISOControl)) throw invalid("Invalid Monero payment link.");
        String rest=uri.substring(7);int query=rest.indexOf('?');
        String destination=query<0 ? rest : rest.substring(0,query);
        Map<String,String> fields=new HashMap<>();
        if(query>=0) {
            String raw=rest.substring(query+1);
            if(raw.isEmpty()) throw invalid("Empty Monero payment parameters.");
            for(String pair:raw.split("&",-1)) {
                int equal=pair.indexOf('=');if(equal<=0) throw invalid("Invalid Monero payment parameter.");
                String key=unescape(pair.substring(0,equal)),value=unescape(pair.substring(equal+1));
                if(!key.equals("tx_amount") && !key.equals("recipient_name") && !key.equals("tx_description")) throw invalid("Unsupported Monero payment parameter: "+text(key));
                if(fields.put(key,value)!=null) throw invalid("Duplicate Monero payment parameter.");
                if(value.isEmpty() && key.equals("tx_amount")) throw invalid("The payment amount is empty.");
            }
        }
        return create(destination,fields.get("tx_amount"),fields.get("recipient_name"),fields.get("tx_description"),expected);
    }
    public String uri() {
        StringBuilder uri=new StringBuilder("monero:").append(address);
        if(atomicAmount!=null) parameter(uri,"tx_amount",format(atomicAmount));
        if(!recipientName.isEmpty()) parameter(uri,"recipient_name",recipientName);
        if(!description.isEmpty()) parameter(uri,"tx_description",description);
        return uri.toString();
    }
    public static String networkName(Network network) {
        return network==Network.MAINNET ? "Mainnet" : network==Network.STAGENET ? "Stagenet" : "Testnet";
    }
    private static void parameter(StringBuilder uri,String key,String value) {
        uri.append(uri.indexOf("?")<0 ? '?' : '&').append(key).append('=');
        try { uri.append(URLEncoder.encode(value,"UTF-8").replace("+","%20")); }
        catch(java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }
    /** Human-readable payment note, encoded as tx_description in the URI, never chain data. */
    public static String note(String value) {
        if(value==null) return "";
        if(value.length()>1024 || value.codePoints().anyMatch(c->Character.isISOControl(c) && c!='\n' || c>=0x202a && c<=0x202e || c>=0x2066 && c<=0x2069))
            throw invalid("Payment notes must contain at most 1024 characters, without hidden control characters.");
        return value;
    }
    private static String text(String value) {
        if(value==null) return "";
        if(value.length()>1024 || value.codePoints().anyMatch(c->Character.isISOControl(c) || c>=0x202a && c<=0x202e || c>=0x2066 && c<=0x2069))
            throw invalid("Payment labels must be plain text of at most 1024 characters.");
        return value;
    }
    private static String unescape(String value) {
        // Decode percent escapes strictly: replacement characters could change what the wallet sees.
        java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
        for(int i=0;i<value.length();) {
            char c=value.charAt(i++);
            if(c=='%') {
                if(i+1>=value.length()) throw invalid("Invalid payment link escape.");
                int high=Character.digit(value.charAt(i++),16),low=Character.digit(value.charAt(i++),16);
                if(high<0 || low<0) throw invalid("Invalid payment link escape.");bytes.write(high*16+low);
            } else if(c=='+') bytes.write(' ');
            else {
                if(c>127) throw invalid("Non-ASCII payment parameters must be percent encoded.");
                bytes.write(c);
            }
        }
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString(); }
        catch(CharacterCodingException malformed) { throw invalid("Invalid UTF-8 payment label."); }
    }
    private static byte[] decode(String value) {
        int blocks=value.length()/11,tail=value.length()%11,tailSize=-1;
        for(int i=0;i<BLOCK_SIZES.length;i++) if(BLOCK_SIZES[i]==tail) tailSize=i;
        if(tailSize<0) throw invalid("Invalid Monero Base58 block length.");
        byte[] result=new byte[blocks*8+tailSize];
        for(int block=0;block<blocks+(tail>0 ? 1 : 0);block++) {
            int length=block<blocks ? 11 : tail,size=block<blocks ? 8 : tailSize;
            BigInteger number=BigInteger.ZERO;
            for(int i=0;i<length;i++) {
                int digit=ALPHABET.indexOf(value.charAt(block*11+i));if(digit<0) throw invalid("Invalid character in Monero address.");
                number=number.multiply(BASE).add(BigInteger.valueOf(digit));
            }
            if(number.bitLength()>size*8) throw invalid("Monero address block overflow.");
            byte[] decoded=number.toByteArray();
            int count=Math.min(size,decoded.length);
            System.arraycopy(decoded,decoded.length-count,result,block*8+size-count,count);
        }
        return result;
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
