// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLRPC;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;

/** Opt-in translation using the provider's official JSON API. */
public final class MonogramTranslation {
    public static final String PREF_PROVIDER="monogram_translation_provider";
    public static final String PREF_ENDPOINT="monogram_translation_endpoint";
    public static final String PREF_API_KEY="monogram_translation_api_key";
    public static final String GOOGLE_ENDPOINT="https://translation.googleapis.com/language/translate/v2";
    public static final String DEEPL_FREE_ENDPOINT="https://api-free.deepl.com/v2/translate";
    public static final String DEEPL_PRO_ENDPOINT="https://api.deepl.com/v2/translate";
    private static final int MAX_REQUEST=120*1024,MAX_RESPONSE=1024*1024,MAX_TEXT=25000,MAX_ITEMS=50;
    private static final ThreadPoolExecutor WORKER=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32));

    private static SharedPreferences preferences(int account) { return MessagesController.getMainSettings(account); }
    public static String provider(int account) { return preferences(account).getString(PREF_PROVIDER,"libretranslate"); }
    public static String endpoint(int account) {
        String provider=provider(account);
        if("google".equals(provider)) return GOOGLE_ENDPOINT;
        return preferences(account).getString(PREF_ENDPOINT,"deepl".equals(provider) ? DEEPL_FREE_ENDPOINT : "");
    }
    public static boolean isConfigured(int account) {
        try {
            String provider=provider(account);validateEndpoint(provider,endpoint(account));
            return "libretranslate".equals(provider) || !preferences(account).getString(PREF_API_KEY,"").isEmpty();
        } catch(Exception invalid) { return false; }
    }
    public static void clearConfiguration(int account) throws java.io.IOException {
        if(!preferences(account).edit().remove(PREF_PROVIDER).remove(PREF_ENDPOINT).remove(PREF_API_KEY).commit())
            throw new java.io.IOException("Could not clear translation settings.");
    }
    public static void saveConfiguration(int account,String provider,String endpoint,String key) throws Exception {
        String address=validateEndpoint(provider,endpoint);
        SharedPreferences prefs=preferences(account);
        boolean same=provider.equals(provider(account)) && address.equals(endpoint(account));
        String saved=key==null && same ? prefs.getString(PREF_API_KEY,"") : "";
        if(key!=null && !key.trim().isEmpty()) {
            String clean=key.trim();
            if(clean.length()>2048 || clean.matches("(?s).*[\\p{Cntrl}].*")) throw new IllegalArgumentException("Enter a valid translation API key.");
            saved=new AccountVault(account).encrypt(clean);
        }
        if(!"libretranslate".equals(provider) && saved.isEmpty()) throw new IllegalArgumentException("This translation provider requires an API key.");
        if(!prefs.edit().putString(PREF_PROVIDER,provider).putString(PREF_ENDPOINT,address).putString(PREF_API_KEY,saved).commit())
            throw new java.io.IOException("Could not save translation settings.");
    }
    static String validateEndpoint(String provider,String endpoint) throws Exception {
        if(!"libretranslate".equals(provider) && !"google".equals(provider) && !"deepl".equals(provider))
            throw new IllegalArgumentException("Choose a translation provider.");
        if("google".equals(provider)) return GOOGLE_ENDPOINT;
        String value=endpoint==null ? "" : endpoint.trim();
        if("deepl".equals(provider)) {
            if(value.isEmpty()) return DEEPL_FREE_ENDPOINT;
            if(!DEEPL_FREE_ENDPOINT.equals(value) && !DEEPL_PRO_ENDPOINT.equals(value)) throw new IllegalArgumentException("Choose the DeepL API Free or Pro endpoint.");
            return value;
        }
        URI uri=new URI(value);
        if(value.length()>2048 || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null)
            throw new IllegalArgumentException("Enter an HTTPS LibreTranslate URL without credentials, query or fragment.");
        while(value.endsWith("/")) value=value.substring(0,value.length()-1);
        return value.endsWith("/translate") ? value : value+"/translate";
    }
    static String language(String target) {
        if(target==null || !target.matches("[A-Za-z]{2,3}(?:-[A-Za-z]{2,8})?") || "auto".equalsIgnoreCase(target))
            throw new IllegalArgumentException("TO_LANG_INVALID");
        String value=target.toLowerCase(Locale.ROOT);
        if("iw".equals(value)) value="he";
        if("in".equals(value)) value="id";
        if("zh-hans".equals(value)) value="zh-CN";
        if("zh-hant".equals(value)) value="zh-TW";
        return value;
    }
    static JSONObject body(String provider,ArrayList<String> texts,String target,String key) throws Exception {
        String lang=language(target);JSONArray items=new JSONArray(texts);
        JSONObject body=new JSONObject();
        if("deepl".equals(provider)) {
            if("zh-CN".equals(lang)) lang="zh-HANS";
            if("zh-TW".equals(lang)) lang="zh-HANT";
            body.put("text",items).put("target_lang",lang.toUpperCase(Locale.ROOT));
        } else {
            body.put("q",items).put("target",lang).put("format","text");
            if("libretranslate".equals(provider)) {
                body.put("source","auto");
                if(!key.isEmpty()) body.put("api_key",key);
            }
        }
        return body;
    }
    static TLRPC.TL_messages_translateResult parse(String provider,JSONObject response,int expected) throws Exception {
        JSONArray translated;
        if("google".equals(provider)) translated=response.getJSONObject("data").getJSONArray("translations");
        else if("deepl".equals(provider)) translated=response.getJSONArray("translations");
        else {
            Object value=response.get("translatedText");
            translated=value instanceof JSONArray ? (JSONArray)value : new JSONArray().put(value);
        }
        if(expected<1 || translated.length()!=expected) throw new java.io.IOException("Invalid translation response");
        TLRPC.TL_messages_translateResult result=new TLRPC.TL_messages_translateResult();
        for(int i=0;i<translated.length();i++) {
            Object value="libretranslate".equals(provider) ? translated.get(i) : translated.getJSONObject(i).get("google".equals(provider) ? "translatedText" : "text");
            if(!(value instanceof String) || ((String)value).length()>MAX_RESPONSE) throw new java.io.IOException("Invalid translation text");
            TLRPC.TL_textWithEntities text=new TLRPC.TL_textWithEntities();text.text=(String)value;
            // Source entity offsets cannot be reused after the text changes language.
            result.result.add(text);
        }
        return result;
    }
    private static final class Request implements Runnable {
        volatile boolean cancelled;
        volatile HttpsURLConnection connection;
        @Override public void run() { cancelled=true;HttpsURLConnection active=connection;if(active!=null) active.disconnect(); }
    }
    public static Runnable request(int account,TLRPC.TL_messages_translateText request,RequestDelegate callback) {
        Request task=new Request();
        ArrayList<String> texts=new ArrayList<>();
        int symbols=0;
        if(request!=null && request.text!=null && request.text.size()<=MAX_ITEMS) for(TLRPC.TL_textWithEntities text:request.text) {
            if(text==null || text.text==null || text.text.toLowerCase(Locale.ROOT).contains("aesgcm://")) { texts.clear();break; }
            texts.add(text.text);symbols+=text.text.length();
            if(symbols>MAX_TEXT) break;
        }
        if(request==null || texts.isEmpty() || symbols>MAX_TEXT || request.text.size()>MAX_ITEMS || !request.id.isEmpty()) {
            complete(task,callback,null,"TRANSLATION_TEXT_INVALID");return task;
        }
        String target=request.to_lang;
        String provider=provider(account),endpoint=endpoint(account),saved=preferences(account).getString(PREF_API_KEY,"");
        try { WORKER.execute(() -> {
            TLRPC.TL_messages_translateResult result=null;String error=null;
            try {
                if(task.cancelled) return;
                String address=validateEndpoint(provider,endpoint);
                if(!"libretranslate".equals(provider) && saved.isEmpty()) throw new IllegalArgumentException("TRANSLATION_NOT_CONFIGURED");
                String key=saved.isEmpty() ? "" : new AccountVault(account).decrypt(saved);
                byte[] payload=body(provider,texts,target,key).toString().getBytes(StandardCharsets.UTF_8);
                if(payload.length>MAX_REQUEST) throw new IllegalArgumentException("TRANSLATION_TEXT_INVALID");
                if(!provider.equals(provider(account)) || !endpoint.equals(endpoint(account)) || !saved.equals(preferences(account).getString(PREF_API_KEY,"")))
                    throw new IllegalArgumentException("TRANSLATION_NOT_CONFIGURED");
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
                HttpsURLConnection connection=(HttpsURLConnection)new URL(address).openConnection();task.connection=connection;
                connection.setConnectTimeout(10000);connection.setReadTimeout(20000);connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setFixedLengthStreamingMode(payload.length);
                connection.setRequestProperty("Content-Type","application/json; charset=utf-8");connection.setRequestProperty("Accept","application/json");
                if("google".equals(provider)) connection.setRequestProperty("X-goog-api-key",key);
                if("deepl".equals(provider)) connection.setRequestProperty("Authorization","DeepL-Auth-Key "+key);
                if(task.cancelled) return;
                try(OutputStream output=connection.getOutputStream()) { output.write(payload); }
                int status=connection.getResponseCode();
                if(status!=200) {
                    error=status==429 || status==456 ? "FLOOD_WAIT_60" : status==401 || status==403 ? "TRANSLATION_AUTH_FAILED" : status==400 ? "TO_LANG_INVALID" : "TRANSLATION_FAILED";
                } else {
                    try(InputStream input=connection.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
                        byte[] buffer=new byte[8192];int count;
                        while((count=input.read(buffer))!=-1) {
                            if(task.cancelled) return;
                            if(System.nanoTime()>deadline) throw new java.io.IOException("Translation response timed out");
                            if(bytes.size()+count>MAX_RESPONSE) throw new java.io.IOException("Translation response too large");
                            bytes.write(buffer,0,count);
                        }
                        result=parse(provider,new JSONObject(bytes.toString("UTF-8")),texts.size());
                    }
                }
            } catch(IllegalArgumentException invalid) {
                String reason=invalid.getMessage();
                error="TO_LANG_INVALID".equals(reason) || "TRANSLATION_TEXT_INVALID".equals(reason) || "TRANSLATION_NOT_CONFIGURED".equals(reason) ? reason : "TRANSLATION_NOT_CONFIGURED";
            } catch(Exception failed) {
                // Provider errors can contain plaintext or API credentials; never log them.
                error="TRANSLATION_FAILED";
            } finally { if(task.connection!=null) task.connection.disconnect(); }
            complete(task,callback,result,error);
        }); } catch(java.util.concurrent.RejectedExecutionException busy) { complete(task,callback,null,"FLOOD_WAIT_5"); }
        return task;
    }
    private static void complete(Request task,RequestDelegate callback,TLRPC.TL_messages_translateResult result,String failure) {
        TLRPC.TL_error error=null;
        if(failure!=null) { error=new TLRPC.TL_error();error.code=400;error.text=failure; }
        TLRPC.TL_error finalError=error;
        AndroidUtilities.runOnUIThread(() -> { if(!task.cancelled) callback.run(result,finalError); });
    }
}
