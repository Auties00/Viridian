package com.github.auties00.viridian.value;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/// A Web Crypto `CryptoKey`, with the key material Chromium stored for it.
///
/// Chromium serializes a key's material whether or not the key is extractable: a non-extractable key cannot be
/// exported by the page, but its bytes are in the stored value all the same. A secret key's material is its raw
/// bytes, a private key's a PKCS #8 `PrivateKeyInfo`, and a public key's an X.509 `SubjectPublicKeyInfo`, all DER;
/// the halves of an `X25519MLKEM768` pair are raw, its private key a seed.
///
/// A key is made from its material and what the material does not say, as `importKey` takes them: a
/// [SecretKeyAlgorithm] for [#secret(SecretKeyAlgorithm, byte\[\])], a [KeyPairAlgorithm] for
/// [#publicKey(KeyPairAlgorithm, byte\[\])] and [#privateKey(KeyPairAlgorithm, byte\[\])], or a `java.security`
/// key of a pair. The key's length, curve, modulus length and public exponent come from the material, and
/// [#algorithm()] holds them. Building accepts what Chromium accepts when it reads a stored key back: key material of
/// a length the algorithm has for AES and ChaCha20-Poly1305 keys, a `SubjectPublicKeyInfo` or `PrivateKeyInfo` of the
/// algorithm for the others, and the usages Web Crypto lets a key of that type have, at least one for a secret or
/// private key. It does not check that the key material is a valid key beyond that.
///
/// {@snippet lang = java :
/// JsCryptoKey aes = JsCryptoKey.secret(SecretKeyAlgorithm.AES_GCM, rawKey)
///         .usages(JsCryptoKey.Usage.ENCRYPT, JsCryptoKey.Usage.DECRYPT)
///         .build();
/// KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
/// JsCryptoKey signing = JsCryptoKey.privateKey(KeyPairAlgorithm.ED25519, pair.getPrivate())
///         .usages(JsCryptoKey.Usage.SIGN)
///         .extractable(true)
///         .build();
/// SecretKey forJava = aes.toSecretKey();
/// }
///
/// @spec https://www.w3.org/TR/WebCryptoAPI/ Web Cryptography Level 2
public final class JsCryptoKey implements JsHostObject {
    // The longest HMAC key a stored value holds, in bytes, which keeps its length in bits within an int
    private static final int MAX_HMAC_BYTES = 1 << 28;

    private final Type type;
    private final KeyAlgorithm algorithm;
    private final Set<Usage> usages;
    private final boolean extractable;
    private final byte[] keyData;

    JsCryptoKey(Type type, KeyAlgorithm algorithm, Set<Usage> usages, boolean extractable, byte[] keyData) {
        this.type = type;
        this.algorithm = algorithm;
        this.usages = usages;
        this.extractable = extractable;
        this.keyData = keyData;
    }

    /// {@return a builder of a secret key of `algorithm`, whose raw bytes are `keyData`, copied}
    ///
    /// An AES key is 16 or 32 bytes (Chromium refuses 192-bit AES keys), a ChaCha20-Poly1305 key 32, an HMAC key any
    /// length but zero, and an HKDF or PBKDF2 key any length.
    public static Builder secret(SecretKeyAlgorithm algorithm, byte[] keyData) {
        var secret = (KeyImport.Secret) Objects.requireNonNull(algorithm, "algorithm");
        return new Builder(Type.SECRET, secret.kind(), secret.hash(), keyData.clone());
    }

    /// {@return a builder of a public key of `algorithm`, whose material is the DER `SubjectPublicKeyInfo` `keyData`,
    /// or for `X25519MLKEM768` the raw public key, copied}
    public static Builder publicKey(KeyPairAlgorithm algorithm, byte[] keyData) {
        var pair = (KeyImport.Pair) Objects.requireNonNull(algorithm, "algorithm");
        return new Builder(Type.PUBLIC, pair.kind(), pair.hash(), keyData.clone());
    }

    /// {@return a builder of a private key of `algorithm`, whose material is the DER `PrivateKeyInfo` `keyData`, or
    /// for `X25519MLKEM768` the private seed, copied}
    public static Builder privateKey(KeyPairAlgorithm algorithm, byte[] keyData) {
        var pair = (KeyImport.Pair) Objects.requireNonNull(algorithm, "algorithm");
        return new Builder(Type.PRIVATE, pair.kind(), pair.hash(), keyData.clone());
    }

    /// {@return a builder of a public key of `algorithm`, whose material is `key`'s X.509 encoding}
    ///
    /// @throws IllegalArgumentException if `key` has no X.509 encoding, or `algorithm` is `X25519MLKEM768`, which
    ///                                  Java has no keys of
    public static Builder publicKey(KeyPairAlgorithm algorithm, PublicKey key) {
        return publicKey(algorithm, encoded(algorithm, key, "X.509"));
    }

    /// {@return a builder of a private key of `algorithm`, whose material is `key`'s PKCS #8 encoding}
    ///
    /// @throws IllegalArgumentException if `key` has no PKCS #8 encoding, or `algorithm` is `X25519MLKEM768`, which
    ///                                  Java has no keys of
    public static Builder privateKey(KeyPairAlgorithm algorithm, PrivateKey key) {
        return privateKey(algorithm, encoded(algorithm, key, "PKCS#8"));
    }

    private static byte[] encoded(KeyPairAlgorithm algorithm, Key key, String format) {
        Objects.requireNonNull(key, "key");
        if (((KeyImport.Pair) Objects.requireNonNull(algorithm, "algorithm")).kind() == KeyImport.Kind.X25519_MLKEM768) {
            throw new IllegalArgumentException("Java has no X25519MLKEM768 keys: pass the key's bytes");
        }
        var encoded = key.getEncoded();
        if (encoded == null || !format.equals(key.getFormat())) {
            throw new IllegalArgumentException("The " + key.getAlgorithm() + " key has no " + format + " encoding, but " + key.getFormat());
        }
        return encoded;
    }

    /// {@return whether the key is secret, public or private}
    public Type type() {
        return type;
    }

    /// {@return the key's algorithm, with its parameters}
    public KeyAlgorithm algorithm() {
        return algorithm;
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

    /// {@return the format of [#keyData()]: raw for a secret key, PKCS #8 for a private one, SPKI for a public one,
    /// and raw for either half of an `X25519MLKEM768` pair}
    public KeyDataFormat keyDataFormat() {
        if (algorithm instanceof KeyAlgorithm.Basic(var name) && name == KeyAlgorithm.Basic.Name.X25519_MLKEM768) {
            return KeyDataFormat.RAW;
        }
        return switch (type) {
            case SECRET -> KeyDataFormat.RAW;
            case PRIVATE -> KeyDataFormat.PKCS8;
            case PUBLIC -> KeyDataFormat.SPKI;
        };
    }

    /// {@return the key as a `javax.crypto` key of its raw bytes, named as the JDK names its algorithm: `AES`,
    /// `HmacSHA256` and the other HMAC hashes, `ChaCha20`, `HKDF` or `PBKDF2`}
    ///
    /// @throws IllegalStateException if the key is not secret
    public SecretKey toSecretKey() {
        if (type != Type.SECRET) {
            throw new IllegalStateException("A " + type.name().toLowerCase() + " key is not secret");
        }
        var name = switch (algorithm) {
            case KeyAlgorithm.Aes _ -> "AES";
            case KeyAlgorithm.Hmac hmac -> "Hmac" + hmac.hash().webCryptoName().replace("-", "");
            case KeyAlgorithm.Basic(var basic) -> switch (basic) {
                case CHACHA20_POLY1305 -> "ChaCha20";
                case HKDF -> "HKDF";
                case PBKDF2 -> "PBKDF2";
                default -> throw new IllegalStateException(basic.webCryptoName() + " keys are not secret");
            };
            case KeyAlgorithm.RsaHashed _, KeyAlgorithm.Ec _ -> throw new IllegalStateException(algorithm.webCryptoName() + " keys are not secret");
        };
        return new SecretKeySpec(keyData, name);
    }

    /// {@return the key as a `java.security` public key, decoded from its `SubjectPublicKeyInfo` by the JDK's key
    /// factory for its algorithm}
    ///
    /// @throws IllegalStateException    if the key is not public
    /// @throws GeneralSecurityException if the JDK has no key factory for the algorithm, as for `X25519MLKEM768`, or
    ///                                  its factory refuses the key material
    public PublicKey toPublicKey() throws GeneralSecurityException {
        if (type != Type.PUBLIC) {
            throw new IllegalStateException("A " + type.name().toLowerCase() + " key is not public");
        }
        return KeyFactory.getInstance(jcaKeyFactory()).generatePublic(new X509EncodedKeySpec(keyData));
    }

    /// {@return the key as a `java.security` private key, decoded from its `PrivateKeyInfo` by the JDK's key factory
    /// for its algorithm}
    ///
    /// @throws IllegalStateException    if the key is not private
    /// @throws GeneralSecurityException if the JDK has no key factory for the algorithm, as for `X25519MLKEM768`, or
    ///                                  its factory refuses the key material
    public PrivateKey toPrivateKey() throws GeneralSecurityException {
        if (type != Type.PRIVATE) {
            throw new IllegalStateException("A " + type.name().toLowerCase() + " key is not private");
        }
        return KeyFactory.getInstance(jcaKeyFactory()).generatePrivate(new PKCS8EncodedKeySpec(keyData));
    }

    /// {@return the name of the JDK key factory for the key's algorithm}
    private String jcaKeyFactory() throws NoSuchAlgorithmException {
        return switch (algorithm) {
            case KeyAlgorithm.RsaHashed _ -> "RSA";
            case KeyAlgorithm.Ec _ -> "EC";
            case KeyAlgorithm.Basic(var name) -> switch (name) {
                case ED25519 -> "Ed25519";
                case X25519 -> "X25519";
                case ML_DSA_44, ML_DSA_65, ML_DSA_87 -> "ML-DSA";
                case ML_KEM_768, ML_KEM_1024 -> "ML-KEM";
                default -> throw new NoSuchAlgorithmException("The JDK has no " + name.webCryptoName() + " keys");
            };
            case KeyAlgorithm.Aes _, KeyAlgorithm.Hmac _ -> throw new NoSuchAlgorithmException(algorithm.webCryptoName() + " keys are secret");
        };
    }

    /// {@return the key material itself}
    byte[] rawKeyData() {
        return keyData;
    }

    /// {@return the Blink usage bit mask of the key's usages and extractability}
    int usageMask() {
        var mask = extractable ? Usage.EXTRACTABLE_BIT : 0;
        for (var usage : usages) {
            mask |= usage.bit;
        }
        return mask;
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

    @Override
    public String toString() {
        return "CryptoKey(" + algorithm.webCryptoName() + ", " + type.name().toLowerCase() + ")";
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
        /// The raw key bytes, or the private seed of an `X25519MLKEM768` key.
        RAW,
        /// A DER PKCS #8 `PrivateKeyInfo`.
        PKCS8,
        /// A DER X.509 `SubjectPublicKeyInfo`.
        SPKI
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

    /// A builder of a [JsCryptoKey]: its usages, none until given, and whether it is extractable, which it is not
    /// until told.
    ///
    /// The builder builds one key: [#build()] returns the same instance again, and a change after it throws
    /// [IllegalStateException].
    public static final class Builder {
        private final Type type;
        private final KeyImport.Kind kind;
        private final KeyAlgorithm.Hash hash;
        private final byte[] keyData;
        private EnumSet<Usage> usages;
        private boolean extractable;
        private JsCryptoKey built;

        private Builder(Type type, KeyImport.Kind kind, KeyAlgorithm.Hash hash, byte[] keyData) {
            this.type = type;
            this.kind = kind;
            this.hash = hash;
            this.keyData = keyData;
            this.usages = EnumSet.noneOf(Usage.class);
        }

        /// Sets the usages.
        ///
        /// @return this builder
        /// @throws IllegalStateException if the builder already built its key
        public Builder usages(Usage... usages) {
            return usages(Arrays.asList(usages));
        }

        /// Sets the usages.
        ///
        /// @return this builder
        /// @throws IllegalStateException if the builder already built its key
        public Builder usages(Collection<Usage> usages) {
            checkOpen();
            var copy = EnumSet.noneOf(Usage.class);
            for (var usage : usages) {
                copy.add(Objects.requireNonNull(usage, "usage"));
            }
            this.usages = copy;
            return this;
        }

        /// Sets whether the page may export the key.
        ///
        /// @return this builder
        /// @throws IllegalStateException if the builder already built its key
        public Builder extractable(boolean extractable) {
            checkOpen();
            this.extractable = extractable;
            return this;
        }

        /// Builds the key.
        ///
        /// @return the key, the same instance on every call
        /// @throws IllegalArgumentException if the key material does not fit the algorithm, a usage is not one the key
        ///                                  may have, or a secret or private key has no usage
        public JsCryptoKey build() {
            if (built == null) {
                var algorithm = algorithm();
                checkUsages(algorithm);
                built = new JsCryptoKey(type, algorithm, Collections.unmodifiableSet(usages), extractable, keyData);
            }
            return built;
        }

        private void checkOpen() {
            if (built != null) {
                throw new IllegalStateException("The builder already built its key, which cannot change");
            }
        }

        /// {@return the key's algorithm dictionary, its parameters read from the key material, checked as Chromium
        /// checks a stored key it reads back}
        private KeyAlgorithm algorithm() {
            return switch (kind) {
                case AES_CBC, AES_CTR, AES_GCM, AES_KW -> {
                    if (keyData.length != 16 && keyData.length != 32) {
                        throw new IllegalArgumentException("An AES key is 16 or 32 bytes, not " + keyData.length);
                    }
                    yield new KeyAlgorithm.Aes(KeyAlgorithm.Aes.Name.valueOf(kind.name()), keyData.length * 8);
                }
                case HMAC -> {
                    if (keyData.length == 0 || keyData.length > MAX_HMAC_BYTES) {
                        throw new IllegalArgumentException("An HMAC key cannot be " + keyData.length + " bytes");
                    }
                    yield new KeyAlgorithm.Hmac(hash, keyData.length * 8);
                }
                case CHACHA20_POLY1305 -> {
                    if (keyData.length != 32) {
                        throw new IllegalArgumentException("A ChaCha20-Poly1305 key is 32 bytes, not " + keyData.length);
                    }
                    yield new KeyAlgorithm.Basic(KeyAlgorithm.Basic.Name.CHACHA20_POLY1305);
                }
                case HKDF -> new KeyAlgorithm.Basic(KeyAlgorithm.Basic.Name.HKDF);
                case PBKDF2 -> new KeyAlgorithm.Basic(KeyAlgorithm.Basic.Name.PBKDF2);
                case ECDSA, ECDH -> {
                    requireAsymmetric();
                    yield new KeyAlgorithm.Ec(KeyAlgorithm.Ec.Name.valueOf(kind.name()), CryptoKeyMaterial.curve(type, keyData));
                }
                case RSASSA_PKCS1_V1_5, RSA_PSS, RSA_OAEP -> {
                    requireAsymmetric();
                    var parameters = CryptoKeyMaterial.rsa(type, keyData);
                    yield new KeyAlgorithm.RsaHashed(KeyAlgorithm.RsaHashed.Name.valueOf(kind.name()), parameters.modulusLength(),
                            parameters.publicExponent(), hash);
                }
                case ED25519 -> basic(KeyAlgorithm.Basic.Name.ED25519, "1.3.101.112");
                case X25519 -> basic(KeyAlgorithm.Basic.Name.X25519, "1.3.101.110");
                case ML_DSA_44 -> basic(KeyAlgorithm.Basic.Name.ML_DSA_44, "2.16.840.1.101.3.4.3.17");
                case ML_DSA_65 -> basic(KeyAlgorithm.Basic.Name.ML_DSA_65, "2.16.840.1.101.3.4.3.18");
                case ML_DSA_87 -> basic(KeyAlgorithm.Basic.Name.ML_DSA_87, "2.16.840.1.101.3.4.3.19");
                case ML_KEM_768 -> basic(KeyAlgorithm.Basic.Name.ML_KEM_768, "2.16.840.1.101.3.4.4.2");
                case ML_KEM_1024 -> basic(KeyAlgorithm.Basic.Name.ML_KEM_1024, "2.16.840.1.101.3.4.4.3");
                case X25519_MLKEM768 -> {
                    requireAsymmetric();
                    if (keyData.length == 0) {
                        throw new IllegalArgumentException("An X25519MLKEM768 key cannot be empty");
                    }
                    yield new KeyAlgorithm.Basic(KeyAlgorithm.Basic.Name.X25519_MLKEM768);
                }
            };
        }

        private KeyAlgorithm basic(KeyAlgorithm.Basic.Name name, String oid) {
            requireAsymmetric();
            CryptoKeyMaterial.requireAlgorithm(type, keyData, oid);
            return new KeyAlgorithm.Basic(name);
        }

        private void requireAsymmetric() {
            if (type == Type.SECRET) {
                throw new IllegalArgumentException(kind.webCryptoName + " keys are public or private, not secret");
            }
        }

        /// Checks the usages against those keys of the algorithm and type may have, as Chromium's Web Crypto allows
        /// them.
        private void checkUsages(KeyAlgorithm algorithm) {
            var isPublic = type == Type.PUBLIC;
            var allowed = switch (algorithm) {
                case KeyAlgorithm.Aes aes -> aes.name() == KeyAlgorithm.Aes.Name.AES_KW ? EnumSet.of(Usage.WRAP_KEY, Usage.UNWRAP_KEY)
                        : EnumSet.of(Usage.ENCRYPT, Usage.DECRYPT, Usage.WRAP_KEY, Usage.UNWRAP_KEY);
                case KeyAlgorithm.Hmac _ -> EnumSet.of(Usage.SIGN, Usage.VERIFY);
                case KeyAlgorithm.RsaHashed rsa -> rsa.name() != KeyAlgorithm.RsaHashed.Name.RSA_OAEP ? EnumSet.of(isPublic ? Usage.VERIFY : Usage.SIGN)
                        : isPublic ? EnumSet.of(Usage.ENCRYPT, Usage.WRAP_KEY) : EnumSet.of(Usage.DECRYPT, Usage.UNWRAP_KEY);
                case KeyAlgorithm.Ec ec -> ec.name() == KeyAlgorithm.Ec.Name.ECDSA ? EnumSet.of(isPublic ? Usage.VERIFY : Usage.SIGN)
                        : isPublic ? EnumSet.noneOf(Usage.class) : EnumSet.of(Usage.DERIVE_KEY, Usage.DERIVE_BITS);
                case KeyAlgorithm.Basic(var name) -> switch (name) {
                    case HKDF, PBKDF2 -> EnumSet.of(Usage.DERIVE_KEY, Usage.DERIVE_BITS);
                    case CHACHA20_POLY1305 -> EnumSet.of(Usage.ENCRYPT, Usage.DECRYPT, Usage.WRAP_KEY, Usage.UNWRAP_KEY);
                    case ED25519, ML_DSA_44, ML_DSA_65, ML_DSA_87 -> EnumSet.of(isPublic ? Usage.VERIFY : Usage.SIGN);
                    case X25519 -> isPublic ? EnumSet.noneOf(Usage.class) : EnumSet.of(Usage.DERIVE_KEY, Usage.DERIVE_BITS);
                    case ML_KEM_768, ML_KEM_1024, X25519_MLKEM768 -> isPublic ? EnumSet.of(Usage.ENCAPSULATE_KEY, Usage.ENCAPSULATE_BITS)
                            : EnumSet.of(Usage.DECAPSULATE_KEY, Usage.DECAPSULATE_BITS);
                };
            };
            if (!allowed.containsAll(usages)) {
                var refused = EnumSet.copyOf(usages);
                refused.removeAll(allowed);
                throw new IllegalArgumentException("A " + type.name().toLowerCase() + " " + algorithm.webCryptoName()
                        + " key cannot be used to " + refused.stream().map(Usage::webCryptoName).toList());
            }
            if (usages.isEmpty() && type != Type.PUBLIC) {
                throw new IllegalArgumentException("A " + type.name().toLowerCase() + " " + algorithm.webCryptoName()
                        + " key needs a usage, as Web Crypto makes none without one");
            }
        }
    }
}
