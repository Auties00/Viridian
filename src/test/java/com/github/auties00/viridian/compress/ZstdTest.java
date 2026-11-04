package com.github.auties00.viridian.compress;

import com.github.auties00.viridian.compress.ZstdTestData.Shape;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDictTrainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.github.auties00.viridian.compress.ZstdTestData.CHECKSUM;
import static com.github.auties00.viridian.compress.ZstdTestData.SINGLE_SEGMENT;
import static com.github.auties00.viridian.compress.ZstdTestData.compress;
import static com.github.auties00.viridian.compress.ZstdTestData.compressStreaming;
import static com.github.auties00.viridian.compress.ZstdTestData.concat;
import static com.github.auties00.viridian.compress.ZstdTestData.descriptor;
import static com.github.auties00.viridian.compress.ZstdTestData.generate;
import static com.github.auties00.viridian.compress.ZstdTestData.skippable;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Checks [Zstd] against hand-written frames and against zstd-jni, the reference implementation, across levels,
/// sizes, data shapes and frame variants, and checks that malformed frames fail with precise exceptions.
@DisplayName("Zstd")
class ZstdTest {
    private static byte[] decompress(byte[] frames) throws IOException {
        return Zstd.decompress(frames, 0, frames.length, Integer.MAX_VALUE);
    }

    private static byte[] hex(String hex) {
        return HexFormat.of().parseHex(hex);
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static IOException rejects(byte[] frames) {
        return assertThrows(IOException.class, () -> decompress(frames));
    }

    private static void assertMessage(IOException exception, String fragment) {
        assertTrue(exception.getMessage().contains(fragment), () -> "\"" + exception.getMessage() + "\" lacks \"" + fragment + "\"");
    }

    @Nested
    @DisplayName("hand-written frames")
    class KnownAnswers {
        @Test
        @DisplayName("a raw block decodes to its bytes")
        void rawBlock() throws IOException {
            assertArrayEquals(ascii("hello"), decompress(hex("28b52ffd" + "20" + "05" + "290000" + "68656c6c6f")));
        }

        @Test
        @DisplayName("an RLE block repeats its byte")
        void rleBlock() throws IOException {
            assertArrayEquals(ascii("aaaaaaaaaa"), decompress(hex("28b52ffd" + "20" + "0a" + "530000" + "61")));
        }

        @Test
        @DisplayName("an empty frame decodes to nothing")
        void emptyFrame() throws IOException {
            assertArrayEquals(new byte[0], decompress(hex("28b52ffd" + "20" + "00" + "010000")));
        }

        @Test
        @DisplayName("a frame without a content size concatenates its raw and RLE blocks")
        void multipleBlocks() throws IOException {
            assertArrayEquals(ascii("abccc"), decompress(hex("28b52ffd" + "00" + "00" + "100000" + "6162" + "1b0000" + "63")));
        }

        @Test
        @DisplayName("a 2-byte content size counts from 256")
        void twoByteContentSize() throws IOException {
            var expected = new byte[300];
            Arrays.fill(expected, (byte) 'z');
            assertArrayEquals(expected, decompress(hex("28b52ffd" + "40" + "00" + "2c00" + "630900" + "7a")));
        }

        @Test
        @DisplayName("a window descriptor with a mantissa decodes")
        void windowMantissa() throws IOException {
            // Exponent 3 and mantissa 5: a window of 8 KiB plus five eighths of it
            assertArrayEquals(ascii("ab"), decompress(hex("28b52ffd" + "00" + "1d" + "110000" + "6162")));
        }

        @Test
        @DisplayName("a dictionary id of zero means no dictionary")
        void zeroDictionaryId() throws IOException {
            assertArrayEquals(ascii("hello"), decompress(hex("28b52ffd" + "22" + "0000" + "05" + "290000" + "68656c6c6f")));
        }

        @Test
        @DisplayName("a raw block with a content checksum verifies it")
        void checksummedRawBlock() throws IOException {
            assertArrayEquals(ascii("hello"), decompress(hex("28b52ffd240529000068656c6c6fa36d9f88")));
        }

        @Test
        @DisplayName("a compressed block with raw literals and predefined tables decodes")
        void predefinedSequences() throws IOException {
            var expected = ascii("The quick brown fox jumps over the lazy dog. The quick brown fox jumps over the lazy dog!");
            var frame = hex("28b52ffd2059b50100e40254686520717569636b2062726f776e20666f78206a756d7073206f76657220746865"
                    + "206c617a7920646f672e20210100059aaa0c");
            assertArrayEquals(expected, decompress(frame));
        }

        @Test
        @DisplayName("compressed blocks with Huffman literals and FSE tables decode")
        void huffmanAndFse() throws IOException {
            var words = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu".split(" ");
            var random = new Random(7);
            var builder = new StringBuilder();
            while (builder.length() < 600) {
                builder.append(words[random.nextInt(words.length)]).append(random.nextInt(10) == 0 ? ".\n" : " ");
            }
            var expected = ascii(builder.substring(0, 600));
            var level19 = hex("28b52ffd645801b5050092040e11c0b70166c2b6ddd24c64cd599571a8e1f475ad985a0f69b5d6bad6bac4873002"
                    + "4c75aa1701e8d3ce89e18c83c7e6ed6cdf7d317392a0c53ea8605592c2b40620448094837c11600c854a4281956a0edfc11f"
                    + "84006520a27affd1060cdad3a99c0cf941f968e9f15e62d5282ec044f1241e53e1547431317dffde8e69b0847ff198ac41fb"
                    + "40fd6b79d164c4b0e996def768a050309fd6979e93f140441ac586a4d6db622754417736b07c5449fae72c4095020a7a7966");
            var levelMinus4 = hex("28b52ffd605801d508003408657073696c6f6e206b617070612065746120616c7068746865746164656c746120"
                    + "6c616d626467616d6d2e0a616c706874686574657461207a6574612e0a626574616574612e0a696f747a2e0a67616d6d6d75"
                    + "2e0a6d75206d757a6574612e0a6265746d7520616c70686d75207468656d7520626574612e0a6c616d626461206574614320"
                    + "2042c5a0edca01d280f117a2dc9006ec07a81b008c0067809c55f560dde05e005b030c10433d705e7826f40d6a3444310072"
                    + "b833bc0c7e5f201aa03090ce85a0cc8023875a34d1b8f041c022d3690090017ee0756168004406c800031cfc3f206a407402"
                    + "8e06605079bcf03117e521063021602ac013cd3d6267b0b389f4c0ba0820131e297f2620f10f59e784532408222801");
            assertArrayEquals(expected, decompress(level19));
            assertArrayEquals(expected, decompress(levelMinus4));
        }

        @Test
        @DisplayName("only the given range of the array is decoded")
        void subrange() throws IOException {
            var frame = hex("28b52ffd" + "20" + "05" + "290000" + "68656c6c6f");
            var padded = concat(ascii("garbage"), frame, ascii("trailer"));
            assertArrayEquals(ascii("hello"), Zstd.decompress(padded, 7, frame.length, 5));
        }

        @Test
        @DisplayName("an empty range and skippable frames alone decode to nothing")
        void noFrames() throws IOException {
            assertArrayEquals(new byte[0], decompress(new byte[0]));
            assertArrayEquals(new byte[0], decompress(skippable(3, 10, 1)));
        }
    }

    @Nested
    @DisplayName("round trips through zstd-jni")
    class RoundTrips {
        private static final int[] SMALL_SIZES = {0, 1, 3, 7, 32, 100, 449, 450, 1000, 4096, 20_000, 65_536, 131_072, 131_073, 300_000};

        private static final int[] LARGE_LEVELS = {-7, -4, -1, 1, 3, 6, 9, 12, 15, 19};

        static Stream<Arguments> smallInputs() {
            var cases = new ArrayList<Arguments>();
            for (var level = -7; level <= 19; level++) {
                for (var shape : Shape.values()) {
                    for (var size : SMALL_SIZES) {
                        cases.add(Arguments.of(level, shape, size));
                    }
                }
            }
            return cases.stream();
        }

        static Stream<Arguments> largeInputs() {
            var cases = new ArrayList<Arguments>();
            for (var level : LARGE_LEVELS) {
                for (var shape : Shape.values()) {
                    cases.add(Arguments.of(level, shape, 1 << 20));
                    if (level < 12) {
                        cases.add(Arguments.of(level, shape, 3 << 20));
                    }
                }
            }
            return cases.stream();
        }

        static Stream<Arguments> ultraInputs() {
            return Stream.of(20, 21, 22).flatMap(level -> Stream.of(
                    Arguments.of(level, Shape.DISTANT, 3 << 20),
                    Arguments.of(level, Shape.TEXT, 2 << 20)));
        }

        private static void roundTrip(int level, Shape shape, int size) throws IOException {
            var data = generate(shape, size);
            assertArrayEquals(data, decompress(compress(data, level, false)));
        }

        @ParameterizedTest(name = "level {0}, {1}, {2} bytes")
        @MethodSource("smallInputs")
        @DisplayName("inputs up to 300 KB at every level from -7 to 19")
        void small(int level, Shape shape, int size) throws IOException {
            roundTrip(level, shape, size);
        }

        @ParameterizedTest(name = "level {0}, {1}, {2} bytes")
        @MethodSource("largeInputs")
        @DisplayName("inputs of 1 and 3 MB")
        void large(int level, Shape shape, int size) throws IOException {
            roundTrip(level, shape, size);
        }

        @ParameterizedTest(name = "level {0}, {1}, {2} bytes")
        @MethodSource("ultraInputs")
        @DisplayName("ultra levels on inputs large enough for their windows")
        void ultra(int level, Shape shape, int size) throws IOException {
            roundTrip(level, shape, size);
        }

        @ParameterizedTest(name = "level {0}")
        @ValueSource(ints = {1, 3, 9})
        @DisplayName("long-distance matching reaches back megabytes")
        void longDistance(int level) throws IOException {
            var data = generate(Shape.DISTANT, 6 << 20);
            byte[] frame;
            try (var context = new ZstdCompressCtx()) {
                context.setLevel(level);
                context.setLong(27);
                context.setWindowLog(27);
                frame = context.compress(data);
            }
            // Only matches 3 MB back make the random data compress
            assertTrue(frame.length < data.length * 3 / 4, "the frame does not use long matches");
            assertArrayEquals(data, decompress(frame));
        }

        @ParameterizedTest(name = "window log {0}")
        @ValueSource(ints = {10, 11, 14, 17, 20, 23, 27})
        @DisplayName("windows from 1 KB to 128 MB bound their frames' matches and blocks")
        void windowLogs(int windowLog) throws IOException {
            var data = concat(generate(Shape.TEXT, 1 << 20), generate(Shape.DISTANT, 2 << 20), generate(Shape.REPETITIVE, 300_000));
            byte[] frame;
            try (var context = new ZstdCompressCtx()) {
                context.setLevel(6);
                context.setContentSize(false);
                context.setWindowLog(windowLog);
                frame = context.compress(data);
            }
            // The compressor shrinks a window larger than the input it knows of
            var window = frame[5] & 0xFF;
            assertEquals(Math.min(windowLog, 22), 10 + (window >>> 3), "the window descriptor");
            assertArrayEquals(data, decompress(frame));
        }
    }

    @Nested
    @DisplayName("frame variants")
    class Variants {
        static Stream<Arguments> checksumCases() {
            // The slow levels skip the largest input
            return Stream.of(-4, 1, 3, 12, 19).flatMap(level -> Stream.of(Shape.values())
                    .flatMap(shape -> IntStream.of(0, 31, 32, 33, 1000, 131_073, 1 << 20)
                            .filter(size -> level < 12 || size < 1 << 20)
                            .mapToObj(size -> Arguments.of(level, shape, size))));
        }

        @ParameterizedTest(name = "level {0}, {1}, {2} bytes")
        @MethodSource("checksumCases")
        @DisplayName("checksummed frames verify their checksum")
        void checksummed(int level, Shape shape, int size) throws IOException {
            var data = generate(shape, size);
            var frame = compress(data, level, true);
            assertEquals(CHECKSUM, descriptor(frame) & CHECKSUM);
            assertArrayEquals(data, decompress(frame));
        }

        @ParameterizedTest(name = "level {0}, {1}, {2} bytes")
        @MethodSource("checksumCases")
        @DisplayName("frames without a content size decode, with and without a checksum")
        void streamed(int level, Shape shape, int size) throws IOException {
            var data = generate(shape, size);
            for (var checksum : new boolean[]{false, true}) {
                var frame = compressStreaming(data, level, checksum);
                // A stream that ends before any write knows its size is 0 and declares it
                if (size > 0) {
                    assertEquals(0, descriptor(frame) & 0xE0, "the frame declares a content size");
                }
                assertArrayEquals(data, decompress(frame));
            }
        }

        @ParameterizedTest(name = "level {0}")
        @ValueSource(ints = {-7, -1, 1, 5, 19})
        @DisplayName("single-segment frames decode")
        void singleSegment(int level) throws IOException {
            for (var size : new int[]{0, 1, 255, 256, 257, 65_791, 65_792, 100_000}) {
                var data = generate(Shape.TEXT, size);
                var frame = compress(data, level, false);
                assertEquals(SINGLE_SEGMENT, descriptor(frame) & SINGLE_SEGMENT, "the frame is not single-segment");
                assertArrayEquals(data, decompress(frame));
            }
        }

        @Test
        @DisplayName("concatenated frames decode to their concatenated content")
        void concatenated() throws IOException {
            var parts = List.of(generate(Shape.TEXT, 5000), generate(Shape.RANDOM, 300), new byte[0],
                    generate(Shape.BINARY, 200_000), generate(Shape.REPETITIVE, 1_000_000), generate(Shape.TEXT, 1));
            var frames = new ArrayList<byte[]>();
            var expected = new ArrayList<byte[]>();
            for (var i = 0; i < parts.size(); i++) {
                var part = parts.get(i);
                frames.add(i % 2 == 0 ? compress(part, i * 4 - 3, i % 3 == 0) : compressStreaming(part, i, true));
                expected.add(part);
            }
            assertArrayEquals(concat(expected.toArray(byte[][]::new)), decompress(concat(frames.toArray(byte[][]::new))));
        }

        @Test
        @DisplayName("skippable frames before, between and after frames are skipped")
        void skippableFrames() throws IOException {
            var first = generate(Shape.TEXT, 10_000);
            var second = generate(Shape.BINARY, 70_000);
            var frames = concat(skippable(0, 0, 1), compress(first, 3, true), skippable(5, 17, 2), skippable(15, 70_000, 3),
                    compressStreaming(second, -2, false), skippable(9, 1, 4));
            assertArrayEquals(concat(first, second), decompress(frames));
        }

        @Test
        @DisplayName("a frame ending exactly at maxLength decodes")
        void exactMaxLength() throws IOException {
            var data = generate(Shape.TEXT, 50_000);
            for (var frame : List.of(compress(data, 3, false), compressStreaming(data, 3, false))) {
                assertArrayEquals(data, Zstd.decompress(frame, 0, frame.length, data.length));
            }
        }
    }

    @Nested
    @DisplayName("malformed input")
    class Rejections {
        @Test
        @DisplayName("a frame with a dictionary id fails")
        void dictionaryId() {
            // Single-segment headers with a 1, 2 and 4-byte dictionary id before the content size
            for (var header : List.of("21" + "07", "22" + "3412", "23" + "78563412")) {
                assertMessage(rejects(hex("28b52ffd" + header + "05" + "290000" + "68656c6c6f")), "dictionaries are not supported");
            }
        }

        @Test
        @DisplayName("a frame compressed with a trained dictionary fails")
        void trainedDictionary() {
            var trainer = new ZstdDictTrainer(1 << 20, 4096);
            var random = new Random(11);
            for (var i = 0; i < 2000; i++) {
                var sample = generate(Shape.TEXT, 1000 + random.nextInt(50) * 10);
                trainer.addSample(Arrays.copyOfRange(sample, random.nextInt(500), sample.length));
            }
            var dictionary = trainer.trainSamples();
            byte[] frame;
            try (var context = new ZstdCompressCtx()) {
                context.setLevel(3);
                context.setDictID(true);
                context.loadDict(dictionary);
                frame = context.compress(generate(Shape.TEXT, 4000));
            }
            assertNotEquals(0, descriptor(frame) & 3, "the frame names no dictionary");
            assertMessage(rejects(frame), "dictionaries are not supported");
        }

        @Test
        @DisplayName("output past maxLength fails, whether or not the frame declares its size")
        void maxLengthExceeded() {
            var data = generate(Shape.TEXT, 100_000);
            for (var frame : List.of(compress(data, 3, false), compressStreaming(data, 3, false))) {
                assertMessage(assertThrows(IOException.class, () -> Zstd.decompress(frame, 0, frame.length, data.length - 1)), "allowed");
            }
            var twice = concat(compress(data, 1, false), compressStreaming(data, 1, false));
            assertMessage(assertThrows(IOException.class, () -> Zstd.decompress(twice, 0, twice.length, 2 * data.length - 1)), "allowed");
        }

        @Test
        @DisplayName("every truncation of a frame fails")
        void truncated() {
            var frames = List.of(compress(generate(Shape.TEXT, 3000), 19, true), compress(generate(Shape.BINARY, 2000), -4, false),
                    compressStreaming(generate(Shape.REPETITIVE, 5000), 3, false), compress(generate(Shape.RANDOM, 500), 3, true));
            for (var frame : frames) {
                for (var length = 1; length < frame.length; length++) {
                    var prefix = Arrays.copyOf(frame, length);
                    assertThrows(IOException.class, () -> decompress(prefix), () -> "a prefix of " + prefix.length + " bytes decoded");
                }
            }
            var large = compress(generate(Shape.DISTANT, 1 << 20), 6, false);
            for (var length = 1; length < large.length; length += 997) {
                var prefix = Arrays.copyOf(large, length);
                assertThrows(IOException.class, () -> decompress(prefix));
            }
        }

        @Test
        @DisplayName("a corrupted checksum fails")
        void corruptedChecksum() {
            var frame = compress(generate(Shape.TEXT, 20_000), 3, true);
            for (var bit = 0; bit < 32; bit++) {
                var corrupted = frame.clone();
                corrupted[corrupted.length - 4 + bit / 8] ^= (byte) (1 << (bit % 8));
                assertMessage(rejects(corrupted), "checksum");
            }
        }

        @Test
        @DisplayName("bytes after the last frame fail")
        void trailingBytes() {
            var frame = compress(generate(Shape.TEXT, 1000), 3, false);
            for (var trailer : List.of("00", "28b5", "28b52f", "00000000", "28b52ffd")) {
                rejects(concat(frame, hex(trailer)));
            }
        }

        @Test
        @DisplayName("an unknown or legacy magic number fails")
        void unknownMagic() {
            assertMessage(rejects(hex("0123456789")), "not the magic number");
            assertMessage(rejects(hex("27b52ffd" + "00000000")), "legacy zstd v0.7");
            assertMessage(rejects(hex("1eb52ffd" + "00000000")), "legacy zstd v0.1");
        }

        @Test
        @DisplayName("a reserved block type fails")
        void reservedBlockType() {
            assertMessage(rejects(hex("28b52ffd" + "20" + "05" + "2f0000" + "68656c6c6f")), "reserved block type");
        }

        @Test
        @DisplayName("a header with its reserved bit set fails")
        void reservedHeaderBit() {
            assertMessage(rejects(hex("28b52ffd" + "28" + "05" + "290000" + "68656c6c6f")), "reserved bit");
        }

        @Test
        @DisplayName("a block above the maximum block size fails")
        void oversizedBlock() {
            // A 1 KB window allows 1 KB blocks; the RLE block claims 1025 bytes
            assertMessage(rejects(hex("28b52ffd" + "00" + "00" + "0b2000" + "61")), "maximum block size");
            // A 1 MB window still allows only 128 KB blocks
            assertMessage(rejects(hex("28b52ffd" + "00" + "50" + "0b0010" + "61")), "maximum block size");
        }

        @Test
        @DisplayName("a window above 2 GB fails")
        void oversizedWindow() throws IOException {
            assertArrayEquals(ascii("ab"), decompress(hex("28b52ffd" + "00" + "a8" + "110000" + "6162")));
            assertMessage(rejects(hex("28b52ffd" + "00" + "a9" + "110000" + "6162")), "window");
            assertMessage(rejects(hex("28b52ffd" + "00" + "f8" + "110000" + "6162")), "window");
        }

        @Test
        @DisplayName("content that differs from the declared size fails")
        void contentSizeMismatch() {
            assertMessage(rejects(hex("28b52ffd" + "20" + "06" + "290000" + "68656c6c6f")), "declares");
            // A 4-byte content size of 4, so the 5-byte block fits the 1 KB window but not the declared size
            assertMessage(rejects(hex("28b52ffd" + "80" + "00" + "04000000" + "290000" + "68656c6c6f")), "declares");
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Shape.class)
        @DisplayName("a match reaching before the frame fails")
        void matchBeforeFrame(Shape shape) {
            // A raw-content dictionary has no id, so its frames are only recognisable by matches into the dictionary
            var dictionary = generate(shape, 50_000);
            byte[] frame;
            try (var context = new ZstdCompressCtx()) {
                context.setLevel(3);
                context.loadDict(dictionary);
                frame = context.compress(Arrays.copyOfRange(dictionary, 10_000, 30_000));
            }
            assertMessage(rejects(frame), "before the start of its frame");
        }
    }
}
