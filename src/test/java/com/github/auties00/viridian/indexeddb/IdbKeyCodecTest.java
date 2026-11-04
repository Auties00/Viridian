package com.github.auties00.viridian.indexeddb;

import com.github.auties00.viridian.IdbKey;
import com.github.auties00.viridian.io.ByteCursor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Both forms of IndexedDB keys: round trips, the sortable form's byte order, and the file names of SQLite databases.
class IdbKeyCodecTest {
    private static IdbKey randomKey(Random random, int depth) {
        return switch (random.nextInt(depth > 2 ? 4 : 5)) {
            case 0 -> new IdbKey.NumberKey(switch (random.nextInt(5)) {
                case 0 -> 0;
                case 1 -> -random.nextDouble() * 1e9;
                case 2 -> Double.NEGATIVE_INFINITY;
                case 3 -> random.nextInt();
                default -> random.nextGaussian() * 1e-300;
            });
            case 1 -> new IdbKey.DateKey(random.nextLong(-1L << 40, 1L << 40));
            case 2 -> {
                var characters = new char[random.nextInt(12)];
                for (var i = 0; i < characters.length; i++) {
                    characters[i] = switch (random.nextInt(4)) {
                        case 0 -> (char) random.nextInt(0x7F);
                        case 1 -> (char) (0x7F + random.nextInt(0x3F00));
                        case 2 -> (char) (0x4000 + random.nextInt(0xC000));
                        default -> (char) random.nextInt(0x10000);
                    };
                }
                yield new IdbKey.StringKey(new String(characters));
            }
            case 3 -> {
                var bytes = new byte[random.nextInt(20)];
                random.nextBytes(bytes);
                yield new IdbKey.BinaryKey(bytes);
            }
            default -> {
                var elements = new ArrayList<IdbKey>();
                var count = random.nextInt(4);
                for (var i = 0; i < count; i++) {
                    elements.add(randomKey(random, depth + 1));
                }
                yield new IdbKey.ArrayKey(elements);
            }
        };
    }

    @Test
    @DisplayName("keys round-trip through both forms")
    void roundTrips() throws IOException {
        var random = new Random(3);
        for (var i = 0; i < 5000; i++) {
            var key = randomKey(random, 0);
            assertEquals(key, IdbKeyCodec.decodeLevelDb(new ByteCursor(IdbKeyCodec.encodeLevelDb(key))), key::toString);
            var sortable = IdbKeyCodec.decodeSortable(IdbKeyCodec.encodeSortable(key));
            assertEquals(0, key.compareTo(sortable), key::toString);
        }
    }

    @Test
    @DisplayName("the sortable form's unsigned byte order is IndexedDB's key order")
    void order() {
        var random = new Random(5);
        for (var i = 0; i < 20_000; i++) {
            var first = randomKey(random, 0);
            var second = randomKey(random, 0);
            var expected = Integer.signum(first.compareTo(second));
            var actual = Integer.signum(Arrays.compareUnsigned(IdbKeyCodec.encodeSortable(first), IdbKeyCodec.encodeSortable(second)));
            assertEquals(expected, actual, () -> first + " vs " + second);
        }
    }

    @Test
    @DisplayName("the sortable form of known keys, as Chromium writes them")
    void knownEncodings() {
        assertArrayEquals(new byte[]{0x10, (byte) 0xBF, (byte) 0xF0, 0, 0, 0, 0, 0, 0}, IdbKeyCodec.encodeSortable(new IdbKey.NumberKey(1)));
        assertArrayEquals(new byte[]{0x10, 0x40, 0x0F, -1, -1, -1, -1, -1, -1}, IdbKeyCodec.encodeSortable(new IdbKey.NumberKey(-1)));
        assertArrayEquals(new byte[]{0x30, 'a' + 1, (byte) 0x80, (byte) 0xE9, (byte) 0xFF, (byte) 0x4F, 0x60, 0}, IdbKeyCodec.encodeSortable(new IdbKey.StringKey("aé你")));
        assertArrayEquals(new byte[]{0x40, 9, 1, 2, 3, 0, 0, 0, 0, 0, 3}, IdbKeyCodec.encodeSortable(new IdbKey.BinaryKey(new byte[]{1, 2, 3})));
        assertArrayEquals(new byte[]{0x40, 0}, IdbKeyCodec.encodeSortable(new IdbKey.BinaryKey(new byte[0])));
        assertArrayEquals(new byte[]{0x50, 0x30, 0, 0}, IdbKeyCodec.encodeSortable(new IdbKey.ArrayKey(List.of(new IdbKey.StringKey("")))));
    }

    @Test
    @DisplayName("-0 sorts and encodes as 0, and NaN is no key")
    void zeroAndNaN() {
        assertArrayEquals(IdbKeyCodec.encodeSortable(new IdbKey.NumberKey(0)), IdbKeyCodec.encodeSortable(new IdbKey.NumberKey(-0.0)));
        assertThrows(IllegalArgumentException.class, () -> new IdbKey.NumberKey(Double.NaN));
        // The bits of NaN, made sortable
        assertThrows(IOException.class, () -> IdbKeyCodec.decodeSortable(new byte[]{0x10, (byte) 0xFF, (byte) 0xF8, 0, 0, 0, 0, 0, 0}));
    }

    @Test
    @DisplayName("SQLite database files are named by the Base32 of the SHA-256 of the name's UTF-16")
    void fileNames() {
        assertEquals("SZSJWAVN6DYSSNAXXGI7HBTGS2WLKVMPWIE75RP2WMNQZHINSBTQ", IndexedDbDirectory.sqliteFileName("wawc"));
        assertEquals("6SWAZZBFF7GBMSNKQLMCXDS3JJIWPKF7JUPJBTBRCW6XSPKNBZFQ", IndexedDbDirectory.sqliteFileName("model-storage"));
        assertEquals("GHLP3JI7SWYOJD6KMG3AUFUQ2EBY6RZ6Y5W373XW42FMSUYSNTDA", IndexedDbDirectory.sqliteFileName("xé😀"));
        assertEquals("0", IndexedDbDirectory.sqliteFileName(""));
    }
}
