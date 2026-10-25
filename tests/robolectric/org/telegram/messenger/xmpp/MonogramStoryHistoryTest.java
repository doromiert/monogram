package org.telegram.messenger.xmpp;
import static org.junit.Assert.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,shadows=MonogramStoryHistoryTest.Controller.class)
public class MonogramStoryHistoryTest {
    private static MonogramController controller;
    @org.robolectric.annotation.Implements(value=MonogramController.class,isInAndroidSdk=false)
    public static class Controller {
        @org.robolectric.annotation.Implementation public void __constructor__(int account) {}
        @org.robolectric.annotation.Implementation public static MonogramController get(int account) {return controller;}
        @org.robolectric.annotation.Implementation public String accountJid() {return "me@example.org";}
    }
    @Before public void setup() {
        org.telegram.messenger.ApplicationLoader.applicationContext=org.robolectric.RuntimeEnvironment.getApplication();
        controller=org.robolectric.util.ReflectionHelpers.callConstructor(MonogramController.class,org.robolectric.util.ReflectionHelpers.ClassParameter.from(int.class,0));
    }
    @Test public void batchProfileRefreshKeepsStableIdsAndIndependentStoryActivity() throws Exception {
        String owner="me@example.org",peer="friend@example.org";long now=System.currentTimeMillis();
        android.content.SharedPreferences prefs=org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("monogram-stories-0",0);prefs.edit().clear().commit();
        JSONArray initial=new JSONArray().put(new JSONObject().put("id",7).put("owner",peer).put("wire","existing").put("url","https://example.org/one.jpg").put("mime","image/jpeg").put("time",now).put("active",true).put("pinned",true));
        prefs.edit().putString(owner+"\nitems",initial.toString()).putInt(owner+"\nnext",8).commit();
        MonogramStories stories=new MonogramStories(0,null,()->null);
        java.util.List<com.negzero.monogram.xmpp.XmppStories.Story> posts=java.util.Arrays.asList(
            new com.negzero.monogram.xmpp.XmppStories.Story("existing",peer,"https://example.org/one.jpg","image/jpeg","Updated",now),
            new com.negzero.monogram.xmpp.XmppStories.Story("older",peer,"https://example.org/two.jpg","image/jpeg","Saved",now-2*86400000L));
        for(int repeat=0;repeat<2;repeat++) org.robolectric.util.ReflectionHelpers.callInstanceMethod(stories,"replaceProfile",org.robolectric.util.ReflectionHelpers.ClassParameter.from(String.class,owner),org.robolectric.util.ReflectionHelpers.ClassParameter.from(String.class,peer),org.robolectric.util.ReflectionHelpers.ClassParameter.from(java.util.List.class,posts));
        JSONArray result=new JSONArray(prefs.getString(owner+"\nitems","[]"));assertEquals(2,result.length());assertEquals(7,result.getJSONObject(0).getInt("id"));assertEquals(8,result.getJSONObject(1).getInt("id"));assertEquals(9,prefs.getInt(owner+"\nnext",0));
        assertTrue(result.getJSONObject(0).getBoolean("active"));assertFalse(result.getJSONObject(1).getBoolean("active"));assertTrue(result.getJSONObject(1).getBoolean("pinned"));
    }
    @Test public void nativeFirstPageMinusOneAndFollowupReturnDistinctRows() throws Exception {
        long now=System.currentTimeMillis();JSONArray rows=new JSONArray();
        for(int id=1;id<=4;id++) rows.put(new JSONObject().put("id",id).put("owner","me@example.org").put("time",now));
        java.util.ArrayList<JSONObject> all=MonogramStories.profileRows(rows,"me@example.org","me@example.org",false,now);
        assertEquals(4,all.size());assertEquals(4,MonogramStories.profilePage(all,-1,2).get(0).getInt("id"));
        assertEquals(3,MonogramStories.profilePage(all,0,2).get(1).getInt("id"));
        assertEquals(2,MonogramStories.profilePage(all,3,2).get(0).getInt("id"));
        assertEquals(1,MonogramStories.profilePage(all,3,2).get(1).getInt("id"));
    }
    @Test public void pinnedProfilePostRemainsAfterStoryExpiry() throws Exception {
        long now=System.currentTimeMillis();JSONArray rows=new JSONArray().put(new JSONObject().put("id",1).put("owner","friend@example.org").put("time",now-2*86400000L).put("active",false).put("pinned",true));
        assertEquals(1,MonogramStories.profileRows(rows,"me@example.org","friend@example.org",false,now).size());
        rows.getJSONObject(0).put("pinned",false);
        assertTrue(MonogramStories.profileRows(rows,"me@example.org","friend@example.org",false,now).isEmpty());
    }
    @Test public void archiveIncludesOwnExpiredItemsButNeverAnotherPersonsHistory() throws Exception {
        long now=System.currentTimeMillis();JSONArray rows=new JSONArray()
            .put(new JSONObject().put("id",1).put("owner","me@example.org").put("time",now-2*86400000L).put("active",false))
            .put(new JSONObject().put("id",2).put("owner","me@example.org").put("time",now))
            .put(new JSONObject().put("id",3).put("owner","friend@example.org").put("time",now));
        assertEquals(1,MonogramStories.profileRows(rows,"me@example.org","me@example.org",false,now).size());
        assertEquals(2,MonogramStories.profileRows(rows,"me@example.org","me@example.org",true,now).size());
        assertTrue(MonogramStories.profileRows(rows,"me@example.org","friend@example.org",true,now).isEmpty());
    }
}
