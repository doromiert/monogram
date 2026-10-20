// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import java.net.URI;
import java.net.URLDecoder;
import java.util.*;

/** Classifies only Telegram monetary routes; never fetches or rewrites a destination. */
public final class MonogramPurchaseLinks {
    private MonogramPurchaseLinks() {}
    private static final Set<String> COMMANDS=new HashSet<>(Arrays.asList(
            "premium","premium_offer","premium_multigift","premium_sms","giftcode","invoice",
            "nft","stargift","stargift_auction","stargift_preview","stars","stars_topup",
            "send_gift","send-gift","boost","wallet","sendgrams","tonconnect","ton"));
    private static final Set<String> SETTINGS=new HashSet<>(Arrays.asList(
            "premium","business","stars","send-gift","send_gift","wallet","payments","premium_sms","ton"));
    public static boolean blocked(String value) {
        if(value==null) return false;
        if(value.length()>65536) value=value.substring(0,65536);
        try {
            // Android Uri accepts whitespace/stray percent signs in opaque query values.
            // Escape those for java.net.URI so a malformed query cannot bypass a known route.
            StringBuilder sanitized=new StringBuilder();for(int i=0;i<value.length();i++) {
                char c=value.charAt(i);boolean percent=c=='%' && (i+2>=value.length() || Character.digit(value.charAt(i+1),16)<0 || Character.digit(value.charAt(i+2),16)<0);
                if(c<=32 || c==127 || "<>\"{}|\\^`".indexOf(c)>=0 || percent) sanitized.append(String.format(Locale.ROOT,"%%%02X",(int)c));else sanitized.append(c);
            }
            URI uri=new URI(sanitized.toString());String scheme=lower(uri.getScheme());
            if(scheme.equals("ton") || scheme.equals("tc") || scheme.equals("tonconnect")) return true;
            String route,query=uri.getRawQuery();
            if(scheme.equals("tg")) {
                String part=uri.getRawSchemeSpecificPart();while(part.startsWith("/")) part=part.substring(1);
                int question=part.indexOf('?');if(question>=0) { query=part.substring(question+1);part=part.substring(0,question); }
                // Native wallet links also accept tg:sendgrams&amount=... .
                int amp=part.indexOf('&');if(amp>=0) part=part.substring(0,amp);
                route=decode(part);
            } else if(scheme.equals("http") || scheme.equals("https")) {
                String host=lower(uri.getHost());String prefix="";
                if(host.endsWith(".t.me") && host.length()>5) prefix=host.substring(0,host.length()-5)+"/";
                else if(!host.equals("t.me") && !host.equals("telegram.me") && !host.equals("telegram.dog")) return false;
                route=prefix+(uri.getPath()==null ? "" : uri.getPath());
            } else return false;
            while(route.startsWith("/")) route=route.substring(1);
            String[] parts=route.split("/",-1);String first=lower(parts.length>0 ? parts[0] : "");
            if(first.startsWith("$") || COMMANDS.contains(first)) return true;
            if(first.equals("settings")) {
                if(parts.length>1 && SETTINGS.contains(lower(parts[1]))) return true;
                if(parts.length>2 && lower(parts[1]).equals("privacy") && Arrays.asList("payments","gifts","paid-messages").contains(lower(parts[2]))) return true;
                if(SETTINGS.contains(lower(parameter(query,"section")))) return true;
            }
            if(first.equals("resolve") && Arrays.asList("premiumbot","sendgrams").contains(lower(parameter(query,"domain")))) return true;
            return "boost".equalsIgnoreCase(query);
        } catch(Exception invalid) { return false; }
    }
    private static String parameter(String query,String name) throws Exception {
        if(query==null) return "";for(String item:query.split("&")) { String[] pair=item.split("=",2);if(decode(pair[0]).equalsIgnoreCase(name)) return pair.length>1 ? decode(pair[1]) : ""; }return "";
    }
    private static String decode(String value) throws Exception { return URLDecoder.decode(value,"UTF-8"); }
    private static String lower(String value) { return value==null ? "" : value.toLowerCase(Locale.ROOT); }
}
