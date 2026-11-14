// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.messenger.xmpp;

import android.app.KeyguardManager;
import android.content.*;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.AtomicFile;
import android.util.Base64;
import com.negzero.monogram.sync.NativeMesh;
import com.negzero.monogram.wallet.*;
import com.negzero.monogram.xmpp.MoneroPayment;
import org.json.*;
import org.telegram.messenger.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/** Platform stores stay authoritative. Native TLS, discovery and encrypted records are shared with desktop. */
public final class MonogramLanSync {
    private static final ScheduledExecutorService WORK=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"monogram-device-sync");t.setDaemon(true);return t;});
    private static final ScheduledExecutorService PAIR=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"monogram-device-pairing");t.setDaemon(true);return t;});
    private static final Object START_LOCK=new Object();
    private static volatile JSONObject numeric=new JSONObject();
    private static volatile JSONArray nearby=new JSONArray();
    public static JSONObject numericStatus(){return numeric;}
    public static JSONArray nearby(){return nearby;}
    private static volatile long handle;
    private static volatile String status="Device sync is off";
    private static volatile JSONObject snapshot=new JSONObject();
    private static volatile boolean registered,screenLocked;
    private static WifiManager.MulticastLock multicast;
    private static final Set<String> EXCLUDED=ConcurrentHashMap.newKeySet();
    private MonogramLanSync(){}
    private static Context context(){return ApplicationLoader.applicationContext;}
    private static SharedPreferences prefs(){return context().getSharedPreferences("monogram-device-sync",Context.MODE_PRIVATE);}
    public static boolean enabled(){return prefs().getBoolean("enabled",false);}
    public static String status(){return status;}
    public static JSONObject snapshot(){return snapshot;}
    public static boolean needsUnlock(){return status.contains("Unlock")||status.contains("screen lock");}
    private static void feedback(String message){status=message;}
    public static void install(){
        if(registered)return;registered=true;
        EXCLUDED.addAll(prefs().getStringSet("excluded",Collections.emptySet()));
        BroadcastReceiver lock=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){if(Intent.ACTION_SCREEN_OFF.equals(i.getAction())){screenLocked=true;stopNow();feedback("Unlock your phone to resume sync");}else{screenLocked=false;if(enabled())resume();}}};
        IntentFilter filter=new IntentFilter(Intent.ACTION_SCREEN_OFF);filter.addAction(Intent.ACTION_USER_PRESENT);
        if(Build.VERSION.SDK_INT>=33)context().registerReceiver(lock,filter,Context.RECEIVER_NOT_EXPORTED);else context().registerReceiver(lock,filter);
        WORK.scheduleWithFixedDelay(()->{long h=handle;if(h==0)return;try{cycle(h);if(h==handle){snapshot=new JSONObject(NativeMesh.inspect(h,"state"));feedback(snapshot.optString("status","Ready"));}}catch(Exception e){if(h==handle)feedback(message(e));}},1,5,TimeUnit.SECONDS);
        PAIR.scheduleWithFixedDelay(()->{long h=handle;if(h==0)return;try{nearby=new JSONArray(NativeMesh.inspect(h,"nearby"));try{numeric=new JSONObject(NativeMesh.inspect(h,"numeric"));}catch(RuntimeException ended){numeric=new JSONObject();}}catch(Exception ignored){}},0,1,TimeUnit.SECONDS);
        if(enabled())resume();
    }
    public static void enable(boolean value){prefs().edit().putBoolean("enabled",value).apply();if(value){feedback("Starting device sync…");resume();}else{stopNow();context().stopService(new Intent(context(),MonogramLanSyncService.class));feedback("Device sync is off");}}
    public static void resume(){
        if(!enabled()||screenLocked)return;feedback("Starting device sync…");
        try{Intent service=new Intent(context(),MonogramLanSyncService.class);if(Build.VERSION.SDK_INT>=26)context().startForegroundService(service);else context().startService(service);}catch(RuntimeException e){feedback("Open Device Sync to resume");}
        WORK.execute(()->{try{start();}catch(Exception e){feedback(message(e));}});
    }
    public static synchronized void stopNow(){long old=handle;handle=0;if(old!=0)NativeMesh.stop(old);if(multicast!=null){if(multicast.isHeld())multicast.release();multicast=null;}}
    private static void start()throws Exception {synchronized(START_LOCK){startInternal();}}
    private static void startInternal()throws Exception {
        if(handle!=0||!enabled()||screenLocked)return;
        KeyguardManager guard=(KeyguardManager)context().getSystemService(Context.KEYGUARD_SERVICE);
        if(guard==null||!guard.isDeviceSecure())throw new IllegalStateException("Set a device screen lock before enabling sync.");
        if(guard.isDeviceLocked())throw new IllegalStateException("Unlock your phone to resume sync");
        File root=new File(context().getNoBackupFilesDir(),"device-sync");if(!root.isDirectory()&&!root.mkdirs())throw new IOException("Cannot create private sync storage.");
        WalletVault vault=new WalletVault(context(),"device-sync","mesh");AtomicFile master=new AtomicFile(new File(root,"master.json"));byte[] key=null;char[] encoded=null;
        try{
            if(master.getBaseFile().exists()){
                try(InputStream input=master.openRead()){encoded=vault.unlock(new JSONObject(new String(MonogramPackArchive.read(input,8192,()->{}),StandardCharsets.UTF_8)));}
            }else{
                try(WalletVault.Authorization authorization=vault.authorizeRestore()){
                    FileOutputStream output=master.startWrite();try{output.write(authorization.sealed.toString().getBytes(StandardCharsets.UTF_8));master.finishWrite(output);}catch(Exception e){master.failWrite(output);throw e;}
                    encoded=authorization.password.clone();
                }
            }
            key=Base64.decode(new String(encoded),Base64.NO_WRAP);JSONArray ips=new JSONArray();Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();
            while(interfaces!=null&&interfaces.hasMoreElements()){NetworkInterface i=interfaces.nextElement();if(!i.isUp()||i.isLoopback())continue;Enumeration<InetAddress> addresses=i.getInetAddresses();while(addresses.hasMoreElements()){InetAddress ip=addresses.nextElement();if(ip instanceof Inet4Address&&(ip.isSiteLocalAddress()||ip.isLinkLocalAddress()))ips.put(ip.getHostAddress());}}
            long h=NativeMesh.start(new File(root,"vault").getAbsolutePath(),key,Build.MODEL,new JSONObject().put("ips",ips).put("excluded",new JSONArray(new ArrayList<>(EXCLUDED))).toString());
            synchronized(MonogramLanSync.class){if(!enabled()||screenLocked){NativeMesh.stop(h);return;}handle=h;}
            for(String owner:EXCLUDED)NativeMesh.account(h,owner,false);
            WifiManager wifi=(WifiManager)context().getSystemService(Context.WIFI_SERVICE);if(wifi!=null){synchronized(MonogramLanSync.class){if(handle==h){multicast=wifi.createMulticastLock("Monogram device sync");multicast.setReferenceCounted(false);multicast.acquire();}}}
            feedback("Ready to pair");
        }finally{if(key!=null)Arrays.fill(key,(byte)0);if(encoded!=null)Arrays.fill(encoded,'\0');}
    }
    private static String message(Exception failure){Throwable e=failure;while(e.getCause()!=null)e=e.getCause();if(e instanceof WalletVault.AuthenticationRequired||e instanceof android.security.keystore.UserNotAuthenticatedException)return "Unlock with your device credential to resume sync";return e instanceof IllegalStateException||e instanceof IllegalArgumentException||e instanceof IOException?e.getMessage():"Could not sync. Unlock your phone and try again.";}
    public static void action(String action,String value,BiConsumer<String,String> done){feedback("Working…");PAIR.execute(()->{String result=null,error=null;try{start();long h=handle;if(h==0)throw new IllegalStateException("Enable device sync first.");switch(action){case "numeric":result=NativeMesh.numeric(h);numeric=new JSONObject(result);break;case "joinNumeric":JSONObject pair=new JSONObject(value);result=NativeMesh.joinNumeric(h,pair.getString("id"),pair.getString("code"));break;case "invite":result=NativeMesh.invite(h);break;case "join":result=NativeMesh.join(h,value);break;case "forget":NativeMesh.forget(h,value);result="Device unpaired";break;default:throw new IllegalArgumentException("Unknown sync action.");}if(h!=handle)throw new IllegalStateException("Sync stopped.");}catch(Exception e){error=message(e);}catch(LinkageError e){error="Device sync could not load. Reinstall the latest Monogram update for this phone.";}final String r=result,f=error;if(f!=null)feedback(f);AndroidUtilities.runOnUIThread(()->done.accept(r,f));});}
    public static void cancelInvite(){long h=handle;if(h!=0)try{NativeMesh.cancelInvite(h);}catch(RuntimeException ignored){}}
    public static boolean excluded(String owner){return EXCLUDED.contains(owner);}
    public static void allow(String owner){if(EXCLUDED.remove(owner))prefs().edit().putStringSet("excluded",new HashSet<>(EXCLUDED)).apply();}
    public static void exclude(String owner){if(owner==null||owner.isEmpty())return;EXCLUDED.add(owner);prefs().edit().putStringSet("excluded",new HashSet<>(EXCLUDED)).apply();stopNow();if(enabled())resume();}
    static String digest(String text)throws Exception {StringBuilder out=new StringBuilder();for(byte b:java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)))out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    private static String key(String kind,String owner,String id){return new JSONArray().put(kind).put(owner).put(id).toString();}
    private static void validHandle(long h){if(h!=handle||!enabled()||screenLocked)throw new IllegalStateException("Sync stopped.");}
    private static boolean publish(long h,String kind,String owner,String id,String signature)throws Exception {
        validHandle(h);if(EXCLUDED.contains(owner))return false;String key=key(kind,owner,id),old=NativeMesh.signature(h,key,"");if(old.equals(signature))return false;
        if(old.isEmpty()){JSONArray pending=new JSONArray(NativeMesh.inspect(h,"pending"));for(int i=0;i<pending.length();i++){JSONObject m=pending.getJSONObject(i);if(key.equals(key(m.getString("kind"),m.getString("owner"),m.getString("id"))))return false;}}return true;
    }
    private static void json(long h,String kind,String owner,String id,JSONObject value)throws Exception {String text=value.toString(),sig=digest(text);if(publish(h,kind,owner,id,sig)){NativeMesh.putJson(h,kind,owner,id,text);NativeMesh.signature(h,key(kind,owner,id),sig);}}
    private static int account(String owner,boolean create){int empty=-1;for(int i=0;i<UserConfig.MAX_ACCOUNT_COUNT;i++){String jid=MonogramController.get(i).accountJid();if(jid.equals(owner))return i;if(empty<0&&jid.isEmpty()&&!UserConfig.getInstance(i).isClientActivated())empty=i;}return create?empty:-1;}
    private static void cycle(long h)throws Exception {
        for(int a=0;a<UserConfig.MAX_ACCOUNT_COUNT;a++){
            validHandle(h);MonogramController controller=MonogramController.get(a);String owner=controller.accountJid();if(owner.isEmpty())continue;
            if(EXCLUDED.contains(owner))continue;NativeMesh.account(h,owner,true);
            json(h,"account",owner,"login",controller.syncAccount());
            for(String provider:new String[]{"giphy","telegram-packs","translation","monero-daemon"}){
                String secret=token(a,provider);if(!secret.isEmpty()||!NativeMesh.signature(h,key("token",owner,provider),"").isEmpty())json(h,"token",owner,provider,new JSONObject().put("value",secret));
            }
            json(h,"monero-settings",owner,"settings",MonogramXmr.syncSettings(a));
            for(MonogramPacks.Pack p:MonogramPacks.packs(a)){
                if(p.items.isEmpty()||p.source!=null&&p.source.startsWith("local:replaced:"))continue;
                MonogramPacks.Pack pack=MonogramPacks.syncTag(a,p.id);String sig=MonogramPacks.syncSignature(pack);
                if(publish(h,"pack",owner,pack.syncId,sig)){feedback("Preparing sticker and emoji packs…");File archive=MonogramPacks.exportSyncArchive(a,pack);try{validHandle(h);NativeMesh.put(h,"pack",owner,pack.syncId,archive.getAbsolutePath());NativeMesh.signature(h,key("pack",owner,pack.syncId),sig);}finally{archive.delete();}}
            }
            JSONObject folders=MonogramSyncFolders.export(a);if(folders!=null)json(h,"folders",owner,"folders",folders);
            controller.syncFingerprints(value->{try{String id=digest(value.getString("mode")+":"+value.getInt("device")+":"+value.getString("fingerprint"));json(h,"fingerprint",owner,id,value);}catch(Exception e){throw new IllegalStateException(e);}});
            for(MoneroPayment.Network network:MoneroPayment.Network.values())for(String id:WalletRegistry.ids(a,owner,network)){
                MonogramWallet wallet=MonogramWallet.getWallet(a,network,id);JSONObject meta=wallet.syncMetadata();if(meta==null)continue;String address=meta.getString("address"),sig=digest(meta.toString());
                if(publish(h,"wallet",owner,address,sig)){feedback("Securing wallet recovery data…");JSONObject value=wallet.exportSync();if(value!=null){validHandle(h);NativeMesh.putJson(h,"wallet",owner,address,value.toString());NativeMesh.signature(h,key("wallet",owner,address),sig);}}
            }
        }
        JSONArray pending=new JSONArray(NativeMesh.inspect(h,"pending"));for(int i=0;i<pending.length();i++){
            validHandle(h);JSONObject meta=pending.getJSONObject(i);String owner=meta.getString("owner");if(EXCLUDED.contains(owner))continue;
            String kind=meta.getString("kind"),id=meta.getString("id"),key=key(kind,owner,id);int a=account(owner,kind.equals("account"));if(a<0)throw new IllegalStateException("Sign in to the synchronized account, or free an account slot.");
            File path=new File(NativeMesh.read(h,key));String sig;
            try{
                if(kind.equals("pack")){sig=MonogramPacks.syncSignature(MonogramPacks.importSyncArchive(a,path,id));}
                else{
                    if(path.length()>8*1024*1024)throw new IOException("Invalid synchronized setting size.");JSONObject value;try(InputStream input=new FileInputStream(path)){value=new JSONObject(new String(MonogramPackArchive.read(input,8*1024*1024,()->{}),StandardCharsets.UTF_8));}
                    NativeMesh.validate(kind,owner,id,value.toString());validHandle(h);
                    switch(kind){
                        case "account":MonogramController.get(a).importSyncAccount(value);break;
                        case "token":saveToken(a,id,value.getString("value"));break;
                        case "monero-settings":MonogramXmr.importSyncSettings(a,value);break;
                        case "folders":MonogramSyncFolders.apply(a,value);break;
                        case "fingerprint":prefs().edit().putString("fingerprint:"+owner+":"+id,value.toString()).commit();break;
                        case "wallet":importWallet(a,owner,value);break;
                        default:throw new IOException("Unsupported sync setting.");
                    }
                    if(kind.equals("wallet")){value.remove("keys");value.remove("password");}sig=digest(value.toString());
                }
                if(EXCLUDED.contains(owner))continue;validHandle(h);NativeMesh.applied(h,key,meta.getString("digest"));NativeMesh.signature(h,key,sig);
            }finally{path.delete();}
        }
    }
    private static String token(int a,String provider)throws Exception {
        if(provider.equals("giphy"))return MonogramGiphy.syncKey(a);if(provider.equals("telegram-packs"))return MonogramPacks.syncToken(a);
        String sealed=MessagesController.getMainSettings(a).getString(provider.equals("translation")?MonogramTranslation.PREF_API_KEY:"monogram-sync-monero-daemon","");return sealed.isEmpty()?"":new AccountVault(a).decrypt(sealed);
    }
    private static void saveToken(int a,String provider,String value)throws Exception {
        if(provider.equals("giphy")){MonogramGiphy.saveKey(a,value);return;}if(provider.equals("telegram-packs")){MonogramPacks.saveToken(a,value);return;}
        String field=provider.equals("translation")?MonogramTranslation.PREF_API_KEY:"monogram-sync-monero-daemon";SharedPreferences.Editor edit=MessagesController.getMainSettings(a).edit();if(value.isEmpty())edit.remove(field);else edit.putString(field,new AccountVault(a).encrypt(value));if(!edit.commit())throw new IOException("Could not save synchronized credential.");
    }
    private static void importWallet(int a,String owner,JSONObject value)throws Exception {
        MoneroPayment.Network network=MoneroPayment.Network.valueOf(value.getString("network"));MonogramWallet target=null;
        for(String id:WalletRegistry.ids(a,owner,network)){MonogramWallet wallet=MonogramWallet.getWallet(a,network,id);if(wallet.primary().equals(value.getString("address"))){target=wallet;break;}if(target==null&&!wallet.exists())target=wallet;}
        if(target==null){String id=WalletRegistry.add(a,owner,network,value.getString("name"));target=MonogramWallet.getWallet(a,network,id);}
        target.importSync(value);
    }
    public static List<JSONObject> fingerprints(){ArrayList<JSONObject> result=new ArrayList<>();for(Map.Entry<String,?> item:prefs().getAll().entrySet())if(item.getKey().startsWith("fingerprint:"))try{result.add(new JSONObject((String)item.getValue()));}catch(Exception ignored){}return result;}
}
