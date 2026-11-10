// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.xmpp;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.util.PacketParserUtils;
import org.jxmpp.jid.impl.JidCreate;

/** Actual mobile compatibility parser/model and protected-content reference, fictional data only. */
public final class GenerateForwardVectors {
    static String b64(String value){return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));}
    static String b64(byte[] value){return Base64.getEncoder().encodeToString(value);}
    static String repeat(String text,int count){StringBuilder out=new StringBuilder();for(int i=0;i<count;i++)out.append(text);return out.toString();}
    static Message message(String body)throws Exception {Message clear=new Message(JidCreate.entityBareFrom("bob@example.org"),Message.Type.chat);clear.setFrom(JidCreate.entityFullFrom("alice@example.org/forwarder"));clear.setStanzaId("outer");if(body!=null)clear.setBody(body);return clear;}
    static void emit(Message message,boolean encrypted)throws Exception {
        message=PacketParserUtils.parseStanza(message.toXML().toString());
        XmppMessage model=XmppMessage.from(message,JidCreate.entityBareFrom("bob@example.org"),message.getBody(),false,true,null,null);
        if(model==null)throw new AssertionError("Missing mobile model");ForwardedContent parsed=ForwardedContent.parse(model.body);
        System.out.print("{\"xml\":\""+b64(message.toXML().toString())+"\",\"body\":\""+b64(model.body)+"\",\"forwarded\":"+(parsed!=null));
        if(parsed!=null)System.out.print(",\"name\":\""+b64(parsed.name)+"\",\"date\":"+parsed.date+",\"payload\":\""+b64(parsed.body)+"\"");
        if(encrypted){SecureRandom fixture=new SecureRandom(){@Override public void nextBytes(byte[] data){for(int i=0;i<data.length;i++)data[i]=(byte)i;}@Override public int nextInt(int bound){return 0;}};byte[] sce=Omemo2Payload.envelope(message,fixture);Message decoded=Omemo2Payload.content(message,sce);XmppMessage modern=XmppMessage.modern(message,decoded,JidCreate.entityBareFrom("bob@example.org"),null,null);if(!Objects.equals(model.body,modern.body)||modern.retraction!=null||modern.reactions!=null)throw new AssertionError("Forwarded authority boundary");try(Omemo2Payload.Sealed sealed=Omemo2Payload.seal(sce,fixture)){System.out.print(",\"sce\":\""+b64(sce)+"\",\"material\":\""+b64(sealed.material)+"\",\"ciphertext\":\""+b64(sealed.ciphertext)+"\"");}}
        System.out.println("}");
    }
    public static void main(String[] args)throws Exception {
        MessageMarkup.initialize();ReplyReference.initialize();MessageRetraction.initialize();PollDefinition.initialize();
        List<Message> values=new ArrayList<>();
        String[] names={"Alice 🧑‍💻","alice@example.org","ROOM@conference.example.org/Anon","\u202eAlice\n","Leading and trailing ","\u00a0",repeat("a",120)+"@long-server.example.org",repeat("😀",150),"Alice · Department"};
        for(String name:names)for(int date:new int[]{0,1700000000,Integer.MAX_VALUE})values.add(message(ForwardedContent.of(name,date,"  Text 😀\n\nwith trailing space  ").wireBody()));
        String base=ForwardedContent.of("Alice",1700000000,"body").wireBody();
        for(String invalid:new String[]{base.replace("2023-11-14","2023-02-30"),base.replace("\n\n","\r\n\r\n"),base.replace("Alice","Al\u202eice"),base.replace("Alice"," Alice "),base.replace("Z\n","+00:00\n"),base.replace("2023-11-14T22:13:20Z","2038-01-19T03:14:08Z"),base.replace("Alice",repeat("a",4100))})values.add(message(invalid));
        values.add(message(ForwardedContent.of("Empty",0,"").wireBody()));
        String inner="<message xmlns='jabber:client' from='original@example.org/device' id='forged'><body>Quoted content 😀</body><origin-id xmlns='urn:xmpp:sid:0' id='forged-origin'/><retract xmlns='urn:xmpp:message-retract:1' id='victim'/></message>";
        for(String annotation:new String[]{"<body>Annotation</body>","<body/>",""})values.add(PacketParserUtils.parseStanza("<message xmlns='jabber:client' from='alice@example.org/forwarder' to='bob@example.org' type='chat' id='outer'>"+annotation+"<forwarded xmlns='urn:xmpp:forward:0'><delay xmlns='urn:xmpp:delay' stamp='2026-10-23T09:00:00Z'/>"+inner+"</forwarded></message>"));
        values.add(PacketParserUtils.parseStanza("<message xmlns='jabber:client' from='alice@example.org/forwarder' to='bob@example.org' type='chat' id='outer'><body>Annotation</body><forwarded xmlns='urn:xmpp:forward:0'>"+inner+"</forwarded></message>"));
        for(String translated:new String[]{"<message xmlns='jabber:client' from='original@example.org'><body xml:lang='fr'>Bonjour</body><body>Default body</body></message>","<message xmlns='jabber:client' xml:lang='fr' from='original@example.org'><body xml:lang='fr'>Bonjour</body></message>","<message xmlns='jabber:client' xml:lang='en' from='original@example.org'><body xml:lang='fr'>Bonjour</body></message>"})values.add(PacketParserUtils.parseStanza("<message xmlns='jabber:client' from='alice@example.org/forwarder' to='bob@example.org' type='chat' id='outer'><body>Annotation</body><forwarded xmlns='urn:xmpp:forward:0'>"+translated+"</forwarded></message>"));
        System.out.println("[");for(int i=0;i<values.size();i++){if(i>0)System.out.println(",");emit(values.get(i),i==0||values.get(i).hasExtension("forwarded","urn:xmpp:forward:0"));}System.out.println("]");
    }
}
