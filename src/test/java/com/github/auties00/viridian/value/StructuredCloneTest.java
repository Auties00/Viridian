package com.github.auties00.viridian.value;

import com.github.auties00.viridian.ViridianException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The structured clone decoder against values written tag by tag, as V8 and Blink write them.
class StructuredCloneTest {
    private static JsValue decode(V8Writer writer) throws ViridianException {
        return StructuredClone.deserialize(writer.toByteArray());
    }

    private static ViridianException.Reason failure(V8Writer writer) {
        return assertThrows(ViridianException.class, () -> decode(writer)).reason();
    }

    @Nested
    @DisplayName("primitives")
    class Primitives {
        @Test
        @DisplayName("undefined, null and booleans")
        void constants() throws ViridianException {
            assertSame(JsValue.UNDEFINED, decode(V8Writer.blink().tag('_')));
            assertSame(JsValue.NULL, decode(V8Writer.blink().tag('0')));
            assertEquals(JsBoolean.TRUE, decode(V8Writer.blink().tag('T')));
            assertEquals(JsBoolean.FALSE, decode(V8Writer.blink().tag('F')));
        }

        @ParameterizedTest
        @CsvSource({"0", "1", "-1", "2147483647", "-2147483648", "63", "-64"})
        @DisplayName("int32 numbers, zigzag-encoded")
        void int32(int value) throws ViridianException {
            assertEquals(new JsNumber(value), decode(V8Writer.blink().int32(value)));
        }

        @Test
        @DisplayName("uint32 and double numbers, special values included")
        void numbers() throws ViridianException {
            assertEquals(new JsNumber(4294967295.0), decode(V8Writer.blink().tag('U').varint(0xFFFF_FFFFL)));
            assertEquals(new JsNumber(Double.NaN), decode(V8Writer.blink().number(Double.NaN)));
            assertEquals(new JsNumber(-0.0), decode(V8Writer.blink().number(-0.0)));
            assertEquals(new JsNumber(Double.NEGATIVE_INFINITY), decode(V8Writer.blink().number(Double.NEGATIVE_INFINITY)));
            assertEquals(new JsNumber(1.5), decode(V8Writer.blink().number(1.5)));
        }

        @Test
        @DisplayName("BigInts, by sign and little-endian digits")
        void bigInts() throws ViridianException {
            assertEquals(new JsBigInt(BigInteger.ZERO), decode(V8Writer.blink().tag('Z').varint(0)));
            assertEquals(new JsBigInt(BigInteger.valueOf(258)), decode(V8Writer.blink().tag('Z').varint(8 << 1).raw(new byte[]{2, 1, 0, 0, 0, 0, 0, 0})));
            assertEquals(new JsBigInt(BigInteger.valueOf(-5)), decode(V8Writer.blink().tag('Z').varint(8 << 1 | 1).raw(new byte[]{5, 0, 0, 0, 0, 0, 0, 0})));
        }

        @Test
        @DisplayName("one-byte, two-byte and UTF-8 strings, a lone surrogate kept")
        void strings() throws ViridianException {
            assertEquals(new JsString("café"), decode(V8Writer.blink().latin1("café")));
            assertEquals(new JsString("你好\ud83d"), decode(V8Writer.blink().twoByte("你好\ud83d")));
            assertEquals(new JsString("é😀"), decode(V8Writer.blink().utf8("é😀")));
        }
    }

    @Nested
    @DisplayName("objects")
    class Objects {
        @Test
        @DisplayName("an object keeps its properties in order, numeric keys as JavaScript names them")
        void properties() throws ViridianException {
            var value = decode(V8Writer.blink().tag('o')
                    .int32(1).latin1("one")
                    .number(1.5).latin1("one and a half")
                    .latin1("name").latin1("value")
                    .tag('{').varint(3));
            var object = assertInstanceOf(JsObject.class, value);
            assertEquals(List.of("1", "1.5", "name"), List.copyOf(object.properties().keySet()));
            assertEquals(new JsString("value"), object.get("name").orElseThrow());
        }

        @Test
        @DisplayName("an object that contains itself decodes to the same instance")
        void cycle() throws ViridianException {
            var object = assertInstanceOf(JsObject.class, decode(V8Writer.blink().tag('o').latin1("self").tag('^').varint(0).tag('{').varint(1)));
            assertSame(object, object.get("self").orElseThrow());
            assertTrue(object.toString().contains("[Circular]"));
        }

        @Test
        @DisplayName("a dense array keeps its holes and its other properties")
        void denseArray() throws ViridianException {
            var array = assertInstanceOf(JsArray.class, decode(V8Writer.blink().tag('A').varint(3)
                    .int32(10).tag('-').int32(30)
                    .latin1("extra").tag('T')
                    .tag('$').varint(1).varint(3)));
            assertEquals(3, array.length());
            assertEquals(new JsNumber(10), array.get(0).orElseThrow());
            assertTrue(array.get(1).isEmpty());
            assertEquals(JsBoolean.TRUE, array.properties().get("extra"));
        }

        @Test
        @DisplayName("a sparse array keeps only the elements it has, whatever its length")
        void sparseArray() throws ViridianException {
            var array = assertInstanceOf(JsArray.class, decode(V8Writer.blink().tag('a').varint(4_000_000_000L)
                    .int32(5).latin1("five")
                    .tag('U').varint(3_999_999_999L).latin1("last")
                    .tag('@').varint(2).varint(4_000_000_000L)));
            assertEquals(4_000_000_000L, array.length());
            assertEquals(new JsString("five"), array.get(5).orElseThrow());
            assertEquals(new JsString("last"), array.get(3_999_999_999L).orElseThrow());
            assertTrue(array.get(6).isEmpty());
        }

        @Test
        @DisplayName("Maps and Sets keep their entries in order, objects as keys included")
        void collections() throws ViridianException {
            var map = assertInstanceOf(JsMap.class, decode(V8Writer.blink().tag(';')
                    .latin1("a").int32(1)
                    .tag('o').tag('{').varint(0).int32(2)
                    .tag(':').varint(4)));
            assertEquals(2, map.size());
            assertInstanceOf(JsObject.class, map.entries().get(1).getKey());
            var set = assertInstanceOf(JsSet.class, decode(V8Writer.blink().tag('\'').int32(1).latin1("b").tag(',').varint(2)));
            assertEquals(List.of(new JsNumber(1), new JsString("b")), set.values());
        }

        @Test
        @DisplayName("dates, regular expressions and primitive wrappers")
        void builtIns() throws ViridianException {
            var date = assertInstanceOf(JsDate.class, decode(V8Writer.blink().tag('D').f64(1_700_000_000_000.0)));
            assertEquals(Instant.ofEpochMilli(1_700_000_000_000L), date.instant().orElseThrow());
            assertTrue(assertInstanceOf(JsDate.class, decode(V8Writer.blink().tag('D').f64(Double.NaN))).instant().isEmpty());
            // g, i and the d flag, bits 0, 1 and 7
            assertEquals(new JsRegExp("a+b", "dgi"), decode(V8Writer.blink().tag('R').latin1("a+b").varint(0b1000_0011)));
            var wrapper = assertInstanceOf(JsWrapper.class, decode(V8Writer.blink().tag('s').latin1("boxed")));
            assertEquals(new JsString("boxed"), wrapper.primitive());
            assertEquals(new JsNumber(2), assertInstanceOf(JsWrapper.class, decode(V8Writer.blink().tag('n').f64(2))).primitive());
        }

        @Test
        @DisplayName("an error keeps its constructor, message, stack and a cause that refers back to it")
        void error() throws ViridianException {
            var error = assertInstanceOf(JsError.class, decode(V8Writer.blink().tag('r')
                    .varint('T').varint('m').latin1("bad").varint('s').latin1("at x").varint('c').tag('^').varint(0).varint('.')));
            assertEquals(JsError.Type.TYPE, error.type());
            assertEquals("bad", error.message().orElseThrow());
            assertEquals("at x", error.stack().orElseThrow());
            assertSame(error, error.cause().orElseThrow());
        }
    }

    @Nested
    @DisplayName("array buffers")
    class Buffers {
        @Test
        @DisplayName("a typed array views its buffer, which a second view shares")
        void views() throws ViridianException {
            var array = assertInstanceOf(JsArray.class, decode(V8Writer.blink().tag('A').varint(2)
                    .tag('B').varint(8).raw(new byte[]{1, 0, 2, 0, 3, 0, 4, 0}).tag('V').varint('W').varint(2).varint(4).varint(0)
                    .tag('^').varint(1).tag('V').varint('?').varint(0).varint(8).varint(0)
                    .tag('$').varint(0).varint(2)));
            var words = assertInstanceOf(JsArrayBufferView.class, array.get(0).orElseThrow());
            assertEquals(JsArrayBufferView.Kind.UINT16, words.kind());
            assertEquals(2, words.length());
            assertArrayEquals(new byte[]{2, 0, 3, 0}, words.toByteArray());
            var view = assertInstanceOf(JsArrayBufferView.class, array.get(1).orElseThrow());
            assertEquals(JsArrayBufferView.Kind.DATA_VIEW, view.kind());
            assertSame(words.buffer(), view.buffer());
        }

        @Test
        @DisplayName("a resizable buffer and a length-tracking view, and an immutable buffer")
        void resizable() throws ViridianException {
            var view = assertInstanceOf(JsArrayBufferView.class, decode(V8Writer.blink()
                    .tag('~').varint(2).varint(16).raw(new byte[]{7, 8}).tag('V').varint('B').varint(0).varint(2).varint(0b11)));
            assertTrue(view.lengthTracking());
            assertEquals(16, view.buffer().maxByteLength().orElseThrow());
            var immutable = assertInstanceOf(JsArrayBuffer.class, decode(V8Writer.v8(16).tag('C').varint(1).raw(new byte[]{9})));
            assertTrue(immutable.immutable());
        }

        @Test
        @DisplayName("a view outside its buffer, or misaligned, is damage")
        void invalidViews() {
            assertEquals(ViridianException.Reason.CORRUPTED, failure(V8Writer.blink().tag('B').varint(2).raw(new byte[2]).tag('V').varint('B').varint(1).varint(2).varint(0)));
            assertEquals(ViridianException.Reason.CORRUPTED, failure(V8Writer.blink().tag('B').varint(4).raw(new byte[4]).tag('V').varint('d').varint(1).varint(2).varint(0)));
        }
    }

    @Nested
    @DisplayName("host objects")
    class HostObjects {
        @Test
        @DisplayName("an AES key, extractable, with its usages and raw material")
        void aesKey() throws ViridianException {
            var key = assertInstanceOf(JsCryptoKey.class, decode(V8Writer.blink().tag('\\').tag('K')
                    .raw(1).varint(9).varint(16).varint(1 | 1 << 1 | 1 << 2).varint(16).raw(new byte[16])));
            assertEquals(new KeyAlgorithm.Aes(KeyAlgorithm.Aes.Name.AES_GCM, 128), key.algorithm());
            assertEquals(EnumSet.of(JsCryptoKey.Usage.ENCRYPT, JsCryptoKey.Usage.DECRYPT), key.usages());
            assertTrue(key.extractable());
            assertEquals(JsCryptoKey.KeyDataFormat.RAW, key.keyDataFormat());
        }

        @Test
        @DisplayName("HMAC, RSA, EC, Ed25519 and parameterless keys")
        void otherKeys() throws ViridianException {
            var hmac = assertInstanceOf(JsCryptoKey.class, decode(V8Writer.blink().tag('\\').tag('K').raw(2).varint(32).varint(6).varint(1 << 3).varint(32).raw(new byte[32])));
            assertEquals(new KeyAlgorithm.Hmac(KeyAlgorithm.Hash.SHA_256, 256), hmac.algorithm());
            assertFalse(hmac.extractable());
            var rsa = assertInstanceOf(JsCryptoKey.class, decode(V8Writer.blink().tag('\\').tag('K')
                    .raw(4).varint(13).varint(2).varint(2048).varint(3).raw(new byte[]{1, 0, 1}).varint(6).varint(1 << 3).varint(4).raw(new byte[4])));
            assertEquals(JsCryptoKey.Type.PRIVATE, rsa.type());
            assertEquals(JsCryptoKey.KeyDataFormat.PKCS8, rsa.keyDataFormat());
            assertEquals(new KeyAlgorithm.RsaHashed(KeyAlgorithm.RsaHashed.Name.RSA_PSS, 2048, BigInteger.valueOf(65537), KeyAlgorithm.Hash.SHA_256),
                    rsa.algorithm());
            var ec = assertInstanceOf(JsCryptoKey.class, decode(V8Writer.blink().tag('\\').tag('K').raw(5).varint(14).varint(1).varint(2).varint(1 << 4).varint(2).raw(new byte[2])));
            assertEquals(new KeyAlgorithm.Ec(KeyAlgorithm.Ec.Name.ECDSA, KeyAlgorithm.NamedCurve.P_384), ec.algorithm());
            assertEquals(JsCryptoKey.KeyDataFormat.SPKI, ec.keyDataFormat());
            assertEquals(new KeyAlgorithm.Basic(KeyAlgorithm.Basic.Name.ED25519), assertInstanceOf(JsCryptoKey.class, decode(V8Writer.blink().tag('\\').tag('K')
                    .raw(7).varint(18).varint(1).varint(0).varint(1).raw(new byte[1]))).algorithm());
            assertEquals(new KeyAlgorithm.Basic(KeyAlgorithm.Basic.Name.HKDF), assertInstanceOf(JsCryptoKey.class, decode(V8Writer.blink().tag('\\').tag('K')
                    .raw(6).varint(16).varint(1 << 5).varint(1).raw(new byte[1]))).algorithm());
            assertEquals(new KeyAlgorithm.Basic(KeyAlgorithm.Basic.Name.ML_DSA_65), assertInstanceOf(JsCryptoKey.class, decode(V8Writer.blink().tag('\\').tag('K')
                    .raw(9).varint(22).varint(2).varint(1 << 3).varint(1).raw(new byte[1]))).algorithm());
        }

        @Test
        @DisplayName("blobs and files by index resolve to the blobs stored beside the value, their bytes included")
        void blobs() throws Exception {
            var blobs = List.of(
                    new JsBlob("text/plain", 3, () -> new ByteArrayInputStream(new byte[]{1, 2, 3})),
                    new JsFile("photo.png", "image/png", 2, Instant.EPOCH, () -> new ByteArrayInputStream(new byte[]{4, 5})));
            var data = V8Writer.blink().tag('A').varint(2).tag('\\').tag('i').varint(0).tag('\\').tag('e').varint(1).tag('$').varint(0).varint(2).toByteArray();
            var array = assertInstanceOf(JsArray.class, StructuredClone.deserialize(data, blobs));
            var blob = assertInstanceOf(JsBlob.class, array.get(0).orElseThrow());
            assertSame(blobs.getFirst(), blob);
            assertArrayEquals(new byte[]{1, 2, 3}, blob.readAllBytes());
            assertSame(blobs.getLast(), array.get(1).orElseThrow());
            assertEquals(ViridianException.Reason.MISSING, failure(V8Writer.blink().tag('\\').tag('i').varint(0)));
        }

        @Test
        @DisplayName("image data, geometry, DOM exceptions, handles and certificates")
        void platformObjects() throws ViridianException {
            var image = assertInstanceOf(JsImageData.class, decode(V8Writer.blink().tag('\\').tag('#')
                    .varint(1).varint(3).varint(0).varint(1).varint(1).varint(4).raw(new byte[]{1, 2, 3, 4})));
            assertEquals(JsColorSpace.DISPLAY_P3, image.colorSpace());
            assertArrayEquals(new byte[]{1, 2, 3, 4}, image.pixels());
            assertEquals(new JsDomPoint(1, 2, 3, 4, true), decode(V8Writer.blink().tag('\\').tag('W').f64(1).f64(2).f64(3).f64(4)));
            assertEquals(new JsDomRect(0, 0, 5, 6, false), decode(V8Writer.blink().tag('\\').tag('E').f64(0).f64(0).f64(5).f64(6)));
            assertTrue(assertInstanceOf(JsDomMatrix.class, decode(V8Writer.blink().tag('\\').tag('I').f64(1).f64(0).f64(0).f64(1).f64(0).f64(0))).is2D());
            assertEquals(new JsDomException("NotFoundError", "gone"), decode(V8Writer.blink().tag('\\').tag('x')
                    .blinkString("NotFoundError").blinkString("gone").blinkString("stack")));
            var handle = assertInstanceOf(JsFileSystemHandle.class, decode(V8Writer.blink().tag('\\').tag('N').blinkString("docs").varint(0)));
            assertEquals(JsFileSystemHandle.Kind.DIRECTORY, handle.kind());
            assertEquals("docs", handle.name());
            assertTrue(handle.token().isEmpty());
            assertInstanceOf(JsRtcCertificate.class, decode(V8Writer.blink().tag('\\').tag('k').blinkString("key").blinkString("cert")));
        }

        @Test
        @DisplayName("an object that exists only while passed between contexts is unsupported")
        void transferOnly() {
            assertEquals(ViridianException.Reason.UNSUPPORTED, failure(V8Writer.blink().tag('\\').tag('M').varint(0)));
            assertEquals(ViridianException.Reason.UNSUPPORTED, failure(V8Writer.blink().tag('t').varint(0)));
        }
    }

    @Nested
    @DisplayName("envelopes")
    class Envelopes {
        @Test
        @DisplayName("Blink envelopes with and without a trailer offset, a shared envelope, and V8 alone")
        void versions() throws ViridianException {
            assertEquals(new JsNumber(7), decode(V8Writer.blink(21, 15, true).int32(7)));
            assertEquals(new JsNumber(7), decode(V8Writer.blink(19, 15, false).int32(7)));
            // Before Blink version 16 one header served both
            assertEquals(new JsNumber(7), decode(V8Writer.v8(13).int32(7)));
            assertEquals(new JsNumber(7), decode(V8Writer.v8(16).raw(0).raw(0).int32(7)));
        }

        @Test
        @DisplayName("a version newer than the reader knows is unsupported")
        void newer() {
            assertEquals(ViridianException.Reason.UNSUPPORTED, failure(V8Writer.blink(21, 17, true).int32(1)));
            assertEquals(ViridianException.Reason.UNSUPPORTED, failure(V8Writer.blink(22, 15, true).int32(1)));
        }

        @Test
        @DisplayName("before version 13, a host object has no tag of its own")
        void legacyHostObject() throws ViridianException {
            assertEquals(new JsDomPoint(1, 1, 1, 1, false), decode(V8Writer.v8(12).tag('Q').f64(1).f64(1).f64(1).f64(1)));
        }
    }

    @Nested
    @DisplayName("damage")
    class Damage {
        @Test
        @DisplayName("truncation, a dangling reference and a wrong count are damage")
        void corrupted() {
            assertEquals(ViridianException.Reason.CORRUPTED, failure(V8Writer.blink().tag('o').latin1("a")));
            assertEquals(ViridianException.Reason.CORRUPTED, failure(V8Writer.blink().tag('"').varint(10).raw(new byte[3])));
            assertEquals(ViridianException.Reason.CORRUPTED, failure(V8Writer.blink().tag('^').varint(3)));
            assertEquals(ViridianException.Reason.CORRUPTED, failure(V8Writer.blink().tag('o').latin1("a").int32(1).tag('{').varint(2)));
        }

        @Test
        @DisplayName("mutated values fail only as damaged or unsupported data, and terminate")
        void fuzz() {
            var seeds = List.of(
                    V8Writer.blink().tag('o').latin1("a").tag('A').varint(2).int32(1).twoByte("你").tag('$').varint(0).varint(2)
                            .latin1("m").tag(';').number(1.5).tag('D').f64(5).tag(':').varint(2).tag('{').varint(2).toByteArray(),
                    V8Writer.blink().tag('B').varint(8).raw(new byte[8]).tag('V').varint('d').varint(0).varint(8).varint(0).toByteArray(),
                    V8Writer.blink().tag('\\').tag('K').raw(4).varint(13).varint(2).varint(2048).varint(3).raw(new byte[]{1, 0, 1}).varint(6).varint(8).varint(4).raw(new byte[4]).toByteArray());
            var random = new Random(41);
            for (var round = 0; round < 20_000; round++) {
                var data = seeds.get(round % seeds.size()).clone();
                var mutations = 1 + random.nextInt(4);
                for (var i = 0; i < mutations; i++) {
                    data[random.nextInt(data.length)] = (byte) random.nextInt(256);
                }
                var length = random.nextInt(4) == 0 ? random.nextInt(data.length + 1) : data.length;
                try {
                    StructuredClone.deserialize(java.util.Arrays.copyOf(data, length));
                } catch (ViridianException _) {
                    // The expected failure for damaged data
                }
            }
        }
    }
}
