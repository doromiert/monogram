// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.jivesoftware.smack.packet.ExtensionElement;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.StandardExtensionElement;
import org.jivesoftware.smack.parsing.StandardExtensionElementProvider;
import org.jivesoftware.smack.util.PacketParserUtils;

/** Bounded XHTML-IM image profile with self-contained, hash-verified XEP-0231 data. */
public final class InlineEmoji {
    public static final String HTML="http://jabber.org/protocol/xhtml-im", XHTML="http://www.w3.org/1999/xhtml", BOB="urn:xmpp:bob";
    public static final int MAX_BYTES=128*1024, MAX_RANGES=64, MAX_ASSETS=16;
    public static final class Asset {
        public final String cid,mime;
        private final byte[] bytes;
        public Asset(String mime,byte[] data) { this(cid(data),mime,data); }
        public Asset(String cid,String mime,byte[] data) {
            if(cid==null || !cid.matches("sha-256\\+[0-9a-f]{64}@bob\\.xmpp\\.org") || !supported(mime)
                    || data!=null && (data.length==0 || data.length>MAX_BYTES || !cid.equals(cid(data)))) throw new IllegalArgumentException("Invalid inline emoji asset");
            this.cid=cid;this.mime=mime;this.bytes=data==null ? null : data.clone();
        }
        public byte[] data() { return bytes==null ? null : bytes.clone(); }
        public int size() { return bytes==null ? 0 : bytes.length; }
        public String hash() { return cid.substring(8,72); }
        private static String cid(byte[] bytes) {
            if(bytes==null || bytes.length==0 || bytes.length>MAX_BYTES) throw new IllegalArgumentException("Invalid inline emoji bytes");
            try {
                StringBuilder hex=new StringBuilder();
                for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes)) hex.append(String.format(Locale.ROOT,"%02x",b&255));
                return "sha-256+"+hex+"@bob.xmpp.org";
            } catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }
    }
    public static final class Range {
        public final int start,end;
        public final Asset asset;
        public Range(int start,int end,Asset asset) {
            if(start<0 || end<=start || end-start>64 || asset==null) throw new IllegalArgumentException("Invalid inline emoji range");
            this.start=start;this.end=end;this.asset=asset;
        }
    }
    public final String body;
    public final List<Range> ranges;
    public InlineEmoji(String body,Collection<Range> ranges) {
        if(body==null || body.length()>131072 || body.codePointCount(0,body.length())>65536 || ranges==null || ranges.size()>MAX_RANGES) throw new IllegalArgumentException("Invalid inline emoji");
        List<Range> copy=new ArrayList<>(ranges);copy.sort(Comparator.comparingInt(range->range.start));
        int length=body.codePointCount(0,body.length()),end=0,total=0;Map<String,Asset> assets=new HashMap<>();
        for(Range range:copy) {
            if(range.start<end || range.end>length) throw new IllegalArgumentException("Overlapping inline emoji");end=range.end;
            String alt=body.substring(body.offsetByCodePoints(0,range.start),body.offsetByCodePoints(0,range.end));
            if(alt.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid emoji fallback");
            Asset previous=assets.putIfAbsent(range.asset.cid,range.asset);
            if(previous==null) total+=range.asset.size();else if(!previous.mime.equals(range.asset.mime)) throw new IllegalArgumentException("Conflicting inline emoji media type");
        }
        if(assets.size()>MAX_ASSETS || total>MAX_BYTES) throw new IllegalArgumentException("Too much inline emoji data");
        this.body=body;this.ranges=Collections.unmodifiableList(copy);
    }
    public static boolean supported(String mime) {
        return "image/gif".equals(mime) || "image/png".equals(mime) || "image/webp".equals(mime) || "application/x-tgsticker".equals(mime) || "video/webm".equals(mime);
    }
    public static void initialize() {
        org.jivesoftware.smack.provider.ProviderManager.addExtensionProvider("html",HTML,StandardExtensionElementProvider.INSTANCE);
        org.jivesoftware.smack.provider.ProviderManager.addExtensionProvider("data",BOB,StandardExtensionElementProvider.INSTANCE);
    }
    public InlineEmoji inBody(String full,int start) {
        int offset=full.offsetByCodePoints(0,start);
        if(!full.regionMatches(offset,body,0,body.length())) throw new IllegalArgumentException("Emoji belong to another body");
        List<Range> shifted=new ArrayList<>();for(Range range:ranges) shifted.add(new Range(range.start+start,range.end+start,range.asset));
        return new InlineEmoji(full,shifted);
    }
    public InlineEmoji slice(int start,int end) {
        int length=body.codePointCount(0,body.length());if(start<0 || end<start || end>length) throw new IllegalArgumentException("Invalid emoji slice");
        List<Range> result=new ArrayList<>();for(Range range:ranges) if(range.start>=start && range.end<=end) result.add(new Range(range.start-start,range.end-start,range.asset));
        return new InlineEmoji(body.substring(body.offsetByCodePoints(0,start),body.offsetByCodePoints(0,end)),result);
    }
    public InlineEmoji without(Collection<int[]> intervals) {
        int[] text=body.codePoints().toArray();boolean[] removed=new boolean[text.length];
        for(int[] span:intervals) {
            if(span==null || span.length!=2 || span[0]<0 || span[1]<span[0] || span[1]>text.length) throw new IllegalArgumentException("Invalid emoji removal");
            Arrays.fill(removed,span[0],span[1],true);
        }
        int[] map=new int[text.length+1];StringBuilder visible=new StringBuilder();
        for(int i=0;i<text.length;i++) { map[i+1]=map[i];if(!removed[i]) { visible.appendCodePoint(text[i]);map[i+1]++; } }
        List<Range> result=new ArrayList<>();
        for(Range range:ranges) { boolean intact=true;for(int i=range.start;i<range.end;i++) if(removed[i]) intact=false;
            if(intact) result.add(new Range(map[range.start],map[range.end],range.asset)); }
        return new InlineEmoji(visible.toString(),result);
    }
    public void addTo(Message message) {
        if(!body.equals(message.getBody()) || message.hasExtension("html",HTML) || message.hasExtension("data",BOB)) throw new IllegalArgumentException("Inline emoji belong to another message");
        if(ranges.isEmpty()) return;
        StandardExtensionElement.Builder paragraph=StandardExtensionElement.builder("p",XHTML);
        Map<String,Asset> assets=new LinkedHashMap<>();int previous=0;
        for(Range range:ranges) {
            if(range.asset.bytes==null) throw new IllegalArgumentException("Missing inline emoji bytes");
            StandardExtensionElement.Builder chunk=StandardExtensionElement.builder("span",XHTML);
            text(chunk,body.substring(previous,body.offsetByCodePoints(0,range.start)));
            String alt=body.substring(body.offsetByCodePoints(0,range.start),body.offsetByCodePoints(0,range.end));
            chunk.addElement(StandardExtensionElement.builder("img",XHTML).addAttribute("src","cid:"+range.asset.cid).addAttribute("alt",alt).addAttribute("width","32").addAttribute("height","32").build());
            paragraph.addElement(chunk.build());
            previous=body.offsetByCodePoints(0,range.end);assets.put(range.asset.cid,range.asset);
        }
        text(paragraph,body.substring(previous));
        message.addExtension(StandardExtensionElement.builder("html",HTML).addElement(StandardExtensionElement.builder("body",XHTML).addElement(paragraph.build()).build()).build());
        for(Asset asset:assets.values()) message.addExtension(StandardExtensionElement.builder("data",BOB).addAttribute("cid",asset.cid).addAttribute("type",asset.mime)
                .addAttribute("max-age","31536000").setText(Base64.getEncoder().encodeToString(asset.bytes)).build());
    }
    private static void text(StandardExtensionElement.Builder parent,String text) {
        // Repeated outer spans preserve order; each contains at most one text span and one image.
        if(!text.isEmpty()) parent.addElement(StandardExtensionElement.builder("span",XHTML).setText(text).build());
    }
    /** Only call with cleartext or authenticated SCE content, never a legacy encrypted outer stanza. */
    public static InlineEmoji from(Message message,String body) {
        if(message==null || body==null || !body.equals(message.getBody())) return null;
        try {
            List<ExtensionElement> html=message.getExtensions("html",HTML),data=message.getExtensions("data",BOB);
            if(html.size()!=1 || data.isEmpty() || data.size()>MAX_ASSETS) return null;
            Map<String,Asset> assets=new LinkedHashMap<>();int total=0;
            for(ExtensionElement extension:data) {
                StandardExtensionElement entry=element(extension);String encoded=entry.getText();
                if(encoded==null || encoded.length()>(MAX_BYTES+2)/3*4 || !attributes(entry,"cid","type","max-age") || !entry.getElements().isEmpty()) return null;
                byte[] bytes=Base64.getDecoder().decode(encoded);total+=bytes.length;if(total>MAX_BYTES) return null;
                Asset asset=new Asset(entry.getAttributeValue("cid"),entry.getAttributeValue("type"),bytes);
                if(assets.put(asset.cid,asset)!=null) return null;
            }
            StandardExtensionElement wrapper=element(html.get(0));
            if(!attributes(wrapper) || !blank(wrapper) || wrapper.getElements().size()!=1) return null;
            StandardExtensionElement content=wrapper.getElements().get(0);
            if(!"body".equals(content.getElementName()) || !XHTML.equals(content.getNamespace()) || !attributes(content,"xml:lang") || !blank(content)) return null;
            if(content.getAttributeValue("xml:lang")!=null && !content.getAttributeValue("xml:lang").equals(message.getLanguage())) return null;
            StringBuilder fallback=new StringBuilder();List<Range> ranges=new ArrayList<>();int[] nodes={0};
            for(StandardExtensionElement child:content.getElements()) parse(child,fallback,ranges,assets,0,nodes);
            if(!body.equals(fallback.toString()) || ranges.isEmpty()) return null;
            return new InlineEmoji(body,ranges);
        } catch(Exception malformed) { return null; }
    }
    private static void parse(StandardExtensionElement element,StringBuilder text,List<Range> ranges,Map<String,Asset> assets,int depth,int[] nodes) throws IOException {
        if(depth>8 || ++nodes[0]>256 || !XHTML.equals(element.getNamespace())) throw new IOException();
        String name=element.getElementName();
        if("img".equals(name)) {
            if(!attributes(element,"src","alt","width","height") || !element.getElements().isEmpty() || !blank(element)) throw new IOException();
            String source=element.getAttributeValue("src"),alt=element.getAttributeValue("alt");
            if(source==null || !source.startsWith("cid:") || alt==null || alt.isEmpty() || alt.length()>128) throw new IOException();
            Asset asset=assets.get(source.substring(4));if(asset==null) throw new IOException();
            int start=text.codePointCount(0,text.length());text.append(alt);ranges.add(new Range(start,start+alt.codePointCount(0,alt.length()),asset));
        } else if("span".equals(name) || "p".equals(name)) {
            if(!attributes(element)) throw new IOException();
            if(!element.getElements().isEmpty() && !blank(element)) throw new IOException();
            if(element.getText()!=null) text.append(element.getText());
            for(StandardExtensionElement child:element.getElements()) parse(child,text,ranges,assets,depth+1,nodes);
        } else throw new IOException();
        if(text.length()>131072 || ranges.size()>MAX_RANGES) throw new IOException();
    }
    private static boolean attributes(StandardExtensionElement element,String... allowed) {
        Set<String> names=new HashSet<>(Arrays.asList(allowed));for(String name:element.getAttributes().keySet()) if(!names.contains(name)) return false;return true;
    }
    private static boolean blank(StandardExtensionElement element) { return element.getText()==null || element.getText().trim().isEmpty(); }
    private static StandardExtensionElement element(ExtensionElement extension) throws Exception {
        if(extension.toXML().length()>240*1024) throw new IOException();
        return extension instanceof StandardExtensionElement ? (StandardExtensionElement)extension : StandardExtensionElementProvider.INSTANCE.parse(PacketParserUtils.getParserFor(extension.toXML().toString()));
    }
}
