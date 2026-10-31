package org.telegram.ui;

import android.content.Context;
import android.os.Bundle;
import androidx.collection.LongSparseArray;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;
import org.telegram.messenger.*;
import org.telegram.tgnet.*;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Adapters.ContactsAdapter;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Components.RecyclerListView;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows={
    MonogramComposeNavigationTest.Contacts.class,MonogramComposeNavigationTest.Clock.class,
    MonogramComposeNavigationTest.Lite.class,MonogramComposeNavigationTest.Labels.class,
    MonogramComposeNavigationTest.Text.class,MonogramNavigationTest.App.class,MonogramDrawerTest.Device.class,
    MonogramComposeNavigationTest.Colors.class,org.telegram.messenger.MonogramSystemEmojiTest.Config.class})
public class MonogramComposeNavigationTest {
    @Implements(value=org.telegram.ui.ActionBar.Theme.class,isInAndroidSdk=false)
    public static class Colors { @Implementation public static void __staticInitializer__(){} }
    @Implements(value=ContactsController.class,isInAndroidSdk=false)
    public static class Contacts {
        static ContactsController controller;
        @Implementation public void __constructor__(int account){}
        @Implementation public static ContactsController getInstance(int account){return controller;}
    }
    @Implements(value=ConnectionsManager.class,isInAndroidSdk=false)
    public static class Clock {
        @Implementation public static void __staticInitializer__(){}
        @Implementation public static int generateClassGuid(){return 1;}
    }
    @Implements(value=LiteMode.class,isInAndroidSdk=false)
    public static class Lite {
        @Implementation public static void __staticInitializer__(){}
        @Implementation public static boolean isEnabled(int flags){return false;}
    }
    @Implements(value=LocaleController.class,isInAndroidSdk=false)
    public static class Labels {
        @Implementation public static void __staticInitializer__(){}
        @Implementation public static String getString(int id){return "string-"+id;}
    }
    /** Only decorative cell painting is replaced; the production adapter selects and binds the labels. */
    @Implements(value=TextCell.class,isInAndroidSdk=false)
    public static class Text extends org.robolectric.shadows.ShadowViewGroup {
        String label;
        @Implementation public void setColors(int icon,int text){}
        @Implementation public void setTextAndValueAndColorfulIcon(String text,CharSequence value,boolean animated,int icon,int top,int bottom,boolean divider){label=text;}
    }
    static class Picker extends ContactsActivity {
        BaseFragment opened;
        Picker(boolean compose){super(arguments(compose));}
        @Override public boolean presentFragment(BaseFragment fragment,boolean remove){opened=fragment;return true;}
    }
    private static Bundle arguments(boolean compose){Bundle args=new Bundle();args.putBoolean("monogramCompose",compose);args.putBoolean("destroyAfterSelect",true);return args;}
    @Before public void setup(){
        ApplicationLoader.applicationContext=RuntimeEnvironment.getApplication();ApplicationLoader.applicationHandler=new android.os.Handler(android.os.Looper.getMainLooper());
        Contacts.controller=ReflectionHelpers.callConstructor(ContactsController.class,ReflectionHelpers.ClassParameter.from(int.class,0));
        Contacts.controller.sortedUsersSectionsArray=new ArrayList<>();Contacts.controller.sortedUsersMutualSectionsArray=new ArrayList<>();
        Contacts.controller.usersSectionsDict=new HashMap<>();Contacts.controller.usersMutualSectionsDict=new HashMap<>();Contacts.controller.phoneBookContacts=new ArrayList<>();
        AndroidUtilities.density=1;AndroidUtilities.displaySize=new android.graphics.Point(400,800);
    }
    private ContactsAdapter adapter(Picker picker){return new ContactsAdapter(RuntimeEnvironment.getApplication(),picker,0,false,null,new LongSparseArray<>(),0);}
    private String label(ContactsAdapter adapter,int position){
        TextCell view=Shadow.newInstanceOf(TextCell.class);RecyclerListView.Holder holder=new RecyclerListView.Holder(view);
        ReflectionHelpers.setField(holder,"mItemViewType",adapter.getItemViewType(0,position));adapter.onBindViewHolder(0,position,holder);return ((Text)Shadow.extract(view)).label;
    }
    @Test public void emptyRosterKeepsSpacesAndRoomJoinVisibleAndClickable(){
        ContactsAdapter adapter=adapter(new Picker(true));assertEquals(2,adapter.getSectionCount());assertEquals(4,adapter.getCountForSection(0));
        for(int row=0;row<3;row++){assertEquals(1,adapter.getItemViewType(0,row));assertTrue(adapter.isEnabled(null,0,row));}
        assertEquals("Spaces and channels",label(adapter,1));assertEquals("Join XMPP room",label(adapter,2));assertFalse(adapter.isEnabled(null,0,3));
    }
    @Test public void populatedRosterAddsContactsHeaderAfterAllThreeActions(){
        Contacts.controller.sortedUsersSectionsArray.add("B");TLRPC.TL_contact bob=new TLRPC.TL_contact();bob.user_id=20;
        Contacts.controller.usersSectionsDict.put("B",new ArrayList<>(Collections.singletonList(bob)));
        ContactsAdapter adapter=adapter(new Picker(true));assertEquals(2,adapter.getSectionCount());assertEquals(5,adapter.getCountForSection(0));
        assertEquals("Spaces and channels",label(adapter,1));assertEquals("Join XMPP room",label(adapter,2));assertEquals(5,adapter.getItemViewType(0,3));assertEquals(2,adapter.getItemViewType(0,4));assertEquals(0,adapter.getItemViewType(1,0));
    }
    @Test public void optionalSearchRowDoesNotShiftLabelsOrDisableRoomJoin(){
        ContactsAdapter adapter=adapter(new Picker(true));adapter.includeSearch=true;adapter.getSectionCount();assertEquals(5,adapter.getCountForSection(0));
        assertFalse(adapter.isEnabled(null,0,0));for(int row=1;row<=3;row++)assertTrue(adapter.isEnabled(null,0,row));
        assertEquals("Spaces and channels",label(adapter,2));assertEquals("Join XMPP room",label(adapter,3));assertFalse(adapter.isEnabled(null,0,4));
    }
    @Test public void actualComposeActionOpensSpacesAndRoomSettingsForTheSelectedAccount(){
        Picker picker=new Picker(true);picker.setCurrentAccount(1);assertTrue(picker.openMonogramComposeAction(1));assertTrue(picker.opened instanceof MonogramSpacesActivity);assertEquals(1,picker.opened.getCurrentAccount());
        assertTrue(picker.openMonogramComposeAction(2));assertTrue(picker.opened instanceof MonogramRoomActivity);assertEquals(1,picker.opened.getCurrentAccount());assertFalse(picker.openMonogramComposeAction(3));
    }
    @Test public void nonComposeContactPickerKeepsItsExistingHeaderAndDoesNotOpenSpaces(){
        Picker picker=new Picker(false);assertFalse(picker.openMonogramComposeAction(1));assertNull(picker.opened);
        ContactsAdapter adapter=adapter(picker);adapter.getSectionCount();assertEquals(2,adapter.getCountForSection(0));assertNotEquals("Spaces and channels",label(adapter,1));
    }
}
