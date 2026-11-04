package com.github.auties00.viridian.compress;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The Snappy compressor against xerial's Snappy, the reference implementation's bindings, and Viridian's own
/// decompressor.
class SnappyTest {
    static Stream<Arguments> inputs() {
        var random = new Random(11);
        var noise = new byte[300_000];
        random.nextBytes(noise);
        var mixed = new byte[400_000];
        for (var i = 0; i < mixed.length; i += 1000) {
            if (random.nextBoolean()) {
                for (var j = i; j < Math.min(mixed.length, i + 1000); j++) {
                    mixed[j] = (byte) random.nextInt(4);
                }
            } else {
                System.arraycopy(noise, random.nextInt(100_000), mixed, i, Math.min(1000, mixed.length - i));
            }
        }
        var text = "large value large value ".repeat(9000).getBytes(StandardCharsets.US_ASCII);
        return Stream.of(
                Arguments.of("empty", new byte[0]),
                Arguments.of("one byte", new byte[]{7}),
                Arguments.of("shorter than the match finder's margin", "abcabcabcabca".getBytes(StandardCharsets.US_ASCII)),
                Arguments.of("text", text),
                Arguments.of("zeros, in matches longer than 64 bytes", new byte[200_000]),
                Arguments.of("noise", noise),
                Arguments.of("noise and runs, across fragments", mixed),
                Arguments.of("one fragment and a byte", Arrays.copyOf(text, (1 << 16) + 1)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("inputs")
    @DisplayName("compressed data decompresses to itself, by xerial's Snappy and by Viridian's")
    void roundTrip(String name, byte[] input) throws IOException {
        var compressed = Snappy.compress(input, 0, input.length);
        assertArrayEquals(input, org.xerial.snappy.Snappy.uncompress(compressed));
        assertArrayEquals(input, Snappy.decompress(compressed, 0, compressed.length, Integer.MAX_VALUE));
    }

    @Test
    @DisplayName("repetitive data compresses to a small fraction, and noise grows by little")
    void ratio() {
        var text = "large value ".repeat(9000).getBytes(StandardCharsets.US_ASCII);
        assertTrue(Snappy.compress(text, 0, text.length).length < text.length / 20);
        var noise = new byte[100_000];
        new Random(3).nextBytes(noise);
        assertTrue(Snappy.compress(noise, 0, noise.length).length < noise.length + noise.length / 50);
    }

    @Test
    @DisplayName("only the range of the input given is compressed")
    void range() throws IOException {
        var input = "xxxxhello hello hello hello hello hello helloyyyy".getBytes(StandardCharsets.US_ASCII);
        var compressed = Snappy.compress(input, 4, input.length - 8);
        assertArrayEquals(Arrays.copyOfRange(input, 4, input.length - 4), org.xerial.snappy.Snappy.uncompress(compressed));
    }
}
