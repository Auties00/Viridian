package com.github.auties00.viridian.value;

/// The implementations of [SecretKeyAlgorithm] and [KeyPairAlgorithm]: an algorithm, and the hash of one that takes
/// a hash.
final class KeyImport {
    private KeyImport() {
    }

    /// The algorithms a key can be made for, and the name each has in Web Crypto.
    enum Kind {
        AES_CBC("AES-CBC"),
        AES_CTR("AES-CTR"),
        AES_GCM("AES-GCM"),
        AES_KW("AES-KW"),
        HMAC("HMAC"),
        CHACHA20_POLY1305("ChaCha20-Poly1305"),
        HKDF("HKDF"),
        PBKDF2("PBKDF2"),
        ECDSA("ECDSA"),
        ECDH("ECDH"),
        ED25519("Ed25519"),
        X25519("X25519"),
        ML_DSA_44("ML-DSA-44"),
        ML_DSA_65("ML-DSA-65"),
        ML_DSA_87("ML-DSA-87"),
        ML_KEM_768("ML-KEM-768"),
        ML_KEM_1024("ML-KEM-1024"),
        X25519_MLKEM768("X25519MLKEM768"),
        RSASSA_PKCS1_V1_5("RSASSA-PKCS1-v1_5"),
        RSA_PSS("RSA-PSS"),
        RSA_OAEP("RSA-OAEP");

        final String webCryptoName;

        Kind(String webCryptoName) {
            this.webCryptoName = webCryptoName;
        }
    }

    /// A secret key's algorithm.
    ///
    /// @param hash the hash of an HMAC key, `null` for the others
    record Secret(Kind kind, KeyAlgorithm.Hash hash) implements SecretKeyAlgorithm {
        @Override
        public String webCryptoName() {
            return kind.webCryptoName;
        }

        @Override
        public String toString() {
            return hash == null ? kind.webCryptoName : kind.webCryptoName + "(" + hash.webCryptoName() + ")";
        }
    }

    /// A key pair's algorithm.
    ///
    /// @param hash the hash of an RSA key, `null` for the others
    record Pair(Kind kind, KeyAlgorithm.Hash hash) implements KeyPairAlgorithm {
        @Override
        public String webCryptoName() {
            return kind.webCryptoName;
        }

        @Override
        public String toString() {
            return hash == null ? kind.webCryptoName : kind.webCryptoName + "(" + hash.webCryptoName() + ")";
        }
    }
}
