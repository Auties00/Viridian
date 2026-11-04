package com.github.auties00.viridian.compress;

import com.github.auties00.viridian.compress.ZstdTestData.Shape;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Checks [Xxh64] against published digests and against the content checksums zstd-jni writes, which cover every
/// tail length of the algorithm.
@DisplayName("XXH64")
class Xxh64Test {
    @ParameterizedTest(name = "\"{0}\" with seed {1}")
    @CsvSource(delimiter = '|', value = {
            "''|0|ef46db3751d8e999",
            "''|2654435761|ac75fda2929b17ef",
            "abc|0|44bc2cf5ad770999",
            "Nobody inspects the spammish repetition|0|fbcea83c8a378bf1"
    })
    @DisplayName("matches published digests")
    void published(String text, long seed, String digest) {
        var bytes = text.getBytes(StandardCharsets.US_ASCII);
        assertEquals(Long.parseUnsignedLong(digest, 16), Xxh64.hash(bytes, 0, bytes.length, seed));
    }

    @Test
    @DisplayName("matches the checksums zstd writes, for every length up to 300 and some larger ones")
    void zstdChecksums() {
        var data = ZstdTestData.generate(Shape.BINARY, 1 << 20);
        for (var length = 0; length <= 1 << 20; length = length < 300 ? length + 1 : length * 3 + 7) {
            var input = Arrays.copyOf(data, length);
            var frame = ZstdTestData.compress(input, 1, true);
            var checksum = LittleEndian.readInt(frame, frame.length - 4);
            assertEquals(checksum, (int) Xxh64.hash(input, 0, length, 0), "length " + length);
        }
    }

    @Test
    @DisplayName("hashes only the given range")
    void range() {
        var data = ZstdTestData.generate(Shape.RANDOM, 1000);
        assertEquals(Xxh64.hash(Arrays.copyOfRange(data, 13, 813), 0, 800, 7), Xxh64.hash(data, 13, 800, 7));
    }
}
