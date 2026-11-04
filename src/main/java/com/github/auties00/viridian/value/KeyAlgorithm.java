package com.github.auties00.viridian.value;

import java.math.BigInteger;
import java.util.Objects;

/// The algorithm of a [JsCryptoKey] with its parameters, as Web Crypto describes it in a key's `algorithm`: one record
/// for each of its key algorithm dictionaries.
///
/// This is what a key holds, read from its material where the material says it, such as an AES key's length or an
/// elliptic-curve key's curve. A key is made from a [SecretKeyAlgorithm] or [KeyPairAlgorithm] instead, which take only
/// what the material does not say.
///
/// {@snippet lang = java :
/// switch (key.algorithm()) {
///     case KeyAlgorithm.Aes(var name, var length) -> System.out.println(name + " of " + length + " bits");
///     case KeyAlgorithm.Hmac(var hash, var length) -> System.out.println("HMAC with " + hash);
///     case KeyAlgorithm.RsaHashed rsa -> System.out.println(rsa.name() + " of " + rsa.modulusLength() + " bits");
///     case KeyAlgorithm.Ec(var name, var curve) -> System.out.println(name + " on " + curve);
///     case KeyAlgorithm.Basic(var name) -> System.out.println(name);
/// }
/// }
///
/// @spec https://www.w3.org/TR/WebCryptoAPI/ Web Cryptography Level 2
public sealed interface KeyAlgorithm permits KeyAlgorithm.Aes, KeyAlgorithm.Hmac, KeyAlgorithm.RsaHashed, KeyAlgorithm.Ec,
        KeyAlgorithm.Basic {
    /// {@return the algorithm's name in Web Crypto, such as `AES-GCM`}
    String webCryptoName();

    /// The algorithm of an AES key, `AesKeyAlgorithm`.
    ///
    /// @param name   the AES mode
    /// @param length the length of the key in bits
    record Aes(Name name, int length) implements KeyAlgorithm {
        /// Creates an AES key's algorithm.
        ///
        /// @throws IllegalArgumentException if the length is not positive
        public Aes {
            Objects.requireNonNull(name, "name");
            if (length <= 0) {
                throw new IllegalArgumentException("An AES key cannot be " + length + " bits");
            }
        }

        @Override
        public String webCryptoName() {
            return name.webCryptoName;
        }

        /// The AES modes, with the id Blink serializes for each.
        public enum Name {
            /// `AES-CBC`.
            AES_CBC("AES-CBC", 1),
            /// `AES-GCM`.
            AES_GCM("AES-GCM", 9),
            /// `AES-CTR`.
            AES_CTR("AES-CTR", 11),
            /// `AES-KW`.
            AES_KW("AES-KW", 12);

            private final String webCryptoName;
            final int blinkId;

            Name(String webCryptoName, int blinkId) {
                this.webCryptoName = webCryptoName;
                this.blinkId = blinkId;
            }

            /// {@return the mode's name in Web Crypto}
            public String webCryptoName() {
                return webCryptoName;
            }
        }
    }

    /// The algorithm of an HMAC key, `HmacKeyAlgorithm`.
    ///
    /// @param hash   the hash the key signs with
    /// @param length the length of the key in bits
    record Hmac(Hash hash, int length) implements KeyAlgorithm {
        /// Creates an HMAC key's algorithm.
        ///
        /// @throws IllegalArgumentException if the length is not positive
        public Hmac {
            Objects.requireNonNull(hash, "hash");
            if (length <= 0) {
                throw new IllegalArgumentException("An HMAC key cannot be " + length + " bits");
            }
        }

        @Override
        public String webCryptoName() {
            return "HMAC";
        }
    }

    /// The algorithm of an RSA key, `RsaHashedKeyAlgorithm`.
    ///
    /// @param name           the RSA scheme
    /// @param modulusLength  the length of the modulus in bits
    /// @param publicExponent the public exponent
    /// @param hash           the hash the key is used with
    record RsaHashed(Name name, int modulusLength, BigInteger publicExponent, Hash hash) implements KeyAlgorithm {
        /// Creates an RSA key's algorithm.
        ///
        /// @throws IllegalArgumentException if the modulus length or the exponent is not positive
        public RsaHashed {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(publicExponent, "publicExponent");
            Objects.requireNonNull(hash, "hash");
            if (modulusLength <= 0 || publicExponent.signum() <= 0) {
                throw new IllegalArgumentException("An RSA key needs a positive modulus length and exponent");
            }
        }

        @Override
        public String webCryptoName() {
            return name.webCryptoName;
        }

        /// The RSA schemes, with the id Blink serializes for each.
        public enum Name {
            /// `RSASSA-PKCS1-v1_5`.
            RSASSA_PKCS1_V1_5("RSASSA-PKCS1-v1_5", 3),
            /// `RSA-PSS`.
            RSA_PSS("RSA-PSS", 13),
            /// `RSA-OAEP`.
            RSA_OAEP("RSA-OAEP", 10);

            private final String webCryptoName;
            final int blinkId;

            Name(String webCryptoName, int blinkId) {
                this.webCryptoName = webCryptoName;
                this.blinkId = blinkId;
            }

            /// {@return the scheme's name in Web Crypto}
            public String webCryptoName() {
                return webCryptoName;
            }
        }
    }

    /// The algorithm of an elliptic-curve key, `EcKeyAlgorithm`.
    ///
    /// @param name       the algorithm
    /// @param namedCurve the curve
    record Ec(Name name, NamedCurve namedCurve) implements KeyAlgorithm {
        /// Creates an elliptic-curve key's algorithm.
        public Ec {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(namedCurve, "namedCurve");
        }

        @Override
        public String webCryptoName() {
            return name.webCryptoName;
        }

        /// The elliptic-curve algorithms, with the id Blink serializes for each.
        public enum Name {
            /// `ECDSA`.
            ECDSA("ECDSA", 14),
            /// `ECDH`.
            ECDH("ECDH", 15);

            private final String webCryptoName;
            final int blinkId;

            Name(String webCryptoName, int blinkId) {
                this.webCryptoName = webCryptoName;
                this.blinkId = blinkId;
            }

            /// {@return the algorithm's name in Web Crypto}
            public String webCryptoName() {
                return webCryptoName;
            }
        }
    }

    /// The algorithm of a key whose algorithm has no parameters, the base `KeyAlgorithm` dictionary.
    ///
    /// @param name the algorithm
    record Basic(Name name) implements KeyAlgorithm {
        /// Creates the algorithm of a key without parameters.
        public Basic {
            Objects.requireNonNull(name, "name");
        }

        @Override
        public String webCryptoName() {
            return name.webCryptoName;
        }

        /// The algorithms without parameters, with the id Blink serializes for each.
        public enum Name {
            /// `HKDF`, whose keys are secret.
            HKDF("HKDF", 16),
            /// `PBKDF2`, whose keys are secret.
            PBKDF2("PBKDF2", 17),
            /// `Ed25519`.
            ED25519("Ed25519", 18),
            /// `X25519`.
            X25519("X25519", 19),
            /// `ChaCha20-Poly1305`, whose keys are secret.
            CHACHA20_POLY1305("ChaCha20-Poly1305", 20),
            /// `ML-DSA-44`.
            ML_DSA_44("ML-DSA-44", 21),
            /// `ML-DSA-65`.
            ML_DSA_65("ML-DSA-65", 22),
            /// `ML-DSA-87`.
            ML_DSA_87("ML-DSA-87", 23),
            /// `ML-KEM-768`.
            ML_KEM_768("ML-KEM-768", 24),
            /// `ML-KEM-1024`.
            ML_KEM_1024("ML-KEM-1024", 25),
            /// `X25519MLKEM768`, the hybrid of ML-KEM-768 and X25519.
            X25519_MLKEM768("X25519MLKEM768", 26);

            private final String webCryptoName;
            final int blinkId;

            Name(String webCryptoName, int blinkId) {
                this.webCryptoName = webCryptoName;
                this.blinkId = blinkId;
            }

            /// {@return the algorithm's name in Web Crypto}
            public String webCryptoName() {
                return webCryptoName;
            }
        }
    }

    /// The hashes of HMAC and RSA keys, with the id Blink serializes for each.
    enum Hash {
        /// `SHA-1`.
        SHA_1("SHA-1", 5),
        /// `SHA-256`.
        SHA_256("SHA-256", 6),
        /// `SHA-384`.
        SHA_384("SHA-384", 7),
        /// `SHA-512`.
        SHA_512("SHA-512", 8);

        private final String webCryptoName;
        final int blinkId;

        Hash(String webCryptoName, int blinkId) {
            this.webCryptoName = webCryptoName;
            this.blinkId = blinkId;
        }

        /// {@return the hash's name in Web Crypto}
        public String webCryptoName() {
            return webCryptoName;
        }
    }

    /// The curves of elliptic-curve keys, with the id Blink serializes for each.
    enum NamedCurve {
        /// `P-256`.
        P_256("P-256", 1),
        /// `P-384`.
        P_384("P-384", 2),
        /// `P-521`.
        P_521("P-521", 3);

        private final String webCryptoName;
        final int blinkId;

        NamedCurve(String webCryptoName, int blinkId) {
            this.webCryptoName = webCryptoName;
            this.blinkId = blinkId;
        }

        /// {@return the curve's name in Web Crypto}
        public String webCryptoName() {
            return webCryptoName;
        }
    }
}
