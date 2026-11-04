package com.github.auties00.viridian.leveldb;

import org.iq80.leveldb.Options;
import org.iq80.leveldb.impl.Iq80DBFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.io.TempDirDeletionStrategy.IgnoreFailures;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The LevelDB builder's databases read back by Viridian's reader and opened, read and written by iq80's LevelDB.
class LevelDbBuilderTest {
    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /// {@return entries of every size: many small ones, empty values, and values spanning several log blocks}
    private static TreeMap<byte[], byte[]> model(long seed) {
        var random = new Random(seed);
        var model = new TreeMap<byte[], byte[]>(Arrays::compareUnsigned);
        for (var i = 0; i < 5000; i++) {
            var value = new byte[random.nextInt(4) == 0 ? 0 : random.nextInt(2000)];
            random.nextBytes(value);
            model.put(bytes("key-" + random.nextInt(1_000_000)), value);
        }
        for (var size : new int[]{LevelDbLog.BLOCK_SIZE - 7, LevelDbLog.BLOCK_SIZE, 100_000, 3_000_000}) {
            var value = new byte[size];
            random.nextBytes(value);
            model.put(bytes("big-" + size), value);
        }
        return model;
    }

    private static List<LevelDb.Entry> entries(TreeMap<byte[], byte[]> model) {
        var entries = new ArrayList<LevelDb.Entry>();
        model.forEach((key, value) -> entries.add(new LevelDb.Entry(key, value)));
        // The order a database is written in does not matter
        java.util.Collections.shuffle(entries, new Random(1));
        return entries;
    }

    private static void assertReads(TreeMap<byte[], byte[]> model, List<LevelDb.Entry> entries) {
        assertEquals(model.size(), entries.size());
        var expected = model.entrySet().iterator();
        for (var entry : entries) {
            var next = expected.next();
            assertArrayEquals(next.getKey(), entry.key());
            assertArrayEquals(next.getValue(), entry.value(), () -> new String(entry.key(), StandardCharsets.UTF_8));
        }
    }

    @Nested
    @DisplayName("read back by Viridian")
    class Viridian {
        @Test
        @DisplayName("entries of every size, across log blocks and batches, read back as written, in key order")
        void entries(@TempDir Path directory) throws IOException {
            var model = model(3);
            LevelDbBuilder.write(directory, LevelDbBuilder.BYTEWISE_COMPARATOR, LevelDbBuilderTest.entries(model));
            assertReads(model, LevelDb.read(directory, _ -> true));
        }

        @Test
        @DisplayName("a later entry of a key replaces an earlier one")
        void replaces(@TempDir Path directory) throws IOException {
            LevelDbBuilder.write(directory, LevelDbBuilder.BYTEWISE_COMPARATOR, List.of(
                    new LevelDb.Entry(bytes("a"), bytes("1")),
                    new LevelDb.Entry(bytes("b"), bytes("2")),
                    new LevelDb.Entry(bytes("a"), bytes("3"))));
            var entries = LevelDb.read(directory, _ -> true);
            assertEquals(2, entries.size());
            assertArrayEquals(bytes("3"), entries.getFirst().value());
        }

        @Test
        @DisplayName("CURRENT names a descriptor that names the comparator, the log and the last sequence number")
        void descriptor(@TempDir Path directory) throws IOException {
            LevelDbBuilder.write(directory, "idb_cmp1", List.of(new LevelDb.Entry(bytes("a"), bytes("1")), new LevelDb.Entry(bytes("b"), bytes("2"))));
            assertEquals("MANIFEST-000001\n", Files.readString(directory.resolve("CURRENT")));
            var edits = new ArrayList<byte[]>();
            LevelDbLog.readRecords(directory.resolve("MANIFEST-000001"), true, edits::add);
            var edit = new byte[]{1, 8, 'i', 'd', 'b', '_', 'c', 'm', 'p', '1', 2, 3, 3, 4, 4, 2};
            assertEquals(1, edits.size());
            assertArrayEquals(edit, edits.getFirst());
            var state = LevelDbManifest.read(directory.resolve("MANIFEST-000001"));
            assertEquals(List.of(), state.liveTables());
            assertEquals(3, state.logNumber());
            try (var files = Files.list(directory)) {
                assertEquals(List.of("000003.log", "CURRENT", "MANIFEST-000001"), files.map(file -> file.getFileName().toString()).sorted().toList());
            }
        }

        @Test
        @DisplayName("a database of no entries is a database")
        void empty(@TempDir Path directory) throws IOException {
            LevelDbBuilder.write(directory, LevelDbBuilder.BYTEWISE_COMPARATOR, List.of());
            assertEquals(List.of(), LevelDb.read(directory, _ -> true));
        }

        @Test
        @DisplayName("a directory that holds a file is not written")
        void notEmpty(@TempDir Path directory) throws IOException {
            Files.writeString(directory.resolve("LOG"), "");
            assertThrows(DirectoryNotEmptyException.class, () -> LevelDbBuilder.write(directory, LevelDbBuilder.BYTEWISE_COMPARATOR, List.of()));
        }

        @Test
        @DisplayName("a builder closed before it finishes leaves an empty directory")
        void abandoned(@TempDir Path directory) throws IOException {
            try (var builder = LevelDbBuilder.create(directory.resolve("db"), LevelDbBuilder.BYTEWISE_COMPARATOR)) {
                builder.put(bytes("a"), bytes("1"));
            }
            try (var files = Files.list(directory.resolve("db"))) {
                assertFalse(files.findAny().isPresent());
            }
        }

        @Test
        @DisplayName("a finished builder takes no more entries")
        void finished(@TempDir Path directory) throws IOException {
            try (var builder = LevelDbBuilder.create(directory, LevelDbBuilder.BYTEWISE_COMPARATOR)) {
                builder.finish();
                assertThrows(IllegalStateException.class, () -> builder.put(bytes("a"), bytes("1")));
            }
            assertTrue(Files.isRegularFile(directory.resolve("CURRENT")));
        }
    }

    // iq80 replays the log into a table when it opens the database, as Chromium's LevelDB does
    @Nested
    @DisplayName("opened by iq80's LevelDB")
    class Oracle {
        @Test
        @DisplayName("iq80 reads every entry, keeps writing to the database, and reopens it")
        void reads(@TempDir(deletionStrategy = IgnoreFailures.class) Path directory) throws IOException {
            var model = model(5);
            LevelDbBuilder.write(directory, LevelDbBuilder.BYTEWISE_COMPARATOR, entries(model));
            var options = new Options().createIfMissing(false).writeBufferSize(1024 * 1024);
            var deleted = model.firstKey();
            try (var database = Iq80DBFactory.factory.open(directory.toFile(), options)) {
                var read = new TreeMap<byte[], byte[]>(Arrays::compareUnsigned);
                try (var iterator = database.iterator()) {
                    iterator.seekToFirst();
                    while (iterator.hasNext()) {
                        var entry = iterator.next();
                        read.put(entry.getKey(), entry.getValue());
                    }
                }
                assertEquals(model.size(), read.size());
                model.forEach((key, value) -> assertArrayEquals(value, read.get(key)));
                database.put(bytes("added"), bytes("after"));
                database.delete(deleted);
            }
            model.put(bytes("added"), bytes("after"));
            model.remove(deleted);
            try (var database = Iq80DBFactory.factory.open(directory.toFile(), options)) {
                assertArrayEquals(bytes("after"), database.get(bytes("added")));
            }
            assertReads(model, LevelDb.read(directory, _ -> true));
        }

        @Test
        @DisplayName("iq80 refuses a database of another comparator, which the descriptor names")
        void comparator(@TempDir(deletionStrategy = IgnoreFailures.class) Path directory) throws IOException {
            LevelDbBuilder.write(directory, "idb_cmp1", List.of(new LevelDb.Entry(bytes("a"), bytes("1"))));
            assertThrows(IllegalArgumentException.class, () -> Iq80DBFactory.factory.open(directory.toFile(), new Options()).close());
        }
    }
}
