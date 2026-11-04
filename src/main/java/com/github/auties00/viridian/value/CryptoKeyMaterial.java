package com.github.auties00.viridian.value;

import java.math.BigInteger;
import java.util.Arrays;

/// Checks the DER key material of a public or private [JsCryptoKey] against its algorithm, as Chromium checks it
/// when it reads a stored key back: the algorithm identifier of the `SubjectPublicKeyInfo` or `PrivateKeyInfo`, the
/// curve of an elliptic-curve key, and the modulus and public exponent of an RSA key.
///
/// @spec https://www.rfc-editor.org/rfc/rfc5280#section-4.1.2.7 SubjectPublicKeyInfo
/// @spec https://www.rfc-editor.org/rfc/rfc5208#section-5 PrivateKeyInfo
/// @spec https://www.rfc-editor.org/rfc/rfc8017#appendix-A.1 RSA key syntax
final class CryptoKeyMaterial {
    private static final int INTEGER = 0x02;
    private static final int BIT_STRING = 0x03;
    private static final int OCTET_STRING = 0x04;
    private static final int OBJECT_IDENTIFIER = 0x06;
    private static final int SEQUENCE = 0x30;

    private static final String RSA_ENCRYPTION = "1.2.840.113549.1.1.1";
    private static final String EC_PUBLIC_KEY = "1.2.840.10045.2.1";

    private CryptoKeyMaterial() {
    }

    /// The modulus length and public exponent of an RSA key.
    ///
    /// @param modulusLength  the length of the modulus in bits
    /// @param publicExponent the public exponent
    record RsaParameters(int modulusLength, BigInteger publicExponent) {
    }

    /// {@return the modulus length and public exponent of the RSA key `der` holds}
    ///
    /// @throws IllegalArgumentException if `der` is not an RSA `SubjectPublicKeyInfo`, for a public key, or
    ///                                  `PrivateKeyInfo`, for a private one
    static RsaParameters rsa(JsCryptoKey.Type type, byte[] der) {
        var key = keyOf(type, der, RSA_ENCRYPTION);
        var sequence = key.sequence();
        if (type == JsCryptoKey.Type.PRIVATE) {
            sequence.integer();
        }
        var modulus = sequence.integer();
        var exponent = sequence.integer();
        if (modulus.signum() <= 0 || exponent.signum() <= 0) {
            throw invalid("An RSA key has a modulus or exponent that is not positive");
        }
        return new RsaParameters(modulus.bitLength(), exponent);
    }

    /// {@return the curve of the elliptic-curve key `der` holds}
    ///
    /// @throws IllegalArgumentException if `der` is not an elliptic-curve key of a curve Web Crypto knows
    static KeyAlgorithm.NamedCurve curve(JsCryptoKey.Type type, byte[] der) {
        var algorithm = algorithmOf(type, der);
        if (!algorithm.oid.equals(EC_PUBLIC_KEY) || algorithm.parameters == null) {
            throw invalid("The key data is not an elliptic-curve key with a named curve, but " + algorithm.oid);
        }
        var curve = algorithm.parameters.objectIdentifier();
        return switch (curve) {
            case "1.2.840.10045.3.1.7" -> KeyAlgorithm.NamedCurve.P_256;
            case "1.3.132.0.34" -> KeyAlgorithm.NamedCurve.P_384;
            case "1.3.132.0.35" -> KeyAlgorithm.NamedCurve.P_521;
            default -> throw invalid("The key is on curve " + curve + ", which Web Crypto does not know");
        };
    }

    /// Checks that `der` is a key of the algorithm `oid` names.
    ///
    /// @throws IllegalArgumentException if it is not
    static void requireAlgorithm(JsCryptoKey.Type type, byte[] der, String oid) {
        var algorithm = algorithmOf(type, der);
        if (!algorithm.oid.equals(oid)) {
            throw invalid("The key data is for algorithm " + algorithm.oid + ", not " + oid);
        }
    }

    private record AlgorithmIdentifier(String oid, Der parameters) {
    }

    /// {@return the algorithm identifier of a `SubjectPublicKeyInfo` or `PrivateKeyInfo`}
    private static AlgorithmIdentifier algorithmOf(JsCryptoKey.Type type, byte[] der) {
        var info = info(type, der);
        var identifier = info.sequence();
        var oid = identifier.objectIdentifier();
        return new AlgorithmIdentifier(oid, identifier.hasRemaining() ? identifier : null);
    }

    /// {@return the key inside a `SubjectPublicKeyInfo` or `PrivateKeyInfo` of the algorithm `oid` names}
    private static Der keyOf(JsCryptoKey.Type type, byte[] der, String oid) {
        var info = info(type, der);
        var identifier = info.sequence();
        var algorithm = identifier.objectIdentifier();
        if (!algorithm.equals(oid)) {
            throw invalid("The key data is for algorithm " + algorithm + ", not " + oid);
        }
        if (type == JsCryptoKey.Type.PUBLIC) {
            var bits = info.read(BIT_STRING);
            if (bits.length == 0 || bits[0] != 0) {
                throw invalid("The public key is not a whole number of bytes");
            }
            return new Der(bits, 1, bits.length);
        }
        var octets = info.read(OCTET_STRING);
        return new Der(octets, 0, octets.length);
    }

    /// {@return the fields of a `SubjectPublicKeyInfo`, for a public key, or `PrivateKeyInfo`, for a private one, its
    /// version read}
    private static Der info(JsCryptoKey.Type type, byte[] der) {
        var outer = new Der(der, 0, der.length);
        var info = outer.sequence();
        if (outer.hasRemaining()) {
            throw invalid("The key data has bytes past its end");
        }
        if (type == JsCryptoKey.Type.PRIVATE) {
            var version = info.integer();
            if (version.signum() < 0 || version.compareTo(BigInteger.ONE) > 0) {
                throw invalid("The private key has version " + version);
            }
        }
        return info;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    /// A cursor over DER elements.
    private static final class Der {
        private final byte[] data;
        private final int end;
        private int position;

        Der(byte[] data, int start, int end) {
            this.data = data;
            this.position = start;
            this.end = end;
        }

        boolean hasRemaining() {
            return position < end;
        }

        /// {@return the contents of the next element, which must have tag `tag`}
        byte[] read(int tag) {
            var start = contents(tag);
            var bytes = Arrays.copyOfRange(data, start, position);
            return bytes;
        }

        /// {@return a cursor over the elements of the next element, a sequence}
        Der sequence() {
            var start = contents(SEQUENCE);
            return new Der(data, start, position);
        }

        BigInteger integer() {
            var bytes = read(INTEGER);
            if (bytes.length == 0) {
                throw invalid("The key data has an empty integer");
            }
            return new BigInteger(bytes);
        }

        String objectIdentifier() {
            var bytes = read(OBJECT_IDENTIFIER);
            if (bytes.length == 0) {
                throw invalid("The key data has an empty object identifier");
            }
            var oid = new StringBuilder();
            long value = 0;
            var first = true;
            for (var i = 0; i < bytes.length; i++) {
                if (value > Long.MAX_VALUE >>> 7) {
                    throw invalid("The key data has an object identifier too long to read");
                }
                value = value << 7 | (bytes[i] & 0x7F);
                if ((bytes[i] & 0x80) != 0) {
                    if (i == bytes.length - 1) {
                        throw invalid("The key data has a truncated object identifier");
                    }
                    continue;
                }
                if (first) {
                    var arc = value < 80 ? value / 40 : 2;
                    oid.append(arc).append('.').append(value - arc * 40);
                    first = false;
                } else {
                    oid.append('.').append(value);
                }
                value = 0;
            }
            return oid.toString();
        }

        /// {@return where the contents of the next element start, past which the cursor then is}
        private int contents(int tag) {
            if (position >= end || (data[position] & 0xFF) != tag) {
                throw invalid("The key data is not the DER structure its type calls for");
            }
            position++;
            if (position >= end) {
                throw invalid("The key data is truncated");
            }
            var first = data[position++] & 0xFF;
            long length;
            if (first < 0x80) {
                length = first;
            } else {
                var count = first & 0x7F;
                if (count == 0 || count > 4) {
                    throw invalid("The key data has an element of unsupported length");
                }
                length = 0;
                for (var i = 0; i < count; i++) {
                    if (position >= end) {
                        throw invalid("The key data is truncated");
                    }
                    length = length << 8 | (data[position++] & 0xFF);
                }
            }
            if (length > end - position) {
                throw invalid("The key data is truncated");
            }
            var start = position;
            position += (int) length;
            return start;
        }
    }
}
