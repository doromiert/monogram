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
    private android.content.pm.PackageInfo apk(String name,int code,String version,String key) {
        android.content.pm.PackageInfo info=new android.content.pm.PackageInfo();
        info.packageName=name;info.versionCode=code;info.versionName=version;
        info.signatures=new android.content.pm.Signature[]{new android.content.pm.Signature(key)};
        return info;
    }
    @Test public void preservesInstalledIdentityAndExplainsIncompatibleReleases() throws Exception {
        android.content.pm.PackageInfo current=apk("com.negzero.monogram.beta",74439,"0.2.226","1234");
        MonogramUpdater.verifyPackage(apk(current.packageName,74459,"0.2.228","1234"),current,"0.2.228");
        java.io.IOException renamed=assertThrows(java.io.IOException.class,()->MonogramUpdater.verifyPackage(apk("com.negzero.monogram",74449,"0.2.227","1234"),current,"0.2.227"));
        assertTrue(renamed.getMessage().contains("different app ID"));
        java.io.IOException signer=assertThrows(java.io.IOException.class,()->MonogramUpdater.verifyPackage(apk(current.packageName,74459,"0.2.228","5678"),current,"0.2.228"));
        assertTrue(signer.getMessage().contains("different signing key"));
        assertThrows(java.io.IOException.class,()->MonogramUpdater.verifyPackage(apk(current.packageName,74439,"0.2.228","1234"),current,"0.2.228"));
    }
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
