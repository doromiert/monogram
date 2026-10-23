// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import org.jivesoftware.smack.packet.ExtensionElement;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smackx.forward.packet.Forwarded;

/** Readable user-forward compatibility form. Attribution is a forwarder's claim, never an authenticated sender. */
public final class ForwardedContent {
    private static final String PREFIX="↪ Forwarded from ",SEPARATOR=" · ";
    public final String name,body;
    public final int date;
    private ForwardedContent(String name,int date,String body) { this.name=label(name);this.date=Math.max(0,date);this.body=body==null ? "" : body; }
    public static ForwardedContent of(String name,int date,String body) { return new ForwardedContent(name,date,body); }
    private static SimpleDateFormat format() {
        SimpleDateFormat format=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));format.setLenient(false);return format;
    }
    public String prefix() { return PREFIX+name+SEPARATOR+format().format(new Date(date*1000L))+"\n\n"; }
    public String wireBody() { return prefix()+body; }
    public static String label(String value) {
        if(value==null) throw new IllegalArgumentException("Missing forwarded sender");
        String address=address(value);if(address!=null) return address;
        StringBuilder safe=new StringBuilder();
        value.codePoints().filter(c->!Character.isISOControl(c) && !(c>=0x202a && c<=0x202e) && !(c>=0x2066 && c<=0x2069))
            .limit(128).forEach(safe::appendCodePoint);
        String name=safe.toString().trim();
        if(name.isEmpty()) throw new IllegalArgumentException("Missing forwarded sender");return name;
    }
    /** Full entity address; unlike a display alias it must never be truncated. */
    public static String address(String value) {
        if(value==null || value.length()>3071 || value.codePoints().anyMatch(c->Character.isISOControl(c) || c>=0x202a && c<=0x202e || c>=0x2066 && c<=0x2069)) return null;
        try {
            org.jxmpp.jid.Jid jid=org.jxmpp.jid.impl.JidCreate.from(value);
            return jid.asEntityBareJidIfPossible()==null ? null : jid.toString();
        } catch(Exception invalid) {return null;}
    }
    public static ForwardedContent parse(String body) {
        if(body==null || !body.startsWith(PREFIX)) return null;
        int end=body.indexOf("\n\n");if(end<0 || end>4096) return null;
        int separator=body.lastIndexOf(SEPARATOR,end);if(separator<PREFIX.length()) return null;
        String stamp=body.substring(separator+SEPARATOR.length(),end);
        ParsePosition position=new ParsePosition(0);Date date=format().parse(stamp,position);
        if(date==null || position.getIndex()!=stamp.length() || date.getTime()<0 || date.getTime()/1000>Integer.MAX_VALUE) return null;
        try {
            ForwardedContent result=of(body.substring(PREFIX.length(),separator),(int)(date.getTime()/1000),body.substring(end+2));
            return result.wireBody().equals(body) ? result : null;
        } catch(IllegalArgumentException invalid) {return null;}
    }
    public static String payload(String body) { ForwardedContent forward=parse(body);return forward==null ? body : forward.body; }
    /** Render a standalone XEP-0297 message as content, never feed its sender/IDs/actions back into stanza processing. */
    static String incoming(Message message,String body) {
        if(message==null || parse(body)!=null) return body;
        java.util.List<ExtensionElement> forwards=message.getExtensions("forwarded","urn:xmpp:forward:0");
        if(forwards.size()!=1 || !(forwards.get(0) instanceof Forwarded)) return body;
        Object stanza=((Forwarded<?>)forwards.get(0)).getForwardedStanza();
        if(!(stanza instanceof Message)) return body;
        Message original=(Message)stanza;
        if(original.getFrom()==null || original.getBody()==null || original.hasExtension("forwarded","urn:xmpp:forward:0")) return body;
        org.jivesoftware.smackx.delay.packet.DelayInformation delay=((Forwarded<?>)forwards.get(0)).getDelayInformation();
        if(delay!=null && delay.getStamp()==null) return body;
        long seconds=delay==null ? 0 : delay.getStamp().getTime()/1000;
        if(seconds<0 || seconds>Integer.MAX_VALUE) return body;
        String text=original.getBody();
        if(body!=null && !body.isEmpty()) text=body+"\n\n"+text;
        return of(original.getFrom().toString(),(int)seconds,text).wireBody();
    }
}
