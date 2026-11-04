package com.github.auties00.viridian.value;

import java.util.Objects;

/// The algorithm a public or private [JsCryptoKey] is made for, as Web Crypto's `importKey` takes it: only what its key
/// material does not say.
///
/// An elliptic-curve key's curve and an RSA key's modulus length and public exponent are in its
/// `SubjectPublicKeyInfo` or `PrivateKeyInfo`; RSA takes the hash it is used with, through
/// [#rsaPss(KeyAlgorithm.Hash)] and its siblings. [JsCryptoKey#algorithm()] is the full dictionary a page reads.
///
/// {@snippet lang = java :
/// KeyPair pair = KeyPairGenerator.getInstance("EC").generateKeyPair();
/// JsCryptoKey signing = JsCryptoKey.privateKey(KeyPairAlgorithm.ECDSA, pair.getPrivate())   // P-256, from the key
///         .usages(JsCryptoKey.Usage.SIGN)
///         .build();
/// }
public sealed interface KeyPairAlgorithm permits KeyImport.Pair {
    /// `ECDSA`, on P-256, P-384 or P-521.
    KeyPairAlgorithm ECDSA = new KeyImport.Pair(KeyImport.Kind.ECDSA, null);

    /// `ECDH`, on P-256, P-384 or P-521.
    KeyPairAlgorithm ECDH = new KeyImport.Pair(KeyImport.Kind.ECDH, null);

    /// `Ed25519`.
    KeyPairAlgorithm ED25519 = new KeyImport.Pair(KeyImport.Kind.ED25519, null);

    /// `X25519`.
    KeyPairAlgorithm X25519 = new KeyImport.Pair(KeyImport.Kind.X25519, null);

    /// `ML-DSA-44`.
    KeyPairAlgorithm ML_DSA_44 = new KeyImport.Pair(KeyImport.Kind.ML_DSA_44, null);

    /// `ML-DSA-65`.
    KeyPairAlgorithm ML_DSA_65 = new KeyImport.Pair(KeyImport.Kind.ML_DSA_65, null);

    /// `ML-DSA-87`.
    KeyPairAlgorithm ML_DSA_87 = new KeyImport.Pair(KeyImport.Kind.ML_DSA_87, null);

    /// `ML-KEM-768`.
    KeyPairAlgorithm ML_KEM_768 = new KeyImport.Pair(KeyImport.Kind.ML_KEM_768, null);

    /// `ML-KEM-1024`.
    KeyPairAlgorithm ML_KEM_1024 = new KeyImport.Pair(KeyImport.Kind.ML_KEM_1024, null);

    /// `X25519MLKEM768`, the hybrid of ML-KEM-768 and X25519, whose public key is raw and whose private key is a seed.
    KeyPairAlgorithm X25519_MLKEM768 = new KeyImport.Pair(KeyImport.Kind.X25519_MLKEM768, null);

    /// {@return `RSASSA-PKCS1-v1_5` with `hash`}
    static KeyPairAlgorithm rsassaPkcs1v15(KeyAlgorithm.Hash hash) {
        return new KeyImport.Pair(KeyImport.Kind.RSASSA_PKCS1_V1_5, Objects.requireNonNull(hash, "hash"));
    }

    /// {@return `RSA-PSS` with `hash`}
    static KeyPairAlgorithm rsaPss(KeyAlgorithm.Hash hash) {
        return new KeyImport.Pair(KeyImport.Kind.RSA_PSS, Objects.requireNonNull(hash, "hash"));
    }

    /// {@return `RSA-OAEP` with `hash`}
    static KeyPairAlgorithm rsaOaep(KeyAlgorithm.Hash hash) {
        return new KeyImport.Pair(KeyImport.Kind.RSA_OAEP, Objects.requireNonNull(hash, "hash"));
    }

    /// {@return the algorithm's name in Web Crypto, such as `ECDSA`}
    String webCryptoName();
}
