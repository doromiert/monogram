package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.*;
import org.telegram.messenger.*;
import org.telegram.tgnet.TLRPC;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows={MonogramMediaDraftsTest.NativeMessage.class})
public class MonogramSelectionDraftsTest {
    @Test public void phonebookLookupAndAllPhoneMetadataAreLocalSnapshots() throws Exception {
        ContactsController.Contact source=new ContactsController.Contact();source.contact_id=73;source.key="private-lookup";source.provider="example";source.first_name="Alice";source.last_name="Example";source.namesFilled=true;source.phones.addAll(Arrays.asList("+40 123 456","+48-123-456"));source.shortPhones.addAll(Arrays.asList("40123456","48123456"));source.phoneTypes.addAll(Arrays.asList("home","work"));source.phoneDeleted.addAll(Arrays.asList(0,1));
        ContactsController.Contact copy=(ContactsController.Contact)MonogramSelectionDrafts.contact(MonogramSelectionDrafts.contact(source));
        source.phones.clear();assertEquals(73,copy.contact_id);assertEquals("private-lookup",copy.key);assertEquals("Alice",copy.first_name);assertEquals(Arrays.asList("+40 123 456","+48-123-456"),copy.phones);assertEquals(Arrays.asList(0,1),copy.phoneDeleted);assertTrue(copy.namesFilled);
    }
    @Test public void userVcardRestrictionPayloadSurvivesNativeVariants() throws Exception {
        TLRPC.TL_user user=new TLRPC.TL_user();user.id=9;user.flags=2|4|16;user.first_name="Alice";user.last_name="Example";user.phone="40123456";
        TLRPC.RestrictionReason vcard=new TLRPC.RestrictionReason();vcard.text="BEGIN:VCARD\nFN:Alice\nEND:VCARD";vcard.reason="";vcard.platform="";user.restriction_reason.add(vcard);
        TLRPC.User copy=(TLRPC.User)MonogramSelectionDrafts.contact(MonogramSelectionDrafts.contact(user));assertEquals(9,copy.id);assertEquals("Alice",copy.first_name);assertEquals(vcard.text,copy.restriction_reason.get(0).text);
    }
    @Test public void forwardSourceKeepsOwnerDialogEntitiesAndPrivateRetryParams() throws Exception {
        TLRPC.TL_message message=new TLRPC.TL_message();message.id=17;message.dialog_id=-7;message.message="text";message.peer_id=new TLRPC.TL_peerChat();message.peer_id.chat_id=7;message.flags|=TLRPC.MESSAGE_FLAG_HAS_ENTITIES;
        TLRPC.TL_messageEntityBold bold=new TLRPC.TL_messageEntityBold();bold.offset=0;bold.length=4;message.entities.add(bold);message.attachPath="/private/local-file";message.params=new HashMap<>();message.params.put("monogram-forward-address","encrypted-source");
        MessageObject original=new MessageObject(0,message,false,false),copy=MonogramSelectionDrafts.message(2,MonogramSelectionDrafts.message(original));assertEquals(2,copy.currentAccount);assertEquals(-7,copy.getDialogId());assertEquals(17,copy.getId());assertEquals("/private/local-file",copy.messageOwner.attachPath);assertEquals("encrypted-source",copy.messageOwner.params.get("monogram-forward-address"));assertTrue(copy.messageOwner.entities.get(0) instanceof TLRPC.TL_messageEntityBold);
    }
}
