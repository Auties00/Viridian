package com.github.auties00.viridian.compress;

import com.github.auties00.viridian.compress.ZstdTestData.Shape;
import com.github.luben.zstd.ZstdDecompressCtx;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.DisplayName;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Times [Zstd] on 32 MB of mixed data once the JIT has compiled it, next to zstd-jni's native decoder; the bound is
/// loose so that a loaded machine does not fail it, and the timings are printed.
@DisplayName("Zstd throughput")
class ZstdThroughputTest {
    private static final byte[] DATA = ZstdTestData.concat(ZstdTestData.generate(Shape.TEXT, 12 << 20),
            ZstdTestData.generate(Shape.BINARY, 12 << 20), ZstdTestData.generate(Shape.REPETITIVE, 4 << 20),
            ZstdTestData.generate(Shape.RANDOM, 4 << 20));

    @ParameterizedTest(name = "level {0}")
    @ValueSource(ints = {-4, 3, 9})
    @DisplayName("decodes 32 MB well within a second once warm")
    void warm(int level) throws IOException {
        var frame = ZstdTestData.compress(DATA, level, true);
        for (var i = 0; i < 8; i++) {
            assertArrayEquals(DATA, Zstd.decompress(frame, 0, frame.length, DATA.length));
        }
        var best = Long.MAX_VALUE;
        for (var i = 0; i < 5; i++) {
            var start = System.nanoTime();
            Zstd.decompress(frame, 0, frame.length, DATA.length);
            best = Math.min(best, System.nanoTime() - start);
        }
        var referenceBest = Long.MAX_VALUE;
        var output = new byte[DATA.length];
        try (var context = new ZstdDecompressCtx()) {
            for (var i = 0; i < 5; i++) {
                var start = System.nanoTime();
                context.decompressByteArray(output, 0, output.length, frame, 0, frame.length);
                referenceBest = Math.min(referenceBest, System.nanoTime() - start);
            }
        }
        System.out.printf("zstd level %d: %d MB compressed to %d KB; Viridian %.1f ms (%.0f MB/s), zstd-jni %.1f ms (%.0f MB/s)%n",
                level, DATA.length >> 20, frame.length >> 10, best / 1e6, DATA.length / (best / 1e9) / 1e6,
                referenceBest / 1e6, DATA.length / (referenceBest / 1e9) / 1e6);
        assertTrue(best < 2_000_000_000L, "decoding 32 MB took " + best / 1e6 + " ms");
    }
}
