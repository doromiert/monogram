package org.telegram.messenger.xmpp;

import static org.junit.Assert.*;
import android.text.SpannableString;
import android.text.style.URLSpan;
import com.negzero.monogram.xmpp.MessageFixture;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows=MonogramMessageRegressionTest.Device.class)
public class MonogramMessageRegressionTest {
    @org.robolectric.annotation.Implements(value=org.telegram.messenger.AndroidUtilities.class,isInAndroidSdk=false)
    public static class Device {
        @org.robolectric.annotation.Implementation public static boolean isTablet() { return false; }
    }
    @Test public void internationalPhoneNumbersKeepCountryCodeAndCoexistWithUrls() {
        ApplicationLoader.applicationContext=RuntimeEnvironment.getApplication();
        for(String number:new String[]{"+48 123 456 789","+48-123-456-789","+1 202-555-0123","+44 20 7946 0958"}) {
            SpannableString text=new SpannableString(number+" https://example.org/page");
            MessageObject.addLinks(false,text,false,true);
            boolean phone=false,web=false;
            for(URLSpan span:text.getSpans(0,text.length(),URLSpan.class)) {
                if(span.getURL().startsWith("tel:")) { assertEquals(number.replaceAll("[ -]",""),span.getURL().substring(4)); phone=true; }
                if(span.getURL().startsWith("https://example.org")) web=true;
            }
            assertTrue(number,phone); assertTrue(web); assertTrue(text.toString().startsWith(number));
            SpannableString alone=new SpannableString(number); MessageObject.addLinks(false,alone,false,true);
            assertEquals(number,1,alone.getSpans(0,alone.length(),URLSpan.class).length);
        }
    }
    @Test public void searchPaginatesByTimeAndExcludesDeletedRowsWithUnicodeMatching() throws Exception {
        android.content.Context context=RuntimeEnvironment.getApplication(); String file="message-search-regression.db";
        context.deleteDatabase(file);
        try(MonogramStore store=new MonogramStore(context,file)) {
            long self=store.peer("alice@example.org"),peer=store.peer("bob@example.org");
            int newest=store.save(MessageFixture.replyMessage("new","new",null,"ŻÓŁĆ needle",true,false,300000,"phone",true,null));
            int oldest=store.save(MessageFixture.replyMessage("old","old",null,"needle old",true,false,100000,"phone",true,null));
            int middle=store.save(MessageFixture.replyMessage("middle","middle",null,"needle middle",true,false,200000,"phone",true,null));
            TLRPC.TL_messages_messagesSlice page=store.search(self,peer,"needle",0,0,1,0,0,0,null,false,false);
            assertEquals(3,page.count); assertEquals(newest,page.messages.get(0).id);
            page=store.search(self,peer,"needle",newest,0,1,0,0,0,null,false,false); assertEquals(middle,page.messages.get(0).id);
            page=store.search(self,peer,"needle",middle,0,1,0,0,0,null,false,false); assertEquals(oldest,page.messages.get(0).id);
            assertEquals(newest,store.search(self,peer,"żółć",0,0,10,0,0,0,null,false,false).messages.get(0).id);
            store.deleteMessages(java.util.Collections.singleton(oldest));
            assertEquals(2,store.search(self,peer,"needle",0,0,10,0,0,0,null,false,false).count);
        } finally { context.deleteDatabase(file); }
    }
}
