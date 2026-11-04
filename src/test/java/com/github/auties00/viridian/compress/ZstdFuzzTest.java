package com.github.auties00.viridian.compress;

import com.github.auties00.viridian.compress.ZstdTestData.Shape;
import com.github.luben.zstd.ZstdDecompressCtx;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLongArray;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// Mutates, truncates and splices valid frames from fixed seeds and checks that [Zstd] terminates, never throws
/// anything but an [IOException], and returns what zstd-jni returns whenever both accept an input.
@DisplayName("Zstd fuzzing")
class ZstdFuzzTest {
    private static final int MAX_LENGTH = 1 << 20;

    private static final List<byte[]> CORPUS = corpus();

    // Both accepted, only Viridian accepted, only zstd-jni accepted, both rejected
    private static final AtomicLongArray OUTCOMES = new AtomicLongArray(4);

    private static List<byte[]> corpus() {
        var frames = new ArrayList<byte[]>();
        var sizes = new int[]{0, 1, 40, 700, 5000, 70_000};
        var levels = new int[]{-5, -1, 1, 3, 9, 19};
        for (var shape : Shape.values()) {
            for (var size : sizes) {
                for (var i = 0; i < levels.length; i++) {
                    var data = ZstdTestData.generate(shape, size);
                    frames.add(ZstdTestData.compress(data, levels[i], i % 2 == 0));
                    if (i % 3 == 0) {
                        frames.add(ZstdTestData.compressStreaming(data, levels[i], i == 0));
                    }
                }
            }
        }
        return frames;
    }

    @AfterAll
    static void report() {
        System.out.printf("zstd fuzzing outcomes: both accepted %d, only Viridian %d, only zstd-jni %d, both rejected %d%n",
                OUTCOMES.get(0), OUTCOMES.get(1), OUTCOMES.get(2), OUTCOMES.get(3));
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 2, 3, 4, 5, 6, 7, 8})
    @DisplayName("randomly mutated frames decode like zstd-jni or fail with an IOException")
    void mutations(long seed) {
        var random = new Random(seed);
        var scratch = new byte[MAX_LENGTH];
        assertTimeoutPreemptively(Duration.ofMinutes(2), () -> {
            for (var iteration = 0; iteration < 6000; iteration++) {
                check(mutate(random), scratch, "seed " + seed + ", iteration " + iteration);
            }
        });
    }

    @Test
    @DisplayName("every single-byte change of small frames decodes like zstd-jni or fails with an IOException")
    void singleBytes() {
        var scratch = new byte[MAX_LENGTH];
        var frames = CORPUS.stream().filter(frame -> frame.length > 20 && frame.length < 400).limit(12).toList();
        assertTrue(frames.size() >= 6, "the corpus has too few small frames");
        assertTimeoutPreemptively(Duration.ofMinutes(2), () -> {
            for (var frame : frames) {
                for (var position = 0; position < frame.length; position++) {
                    for (var value : new int[]{0x00, 0xFF, 0x7F, 0x80, frame[position] ^ 0x01, frame[position] ^ 0x10}) {
                        var mutated = frame.clone();
                        mutated[position] = (byte) value;
                        check(mutated, scratch, "position " + position + ", value " + value);
                    }
                }
            }
        });
    }

    private static byte[] mutate(Random random) {
        var input = CORPUS.get(random.nextInt(CORPUS.size())).clone();
        var rounds = 1 + random.nextInt(3);
        for (var round = 0; round < rounds; round++) {
            if (input.length == 0) {
                break;
            }
            input = switch (random.nextInt(7)) {
                case 0 -> {
                    for (var i = 1 + random.nextInt(8); i > 0; i--) {
                        input[random.nextInt(input.length)] ^= (byte) (1 << random.nextInt(8));
                    }
                    yield input;
                }
                case 1 -> {
                    var specials = new int[]{0x00, 0xFF, 0x7F, 0x80, 0x01, random.nextInt(256)};
                    for (var i = 1 + random.nextInt(4); i > 0; i--) {
                        input[random.nextInt(input.length)] = (byte) specials[random.nextInt(specials.length)];
                    }
                    yield input;
                }
                case 2 -> Arrays.copyOf(input, random.nextInt(input.length));
                case 3 -> {
                    var other = CORPUS.get(random.nextInt(CORPUS.size()));
                    var cut = random.nextInt(input.length + 1);
                    var from = random.nextInt(other.length + 1);
                    yield ZstdTestData.concat(Arrays.copyOf(input, cut), Arrays.copyOfRange(other, from, other.length));
                }
                case 4 -> {
                    var at = random.nextInt(input.length + 1);
                    var length = 1 + random.nextInt(16);
                    if (random.nextBoolean()) {
                        yield ZstdTestData.concat(Arrays.copyOf(input, at), ZstdTestData.random(length, random.nextLong()),
                                Arrays.copyOfRange(input, at, input.length));
                    }
                    yield ZstdTestData.concat(Arrays.copyOf(input, at), Arrays.copyOfRange(input, Math.min(input.length, at + length), input.length));
                }
                case 5 -> {
                    // The headers drive the parsing: the frame header, the first block header and the section headers
                    var position = random.nextInt(Math.min(input.length, 24));
                    input[position] = (byte) random.nextInt(256);
                    yield input;
                }
                default -> ZstdTestData.concat(input, CORPUS.get(random.nextInt(CORPUS.size())));
            };
        }
        return input;
    }

    private static void check(byte[] input, byte[] scratch, String context) {
        byte[] decoded;
        try {
            decoded = Zstd.decompress(input, 0, input.length, MAX_LENGTH);
        } catch (IOException exception) {
            decoded = null;
        } catch (RuntimeException | StackOverflowError | OutOfMemoryError throwable) {
            fail(context + ": " + throwable + " on " + HexFormat.of().formatHex(input), throwable);
            return;
        }
        byte[] reference;
        try (var decompressor = new ZstdDecompressCtx()) {
            var size = decompressor.decompressByteArray(scratch, 0, scratch.length, input, 0, input.length);
            reference = Arrays.copyOf(scratch, size);
        } catch (RuntimeException exception) {
            reference = null;
        }
        if (decoded != null && reference != null) {
            assertArrayEquals(reference, decoded, () -> context + ": the output differs from zstd-jni's on " + HexFormat.of().formatHex(input));
        }
        OUTCOMES.incrementAndGet((decoded != null ? 0 : 2) + (reference != null ? 0 : 1));
    }
}
