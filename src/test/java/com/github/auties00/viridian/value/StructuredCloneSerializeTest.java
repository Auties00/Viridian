package com.github.auties00.viridian.value;

import com.github.auties00.viridian.ViridianException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The structured clone serializer: Chrome's own bytes for values Chrome wrote, the encodings V8 chooses, round trips
/// of every kind of value and of random graphs with shared objects and cycles, and the external objects a value
/// refers to.
class StructuredCloneSerializeTest {
    // Where V8's version is in a value: past Blink's 15-byte envelope and V8's version tag
    private static final int V8_VERSION_POSITION = 16;
    // Where the value itself starts: past both envelopes
    private static final int VALUE_POSITION = 17;

    private static byte[] serialize(JsValue value) {
        return StructuredClone.serialize(value).bytes();
    }

    private static JsValue roundTrip(JsValue value) throws ViridianException {
        var serialized = StructuredClone.serialize(value);
        return StructuredClone.deserialize(serialized);
    }

    /// {@return the bytes of the value itself, past both envelopes and before any trailer}
    private static String body(JsValue value) {
        var bytes = serialize(value);
        return HexFormat.of().formatHex(bytes, VALUE_POSITION, bytes.length);
    }

    private static JsBlob blob(String type, long size) {
        return new JsBlob(type, size, BlobSource.of(new byte[0]));
    }

    private static JsBlob file(String name, String type, long size, long lastModified) {
        return new JsFile(name, type, size, Instant.ofEpochMilli(lastModified), BlobSource.of(new byte[0]));
    }

    /// {@return what a reader keeps of a blob besides its bytes}
    private static String metadata(JsBlob external) {
        return switch (external) {
            case JsFile file -> "file " + file.name() + " " + file.type() + " " + file.size() + " " + file.lastModified().orElse(null);
            case JsBlob blob -> "blob " + blob.type() + " " + blob.size();
        };
    }

    @Nested
    @DisplayName("Chrome's bytes")
    class ChromeBytes {
        // Values Chrome for Testing 157.0.8103.38 wrote to IndexedDB on Windows, LevelDB backend, with their IndexedDB
        // wrapping undone: those of ci/chrome/fixture.js, and others a page made to cover the rest of the format
        static Stream<Arguments> values() {
            return Stream.of(
                Arguments.of("-0", List.of(),
                        "ff15fe000000000000000000000000ff104e0000000000000080"),
                Arguments.of("big int32", List.of(),
                        "ff15fe000000000000000000000000ff104e0000c0ffffffdf41"),
                Arguments.of("bigint", List.of(),
                        "ff15fe000000000000000000000000ff105a20d20a3f4eeee073c3f60fe98e01000000"),
                Arguments.of("blobs", List.of(blob("text/plain", 10), file("name.txt", "text/plain", 9, 1600000000000L), blob("application/octet-stream", 1500000)),
                        "ff15fe000000000000001f00000007ff1041035c69005c65015c6902240003a0000000026965"),
                Arguments.of("cycle", List.of(),
                        "ff15fe000000000000000000000000ff106f22046e616d6522056379636c65220473656c665e007b02"),
                Arguments.of("dom exception", List.of(),
                        "ff15fe000000000000002d00000006ff105c780d4e6f74466f756e644572726f720a697420697320676f6e6500a000000001" +
                        "78"),
                Arguments.of("geometry", List.of(),
                        "ff15fe00000000000001d40000000cff1041075c51000000000000f03f000000000000004000000000000008400000000000" +
                        "0010405c57000000000000144000000000000018400000000000000000000000000000f03f5c45000000000000f03f000000" +
                        "0000000040000000000000084000000000000010405c52000000000000000000000000000000000000000000002240000000" +
                        "00000022405c54000000000000000000000000000000000000000000000000000000000000f03f000000000000f03f000000" +
                        "00000000000000000000000000000000000000f03f000000000000f03f000000000000f03f00000000000000000000000000" +
                        "00f03f0000000000000000000000000000f03f0000000000000000000000000000f03f5c49000000000000f03f0000000000" +
                        "00004000000000000008400000000000001040000000000000144000000000000018405c55000000000000f03f0000000000" +
                        "000000000000000000000000000000000000000000000000000000000000000000f03f000000000000000000000000000000" +
                        "0000000000000000000000000000000000000000000000f03f00000000000000000000000000001c40000000000000204000" +
                        "00000000002240000000000000f03f240007a00000000751574552544955"),
                Arguments.of("holes", List.of(),
                        "ff15fe000000000000000000000000ff106103490049024904490622056578747261220870726f7065727479400303"),
                Arguments.of("image", List.of(),
                        "ff15fe000000000000002300000006ff105c230101030000020108ff0000ff00ff0080a00000000123"),
                Arguments.of("int", List.of(),
                        "ff15fe000000000000000000000000ff104954"),
                Arguments.of("latin1", List.of(),
                        "ff15fe000000000000000000000000ff102204636166e9"),
                Arguments.of("lone surrogate", List.of(),
                        "ff15fe000000000000000000000000ff10006306610000d86200"),
                Arguments.of("map", List.of(),
                        "ff15fe000000000000000000000000ff103b22036b657949026f2206736861726564547b01220a6f626a656374206b657949" +
                        "043b3a003a06"),
                Arguments.of("negative bigint", List.of(),
                        "ff15fe000000000000000000000000ff105a112a00000000000000"),
                Arguments.of("object", List.of(),
                        "ff15fe000000000000000000000000ff106f4904220b6e756d65726963206b657922016149022201626f2201634103490222" +
                        "0374776f6f220164307b012400037b012203312e35220e6672616374696f6e616c206b65797b04"),
                Arguments.of("regexp", List.of(),
                        "ff15fe000000000000000000000000ff10522206612b2862293fbf01"),
                Arguments.of("resizable", List.of(),
                        "ff15fe000000000000000000000000ff1041027e084009080700000000005e015642000003240002"),
                Arguments.of("set", List.of(),
                        "ff15fe000000000000000000000000ff10274902220374776f6f2206736861726564547b012c03"),
                Arguments.of("shared", List.of(),
                        "ff15fe000000000000000000000000ff1041036f2206736861726564547b015e016f2205616761696e5e017b01240003"),
                Arguments.of("sparse", List.of(),
                        "ff15fe000000000000000000000000ff1061c1843d490422046e6561724980897a22036661724002c1843d"),
                Arguments.of("two-byte", List.of(),
                        "ff15fe000000000000000000000000ff1000630a604f7d5920003dd800de"),
                Arguments.of("unicode sets", List.of(),
                        "ff15fe000000000000000000000000ff1052220e5b5c707b4c7d2d2d5b612d7a5d5d8002"),
                Arguments.of("views", List.of(),
                        "ff15fe000000000000000000000000ff10410d42100102030405060708090a0b0c0d0e0f1056420104005e0156770204005e" +
                        "0156430010005e0156620301005e0156570404005e0156640404005e0156440808005e0156660008005e0156460808005e01" +
                        "56710008005e0156510808005e01563f0507005e01566802060024000d"),
                Arguments.of("wrappers", List.of(),
                        "ff15fe000000000000000000000000ff104104786e0000000000000080732205626f7865647a100a00000000000000240004"),
                Arguments.of("aes single", List.of(),
                        "ff15fe000000000000002800000006ff105c4b010910071003030303030303030303030303030303a0000000014b"),
                Arguments.of("big index keys", List.of(),
                        "ff15fe000000000000000000000000ff106f4e000000000000d04149084e000000000000e04149024e0000c0ffffffef4149" +
                        "04220a3432393439363732393549067b04"),
                Arguments.of("bigints", List.of(),
                        "ff15fe000000000000000000000000ff1041035a400000000000000000000000000000000000000000000000000001000000" +
                        "0000005a21000000000000000001000000000000005a10ffffffffffffff7f240003"),
                Arguments.of("bitmap", List.of(),
                        "ff15fe00000000000000aa00000006ff105c67070000004033330340000000c0ed54ee3f0000002023b1aa3f0000002072d0" +
                        "b33f000000c0dcb5a43f000000000000000000000000000000000000000080e8db3f0000000040a6d83f000000000050c23f" +
                        "00000000807acc3f00000000a0f0e63f000000000008af3f0000000000808c3f0000000000dab83f00000000e0d9e63f0203" +
                        "0600040105010800000201080000ffff00800080a00000000167"),
                Arguments.of("blob type", List.of(blob("text/plain", 1), blob("", 1)),
                        "ff15fe000000000000001c00000006ff1041025c69005c6901240002a00000000169"),
                Arguments.of("chacha", List.of(),
                        "ff15fe000000000000003800000006ff105c4b0914030320bd74684df7ec5c2f0c24435b75ac3ce477048595cbcf405eefdb" +
                        "ef46ebb1e834a0000000014b"),
                Arguments.of("doubles", List.of(),
                        "ff15fe000000000000000000000000ff1041034e000000000000f83f4e00000000000000404e0000000000000840240003"),
                Arguments.of("ecdsa p521", List.of(),
                        "ff15fe00000000000000b800000006ff105c4b050e0103119e0130819b301006072a8648ce3d020106052b81040023038186" +
                        "00040109fdd6284bcd0cb2627dde925efad56d66f3a12bfe79d06bc1a4d16d6e57a8dd0b4e8a5465ae6f93b0473173be8f3c" +
                        "757356a52d2e062d05abf66b04bfa43e18d80106a6d74ad47732659f404643eef3174ce8c3b2261806b70aa7dd0cb71af917" +
                        "e3a15ddd6ed46770d3b6f85bcfe368fd1b213044716744e3c3dbf35f3c88597e1caba0000000014b"),
                Arguments.of("ed25519 public", List.of(),
                        "ff15fe000000000000004400000006ff105c4b071201112c302a300506032b65700321005742db493d8e2dca84118a664dab" +
                        "42ae44d431cee6dafbd4e83138bfdca7babca0000000014b"),
                Arguments.of("file list", List.of(file("one.txt", "text/plain", 3, 5L), file("two.bin", "", 3, 6L)),
                        "ff15fe000000000000001600000006ff105c4c020001a0000000014c"),
                Arguments.of("file list shared", List.of(file("x.txt", "", 1, 7L), file("x.txt", "", 1, 7L)),
                        "ff15fe000000000000001f00000007ff1041035c65005c4c01015e01240003a000000002654c"),
                Arguments.of("geometry ro", List.of(),
                        "ff15fe000000000000010e00000009ff1041045c59000000000000f03f000000000000000000000000000000000000000000" +
                        "0000000000000000000000000000000000f03f00000000000000000000000000000000000000000000000000000000000000" +
                        "00000000000000f03f00000000000000000000000000001c4000000000000020400000000000002240000000000000f03f5c" +
                        "4f000000000000f03f000000000000004000000000000008400000000000001040000000000000144000000000000018405c" +
                        "51000000000000f03f00000000000000000000000000000000000000000000f03f5c52000000000000f03f00000000000000" +
                        "4000000000000008400000000000001040240004a000000004594f5152"),
                Arguments.of("hmac single", List.of(),
                        "ff15fe000000000000002c00000006ff105c4b02140611140404040404040404040404040404040404040404a0000000014b"),
                Arguments.of("holey filled", List.of(),
                        "ff15fe000000000000000000000000ff106103490049024902490449044906400303"),
                Arguments.of("image f16", List.of(),
                        "ff15fe000000000000002300000006ff105c2301010301000101080000000000000000a00000000123"),
                Arguments.of("image p3", List.of(),
                        "ff15fe000000000000001f00000006ff105c23010303000001010401020304a00000000123"),
                Arguments.of("map -0", List.of(),
                        "ff15fe000000000000000000000000ff103b490022047a65726f4e000000000000f87f22036e616e3a04"),
                Arguments.of("mixed numbers", List.of(),
                        "ff15fe000000000000000000000000ff10410449022201614e000000000000f83f4e000000000000e041240004"),
                Arguments.of("pbkdf2", List.of(),
                        "ff15fe000000000000001a00000006ff105c4b06112003010203a0000000014b"),
                Arguments.of("rab views", List.of(),
                        "ff15fe000000000000000000000000ff1041037e0810000000000000000056420004025e01563f0200035e01565702000324" +
                        "0003"),
                Arguments.of("smi edges", List.of(),
                        "ff15fe000000000000000000000000ff1041044e000080ffffffcf414e000000000000d0414e000000000000d0c14e000040" +
                        "000000d0c1240004"),
                Arguments.of("sparse big", List.of(),
                        "ff15fe000000000000000000000000ff10618180808008490a2205736d616c6c4e000000000000e041220362696740028180" +
                        "808008"),
                Arguments.of("two-byte keys", List.of(),
                        "ff15fe000000000000000000000000ff106f6302604f49022201e949040063043dd800de49067b03"),
                Arguments.of("view first", List.of(),
                        "ff15fe000000000000000000000000ff1041034208010203040506070856570204005e015e01563f000800240003"),
                Arguments.of("x25519 public", List.of(),
                        "ff15fe000000000000004400000006ff105c4b081301012c302a300506032b656e032100c8012326d4e51b0f1f4bdfe07c85" +
                        "36a92b13eb70cd957a41f314359ff7913e26a0000000014b")
            );
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("values")
        @DisplayName("a value Chrome wrote serializes to Chrome's bytes, but for V8's version, with the same externals")
        void identical(String name, List<JsBlob> externals, String hex) throws ViridianException {
            var chrome = HexFormat.of().parseHex(hex);
            var serialized = StructuredClone.serialize(StructuredClone.deserialize(chrome, externals));
            // Chrome 157 writes V8's version 16, Viridian 15, which encodes these values alike
            assertEquals(16, chrome[V8_VERSION_POSITION]);
            chrome[V8_VERSION_POSITION] = 15;
            assertEquals(HexFormat.of().formatHex(chrome), HexFormat.of().formatHex(serialized.bytes()));
            assertEquals(externals.stream().map(StructuredCloneSerializeTest::metadata).toList(),
                    serialized.blobs().stream().map(StructuredCloneSerializeTest::metadata).toList());
        }
    }

    @Nested
    @DisplayName("envelope")
    class Envelope {
        @Test
        @DisplayName("Blink's version 21 with an empty trailer offset, then V8's version 15")
        void header() {
            assertEquals("ff15fe000000000000000000000000ff0f30", HexFormat.of().formatHex(serialize(JsValue.NULL)));
        }

        @Test
        @DisplayName("a trailer lists the platform interfaces the value holds, once each in the order first written")
        void trailer() {
            var point = new JsDomPoint(0, 0, 0, 1, false);
            var bytes = serialize(JsArray.of(point, new JsDomRect(0, 0, 1, 1, true), new JsDomPoint(1, 1, 1, 1, false)));
            var hex = HexFormat.of().formatHex(bytes);
            var offset = Integer.parseInt(hex.substring(6, 22), 16);
            assertEquals(offset, bytes.length - 7);
            assertEquals("00000007", hex.substring(22, 30));
            assertEquals("a0000000025152", hex.substring(offset * 2));
        }

        @Test
        @DisplayName("V8's version 16 only for a resizable buffer that may grow past 32 bits")
        void wideLengths() {
            assertEquals(15, serialize(JsArrayBuffer.ofResizable(new byte[1], 0xFFFF_FFFFL))[V8_VERSION_POSITION]);
            var wide = serialize(JsArrayBuffer.ofResizable(new byte[1], 0x1_0000_0000L));
            assertEquals(16, wide[V8_VERSION_POSITION]);
            assertEquals("7e01808080801000", HexFormat.of().formatHex(wide, VALUE_POSITION, wide.length));
        }
    }

    @Nested
    @DisplayName("encodings")
    class Encodings {
        @ParameterizedTest
        @ValueSource(doubles = {0, 1, -1, 1073741823, -1073741824})
        @DisplayName("a 31-bit integer is a small integer")
        void smallIntegers(double value) {
            assertTrue(body(new JsNumber(value)).startsWith("49"));
        }

        @ParameterizedTest
        @ValueSource(doubles = {-0.0, 0.5, 1073741824, -1073741825, Double.NaN, Double.POSITIVE_INFINITY})
        @DisplayName("any other number is a double")
        void doubles(double value) {
            assertTrue(body(new JsNumber(value)).startsWith("4e"));
        }

        @Test
        @DisplayName("an array of numbers that are not all small integers holds only doubles")
        void doubleArrays() {
            assertEquals("41024e000000000000f03f4e000000000000f83f240002", body(JsArray.of(new JsNumber(1), new JsNumber(1.5))));
            assertEquals("410249024904240002", body(JsArray.of(new JsNumber(1), new JsNumber(2))));
            assertEquals("41034902220161" + "4e000000000000f83f240003",
                    body(JsArray.of(new JsNumber(1), new JsString("a"), new JsNumber(1.5))));
        }

        @Test
        @DisplayName("a string is one byte a character when all fit, else two bytes aligned to two from the start")
        void strings() {
            assertEquals("2204636166e9", body(new JsString("café")));
            assertEquals("00" + "6302604f", body(new JsString("你")));
            // The object's tag and its key leave the string's tag at an even position, so it needs no padding
            assertEquals("6f22026162" + "6302604f" + "7b01", body(JsObject.builder().put("ab", "你").build()));
        }

        @Test
        @DisplayName("a BigInt's digits are padded to whole 64-bit words")
        void bigInts() {
            assertEquals("5a00", body(new JsBigInt(BigInteger.ZERO)));
            assertEquals("5a11" + "0100000000000000", body(new JsBigInt(BigInteger.ONE.negate())));
            assertEquals("5a20" + "0000000000000000" + "0100000000000000", body(new JsBigInt(BigInteger.TWO.pow(64))));
        }

        @Test
        @DisplayName("object keys that are array indexes come first, as numbers")
        void indexKeys() {
            var object = JsObject.builder().put("b", true).put("4294967295", JsValue.NULL).put("2147483648", JsValue.NULL)
                    .put("1", JsValue.NULL).build();
            assertEquals("6f" + "4902" + "30" + "4e000000000000e041" + "30" + "220162" + "54" + "220a34323934393637323935" + "30" + "7b04", body(object));
        }

        @Test
        @DisplayName("an array with holes, or read as sparse, is written by index")
        void sparseArrays() throws ViridianException {
            assertEquals("61034900" + "4902" + "4904" + "4904" + "400203", body(JsArray.builder().add(new JsNumber(1)).addHole().add(new JsNumber(2)).build()));
            var read = assertInstanceOf(JsArray.class, StructuredClone.deserialize(V8Writer.blink().tag('a').varint(1)
                    .int32(0).int32(7).tag('@').varint(1).varint(1).toByteArray()));
            assertTrue(read.isSparse());
            assertTrue(body(read).startsWith("6101"));
        }

        @Test
        @DisplayName("a view is written after its buffer, which it then refers back to")
        void views() {
            var buffer = JsArrayBuffer.of(new byte[]{1, 2, 3, 4});
            var first = JsArrayBufferView.of(JsArrayBufferView.Kind.UINT16, buffer, 2, 2);
            var second = JsArrayBufferView.of(JsArrayBufferView.Kind.DATA_VIEW, buffer);
            assertEquals("4103" + "420401020304" + "5657020200" + "5e01" + "563f000400" + "5e02" + "240003",
                    body(JsArray.of(first, second, first)));
            var resizable = JsArrayBuffer.ofResizable(new byte[]{1, 2}, 8);
            assertEquals("7e0208" + "0102" + "5642010003", body(JsArrayBufferView.ofLengthTracking(JsArrayBufferView.Kind.UINT8, resizable, 1)));
        }
    }

    @Nested
    @DisplayName("round trips")
    class RoundTrips {
        static Stream<Arguments> values() throws Exception {
            var keys = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            var buffer = JsArrayBuffer.of(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
            var resizable = JsArrayBuffer.ofResizable(new byte[]{1, 2, 3}, 100);
            var error = JsError.builder(JsError.Type.TYPE).message("message").stack("TypeError: message\n    at <anonymous>");
            error.cause(error);
            return Stream.of(
                    Arguments.of("primitives", JsArray.of(JsValue.UNDEFINED, JsValue.NULL, JsBoolean.TRUE, new JsNumber(-0.0),
                            new JsNumber(Double.NaN), new JsBigInt(BigInteger.TWO.pow(100).negate()), new JsString("a\ud800\u4f60"))),
                    Arguments.of("object", JsObject.builder().put("10", 1).put("x", JsObject.builder()).put("2", JsValue.NULL).build()),
                    Arguments.of("sparse array", JsArray.builder().length(4_000_000_000L).set(3_999_999_999L, JsValue.of(true))
                            .put("p", JsValue.NULL).build()),
                    Arguments.of("dates and regexps", JsArray.of(new JsDate(1.5), new JsDate(Double.NaN), new JsRegExp("a/b", "yg"), new JsRegExp("[x]", "v"))),
                    Arguments.of("maps and sets", JsArray.of(JsMap.builder().put(JsValue.of(-0.0), JsValue.of("zero")).put(buffer, buffer).build(),
                            JsSet.of(JsValue.of(Double.NaN), JsValue.of(Double.NaN)))),
                    Arguments.of("buffers and views", JsArray.of(JsArrayBufferView.of(JsArrayBufferView.Kind.BIGINT64, buffer),
                            JsArrayBufferView.of(JsArrayBufferView.Kind.FLOAT16, buffer, 2, 4), JsArrayBufferView.ofLengthTracking(JsArrayBufferView.Kind.DATA_VIEW, resizable, 1),
                            JsArrayBuffer.ofImmutable(new byte[]{9}))),
                    Arguments.of("wrappers", JsArray.of(JsWrapper.of(JsBoolean.FALSE), JsWrapper.of(new JsNumber(-0.0)), JsWrapper.of(new JsBigInt(BigInteger.TEN)),
                            JsWrapper.of(new JsString("\u4f60")))),
                    Arguments.of("errors", JsArray.of(error.build(), JsError.of(JsError.Type.ERROR, null),
                            JsError.builder(JsError.Type.URI).message("").cause(JsValue.UNDEFINED).build())),
                    Arguments.of("crypto keys", JsArray.of(
                            JsCryptoKey.secret(SecretKeyAlgorithm.AES_GCM, new byte[16]).usages(JsCryptoKey.Usage.ENCRYPT).extractable(true).build(),
                            JsCryptoKey.secret(SecretKeyAlgorithm.hmac(KeyAlgorithm.Hash.SHA_256), new byte[]{1, 2, 3}).usages(JsCryptoKey.Usage.SIGN).extractable(true).build(),
                            JsCryptoKey.publicKey(KeyPairAlgorithm.ED25519, keys.getPublic()).usages(JsCryptoKey.Usage.VERIFY).extractable(true).build())),
                    Arguments.of("blobs and files", JsArray.of(JsBlob.of(new byte[]{1, 2}, "Text/Plain"), JsFile.of("a.txt", new byte[]{3}, "", Instant.ofEpochMilli(5)),
                            JsFileList.of(JsFile.of("b", new byte[0], "x/y", Instant.EPOCH)))),
                    Arguments.of("images", JsArray.of(JsImageData.of(1, 1, new byte[]{1, 2, 3, 4}),
                            JsImageData.of(1, 1, JsColorSpace.DISPLAY_P3, JsImageData.PixelFormat.RGBA_FLOAT16, new byte[8]))),
                    Arguments.of("geometry", JsArray.of(new JsDomPoint(1, 2, 3, 4, true), new JsDomRect(1, 2, 3, 4, false),
                            new JsDomQuad(new JsDomPoint(0, 0, 0, 1, false), new JsDomPoint(1, 0, 0, 1, false), new JsDomPoint(1, 1, 0, 1, false), new JsDomPoint(0, 1, 0, 1, false)),
                            JsDomMatrix.of(new double[]{1, 0, 0, 1, 5, 6}, true), JsDomMatrix.of(new double[16], false))),
                    Arguments.of("exceptions", JsArray.of(new JsDomException("NotFoundError", "gone"), new JsDomException("QuotaExceededError", "full")))
            );
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("values")
        @DisplayName("a value reads back as the value written, and writes again to the same bytes")
        void reads(String name, JsValue value) throws ViridianException {
            var read = roundTrip(value);
            assertEquals(JsDescriber.describe(value), JsDescriber.describe(read));
            assertArrayEquals(serialize(value), serialize(read));
        }

        @Test
        @DisplayName("random graphs, shared objects and cycles included, read back as written")
        void randomGraphs() throws ViridianException {
            for (var seed = 0; seed < 400; seed++) {
                var value = RandomValues.graph(seed, 80);
                assertEquals(JsDescriber.describe(value), JsDescriber.describe(roundTrip(value)), "seed " + seed);
            }
        }

        @Test
        @DisplayName("an object held twice reads back as one object, and a cycle as a cycle")
        void identity() throws ViridianException {
            var shared = JsObject.builder().build();
            var cyclic = JsArray.builder();
            cyclic.add(cyclic).add(shared).add(shared);
            var read = assertInstanceOf(JsArray.class, roundTrip(cyclic.build()));
            assertSame(read, read.get(0).orElseThrow());
            assertSame(read.get(1).orElseThrow(), read.get(2).orElseThrow());
        }

        @Test
        @DisplayName("a lone surrogate in a string Blink writes as UTF-8 becomes U+FFFD, which Chrome reads")
        void loneSurrogates() throws ViridianException {
            var read = assertInstanceOf(JsDomException.class, roundTrip(new JsDomException("Error", "a\ud800b")));
            assertEquals("a\ufffdb", read.message());
        }
    }

    @Nested
    @DisplayName("externals")
    class Externals {
        @Test
        @DisplayName("blobs, files and the files of lists are listed in the order the value refers to them")
        void order() {
            var blob = JsBlob.of(new byte[]{1}, "a/b");
            var file = JsFile.of("f", new byte[]{2, 3}, "", Instant.ofEpochMilli(7));
            var serialized = StructuredClone.serialize(JsArray.of(blob, JsFileList.of(file, file), file, blob));
            assertEquals(List.of("blob a/b 1", "file f  2 1970-01-01T00:00:00.007Z", "file f  2 1970-01-01T00:00:00.007Z",
                    "file f  2 1970-01-01T00:00:00.007Z"), serialized.blobs().stream().map(StructuredCloneSerializeTest::metadata).toList());
            assertSame(blob, serialized.blobs().getFirst());
            assertSame(file, serialized.blobs().get(1));
            var hex = HexFormat.of().formatHex(serialized.bytes());
            assertTrue(hex.contains("5c6900" + "5c4c02" + "0102" + "5c6503" + "5e01"), hex);
            assertTrue(hex.endsWith("a000000003694c65"), hex);
        }

        @Test
        @DisplayName("a blob or file read without its bytes is listed without them")
        void noSource() throws ViridianException {
            var inline = V8Writer.blink().tag('\\').tag('b').blinkString("uuid").blinkString("text/plain").varint(5).toByteArray();
            var serialized = StructuredClone.serialize(StructuredClone.deserialize(inline));
            var blob = serialized.blobs().getFirst();
            assertFalse(blob.hasBytes());
            assertEquals(5, blob.size());
        }
    }

    @Nested
    @DisplayName("refusals")
    class Refusals {
        @Test
        @DisplayName("a File System Access handle, whose grant only its browser holds")
        void handle() {
            assertThrows(IllegalArgumentException.class, () -> StructuredClone.serialize(JsArray.of(new JsFileSystemHandle(JsFileSystemHandle.Kind.FILE, "a", null))));
        }

        @Test
        @DisplayName("a value nested deeper than Chrome reads back")
        void depth() {
            assertThrows(IllegalArgumentException.class, () -> StructuredClone.serialize(nested(1000)));
            StructuredClone.serialize(nested(999));
        }

        // Arrays nested `levels` deep around a number, which is one level more
        private static JsValue nested(int levels) {
            JsValue value = new JsNumber(1);
            for (var i = 0; i < levels; i++) {
                value = JsArray.of(value);
            }
            return value;
        }
    }
}
