// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.jivesoftware.smack.packet.*;

/** Structured Reaction Polls ProtoXEP 0.0.2. The optional body is never authoritative. */
public final class PollDefinition {
    public static final String NAMESPACE="urn:xmpp:polls:0";
    public static final int MAX_BYTES=65536;
    public final String question,description;
    public final List<Option> options;
    public final boolean multiple,closed;
    public static final class Option {
        public final String emoji,label;
        public Option(String emoji,String label) {
            if(emoji==null || emoji.length()>128)throw invalid();
            String canonical=ReactionEmoji.canonical(emoji);
            if(canonical==null)throw invalid();
            this.emoji=canonical;this.label=field(label,1024);
        }
    }
    public PollDefinition(String question,String description,List<Option> options,boolean multiple,boolean closed) {
        this.question=field(question,4096);
        this.description=description==null || description.isEmpty()?"":field(description,4096);
        if(options==null || options.size()<2 || options.size()>32)throw invalid();
        List<Option> copy=new ArrayList<>();Set<String> keys=new HashSet<>();
        for(Option value:options) {
            if(value==null || !keys.add(ReactionEmoji.key(value.emoji)))throw invalid();
            copy.add(new Option(value.emoji,value.label));
        }
        this.options=Collections.unmodifiableList(copy);this.multiple=multiple;this.closed=closed;
        if(element().toXML().toString().getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw invalid();
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid structured poll");}
    private static boolean whitespace(int c){return Character.isWhitespace(c)||Character.isSpaceChar(c)||c==0x85;}
    private static String field(String text,int max) {
        if(text==null || text.codePointCount(0,text.length())>max || text.codePoints().allMatch(PollDefinition::whitespace)
            || text.codePoints().anyMatch(c -> !(c==9||c==10||c==13||c>=32&&c<=0xd7ff||c>=0xe000&&c<=0xfffd||c>=0x10000&&c<=0x10ffff)))throw invalid();
        return text;
    }
    public static void initialize(){org.jivesoftware.smack.provider.ProviderManager.addExtensionProvider("poll",NAMESPACE,org.jivesoftware.smack.parsing.StandardExtensionElementProvider.INSTANCE);}
    public StandardExtensionElement element() {
        StandardExtensionElement.Builder poll=StandardExtensionElement.builder("poll",NAMESPACE).addAttribute("type",multiple?"multiple":"single");
        if(closed)poll.addAttribute("closed","true");
        poll.addElement("question",question);
        if(!description.isEmpty())poll.addElement("description",description);
        for(Option option:options)poll.addElement(StandardExtensionElement.builder("option",NAMESPACE).addAttribute("emoji",option.emoji).setText(option.label).build());
        return poll.build();
    }
    public void addTo(Message message){message.addExtension(element());}
    public String readableBody() {
        StringBuilder text=new StringBuilder("📊 ").append(question);
        if(!description.isEmpty())text.append("\n\n").append(description);
        text.append("\n\n");for(Option option:options)text.append(option.emoji).append(' ').append(option.label).append('\n');
        return text.append(closed?"Voting closed.":multiple?"Public votes. Choose any.":"Public votes. Choose one.").toString();
    }
    /** Build the optional body and independent poll/reply fallback ranges from structured fields. */
    public void addTo(Message message,String accompanying,boolean fallback,ReplyReference reply) {
        String body=accompanying==null?"":accompanying;
        String redundant=fallback?(body.isEmpty()?"":"\n\n")+readableBody():"";
        body+=redundant;if(reply!=null){body=reply.wireBody(body);reply.addTo(message);}
        message.setBody(body.isEmpty()?null:body);
        addTo(message);
        if(fallback) {
            int end=body.codePointCount(0,body.length()),start=end-redundant.codePointCount(0,redundant.length());
            message.addExtension(StandardExtensionElement.builder("fallback",ReplyReference.FALLBACK).addAttribute("for",NAMESPACE)
                .addElement(StandardExtensionElement.builder("body",ReplyReference.FALLBACK).addAttribute("start",Integer.toString(start)).addAttribute("end",Integer.toString(end)).build()).build());
        }
    }
    public static PollDefinition from(Message message) {
        List<ExtensionElement> found=message.getExtensions("poll",NAMESPACE);
        if(found.isEmpty())return null;
        if(found.size()!=1 || !(found.get(0) instanceof StandardExtensionElement))throw invalid();
        StandardExtensionElement root=(StandardExtensionElement)found.get(0);
        if(root.toXML().toString().getBytes(StandardCharsets.UTF_8).length>MAX_BYTES || root.getText()!=null&&!root.getText().codePoints().allMatch(PollDefinition::whitespace))throw invalid();
        String type=root.getAttributeValue("type"),close=root.getAttributeValue("closed");
        if(!"single".equals(type)&&!"multiple".equals(type) || close!=null&&!Arrays.asList("true","false","1","0").contains(close))throw invalid();
        String question=null,description=null;List<Option> options=new ArrayList<>();
        for(StandardExtensionElement child:root.getElements()) {
            if(!NAMESPACE.equals(child.getNamespace()) || !child.getElements().isEmpty())throw invalid();
            switch(child.getElementName()) {
                case "question":if(question!=null)throw invalid();question=field(child.getText(),4096);break;
                case "description":if(description!=null)throw invalid();description=field(child.getText(),4096);break;
                case "option":if(options.size()>=32)throw invalid();options.add(new Option(child.getAttributeValue("emoji"),child.getText()));break;
                default:throw invalid();
            }
        }
        return new PollDefinition(question,description,options,"multiple".equals(type),"true".equals(close)||"1".equals(close));
    }
    public String xml(){return element().toXML().toString();}
    public static PollDefinition fromXml(String xml) {
        if(xml==null || xml.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES || xml.contains("<!"))throw invalid();
        try {PollDefinition value=from(org.jivesoftware.smack.util.PacketParserUtils.parseStanza("<message xmlns='jabber:client'>"+xml+"</message>"));if(value==null)throw invalid();return value;}
        catch(Exception malformed){throw invalid();}
    }
    /** null means absent; empty means malformed and must never fall back to legacy body parsing. */
    static String incomingXml(Message content,boolean readable,boolean protectedContent) {
        if(!readable || !protectedContent || content==null || !content.hasExtension("poll",NAMESPACE))return null;
        try {validateProtected(content);return from(content).xml();}catch(IllegalArgumentException malformed){return "";}
    }
    public static void validateProtected(Message message) {
        PollDefinition poll=from(message);
        if(poll!=null && (message.hasExtension("reactions",MessageReactions.NAMESPACE) || message.hasExtension("retract",MessageRetraction.NAMESPACE)
            || message.hasExtension("apply-to",MessageRetraction.FASTEN) || message.hasExtension("file-sharing",FileShare.NAMESPACE)))throw invalid();
    }
    public boolean sameDefinition(PollDefinition other) {
        if(other==null || !question.equals(other.question)||!description.equals(other.description)||multiple!=other.multiple||options.size()!=other.options.size())return false;
        for(int i=0;i<options.size();i++)if(!ReactionEmoji.key(options.get(i).emoji).equals(ReactionEmoji.key(other.options.get(i).emoji))||!options.get(i).label.equals(other.options.get(i).label))return false;
        return true;
    }
    public PollDefinition withClosed(boolean value){return new PollDefinition(question,description,options,multiple,value);}
    /** Invalid ranges leave readable text intact; every offset is a Unicode code point. */
    static List<int[]> removedBodyRanges(Message message) {
        String body=message.getBody();if(body==null)return Collections.emptyList();
        int length=body.codePointCount(0,body.length());if(length>256*1024)return Collections.emptyList();
        List<int[]> ranges=new ArrayList<>();
        try {
            for(ExtensionElement extension:message.getExtensions("fallback",ReplyReference.FALLBACK)) {
                if(!(extension instanceof StandardExtensionElement))continue;
                StandardExtensionElement fallback=(StandardExtensionElement)extension;
                if(!NAMESPACE.equals(fallback.getAttributeValue("for")))continue;
                if(fallback.getText()!=null&&!fallback.getText().codePoints().allMatch(PollDefinition::whitespace))return Collections.emptyList();
                if(fallback.getElements().isEmpty()){if(ranges.size()>=64)return Collections.emptyList();ranges.add(new int[]{0,length});}
                for(StandardExtensionElement text:fallback.getElements()) {
                    if(!"body".equals(text.getElementName())||!ReplyReference.FALLBACK.equals(text.getNamespace())||text.getAttributeValue("xml:lang")!=null)continue;
                    if(!text.getElements().isEmpty()||text.getText()!=null&&!text.getText().isEmpty()||ranges.size()>=64)return Collections.emptyList();
                    String start=text.getAttributeValue("start"),end=text.getAttributeValue("end");
                    int a=start==null&&end==null?0:Integer.parseInt(start),b=start==null&&end==null?length:Integer.parseInt(end);
                    if(a<0||b<a||b>length)return Collections.emptyList();
                    ranges.add(new int[]{a,b});
                }
            }
        }catch(NumberFormatException invalid){return Collections.emptyList();}
        ranges.sort(Comparator.comparingInt(r->r[0]));return ranges;
    }
    /** Rebase reply fallbacks after removing the poll's separate redundant body ranges. */
    static ReplyReference replyAfterFallback(Message message,ReplyReference reply) {
        if(reply==null || reply.ranges.isEmpty())return reply;
        List<int[]> removed=removedBodyRanges(message);if(removed.isEmpty())return reply;
        String body=message.getBody();int length=body.codePointCount(0,body.length());boolean[] hidden=new boolean[length];
        for(int[] range:removed)Arrays.fill(hidden,range[0],range[1],true);
        int[] map=new int[length+1];for(int i=0;i<length;i++)map[i+1]=map[i]+(hidden[i]?0:1);
        StringBuilder encoded=new StringBuilder();
        for(String range:reply.ranges.split(",")) {
            String[] ends=range.split(":");int a=Integer.parseInt(ends[0]),b=ends[1].equals("*")?length:Integer.parseInt(ends[1]);
            if(a<0||b<a||b>length)return new ReplyReference(reply.to,reply.id);
            if(map[b]>map[a]){if(encoded.length()>0)encoded.append(',');encoded.append(map[a]).append(':').append(map[b]);}
        }
        return new ReplyReference(reply.to,reply.id,encoded.toString());
    }
    public String accompanyingBody(Message message) {
        String body=message.getBody();if(body==null)return "";
        StringBuilder out=new StringBuilder();int position=0;
        for(int[] range:removedBodyRanges(message)){if(range[0]>position)out.append(body,body.offsetByCodePoints(0,position),body.offsetByCodePoints(0,range[0]));position=Math.max(position,range[1]);}
        out.append(body,body.offsetByCodePoints(0,position),body.length());return out.toString();
    }
}
