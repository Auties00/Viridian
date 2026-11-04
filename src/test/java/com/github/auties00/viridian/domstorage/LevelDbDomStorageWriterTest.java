package com.github.auties00.viridian.domstorage;

import com.github.auties00.viridian.StorageKey;
import com.github.auties00.viridian.StorageOrigin;
import com.github.auties00.viridian.io.ByteCursor;
import com.github.auties00.viridian.leveldb.LevelDb;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.impl.Iq80DBFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.io.TempDirDeletionStrategy.IgnoreFailures;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The Local Storage writer against Viridian's reader and the entries Chrome writes, copying databases iq80's
/// LevelDB wrote and iq80 reads back.
class LevelDbDomStorageWriterTest {
    private static final StorageOrigin ORIGIN = StorageOrigin.parse("http://127.0.0.1:65326");
    private static final StorageOrigin OTHER = StorageOrigin.parse("https://example.com");
    private static final Instant WINDOWS_EPOCH = Instant.parse("1601-01-01T00:00:00Z");

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static Map<String, String> entries(Path directory) throws IOException {
        var map = new TreeMap<String, String>();
        for (var entry : LevelDb.read(directory, _ -> true)) {
            map.put(HexFormat.of().formatHex(entry.key()), HexFormat.of().formatHex(entry.value()));
        }
        return map;
    }

    @Test
    @DisplayName("a fresh database holds the version, the items as Chrome 157 encodes them, and their usage")
    void fresh(@TempDir Path root) throws IOException {
        var items = new LinkedHashMap<String, String>();
        items.put("latin", "café");
        items.put("unicode", "你好 😀");
        items.put("é key", "ascii value");
        items.put("empty", "");
        var before = Instant.now().truncatedTo(ChronoUnit.MICROS);
        LevelDbDomStorageWriter.replaceLocalStorage(root.resolve("missing"), root.resolve("target"), Map.of(StorageKey.of(ORIGIN), items));
        var after = Instant.now();

        assertEquals(items, LevelDbDomStorage.localStorage(root.resolve("target"), StorageKey.of(ORIGIN)));
        var stored = entries(root.resolve("target"));
        // Captured from the Local Storage Chrome 157 wrote for the same items
        assertEquals("31", stored.get(HexFormat.of().formatHex(bytes("VERSION"))));
        assertEquals("01636166e9", stored.get("5f687474703a2f2f3132372e302e302e313a363533323600016c6174696e"));
        assertEquals("00604f7d5920003dd800de", stored.get("5f687474703a2f2f3132372e302e302e313a36353332360001756e69636f6465"));
        assertEquals("0161736369692076616c7565", stored.get("5f687474703a2f2f3132372e302e302e313a36353332360001e9206b6579"));
        assertEquals("01", stored.get(HexFormat.of().formatHex(bytes("_http://127.0.0.1:65326\0\u0001empty"))));
        var meta = new ByteCursor(HexFormat.of().parseHex(stored.get(HexFormat.of().formatHex(bytes("META:http://127.0.0.1:65326")))));
        assertEquals(0x08, meta.u8());
        var modified = WINDOWS_EPOCH.plus(meta.varint64(), ChronoUnit.MICROS);
        assertTrue(!modified.isBefore(before) && !modified.isAfter(after), modified.toString());
        assertEquals(0x10, meta.u8());
        // The keys and values with their format bytes: 6 + 5, 8 + 11, 6 + 12, 6 + 1
        assertEquals(55, meta.varint64());
        assertFalse(meta.hasRemaining());
        assertTrue(stored.containsKey(HexFormat.of().formatHex(bytes("METAACCESS:http://127.0.0.1:65326"))));
        assertEquals(7, stored.size());
    }

    @Test
    @DisplayName("a copy replaces the origin's items and usage, and keeps every other entry and file as it was")
    void copy(@TempDir(deletionStrategy = IgnoreFailures.class) Path root) throws IOException {
        var existing = root.resolve("existing");
        var options = new Options().createIfMissing(true).writeBufferSize(4 * 1024);
        try (var database = Iq80DBFactory.factory.open(existing.toFile(), options)) {
            database.put(bytes("VERSION"), bytes("1"));
            for (var i = 0; i < 200; i++) {
                database.put(bytes("_https://example.com\0\u0001key " + i), bytes("\u0001" + "value ".repeat(i)));
            }
            database.put(bytes("META:https://example.com"), new byte[]{8, 1, 16, 2});
            database.put(bytes("METAACCESS:https://example.com"), new byte[]{8, 3});
            database.put(bytes("_http://127.0.0.1:65326\0\u0001stale"), bytes("\u0001gone"));
            database.put(bytes("META:http://127.0.0.1:65326"), new byte[]{8, 1, 16, 9});
            database.put(bytes("METAACCESS:http://127.0.0.1:65326"), new byte[]{8, 1});
            // Another origin whose serialization starts with this one's
            database.put(bytes("_http://127.0.0.1:653260\0\u0001kept"), bytes("\u0001kept"));
        }
        Files.writeString(existing.resolve("tag"), "");
        var before = entries(existing);

        LevelDbDomStorageWriter.replaceLocalStorage(existing, root.resolve("target"), Map.of(StorageKey.of(ORIGIN), Map.of("fresh", "new")));

        assertEquals(before, entries(existing), "the existing database is only read");
        var after = entries(root.resolve("target"));
        assertEquals(Map.of("fresh", "new"), LevelDbDomStorage.localStorage(root.resolve("target"), StorageKey.of(ORIGIN)));
        assertEquals(200, LevelDbDomStorage.localStorage(root.resolve("target"), StorageKey.of(OTHER)).size());
        var expected = new TreeMap<>(before);
        expected.keySet().removeIf(key -> new String(HexFormat.of().parseHex(key), StandardCharsets.ISO_8859_1).contains("127.0.0.1:65326\0")
                || key.equals(HexFormat.of().formatHex(bytes("META:http://127.0.0.1:65326")))
                || key.equals(HexFormat.of().formatHex(bytes("METAACCESS:http://127.0.0.1:65326"))));
        var unchanged = new TreeMap<>(after);
        unchanged.keySet().retainAll(expected.keySet());
        assertEquals(expected, unchanged);
        assertEquals(expected.size() + 3, after.size());
        assertTrue(Files.isRegularFile(root.resolve("target").resolve("tag")));

        // iq80, a LevelDB of the bytewise comparator Local Storage uses, opens the copy
        try (var database = Iq80DBFactory.factory.open(root.resolve("target").toFile(), new Options())) {
            assertArrayEquals(bytes("\u0001new"), database.get(bytes("_http://127.0.0.1:65326\0\u0001fresh")));
            assertArrayEquals(bytes("\u0001kept"), database.get(bytes("_http://127.0.0.1:653260\0\u0001kept")));
        }
    }

    @Test
    @DisplayName("no items leave the origin without items or usage, as Chrome leaves an origin it clears")
    void cleared(@TempDir Path root) throws IOException {
        LevelDbDomStorageWriter.replaceLocalStorage(root.resolve("first"), root.resolve("second"), Map.of(StorageKey.of(ORIGIN), Map.of("a", "b")));
        LevelDbDomStorageWriter.replaceLocalStorage(root.resolve("second"), root.resolve("third"), Map.of(StorageKey.of(ORIGIN), Map.of()));
        assertEquals(Map.of(HexFormat.of().formatHex(bytes("VERSION")), "31"), entries(root.resolve("third")));
    }

    @Test
    @DisplayName("a target that holds a file is not written")
    void notEmpty(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("target"));
        Files.writeString(root.resolve("target").resolve("LOG"), "");
        assertThrows(DirectoryNotEmptyException.class, () -> LevelDbDomStorageWriter.replaceLocalStorage(root.resolve("missing"), root.resolve("target"), Map.of(StorageKey.of(ORIGIN), Map.of())));
    }
}
