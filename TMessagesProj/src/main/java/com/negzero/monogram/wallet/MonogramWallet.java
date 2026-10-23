// SPDX-License-Identifier: GPL-3.0-or-later
package com.negzero.monogram.wallet;

import android.content.Context;
import android.util.AtomicFile;
import com.negzero.monogram.xmpp.MoneroPayment;
import com.negzero.monogram.xmpp.MoneroClaim;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.xmpp.MonogramController;
import org.telegram.messenger.xmpp.MonogramXmr;

/** Serialized local wallet; the first scan can complete in a user-visible foreground service. */
public final class MonogramWallet {
    public interface Result<T> {void done(T value,Exception error);}
    public interface Observer {void changed();}
    private static final Map<String,MonogramWallet> instances=new HashMap<>();
    private static final ExecutorService stopper=Executors.newSingleThreadExecutor();
    private final ExecutorService actor=Executors.newSingleThreadExecutor();
    private final Set<Observer> observers=new HashSet<>();
    private final int account;
    private final String owner,scope;
    public final String walletId;
    public final String claimId;
    private String expectedRestoreAddress;
    public final MoneroPayment.Network network;
    private final File root;
    private final AtomicFile metadata;
    private final WalletVault vault;
    private volatile long handle;
    private volatile WalletTlsBridge transport;
    private int leases;
    private volatile long epoch;
    public volatile boolean busy;
    private volatile boolean sessionSynced;
    private volatile boolean recentCacheReady;
    private static final long RECENT_SYNC_MS=5*60*1000L;
    public volatile boolean scanning;
    private volatile boolean scanCancelled;
    public volatile long scanHeight,scanTarget,scanOrigin;
    public int scanPercent(){long span=scanTarget-scanOrigin;return span<=0 ? 0 : (int)Math.max(0,Math.min(100,100.0*(scanHeight-scanOrigin)/span));}
    public String scanProgress(){
        if(scanTarget<=0)return scanHeight>0 ? "Scanning · block "+scanHeight : status;
        return String.format(java.util.Locale.getDefault(),"%,d blocks remaining · %d%%",Math.max(0,scanTarget-scanHeight),scanPercent());
    }
    public volatile String status="Locked",error="";
    public volatile String[] balance,transactions=new String[0];
    private volatile JSONObject config;
    private Authentication authentication;
    private final Runnable delayedLock=()->{if(leases==0)lockAutomatically();};
    public static synchronized MonogramWallet get(int account,MoneroPayment.Network network) throws Exception {
        String owner=MonogramController.get(account).accountJid();
        return getWallet(account,network,WalletRegistry.active(account,owner,network));
    }
    public static synchronized MonogramWallet getWallet(int account,MoneroPayment.Network network,String id) throws Exception {
        WalletRegistry.validate(id);String owner=MonogramController.get(account).accountJid();
        if(owner.isEmpty())throw new IllegalStateException("Sign in first.");
        String key=account+"\n"+owner+"\n"+network.name()+"\n"+id;
        MonogramWallet value=instances.get(key);
        if(value==null){value=new MonogramWallet(account,owner,network,id,null);instances.put(key,value);}return value;
    }
    public String name(){return WalletRegistry.name(account,owner,network,walletId);}
    public String selectedId(){return WalletRegistry.active(account,owner,network);}
    public List<MonogramWallet> wallets() throws Exception {
        ArrayList<MonogramWallet> result=new ArrayList<>();
        for(String id:WalletRegistry.ids(account,owner,network)){
            MonogramWallet wallet=getWallet(account,network,id);
            if(wallet.exists() || !WalletRegistry.DEFAULT.equals(id))result.add(wallet);
        }
        if(result.isEmpty())result.add(getWallet(account,network,WalletRegistry.DEFAULT));return result;
    }
    public void rename(String name)throws Exception{WalletRegistry.rename(account,owner,network,walletId,name);changed();}
    public void select(String id)throws Exception{
        check();if(busy && !scanning)throw new IllegalStateException("Finish the current wallet operation first.");
        if(!scanning)lock();WalletRegistry.select(account,owner,network,id);
    }
    public String add(String name)throws Exception{
        check();if(busy && !scanning)throw new IllegalStateException("Stop syncing or finish the current wallet operation first.");
        if(!exists()){rename(name);return walletId;}
        return WalletRegistry.add(account,owner,network,name);
    }
    public static boolean connected(int account){
        if(!MonogramXmr.enabled(account))return false;
        try(MonogramXmr settings=new MonogramXmr(account)){return get(account,settings.network()).exists();}catch(Exception unavailable){return false;}
    }
    public static synchronized MonogramWallet getClaim(int account,MoneroPayment.Network network,String id) throws Exception {
        if(id==null || !id.matches("[a-f0-9-]{36}|[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid claim identifier.");
        String owner=MonogramController.get(account).accountJid();if(owner.isEmpty())throw new IllegalStateException("Sign in first.");
        String walletId=WalletRegistry.active(account,owner,network);
        String key=account+"\n"+owner+"\n"+network.name()+"\n"+walletId+"\nclaim:"+id;
        MonogramWallet wallet=instances.get(key);if(wallet==null){wallet=new MonogramWallet(account,owner,network,walletId,id);instances.put(key,wallet);}return wallet;
    }
    public List<MonogramWallet> claims() throws Exception {
        check();if(claimId!=null)throw new IllegalStateException("Open your main wallet.");
        ArrayList<MonogramWallet> result=new ArrayList<>();File[] dirs=new File(root,"claims").listFiles();
        if(dirs!=null)for(File dir:dirs)if(dir.isDirectory() && dir.getName().matches("[a-f0-9-]{36}|[a-f0-9]{64}")){
            MonogramWallet claim=getClaim(account,network,dir.getName());if(claim.exists())result.add(claim);
        }
        result.sort((a,b)->Long.compare(b.config.optLong("created"),a.config.optLong("created")));return result;
    }
    public String claimAmount(){return config==null ? "" : config.optString("claimAmount","");}
    public String claimDestination(){return config==null ? "" : config.optString("deposit",primary());}
    public String fundingStatus(){return config==null ? "" : config.optString("fundingStatus","unfunded");}
    public String fundingHashes(){return config==null ? "" : config.optString("fundingHashes","");}
    public long fundingHeight(){
        check();if(claimId!=null || !unlocked() || !sessionScanned() || balance==null)throw new IllegalStateException("Unlock and sync your main wallet before funding a link.");
        return Math.max(0,Long.parseLong(balance[3])-20);
    }
    public void createClaim(WalletVault.Authorization authorization,String amount,long height,String node,Result<Void> callback){
        MoneroPayment.amount(amount);
        try{work("Creating payment link…",callback,()->{
            if(claimId==null || exists())throw new IllegalStateException("Create a new payment link.");
            File directory=new File(root,UUID.randomUUID().toString());long created=0;
            try{
                if(!directory.mkdirs())throw new IOException("Cannot save claim wallet.");WalletNative.load();
                created=WalletNative.createClaim(new File(directory,"wallet").getAbsolutePath(),authorization.password,nativeNetwork(network),height);
                String[] snapshot=WalletNative.snapshot(created);MoneroPayment.address(snapshot[0],network);
                String[] deposit=WalletNative.newAddress(created,0,"");MoneroPayment.address(deposit[0],network);
                JSONObject next=new JSONObject().put("version",1).put("directory",directory.getName()).put("primary",snapshot[0]).put("sealed",authorization.sealed)
                    .put("node",node).put("maxIndex",0).put("scanned",false).put("scanPolicy",2).put("scanStart",height).put("initialScanComplete",true)
                    .put("deposit",deposit[0]).put("maxIndex",Long.parseLong(deposit[1])).put("claimAmount",amount).put("created",System.currentTimeMillis()).put("fundingStatus","unfunded");
                save(next);handle=created;created=0;read();return null;
            }finally{if(created!=0)WalletNative.close(created);if(!exists())delete(directory);}
        },authorization::close);}catch(Exception error){authorization.close();throw error;}
    }
    public void restoreClaim(WalletVault.Authorization authorization,MoneroClaim link,Result<Void> callback){
        if(claimId==null || link.payment.network!=network)throw new IllegalArgumentException("Invalid claim network.");
        expectedRestoreAddress=link.payment.address;
        restore(authorization,link.seed(),new char[0],false,link.height,(unused,error)->{
            if(error!=null){callback.done(null,error);return;}
            work("Saving claim…",callback,()->{JSONObject next=new JSONObject(config.toString()).put("claimAmount",MoneroPayment.format(link.payment.atomicAmount))
                .put("created",System.currentTimeMillis()).put("fundingStatus","received").put("initialScanComplete",true);save(next);return null;});
        });
    }
    private synchronized void saveFunding(String state,String hashes) throws Exception {
        check();if(claimId==null || !exists())throw new IllegalStateException("Claim wallet missing.");
        JSONObject next=new JSONObject(config.toString()).put("fundingStatus",state).put("fundingHashes",hashes);save(next);
    }
    public void prepareClaimFunding(MonogramWallet claim,Result<Quote> callback){
        check();claim.check();if(claimId!=null || claim.claimId==null || claim.account!=account || !claim.owner.equals(owner) || claim.network!=network
            || !claim.fundingStatus().equals("unfunded"))throw new IllegalStateException("This link cannot be funded again. Inspect its payment history.");
        prepare(MoneroPayment.create(claim.claimDestination(),claim.claimAmount(),"","",network),(quote,error)->{if(quote!=null)quote.fundingClaim=claim;callback.done(quote,error);});
    }
    public void claimLink(Result<MoneroClaim> callback){
        work("Opening payment link…",callback,()->{
            if(claimId==null || handle==0 || !(fundingStatus().equals("broadcast") || fundingStatus().equals("received")))throw new IllegalStateException("Confirm funding before sharing this link. An uncertain broadcast must be checked first.");
            char[] seed=WalletNative.claimSeed(handle);
            try{return new MoneroClaim(MoneroPayment.create(primary(),claimAmount(),"","",network),config.optLong("scanStart",0),seed);}
            finally{Arrays.fill(seed,'\0');}
        });
    }
    public void prepareClaimSweep(String destination,Result<Quote> callback){
        MoneroPayment address=MoneroPayment.address(destination,network);
        work("Calculating claim fee…",callback,()->{
            if(claimId==null || handle==0 || !sessionSynced)throw new IllegalStateException("Unlock and sync the claim first.");
            String[] values=WalletNative.prepareClaimSweep(handle,address.address);
            MoneroPayment payment=MoneroPayment.create(address.address,MoneroPayment.format(new java.math.BigInteger(values[1])),"","",network);
            return new Quote(handle,payment,values);
        });
    }
    private MonogramWallet(int account,String owner,MoneroPayment.Network network,String walletId,String claimId) throws Exception {
        this.account=account;this.owner=owner;this.network=network;this.walletId=walletId;this.claimId=claimId;
        byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest((account+"\n"+owner).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex=new StringBuilder();for(byte b:digest)hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));scope=hex.toString();
        Context context=ApplicationLoader.applicationContext;
        root=new File(context.getNoBackupFilesDir(),"xmr-wallet/"+scope+"/"+network.name()+(WalletRegistry.DEFAULT.equals(walletId) ? "" : "/wallets/"+walletId)+(claimId==null ? "" : "/claims/"+claimId));
        metadata=new AtomicFile(new File(root,"wallet.json"));vault=new WalletVault(context,scope+(WalletRegistry.DEFAULT.equals(walletId) ? "" : "-wallet-"+walletId)+(claimId==null ? "" : "-claim-"+claimId),network.name());
        if(metadata.getBaseFile().exists())try(FileInputStream input=metadata.openRead()) {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;
            while((count=input.read(buffer))!=-1){if(bytes.size()+count>65536)throw new IOException("Invalid wallet metadata.");bytes.write(buffer,0,count);}
            config=new JSONObject(bytes.toString("UTF-8"));
        }
    }
    private void check() {
        if(!walletId.equals(WalletRegistry.active(account,owner,network)))throw new IllegalStateException("The selected wallet changed. Reopen the wallet screen.");
        checkIdentity();
    }
    private void checkIdentity(){
        if(!owner.equals(MonogramController.get(account).accountJid()) || !MonogramXmr.enabled(account))throw new IllegalStateException("Wallet access changed. Reopen Monero settings.");
        try(MonogramXmr settings=new MonogramXmr(account)){if(settings.network()!=network)throw new IllegalStateException("The wallet network changed.");}
    }
    public boolean exists(){return config!=null && config.has("directory");}
    public boolean unlocked(){return handle!=0;}
    public boolean readyToSend(){return sessionScanned() && !busy;}
    public boolean sessionScanned(){return handle!=0 && (sessionSynced || recentCacheReady && scanned());}
    private boolean recentlySynced(){
        if(!scanned() || claimId!=null || !node().equals(config.optString("lastSyncNode","")))return false;
        long age=System.currentTimeMillis()-config.optLong("lastSyncAt",0);
        return age>=0 && age<=RECENT_SYNC_MS;
    }
    private void rememberSync() throws Exception {
        JSONObject next=new JSONObject(config.toString()).put("lastSyncAt",System.currentTimeMillis()).put("lastSyncNode",node());
        save(next);sessionSynced=true;recentCacheReady=false;
    }
    public String node(){return config==null ? "" : config.optString("node","");}
    public String primary(){return config==null ? "" : config.optString("primary","");}
    public void observe(Observer observer){observers.add(observer);}
    public void unobserve(Observer observer){observers.remove(observer);}
    private void changed(){WalletSyncService.progress(this);AndroidUtilities.runOnUIThread(()->{for(Observer observer:new ArrayList<>(observers))observer.changed();});}
    public void acquire(){check();leases++;AndroidUtilities.cancelRunOnUIThread(delayedLock);}
    public void acquireForManagement(){checkIdentity();leases++;AndroidUtilities.cancelRunOnUIThread(delayedLock);}
    public void release(){leases=Math.max(0,leases-1);if(leases==0)AndroidUtilities.runOnUIThread(delayedLock,700);}
    public final class Authentication implements AutoCloseable {
        private boolean closed;
        private final long expires=android.os.SystemClock.elapsedRealtime()+120000;
        private final Runnable timeout=()->{if(!closed){close();lock();}};
        private Authentication(){AndroidUtilities.runOnUIThread(timeout,120000);}
        public boolean valid(){return !closed && authentication==this && android.os.SystemClock.elapsedRealtime()<expires;}
        @Override public void close(){if(closed)return;closed=true;AndroidUtilities.cancelRunOnUIThread(timeout);if(authentication==this)authentication=null;if(leases==0)lock();}
    }
    private boolean authenticating(){return authentication!=null && authentication.valid();}
    public Authentication beginSystemAuthentication(){return beginSystemAuthentication(false);}
    public Authentication beginSystemAuthentication(boolean management){if(management)checkIdentity();else check();if(busy || authenticating())throw new IllegalStateException("Finish the current wallet operation first.");authentication=new Authentication();return authentication;}
    boolean initialScanComplete(){return config!=null && config.optBoolean("initialScanComplete",scanned());}
    private boolean holdForInitialScan(){
        if(claimId!=null || initialScanComplete() || (!exists() && !busy))return false;
        try{checkIdentity();return true;}catch(Exception changed){return false;}
    }
    private void lockAutomatically(){if(!authenticating() && !holdForInitialScan() && !(claimId==null && scanning && !scanCancelled))lock();}
    public void lock(){
        if(authentication!=null){Authentication old=authentication;authentication=null;old.closed=true;AndroidUtilities.cancelRunOnUIThread(old.timeout);}
        epoch++;scanCancelled=true;recentCacheReady=false;sessionSynced=false;WalletTlsBridge bridge=transport;transport=null;long current=handle;stopper.execute(()->{if(bridge!=null)bridge.close();if(current!=0)WalletNative.stop(current);});
        actor.execute(()->{long old=handle;handle=0;try{if(old!=0)WalletNative.close(old);}catch(Exception ignored){}balance=null;transactions=new String[0];status="Locked";changed();});
    }
    public static synchronized void lockAll(){for(MonogramWallet wallet:instances.values())wallet.lockAutomatically();}
    public static synchronized void lockAccount(int account){for(MonogramWallet wallet:instances.values())if(wallet.account==account)wallet.lock();}
    public WalletVault.Authorization authorizeRestore() throws Exception {check();if(exists())throw new IllegalStateException("A wallet already exists on this network.");return vault.authorizeRestore();}
    public char[] authorizeUnlock() throws Exception {check();if(!exists())throw new IllegalStateException("Restore your wallet first.");return vault.unlock(config.getJSONObject("sealed"));}
    private void save(JSONObject value) throws Exception {
        if(!root.exists() && !root.mkdirs())throw new IOException("Cannot create private wallet storage.");
        FileOutputStream output=metadata.startWrite();
        try {output.write(value.toString().getBytes(StandardCharsets.UTF_8));metadata.finishWrite(output);config=value;}
        catch(Exception error){metadata.failWrite(output);throw error;}
    }
    private static int nativeNetwork(MoneroPayment.Network n){return n==MoneroPayment.Network.MAINNET ? 0 : n==MoneroPayment.Network.TESTNET ? 1 : 2;}
    private <T> void work(String label,Result<T> result,Callable<T> task){work(label,result,task,()->{});}
    private <T> void work(String label,Result<T> result,Callable<T> task,Runnable cleanup) {work(label,result,task,cleanup,false);}
    private <T> void work(String label,Result<T> result,Callable<T> task,Runnable cleanup,boolean identityScoped) {
        if(identityScoped)checkIdentity();else check();if(busy)throw new IllegalStateException("Wait for the current wallet operation, or stop syncing first.");
        busy=true;status=label;error="";long generation=epoch;changed();
        actor.execute(()->{
            T value=null;Exception problem=null;
            try {if(identityScoped)checkIdentity();else check();if(generation!=epoch)throw new IllegalStateException("Wallet operation cancelled.");value=task.call();}
            catch(Exception failure){problem=failure;error=failure.getMessage()==null ? "Wallet operation failed." : failure.getMessage();}
            catch(LinkageError failure){problem=new IllegalStateException("The wallet engine is unavailable in this build.");error=problem.getMessage();}
            finally {cleanup.run();busy=false;if(handle!=0)status="Unlocked";else status="Locked";changed();}
            final T answer=value;final Exception failed=problem;
            AndroidUtilities.runOnUIThread(()->{try{result.done(answer,failed);}finally{if(leases==0)lockAutomatically();}});
        });
    }
    private void read() {
        balance=WalletNative.snapshot(handle);MoneroPayment.address(balance[0],network);transactions=WalletNative.history(handle);
    }
    public void restore(WalletVault.Authorization authorization,char[] seed,char[] passphrase,boolean polyseed,long height,Result<Void> callback) {
        try {work("Restoring wallet…",callback,()->{
            File directory=new File(root,UUID.randomUUID().toString());long restored=0;
            try {
                if(exists())throw new IllegalStateException("A wallet already exists.");
                if(!directory.mkdirs())throw new IOException("Cannot create private wallet storage.");WalletNative.load();
                restored=WalletNative.restore(new File(directory,"wallet").getAbsolutePath(),authorization.password,seed,passphrase,nativeNetwork(network),height,polyseed);
                String[] snapshot=WalletNative.snapshot(restored);MoneroPayment.address(snapshot[0],network);
                if(claimId!=null){
                    if(expectedRestoreAddress==null || !snapshot[0].equals(expectedRestoreAddress))throw new IllegalStateException("Claim wallet does not match the link address.");
                    WalletNative.newAddress(restored,0,"");
                    WalletNative.close(restored);restored=0;restored=WalletNative.openClaim(new File(directory,"wallet").getAbsolutePath(),authorization.password,nativeNetwork(network),height);
                }
                JSONObject value=new JSONObject().put("node",node()).put("version",1).put("directory",directory.getName()).put("primary",snapshot[0]).put("sealed",authorization.sealed).put("maxIndex",0).put("scanned",false).put("scanPolicy",2).put("scanStart",Long.parseLong(snapshot[7])).put("initialScanComplete",false);
                save(value);handle=restored;restored=0;read();return null;
            } finally {
                Arrays.fill(seed,'\0');Arrays.fill(passphrase,'\0');authorization.close();
                if(restored!=0)WalletNative.close(restored);
                if(!exists())delete(directory);
            }
        },()->{Arrays.fill(seed,'\0');Arrays.fill(passphrase,'\0');authorization.close();});}catch(Exception error){Arrays.fill(seed,'\0');Arrays.fill(passphrase,'\0');authorization.close();throw error;}
    }
    public void remove(Result<Void> callback){
        if(claimId!=null)throw new IllegalStateException("Use payment-link recovery for claim wallets.");
        File[] links=new File(root,"claims").listFiles();
        if(links!=null)for(File link:links)if(new File(link,"wallet.json").exists())throw new IllegalStateException("Resolve your saved payment links before removing this wallet.");
        work("Removing local wallet…",callback,()->{
            if(handle!=0){long old=handle;handle=0;WalletNative.close(old);}
            WalletTlsBridge bridge=transport;transport=null;if(bridge!=null)bridge.close();
            if(exists()){
                String directory=config.getString("directory");if(!directory.matches("[0-9a-f-]{36}"))throw new IOException("Invalid wallet path.");
                deleteChecked(new File(root,directory));
            }
            metadata.delete();vault.remove();config=null;balance=null;transactions=new String[0];sessionSynced=false;recentCacheReady=false;
            WalletRegistry.remove(account,owner,network,walletId);return null;
        },()->{},true);
    }
    private void deleteChecked(File file)throws IOException{
        if(!file.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator))throw new IOException("Invalid wallet storage path.");
        File[] children=file.listFiles();if(children!=null)for(File child:children)deleteChecked(child);
        if(file.exists() && !file.delete())throw new IOException("Could not remove local wallet files.");
    }
    public void create(WalletVault.Authorization authorization,String node,Result<Void> callback){
        try{work("Creating wallet…",callback,()->{
            if(exists() || claimId!=null)throw new IllegalStateException("Choose an empty wallet slot.");
            File directory=new File(root,UUID.randomUUID().toString());long created=0;
            try{
                if(!directory.mkdirs())throw new IOException("Cannot save wallet.");WalletNative.load();
                WalletTlsBridge bridge=new WalletTlsBridge(node);transport=bridge;
                created=WalletNative.createWallet(new File(directory,"wallet").getAbsolutePath(),authorization.password,nativeNetwork(network),bridge.endpoint(),bridge.user,bridge.password);
                String[] snapshot=WalletNative.snapshot(created);MoneroPayment.address(snapshot[0],network);
                JSONObject value=new JSONObject().put("version",1).put("directory",directory.getName()).put("primary",snapshot[0]).put("sealed",authorization.sealed)
                    .put("node",node).put("maxIndex",0).put("scanned",false).put("scanPolicy",2).put("scanStart",Long.parseLong(snapshot[7])).put("initialScanComplete",false);
                save(value);handle=created;created=0;read();return null;
            }finally{if(created!=0)WalletNative.close(created);if(!exists())delete(directory);}
        },authorization::close);}catch(Exception e){authorization.close();throw e;}
    }
    /** Local recovery UI only. This method is never used by payment-link construction. */
    public void recoveryPhrase(Result<char[]> callback){
        work("Opening recovery phrase…",callback,()->{if(claimId!=null || handle==0)throw new IllegalStateException("Unlock this wallet first.");return WalletNative.recoveryPhrase(handle);});
    }
    private static void delete(File file){File[] children=file.listFiles();if(children!=null)for(File child:children)delete(child);file.delete();}
    public void unlock(char[] password,Result<Void> callback) {
        try {work("Unlocking wallet…",callback,()->{try{
            if(handle!=0)return null;
            String directory=config.getString("directory");if(!directory.matches("[0-9a-f-]{36}"))throw new IOException("Invalid wallet path.");
            WalletNative.load();handle=claimId==null ? WalletNative.open(new File(new File(root,directory),"wallet").getAbsolutePath(),password,nativeNetwork(network)) : WalletNative.openClaim(new File(new File(root,directory),"wallet").getAbsolutePath(),password,nativeNetwork(network),config.optLong("scanStart",0));
            read();if(!balance[0].equals(primary())){WalletNative.close(handle);handle=0;throw new IllegalStateException("Wallet identity does not match.");}
            recentCacheReady=recentlySynced();return null;
        }finally{Arrays.fill(password,'\0');}},()->Arrays.fill(password,'\0'));}catch(Exception error){Arrays.fill(password,'\0');throw error;}
    }
    public void node(String endpoint,Result<Void> callback) {
        String value=endpoint.trim();URI uri=URI.create(value);
        if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null || (!uri.getPath().isEmpty() && !uri.getPath().equals("/")))
            throw new IllegalArgumentException("Enter an HTTPS Monero node URL, with no password, path or query.");
        work("Saving node…",callback,()->{JSONObject next=config==null ? new JSONObject() : new JSONObject(config.toString());next.put("node",value).put("lastSyncAt",0);save(next);recentCacheReady=false;sessionSynced=false;return null;});
    }
    public boolean needsRescan(){return exists() && config.optInt("scanPolicy",0)<2;}
    public void sync(Result<Void> callback){if(needsRescan())throw new IllegalStateException("Rescan this wallet to recover history skipped by an older build.");refresh(false,callback);}
    public void rescan(Result<Void> callback){refresh(true,callback);}
    private void refresh(boolean rescan,Result<Void> callback) {
        if(!unlocked())throw new IllegalStateException("Unlock your wallet first.");if(node().isEmpty())throw new IllegalStateException("Set your Monero node first.");
        check();if(busy)throw new IllegalStateException("Wait for the current wallet operation first.");
        scanCancelled=false;scanning=true;scanHeight=0;scanTarget=0;scanOrigin=0;
        boolean initial=claimId==null;
        try{if(initial)WalletSyncService.begin(this);work("Connecting to node…",callback,()->{
            sessionSynced=false;
            if(rescan){JSONObject pending=new JSONObject(config.toString());pending.put("initialScanComplete",initialScanComplete()).put("scanned",false).put("scanPolicy",1);save(pending);balance=null;transactions=new String[0];changed();}
            WalletTlsBridge previous=transport;transport=null;if(previous!=null)previous.close();
            WalletTlsBridge bridge=new WalletTlsBridge(node());transport=bridge;WalletNative.connect(handle,bridge.endpoint(),bridge.user,bridge.password);
            String[] start=WalletNative.snapshot(handle);
            scanTarget=Long.parseLong(start[4]);scanOrigin=rescan ? 0 : Math.max(Long.parseLong(start[3]),Long.parseLong(start[7]));
            scanHeight=rescan ? 0 : Long.parseLong(start[3]);status=scanProgress();changed();
            final long[] last={0};WalletNative.Progress progress=(height,end)->{
                scanHeight=height;long now=android.os.SystemClock.elapsedRealtime();
                if(end || now-last[0]>700){last[0]=now;status=scanProgress();changed();}
            };
            boolean first=rescan;
            while(true){
                if(scanCancelled)throw new IllegalStateException("Scan stopped. Saved progress will resume next time.");
                boolean complete=first ? WalletNative.rescan(handle,progress) : WalletNative.sync(handle,progress);
                // Native returned only after saving a safe checkpoint. A repaired cache
                // can now resume without clearing its already scanned history again.
                read();JSONObject checkpoint=new JSONObject(config.toString()).put("scanPolicy",2).put("scanStart",Long.parseLong(balance[7]));
                save(checkpoint);first=false;changed();
                if(complete)break;
            }
            checkIdentity();read();if(!"true".equals(balance[5]))throw new IllegalStateException("The node has not completed this scan.");
            JSONObject next=new JSONObject(config.toString());next.put("scanned",true).put("scanPolicy",2).put("scanStart",Long.parseLong(balance[7])).put("initialScanComplete",true);
            if(claimId!=null && fundingStatus().equals("unknown") && new java.math.BigInteger(balance[1]).signum()>0)next.put("fundingStatus","broadcast");
            save(next);rememberSync();return null;
        },()->{scanning=false;if(initial)WalletSyncService.finish(this);},true);}
        catch(RuntimeException failure){scanning=false;if(initial)WalletSyncService.finish(this);throw failure;}
    }
    public void stop(){scanCancelled=true;recentCacheReady=false;sessionSynced=false;status="Saving scan progress…";changed();WalletTlsBridge bridge=transport;transport=null;long current=handle;stopper.execute(()->{if(bridge!=null)bridge.close();if(current!=0)WalletNative.stop(current);});}
    public boolean scanned(){return config!=null && config.optBoolean("scanned",false) && !needsRescan();}
    public void newAddress(Result<String> callback){work("Creating receiving address…",callback,()->{
        if(handle==0)throw new IllegalStateException("Unlock your wallet first.");
        String[] address=WalletNative.newAddress(handle,config.optLong("maxIndex",0),"");
        MoneroPayment checked=MoneroPayment.address(address[0],network);if(checked.type!=MoneroPayment.AddressType.SUBADDRESS)throw new IllegalStateException("Engine returned an invalid subaddress.");
        JSONObject next=new JSONObject(config.toString());next.put("maxIndex",Long.parseLong(address[1]));save(next);return address[0];
    });}
    public static final class Quote {
        public final long session,id;public final MoneroPayment payment;public final java.math.BigInteger fee;public final String hashes,count;
        private boolean consumed;
        private MonogramWallet fundingClaim;
        Quote(long session,MoneroPayment payment,String[] values){this.session=session;this.payment=payment;id=Long.parseLong(values[0]);fee=new java.math.BigInteger(values[2]);count=values[3];hashes=values[4];}
    }
    /** Recent cache opens the composer immediately. Before a real quote, connect and
     * catch up the native engine so cached UI readiness never authorizes stale spending. */
    private void syncBeforePayment() throws Exception {
        if(sessionSynced && recentlySynced())return;
        if(node().isEmpty())throw new IllegalStateException("Set your Monero node first.");
        sessionSynced=false;scanCancelled=false;
        WalletTlsBridge previous=transport;transport=null;if(previous!=null)previous.close();
        WalletTlsBridge bridge=new WalletTlsBridge(node());transport=bridge;
        status="Checking current balance…";changed();
        WalletNative.connect(handle,bridge.endpoint(),bridge.user,bridge.password);
        while(true){
            if(scanCancelled)throw new IllegalStateException("Wallet operation cancelled.");
            boolean complete=WalletNative.sync(handle,(height,last)->{});
            read();changed();if(complete)break;
        }
        check();if(!"true".equals(balance[5]))throw new IllegalStateException("The node has not completed this scan.");
        rememberSync();
    }
    public void prepare(MoneroPayment payment,Result<Quote> callback){
        MoneroPayment checked=MoneroPayment.parse(payment.uri(),network);
        if(checked.atomicAmount==null)throw new IllegalArgumentException("Enter an amount.");
        work("Calculating network fee…",callback,()->{if(handle==0 || !sessionScanned())throw new IllegalStateException("Unlock and sync your wallet first.");
            syncBeforePayment();
            String[] values=WalletNative.prepare(handle,checked.address,checked.atomicAmount.toString());
            Quote quote=new Quote(handle,checked,values);if(!checked.atomicAmount.toString().equals(values[1])){WalletNative.cancel(handle,quote.id);throw new IllegalStateException("Payment amount mismatch.");}return quote;});
    }
    public void cancel(Quote quote){if(quote==null || quote.consumed)return;quote.consumed=true;actor.execute(()->{try{if(handle==quote.session)WalletNative.cancel(handle,quote.id);}catch(Exception ignored){}});}
    public void commit(Quote quote,Result<String[]> callback){
        if(quote.consumed || quote.session!=handle || handle==0)throw new IllegalStateException("Payment review expired. Review again.");
        work("Broadcasting payment…",(value,failure)->{if(failure!=null)cancel(quote);callback.done(value,failure);},()->{
            // Durable before broadcast: a crash must leave the transaction hashes available for inspection.
            if(quote.fundingClaim!=null)quote.fundingClaim.saveFunding("unknown",quote.hashes);
            JSONObject next=new JSONObject(config.toString()).put("lastBroadcast",new JSONObject().put("hashes",quote.hashes).put("state","unknown"));save(next);
            quote.consumed=true;String[] result=WalletNative.commit(handle,quote.id);
            try{JSONObject saved=new JSONObject(config.toString());saved.getJSONObject("lastBroadcast").put("state",result[0]);save(saved);}catch(Exception ignored){}
            if(quote.fundingClaim!=null)try{quote.fundingClaim.saveFunding(result[0],quote.hashes);}catch(Exception ignored){}
            try{read();}catch(Exception ignored){}return result;
        });
    }
    public String lastBroadcast(){return config==null ? "" : config.optJSONObject("lastBroadcast")==null ? "" : config.optJSONObject("lastBroadcast").toString();}
}
