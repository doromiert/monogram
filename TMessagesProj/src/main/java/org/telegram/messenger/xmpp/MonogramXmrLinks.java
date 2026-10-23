// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.text.Spannable;
import android.text.Spanned;
import android.text.style.URLSpan;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Opt-in local payment presentation; the original interoperable URI remains intact. */
public final class MonogramXmrLinks {
    private static final Pattern LINKS=Pattern.compile("\\b(?:monero:|monogram-xmr:(?:claim|transfer)\\?)[^\\s<>\"\\p{Cntrl}]+",Pattern.CASE_INSENSITIVE);
    private static final class PaymentSpan extends URLSpan {PaymentSpan(String uri){super(uri);}}
    private static boolean hasLink(CharSequence text){String s=text.toString().toLowerCase(java.util.Locale.ROOT);return s.contains("monero:") || s.contains("monogram-xmr:");}
    public static CharSequence update(CharSequence value,boolean enabled) {
        if(value==null || value.length()>20*1024 || !hasLink(value)) return value;
        Spannable text=value instanceof Spannable ? (Spannable)value : new android.text.SpannableString(value);
        for(PaymentSpan old:text.getSpans(0,text.length(),PaymentSpan.class)) text.removeSpan(old);
        if(enabled) add(text);
        return text;
    }
    public static void add(Spannable text) {
        if(text.length()>20*1024 || !hasLink(text)) return;
        Matcher matcher=LINKS.matcher(text);
        while(matcher.find()) {
            boolean overlap=false;
            for(URLSpan old:text.getSpans(matcher.start(),matcher.end(),URLSpan.class)) {
                if(old.getURL().startsWith("tel:") && text.getSpanStart(old)>=matcher.start() && text.getSpanEnd(old)<=matcher.end()) text.removeSpan(old);
                else overlap=true;
            }
            if(overlap) continue;
            text.setSpan(new PaymentSpan(matcher.group()),matcher.start(),matcher.end(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }
}
