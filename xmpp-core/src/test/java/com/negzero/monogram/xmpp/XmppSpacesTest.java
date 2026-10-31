package com.negzero.monogram.xmpp;

import java.util.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.util.PacketParserUtils;
import org.jivesoftware.smackx.pubsub.PayloadItem;
import org.jivesoftware.smackx.xdata.packet.DataForm;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class XmppSpacesTest {
    @Test void readsStandardBookmarksAndNestedSpacesWithoutExposingOtherPayloads()throws Exception{
        List<PayloadItem<ExtensionElement>> items=Arrays.asList(
            item("general@mix.example.org","<conference xmlns='urn:xmpp:bookmarks:1' name='General' autojoin='true'/>"),
            item("child","<subscription xmlns='urn:xmpp:pubsub:subscription:0' server='pubsub.example.org' node='development'/>") ,
            item("urn:xmpp:spaces:avatar:metadata:0","<metadata xmlns='urn:xmpp:avatar:metadata'><info url='https://example.org/avatar'/></metadata>"),
            item("future","<unknown xmlns='urn:example:future'><secret>Do not render this raw XML</secret></unknown>"));
        List<XmppSpaces.Entry> entries=XmppSpaces.entries(items);assertEquals(2,entries.size());assertEquals("General",entries.get(0).name);assertEquals("general@mix.example.org",entries.get(0).channel);assertEquals("pubsub.example.org\ndevelopment",entries.get(1).space.key());
    }
    @Test void defaultPrivateDirectoryAndPublicOptInHaveExplicitSafeConfiguration(){
        DataForm privateForm=XmppSpaces.configuration("Team",false);assertEquals("whitelist",privateForm.getField("pubsub#access_model").getFirstValue());assertEquals("publishers",privateForm.getField("pubsub#publish_model").getFirstValue());assertEquals(XmppSpaces.NS,privateForm.getField("pubsub#type").getFirstValue());
        assertTrue(Arrays.asList("true","1").contains(privateForm.getField("pubsub#persist_items").getFirstValue()));assertTrue(Arrays.asList("false","0").contains(privateForm.getField("pubsub#purge_offline").getFirstValue()));
        assertEquals("open",XmppSpaces.configuration("Team",true).getField("pubsub#access_model").getFirstValue());
    }
    @Test void invalidAndDuplicateDirectoryAddressesCannotBecomeChatDestinations()throws Exception{
        assertThrows(Exception.class,()->XmppSpaces.entries(Collections.singletonList(item("room@mix.example.org/resource","<conference xmlns='urn:xmpp:bookmarks:1'/>"))));
        PayloadItem<ExtensionElement> entry=item("room@mix.example.org","<conference xmlns='urn:xmpp:bookmarks:1'/>");assertThrows(IllegalArgumentException.class,()->XmppSpaces.entries(Arrays.asList(entry,entry)));
        assertThrows(Exception.class,()->new XmppSpaces.Reference("pubsub.example.org/resource","node",null));
        assertThrows(IllegalArgumentException.class,()->new XmppSpaces.Reference("pubsub.example.org","",null));
    }
    @Test void createsRealPubsubNodeAndNestedPublicationUsesStandardSubscription()throws Exception{
        try(MixTestConnection connection=new MixTestConnection();XmppSpaces spaces=new XmppSpaces(connection,()->{})){
            XmppSpaces.Reference parent=new XmppSpaces.Reference("pubsub.example.org","team","Team"),child=new XmppSpaces.Reference("pubsub.example.org","child","Child");
            spaces.create(parent,false);spaces.addSpace(parent,child);
            List<String> sent=new ArrayList<>();for(Stanza stanza:connection.sent)sent.add(XmppXml.string(stanza));
            assertTrue(sent.stream().anyMatch(xml->xml.contains("<create") && xml.contains("node='team'") && xml.contains("whitelist") && xml.contains("purge_offline")));
            assertTrue(sent.stream().anyMatch(xml->xml.contains("<publish") && xml.contains(XmppSpaces.SUBSCRIPTION) && xml.contains("server='pubsub.example.org'") && xml.contains("node='child'")));
            assertThrows(IllegalArgumentException.class,()->spaces.addSpace(parent,parent));
        }
    }
    private PayloadItem<ExtensionElement> item(String id,String xml)throws Exception{return new PayloadItem<>(id,org.jivesoftware.smack.parsing.StandardExtensionElementProvider.INSTANCE.parse(PacketParserUtils.getParserFor(xml)));}
}
