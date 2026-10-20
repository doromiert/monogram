package org.telegram.messenger.xmpp;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.telegram.messenger.*;
import org.telegram.tgnet.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=36,application=android.app.Application.class,
        shadows={MonogramTranslationTest.Settings.class,MonogramTranslationTest.Ui.class,MonogramTranslationTest.Vault.class})
public class MonogramTranslationTest {
    @Implements(value=MessagesController.class,isInAndroidSdk=false)
    public static class Settings {
        @Implementation public static void __staticInitializer__() {}
        @Implementation public static SharedPreferences getMainSettings(int account) {
            return RuntimeEnvironment.getApplication().getSharedPreferences("translation-test-"+account,Context.MODE_PRIVATE);
        }
    }
    @Implements(value=AndroidUtilities.class,isInAndroidSdk=false)
    public static class Ui {
        static final BlockingQueue<Runnable> callbacks=new LinkedBlockingQueue<>();
        @Implementation public static void __staticInitializer__() {}
        @Implementation public static void runOnUIThread(Runnable runnable) { callbacks.add(runnable); }
    }
    @Implements(value=AccountVault.class,isInAndroidSdk=false)
    public static class Vault {
        @Implementation public void __constructor__(int account) {}
        @Implementation protected String encrypt(String key) { return "encrypted:"+key; }
        @Implementation protected String decrypt(String key) { return key.substring("encrypted:".length()); }
    }
    @Before public void setup() {
        Settings.getMainSettings(0).edit().clear().commit();Settings.getMainSettings(1).edit().clear().commit();Ui.callbacks.clear();
    }
    @Test public void endpointConfigurationIsExplicitHttpsAndDoesNotCarrySecretsAcrossProvidersOrHosts() throws Exception {
        assertFalse(MonogramTranslation.isConfigured(0));
        assertEquals("https://translate.example/path/translate",MonogramTranslation.validateEndpoint("libretranslate","https://translate.example/path/"));
        for(String bad:new String[]{"http://translate.example","https://user:pass@translate.example","https://translate.example?q=key","https://translate.example/#fragment",""}) {
            assertThrows(Exception.class,() -> MonogramTranslation.validateEndpoint("libretranslate",bad));
        }
        assertThrows(Exception.class,() -> MonogramTranslation.validateEndpoint("deepl","https://lookalike.example/v2/translate"));
        MonogramTranslation.saveConfiguration(0,"google","","secret");assertTrue(MonogramTranslation.isConfigured(0));
        assertEquals(MonogramTranslation.GOOGLE_ENDPOINT,MonogramTranslation.endpoint(0));
        assertEquals("encrypted:secret",Settings.getMainSettings(0).getString(MonogramTranslation.PREF_API_KEY,null));
        MonogramTranslation.saveConfiguration(0,"google","",null);
        assertThrows(Exception.class,() -> MonogramTranslation.saveConfiguration(0,"deepl",MonogramTranslation.DEEPL_FREE_ENDPOINT,null));
        MonogramTranslation.saveConfiguration(0,"libretranslate","https://first.example","libre-key");
        MonogramTranslation.saveConfiguration(0,"libretranslate","https://second.example",null);
        assertEquals("",Settings.getMainSettings(0).getString(MonogramTranslation.PREF_API_KEY,null));
        assertTrue(MonogramTranslation.isConfigured(0));assertFalse(MonogramTranslation.isConfigured(1));
        MonogramTranslation.clearConfiguration(0);assertFalse(MonogramTranslation.isConfigured(0));
    }
    @Test public void providersUsePlainTextBatchBodiesAndNeverReuseSourceEntityOffsets() throws Exception {
        ArrayList<String> texts=new ArrayList<>(Arrays.asList("Hello <friend>","Goodbye"));
        JSONObject libre=MonogramTranslation.body("libretranslate",texts,"pl","secret");
        assertEquals("auto",libre.getString("source"));assertEquals("text",libre.getString("format"));
        assertEquals(2,libre.getJSONArray("q").length());assertEquals("secret",libre.getString("api_key"));
        JSONObject google=MonogramTranslation.body("google",texts,"iw","secret");
        assertEquals("he",google.getString("target"));assertFalse(google.has("key"));assertFalse(google.has("api_key"));
        JSONObject deepl=MonogramTranslation.body("deepl",texts,"zh-hant","secret");
        assertEquals("ZH-HANT",deepl.getString("target_lang"));assertFalse(deepl.has("source_lang"));assertEquals(2,deepl.getJSONArray("text").length());
        for(String bad:new String[]{"auto","pl\nheader","unknown","https://host",null}) assertThrows(IllegalArgumentException.class,() -> MonogramTranslation.language(bad));
        TLRPC.TL_messages_translateResult result=MonogramTranslation.parse("libretranslate",new JSONObject("{\"translatedText\":[\"Cześć\",\"Do widzenia\"]}"),2);
        assertEquals("Cześć",result.result.get(0).text);assertTrue(result.result.get(0).entities.isEmpty());
        assertEquals("Hallo",MonogramTranslation.parse("deepl",new JSONObject("{\"translations\":[{\"text\":\"Hallo\"}]}"),1).result.get(0).text);
        assertEquals("Bonjour",MonogramTranslation.parse("google",new JSONObject("{\"data\":{\"translations\":[{\"translatedText\":\"Bonjour\"}]}}"),1).result.get(0).text);
    }
    @Test public void malformedAndMisalignedResponsesAreRejectedRatherThanAssignedToAnotherMessage() throws Exception {
        for(String bad:new String[]{"{\"translatedText\":[null]}","{\"translatedText\":[123]}","{\"translatedText\":[]}","{\"translatedText\":[\"one\",\"two\"]}"})
            assertThrows(Exception.class,() -> MonogramTranslation.parse("libretranslate",new JSONObject(bad),1));
        assertThrows(Exception.class,() -> MonogramTranslation.parse("google",new JSONObject("{\"data\":{\"translations\":[{\"translatedText\":42}]}}"),1));
    }
    TLRPC.TL_messages_translateText request(String text) {
        TLRPC.TL_messages_translateText request=new TLRPC.TL_messages_translateText();request.flags=2;request.to_lang="pl";
        TLRPC.TL_textWithEntities item=new TLRPC.TL_textWithEntities();item.text=text;request.text.add(item);return request;
    }
    Runnable nextCallback() throws Exception {
        Runnable callback=Ui.callbacks.poll(5,TimeUnit.SECONDS);assertNotNull("translation must finish",callback);return callback;
    }
    @Test public void unconfiguredTranslationDoesNotTransmitAndCancellationSuppressesQueuedResult() throws Exception {
        ArrayList<TLRPC.TL_error> errors=new ArrayList<>();
        MonogramTranslation.request(0,request("Hello"),(result,error) -> { assertNull(result);errors.add(error); });
        nextCallback().run();assertEquals("TRANSLATION_NOT_CONFIGURED",errors.get(0).text);
        Runnable cancel=MonogramTranslation.request(0,request("Hello"),(result,error) -> fail("Cancelled callback delivered"));
        Runnable callback=nextCallback();cancel.run();callback.run();
    }
    @Test public void attachmentKeysAndOversizedInputsAreRejectedBeforeProviderWork() throws Exception {
        for(String text:new String[]{"File AESGCM://host/file#secret",String.join("",Collections.nCopies(25001,"a"))}) {
            MonogramTranslation.request(0,request(text),(result,error) -> { assertNull(result);assertEquals("TRANSLATION_TEXT_INVALID",error.text); });nextCallback().run();
        }
    }
}
