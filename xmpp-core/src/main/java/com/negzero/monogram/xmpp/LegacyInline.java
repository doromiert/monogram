// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;
import java.io.IOException;import java.nio.charset.StandardCharsets;
import org.jivesoftware.smack.packet.*;import org.jivesoftware.smack.util.PacketParserUtils;
/** Proposed, negotiated inline artwork inside the authenticated Legacy OMEMO body. */
public final class LegacyInline {
 public static final String NAMESPACE="urn:monogram:legacy-inline:0",PREFIX=NAMESPACE+"\n";
 public static final int MAX=256*1024;
 private LegacyInline(){}
 public static String encode(String body,InlineEmoji emoji)throws IOException {
  if(emoji==null || emoji.ranges.isEmpty())return body;
  Message clear=new Message();clear.setStanzaId(null);clear.setBody(body);emoji.addTo(clear);
  String encoded=PREFIX+XmppXml.string(clear);if(encoded.getBytes(StandardCharsets.UTF_8).length>MAX)throw new IOException("Inline emoji message is too large");return encoded;
 }
 public static Message decode(String body)throws Exception {
  if(body==null || !body.startsWith(PREFIX))return null;
  String xml=body.substring(PREFIX.length());if(body.getBytes(StandardCharsets.UTF_8).length>MAX || xml.contains("<!"))throw new IOException("Invalid authenticated inline emoji content");
  org.jivesoftware.smack.parsing.StandardExtensionElementProvider provider=org.jivesoftware.smack.parsing.StandardExtensionElementProvider.INSTANCE;
  StandardExtensionElement root=provider.parse(PacketParserUtils.getParserFor(xml));
  if(!root.getElementName().equals("message") || !root.getNamespace().equals("jabber:client") || !root.getAttributes().isEmpty())throw new IOException("Invalid inline emoji envelope");
  int bodies=0;for(StandardExtensionElement child:root.getElements()){
   String name=child.getElementName(),ns=child.getNamespace();
   if(name.equals("body")&&ns.equals("jabber:client"))bodies++;
   else if(!(name.equals("html")&&ns.equals(InlineEmoji.HTML) || name.equals("data")&&ns.equals(InlineEmoji.BOB)))throw new IOException("Invalid inline emoji extension");
  }
  if(bodies!=1)throw new IOException("Invalid inline emoji body");
  Message clear=PacketParserUtils.parseStanza(xml);if(InlineEmoji.from(clear,clear.getBody())==null)throw new IOException("Invalid authenticated inline artwork");return clear;
 }
}
