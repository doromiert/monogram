// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.util.*;
import org.jivesoftware.smack.packet.*;

/** XEP-0394 ranges use Unicode code points, never Java/Android UTF-16 offsets. */
public final class MessageMarkup {
    public static final String NAMESPACE="urn:xmpp:markup:0";
    public static final int MAX_RANGES=256;
    public enum Style { EMPHASIS, STRONG, CODE, DELETED, PRE, QUOTE }
    public static final class Range {
        public final int start,end;
        public final Set<Style> styles;
        public final String language;
        public Range(int start,int end,Collection<Style> styles,String language) {
            if(start<0 || end<=start || styles==null || styles.isEmpty()) throw new IllegalArgumentException("Invalid markup range");
            EnumSet<Style> copy=EnumSet.copyOf(styles);
            if((copy.contains(Style.PRE) || copy.contains(Style.QUOTE)) && copy.size()!=1)
                throw new IllegalArgumentException("Block markup cannot be an inline style");
            if(language!=null && (language.length()>128 || language.codePoints().anyMatch(Character::isISOControl)))
                throw new IllegalArgumentException("Invalid code language");
            this.start=start;this.end=end;this.styles=Collections.unmodifiableSet(copy);this.language=language;
        }
        public int utf16Start(String body) { return body.offsetByCodePoints(0,start); }
        public int utf16End(String body) { return body.offsetByCodePoints(0,end); }
        boolean block() { return styles.contains(Style.PRE) || styles.contains(Style.QUOTE); }
    }
    public final String body;
    public final List<Range> ranges;
    public MessageMarkup(String body,Collection<Range> ranges) {
        if(body==null || ranges==null || ranges.size()>MAX_RANGES) throw new IllegalArgumentException("Invalid markup");
        int count=body.codePointCount(0,body.length());
        List<Range> copy=new ArrayList<>(ranges);
        for(int i=0;i<copy.size();i++) {
            Range a=Objects.requireNonNull(copy.get(i));
            if(a.end>count) throw new IllegalArgumentException("Markup outside body");
            for(int j=0;j<i;j++) {
                Range b=copy.get(j);
                if(a.start>=b.end || b.start>=a.end) continue;
                if(!a.block() && !b.block()
                        || a.block() && !b.block() && (b.start<a.start || b.end>a.end)
                        || b.block() && !a.block() && (a.start<b.start || a.end>b.end)
                        || a.block() && b.block() && (a.start<b.start && a.end<b.end || b.start<a.start && b.end<a.end))
                    throw new IllegalArgumentException("Overlapping markup boundaries");
            }
        }
        this.body=body;this.ranges=Collections.unmodifiableList(copy);
    }
    public static void initialize() {
        org.jivesoftware.smack.provider.ProviderManager.addExtensionProvider("markup",NAMESPACE,
                org.jivesoftware.smack.parsing.StandardExtensionElementProvider.INSTANCE);
    }
    public void addTo(Message message) {
        if(!body.equals(message.getBody())) throw new IllegalArgumentException("Markup belongs to another body");
        StandardExtensionElement.Builder markup=StandardExtensionElement.builder("markup",NAMESPACE);
        for(Range range:ranges) {
            String tag=range.styles.contains(Style.PRE) ? "bcode" : range.styles.contains(Style.QUOTE) ? "bquote" : "span";
            StandardExtensionElement.Builder element=StandardExtensionElement.builder(tag,NAMESPACE)
                    .addAttribute("start",Integer.toString(range.start)).addAttribute("end",Integer.toString(range.end));
            if(tag.equals("bcode") && range.language!=null && !range.language.isEmpty()) element.addAttribute("language",range.language);
            if(tag.equals("span")) for(Style style:range.styles)
                element.addElement(StandardExtensionElement.builder(style.name().toLowerCase(Locale.ROOT),NAMESPACE).build());
            markup.addElement(element.build());
        }
        message.addExtension(markup.build());
    }
    /** Call only with cleartext or authenticated decrypted content, never an encrypted outer stanza. */
    public static MessageMarkup from(Message message,String body) {
        if(message==null || body==null || !body.equals(message.getBody())) return null;
        List<ExtensionElement> found=message.getExtensions("markup",NAMESPACE);
        if(found.size()!=1 || !(found.get(0) instanceof StandardExtensionElement)) return null;
        StandardExtensionElement markup=(StandardExtensionElement)found.get(0);
        String language=markup.getAttributeValue("xml:lang");
        if(language!=null && !language.equals(message.getLanguage())) return null;
        if(markup.getElements().size()>MAX_RANGES) return null;
        List<Range> ranges=new ArrayList<>();
        try {
            for(StandardExtensionElement element:markup.getElements()) {
                if(!NAMESPACE.equals(element.getNamespace())) continue;
                String tag=element.getElementName();
                EnumSet<Style> styles=EnumSet.noneOf(Style.class);
                if(tag.equals("span")) {
                    for(StandardExtensionElement child:element.getElements()) {
                        if(!NAMESPACE.equals(child.getNamespace())) continue;
                        switch(child.getElementName()) {
                            case "emphasis":styles.add(Style.EMPHASIS);break;
                            case "strong":styles.add(Style.STRONG);break;
                            case "code":styles.add(Style.CODE);break;
                            case "deleted":styles.add(Style.DELETED);break;
                        }
                    }
                } else if(tag.equals("bcode")) styles.add(Style.PRE);
                else if(tag.equals("bquote")) styles.add(Style.QUOTE);
                if(styles.isEmpty()) continue; // Unknown extensions do not impose range semantics.
                ranges.add(new Range(index(element,"start"),index(element,"end"),styles,
                        tag.equals("bcode") ? element.getAttributeValue("language") : null));
            }
            return new MessageMarkup(body,ranges);
        } catch(IllegalArgumentException invalid) { return null; }
    }
    private static int index(StandardExtensionElement element,String name) {
        String value=element.getAttributeValue(name);
        if(value==null || value.isEmpty() || value.length()>10) throw new IllegalArgumentException("Missing markup index");
        for(int i=0;i<value.length();i++) if(value.charAt(i)<'0' || value.charAt(i)>'9') throw new IllegalArgumentException("Invalid markup index");
        return Integer.parseInt(value);
    }
}
