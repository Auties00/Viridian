package com.github.auties00.viridian.value;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/// A Web Crypto `CryptoKey`, with the key material Chromium stored for it.
///
/// Chromium serializes a key's material whether or not the key is extractable: a non-extractable key cannot be
/// exported by the page, but its bytes are in the stored value all the same. A secret key's material is its raw
/// bytes, a private key's a PKCS #8 `PrivateKeyInfo`, and a public key's an X.509 `SubjectPublicKeyInfo`, all DER.
///
/// @spec https://www.w3.org/TR/WebCryptoAPI/ Web Cryptography Level 2
public final class JsCryptoKey implements JsHostObject {
    private final Type type;
    private final Algorithm algorithm;
    private final Algorithm hash;
    private final int length;
    private final NamedCurve namedCurve;
    private final byte[] publicExponent;
    private final Set<Usage> usages;
    private final boolean extractable;
    private final byte[] keyData;

    JsCryptoKey(Type type, Algorithm algorithm, Algorithm hash, int length, NamedCurve namedCurve, byte[] publicExponent,
                Set<Usage> usages, boolean extractable, byte[] keyData) {
        this.type = type;
        this.algorithm = algorithm;
        this.hash = hash;
        this.length = length;
        this.namedCurve = namedCurve;
        this.publicExponent = publicExponent;
        this.usages = usages;
        this.extractable = extractable;
        this.keyData = keyData;
    }

    /// {@return whether the key is secret, public or private}
    public Type type() {
        return type;
    }

    /// {@return the key's algorithm}
    public Algorithm algorithm() {
        return algorithm;
    }

    /// {@return the hash of an HMAC or RSA key, or an empty optional for other algorithms}
    public Optional<Algorithm> hash() {
        return Optional.ofNullable(hash);
    }

    /// {@return the length in bits of an AES or HMAC key, or the modulus length of an RSA key; empty for the others}
    public OptionalInt length() {
        return length < 0 ? OptionalInt.empty() : OptionalInt.of(length);
    }

    /// {@return the curve of an ECDSA or ECDH key, or an empty optional for other algorithms}
    public Optional<NamedCurve> namedCurve() {
        return Optional.ofNullable(namedCurve);
    }

    /// {@return a copy of the big-endian public exponent of an RSA key, or an empty optional for other algorithms}
    public Optional<byte[]> publicExponent() {
        return publicExponent == null ? Optional.empty() : Optional.of(publicExponent.clone());
    }

    /// {@return what the page may use the key for}
    public Set<Usage> usages() {
        return usages;
    }

    /// {@return whether the page may export the key}
    public boolean extractable() {
        return extractable;
    }

    /// {@return a copy of the key material, in the format [#keyDataFormat()] names}
    public byte[] keyData() {
        return keyData.clone();
    }

    /// {@return the format of [#keyData()]: raw for a secret key, PKCS #8 for a private one, SPKI for a public one}
    public KeyDataFormat keyDataFormat() {
        return switch (type) {
            case SECRET -> KeyDataFormat.RAW;
            case PRIVATE -> KeyDataFormat.PKCS8;
            case PUBLIC -> KeyDataFormat.SPKI;
        };
    }

    @Override
    public String toString() {
        return "CryptoKey(" + algorithm.webCryptoName() + ", " + type.name().toLowerCase() + ")";
    }

    /// {@return the usages a Blink usage bit mask names}
    static Set<Usage> usagesOf(int mask) {
        var usages = EnumSet.noneOf(Usage.class);
        for (var usage : Usage.values()) {
            if ((mask & usage.bit) != 0) {
                usages.add(usage);
            }
        }
        return Collections.unmodifiableSet(usages);
    }

    /// Whether a key is secret, public or private.
    public enum Type {
        /// A symmetric key.
        SECRET,
        /// The public half of a key pair.
        PUBLIC,
        /// The private half of a key pair.
        PRIVATE
    }

    /// The formats of [#keyData()].
    public enum KeyDataFormat {
        /// The raw key bytes.
        RAW,
        /// A DER PKCS #8 `PrivateKeyInfo`.
        PKCS8,
        /// A DER X.509 `SubjectPublicKeyInfo`.
        SPKI
    }

    /// The algorithms a key may be for, with the id Blink serializes for each.
    public enum Algorithm {
        /// `AES-CBC`.
        AES_CBC(1, "AES-CBC"),
        /// `HMAC`.
        HMAC(2, "HMAC"),
        /// `RSASSA-PKCS1-v1_5`.
        RSASSA_PKCS1_V1_5(3, "RSASSA-PKCS1-v1_5"),
        /// `SHA-1`, as a hash.
        SHA_1(5, "SHA-1"),
        /// `SHA-256`, as a hash.
        SHA_256(6, "SHA-256"),
        /// `SHA-384`, as a hash.
        SHA_384(7, "SHA-384"),
        /// `SHA-512`, as a hash.
        SHA_512(8, "SHA-512"),
        /// `AES-GCM`.
        AES_GCM(9, "AES-GCM"),
        /// `RSA-OAEP`.
        RSA_OAEP(10, "RSA-OAEP"),
        /// `AES-CTR`.
        AES_CTR(11, "AES-CTR"),
        /// `AES-KW`.
        AES_KW(12, "AES-KW"),
        /// `RSA-PSS`.
        RSA_PSS(13, "RSA-PSS"),
        /// `ECDSA`.
        ECDSA(14, "ECDSA"),
        /// `ECDH`.
        ECDH(15, "ECDH"),
        /// `HKDF`.
        HKDF(16, "HKDF"),
        /// `PBKDF2`.
        PBKDF2(17, "PBKDF2"),
        /// `Ed25519`.
        ED25519(18, "Ed25519"),
        /// `X25519`.
        X25519(19, "X25519"),
        /// `ChaCha20-Poly1305`.
        CHACHA20_POLY1305(20, "ChaCha20-Poly1305"),
        /// `ML-DSA-44`.
        ML_DSA_44(21, "ML-DSA-44"),
        /// `ML-DSA-65`.
        ML_DSA_65(22, "ML-DSA-65"),
        /// `ML-DSA-87`.
        ML_DSA_87(23, "ML-DSA-87"),
        /// `ML-KEM-768`.
        ML_KEM_768(24, "ML-KEM-768"),
        /// `ML-KEM-1024`.
        ML_KEM_1024(25, "ML-KEM-1024"),
        /// The hybrid of ML-KEM-768 and X25519.
        ML_KEM_768_X25519(26, "X25519MLKEM768");

        private final int id;
        private final String webCryptoName;

        Algorithm(int id, String webCryptoName) {
            this.id = id;
            this.webCryptoName = webCryptoName;
        }

        /// {@return the id Blink serializes for the algorithm}
        public int id() {
            return id;
        }

        /// {@return the algorithm's name in the Web Crypto API}
        public String webCryptoName() {
            return webCryptoName;
        }

        /// {@return the algorithm of Blink id `id`, or an empty optional for an id it does not know}
        static Optional<Algorithm> ofId(int id) {
            for (var algorithm : values()) {
                if (algorithm.id == id) {
                    return Optional.of(algorithm);
                }
            }
            return Optional.empty();
        }
    }

    /// The elliptic curves of ECDSA and ECDH keys.
    public enum NamedCurve {
        /// `P-256`.
        P_256(1, "P-256"),
        /// `P-384`.
        P_384(2, "P-384"),
        /// `P-521`.
        P_521(3, "P-521");

        private final int id;
        private final String webCryptoName;

        NamedCurve(int id, String webCryptoName) {
            this.id = id;
            this.webCryptoName = webCryptoName;
        }

        /// {@return the id Blink serializes for the curve}
        public int id() {
            return id;
        }

        /// {@return the curve's name in the Web Crypto API}
        public String webCryptoName() {
            return webCryptoName;
        }

        /// {@return the curve of Blink id `id`, or an empty optional for an id it does not know}
        static Optional<NamedCurve> ofId(int id) {
            for (var curve : values()) {
                if (curve.id == id) {
                    return Optional.of(curve);
                }
            }
            return Optional.empty();
        }
    }

    /// What a key may be used for, with the bit Blink serializes for each.
    public enum Usage {
        /// `encrypt`.
        ENCRYPT(1 << 1, "encrypt"),
        /// `decrypt`.
        DECRYPT(1 << 2, "decrypt"),
        /// `sign`.
        SIGN(1 << 3, "sign"),
        /// `verify`.
        VERIFY(1 << 4, "verify"),
        /// `deriveKey`.
        DERIVE_KEY(1 << 5, "deriveKey"),
        /// `wrapKey`.
        WRAP_KEY(1 << 6, "wrapKey"),
        /// `unwrapKey`.
        UNWRAP_KEY(1 << 7, "unwrapKey"),
        /// `deriveBits`.
        DERIVE_BITS(1 << 8, "deriveBits"),
        /// `encapsulateKey`.
        ENCAPSULATE_KEY(1 << 9, "encapsulateKey"),
        /// `encapsulateBits`.
        ENCAPSULATE_BITS(1 << 10, "encapsulateBits"),
        /// `decapsulateKey`.
        DECAPSULATE_KEY(1 << 11, "decapsulateKey"),
        /// `decapsulateBits`.
        DECAPSULATE_BITS(1 << 12, "decapsulateBits");

        // The bit of the extractable flag, which shares the mask with the usages
        static final int EXTRACTABLE_BIT = 1;
        // Every bit Blink defines, the extractable flag included
        static final int KNOWN_BITS = (1 << 13) - 1;

        private final int bit;
        private final String webCryptoName;

        Usage(int bit, String webCryptoName) {
            this.bit = bit;
            this.webCryptoName = webCryptoName;
        }

        /// {@return the usage's name in the Web Crypto API}
        public String webCryptoName() {
            return webCryptoName;
        }
    }
}
