package com.github.auties00.viridian.compress;

import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;
import com.github.luben.zstd.ZstdOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/// Deterministic inputs of several shapes and the zstd-jni calls the tests use as the reference implementation.
final class ZstdTestData {
    /// The frame descriptor bit of single-segment frames.
    static final int SINGLE_SEGMENT = 0x20;

    /// The frame descriptor bit of checksummed frames.
    static final int CHECKSUM = 0x04;

    /// The inputs already generated, by shape and size, since many tests reuse them.
    private static final Map<String, byte[]> CACHE = new ConcurrentHashMap<>();

    /// The words of the English-like text.
    private static final String[] WORDS = ("the of and to a in is it you that he was for on are with as I his they be at one have this from or "
            + "had by hot word but what some we can out other were all there when up use your how said an each she which do their time if "
            + "will way about many then them write would like so these her long make thing see him two has look more day could go come did "
            + "number sound no most people my over know water than call first who may down side been now find any new work part take get "
            + "place made live where after back little only round man year came show every good me give our under name very through just "
            + "form sentence great think say help low line differ turn cause much mean before move right boy old too same tell does set "
            + "three want air well also play small end put home read hand port large spell add even land here must big high such follow "
            + "IndexedDB Chromium LevelDB blob record compression window offset literal sequence").split(" ");

    private ZstdTestData() {
    }

    /// The shapes of input the round trips cover.
    enum Shape {
        /// Uniformly random bytes, which zstd stores raw.
        RANDOM,
        /// Runs of one byte and of short repeated patterns, which RLE blocks and literals and overlapping matches cover.
        REPETITIVE,
        /// English-like text, which Huffman-coded literals and short matches cover.
        TEXT,
        /// Structured binary records, varied integers and runs of zeros, close to what a database stores.
        BINARY,
        /// Random data whose first half comes back with sparse changes, so matches reach back half the input.
        DISTANT
    }

    /// {@return the input of `shape` and `size`, the same on every call}
    static byte[] generate(Shape shape, int size) {
        return CACHE.computeIfAbsent(shape + ":" + size, _ -> switch (shape) {
            case RANDOM -> random(size, size);
            case REPETITIVE -> repetitive(size);
            case TEXT -> text(size);
            case BINARY -> binary(size);
            case DISTANT -> distant(size);
        });
    }

    /// {@return `size` random bytes from `seed`}
    static byte[] random(int size, long seed) {
        var bytes = new byte[size];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    /// {@return the frame zstd-jni compresses `data` into at `level`, with its content size and an optional checksum}
    static byte[] compress(byte[] data, int level, boolean checksum) {
        try (var context = new ZstdCompressCtx()) {
            context.setLevel(level);
            context.setChecksum(checksum);
            context.setContentSize(true);
            return context.compress(data);
        }
    }

    /// {@return the frame zstd-jni streams `data` into at `level`, written in chunks, so the header has no content
    /// size}
    static byte[] compressStreaming(byte[] data, int level, boolean checksum) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var stream = new ZstdOutputStream(bytes, level)) {
                stream.setChecksum(checksum);
                for (var offset = 0; offset < data.length; offset += 7919) {
                    stream.write(data, offset, Math.min(7919, data.length - offset));
                }
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /// {@return what zstd-jni decompresses `frames` to, or `null` if it rejects them or they exceed `maxLength`}
    static byte[] referenceDecompress(byte[] frames, int maxLength) {
        try (var context = new ZstdDecompressCtx()) {
            var output = new byte[maxLength];
            var size = context.decompressByteArray(output, 0, output.length, frames, 0, frames.length);
            return Arrays.copyOf(output, size);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /// {@return the frame descriptor of the zstd frame `frame` starts with}
    static int descriptor(byte[] frame) {
        return frame[4] & 0xFF;
    }

    /// {@return a skippable frame with magic `0x184D2A50 + variant` and `size` bytes of payload}
    static byte[] skippable(int variant, int size, long seed) {
        var frame = new byte[8 + size];
        var magic = 0x184D2A50 + variant;
        for (var i = 0; i < 4; i++) {
            frame[i] = (byte) (magic >>> (8 * i));
            frame[4 + i] = (byte) (size >>> (8 * i));
        }
        System.arraycopy(random(size, seed), 0, frame, 8, size);
        return frame;
    }

    /// {@return the concatenation of `parts`}
    static byte[] concat(byte[]... parts) {
        var bytes = new ByteArrayOutputStream();
        for (var part : parts) {
            bytes.writeBytes(part);
        }
        return bytes.toByteArray();
    }

    /// {@return `size` bytes of byte runs and repeated short patterns}
    private static byte[] repetitive(int size) {
        var random = new Random(size + 1L);
        var bytes = new byte[size];
        var position = 0;
        while (position < size) {
            var run = Math.min(size - position, 1 + random.nextInt(random.nextBoolean() ? 64 : 4096));
            if (random.nextBoolean()) {
                Arrays.fill(bytes, position, position + run, (byte) random.nextInt(4));
            } else {
                var period = 2 + random.nextInt(11);
                var pattern = new byte[period];
                random.nextBytes(pattern);
                for (var i = 0; i < run; i++) {
                    bytes[position + i] = pattern[i % period];
                }
            }
            position += run;
        }
        return bytes;
    }

    /// {@return `size` bytes of English-like text: frequent words more often, sentences and paragraphs}
    private static byte[] text(int size) {
        var random = new Random(size + 2L);
        var builder = new StringBuilder(size + 64);
        var sentenceStart = true;
        while (builder.length() < size) {
            // Squaring the uniform draw favours the first words, as natural text favours common ones
            var draw = random.nextDouble();
            var word = WORDS[(int) (draw * draw * WORDS.length)];
            if (sentenceStart) {
                word = Character.toUpperCase(word.charAt(0)) + word.substring(1);
                sentenceStart = false;
            }
            builder.append(word);
            var punctuation = random.nextInt(100);
            if (punctuation < 7) {
                builder.append(punctuation < 2 ? ".\n" : ". ");
                sentenceStart = true;
            } else if (punctuation < 12) {
                builder.append(", ");
            } else {
                builder.append(' ');
            }
        }
        return Arrays.copyOf(builder.toString().getBytes(StandardCharsets.US_ASCII), size);
    }

    /// {@return `size` bytes of structured records interleaved with random chunks and zero runs}
    private static byte[] binary(int size) {
        var random = new Random(size + 3L);
        var bytes = new byte[size];
        var position = 0;
        var counter = random.nextInt(1000);
        while (position < size) {
            switch (random.nextInt(4)) {
                case 0 -> {
                    // A record: an incrementing id, a small integer, a double and a short tag
                    var record = new byte[24];
                    counter += 1 + random.nextInt(3);
                    for (var i = 0; i < 4; i++) {
                        record[i] = (byte) (counter >>> (8 * i));
                    }
                    record[4] = (byte) random.nextInt(16);
                    var value = Double.doubleToLongBits(random.nextGaussian() * 100);
                    for (var i = 0; i < 8; i++) {
                        record[8 + i] = (byte) (value >>> (8 * i));
                    }
                    record[16] = (byte) ('a' + random.nextInt(4));
                    record[17] = (byte) ('a' + random.nextInt(4));
                    position = put(bytes, position, record);
                }
                case 1 -> {
                    var chunk = new byte[1 + random.nextInt(48)];
                    random.nextBytes(chunk);
                    position = put(bytes, position, chunk);
                }
                case 2 -> position = put(bytes, position, new byte[1 + random.nextInt(200)]);
                default -> {
                    var varints = new byte[16];
                    for (var i = 0; i < varints.length; i++) {
                        varints[i] = (byte) (random.nextInt(1 << (1 + random.nextInt(7))));
                    }
                    position = put(bytes, position, varints);
                }
            }
        }
        return bytes;
    }

    /// {@return `size` bytes whose second half is the first half with one byte in 4096 changed}
    private static byte[] distant(int size) {
        var random = new Random(size + 4L);
        var bytes = new byte[size];
        var half = size / 2;
        var head = new byte[half];
        random.nextBytes(head);
        System.arraycopy(head, 0, bytes, 0, half);
        System.arraycopy(head, 0, bytes, half, half);
        for (var i = half; i < size; i += 1 + random.nextInt(8192)) {
            bytes[i] = (byte) random.nextInt();
        }
        return bytes;
    }

    /// {@return the position after copying as much of `part` as fits into `bytes` at `position`}
    private static int put(byte[] bytes, int position, byte[] part) {
        var length = Math.min(part.length, bytes.length - position);
        System.arraycopy(part, 0, bytes, position, length);
        return position + length;
    }
}
