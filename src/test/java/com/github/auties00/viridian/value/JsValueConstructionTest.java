package com.github.auties00.viridian.value;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The public construction and reading of values: builders, cycles and sharing among immutable values, JavaScript's
/// semantics for the containers, the typed accessors, and the factories refusing what Chrome would refuse to read
/// back. Key pairs come from the JDK, whose encodings Chrome's are.
class JsValueConstructionTest {
    private static KeyPair rsa;
    private static KeyPair ec;
    private static KeyPair ed25519;

    @BeforeAll
    static void keys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        rsa = generator.generateKeyPair();
        var curves = KeyPairGenerator.getInstance("EC");
        curves.initialize(new ECGenParameterSpec("secp384r1"));
        ec = curves.generateKeyPair();
        ed25519 = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    @Nested
    @DisplayName("builders")
    class Builders {
        @Test
        @DisplayName("build one instance, which they no longer change")
        void once() {
            var builder = JsObject.builder().put("a", 1);
            var object = builder.build();
            assertSame(object, builder.build());
            assertThrows(IllegalStateException.class, () -> builder.put("b", 2));
            assertEquals(1, object.size());
        }

        @Test
        @DisplayName("make cycles from a builder that holds itself")
        void cycles() {
            var node = JsObject.builder();
            node.put("self", node);
            var cyclic = node.build();
            assertSame(cyclic, cyclic.get("self").orElseThrow());
            var array = JsArray.builder();
            var error = JsError.builder(JsError.Type.TYPE).cause(array);
            array.add(error).add(array);
            var built = array.build();
            var cause = built.get(0).map(JsError.class::cast).flatMap(JsError::cause).orElseThrow();
            assertSame(built, cause);
            assertSame(built, built.get(1).orElseThrow());
        }

        @Test
        @DisplayName("build the builders they hold, which then no longer change")
        void heldBuilders() {
            var shared = JsObject.builder().put("x", 1);
            var map = JsMap.builder().put(shared, JsValue.of("value"));
            var set = JsSet.builder().add(shared);
            var root = JsArray.builder().add(shared).add(map).add(set).add(shared).build();
            assertThrows(IllegalStateException.class, () -> shared.put("y", 2));
            assertThrows(IllegalStateException.class, () -> map.put(JsValue.of(1), JsValue.of(2)));
            var object = root.getObject(0).orElseThrow();
            assertSame(object, root.get(3).orElseThrow());
            assertSame(object, shared.build());
            assertEquals(Optional.of(JsValue.of("value")), root.getMap(1).orElseThrow().get(object));
            assertTrue(set.build().has(object));
        }
    }

    @Nested
    @DisplayName("objects")
    class Objects {
        @Test
        @DisplayName("keep index keys ascending before the others, which keep the order they were first put in")
        void order() {
            var object = JsObject.builder().put("b", JsValue.NULL).put("10", 1).put("a", "x").put("2", true)
                    .put("b", 5L).put("01", 1.5).build();
            assertEquals(List.of("2", "10", "b", "a", "01"), List.copyOf(object.properties().keySet()));
            assertEquals(OptionalLong.of(5), object.getLong("b"));
            assertEquals(List.of("1", "x"), List.copyOf(JsObject.of(Map.of("x", JsValue.of(1), "1", JsValue.of(2))).properties().keySet()));
        }

        @Test
        @DisplayName("refuse a long past 2^53, which a number may not hold exactly")
        void safeIntegers() {
            assertEquals(new JsNumber(9007199254740992.0), JsValue.of(9007199254740992L));
            assertThrows(ArithmeticException.class, () -> JsValue.of(9007199254740993L));
            assertThrows(ArithmeticException.class, () -> JsObject.builder().put("x", Long.MIN_VALUE));
        }
    }

    @Nested
    @DisplayName("typed reading")
    class TypedReading {
        private final JsObject chat = JsObject.builder()
                .put("id", "123@c.us")
                .put("t", 1_700_000_000)
                .put("unread", 2.5)
                .put("archive", false)
                .put("missing", JsValue.UNDEFINED)
                .put("none", JsValue.NULL)
                .put("big", JsValue.of(BigInteger.TEN))
                .put("key", JsArrayBuffer.of(new byte[]{1, 2, 3}))
                .put("view", JsArrayBufferView.of(JsArrayBufferView.Kind.UINT8, JsArrayBuffer.of(new byte[]{1, 2, 3}), 1, 2))
                .put("when", JsDate.of(Instant.ofEpochMilli(5)))
                .put("never", new JsDate(Double.NaN))
                .put("labels", JsArray.of("a", "b"))
                .put("nested", JsObject.builder().put("x", 1))
                .build();

        @Test
        @DisplayName("read a property of the type asked for")
        void present() {
            assertEquals(Optional.of("123@c.us"), chat.getString("id"));
            assertEquals(OptionalLong.of(1_700_000_000), chat.getLong("t"));
            assertEquals(OptionalInt.of(1_700_000_000), chat.getInt("t"));
            assertEquals(OptionalDouble.of(2.5), chat.getNumber("unread"));
            assertEquals(Optional.of(false), chat.getBoolean("archive"));
            assertEquals(Optional.of(BigInteger.TEN), chat.getBigInt("big"));
            assertArrayEquals(new byte[]{1, 2, 3}, chat.getBytes("key").orElseThrow());
            assertArrayEquals(new byte[]{2, 3}, chat.getBytes("view").orElseThrow());
            assertEquals(Optional.of(Instant.ofEpochMilli(5)), chat.getDate("when"));
            assertEquals(Optional.of("b"), chat.getArray("labels").orElseThrow().getString(1));
            assertEquals(OptionalInt.of(1), chat.getObject("nested").orElseThrow().getInt("x"));
        }

        @Test
        @DisplayName("read an absent, undefined or null property, or an invalid date, as empty")
        void absent() {
            for (var key : List.of("absent", "missing", "none")) {
                assertTrue(chat.getString(key).isEmpty());
                assertTrue(chat.getLong(key).isEmpty());
                assertTrue(chat.getObject(key).isEmpty());
                assertTrue(chat.get(key, JsCryptoKey.class).isEmpty());
            }
            assertTrue(chat.has("none"));
            assertTrue(chat.getDate("never").isEmpty());
        }

        @Test
        @DisplayName("get returns what is stored, undefined and null included, and is empty only for an absent property")
        void faithful() {
            assertEquals(Optional.of(JsValue.NULL), chat.get("none"));
            assertEquals(Optional.of(JsValue.UNDEFINED), chat.get("missing"));
            assertTrue(chat.get("absent").isEmpty());
            var array = JsArray.builder().add(JsValue.NULL).addHole().add(JsValue.UNDEFINED).build();
            assertEquals(Optional.of(JsValue.NULL), array.get(0));
            assertTrue(array.get(1).isEmpty());
            assertEquals(Optional.of(JsValue.UNDEFINED), array.get(2));
            assertTrue(array.get(3).isEmpty());
        }

        @Test
        @DisplayName("get with a type reads any type of value, and names the property and both types when it differs")
        void typed() {
            var buffer = chat.get("key", JsArrayBuffer.class).orElseThrow();
            assertEquals(3, buffer.byteLength());
            assertEquals(Optional.of(new JsString("123@c.us")), chat.get("id", JsString.class));
            assertEquals(2, chat.getArray("labels").orElseThrow().get(1, JsString.class).orElseThrow().value().length() + 1);
            var failure = assertThrows(ClassCastException.class, () -> chat.get("t", JsCryptoKey.class));
            assertEquals("Property \"t\" holds a number, not a CryptoKey", failure.getMessage());
        }

        @Test
        @DisplayName("refuse a property of another type, naming the property and both types")
        void mismatch() {
            var failure = assertThrows(ClassCastException.class, () -> chat.getString("t"));
            assertEquals("Property \"t\" holds a number, not a string", failure.getMessage());
            assertThrows(ClassCastException.class, () -> chat.getArray("nested"));
            assertThrows(ClassCastException.class, () -> chat.getBytes("id"));
            assertEquals("Element 0 holds a string, not a boolean",
                    assertThrows(ClassCastException.class, () -> chat.getArray("labels").orElseThrow().getBoolean(0)).getMessage());
        }

        @Test
        @DisplayName("refuse a number that is not an integer of the type asked for")
        void integers() {
            assertThrows(ArithmeticException.class, () -> chat.getLong("unread"));
            assertThrows(ArithmeticException.class, () -> JsObject.builder().put("x", 1e10).build().getInt("x"));
            assertEquals(OptionalLong.of(0), JsObject.builder().put("x", -0.0).build().getLong("x"));
            assertEquals(OptionalLong.of(1L << 53), JsObject.builder().put("x", 0x1p53).build().getLong("x"));
            assertThrows(ArithmeticException.class, () -> JsObject.builder().put("x", 0x1p60).build().getLong("x"));
            assertThrows(ArithmeticException.class, () -> JsArray.of(JsValue.of(-0x1p54)).getLong(0));
        }
    }

    @Nested
    @DisplayName("arrays")
    class Arrays {
        @Test
        @DisplayName("grow when set past their length, leaving holes, and shrink with their length")
        void length() {
            var array = JsArray.builder().add(1).add("two").addHole().set(5, JsValue.NULL).length(4).build();
            assertEquals(4, array.length());
            assertEquals(OptionalInt.of(1), array.getInt(0));
            assertTrue(array.get(2).isEmpty());
            assertFalse(array.isSparse());
            assertEquals(Map.of(0L, JsValue.of(1), 1L, JsValue.of("two")), array.elements());
        }

        @Test
        @DisplayName("keep their elements by index once they take on a long run of holes")
        void sparse() {
            var array = JsArray.builder().add(true).set(10_000_000, JsValue.of(false)).build();
            assertTrue(array.isSparse());
            assertEquals(10_000_001, array.length());
            assertThrows(IllegalArgumentException.class, () -> JsArray.builder().length(4_294_967_296L));
            assertThrows(IllegalArgumentException.class, () -> JsArray.builder().set(4_294_967_295L, JsValue.NULL));
        }

        @Test
        @DisplayName("treat an index key as the element it names, and refuse a property named length")
        void properties() {
            var array = JsArray.builder().put("1", JsValue.of("one")).put("4294967295", JsValue.of("named")).build();
            assertEquals(2, array.length());
            assertEquals(Optional.of("one"), array.getString(1));
            assertEquals(Map.of("4294967295", JsValue.of("named")), array.properties());
            assertThrows(IllegalArgumentException.class, () -> JsArray.builder().put("length", JsValue.of(0)));
        }

        @Test
        @DisplayName("are made of values or strings")
        void factories() {
            assertEquals(0, JsArray.of().length());
            assertEquals(List.of(JsValue.of("a"), JsValue.of("b")), JsArray.of("a", "b").values());
        }
    }

    @Nested
    @DisplayName("array elements")
    class Elements {
        private final JsArray holey = JsArray.builder().add("a").addHole().add(JsValue.UNDEFINED).put("p", JsValue.of(1)).build();

        @Test
        @DisplayName("values lists holes as undefined, as spreading the array does")
        void values() {
            assertEquals(List.of(JsValue.of("a"), JsValue.UNDEFINED, JsValue.UNDEFINED), holey.values());
        }

        @Test
        @DisplayName("elements and has tell a hole from an element that holds undefined")
        void elements() {
            assertEquals(new TreeMap<>(Map.of(0L, JsValue.of("a"), 2L, JsValue.UNDEFINED)), holey.elements());
            assertTrue(holey.has(0));
            assertFalse(holey.has(1));
            assertTrue(holey.has(2));
            assertFalse(holey.has(3));
            var sparse = JsArray.builder().set(1_000_000, JsValue.of("far")).build();
            assertEquals(1_000_001, sparse.length());
            assertEquals(List.of(1_000_000L), List.copyOf(sparse.elements().keySet()));
        }

        @Test
        @DisplayName("of refuses null, which is no value")
        void noNull() {
            assertThrows(NullPointerException.class, () -> JsArray.of(JsValue.of(1), null));
            assertEquals(0, JsArray.of().length());
        }
    }

    @Nested
    @DisplayName("deep equality")
    class DeepEquality {
        @Test
        @DisplayName("values that hold the same are equal, though objects compare by identity")
        void structure() {
            var first = JsObject.builder().put("a", JsArray.of("x", "y")).put("b", JsMap.builder().put("k", JsValue.of(1))).build();
            var second = JsObject.builder().put("a", JsArray.of("x", "y")).put("b", JsMap.builder().put("k", JsValue.of(1))).build();
            assertNotEquals(first, second);
            assertTrue(JsValue.deepEquals(first, second));
            assertFalse(JsValue.deepEquals(first, JsObject.builder().put("b", JsMap.builder()).put("a", JsArray.of("x", "y")).build()));
            assertFalse(JsValue.deepEquals(JsValue.of(0.0), JsValue.of(-0.0)));
            assertTrue(JsValue.deepEquals(JsValue.of(Double.NaN), JsValue.of(Double.NaN)));
        }

        @Test
        @DisplayName("sharing and cycles must be in the same places")
        void sharing() {
            var shared = JsObject.builder().put("x", 1).build();
            var twice = JsArray.of(shared, shared);
            var two = JsArray.of(JsObject.builder().put("x", 1).build(), JsObject.builder().put("x", 1).build());
            assertFalse(JsValue.deepEquals(twice, two));
            var one = JsObject.builder().put("x", 1).build();
            assertTrue(JsValue.deepEquals(twice, JsArray.of(one, one)));
            var loop = JsObject.builder();
            loop.put("self", loop);
            var otherLoop = JsObject.builder();
            otherLoop.put("self", otherLoop);
            assertTrue(JsValue.deepEquals(loop.build(), otherLoop.build()));
            var outer = JsObject.builder();
            outer.put("self", JsObject.builder().put("self", outer));
            assertFalse(JsValue.deepEquals(loop.build(), outer.build()));
        }

        @Test
        @DisplayName("holes differ from undefined, and views share buffers in the same places")
        void arraysAndViews() {
            assertFalse(JsValue.deepEquals(JsArray.builder().addHole().build(), JsArray.of(JsValue.UNDEFINED)));
            var buffer = JsArrayBuffer.of(new byte[4]);
            var sharing = JsArray.of(JsArrayBufferView.of(JsArrayBufferView.Kind.UINT8, buffer), JsArrayBufferView.of(JsArrayBufferView.Kind.INT8, buffer));
            var separate = JsArray.of(JsArrayBufferView.uint8(new byte[4]), JsArrayBufferView.of(JsArrayBufferView.Kind.INT8, JsArrayBuffer.of(new byte[4])));
            assertFalse(JsValue.deepEquals(sharing, separate));
            assertTrue(JsValue.deepEquals(separate, JsArray.of(JsArrayBufferView.uint8(new byte[4]),
                    JsArrayBufferView.of(JsArrayBufferView.Kind.INT8, JsArrayBuffer.of(new byte[4])))));
        }

        @Test
        @DisplayName("a value equals what it serializes to")
        void roundTrip() throws Exception {
            for (var i = 0; i < 200; i++) {
                var value = RandomValues.graph(i, 30);
                assertTrue(JsValue.deepEquals(value, StructuredClone.deserialize(StructuredClone.serialize(value))), value::toString);
            }
        }
    }

    @Nested
    @DisplayName("maps and sets")
    class Collections {
        @Test
        @DisplayName("compare keys as SameValueZero: NaN to itself, -0 to 0, objects and records by identity")
        void keys() {
            var date = new JsDate(5);
            var map = JsMap.builder().put(JsValue.of(Double.NaN), JsValue.of("a")).put(JsValue.of(Double.NaN), JsValue.of("b"))
                    .put(JsValue.of(-0.0), JsValue.of("c")).put(JsValue.of(0.0), JsValue.of("d"))
                    .put(date, JsValue.of("e")).put(new JsDate(5), JsValue.of("f")).put("k", JsValue.of("g")).build();
            assertEquals(5, map.size());
            assertEquals(Optional.of(JsValue.of("b")), map.get(JsValue.of(Double.NaN)));
            assertEquals(JsValue.of(0.0), map.entries().get(1).getKey());
            assertEquals(Optional.of(JsValue.of("d")), map.get(JsValue.of(-0.0)));
            assertEquals(Optional.of(JsValue.of("g")), map.get("k"));
            assertTrue(map.has(date));
            var set = JsSet.of(JsValue.of("x"), JsValue.of("x"), JsValue.of(-0.0), date, new JsDate(5));
            assertEquals(4, set.size());
            assertTrue(set.has(JsValue.of(0.0)));
            assertSame(date, set.values().get(2));
        }
    }

    @Nested
    @DisplayName("primitive-like objects")
    class Simple {
        @Test
        @DisplayName("dates are clipped as JavaScript clips them")
        void dates() {
            assertEquals(1, new JsDate(1.75).time());
            assertEquals(0, Double.doubleToRawLongBits(new JsDate(-0.5).time()));
            assertTrue(Double.isNaN(new JsDate(8.64e15 + 1).time()));
            assertEquals(1_700_000_000_123.0, JsDate.of(Instant.parse("2023-11-14T22:13:20.123456Z")).time());
            assertThrows(IllegalArgumentException.class, () -> JsDate.of(Instant.MAX));
        }

        @Test
        @DisplayName("regular expression flags are put in their order, and invalid ones refused")
        void regExps() {
            assertEquals(new JsRegExp("a", "gi"), new JsRegExp("a", "ig"));
            assertEquals("dgimsuy", new JsRegExp("a", "yusmigd").flags());
            assertThrows(IllegalArgumentException.class, () -> new JsRegExp("a", "uv"));
            assertThrows(IllegalArgumentException.class, () -> new JsRegExp("a", "gg"));
        }

        @Test
        @DisplayName("an error has a message, stack and cause only when given them")
        void errors() {
            var error = JsError.builder(JsError.Type.RANGE).message("m").stack("at x").cause(JsValue.UNDEFINED).build();
            assertEquals(Optional.of("m"), error.message());
            assertEquals(Optional.of(JsValue.UNDEFINED), error.cause());
            assertTrue(JsError.of(JsError.Type.ERROR, null).message().isEmpty());
            assertTrue(JsError.of(JsError.Type.ERROR, null).cause().isEmpty());
        }

        @Test
        @DisplayName("primitives switch by family")
        void primitives() {
            JsValue value = JsWrapper.of(JsValue.of("x"));
            var family = switch (value) {
                case JsPrimitive _ -> "primitive";
                case JsHostObject _ -> "platform";
                default -> "object";
            };
            assertEquals("object", family);
            assertEquals(JsValue.of("x"), ((JsWrapper) value).primitive());
        }
    }

    @Nested
    @DisplayName("buffers and views")
    class Buffers {
        @Test
        @DisplayName("a buffer copies its bytes in and out")
        void copies() {
            var bytes = new byte[]{1, 2};
            var buffer = JsArrayBuffer.of(bytes);
            bytes[0] = 9;
            assertArrayEquals(new byte[]{1, 2}, buffer.toByteArray());
            buffer.toByteArray()[1] = 9;
            assertArrayEquals(new byte[]{1, 2}, buffer.toByteArray());
            assertTrue(buffer.asByteBuffer().isReadOnly());
            assertThrows(IllegalArgumentException.class, () -> JsArrayBuffer.ofResizable(new byte[2], 1));
        }

        @Test
        @DisplayName("a view must lie within its buffer, aligned to its elements")
        void ranges() {
            var buffer = JsArrayBuffer.of(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
            assertArrayEquals(new byte[]{5, 6, 7, 8}, JsArrayBufferView.of(JsArrayBufferView.Kind.UINT32, buffer, 4, 4).toByteArray());
            assertThrows(IllegalArgumentException.class, () -> JsArrayBufferView.of(JsArrayBufferView.Kind.UINT8, buffer, 6, 3));
            assertThrows(IllegalArgumentException.class, () -> JsArrayBufferView.of(JsArrayBufferView.Kind.INT16, buffer, 1, 2));
            var resizable = JsArrayBuffer.ofResizable(new byte[7], 16);
            assertEquals(4, JsArrayBufferView.ofLengthTracking(JsArrayBufferView.Kind.UINT16, resizable, 2).byteLength());
            assertThrows(IllegalArgumentException.class, () -> JsArrayBufferView.ofLengthTracking(JsArrayBufferView.Kind.UINT8, buffer, 0));
        }
    }

    @Nested
    @DisplayName("crypto keys")
    class CryptoKeys {
        @Test
        @DisplayName("a secret key takes its length from its bytes and the usages its keys may have")
        void secret() {
            var aes = JsCryptoKey.secret(SecretKeyAlgorithm.AES_CTR, new byte[32]).usages(JsCryptoKey.Usage.ENCRYPT).build();
            assertEquals(new KeyAlgorithm.Aes(KeyAlgorithm.Aes.Name.AES_CTR, 256), aes.algorithm());
            assertFalse(aes.extractable());
            assertEquals(JsCryptoKey.KeyDataFormat.RAW, aes.keyDataFormat());
            assertThrows(IllegalArgumentException.class, () -> JsCryptoKey.secret(SecretKeyAlgorithm.AES_GCM, new byte[24]).usages(JsCryptoKey.Usage.ENCRYPT).build());
            assertThrows(IllegalArgumentException.class, () -> JsCryptoKey.secret(SecretKeyAlgorithm.AES_KW, new byte[16]).usages(JsCryptoKey.Usage.ENCRYPT).build());
            assertThrows(IllegalArgumentException.class, () -> JsCryptoKey.secret(SecretKeyAlgorithm.CHACHA20_POLY1305, new byte[31]).usages(JsCryptoKey.Usage.ENCRYPT).build());
            assertEquals(new KeyAlgorithm.Basic(KeyAlgorithm.Basic.Name.PBKDF2),
                    JsCryptoKey.secret(SecretKeyAlgorithm.PBKDF2, new byte[0]).usages(JsCryptoKey.Usage.DERIVE_KEY).build().algorithm());
        }

        @Test
        @DisplayName("a secret or private key without a usage is refused, as Web Crypto makes none")
        void usagesRequired() {
            assertThrows(IllegalArgumentException.class, () -> JsCryptoKey.secret(SecretKeyAlgorithm.AES_GCM, new byte[16]).build());
            assertThrows(IllegalArgumentException.class, () -> JsCryptoKey.privateKey(KeyPairAlgorithm.ECDH, ec.getPrivate()).build());
            assertTrue(JsCryptoKey.publicKey(KeyPairAlgorithm.ECDH, ec.getPublic()).build().usages().isEmpty());
        }

        @Test
        @DisplayName("an HMAC key's length is all its bits, with the hash it was made for")
        void hmac() {
            var key = JsCryptoKey.secret(SecretKeyAlgorithm.hmac(KeyAlgorithm.Hash.SHA_384), new byte[5]).usages(JsCryptoKey.Usage.VERIFY).build();
            assertEquals(new KeyAlgorithm.Hmac(KeyAlgorithm.Hash.SHA_384, 40), key.algorithm());
            assertThrows(IllegalArgumentException.class, () -> JsCryptoKey.secret(SecretKeyAlgorithm.hmac(KeyAlgorithm.Hash.SHA_1), new byte[0])
                    .usages(JsCryptoKey.Usage.SIGN).build());
            assertEquals(SecretKeyAlgorithm.hmac(KeyAlgorithm.Hash.SHA_1), SecretKeyAlgorithm.hmac(KeyAlgorithm.Hash.SHA_1));
        }

        @Test
        @DisplayName("an RSA key takes its modulus length and public exponent from its key data")
        void rsa() {
            var modulus = ((RSAPublicKey) rsa.getPublic()).getModulus().bitLength();
            var key = JsCryptoKey.privateKey(KeyPairAlgorithm.rsaOaep(KeyAlgorithm.Hash.SHA_256), rsa.getPrivate().getEncoded())
                    .usages(JsCryptoKey.Usage.DECRYPT, JsCryptoKey.Usage.UNWRAP_KEY).build();
            assertEquals(new KeyAlgorithm.RsaHashed(KeyAlgorithm.RsaHashed.Name.RSA_OAEP, modulus, BigInteger.valueOf(65537), KeyAlgorithm.Hash.SHA_256),
                    key.algorithm());
            assertEquals(JsCryptoKey.KeyDataFormat.PKCS8, key.keyDataFormat());
            var oaep = KeyPairAlgorithm.rsaOaep(KeyAlgorithm.Hash.SHA_256);
            assertThrows(IllegalArgumentException.class, () -> JsCryptoKey.publicKey(oaep, rsa.getPrivate().getEncoded()).build());
            assertThrows(IllegalArgumentException.class, () -> JsCryptoKey.publicKey(oaep, rsa.getPublic()).usages(JsCryptoKey.Usage.DECRYPT).build());
        }

        @Test
        @DisplayName("an elliptic-curve key takes its curve from its key data")
        void ellipticCurves() {
            var key = JsCryptoKey.privateKey(KeyPairAlgorithm.ECDH, ec.getPrivate().getEncoded())
                    .usages(EnumSet.of(JsCryptoKey.Usage.DERIVE_BITS)).build();
            assertEquals(new KeyAlgorithm.Ec(KeyAlgorithm.Ec.Name.ECDH, KeyAlgorithm.NamedCurve.P_384), key.algorithm());
            assertThrows(IllegalArgumentException.class, () -> JsCryptoKey.publicKey(KeyPairAlgorithm.ECDH, ec.getPublic())
                    .usages(JsCryptoKey.Usage.DERIVE_BITS).build());
            assertThrows(IllegalArgumentException.class, () -> JsCryptoKey.publicKey(KeyPairAlgorithm.ECDSA, ed25519.getPublic()).build());
        }

        @Test
        @DisplayName("a key without parameters must be of its algorithm")
        void others() {
            var key = JsCryptoKey.publicKey(KeyPairAlgorithm.ED25519, ed25519.getPublic()).usages(JsCryptoKey.Usage.VERIFY).extractable(true).build();
            assertTrue(key.extractable());
            assertThrows(IllegalArgumentException.class, () -> JsCryptoKey.publicKey(KeyPairAlgorithm.X25519, ed25519.getPublic()).build());
            assertThrows(IllegalArgumentException.class, () -> JsCryptoKey.publicKey(KeyPairAlgorithm.ED25519, new byte[]{0x30, 0x03, 0x02, 0x01}).build());
            var hybrid = JsCryptoKey.privateKey(KeyPairAlgorithm.X25519_MLKEM768, new byte[32]).usages(JsCryptoKey.Usage.DECAPSULATE_BITS).build();
            assertEquals(JsCryptoKey.KeyDataFormat.RAW, hybrid.keyDataFormat());
            assertThrows(IllegalArgumentException.class, () -> JsCryptoKey.privateKey(KeyPairAlgorithm.X25519_MLKEM768, ec.getPrivate()));
            var builder = JsCryptoKey.secret(SecretKeyAlgorithm.HKDF, new byte[1]).usages(JsCryptoKey.Usage.DERIVE_BITS);
            assertSame(builder.build(), builder.build());
            assertThrows(IllegalStateException.class, () -> builder.extractable(true));
        }

        @Test
        @DisplayName("keys convert to the JDK's keys and back")
        void jca() throws GeneralSecurityException {
            var aes = JsCryptoKey.secret(SecretKeyAlgorithm.AES_GCM, new byte[16]).usages(JsCryptoKey.Usage.ENCRYPT).build();
            assertEquals("AES", aes.toSecretKey().getAlgorithm());
            assertArrayEquals(new byte[16], aes.toSecretKey().getEncoded());
            var mac = JsCryptoKey.secret(SecretKeyAlgorithm.hmac(KeyAlgorithm.Hash.SHA_512), new byte[64]).usages(JsCryptoKey.Usage.SIGN).build();
            assertEquals("HmacSHA512", mac.toSecretKey().getAlgorithm());
            var publicKey = JsCryptoKey.publicKey(KeyPairAlgorithm.rsaPss(KeyAlgorithm.Hash.SHA_384), rsa.getPublic()).usages(JsCryptoKey.Usage.VERIFY).build();
            assertEquals(rsa.getPublic(), publicKey.toPublicKey());
            var privateKey = JsCryptoKey.privateKey(KeyPairAlgorithm.ECDH, ec.getPrivate()).usages(JsCryptoKey.Usage.DERIVE_BITS).build();
            assertArrayEquals(ec.getPrivate().getEncoded(), privateKey.toPrivateKey().getEncoded());
            var edPublic = JsCryptoKey.publicKey(KeyPairAlgorithm.ED25519, ed25519.getPublic()).usages(JsCryptoKey.Usage.VERIFY).build();
            assertEquals(ed25519.getPublic(), edPublic.toPublicKey());
            assertThrows(IllegalStateException.class, aes::toPublicKey);
            assertThrows(IllegalStateException.class, publicKey::toSecretKey);
            var hybrid = JsCryptoKey.publicKey(KeyPairAlgorithm.X25519_MLKEM768, new byte[1216]).usages(JsCryptoKey.Usage.ENCAPSULATE_BITS).build();
            assertThrows(NoSuchAlgorithmException.class, hybrid::toPublicKey);
        }
    }

    @Nested
    @DisplayName("platform objects")
    class Platform {
        @Test
        @DisplayName("blob and file types are kept as the File API keeps them")
        void blobs() throws Exception {
            assertEquals("text/plain", JsBlob.of(new byte[]{1}, "Text/PLAIN").type());
            assertEquals("", JsBlob.of(new byte[]{1}, "badé").type());
            assertThrows(IllegalArgumentException.class, () -> JsBlob.of(BlobSource.of(new byte[0]), -1, ""));
            var file = JsFile.of("a.txt", new byte[]{7, 8}, "A/B", Instant.ofEpochMilli(9));
            assertEquals("a/b", file.type());
            try (var stream = file.open()) {
                assertArrayEquals(new byte[]{7, 8}, stream.readAllBytes());
            }
            assertEquals(List.of(file, file), JsFileList.of(file, file).files());
            assertInstanceOf(JsBlob.class, file);
            assertTrue(file.hasBytes());
        }

        @Test
        @DisplayName("blobs and files of a path take its size, and a file its name and modification time")
        void paths(@TempDir Path directory) throws Exception {
            var path = Files.write(directory.resolve("clip.bin"), new byte[]{1, 2, 3});
            Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.from(Instant.ofEpochSecond(1_600_000_000)));
            var blob = JsBlob.of(path, "Video/MP4");
            assertEquals(3, blob.size());
            assertEquals("video/mp4", blob.type());
            var file = JsFile.of(path, "");
            assertEquals("clip.bin", file.name());
            assertEquals(Optional.of(Instant.ofEpochSecond(1_600_000_000)), file.lastModified());
            assertArrayEquals(new byte[]{1, 2, 3}, file.readAllBytes());
            assertArrayEquals(new byte[]{1, 2, 3}, BlobSource.of(path).open().readAllBytes());
        }

        @Test
        @DisplayName("a serialized value copies its bytes and compares by them and its blobs")
        void serialized() {
            var bytes = new byte[]{1, 2};
            var blob = JsBlob.of(new byte[0], "");
            var serialized = new SerializedValue(bytes, List.of(blob));
            bytes[0] = 9;
            assertArrayEquals(new byte[]{1, 2}, serialized.bytes());
            serialized.bytes()[0] = 9;
            assertEquals(new SerializedValue(new byte[]{1, 2}, List.of(blob)), serialized);
            assertNotEquals(new SerializedValue(new byte[]{1, 2}, List.of(JsBlob.of(new byte[0], ""))), serialized);
        }

        @Test
        @DisplayName("image data has the pixels its size and format call for")
        void images() {
            assertEquals(2, JsImageData.of(2, 1, new byte[8]).width());
            assertThrows(IllegalArgumentException.class, () -> JsImageData.of(1, 1, JsColorSpace.SRGB, JsImageData.PixelFormat.RGBA_FLOAT16, new byte[4]));
            assertThrows(IllegalArgumentException.class, () -> JsImageBitmap.of(1, 1, JsColorSpace.SRGB, JsImageBitmap.PixelFormat.NATIVE8, true,
                    false, JsImageBitmap.Orientation.TOP_LEFT, new byte[4]));
            assertThrows(IllegalArgumentException.class, () -> JsDomMatrix.of(new double[7], false));
        }
    }
}
