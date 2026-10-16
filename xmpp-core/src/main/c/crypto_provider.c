// SPDX-License-Identifier: GPL-3.0-or-later
#include <limits.h>
#include <openssl/crypto.h>
#include <openssl/evp.h>
#include <openssl/hmac.h>
#include <openssl/rand.h>
#include "signal_protocol.h"

static int random_bytes(uint8_t *out, size_t len, void *unused) {
    return len <= INT_MAX && RAND_bytes(out, (int)len) == 1 ? 0 : SG_ERR_UNKNOWN;
}
static int hmac_init(void **out, const uint8_t *key, size_t len, void *unused) {
    HMAC_CTX *ctx = HMAC_CTX_new();
    if (!ctx) return SG_ERR_NOMEM;
    if (len > INT_MAX || HMAC_Init_ex(ctx, key, (int)len, EVP_sha256(), NULL) != 1) {
        HMAC_CTX_free(ctx); return SG_ERR_UNKNOWN;
    }
    *out = ctx; return 0;
}
static int hmac_update(void *ctx, const uint8_t *bytes, size_t len, void *unused) {
    return HMAC_Update(ctx, bytes, len) == 1 ? 0 : SG_ERR_UNKNOWN;
}
static int hmac_final(void *ctx, signal_buffer **out, void *unused) {
    *out = NULL;
    unsigned char bytes[EVP_MAX_MD_SIZE]; unsigned int len = 0;
    int ok = HMAC_Final(ctx, bytes, &len);
    if (ok == 1) *out = signal_buffer_create(bytes, len);
    OPENSSL_cleanse(bytes, sizeof(bytes));
    return ok != 1 ? SG_ERR_UNKNOWN : *out ? 0 : SG_ERR_NOMEM;
}
static void hmac_free(void *ctx, void *unused) { HMAC_CTX_free(ctx); }
static int sha_init(void **out, void *unused) {
    EVP_MD_CTX *ctx = EVP_MD_CTX_new();
    if (!ctx) return SG_ERR_NOMEM;
    if (EVP_DigestInit_ex(ctx, EVP_sha512(), NULL) != 1) { EVP_MD_CTX_free(ctx); return SG_ERR_UNKNOWN; }
    *out = ctx; return 0;
}
static int sha_update(void *ctx, const uint8_t *bytes, size_t len, void *unused) {
    return EVP_DigestUpdate(ctx, bytes, len) == 1 ? 0 : SG_ERR_UNKNOWN;
}
static int sha_final(void *ctx, signal_buffer **out, void *unused) {
    *out = NULL;
    unsigned char bytes[EVP_MAX_MD_SIZE]; unsigned int len = 0;
    int ok = EVP_DigestFinal_ex(ctx, bytes, &len);
    if (ok == 1) *out = signal_buffer_create(bytes, len);
    OPENSSL_cleanse(bytes, sizeof(bytes));
    if (ok != 1 || EVP_DigestInit_ex(ctx, EVP_sha512(), NULL) != 1) { signal_buffer_bzero_free(*out); *out = NULL; return SG_ERR_UNKNOWN; }
    return *out ? 0 : SG_ERR_NOMEM;
}
static void sha_free(void *ctx, void *unused) { EVP_MD_CTX_free(ctx); }
static int aes(signal_buffer **out, int cipher, const uint8_t *key, size_t key_len,
        const uint8_t *iv, size_t iv_len, const uint8_t *input, size_t length, int encrypt) {
    *out = NULL;
    const EVP_CIPHER *algorithm = NULL;
    if (iv_len != 16 || length > INT_MAX - EVP_MAX_BLOCK_LENGTH) return SG_ERR_INVAL;
    if (cipher == SG_CIPHER_AES_CTR_NOPADDING) {
        if (key_len == 16) algorithm = EVP_aes_128_ctr();
        if (key_len == 32) algorithm = EVP_aes_256_ctr();
    } else if (cipher == SG_CIPHER_AES_CBC_PKCS5) {
        if (key_len == 16) algorithm = EVP_aes_128_cbc();
        if (key_len == 32) algorithm = EVP_aes_256_cbc();
    }
    if (!algorithm) return SG_ERR_INVAL;
    EVP_CIPHER_CTX *ctx = EVP_CIPHER_CTX_new();
    signal_buffer *buffer = signal_buffer_alloc(length + EVP_MAX_BLOCK_LENGTH);
    if (!ctx || !buffer) { EVP_CIPHER_CTX_free(ctx); signal_buffer_bzero_free(buffer); return SG_ERR_NOMEM; }
    int written = 0, last = 0;
    int ok = EVP_CipherInit_ex(ctx, algorithm, NULL, key, iv, encrypt) == 1
        && EVP_CIPHER_CTX_set_padding(ctx, cipher == SG_CIPHER_AES_CBC_PKCS5) == 1
        && EVP_CipherUpdate(ctx, signal_buffer_data(buffer), &written, input, (int)length) == 1
        && EVP_CipherFinal_ex(ctx, signal_buffer_data(buffer) + written, &last) == 1;
    EVP_CIPHER_CTX_free(ctx);
    if (!ok) { signal_buffer_bzero_free(buffer); return SG_ERR_INVALID_MESSAGE; }
    *out = signal_buffer_create(signal_buffer_data(buffer), (size_t)(written + last));
    signal_buffer_bzero_free(buffer); return *out ? 0 : SG_ERR_NOMEM;
}
static int encrypt(signal_buffer **out, int cipher, const uint8_t *key, size_t key_len,
        const uint8_t *iv, size_t iv_len, const uint8_t *input, size_t length, void *unused) {
    return aes(out, cipher, key, key_len, iv, iv_len, input, length, 1);
}
static int decrypt(signal_buffer **out, int cipher, const uint8_t *key, size_t key_len,
        const uint8_t *iv, size_t iv_len, const uint8_t *input, size_t length, void *unused) {
    return aes(out, cipher, key, key_len, iv, iv_len, input, length, 0);
}
int monogram_crypto(signal_context *ctx) {
    signal_crypto_provider provider = {
        random_bytes, hmac_init, hmac_update, hmac_final, hmac_free,
        sha_init, sha_update, sha_final, sha_free, encrypt, decrypt, NULL
    };
    return signal_context_set_crypto_provider(ctx, &provider);
}
