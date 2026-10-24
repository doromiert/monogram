// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import com.negzero.monogram.xmpp.MoneroPayment;
import com.negzero.monogram.xmpp.MoneroClaim;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

/** Presentation of ordinary chat bodies. Transfer claims are not proof of payment. */
public final class MonogramXmrMessage {
    public enum Kind { REQUEST, CLAIM, TRANSFER }
    public final Kind kind;
    public final MoneroPayment payment;
    public final String uri,hashes,comment;
    private MonogramXmrMessage(Kind kind,MoneroPayment payment,String uri,String hashes){this.kind=kind;this.payment=payment;this.uri=uri;this.hashes=hashes;this.comment=payment.description;}
    private MonogramXmrMessage(Kind kind,MoneroPayment payment,String uri,String hashes,String comment){this.kind=kind;this.payment=payment;this.uri=uri;this.hashes=hashes;this.comment=comment;}
    public static final String TRANSFER_PREFIX="monogram-xmr:transfer?";
    public static String transfer(MoneroPayment payment,String hashes){
        if(hashes==null || !hashes.matches("[0-9a-fA-F]{64}(,[0-9a-fA-F]{64}){0,15}"))throw new IllegalArgumentException("Invalid transaction IDs.");
        return TRANSFER_PREFIX+"v=1&payment="+URLEncoder.encode(payment.uri(),StandardCharsets.UTF_8)+"&txid="+hashes.toLowerCase(java.util.Locale.ROOT);
    }
    private static final String CLAIM_COMPAT_SEPARATOR="\n\nClaim with another wallet:\n";
    public static String claimBody(MoneroClaim claim,String note){
        String comment=note==null ? "" : note;
        if(comment.getBytes(StandardCharsets.UTF_8).length>960 || comment.contains(CLAIM_COMPAT_SEPARATOR))
            throw new IllegalArgumentException("Invalid claim note.");
        return "XMR claim link — anyone with this link can claim the funds\n"+claim.uri()
            +(comment.isEmpty() ? "" : "\n\n"+comment)+"\n\n"+claim.compatibilityText();
    }
    public static MonogramXmrMessage parse(String body){
        if(body==null || body.length()>20*1024)return null;
        try{
            String uri=body;
            if(body.startsWith("XMR payment request\n"))uri=body.substring("XMR payment request\n".length());
            else if(body.startsWith("XMR payment broadcast\n"))uri=body.substring("XMR payment broadcast\n".length());
            else if(body.startsWith("XMR claim link — anyone with this link can claim the funds\n"))uri=body.substring(body.indexOf('\n')+1);
            if(uri.startsWith(TRANSFER_PREFIX)){
                HashMap<String,String> values=new HashMap<>();
                for(String part:uri.substring(TRANSFER_PREFIX.length()).split("&",-1)){
                    String[] kv=part.split("=",2);if(kv.length!=2 || values.put(kv[0],URLDecoder.decode(kv[1],StandardCharsets.UTF_8))!=null)return null;
                }
                if(values.size()!=3 || !"1".equals(values.get("v")) || !values.containsKey("payment") || !values.containsKey("txid"))return null;
                MoneroPayment payment=MoneroPayment.parse(values.get("payment"),null);
                if(payment.atomicAmount==null || !transfer(payment,values.get("txid")).equals(uri))return null;
                return new MonogramXmrMessage(Kind.TRANSFER,payment,uri,values.get("txid"));
            }
            if(uri.startsWith(MoneroClaim.PREFIX)){
                int compatibility=uri.indexOf(CLAIM_COMPAT_SEPARATOR);
                String fallback=compatibility<0 ? null : uri.substring(compatibility+2);
                if(compatibility>=0)uri=uri.substring(0,compatibility);
                int separator=uri.indexOf("\n\n");
                String note=separator<0 ? "" : uri.substring(separator+2);
                String bearer=separator<0 ? uri : uri.substring(0,separator);
                if(note.getBytes(StandardCharsets.UTF_8).length>960)return null;
                try(MoneroClaim claim=MoneroClaim.parse(bearer,null)){
                    if(fallback!=null && !fallback.equals(claim.compatibilityText()))return null;
                    return new MonogramXmrMessage(Kind.CLAIM,claim.payment,bearer,"",note);
                }
            }
            if(uri.regionMatches(true,0,"monero:",0,7)){
                MoneroPayment payment=MoneroPayment.parse(uri,null);
                return new MonogramXmrMessage(Kind.REQUEST,payment,uri,"");
            }
        }catch(Exception invalid){/* Leave unsupported or invalid bodies as ordinary text. */}
        return null;
    }
    /** Requests follow the chat policy; bearer claims must never downgrade to plaintext. */
    public static boolean requiresEncryption(String body,boolean requested) {
        MonogramXmrMessage payment=parse(body);
        if(payment!=null && payment.kind==Kind.REQUEST)return false;
        if(payment!=null && payment.kind==Kind.CLAIM)return true;
        return requested;
    }
    @Override public String toString(){return "MonogramXmrMessage["+kind+"]";}
}
