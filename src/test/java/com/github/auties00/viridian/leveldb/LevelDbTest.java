package com.github.auties00.viridian.leveldb;

import org.iq80.leveldb.CompressionType;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.impl.Iq80DBFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.io.TempDirDeletionStrategy.IgnoreFailures;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// The LevelDB reader against files written by hand, with checksums, and against databases iq80's LevelDB writes.
class LevelDbTest {
    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, String> read(Path directory) throws IOException {
        var map = new TreeMap<String, String>();
        for (var entry : LevelDb.read(directory, _ -> true)) {
            map.put(new String(entry.key(), StandardCharsets.UTF_8), new String(entry.value(), StandardCharsets.UTF_8));
        }
        return map;
    }

    @Nested
    @DisplayName("logs")
    class Logs {
        @Test
        @DisplayName("the newest write of a key wins, and a deleted key is gone")
        void newestWins(@TempDir Path directory) throws IOException {
            new LevelDbWriter()
                    .put(bytes("a"), bytes("1"))
                    .put(bytes("b"), bytes("2"))
                    .put(bytes("a"), bytes("3"))
                    .delete(bytes("b"))
                    .put(bytes("c"), bytes("4"))
                    .writeLog(directory, 3);
            assertEquals(Map.of("a", "3", "c", "4"), read(directory));
        }

        @Test
        @DisplayName("records fragmented across blocks join, and a torn tail is ignored")
        void fragments(@TempDir Path directory) throws IOException {
            var big = "x".repeat(LevelDbLog.BLOCK_SIZE * 2 + 100);
            var log = LevelDbWriter.logRecords(List.of(
                    LevelDbWriter.batch(1, bytes("big"), bytes(big)),
                    LevelDbWriter.batch(2, bytes("small"), bytes("v")),
                    LevelDbWriter.batch(3, bytes("torn"), bytes("lost"))));
            Files.createDirectories(directory);
            Files.write(directory.resolve("000003.log"), java.util.Arrays.copyOf(log, log.length - 2));
            assertEquals(Map.of("big", big, "small", "v"), read(directory));
        }

        @Test
        @DisplayName("a record whose checksum fails is dropped with the rest of its block")
        void badChecksum(@TempDir Path directory) throws IOException {
            var log = LevelDbWriter.logRecords(List.of(
                    LevelDbWriter.batch(1, bytes("kept"), bytes("1")),
                    LevelDbWriter.batch(2, bytes("damaged"), bytes("2")),
                    LevelDbWriter.batch(3, bytes("after"), bytes("3"))));
            var first = LevelDbWriter.logRecords(List.of(LevelDbWriter.batch(1, bytes("kept"), bytes("1")))).length;
            // A byte of the second record's payload
            log[first + 12] ^= 1;
            Files.createDirectories(directory);
            Files.write(directory.resolve("000003.log"), log);
            assertEquals(Map.of("kept", "1"), read(directory));
        }

        @Test
        @DisplayName("only the keys the filter accepts are returned")
        void filter(@TempDir Path directory) throws IOException {
            new LevelDbWriter().put(bytes("keep:1"), bytes("a")).put(bytes("drop:1"), bytes("b")).put(bytes("keep:2"), bytes("c")).writeLog(directory, 3);
            var entries = LevelDb.read(directory, key -> new String(key, StandardCharsets.UTF_8).startsWith("keep:"));
            assertEquals(2, entries.size());
            assertArrayEquals(bytes("keep:1"), entries.getFirst().key());
        }
    }

    @Nested
    @DisplayName("tables and descriptors")
    class Tables {
        @Test
        @DisplayName("live tables are read under newer logs, Snappy blocks included, and deleted tables are not")
        void layered(@TempDir Path directory) throws IOException {
            var older = new LinkedHashMap<byte[], byte[]>();
            older.put(LevelDbWriter.internalKey(bytes("a"), 1, false), bytes("old-a"));
            older.put(LevelDbWriter.internalKey(bytes("b"), 2, false), bytes("old-b"));
            Files.createDirectories(directory);
            Files.write(directory.resolve("000005.ldb"), LevelDbWriter.table(older, LevelDbWriter::snappyLiterals, 1));
            var deleted = new LinkedHashMap<byte[], byte[]>();
            deleted.put(LevelDbWriter.internalKey(bytes("z"), 3, false), bytes("ghost"));
            Files.write(directory.resolve("000004.ldb"), LevelDbWriter.table(deleted, null, 0));
            LevelDbWriter.writeDescriptor(directory, 2, LevelDbWriter.manifest(7, List.of(5L), List.of()));
            // A log older than the descriptor's log number is flushed already
            Files.write(directory.resolve("000006.log"), LevelDbWriter.logRecords(List.of(LevelDbWriter.batch(10, bytes("c"), bytes("stale")))));
            Files.write(directory.resolve("000007.log"), LevelDbWriter.logRecords(List.of(LevelDbWriter.batch(10, bytes("a"), bytes("new-a")))));
            assertEquals(Map.of("a", "new-a", "b", "old-b"), read(directory));
        }

        @Test
        @DisplayName("a table block whose checksum fails is damage")
        void badBlock(@TempDir Path directory) throws IOException {
            var entries = new LinkedHashMap<byte[], byte[]>();
            entries.put(LevelDbWriter.internalKey(bytes("a"), 1, false), bytes("value"));
            var table = LevelDbWriter.table(entries, null, 0);
            table[3] ^= 1;
            Files.createDirectories(directory);
            Files.write(directory.resolve("000005.ldb"), table);
            LevelDbWriter.writeDescriptor(directory, 2, LevelDbWriter.manifest(7, List.of(5L), List.of()));
            assertThrows(IOException.class, () -> read(directory));
        }

        @Test
        @DisplayName("a descriptor listing a table that is not there fails")
        void missingTable(@TempDir Path directory) throws IOException {
            LevelDbWriter.writeDescriptor(directory, 2, LevelDbWriter.manifest(7, List.of(5L), List.of()));
            assertThrows(NoSuchFileException.class, () -> read(directory));
        }

        @Test
        @DisplayName("a directory with no database fails")
        void empty(@TempDir Path directory) {
            assertThrows(NoSuchFileException.class, () -> read(directory));
        }
    }

    // iq80 keeps its tables mapped after it closes, and Windows cannot delete a mapped file. Unmapped, iq80 reads its
    // tables into heap buffers, which xerial's Snappy rejects, so every compaction fails and is retried forever
    @Nested
    @DisplayName("against iq80's LevelDB")
    class Oracle {
        @ParameterizedTest(name = "{0}")
        @EnumSource(CompressionType.class)
        @DisplayName("random writes and deletions, flushed and compacted through a small write buffer, read back as the model holds them")
        void random(CompressionType compression, @TempDir(deletionStrategy = IgnoreFailures.class) Path directory) throws IOException {
            var model = new TreeMap<String, String>();
            var random = new Random(compression.ordinal() + 7L);
            var options = new Options().createIfMissing(true).writeBufferSize(16 * 1024).compressionType(compression).blockSize(1024);
            for (var session = 0; session < 3; session++) {
                try (var database = Iq80DBFactory.factory.open(directory.toFile(), options)) {
                    for (var i = 0; i < 3000; i++) {
                        var key = "key-" + random.nextInt(800);
                        if (random.nextInt(5) == 0) {
                            database.delete(bytes(key));
                            model.remove(key);
                        } else {
                            var value = "value-" + i + "-" + "v".repeat(random.nextInt(200));
                            database.put(bytes(key), bytes(value));
                            model.put(key, value);
                        }
                    }
                }
                assertEquals(model, read(directory), "after session " + session);
            }
        }

        @Test
        @DisplayName("a database still being written, with a log and no clean close, reads as written so far")
        void open(@TempDir(deletionStrategy = IgnoreFailures.class) Path directory) throws IOException {
            var options = new Options().createIfMissing(true).writeBufferSize(4 * 1024 * 1024);
            var model = new TreeMap<String, String>();
            try (var database = Iq80DBFactory.factory.open(directory.toFile(), options)) {
                for (var i = 0; i < 200; i++) {
                    database.put(bytes("k" + i), bytes("v" + i));
                    model.put("k" + i, "v" + i);
                }
                assertEquals(model, read(directory));
            }
            var keys = new ArrayList<>(model.keySet());
            assertEquals(keys, new ArrayList<>(read(directory).keySet()));
        }
    }
}
