package org.telegram.messenger.xmpp;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.tgnet.TLRPC;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramGiphyTest {
    private JSONObject gif(String url) throws Exception {
        JSONObject image = new JSONObject().put("url",url).put("width","200").put("height","120").put("size","4096");
        return new JSONObject().put("id","example").put("title","Example")
                .put("images",new JSONObject().put("original",image).put("fixed_width",image));
    }
    @Test public void externalResultsUseDirectHttpAndBoundedPagination() throws Exception {
        JSONObject response = new JSONObject().put("data",new JSONArray().put(gif("https://media0.giphy.com/media/example/giphy.gif")))
                .put("pagination",new JSONObject().put("offset",0).put("count",25).put("total_count",100));
        MonogramGiphy.Page page = MonogramGiphy.parse(response,false);
        assertEquals(1,page.results.size()); assertEquals("25",page.nextOffset);
        assertTrue(page.results.get(0).content instanceof TLRPC.TL_webDocumentNoProxy);
        assertTrue(MonogramGiphy.isResult(page.results.get(0)));
        assertEquals("",page.results.get(0).send_message.message);
        response.getJSONObject("pagination").put("offset",4975).put("total_count",6000);
        assertEquals("",MonogramGiphy.parse(response,false).nextOffset);
    }
    @Test public void untrustedMediaUrlsAreDiscarded() throws Exception {
        JSONArray data = new JSONArray();
        for (String url : new String[]{"http://media.giphy.com/a.gif","https://giphy.com.evil.invalid/a.gif",
                "https://user@media.giphy.com/a.gif","https://media.giphy.com:444/a.gif","https://127.0.0.1/a.gif"}) data.put(gif(url));
        assertTrue(MonogramGiphy.parse(new JSONObject().put("data",data),false).results.isEmpty());
    }
}
