// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.content.*;
import android.net.Uri;
import java.io.File;
import java.util.*;
import java.util.function.Consumer;
import org.json.*;
import org.telegram.messenger.*;

/** Owner-bound Android provider/camera launches. Documents send immediately to their original conversation; other results can become draft selections. */
public final class MonogramExternalSelections {
    private MonogramExternalSelections() {}
    private static android.content.SharedPreferences preferences() { return ApplicationLoader.applicationContext.getSharedPreferences("monogram-external-selections",Context.MODE_PRIVATE); }
    public static String launch(int account,long dialog,int request,String cameraPath) {
        try {
            MonogramMediaDrafts.Owner owner=MonogramMediaDrafts.Owner.current(account,dialog);if(owner==null) return null;String token=UUID.randomUUID().toString();
            JSONObject state=new JSONObject().put("account",account).put("dialog",dialog).put("jid",owner.jid).put("peer",owner.peer).put("epoch",owner.epoch).put("request",request).put("output",cameraPath);
            return preferences().edit().putString(token,state.toString()).commit() ? token : null;
        } catch(Exception unavailable) { return null; }
    }
    private static JSONObject state(String token) throws Exception { if(token==null || token.isEmpty()) return null;String value=preferences().getString(token,null);return value==null ? null : new JSONObject(value); }
    public static MonogramMediaDrafts.Owner owner(String token,int request) {
        try {
            JSONObject state=state(token);if(state==null || state.getInt("request")!=request) return null;
            MonogramMediaDrafts.Owner current=MonogramMediaDrafts.Owner.current(state.getInt("account"),state.getLong("dialog"));
            return current!=null && current.jid.equals(state.getString("jid")) && current.peer.equals(state.getString("peer")) && current.epoch.equals(state.getString("epoch")) ? current : null;
        } catch(Exception unavailable) { return null; }
    }
    public static boolean sameOwner(MonogramMediaDrafts.Owner expected,int account,long dialog) {
        MonogramMediaDrafts.Owner current=MonogramMediaDrafts.Owner.current(account,dialog);return expected!=null && current!=null && expected.key.equals(current.key) && expected.epoch.equals(current.epoch);
    }
    public static boolean matches(String token,int request,int account,long dialog) {
        MonogramMediaDrafts.Owner expected=owner(token,request),current=MonogramMediaDrafts.Owner.current(account,dialog);
        return expected!=null && current!=null && expected.key.equals(current.key) && expected.epoch.equals(current.epoch);
    }
    /** Consume only a verified document return; mismatched results remain recoverable. */
    public static synchronized boolean consumeDocumentResult(String token,int request,int account,long dialog) {
        if(request!=21 || account!=UserConfig.selectedAccount || !matches(token,request,account,dialog)) return false;
        return preferences().edit().remove(token).commit();
    }
    public static ArrayList<Uri> documentUris(Intent data) {
        LinkedHashSet<Uri> uris=new LinkedHashSet<>();
        if(data!=null) {
            if(data.getData()!=null) uris.add(data.getData());
            ClipData clip=data.getClipData();
            if(clip!=null) for(int i=0;i<clip.getItemCount();i++) if(clip.getItemAt(i).getUri()!=null) uris.add(clip.getItemAt(i).getUri());
        }
        return new ArrayList<>(uris);
    }
    public static String cameraPath(String token,int request) {
        try { JSONObject saved=state(token);return saved!=null && saved.getInt("request")==request ? saved.optString("output",null) : null; } catch(Exception unavailable) { return null; }
    }
    public static void logout(int account,String jid) {
        try {
            android.content.SharedPreferences.Editor editor=preferences().edit();
            for(Map.Entry<String,?> entry:preferences().getAll().entrySet()) try { JSONObject state=new JSONObject(String.valueOf(entry.getValue()));if(state.optInt("account",-1)==account && jid.equals(state.optString("jid"))) editor.remove(entry.getKey()); } catch(Exception ignored) {}
            editor.commit();
        } catch(Exception unavailable) { FileLog.e("External selection logout metadata cleanup failed"); }
    }
    public static void cancel(String token) { if(token!=null && !token.isEmpty()) preferences().edit().remove(token).commit(); }
    public static boolean stage(String token,int request,Intent data,Consumer<String> errors,Runnable ready) {
        MonogramMediaDrafts.Owner owner=owner(token,request);if(owner==null) { errors.accept("Could not verify the external selection’s account and conversation. Select the files again.");return false; }
        try {
            JSONObject saved=state(token);LinkedHashSet<String> paths=new LinkedHashSet<>();String output=saved.optString("output");
            if((request==0 || request==2) && !output.isEmpty() && new File(output).isFile() && new File(output).length()>0) paths.add(output);
            if(paths.isEmpty() && data!=null) {
                if(data.getData()!=null) add(paths,data.getData());
                ClipData clip=data.getClipData();if(clip!=null) for(int i=0;i<clip.getItemCount();i++) if(clip.getItemAt(i).getUri()!=null) add(paths,clip.getItemAt(i).getUri());
            }
            if(paths.isEmpty()) { errors.accept("No readable media was returned. The external selection remains available for recovery; select the files again.");return false; }
            JSONArray persisted=new JSONArray(paths);saved.put("returned",persisted);
            // Retain the result before asynchronous private copies; camera output and URI ownership survive fragment recreation.
            if(!preferences().edit().putString(token,saved.toString()).commit()) { errors.accept("Could not preserve the external selection. Select the files again.");return false; }
            ArrayList<MediaController.PhotoEntry> inputs=new ArrayList<>();int id=-1000000;
            for(String path:paths) {
                if(path.startsWith("content:")) try { ApplicationLoader.applicationContext.getContentResolver().takePersistableUriPermission(Uri.parse(path),Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch(SecurityException unsupported) { /* Private snapshot fallback handles temporary grants. */ }
                MediaController.PhotoEntry photo=new MediaController.PhotoEntry(0,id--,0,path,0,0,request==2,0,0,new File(path).length());photo.canDeleteAfter=true;inputs.add(photo);
            }
            boolean captured=MonogramMediaDrafts.recordExternal(owner,inputs,request==21 ? "document" : "photo",errors,ready);
            if(captured) cancel(token);return captured;
        } catch(Exception unavailable) { errors.accept("Could not preserve the external selection. Keep the original files available and select them again.");return false; }
    }
    /** A conflicting return stays owner-bound until its existing composition is finished or discarded. */
    public static boolean recoverWhenEmpty(int account,long dialog,Consumer<String> errors,Runnable ready) {
        MonogramMediaDrafts.Owner current=MonogramMediaDrafts.Owner.current(account,dialog);if(current==null || MonogramMediaDrafts.hasComposition(current)) return false;
        for(Map.Entry<String,?> entry:preferences().getAll().entrySet()) try {
            JSONObject pending=new JSONObject(String.valueOf(entry.getValue()));if(!pending.has("returned")) continue;int request=pending.getInt("request");
            if(!matches(entry.getKey(),request,account,dialog)) continue;
            JSONArray paths=pending.getJSONArray("returned");Intent data=new Intent();ClipData clip=null;
            for(int i=0;i<paths.length();i++) { String path=paths.getString(i);Uri uri=path.startsWith("content:") ? Uri.parse(path) : Uri.fromFile(new File(path));if(clip==null) clip=ClipData.newRawUri("Recovered selection",uri);else clip.addItem(new ClipData.Item(uri)); }
            if(clip!=null) data.setClipData(clip);
            return stage(entry.getKey(),request,data,errors,ready);
        } catch(Exception uncertain) { /* Keep unreadable metadata; never replace another composition. */ }
        return false;
    }
    private static void add(Set<String> paths,Uri uri) throws Exception {
        if("content".equalsIgnoreCase(uri.getScheme())) {
            if((ApplicationLoader.getApplicationId()+".provider").equalsIgnoreCase(uri.getAuthority())) throw new SecurityException("Internal provider result");paths.add(uri.normalizeScheme().toString());
        } else if("file".equalsIgnoreCase(uri.getScheme())) {
            File source=new File(uri.getPath()).getCanonicalFile();String data=ApplicationLoader.applicationContext.getApplicationInfo().dataDir;
            File privateRoot=(data==null ? ApplicationLoader.applicationContext.getFilesDir().getParentFile() : new File(data)).getCanonicalFile();
            if(source.equals(privateRoot) || source.getPath().startsWith(privateRoot.getPath()+File.separator) || AndroidUtilities.isInternalUri(uri)) throw new SecurityException("Internal provider result");paths.add(source.getPath());
        } else throw new SecurityException("Unsupported provider result");
    }
}
