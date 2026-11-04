package com.github.auties00.viridian.value;

import java.util.Objects;

/// The algorithm a secret [JsCryptoKey] is made for, as Web Crypto's `importKey` takes it: only what its raw bytes do
/// not say.
///
/// An AES key's length is the length of its bytes, and so is an HMAC key's; HMAC takes the hash it signs with,
/// through [#hmac(KeyAlgorithm.Hash)]. [JsCryptoKey#algorithm()] is the full dictionary a page reads, length
/// included.
///
/// {@snippet lang = java :
/// JsCryptoKey aes = JsCryptoKey.secret(SecretKeyAlgorithm.AES_GCM, key32).usages(JsCryptoKey.Usage.ENCRYPT).build();
/// JsCryptoKey mac = JsCryptoKey.secret(SecretKeyAlgorithm.hmac(KeyAlgorithm.Hash.SHA_256), macKey)
///         .usages(JsCryptoKey.Usage.SIGN, JsCryptoKey.Usage.VERIFY)
///         .build();
/// }
public sealed interface SecretKeyAlgorithm permits KeyImport.Secret {
    /// `AES-CBC`, of a 16- or 32-byte key.
    SecretKeyAlgorithm AES_CBC = new KeyImport.Secret(KeyImport.Kind.AES_CBC, null);

    /// `AES-CTR`, of a 16- or 32-byte key.
    SecretKeyAlgorithm AES_CTR = new KeyImport.Secret(KeyImport.Kind.AES_CTR, null);

    /// `AES-GCM`, of a 16- or 32-byte key.
    SecretKeyAlgorithm AES_GCM = new KeyImport.Secret(KeyImport.Kind.AES_GCM, null);

    /// `AES-KW`, of a 16- or 32-byte key.
    SecretKeyAlgorithm AES_KW = new KeyImport.Secret(KeyImport.Kind.AES_KW, null);

    /// `ChaCha20-Poly1305`, of a 32-byte key.
    SecretKeyAlgorithm CHACHA20_POLY1305 = new KeyImport.Secret(KeyImport.Kind.CHACHA20_POLY1305, null);

    /// `HKDF`, of input keying material of any length.
    SecretKeyAlgorithm HKDF = new KeyImport.Secret(KeyImport.Kind.HKDF, null);

    /// `PBKDF2`, of a password of any length.
    SecretKeyAlgorithm PBKDF2 = new KeyImport.Secret(KeyImport.Kind.PBKDF2, null);

    /// {@return `HMAC` with `hash`, of a key of any length but zero}
    static SecretKeyAlgorithm hmac(KeyAlgorithm.Hash hash) {
        return new KeyImport.Secret(KeyImport.Kind.HMAC, Objects.requireNonNull(hash, "hash"));
    }

    /// {@return the algorithm's name in Web Crypto, such as `AES-GCM`}
    String webCryptoName();
}
