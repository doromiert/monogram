package org.telegram.messenger.xmpp;
import static org.junit.Assert.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramStoryHistoryTest {
    @Test public void nativeFirstPageMinusOneAndFollowupReturnDistinctRows() throws Exception {
        long now=System.currentTimeMillis();JSONArray rows=new JSONArray();
        for(int id=1;id<=4;id++) rows.put(new JSONObject().put("id",id).put("owner","me@example.org").put("time",now));
        java.util.ArrayList<JSONObject> all=MonogramStories.profileRows(rows,"me@example.org","me@example.org",false,now);
        assertEquals(4,all.size());assertEquals(4,MonogramStories.profilePage(all,-1,2).get(0).getInt("id"));
        assertEquals(3,MonogramStories.profilePage(all,0,2).get(1).getInt("id"));
        assertEquals(2,MonogramStories.profilePage(all,3,2).get(0).getInt("id"));
        assertEquals(1,MonogramStories.profilePage(all,3,2).get(1).getInt("id"));
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
