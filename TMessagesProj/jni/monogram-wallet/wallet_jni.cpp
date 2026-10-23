// SPDX-License-Identifier: GPL-3.0-or-later
// Cryptography, seed interpretation, scanning and signing are supplied by the pinned Monero API.
#include <jni.h>
#include "wallet2_api.h"
#include <algorithm>
#include <atomic>
#include <memory>
#include <mutex>
#include <sstream>
#include <unordered_map>
#include <limits>
#include <cstdlib>
#include <chrono>

namespace {
JavaVM* vm=nullptr;
std::mutex registryMutex;
std::atomic<uint64_t> nextHandle{1};
void wipe(std::string& value) {volatile char* p=value.empty() ? nullptr : &value[0];for(size_t i=0;i<value.size();i++)p[i]=0;}
struct Secret {
    std::string value;
    Secret(JNIEnv* env,jcharArray input) {
        if(!input) return;
        const jsize length=env->GetArrayLength(input);
        std::vector<jchar> text(length);env->GetCharArrayRegion(input,0,length,text.data());
        for(jsize i=0;i<length;i++) {
            uint32_t c=text[i];
            if(c>=0xd800 && c<=0xdbff) {
                if(i+1>=length || text[i+1]<0xdc00 || text[i+1]>0xdfff) {std::fill(text.begin(),text.end(),0);wipe(value);throw std::runtime_error("Invalid Unicode input.");}
                c=0x10000+((c-0xd800)<<10)+(text[++i]-0xdc00);
            } else if(c>=0xdc00 && c<=0xdfff) {std::fill(text.begin(),text.end(),0);wipe(value);throw std::runtime_error("Invalid Unicode input.");}
            if(c==0) {std::fill(text.begin(),text.end(),0);wipe(value);throw std::runtime_error("Input contains a null character.");}
            if(c<0x80) value.push_back(static_cast<char>(c));
            else if(c<0x800) {value.push_back(static_cast<char>(0xc0|(c>>6)));value.push_back(static_cast<char>(0x80|(c&63)));}
            else if(c<0x10000) {value.push_back(static_cast<char>(0xe0|(c>>12)));value.push_back(static_cast<char>(0x80|((c>>6)&63)));value.push_back(static_cast<char>(0x80|(c&63)));}
            else {value.push_back(static_cast<char>(0xf0|(c>>18)));value.push_back(static_cast<char>(0x80|((c>>12)&63)));value.push_back(static_cast<char>(0x80|((c>>6)&63)));value.push_back(static_cast<char>(0x80|(c&63)));}
        }
        volatile jchar* p=text.data();for(jsize i=0;i<length;i++)p[i]=0;
    }
    ~Secret() {wipe(value);}
};
std::string string(JNIEnv* env,jstring input) {
    if(!input) return {};
    const char* value=env->GetStringUTFChars(input,nullptr);if(!value) throw std::runtime_error("Could not read input.");
    std::string copy(value);env->ReleaseStringUTFChars(input,value);return copy;
}
void fail(JNIEnv* env,const std::string& message) {
    if(env->ExceptionCheck()) return;
    std::string safe=message.substr(0,800);
    for(char& c:safe) if(static_cast<unsigned char>(c)<32 && c!='\n')c=' ';
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),safe.c_str());
}
jobjectArray strings(JNIEnv* env,const std::vector<std::string>& values) {
    jclass type=env->FindClass("java/lang/String");
    jobjectArray result=env->NewObjectArray(static_cast<jsize>(values.size()),type,nullptr);env->DeleteLocalRef(type);
    for(size_t i=0;i<values.size();i++) {jstring value=env->NewStringUTF(values[i].c_str());env->SetObjectArrayElement(result,i,value);env->DeleteLocalRef(value);}
    return result;
}
std::string hashes(const std::vector<std::string>& values) {std::string result;for(const auto& value:values){if(!result.empty())result+=',';result+=value;}return result;}
Monero::WalletManager* manager() {return Monero::WalletManagerFactory::getWalletManager();}
struct Session {
    Monero::Wallet* wallet;
    uint64_t targetHeight=0;
    bool connected=false;
    std::atomic<bool> cancelled{false};
    bool claim=false;
    std::unordered_map<uint64_t,Monero::PendingTransaction*> quotes;
    explicit Session(Monero::Wallet* wallet):wallet(wallet) {}
    ~Session() {
        try {
            wallet->setListener(nullptr);
            for(const auto& item:quotes)wallet->disposeTransaction(item.second);
            wallet->stop();
            manager()->closeWallet(wallet,false);
        } catch(...) { /* No private engine exception may cross a destructor/JNI boundary. */ }
    }
};
std::unordered_map<uint64_t,std::shared_ptr<Session>> sessions;
std::shared_ptr<Session> session(jlong handle) {
    std::lock_guard<std::mutex> lock(registryMutex);auto found=sessions.find(handle);
    if(found==sessions.end())throw std::runtime_error("Wallet is locked.");return found->second;
}
jlong keep(Monero::Wallet* wallet,bool recovering=true) {
    auto owned=std::make_shared<Session>(wallet);
    // Every wallet opened here was restored. Without this, reopening at height 1
    // makes WalletImpl::doInit skip history up to the current tip as a new wallet.
    wallet->setRecoveringFromSeed(recovering);
    int status;std::string error;wallet->statusWithErrorString(status,error);
    if(status!=Monero::Wallet::Status_Ok)throw std::runtime_error("Wallet operation failed.");
    uint64_t id=nextHandle.fetch_add(1);std::lock_guard<std::mutex> lock(registryMutex);sessions.emplace(id,owned);return static_cast<jlong>(id);
}
struct Listener final:Monero::WalletListener {
    jobject callback; jmethodID method;
    Monero::Wallet* wallet;
    bool checkpoint=false;
    std::chrono::steady_clock::time_point lastReport{};
    const std::chrono::steady_clock::time_point started=std::chrono::steady_clock::now();
    Listener(JNIEnv* env,jobject callback,Monero::Wallet* wallet):callback(env->NewGlobalRef(callback)),method(nullptr),wallet(wallet) {
        jclass type=env->GetObjectClass(callback);method=env->GetMethodID(type,"block","(JZ)V");env->DeleteLocalRef(type);
    }
    ~Listener() override {
        JNIEnv* env=nullptr;bool attached=vm->GetEnv(reinterpret_cast<void**>(&env),JNI_VERSION_1_6)==JNI_EDETACHED;
        if(attached && vm->AttachCurrentThread(&env,nullptr)!=JNI_OK)return;
        if(env)env->DeleteGlobalRef(callback);
        if(attached)vm->DetachCurrentThread();
    }
    void moneySpent(const std::string&,uint64_t) override {}
    void moneyReceived(const std::string&,uint64_t) override {}
    void unconfirmedMoneyReceived(const std::string&,uint64_t) override {}
    void newBlock(uint64_t height,bool last) override {
        // Never serialize wallet2 while refresh mutates it. Stop at a block boundary,
        // return from refresh, then save on the same serialized wallet actor.
        if(std::chrono::steady_clock::now()-started>=std::chrono::seconds(30)) {
            checkpoint=true;wallet->stop();
        }
        auto now=std::chrono::steady_clock::now();
        if(!last && now-lastReport<std::chrono::milliseconds(250))return;
        lastReport=now;
        JNIEnv* env=nullptr;bool attached=vm->GetEnv(reinterpret_cast<void**>(&env),JNI_VERSION_1_6)==JNI_EDETACHED;
        if(attached && vm->AttachCurrentThread(&env,nullptr)!=JNI_OK)return;
        if(env && method && !env->ExceptionCheck())env->CallVoidMethod(callback,method,static_cast<jlong>(height),static_cast<jboolean>(last));
        if(env && env->ExceptionCheck())env->ExceptionClear();
        if(attached)vm->DetachCurrentThread();
    }
    void updated() override {}
    void refreshed() override {}
};
std::string publicError(Monero::Wallet* wallet,const std::string& fallback) {
    int status;std::string error;wallet->statusWithErrorString(status,error);return error.empty() ? fallback : error;
}
uint64_t unsignedAmount(const std::string& input) {
    if(input.empty() || input.find_first_not_of("0123456789")!=std::string::npos)throw std::runtime_error("Invalid amount.");
    size_t used=0;uint64_t value=std::stoull(input,&used);
    if(!value || used!=input.size())throw std::runtime_error("Invalid amount.");return value;
}
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* loaded,void*) {
    vm=loaded;
    Monero::WalletManagerFactory::setLogCategories("*:FATAL");
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jlong JNICALL Java_com_negzero_monogram_wallet_WalletNative_restore(
        JNIEnv* env,jclass,jstring path,jcharArray password,jcharArray seed,jcharArray passphrase,jint network,jlong height,jboolean polyseed) {
    try {
        if(network<0 || network>2 || height<0)throw std::runtime_error("Invalid wallet settings.");
        Secret pass(env,password),words(env,seed),offset(env,passphrase);
        Monero::Wallet* wallet=polyseed ? manager()->createWalletFromPolyseed(string(env,path),pass.value,static_cast<Monero::NetworkType>(network),words.value,offset.value,false,height)
                : manager()->recoveryWallet(string(env,path),pass.value,words.value,static_cast<Monero::NetworkType>(network),height,1,offset.value);
        if(!wallet)throw std::runtime_error("Restore failed.");
        jlong handle=keep(wallet);
        if(polyseed && height>0)session(handle)->wallet->setRefreshFromBlockHeight(height);
        try {if(!session(handle)->wallet->store(""))throw std::runtime_error("Could not store wallet.");}
        catch(...) {std::lock_guard<std::mutex> lock(registryMutex);sessions.erase(handle);throw;}
        return handle;
    } catch(...) {fail(env,"Could not restore this wallet. Check the seed, its checksum and the optional seed passphrase.");return 0;}
}

extern "C" JNIEXPORT jlong JNICALL Java_com_negzero_monogram_wallet_WalletNative_open(
        JNIEnv* env,jclass,jstring path,jcharArray password,jint network) {
    try {
        if(network<0 || network>2)throw std::runtime_error("Invalid network.");Secret pass(env,password);
        Monero::Wallet* wallet=manager()->openWallet(string(env,path),pass.value,static_cast<Monero::NetworkType>(network));
        if(!wallet)throw std::runtime_error("Open failed.");return keep(wallet);
    } catch(...) {fail(env,"Could not unlock the local wallet. Its files and device unlock key must match.");return 0;}
}

extern "C" JNIEXPORT void JNICALL Java_com_negzero_monogram_wallet_WalletNative_close(JNIEnv* env,jclass,jlong handle) {
    try {
        auto owned=session(handle);owned->wallet->stop();
        const bool stored=owned->wallet->store("");
        {std::lock_guard<std::mutex> lock(registryMutex);sessions.erase(handle);}
        if(!stored)throw std::runtime_error("The wallet was locked, but its latest local cache could not be saved.");
    } catch(const std::exception& error) {fail(env,error.what());}
}
extern "C" JNIEXPORT void JNICALL Java_com_negzero_monogram_wallet_WalletNative_stop(JNIEnv*,jclass,jlong handle) {
    try {auto s=session(handle);s->cancelled=true;s->wallet->stop();}catch(...) {}
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_com_negzero_monogram_wallet_WalletNative_snapshot(JNIEnv* env,jclass,jlong handle) {
    try {
        auto s=session(handle);auto w=s->wallet;
        return strings(env,{w->address(0,0),std::to_string(w->balance(0)),std::to_string(w->unlockedBalance(0)),std::to_string(w->blockChainHeight()),
                std::to_string(s->targetHeight),w->synchronized() ? "true" : "false",std::to_string(w->numSubaddresses(0)),std::to_string(w->getRefreshFromBlockHeight())});
    }catch(const std::exception& error){fail(env,error.what());return nullptr;}
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_com_negzero_monogram_wallet_WalletNative_newAddress(JNIEnv* env,jclass,jlong handle,jlong floor,jstring label) {
    try {
        if(floor<0 || floor>std::numeric_limits<uint32_t>::max()-2)throw std::runtime_error("Invalid subaddress index.");auto s=session(handle);
        while(s->wallet->numSubaddresses(0)<=static_cast<size_t>(floor))s->wallet->addSubaddress(0,"");
        const size_t index=s->wallet->numSubaddresses(0);s->wallet->addSubaddress(0,string(env,label));
        if(!s->wallet->store(""))throw std::runtime_error("Could not save the new receiving address.");
        return strings(env,{s->wallet->address(0,index),std::to_string(index)});
    }catch(const std::exception& error){fail(env,error.what());return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_com_negzero_monogram_wallet_WalletNative_connect(JNIEnv* env,jclass,jlong handle,jstring endpoint,jstring user,jstring password) {
    try {
        auto s=session(handle);std::string node=string(env,endpoint);
        // Remote TLS is verified on EVERY socket by WalletTlsBridge. The upstream API's
        // automatic SSL mode accepts invalid certificates, so it only sees authenticated loopback.
        if(node.rfind("http://127.0.0.1:",0)!=0)throw std::runtime_error("Use the verified wallet TLS transport.");
        if(!s->wallet->init(node,0,string(env,user),string(env,password),false,false,""))throw std::runtime_error(publicError(s->wallet,"Could not initialize the node connection."));
        s->wallet->setTrustedDaemon(false);
        if(!s->wallet->connectToDaemon())throw std::runtime_error(publicError(s->wallet,"Could not connect to the node."));
        // connectToDaemon() checks HTTP but does not populate WalletImpl's
        // m_is_connected flag. Height APIs return zero until connected() is called.
        if(s->wallet->connected()!=Monero::Wallet::ConnectionStatus_Connected)
            throw std::runtime_error("The node connection or RPC version is unavailable.");
        s->targetHeight=std::max(s->wallet->daemonBlockChainHeight(),s->wallet->daemonBlockChainTargetHeight());
        if(s->targetHeight<=1)throw std::runtime_error("The node did not provide a usable blockchain height.");
        s->connected=true;s->cancelled=false;
    }catch(const std::exception& error){fail(env,error.what());}
}
namespace {
bool refresh(JNIEnv* env,jlong handle,jobject callback,bool rescan) {
    try {
        auto s=session(handle);if(!s->connected)throw std::runtime_error("Connect to your node before syncing.");
        if(s->cancelled)throw std::runtime_error("Scan stopped. Saved progress will resume next time.");
        if(rescan){s->wallet->setRecoveringFromSeed(true);s->wallet->setRefreshFromBlockHeight(0);}
        Listener listener(env,callback,s->wallet);s->wallet->setListener(&listener);
        bool success=false;
        try {success=rescan ? s->wallet->rescanBlockchain() : s->wallet->refresh();}
        catch(...) {s->wallet->setListener(nullptr);s->wallet->store("");throw;}
        s->wallet->setListener(nullptr);
        if(!s->wallet->store(""))throw std::runtime_error("Could not save scan progress. Retry before closing the app.");
        // A checkpoint is a successfully returned, durably saved partial refresh.
        // An interrupted repair must never be advertised as a completed scan.
        if(s->cancelled)throw std::runtime_error("Scan stopped. Saved progress will resume next time.");
        if(listener.checkpoint && success)return false;
        if(!success || !s->wallet->synchronized())throw std::runtime_error(publicError(s->wallet,"Wallet scan has not finished. Retry when the node is fully synced."));
        return true;
    }catch(const std::exception& error){fail(env,error.what());return false;}
}
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_negzero_monogram_wallet_WalletNative_sync(JNIEnv* env,jclass,jlong handle,jobject callback){return refresh(env,handle,callback,false);}
extern "C" JNIEXPORT jboolean JNICALL Java_com_negzero_monogram_wallet_WalletNative_rescan(JNIEnv* env,jclass,jlong handle,jobject callback){return refresh(env,handle,callback,true);}
extern "C" JNIEXPORT jobjectArray JNICALL Java_com_negzero_monogram_wallet_WalletNative_prepare(JNIEnv* env,jclass,jlong handle,jstring destination,jstring amount) {
    try {
        auto s=session(handle);if(!s->connected || !s->wallet->synchronized())throw std::runtime_error("Sync this wallet before sending.");
        if(!s->quotes.empty())throw std::runtime_error("Finish or cancel the current payment review first.");
        uint64_t value=unsignedAmount(string(env,amount));
        auto tx=s->wallet->createTransaction(string(env,destination),"",Monero::optional<uint64_t>(value),0,Monero::PendingTransaction::Priority_Default,0);
        if(!tx)throw std::runtime_error("Could not prepare the payment.");
        if(tx->status()!=Monero::PendingTransaction::Status_Ok || tx->amount()!=value) {
            std::string error=tx->amount()!=value ? "The wallet returned an unexpected payment amount." : tx->errorString();
            s->wallet->disposeTransaction(tx);throw std::runtime_error(error.empty() ? "Could not prepare the payment." : error);
        }
        uint64_t id=nextHandle.fetch_add(1);s->quotes.emplace(id,tx);
        return strings(env,{std::to_string(id),std::to_string(tx->amount()),std::to_string(tx->fee()),std::to_string(tx->txCount()),hashes(tx->txid())});
    }catch(const std::exception& error){fail(env,error.what());return nullptr;}
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_com_negzero_monogram_wallet_WalletNative_commit(JNIEnv* env,jclass,jlong handle,jlong quote) {
    try {
        auto s=session(handle);auto found=s->quotes.find(quote);if(found==s->quotes.end())throw std::runtime_error("This payment review is no longer valid.");
        auto tx=found->second;s->quotes.erase(found);const std::string ids=hashes(tx->txid());
        bool sent=false;std::string error;
        try {sent=tx->commit();if(!sent)error=tx->errorString();}catch(...) {error="The node did not confirm broadcast status.";}
        s->wallet->disposeTransaction(tx);
        bool saved=false;try{saved=s->wallet->store("");}catch(...){}
        return strings(env,{sent ? "broadcast" : "unknown",ids,saved ? "true" : "false",error});
    }catch(const std::exception& error){fail(env,error.what());return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_com_negzero_monogram_wallet_WalletNative_cancel(JNIEnv* env,jclass,jlong handle,jlong quote) {
    try {
        auto s=session(handle);auto found=s->quotes.find(quote);if(found!=s->quotes.end()){s->wallet->disposeTransaction(found->second);s->quotes.erase(found);}
    }catch(const std::exception& error){fail(env,error.what());}
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_com_negzero_monogram_wallet_WalletNative_history(JNIEnv* env,jclass,jlong handle) {
    try {
        auto s=session(handle);auto history=s->wallet->history();history->refresh();std::vector<std::string> rows;
        for(int i=history->count()-1;i>=0;i--) {
            auto tx=history->transaction(i);std::ostringstream row;
            // Engine hashes and numeric fields only; no untrusted labels are interpolated into JSON.
            row<<"{\"hash\":\""<<tx->hash()<<"\",\"amount\":\""<<tx->amount()<<"\",\"fee\":\""<<tx->fee()
               <<"\",\"direction\":"<<tx->direction()<<",\"height\":"<<tx->blockHeight()<<",\"confirmations\":"<<tx->confirmations()
               <<",\"timestamp\":"<<tx->timestamp()<<",\"pending\":"<<(tx->isPending()?"true":"false")<<",\"failed\":"<<(tx->isFailed()?"true":"false")<<"}";
            rows.push_back(row.str());
        }
        return strings(env,rows);
    }catch(const std::exception& error){fail(env,error.what());return nullptr;}
}

extern "C" JNIEXPORT jlong JNICALL Java_com_negzero_monogram_wallet_WalletNative_createClaim(JNIEnv* env,jclass,jstring path,jcharArray password,jint network,jlong height) {
    jlong handle=0;
    try {
        if(network<0 || network>2 || height<0)throw std::runtime_error("Invalid claim settings.");
        Secret pass(env,password);
        auto wallet=manager()->createWallet(string(env,path),pass.value,"English",static_cast<Monero::NetworkType>(network));
        if(!wallet)throw std::runtime_error("Could not create claim wallet.");
        handle=keep(wallet);auto s=session(handle);s->claim=true;s->wallet->setRefreshFromBlockHeight(height);
        if(!s->wallet->store(""))throw std::runtime_error("Could not save claim wallet.");
        return handle;
    }catch(...){if(handle){std::lock_guard<std::mutex> lock(registryMutex);sessions.erase(handle);}fail(env,"Could not create and save the claim wallet.");return 0;}
}
extern "C" JNIEXPORT jlong JNICALL Java_com_negzero_monogram_wallet_WalletNative_openClaim(JNIEnv* env,jclass type,jstring path,jcharArray password,jint network,jlong height) {
    if(height<0){fail(env,"Invalid claim restore height.");return 0;}
    jlong handle=Java_com_negzero_monogram_wallet_WalletNative_open(env,type,path,password,network);
    if(handle){auto s=session(handle);s->claim=true;s->wallet->setRefreshFromBlockHeight(height);}return handle;
}
extern "C" JNIEXPORT jcharArray JNICALL Java_com_negzero_monogram_wallet_WalletNative_claimSeed(JNIEnv* env,jclass,jlong handle) {
    std::string seed;
    try {
        auto s=session(handle);if(!s->claim)throw std::runtime_error("Only a dedicated claim wallet can be exported.");
        seed=s->wallet->seed();if(seed.empty() || seed.size()>400)throw std::runtime_error("Invalid claim seed.");
        std::vector<jchar> chars(seed.begin(),seed.end());jcharArray out=env->NewCharArray(chars.size());
        if(out)env->SetCharArrayRegion(out,0,chars.size(),chars.data());
        std::fill(chars.begin(),chars.end(),0);wipe(seed);return out;
    }catch(...){wipe(seed);fail(env,"Could not export this claim wallet.");return nullptr;}
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_com_negzero_monogram_wallet_WalletNative_prepareClaimSweep(JNIEnv* env,jclass,jlong handle,jstring destination) {
    try {
        auto s=session(handle);if(!s->claim || !s->connected || !s->wallet->synchronized())throw std::runtime_error("Sync the claim wallet first.");
        if(!s->quotes.empty())throw std::runtime_error("Finish the current claim review first.");
        if(s->wallet->balance(0)==0)throw std::runtime_error("No claim funds found. The payment may be pending or already claimed.");
        if(s->wallet->balance(0)!=s->wallet->unlockedBalance(0))throw std::runtime_error("Claim funds are still locked. Wait for confirmations, then sync again.");
        auto tx=s->wallet->createTransaction(string(env,destination),"",Monero::optional<uint64_t>(),0,Monero::PendingTransaction::Priority_Default,0);
        if(!tx)throw std::runtime_error("Could not prepare claim.");
        if(tx->status()!=Monero::PendingTransaction::Status_Ok || tx->amount()==0){s->wallet->disposeTransaction(tx);throw std::runtime_error("Could not prepare the claim. The balance must cover the network fee.");}
        uint64_t id=nextHandle.fetch_add(1);s->quotes.emplace(id,tx);
        return strings(env,{std::to_string(id),std::to_string(tx->amount()),std::to_string(tx->fee()),std::to_string(tx->txCount()),hashes(tx->txid())});
    }catch(const std::exception& error){fail(env,error.what());return nullptr;}
}

extern "C" JNIEXPORT jlong JNICALL Java_com_negzero_monogram_wallet_WalletNative_createWallet(JNIEnv* env,jclass type,jstring path,jcharArray password,jint network,jstring endpoint,jstring user,jstring localPassword) {
    jlong handle=0;
    try {
        if(network<0 || network>2)throw std::runtime_error("Invalid wallet network.");Secret pass(env,password);
        std::string mnemonic,seedError;
        if(!Monero::Wallet::createPolyseed(mnemonic,seedError,"English")) {
            wipe(mnemonic);wipe(seedError);throw std::runtime_error("Could not generate Polyseed.");
        }
        Monero::Wallet* wallet=nullptr;
        try {
            wallet=manager()->createWalletFromPolyseed(string(env,path),pass.value,
                static_cast<Monero::NetworkType>(network),mnemonic,"",true);
        } catch(...) {wipe(mnemonic);throw;}
        wipe(mnemonic);wipe(seedError);
        if(!wallet)throw std::runtime_error("Could not create wallet.");handle=keep(wallet,false);
        Java_com_negzero_monogram_wallet_WalletNative_connect(env,type,handle,endpoint,user,localPassword);
        if(env->ExceptionCheck()){std::lock_guard<std::mutex> lock(registryMutex);sessions.erase(handle);return 0;}
        auto s=session(handle);s->wallet->setRefreshFromBlockHeight(s->targetHeight>20 ? s->targetHeight-20 : 0);
        s->wallet->setRecoveringFromSeed(true);
        if(!s->wallet->store(""))throw std::runtime_error("Could not save the new wallet.");return handle;
    }catch(...){if(handle){std::lock_guard<std::mutex> lock(registryMutex);sessions.erase(handle);}fail(env,"Could not create and save the wallet. Check the node connection.");return 0;}
}
extern "C" JNIEXPORT jcharArray JNICALL Java_com_negzero_monogram_wallet_WalletNative_recoveryPhrase(JNIEnv* env,jclass,jlong handle) {
    std::string seed;
    try {
        auto s=session(handle);if(s->claim)throw std::runtime_error("Open the main wallet recovery screen.");
        std::string passphrase;
        const bool polyseed=s->wallet->getPolyseed(seed,passphrase);
        wipe(passphrase);
        if(!polyseed){wipe(seed);seed=s->wallet->seed();}
        if(seed.empty() || seed.size()>400)throw std::runtime_error("Recovery phrase unavailable.");
        std::vector<jchar> chars(seed.begin(),seed.end());jcharArray out=env->NewCharArray(chars.size());
        if(out)env->SetCharArrayRegion(out,0,chars.size(),chars.data());std::fill(chars.begin(),chars.end(),0);wipe(seed);return out;
    }catch(...){wipe(seed);fail(env,"Could not open this recovery phrase.");return nullptr;}
}
