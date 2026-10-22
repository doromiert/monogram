package org.telegram.messenger.xmpp;
import static org.junit.Assert.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.json.*;
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class)
public class MonogramUpdaterTest {
    private JSONObject release() throws Exception {
        JSONObject asset=new JSONObject().put("name","monogram-0.2.144-arm64.apk").put("state","uploaded")
                .put("browser_download_url","https://github.com/doromiert/monogram/releases/download/v0.2.144/monogram-0.2.144-arm64.apk")
                .put("size",74000000).put("digest","sha256:"+String.join("",java.util.Collections.nCopies(64,"a")));
        return new JSONObject().put("tag_name","v0.2.144").put("assets",new JSONArray().put(asset));
    }
    @Test public void selectsExactReleaseAndArchitecture() throws Exception {
        MonogramUpdater.Release parsed=MonogramUpdater.parse(release(),"arm64-v8a");assertEquals("0.2.144",parsed.version);assertEquals(74000000,parsed.size);
        assertNull(MonogramUpdater.parse(release(),"x86_64"));
        assertNull(MonogramUpdater.parse(release().put("prerelease",true),"arm64-v8a"));
    }
    @Test public void rejectsForeignAssetsMissingDigestAndOversize() throws Exception {
        JSONObject json=release();json.getJSONArray("assets").getJSONObject(0).put("browser_download_url","https://example.org/app.apk");
        assertThrows(java.io.IOException.class,()->MonogramUpdater.parse(json,"arm64-v8a"));
        JSONObject missing=release();missing.getJSONArray("assets").getJSONObject(0).remove("digest");
        assertThrows(java.io.IOException.class,()->MonogramUpdater.parse(missing,"arm64-v8a"));
        JSONObject large=release();large.getJSONArray("assets").getJSONObject(0).put("size",300000000);
        assertThrows(java.io.IOException.class,()->MonogramUpdater.parse(large,"arm64-v8a"));
    }
    @Test public void comparesVersionsNumericallyAndRejectsNonReleaseTags() {
        assertTrue(MonogramUpdater.version("0.2.100")>MonogramUpdater.version("v0.2.99"));
        assertTrue(MonogramUpdater.version("1.0.0")>MonogramUpdater.version("0.99.999"));
        assertThrows(IllegalArgumentException.class,()->MonogramUpdater.version("v0.2.144-beta"));
    }
}
