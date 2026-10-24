package com.negzero.monogram.xmpp;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HashSet;
import org.jivesoftware.smack.packet.StandardExtensionElement;
import org.jivesoftware.smack.parsing.StandardExtensionElementProvider;
import org.jivesoftware.smack.util.PacketParserUtils;
import org.junit.jupiter.api.Test;

class Omemo2DeviceListTest {
    private StandardExtensionElement parse(String inner)throws Exception {
        return StandardExtensionElementProvider.INSTANCE.parse(PacketParserUtils.getParserFor("<devices xmlns='urn:xmpp:omemo:2' revision='keep'>"+inner+"</devices>"));
    }
    private StandardExtensionElement devices(int... ids)throws Exception {
        StringBuilder xml=new StringBuilder();for(int id:ids)xml.append("<device id='").append(id).append("'/>");return parse(xml.toString());
    }
    private static class Store implements Omemo2DeviceList.Store {
        StandardExtensionElement value;int reads,writes;
        Store(StandardExtensionElement value){this.value=value;}
        public StandardExtensionElement read()throws Exception{reads++;return value;}
        public void publish(StandardExtensionElement value)throws Exception{writes++;this.value=value;}
    }
    @Test void anotherClientsSignedLabelAndExtensionsSurviveOurAnnouncementAndSerialization()throws Exception {
        StandardExtensionElement old=parse("<device id='7' label='Other phone &amp; desktop' labelsig='unchanged-signature' extra='keep'><extension xmlns='urn:example:future' value='keep'/></device><metadata xmlns='urn:example:future'>opaque</metadata>");
        Store store=new Store(old);StandardExtensionElement merged=Omemo2DeviceList.announce(store,9);
        assertEquals(new HashSet<>(Arrays.asList(7,9)),Omemo2DeviceList.ids(merged));
        StandardExtensionElement decoded=StandardExtensionElementProvider.INSTANCE.parse(PacketParserUtils.getParserFor(merged.toXML().toString()));
        assertEquals("keep",decoded.getAttributeValue("revision"));
        StandardExtensionElement before=old.getElements().get(0),after=decoded.getElements().get(0);
        assertEquals(before.getNamespace(),after.getNamespace());assertEquals(before.getAttributes(),after.getAttributes());
        assertEquals("keep",after.getFirstElement("extension","urn:example:future").getAttributeValue("value"));
        assertEquals("opaque",decoded.getFirstElement("metadata","urn:example:future").getText());
        assertEquals(1,store.writes);assertEquals(2,store.reads);
    }
    @Test void alreadyRegisteredDeviceDoesNotRewriteItsLabelOrTheSharedList()throws Exception {
        StandardExtensionElement original=parse("<device id='9' label='My phone' labelsig='signed'/><device id='7' label='Other'/>");
        Store store=new Store(original);assertSame(original,Omemo2DeviceList.announce(store,9));assertEquals(0,store.writes);
    }
    @Test void absentNodeAllowsFirstRegistrationAndVerifiesIt()throws Exception {
        Store store=new Store(null);assertEquals(new HashSet<>(Arrays.asList(9)),Omemo2DeviceList.ids(Omemo2DeviceList.announce(store,9)));assertEquals(2,store.reads);
    }
    @Test void failedReadNeverPublishesAnEmptyReplacement()throws Exception {
        Store store=new Store(devices(7)){
            public StandardExtensionElement read()throws Exception{throw new IOException("Service unavailable or timeout");}
        };
        assertThrows(IOException.class,()->Omemo2DeviceList.announce(store,9));assertEquals(0,store.writes);
    }
    @Test void malformedDuplicateOrOversizedListIsNeverOverwritten()throws Exception {
        for(StandardExtensionElement invalid:Arrays.asList(parse("<device id='0'/>"),parse("<device id='7'/><device id='7'/>"),parse("<device id='7' label='"+new String(new char[66000]).replace('\0','x')+"'/>"),StandardExtensionElement.builder("wrong",Omemo2Bundle.NS).build())) {
            Store store=new Store(invalid);assertThrows(GeneralSecurityException.class,()->Omemo2DeviceList.announce(store,9));assertEquals(0,store.writes);
        }
    }
    @Test void racedAdditionIsFetchedAndPreservedWhenReannouncing()throws Exception {
        StandardExtensionElement race=parse("<device id='7' label='First' labelsig='sig'/><device id='11' label='Concurrent client' labelsig='sig2'/>");
        Store store=new Store(devices(7)){
            public void publish(StandardExtensionElement next)throws Exception{super.publish(writes==0 ? race : next);}
        };
        StandardExtensionElement result=Omemo2DeviceList.announce(store,9);
        assertEquals(new HashSet<>(Arrays.asList(7,9,11)),Omemo2DeviceList.ids(result));assertEquals(2,store.writes);
        assertEquals("sig2",result.getElements("device").get(1).getAttributeValue("labelsig"));
    }
    @Test void perpetualConflictsStopAfterBoundedRetriesWithoutClaimingSuccess()throws Exception {
        Store store=new Store(devices(7)){
            public void publish(StandardExtensionElement next){writes++;}
        };
        assertThrows(GeneralSecurityException.class,()->Omemo2DeviceList.announce(store,9));assertEquals(3,store.writes);assertEquals(4,store.reads);
    }
    @Test void verificationFailureAbortsWithoutRetryingFromAnEmptySnapshot()throws Exception {
        Store store=new Store(devices(7)){
            public StandardExtensionElement read()throws Exception{if(reads++>0)throw new IOException("Lost connection after publish");return value;}
        };
        assertThrows(IOException.class,()->Omemo2DeviceList.announce(store,9));assertEquals(1,store.writes);assertTrue(Omemo2DeviceList.ids(store.value).contains(7));
    }
    @Test void fullListCannotBeReplacedOrExtendedPastTheDeviceLimit()throws Exception {
        int[] ids=new int[500];for(int i=0;i<ids.length;i++)ids[i]=i+1;Store store=new Store(devices(ids));
        assertThrows(GeneralSecurityException.class,()->Omemo2DeviceList.announce(store,999));assertEquals(0,store.writes);
    }
}
