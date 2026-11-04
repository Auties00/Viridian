package com.github.auties00.viridian.sqlite;

import com.github.auties00.viridian.sqlite.SqliteTesting.Jdbc;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Reads a database laid out like one of Chromium's SQLite IndexedDB databases, in WAL mode with frames that were
/// never checkpointed, the way the IndexedDB reader does: the records of each object store by seeking
/// `records_by_key` to the store and looking every entry's row up by rowid, and the small tables in full.
@DisplayName("Chromium IndexedDB layout")
class SqliteChromiumTest {
    @TempDir
    Path directory;

    private static final List<String> SCHEMA = List.of(
            "CREATE TABLE meta(key LONGVARCHAR NOT NULL UNIQUE PRIMARY KEY, value LONGVARCHAR)",
            "CREATE TABLE records (row_id INTEGER PRIMARY KEY, object_store_id INTEGER NOT NULL, compression_type INTEGER NOT NULL, key BLOB NOT NULL, value BLOB NOT NULL)",
            "CREATE UNIQUE INDEX records_by_key ON records(object_store_id, key)",
            "CREATE TABLE indexes (object_store_id INTEGER NOT NULL, id INTEGER NOT NULL, name BLOB NOT NULL, key_path BLOB, is_unique INTEGER NOT NULL, multi_entry INTEGER NOT NULL, PRIMARY KEY (object_store_id, id)) WITHOUT ROWID",
            "CREATE TABLE blobs (row_id INTEGER PRIMARY KEY AUTOINCREMENT, object_type INTEGER NOT NULL, mime_type TEXT, size_bytes INTEGER, file_name BLOB, last_modified INTEGER, bytes BLOB)",
            "CREATE INDEX legacy_blobs ON blobs (object_type) WHERE bytes IS NULL");

    /// The number of records in each object store; store 3 is empty.
    private static final int[] STORE_SIZES = {0, 40, 1200, 0, 7, 3000};

    /// {@return an IndexedDB-style key: a type tag followed by a number or a UTF-16 string}
    private static byte[] key(Random random, int index) {
        if (index % 3 == 0) {
            return ByteBuffer.allocate(9).put((byte) 0x03).putDouble(index * 1.5 - 1000.25).array();
        }
        var text = (index % 2 == 0 ? "user-" : "msg:") + SqliteTesting.randomText(random, 3 + random.nextInt(30)) + index;
        var bytes = text.getBytes(StandardCharsets.UTF_16BE);
        return ByteBuffer.allocate(1 + bytes.length).put((byte) 0x01).put(bytes).array();
    }

    @Test
    @DisplayName("reads each object store in key order through records_by_key")
    void objectStores() throws IOException {
        var file = directory.resolve("indexeddb.sqlite");
        try (var jdbc = Jdbc.open(file)) {
            jdbc.execute("PRAGMA journal_mode = WAL", "PRAGMA wal_autocheckpoint = 0");
            jdbc.execute(SCHEMA.toArray(String[]::new));
            jdbc.execute("INSERT INTO meta VALUES ('version', '1'), ('last_compatible_version', '1'), ('mmap_status', '-1')");
            var random = new Random(29);
            var records = new ArrayList<Object[]>();
            for (var store = 1; store < STORE_SIZES.length; store++) {
                for (var i = 0; i < STORE_SIZES[store]; i++) {
                    var size = i % 250 == 0 ? 90_000 : 10 + random.nextInt(1500);
                    records.add(new Object[]{(long) store, (long) random.nextInt(2), key(random, i), SqliteTesting.randomBytes(random, size)});
                }
            }
            Collections.shuffle(records, random);
            jdbc.batch("INSERT INTO records(object_store_id, compression_type, key, value) VALUES (?, ?, ?, ?)", records);
            jdbc.execute("DELETE FROM records WHERE row_id % 11 = 0");
            var indexes = new ArrayList<Object[]>();
            var blobs = new ArrayList<Object[]>();
            for (var store = 1; store < STORE_SIZES.length; store++) {
                for (var id = 1; id <= 3; id++) {
                    indexes.add(new Object[]{(long) store, (long) id, ("index" + id).getBytes(StandardCharsets.UTF_16LE),
                            id == 3 ? null : ("path." + id).getBytes(StandardCharsets.UTF_16LE), (long) (id % 2), 0L});
                }
            }
            for (var i = 0; i < 30; i++) {
                var inline = i % 3 != 0;
                blobs.add(new Object[]{(long) (i % 2), i % 5 == 0 ? null : "image/png", (long) (i * 100),
                        "file" + i, 1_700_000_000_000L + i, inline ? SqliteTesting.randomBytes(random, i * 100) : null});
            }
            jdbc.batch("INSERT INTO indexes VALUES (?, ?, ?, ?, ?, ?)", indexes);
            jdbc.batch("INSERT INTO blobs(object_type, mime_type, size_bytes, file_name, last_modified, bytes) VALUES (?, ?, ?, ?, ?, ?)", blobs);
            var copy = SqliteTesting.copyDatabase(file, directory.resolve("profile"), false);
            try (var database = SqliteDatabase.open(copy)) {
                var recordsTable = database.findTable("records").orElseThrow();
                var byKey = database.findIndex("records_by_key").orElseThrow();
                assertEquals(List.of("object_store_id", "key"), byKey.columns());
                assertTrue(byKey.unique());
                var keyColumn = recordsTable.columnIndex("key");
                var valueColumn = recordsTable.columnIndex("value");
                var compressionColumn = recordsTable.columnIndex("compression_type");
                for (var store = 0L; store <= STORE_SIZES.length; store++) {
                    var expected = jdbc.query("SELECT key, value, compression_type FROM records WHERE object_store_id = ? ORDER BY key", store);
                    var actual = new ArrayList<List<Object>>();
                    try (var cursor = byKey.seek(List.of(store))) {
                        while (cursor.next() && cursor.row().getLong(0) == store) {
                            var entry = cursor.row();
                            var record = recordsTable.findByRowid(entry.rowid()).orElseThrow();
                            assertArrayEquals(entry.getBlob(1), record.getBlob(keyColumn));
                            assertEquals(entry.rowid(), record.getLong(0));
                            actual.add(List.of(new SqliteTesting.Blob(record.getBlob(keyColumn)),
                                    new SqliteTesting.Blob(record.getBlob(valueColumn)), record.getLong(compressionColumn)));
                        }
                    }
                    SqliteTesting.assertRows(expected, actual, "object store " + store);
                }
                for (var table : List.of("meta", "indexes", "blobs", "sqlite_sequence")) {
                    SqliteTesting.assertTableMatches(database, jdbc, table);
                }
                SqliteTesting.assertMatchesJdbc(database, jdbc);
                assertEquals("1", SqliteTesting.read(database.findTable("META").orElseThrow().scan()).getFirst().get(1));
            }
        }
    }
}
