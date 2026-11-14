// SPDX-License-Identifier: GPL-3.0-or-later
// Compiles and invokes the actual mobile MessageMarkup source; no accounts/network.
package com.negzero.monogram.xmpp;
import java.util.*;
import org.jivesoftware.smack.packet.Message;
public final class FormattingVectors {
 static String quote(String s){if(s==null)return "null";StringBuilder b=new StringBuilder("\"");for(char c:s.toCharArray()){switch(c){case '\\':b.append("\\\\");break;case '"':b.append("\\\"");break;case '\n':b.append("\\n");break;case '\r':b.append("\\r");break;case '\t':b.append("\\t");break;default:if(c<32)b.append(String.format("\\u%04x",(int)c));else b.append(c);}}return b.append('"').toString();}
 static String ranges(List<MessageMarkup.Range> ranges){StringJoiner out=new StringJoiner(",","[","]");for(MessageMarkup.Range r:ranges){StringJoiner styles=new StringJoiner(",","[","]");for(MessageMarkup.Style s:r.styles){String n=s.name().toLowerCase(Locale.ROOT);styles.add(quote(n.substring(0,1).toUpperCase(Locale.ROOT)+n.substring(1)));}out.add("{\"start\":"+r.start+",\"end\":"+r.end+",\"styles\":"+styles+",\"language\":"+quote(r.language)+"}");}return out.toString();}
 public static void main(String[] args){List<MessageMarkup> cases=new ArrayList<>();
  for(String s:Arrays.asList("😀 *strong _nested_* ~gone~ `*literal*`","https://example.org/a_b_c","word*not strong*","**","***","* bad*","*bad *","*cross\nline*",">greentext","> quote\n>greentext\n> > nested quote","```\na *literal*\n```","before\n```java\nhello\n```\nafter","```\nunclosed","\u00a0*bold*\u00a0"))cases.add(MessageMarkup.body(s));
  cases.add(new MessageMarkup("😀 secret tail",Arrays.asList(new MessageMarkup.Range(2,8,EnumSet.of(MessageMarkup.Style.STRONG,MessageMarkup.Style.SPOILER),null))).styled());
  cases.add(new MessageMarkup("😀 bold italic done",Arrays.asList(new MessageMarkup.Range(2,7,EnumSet.of(MessageMarkup.Style.STRONG),null),new MessageMarkup.Range(7,13,EnumSet.of(MessageMarkup.Style.STRONG,MessageMarkup.Style.EMPHASIS),null))).styled());
  cases.add(new MessageMarkup("first\nsecond",Arrays.asList(new MessageMarkup.Range(0,12,EnumSet.of(MessageMarkup.Style.QUOTE),null))).styled());
  Set<MessageMarkup> typed=new HashSet<>();
  for(String value:Arrays.asList("*text*","**text**","_text_","__text__","~text~","~~text~~","`text`","😀 *italic* and **bold**","<tg-spoiler>hidden</tg-spoiler>",">greentext","https://example.org/a_b_c","\\*literal\\*")){MessageMarkup markup=MessageMarkup.body(value).styled();cases.add(markup);typed.add(markup);}
  StringJoiner out=new StringJoiner(",\n","[\n","\n]");for(MessageMarkup source:cases){Message wire=new Message();wire.setBody(source.body);source.addTo(wire);MessageMarkup display=source.display();out.add("{\"xml\":"+quote(wire.toXML().toString())+",\"source\":"+quote(source.body)+",\"display\":"+quote(display.body)+",\"typed\":"+typed.contains(source)+",\"unstyled\":"+wire.hasExtension("unstyled","urn:xmpp:styling:0")+",\"markdown\":"+source.markdown+",\"ranges\":"+ranges(display.ranges)+"}");}System.out.println(out);
 }
}
