// SPDX-License-Identifier: GPL-3.0-or-later
#include <jni.h>
#include <openssl/crypto.h>
#include <climits>
#include <cstring>
#include <string>
#include <vector>
#include "signal_protocol.h"
#include "curve.h"
#include "ratchet.h"
#include "key_helper.h"
#include "session_builder.h"
#include "session_cipher.h"
#include "protocol.h"

extern "C" int monogram_crypto(signal_context *ctx);
namespace {
enum Kind { IDENTITY, PRE_KEY, SIGNED_PRE_KEY, SESSION, SESSION_IDENTITY, TRUSTED };
struct Error { int code; };
void check(int result) { if (result < 0) throw Error{result}; }
void require(bool valid) { if (!valid) throw Error{SG_ERR_INVAL}; }
struct Buffer {
    signal_buffer *p = nullptr;
    ~Buffer() { signal_buffer_bzero_free(p); }
    Buffer() = default; Buffer(const Buffer &) = delete;
};
template<typename T> struct Ref {
    T *p = nullptr;
    ~Ref() { if (p) signal_type_unref(reinterpret_cast<signal_type_base *>(p)); }
    Ref() = default; Ref(const Ref &) = delete;
};
struct Bytes : std::vector<uint8_t> {
    Bytes(JNIEnv *env, jbyteArray bytes) {
        require(bytes != nullptr);
        jsize length = env->GetArrayLength(bytes); require(length > 0 && length <= 1024 * 1024);
        resize(length); env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte *>(data()));
        if (env->ExceptionCheck()) { OPENSSL_cleanse(data(), size()); throw Error{SG_ERR_UNKNOWN}; }
    }
    ~Bytes() { OPENSSL_cleanse(data(), size()); }
};
jbyteArray array(JNIEnv *env, const uint8_t *data, size_t len) {
    require(len <= INT_MAX);
    jbyteArray result = env->NewByteArray(static_cast<jsize>(len));
    if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(len), reinterpret_cast<const jbyte *>(data));
    return result;
}
jbyteArray array(JNIEnv *env, signal_buffer *buffer) { return array(env, signal_buffer_data(buffer), signal_buffer_len(buffer)); }
jobjectArray arrays(JNIEnv *env, std::initializer_list<signal_buffer *> buffers) {
    jclass bytes = env->FindClass("[B"); if (!bytes) return nullptr;
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(buffers.size()), bytes, nullptr); env->DeleteLocalRef(bytes);
    if (!result) return nullptr;
    int index = 0;
    for (auto buffer : buffers) {
        jbyteArray value = array(env, buffer); if (!value) return nullptr;
        env->SetObjectArrayElement(result, index++, value); env->DeleteLocalRef(value);
        if (env->ExceptionCheck()) return nullptr;
    }
    return result;
}
void failure(JNIEnv *env, int code) {
    if (env->ExceptionCheck()) return;
    jclass cls = env->FindClass("com/negzero/monogram/xmpp/Omemo2Native$Failure"); if (!cls) return;
    jmethodID constructor = env->GetMethodID(cls, "<init>", "(I)V"); if (!constructor) { env->DeleteLocalRef(cls); return; }
    auto exception = static_cast<jthrowable>(env->NewObject(cls, constructor, code));
    if (exception) { env->Throw(exception); env->DeleteLocalRef(exception); } env->DeleteLocalRef(cls);
}
template<typename T, typename F> T operation(JNIEnv *env, F body) {
    try { return body(); }
    catch (const Error &error) { failure(env, error.code); }
    catch (...) { failure(env, SG_ERR_UNKNOWN); }
    return T{};
}

struct Context {
    JNIEnv *env;
    signal_context *global = nullptr;
    signal_protocol_store_context *store = nullptr;
    jobject javaStore = nullptr;
    jclass storeClass = nullptr;
    jstring empty = nullptr, peer = nullptr;
    jmethodID load = nullptr, save = nullptr;
    uint32_t localDevice = 0;
    explicit Context(JNIEnv *env) : env(env) {}
    ~Context() {
        if (store) signal_protocol_store_context_destroy(store);
        if (global) signal_context_destroy(global);
        if (empty) env->DeleteLocalRef(empty);
        if (storeClass) env->DeleteLocalRef(storeClass);
    }
    int read(int kind, int id, signal_buffer **out) {
        *out = nullptr; if (env->ExceptionCheck()) return SG_ERR_UNKNOWN;
        auto value = static_cast<jbyteArray>(env->CallObjectMethod(javaStore, load, kind, kind >= SESSION ? peer : empty, id));
        if (env->ExceptionCheck()) return SG_ERR_UNKNOWN;
        if (!value) return 0;
        jsize len = env->GetArrayLength(value);
        if (len <= 0 || len > 1024 * 1024) { env->DeleteLocalRef(value); return SG_ERR_INVAL; }
        *out = signal_buffer_alloc(len);
        if (*out) env->GetByteArrayRegion(value, 0, len, reinterpret_cast<jbyte *>(signal_buffer_data(*out)));
        env->DeleteLocalRef(value);
        return env->ExceptionCheck() ? SG_ERR_UNKNOWN : *out ? 1 : SG_ERR_NOMEM;
    }
    int write(int kind, int id, const uint8_t *data, size_t len) {
        if (env->ExceptionCheck() || len > 1024 * 1024) return SG_ERR_UNKNOWN;
        jbyteArray value = data ? array(env, data, len) : nullptr;
        if (env->ExceptionCheck()) return SG_ERR_UNKNOWN;
        env->CallVoidMethod(javaStore, save, kind, kind >= SESSION ? peer : empty, id, value);
        if (value) env->DeleteLocalRef(value);
        return env->ExceptionCheck() ? SG_ERR_UNKNOWN : 0;
    }
    void init(jobject records = nullptr, int device = 0, jstring remote = nullptr);
};
Context *context(void *data) { return static_cast<Context *>(data); }
int loadSession(signal_buffer **record, signal_buffer **user, const signal_protocol_address *address, void *data) {
    *user = nullptr; return context(data)->read(SESSION, address->device_id, record);
}
int saveSession(const signal_protocol_address *address, uint8_t *record, size_t len, uint8_t *, size_t, void *data) {
    return context(data)->write(SESSION, address->device_id, record, len);
}
int containsSession(const signal_protocol_address *address, void *data) {
    Buffer record; return context(data)->read(SESSION, address->device_id, &record.p);
}
int deleteSession(const signal_protocol_address *address, void *data) { return context(data)->write(SESSION, address->device_id, nullptr, 0); }
int noDevices(signal_int_list **, const char *, size_t, void *) { return SG_ERR_INVAL; }
int noDeleteAll(const char *, size_t, void *) { return SG_ERR_INVAL; }
int loadPre(signal_buffer **record, uint32_t id, void *data) {
    int result = context(data)->read(PRE_KEY, static_cast<int>(id), record); return result == 0 ? SG_ERR_INVALID_KEY_ID : result < 0 ? result : 0;
}
int savePre(uint32_t id, uint8_t *record, size_t len, void *data) { return context(data)->write(PRE_KEY, static_cast<int>(id), record, len); }
int containsPre(uint32_t id, void *data) { Buffer record; return context(data)->read(PRE_KEY, static_cast<int>(id), &record.p); }
int removePre(uint32_t id, void *data) { return context(data)->write(PRE_KEY, static_cast<int>(id), nullptr, 0); }
int loadSigned(signal_buffer **record, uint32_t id, void *data) {
    int result = context(data)->read(SIGNED_PRE_KEY, static_cast<int>(id), record); return result == 0 ? SG_ERR_INVALID_KEY_ID : result < 0 ? result : 0;
}
int saveSigned(uint32_t id, uint8_t *record, size_t len, void *data) { return context(data)->write(SIGNED_PRE_KEY, static_cast<int>(id), record, len); }
int containsSigned(uint32_t id, void *data) { Buffer record; return context(data)->read(SIGNED_PRE_KEY, static_cast<int>(id), &record.p); }
int removeSigned(uint32_t id, void *data) { return context(data)->write(SIGNED_PRE_KEY, static_cast<int>(id), nullptr, 0); }
int identityPair(signal_buffer **publicKey, signal_buffer **privateKey, void *data) {
    Context *ctx = context(data); Buffer record; Ref<ratchet_identity_key_pair> pair;
    int result = ctx->read(IDENTITY, 0, &record.p); if (result <= 0) return result < 0 ? result : SG_ERR_INVALID_KEY;
    result = ratchet_identity_key_pair_deserialize(&pair.p, signal_buffer_data(record.p), signal_buffer_len(record.p), ctx->global);
    if (result < 0) return result;
    result = ec_public_key_serialize(publicKey, ratchet_identity_key_pair_get_public(pair.p)); if (result < 0) return result;
    return ec_private_key_serialize(privateKey, ratchet_identity_key_pair_get_private(pair.p));
}
int registration(void *data, uint32_t *id) { *id = context(data)->localDevice; return 0; }
int trusted(const signal_protocol_address *address, uint8_t *key, size_t len, void *data) {
    Context *ctx = context(data); Buffer pin, mont; Ref<ec_public_key> identity;
    int result = ctx->read(TRUSTED, address->device_id, &pin.p); if (result <= 0) return result;
    if (signal_buffer_len(pin.p) != 32) return SG_ERR_INVAL;
    result = curve_decode_point_ed(&identity.p, signal_buffer_data(pin.p), signal_buffer_len(pin.p), ctx->global); if (result < 0) return result;
    result = ec_public_key_serialize(&mont.p, identity.p); if (result < 0) return result;
    return signal_buffer_len(mont.p) == len && CRYPTO_memcmp(signal_buffer_data(mont.p), key, len) == 0;
}
int observe(const signal_protocol_address *address, uint8_t *key, size_t len, void *data) {
    int result = trusted(address, key, len, data); return result == 1 ? 0 : result < 0 ? result : SG_ERR_UNTRUSTED_IDENTITY;
}
void Context::init(jobject records, int device, jstring remote) {
    check(signal_context_create(&global, nullptr)); check(monogram_crypto(global));
    if (!records) { require(device == 0 && remote == nullptr); return; }
    require(device > 0 && remote != nullptr); localDevice = device; javaStore = records; peer = remote;
    storeClass = env->GetObjectClass(records); if (!storeClass) throw Error{SG_ERR_UNKNOWN};
    empty = env->NewStringUTF(""); if (!empty) throw Error{SG_ERR_UNKNOWN};
    load = env->GetMethodID(storeClass, "load", "(ILjava/lang/String;I)[B");
    if (!load) throw Error{SG_ERR_UNKNOWN};
    save = env->GetMethodID(storeClass, "save", "(ILjava/lang/String;I[B)V");
    if (env->ExceptionCheck()) throw Error{SG_ERR_UNKNOWN};
    check(signal_protocol_store_context_create(&store, global));
    signal_protocol_session_store sessions = {loadSession, noDevices, saveSession, containsSession, deleteSession, noDeleteAll, nullptr, this};
    signal_protocol_pre_key_store prekeys = {loadPre, savePre, containsPre, removePre, nullptr, this};
    signal_protocol_signed_pre_key_store signedKeys = {loadSigned, saveSigned, containsSigned, removeSigned, nullptr, this};
    signal_protocol_identity_key_store identities = {identityPair, registration, observe, trusted, nullptr, this};
    check(signal_protocol_store_context_set_session_store(store, &sessions));
    check(signal_protocol_store_context_set_pre_key_store(store, &prekeys));
    check(signal_protocol_store_context_set_signed_pre_key_store(store, &signedKeys));
    check(signal_protocol_store_context_set_identity_key_store(store, &identities));
}
struct Address {
    std::string name;
    signal_protocol_address value;
    Address(JNIEnv *env, jstring peer, int device) {
        require(peer != nullptr && device > 0);
        const char *text = env->GetStringUTFChars(peer, nullptr); if (!text) throw Error{SG_ERR_UNKNOWN};
        try { name = text; }
        catch (...) { env->ReleaseStringUTFChars(peer, text); throw; }
        env->ReleaseStringUTFChars(peer, text); require(!name.empty() && name.size() <= 4096);
        value = {name.c_str(), name.size(), device};
    }
};
void pinned(Context &ctx, int device, const uint8_t *key, size_t len) {
    Buffer pin; check(ctx.read(TRUSTED, device, &pin.p));
    if (!pin.p || len != 32 || signal_buffer_len(pin.p) != len || CRYPTO_memcmp(signal_buffer_data(pin.p), key, len)) throw Error{SG_ERR_UNTRUSTED_IDENTITY};
}
void existingIdentity(Context &ctx, int device) {
    Buffer identity; check(ctx.read(SESSION_IDENTITY, device, &identity.p));
    if (!identity.p) throw Error{SG_ERR_UNTRUSTED_IDENTITY};
    pinned(ctx, device, signal_buffer_data(identity.p), signal_buffer_len(identity.p));
}
}

extern "C" JNIEXPORT jobjectArray JNICALL Java_com_negzero_monogram_xmpp_Omemo2Native_identity(JNIEnv *env, jclass) {
    return operation<jobjectArray>(env, [&] {
        Context ctx(env); ctx.init(); Ref<ratchet_identity_key_pair> pair; Buffer record, publicKey;
        check(signal_protocol_key_helper_generate_identity_key_pair(&pair.p, ctx.global));
        check(ratchet_identity_key_pair_serialize(&record.p, pair.p)); publicKey.p = ec_public_key_get_ed(ratchet_identity_key_pair_get_public(pair.p)); require(publicKey.p != nullptr);
        return arrays(env, {record.p, publicKey.p});
    });
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_com_negzero_monogram_xmpp_Omemo2Native_preKey(JNIEnv *env, jclass, jint id) {
    return operation<jobjectArray>(env, [&] {
        require(id > 0); Context ctx(env); ctx.init(); Ref<ec_key_pair> pair; Ref<session_pre_key> key; Buffer record, publicKey;
        check(curve_generate_key_pair(ctx.global, &pair.p)); check(session_pre_key_create(&key.p, id, pair.p));
        check(session_pre_key_serialize(&record.p, key.p)); check(ec_public_key_serialize_omemo(&publicKey.p, ec_key_pair_get_public(pair.p)));
        return arrays(env, {record.p, publicKey.p});
    });
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_com_negzero_monogram_xmpp_Omemo2Native_signedPreKey(JNIEnv *env, jclass, jbyteArray identity, jint id, jlong time) {
    return operation<jobjectArray>(env, [&] {
        require(id > 0 && time >= 0); Context ctx(env); ctx.init(); Bytes bytes(env, identity); Ref<ratchet_identity_key_pair> pair; Ref<session_signed_pre_key> key; Buffer record, publicKey, signature;
        check(ratchet_identity_key_pair_deserialize(&pair.p, bytes.data(), bytes.size(), ctx.global));
        check(signal_protocol_key_helper_generate_signed_pre_key(&key.p, pair.p, id, static_cast<uint64_t>(time), ctx.global));
        check(session_signed_pre_key_serialize(&record.p, key.p)); check(ec_public_key_serialize_omemo(&publicKey.p, ec_key_pair_get_public(session_signed_pre_key_get_key_pair(key.p))));
        signature.p = signal_buffer_create(session_signed_pre_key_get_signature_omemo(key.p), session_signed_pre_key_get_signature_omemo_len(key.p)); require(signature.p != nullptr);
        return arrays(env, {record.p, publicKey.p, signature.p});
    });
}
extern "C" JNIEXPORT void JNICALL Java_com_negzero_monogram_xmpp_Omemo2Native_build(JNIEnv *env, jclass, jobject store, jint local, jstring peer, jint device,
        jlong preId, jbyteArray pre, jlong signedId, jbyteArray signedKey, jbyteArray signature, jbyteArray identity) {
    operation<jobject>(env, [&]() -> jobject {
        require(preId >= 0 && preId <= UINT32_MAX && signedId >= 0 && signedId <= UINT32_MAX); Context ctx(env); ctx.init(store, local, peer); Address address(env, peer, device);
        Bytes p(env, pre), s(env, signedKey), sig(env, signature), ik(env, identity);
        require(p.size() == 32 && s.size() == 32 && sig.size() == 64 && ik.size() == 32); pinned(ctx, device, ik.data(), ik.size());
        require((sig[63] & 0xe0) == 0);
        Ref<ec_public_key> prePublic, signedPublic, identityPublic; Ref<session_pre_key_bundle> bundle;
        check(curve_decode_point_mont(&prePublic.p, p.data(), p.size(), ctx.global));
        check(curve_decode_point_mont(&signedPublic.p, s.data(), s.size(), ctx.global));
        check(curve_decode_point_ed(&identityPublic.p, ik.data(), ik.size(), ctx.global));
        check(session_pre_key_bundle_create(&bundle.p, device, device, static_cast<uint32_t>(preId), prePublic.p, static_cast<uint32_t>(signedId), signedPublic.p, sig.data(), sig.size(), identityPublic.p));
        session_builder *builder = nullptr; check(session_builder_create(&builder, ctx.store, &address.value, ctx.global));
        session_builder_set_version(builder, CIPHERTEXT_OMEMO_VERSION);
        int result = session_builder_process_pre_key_bundle(builder, bundle.p); session_builder_free(builder); check(result);
        check(ctx.write(SESSION_IDENTITY, device, ik.data(), ik.size())); return nullptr;
    });
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_com_negzero_monogram_xmpp_Omemo2Native_encrypt(JNIEnv *env, jclass, jobject store, jint local, jstring peer, jint device, jbyteArray plaintext) {
    return operation<jobjectArray>(env, [&] {
        Context ctx(env); ctx.init(store, local, peer); Address address(env, peer, device); existingIdentity(ctx, device);
        Bytes bytes(env, plaintext); Ref<ciphertext_message> encrypted; session_cipher *cipher = nullptr;
        check(session_cipher_create(&cipher, ctx.store, &address.value, ctx.global)); session_cipher_set_version(cipher, CIPHERTEXT_OMEMO_VERSION);
        int result = session_cipher_encrypt(cipher, bytes.data(), bytes.size(), &encrypted.p); session_cipher_free(cipher); check(result);
        uint8_t flag = ciphertext_message_get_type(encrypted.p) == CIPHERTEXT_PREKEY_TYPE; Buffer preKey;
        preKey.p = signal_buffer_create(&flag, 1); require(preKey.p != nullptr);
        return arrays(env, {preKey.p, ciphertext_message_get_serialized(encrypted.p)});
    });
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_negzero_monogram_xmpp_Omemo2Native_decrypt(JNIEnv *env, jclass, jobject store, jint local, jstring peer, jint device, jbyteArray ciphertext, jboolean preKey) {
    return operation<jbyteArray>(env, [&] {
        Context ctx(env); ctx.init(store, local, peer); Address address(env, peer, device); Bytes bytes(env, ciphertext); Buffer plaintext, identity;
        Ref<pre_key_signal_message> first; Ref<signal_message> message;
        if (preKey) {
            check(pre_key_signal_message_deserialize_omemo(&first.p, bytes.data(), bytes.size(), device, ctx.global));
            identity.p = ec_public_key_get_ed(pre_key_signal_message_get_identity_key(first.p)); require(identity.p != nullptr);
            pinned(ctx, device, signal_buffer_data(identity.p), signal_buffer_len(identity.p));
        } else { existingIdentity(ctx, device); check(signal_message_deserialize_omemo(&message.p, bytes.data(), bytes.size(), ctx.global)); }
        session_cipher *cipher = nullptr; check(session_cipher_create(&cipher, ctx.store, &address.value, ctx.global)); session_cipher_set_version(cipher, CIPHERTEXT_OMEMO_VERSION);
        int result = preKey ? session_cipher_decrypt_pre_key_signal_message(cipher, first.p, nullptr, &plaintext.p)
                : session_cipher_decrypt_signal_message(cipher, message.p, nullptr, &plaintext.p);
        session_cipher_free(cipher); check(result);
        if (identity.p) check(ctx.write(SESSION_IDENTITY, device, signal_buffer_data(identity.p), signal_buffer_len(identity.p)));
        return array(env, plaintext.p);
    });
}
