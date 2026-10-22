// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.util.*;
import java.io.*;
import org.jivesoftware.smack.packet.*;

/** XEP-0394 ranges use Unicode code points, never Java/Android UTF-16 offsets. */
public final class MessageMarkup {
    public static final String NAMESPACE="urn:xmpp:markup:0";
    public static final int MAX_RANGES=256;
    public static final String CONTENT="urn:xmpp:content";
    public final boolean markdown;
    public static final String STYLING="urn:xmpp:styling:0";
    private final boolean unstyled;
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
    public final List<MessageMentions.Mention> mentions;
    /** Optional opaque checklist envelope. Only consumers of authenticated plaintext may trust it. */
    public final ChecklistContent checklist;
    public MessageMarkup(String body,Collection<Range> ranges) { this(body,ranges,false); }
    private MessageMarkup(String body,Collection<Range> ranges,boolean unstyled) { this(body,ranges,unstyled,Collections.emptyList()); }
    private MessageMarkup(String body,Collection<Range> ranges,boolean unstyled,Collection<MessageMentions.Mention> mentions) { this(body,ranges,unstyled,mentions,false); }
    private MessageMarkup(String body,Collection<Range> ranges,boolean unstyled,Collection<MessageMentions.Mention> mentions,boolean markdown) { this(body,ranges,unstyled,mentions,markdown,null); }
    private MessageMarkup(String body,Collection<Range> ranges,boolean unstyled,Collection<MessageMentions.Mention> mentions,boolean markdown,ChecklistContent checklist) {
        this.markdown=markdown;this.unstyled=unstyled;
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
        this.body=body;this.ranges=Collections.unmodifiableList(copy);this.mentions=MessageMentions.checked(body,mentions);this.checklist=checklist;
    }
    public static void initialize() {
        InlineEmoji.initialize();MessageMentions.initialize();ChecklistContent.initialize();
        org.jivesoftware.smack.provider.ProviderManager.addExtensionProvider("content",CONTENT,
                org.jivesoftware.smack.parsing.StandardExtensionElementProvider.INSTANCE);
        org.jivesoftware.smack.provider.ProviderManager.addExtensionProvider("markup",NAMESPACE,
                org.jivesoftware.smack.parsing.StandardExtensionElementProvider.INSTANCE);
        org.jivesoftware.smack.provider.ProviderManager.addExtensionProvider("unstyled",STYLING,
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
        if(!ranges.isEmpty()) message.addExtension(markup.build());
        MessageMentions.addTo(message,mentions);
        addContentHint(message);
        if(checklist!=null) checklist.addTo(message);
        if(unstyled) message.addExtension(StandardExtensionElement.builder("unstyled",STYLING).build());
    }
    /** XEP-0481 empty hint selects a renderer for the body; never accepts alternate remote text. */
    public void addContentHint(Message message) {
        if(markdown) message.addExtension(StandardExtensionElement.builder("content",CONTENT).addAttribute("type","text/markdown").build());
    }
    public MessageMarkup asMarkdown() { return new MessageMarkup(body,ranges,true,mentions,true,checklist); }
    public MessageMarkup withChecklist(ChecklistContent checklist) { return new MessageMarkup(body,ranges,unstyled,mentions,markdown,checklist); }
    private static boolean markdownHint(Message message) {
        if(message==null) return false;
        List<ExtensionElement> hints=message.getExtensions("content",CONTENT);
        if(hints.size()!=1 || !(hints.get(0) instanceof StandardExtensionElement)) return false;
        StandardExtensionElement hint=(StandardExtensionElement)hints.get(0);
        return "text/markdown".equals(hint.getAttributeValue("type")) && hint.getElements().isEmpty()
                && (hint.getText()==null || hint.getText().isEmpty()) && hint.getAttributes().size()==1;
    }
    /** Legacy OMEMO authenticates only body. A public empty hint can only change presentation of that same text. */
    public static MessageMarkup legacy(Message wire,String body) {
        MessageMarkup result=body(body);return result!=null && markdownHint(wire) ? result.asMarkdown() : result;
    }
    /** Call only with cleartext or authenticated decrypted content, never an encrypted outer stanza. */
    private static MessageMarkup metadata(Message message,String body) {
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
    static void validateProtected(Message message) throws java.security.GeneralSecurityException {
        List<ExtensionElement> markup=message.getExtensions("markup",NAMESPACE),hints=message.getExtensions("unstyled",STYLING);
        if(markup.size()>1 || hints.size()>1) throw Omemo2Bundle.invalid();
        if(!markup.isEmpty()) {
            ExtensionElement extension=markup.get(0);
            if(!(extension instanceof StandardExtensionElement) || extension.toXML().length()>65536
                    || !bounded((StandardExtensionElement)extension,0,new int[]{0}) || metadata(message,message.getBody())==null)
                throw Omemo2Bundle.invalid();
        }
        if(!hints.isEmpty()) {
            ExtensionElement extension=hints.get(0);
            if(!(extension instanceof StandardExtensionElement)) throw Omemo2Bundle.invalid();
            StandardExtensionElement hint=(StandardExtensionElement)extension;
            if(!hint.getElements().isEmpty() || !hint.getAttributes().isEmpty() || hint.getText()!=null && !hint.getText().isEmpty())
                throw Omemo2Bundle.invalid();
        }
    }
    private static boolean bounded(StandardExtensionElement element,int depth,int[] nodes) {
        if(depth>16 || ++nodes[0]>2048) return false;
        for(StandardExtensionElement child:element.getElements()) if(!bounded(child,depth+1,nodes)) return false;
        return true;
    }
    /** Metadata is trusted only when the caller supplies plaintext or authenticated decrypted content. */
    public static MessageMarkup from(Message message,String body) {
        if(body==null) return null;
        MessageMarkup metadata=metadata(message,body);
        boolean unstyled=message!=null && message.hasExtension("unstyled",STYLING);
        ChecklistContent checklist=message!=null && body.equals(message.getBody()) ? ChecklistContent.from(message) : null;
        return new MessageMarkup(body,metadata==null ? Collections.emptyList() : metadata.ranges,unstyled,MessageMentions.from(message,body),message!=null && body.equals(message.getBody()) && markdownHint(message),checklist);
    }
    /** Pure body parser for legacy OMEMO, whose outer extensions are not authenticated. */
    public static MessageMarkup body(String body) { return body==null ? null : new MessageMarkup(body,Collections.emptyList()); }
    public MessageMarkup slice(int start,int end) {
        int length=body.codePointCount(0,body.length());
        if(start<0 || end<start || end>length) throw new IllegalArgumentException("Invalid markup slice");
        List<int[]> removed=new ArrayList<>();removed.add(new int[]{0,start});removed.add(new int[]{end,length});
        return without(removed);
    }
    /** Remove declared fallback ranges, preserving original code-point boundaries even for disjoint spans. */
    public MessageMarkup without(Collection<int[]> intervals) {
        int[] text=body.codePoints().toArray();boolean[] removed=new boolean[text.length];
        for(int[] interval:intervals) {
            if(interval==null || interval.length!=2 || interval[0]<0 || interval[1]<interval[0] || interval[1]>text.length)
                throw new IllegalArgumentException("Invalid removed range");
            Arrays.fill(removed,interval[0],interval[1],true);
        }
        MessageMarkup result=mapped(text,removed,ranges,unstyled,mentions,true,checklist);
        return markdown ? result.asMarkdown() : result;
    }
    private static MessageMarkup mapped(int[] text,boolean[] removed,Collection<Range> ranges,boolean unstyled,List<MessageMentions.Mention> mentions,boolean intactMentions,ChecklistContent checklist) {
        int[] map=new int[text.length+1];StringBuilder visible=new StringBuilder();
        for(int i=0;i<text.length;i++) { map[i+1]=map[i];if(!removed[i]) { visible.appendCodePoint(text[i]);map[i+1]++; } }
        List<Range> projected=new ArrayList<>();
        for(Range range:ranges) if(map[range.end]>map[range.start])
            projected.add(new Range(map[range.start],map[range.end],range.styles,range.language));
        return new MessageMarkup(visible.toString(),normalize(projected),unstyled,MessageMentions.mapped(mentions,map,removed,intactMentions),false,checklist);
    }
    /** The exact parser map removes recognized directives only; raw models and archive offsets stay unchanged. */
    public MessageMarkup display() {
        if(markdown || unstyled || body.codePointCount(0,body.length())>65536) return this;
        Styling parsed=new Styling(body,ranges);parsed.parse();
        List<Range> styles=new ArrayList<>(ranges);
        if(styles.isEmpty()) styles.addAll(parsed.ranges);
        try { return mapped(parsed.text,parsed.removed,styles,true,mentions,false,checklist); }
        catch(IllegalArgumentException invalid) { return new MessageMarkup(body,ranges,true,mentions,markdown,checklist); }
    }
    public InlineEmoji displayInline(InlineEmoji inline) {
        if(inline==null || !body.equals(inline.body)) return null;
        if(markdown || unstyled || body.codePointCount(0,body.length())>65536) return inline;
        Styling parsed=new Styling(body,ranges);parsed.parse();List<int[]> removed=new ArrayList<>();
        for(int i=0;i<parsed.removed.length;) {
            if(!parsed.removed[i]) { i++;continue; } int start=i;while(i<parsed.removed.length && parsed.removed[i]) i++;
            removed.add(new int[]{start,i});
        }
        return inline.without(removed);
    }
    private static List<Range> normalize(Collection<Range> source) {
        List<Range> blocks=new ArrayList<>(),inline=new ArrayList<>();TreeSet<Integer> boundaries=new TreeSet<>();
        for(Range range:source) {
            if(range.block()) { blocks.add(range);boundaries.add(range.start);boundaries.add(range.end); }
            else { inline.add(range);boundaries.add(range.start);boundaries.add(range.end); }
        }
        List<Integer> points=new ArrayList<>(boundaries);List<Range> result=new ArrayList<>(blocks);
        for(int i=1;i<points.size();i++) {
            int start=points.get(i-1),end=points.get(i);EnumSet<Style> styles=EnumSet.noneOf(Style.class);
            for(Range range:inline) if(range.start<=start && range.end>=end) styles.addAll(range.styles);
            if(!styles.isEmpty()) result.add(new Range(start,end,styles,null));
        }
        if(result.size()>MAX_RANGES) throw new IllegalArgumentException("Too many markup ranges");
        return result;
    }
    /** Native entities become interoperable body directives only when the literal text survives round-trip exactly. */
    public MessageMarkup preservingBody() { return new MessageMarkup(body,ranges,true,mentions,markdown,checklist); }
    public MessageMarkup styled() {
        if(markdown) return this;
        if(ranges.isEmpty()) return new MessageMarkup(body,ranges,true,mentions,false,checklist);
        int[] text=body.codePoints().toArray();Map<Integer,StringBuilder> opens=new HashMap<>(),closes=new HashMap<>();
        List<Range> directives=new ArrayList<>();
        for(Range range:ranges) if(range.block()) directives.add(range);
        for(Style style:new Style[]{Style.STRONG,Style.EMPHASIS,Style.DELETED,Style.CODE}) {
            List<Range> parts=new ArrayList<>();for(Range range:ranges) if(range.styles.contains(style)) parts.add(range);
            parts.sort(Comparator.comparingInt(range -> range.start));int start=-1,end=-1;
            for(Range range:parts) {
                if(start>=0 && range.start<=end) end=Math.max(end,range.end);
                else { if(start>=0) directives.add(new Range(start,end,Collections.singleton(style),null));start=range.start;end=range.end; }
            }
            if(start>=0) directives.add(new Range(start,end,Collections.singleton(style),null));
        }
        directives.sort(Comparator.comparingInt((Range range) -> range.start).thenComparing((a,b) -> Integer.compare(b.end,a.end)));
        for(Range range:directives) {
            String open="",close="";
            if(range.styles.contains(Style.PRE)) {
                if(range.start!=0 && text[range.start-1]!='\n' || range.end<text.length && text[range.end]!='\n')
                    return new MessageMarkup(body,ranges,true,mentions,false,checklist);
                open="```\n";close="\n```";
            } else if(range.styles.contains(Style.QUOTE)) {
                if(range.start!=0 && text[range.start-1]!='\n' || range.end<text.length && text[range.end]!='\n')
                    return new MessageMarkup(body,ranges,true,mentions,false,checklist);
                opens.computeIfAbsent(range.start,k -> new StringBuilder()).append("> ");
                for(int i=range.start;i<range.end-1;i++) if(text[i]=='\n') opens.computeIfAbsent(i+1,k -> new StringBuilder()).append("> ");
                continue;
            } else {
                for(Style style:new Style[]{Style.STRONG,Style.EMPHASIS,Style.DELETED,Style.CODE}) if(range.styles.contains(style)) {
                    String marker=style==Style.STRONG ? "*" : style==Style.EMPHASIS ? "_" : style==Style.DELETED ? "~" : "`";
                    open+=marker;close=marker+close;
                }
            }
            opens.computeIfAbsent(range.start,k -> new StringBuilder()).append(open);
            closes.computeIfAbsent(range.end,k -> new StringBuilder()).insert(0,close);
        }
        StringBuilder wire=new StringBuilder();int[] starts=new int[text.length+1],ends=new int[text.length+1];int position=0;
        for(int i=0;i<=text.length;i++) {
            if(closes.containsKey(i)) { String markers=closes.get(i).toString();wire.append(markers);position+=markers.codePointCount(0,markers.length()); }
            ends[i]=position;
            if(opens.containsKey(i)) { String markers=opens.get(i).toString();wire.append(markers);position+=markers.codePointCount(0,markers.length()); }
            starts[i]=position;
            if(i<text.length) { wire.appendCodePoint(text[i]);position++; }
        }
        List<Range> shifted=new ArrayList<>();
        for(Range range:ranges) shifted.add(new Range(starts[range.start],ends[range.end],range.styles,range.language));
        MessageMarkup encoded;
        try { List<MessageMentions.Mention> moved=new ArrayList<>();for(MessageMentions.Mention mention:mentions) moved.add(new MessageMentions.Mention(starts[mention.start],ends[mention.end],mention.address));
            encoded=new MessageMarkup(wire.toString(),normalize(shifted),false,moved,false,checklist); }
        catch(IllegalArgumentException invalid) { return new MessageMarkup(body,ranges,true,mentions,markdown,checklist); }
        return encoded.display().body.equals(body) ? encoded : new MessageMarkup(body,ranges,true,mentions,false,checklist);
    }
    /** Rebase already-encoded markup after a reply prefix or SFS URL is added. Never search for a repeated substring. */
    public MessageMarkup inBody(String fullBody,int start) {
        int length=body.codePointCount(0,body.length()),offset=fullBody.offsetByCodePoints(0,start);
        if(!fullBody.regionMatches(offset,body,0,body.length())) throw new IllegalArgumentException("Markup belongs to another body");
        List<Range> shifted=new ArrayList<>();for(Range range:ranges) shifted.add(new Range(range.start+start,range.end+start,range.styles,range.language));
        List<MessageMentions.Mention> moved=new ArrayList<>();for(MessageMentions.Mention mention:mentions) moved.add(new MessageMentions.Mention(mention.start+start,mention.end+start,mention.address));
        return new MessageMarkup(fullBody,shifted,unstyled,moved,markdown,checklist);
    }
    public MessageMarkup withMentions(Collection<MessageMentions.Mention> mentions) { return new MessageMarkup(body,ranges,unstyled,mentions,markdown,checklist); }
    /** Bounded local persistence codec. The body remains in the existing message record. */
    public String encode() {
        try {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
            out.writeByte(4);out.writeBoolean(markdown);out.writeBoolean(unstyled);out.writeShort(ranges.size());
            for(Range range:ranges) { out.writeInt(range.start);out.writeInt(range.end);int mask=0;for(Style style:range.styles) mask|=1<<style.ordinal();out.writeByte(mask);out.writeUTF(range.language==null ? "" : range.language); }
            out.writeShort(mentions.size());for(MessageMentions.Mention mention:mentions) { out.writeInt(mention.start);out.writeInt(mention.end);out.writeUTF(mention.address); }
            out.writeBoolean(checklist!=null);if(checklist!=null) { out.writeBoolean(checklist.update);writeAscii(out,checklist.data,ChecklistContent.MAX_DATA);if(checklist.update) writeAscii(out,checklist.target,ChecklistContent.MAX_TARGET); }
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch(IOException impossible) { throw new IllegalStateException(impossible); }
    }
    public static MessageMarkup decode(String body,String encoded) {
        if(body==null || encoded==null || encoded.length()>262144) return null;
        try {
            DataInputStream in=new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)));
            int version=in.readUnsignedByte();if(version<1 || version>4) return null;boolean markdown=version>=3 && in.readBoolean();boolean unstyled=in.readBoolean();int count=in.readUnsignedShort();
            if(count>MAX_RANGES) return null;List<Range> ranges=new ArrayList<>();
            for(int i=0;i<count;i++) {
                int start=in.readInt(),end=in.readInt(),mask=in.readUnsignedByte();EnumSet<Style> styles=EnumSet.noneOf(Style.class);
                if((mask & ~63)!=0) return null;for(Style style:Style.values()) if((mask & 1<<style.ordinal())!=0) styles.add(style);
                String language=in.readUTF();ranges.add(new Range(start,end,styles,language.isEmpty() ? null : language));
            }
            List<MessageMentions.Mention> mentions=new ArrayList<>();if(version>=2) { int total=in.readUnsignedShort();if(total>MessageMentions.MAX) return null;
                for(int i=0;i<total;i++) mentions.add(new MessageMentions.Mention(in.readInt(),in.readInt(),in.readUTF())); }
            ChecklistContent checklist=null;
            if(version>=4 && in.readBoolean()) {
                boolean update=in.readBoolean();String data=readAscii(in,ChecklistContent.MAX_DATA);
                if(data==null) return new MessageMarkup(body,ranges,unstyled,mentions,markdown);
                String target=update ? readAscii(in,ChecklistContent.MAX_TARGET) : null;
                if(update && target==null) return new MessageMarkup(body,ranges,unstyled,mentions,markdown);
                try { checklist=update ? ChecklistContent.update(target,data) : ChecklistContent.document(data); }
                catch(IllegalArgumentException malformedOptional) { /* Keep local readable message data when optional metadata is corrupt. */ }
            }
            if(in.read()!=-1) return null;return new MessageMarkup(body,ranges,unstyled,mentions,markdown,checklist);
        } catch(IOException|IllegalArgumentException invalid) { return null; }
    }
    private static void writeAscii(DataOutputStream out,String value,int maximum) throws IOException {
        if(value==null || value.length()>maximum) throw new IOException("Invalid optional metadata");
        out.writeInt(value.length());for(int i=0;i<value.length();i++) { char c=value.charAt(i);if(c>0x7f) throw new IOException("Invalid optional metadata");out.writeByte(c); }
    }
    /** A null result means malformed or oversized optional data; it is deliberately non-fatal to body recovery. */
    private static String readAscii(DataInputStream in,int maximum) throws IOException {
        int length=in.readInt();if(length<0 || length>maximum) return null;
        byte[] value=new byte[length];in.readFully(value);for(byte b:value) if((b&0x80)!=0) return null;
        return new String(value,java.nio.charset.StandardCharsets.US_ASCII);
    }
    private static boolean space(int c) { return Character.isWhitespace(c) || Character.isSpaceChar(c) || c==0x85; }
    private static final class Styling {
        final int[] text;final boolean[] removed;final List<Range> ranges=new ArrayList<>();
        final List<Range> declared;
        final boolean inferQuotes;
        Styling(String body,List<Range> declared) {
            text=body.codePoints().toArray();removed=new boolean[text.length];this.declared=declared;
            inferQuotes=declared.stream().noneMatch(range -> range.styles.contains(Style.QUOTE));
        }
        /** Explicit quotes strip only their own fallback prefix, preserving literal markers inside them. */
        boolean quotePrefix(int line) {
            for(Range range:declared) if(range.styles.contains(Style.QUOTE)) {
                int first=range.start-2;
                if(first>=0 && text[first]=='>' && text[first+1]==' ' && (first==0 || text[first-1]=='\n')
                        && (line==first || line>range.start && line<range.end)) return true;
            }
            return false;
        }
        void parse() {
            for(int line=0;line<text.length;) {
                int end=line;while(end<text.length && text[end]!='\n') end++;
                int start=line,quote=0;
                while(start+1<end && text[start]=='>' && text[start+1]==' ' && quote<8
                        && (inferQuotes || quote==0 && quotePrefix(line))) {
                    removed[start++]=true;removed[start++]=true;quote++;
                }
                if(inferQuotes && quote>0 && start<end) ranges.add(new Range(start,end,Collections.singleton(Style.QUOTE),null));
                if(start+2<end && text[start]=='`' && text[start+1]=='`' && text[start+2]=='`') {
                    int content=end<text.length ? end+1 : end,close=content;
                    while(close<text.length) {
                        int next=close;while(next<text.length && text[next]!='\n') next++;
                        if(next-close==3 && text[close]=='`' && text[close+1]=='`' && text[close+2]=='`') break;
                        close=next<text.length ? next+1 : next;
                    }
                    Arrays.fill(removed,start,content,true);
                    int stop=close;if(close<text.length) { if(stop>content && text[stop-1]=='\n') removed[--stop]=true;Arrays.fill(removed,close,Math.min(text.length,close+3),true); }
                    if(stop>content) ranges.add(new Range(content,stop,Collections.singleton(Style.PRE),null));
                    line=close<text.length ? close+3 : text.length;
                    if(line<text.length && text[line]=='\n') line++;
                } else { spans(start,end,0);line=end<text.length ? end+1 : end; }
                if(ranges.size()>MAX_RANGES) { ranges.clear();Arrays.fill(removed,false);return; }
            }
        }
        void spans(int start,int end,int depth) {
            if(depth>8) return;
            for(int i=start;i<end;i++) {
                int marker=text[i];Style style=marker=='*' ? Style.STRONG : marker=='_' ? Style.EMPHASIS : marker=='~' ? Style.DELETED : marker=='`' ? Style.CODE : null;
                if(style==null || i+1>=end || space(text[i+1]) || text[i+1]==marker || i!=start && !space(text[i-1])) continue;
                int close=i+2;while(close<end && (text[close]!=marker || space(text[close-1]))) close++;
                if(close>=end) continue;
                removed[i]=removed[close]=true;ranges.add(new Range(i+1,close,Collections.singleton(style),null));
                if(style!=Style.CODE) spans(i+1,close,depth+1);
                i=close;
            }
        }
    }
    private static int index(StandardExtensionElement element,String name) {
        String value=element.getAttributeValue(name);
        if(value==null || value.isEmpty() || value.length()>10) throw new IllegalArgumentException("Missing markup index");
        for(int i=0;i<value.length();i++) if(value.charAt(i)<'0' || value.charAt(i)>'9') throw new IllegalArgumentException("Invalid markup index");
        return Integer.parseInt(value);
    }
}
