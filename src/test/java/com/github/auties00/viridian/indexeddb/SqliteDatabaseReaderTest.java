package com.github.auties00.viridian.indexeddb;

import com.github.auties00.viridian.ChromiumProfile;
import com.github.auties00.viridian.IdbKey;
import com.github.auties00.viridian.IdbKeyPath;
import com.github.auties00.viridian.IdbRecord;
import com.github.auties00.viridian.StorageBackend;
import com.github.auties00.viridian.StorageBucket;
import com.github.auties00.viridian.StorageOrigin;
import com.github.auties00.viridian.ViridianException;
import com.github.auties00.viridian.value.JsArray;
import com.github.auties00.viridian.value.JsBlob;
import com.github.auties00.viridian.value.JsFile;
import com.github.auties00.viridian.value.JsString;
import com.github.auties00.viridian.value.V8Writer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The SQLite IndexedDB backend against a database with Chromium's schema, written by SQLite itself and read while
/// its write-ahead log still holds the writes.
class SqliteDatabaseReaderTest {
    private static final StorageOrigin ORIGIN = StorageOrigin.parse("https://example.com");
    // Chromium's schema, version 1, as content/browser/indexed_db/instance/sqlite/database_connection.cc creates it
    private static final List<String> SCHEMA = List.of(
            "CREATE TABLE meta(key LONGVARCHAR NOT NULL UNIQUE PRIMARY KEY, value LONGVARCHAR)",
            "CREATE TABLE indexed_db_metadata (name BLOB NOT NULL, version INTEGER NOT NULL)",
            "CREATE TABLE object_stores (id INTEGER PRIMARY KEY, name BLOB NOT NULL, key_path BLOB, auto_increment INTEGER NOT NULL, key_generator_current_number INTEGER NOT NULL)",
            "CREATE TABLE indexes (object_store_id INTEGER NOT NULL, id INTEGER NOT NULL, name BLOB NOT NULL, key_path BLOB, is_unique INTEGER NOT NULL, multi_entry INTEGER NOT NULL, PRIMARY KEY (object_store_id, id)) WITHOUT ROWID",
            "CREATE TABLE records (row_id INTEGER PRIMARY KEY, object_store_id INTEGER NOT NULL, compression_type INTEGER NOT NULL, key BLOB NOT NULL, value BLOB NOT NULL)",
            "CREATE UNIQUE INDEX records_by_key ON records(object_store_id, key)",
            "CREATE TABLE index_references (record_row_id INTEGER NOT NULL, index_id INTEGER NOT NULL, key BLOB NOT NULL, object_store_id INTEGER NOT NULL, record_key BLOB NOT NULL, PRIMARY KEY (record_row_id, index_id, key)) WITHOUT ROWID",
            "CREATE INDEX index_references_by_key ON index_references (object_store_id, index_id, key, record_key)",
            "CREATE TABLE blobs (row_id INTEGER PRIMARY KEY AUTOINCREMENT, object_type INTEGER NOT NULL, mime_type TEXT, size_bytes INTEGER, file_name BLOB, last_modified INTEGER, bytes BLOB)",
            "CREATE INDEX legacy_blobs ON blobs (object_type) WHERE bytes IS NULL",
            "CREATE TABLE overflow_blob_chunks (row_id INTEGER PRIMARY KEY, blob_row_id INTEGER NOT NULL, chunk_index INTEGER NOT NULL, bytes BLOB NOT NULL)",
            "CREATE INDEX overflow_chunks_by_blob ON overflow_blob_chunks (blob_row_id, chunk_index)",
            "CREATE TABLE blob_references (row_id INTEGER PRIMARY KEY, blob_row_id INTEGER NOT NULL, record_row_id INTEGER)",
            "CREATE INDEX blob_references_by_blob ON blob_references (blob_row_id)",
            "CREATE INDEX blob_references_by_record ON blob_references (record_row_id, blob_row_id)");

    private static byte[] utf16(String value) {
        return value.getBytes(StandardCharsets.UTF_16LE);
    }

    private static void insert(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (var i = 0; i < values.length; i++) {
                statement.setObject(i + 1, values[i]);
            }
            statement.executeUpdate();
        }
    }

    /// Writes a database with Chromium's schema to `file`, and copies it while its log is unchecked-pointed.
    private static void write(Path original, Path copy, byte[] largeValue, byte[] bigBlob) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + original)) {
            try (var statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA wal_autocheckpoint=0");
                for (var sql : SCHEMA) {
                    statement.execute(sql);
                }
            }
            insert(connection, "INSERT INTO meta VALUES (?, ?)", "version", 1);
            insert(connection, "INSERT INTO meta VALUES (?, ?)", "last_compatible_version", 1);
            insert(connection, "INSERT INTO indexed_db_metadata VALUES (?, ?)", utf16("app"), 2);
            insert(connection, "INSERT INTO object_stores VALUES (?, ?, ?, ?, ?)", 1, utf16("users"), utf16("id"), 0, 1);
            insert(connection, "INSERT INTO object_stores VALUES (?, ?, ?, ?, ?)", 2, utf16("files"), null, 1, 3);
            insert(connection, "INSERT INTO indexes VALUES (?, ?, ?, ?, ?, ?)", 1, 30, utf16("byName"), utf16("name"), 1, 0);
            insert(connection, "INSERT INTO indexes VALUES (?, ?, ?, ?, ?, ?)", 1, 31, utf16("pair"), utf16("first last"), 1, 0);

            var plain = V8Writer.blink().latin1("plain").toByteArray();
            insert(connection, "INSERT INTO records VALUES (?, ?, ?, ?, ?)", 1, 1, 0, IdbKeyCodec.encodeSortable(new IdbKey.StringKey("b")), plain);
            insert(connection, "INSERT INTO records VALUES (?, ?, ?, ?, ?)", 2, 1, 1, IdbKeyCodec.encodeSortable(new IdbKey.StringKey("a")),
                    com.github.luben.zstd.Zstd.compress(largeValue, -4));
            var snappied = V8Writer.blink().latin1("snappy").toByteArray();
            insert(connection, "INSERT INTO records VALUES (?, ?, ?, ?, ?)", 3, 1, 2, IdbKeyCodec.encodeSortable(new IdbKey.NumberKey(10)),
                    org.xerial.snappy.Snappy.compress(snappied));
            var blinkWrapped = V8Writer.blink().latin1("wrapped").toByteArray();
            var wrapped = new java.io.ByteArrayOutputStream();
            wrapped.write(new byte[]{(byte) 0xFF, 0x11, 0x02});
            wrapped.write(org.xerial.snappy.Snappy.compress(blinkWrapped));
            insert(connection, "INSERT INTO records VALUES (?, ?, ?, ?, ?)", 4, 1, 0, IdbKeyCodec.encodeSortable(new IdbKey.DateKey(5)), wrapped.toByteArray());

            // A record of "files" whose value is an array of a blob, a file and a file kept as a legacy file
            var withBlobs = V8Writer.blink().tag('A').varint(3)
                    .tag('\\').tag('i').varint(0).tag('\\').tag('e').varint(1).tag('\\').tag('e').varint(2)
                    .tag('$').varint(0).varint(3).toByteArray();
            insert(connection, "INSERT INTO records VALUES (?, ?, ?, ?, ?)", 5, 2, 0, IdbKeyCodec.encodeSortable(new IdbKey.NumberKey(1)), withBlobs);
            var head = java.util.Arrays.copyOf(bigBlob, 1000);
            var tail = java.util.Arrays.copyOfRange(bigBlob, 1000, bigBlob.length);
            insert(connection, "INSERT INTO blobs VALUES (?, ?, ?, ?, ?, ?, ?)", 7, 0, "application/octet-stream", bigBlob.length, null, null, head);
            insert(connection, "INSERT INTO overflow_blob_chunks VALUES (?, ?, ?, ?)", 1, 7, 1, tail);
            // A handle, between the blobs by row id, but listed after them
            insert(connection, "INSERT INTO blobs VALUES (?, ?, ?, ?, ?, ?, ?)", 8, 2, null, null, null, null, new byte[]{42});
            insert(connection, "INSERT INTO blobs VALUES (?, ?, ?, ?, ?, ?, ?)", 9, 1, "text/plain", 4, "note.txt", 13_000_000_000_000_000L, "note".getBytes(StandardCharsets.UTF_8));
            insert(connection, "INSERT INTO blobs VALUES (?, ?, ?, ?, ?, ?, ?)", 10, 1, "text/plain", 6, "old.txt", null, null);
            for (var blob : List.of(7, 8, 9, 10)) {
                insert(connection, "INSERT INTO blob_references (blob_row_id, record_row_id) VALUES (?, ?)", blob, 5);
            }
            Files.copy(original, copy, StandardCopyOption.REPLACE_EXISTING);
            Files.copy(original.resolveSibling(original.getFileName() + "-wal"), copy.resolveSibling(copy.getFileName() + "-wal"), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.createDirectories(copy.resolveSibling(copy.getFileName() + "_"));
        Files.writeString(copy.resolveSibling(copy.getFileName() + "_").resolve("a"), "legacy");
    }

    @Test
    @DisplayName("metadata, records in key order, every compression and every kind of blob read as Chromium wrote them")
    void readsEverything(@TempDir Path directory) throws Exception {
        var origin = directory.resolve("original");
        Files.createDirectories(origin);
        var profile = directory.resolve("profile");
        var databaseDirectory = profile.resolve("IndexedDB").resolve("https_example.com_0");
        Files.createDirectories(databaseDirectory);
        var file = databaseDirectory.resolve(IndexedDbDirectory.sqliteFileName("app"));
        var random = new Random(9);
        var large = new StringBuilder();
        while (large.length() < 5000) {
            large.append("word").append(random.nextInt(100)).append(' ');
        }
        var largeValue = V8Writer.blink().latin1(large.toString()).toByteArray();
        var bigBlob = new byte[3000];
        random.nextBytes(bigBlob);
        write(origin.resolve("db"), file, largeValue, bigBlob);

        var chromium = ChromiumProfile.open(profile);
        assertEquals(List.of(ORIGIN), chromium.indexedDb().origins());
        assertEquals(StorageBackend.SQLITE, chromium.indexedDb().backend(ORIGIN).orElseThrow());
        assertEquals(List.of("app"), chromium.indexedDb().names(ORIGIN));
        try (var database = chromium.indexedDb().open(ORIGIN, "app").orElseThrow()) {
            assertEquals(2, database.version());
            var users = database.objectStore("users").orElseThrow();
            assertEquals(Optional.of(new IdbKeyPath.Single("id")), users.keyPath());
            assertEquals(new IdbKeyPath.Compound(List.of("first", "last")), users.index("pair").orElseThrow().keyPath());
            assertTrue(users.index("pair").orElseThrow().unique());
            assertTrue(database.objectStore("files").orElseThrow().autoIncrement());

            List<IdbRecord> records;
            try (var stream = database.records("users")) {
                records = stream.toList();
            }
            assertEquals(List.of(new IdbKey.NumberKey(10), new IdbKey.DateKey(5), new IdbKey.StringKey("a"), new IdbKey.StringKey("b")),
                    records.stream().map(IdbRecord::key).toList());
            assertEquals(new JsString("snappy"), records.get(0).value());
            assertEquals(new JsString("wrapped"), records.get(1).value());
            assertEquals(new JsString(large.toString()), records.get(2).value());
            assertEquals(new JsString("plain"), records.get(3).value());

            JsArray array;
            try (var cursor = database.openCursor("files")) {
                assertTrue(cursor.next());
                assertEquals(new IdbKey.NumberKey(1), cursor.key());
                assertEquals(3, cursor.blobs().size());
                array = assertInstanceOf(JsArray.class, cursor.value());
            }
            try (var stream = assertInstanceOf(JsBlob.class, array.get(0).orElseThrow()).open()) {
                assertArrayEquals(bigBlob, stream.readAllBytes());
            }
            var note = assertInstanceOf(JsFile.class, array.get(1).orElseThrow());
            assertEquals("note.txt", note.name());
            try (var stream = note.open()) {
                assertEquals("note", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
            try (var stream = assertInstanceOf(JsFile.class, array.get(2).orElseThrow()).open()) {
                assertEquals("legacy", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
            assertTrue(database.get("files", new IdbKey.NumberKey(2)).isEmpty());
        }
    }

    @Test
    @DisplayName("a schema newer than the reader knows is unsupported")
    void newerSchema(@TempDir Path directory) throws Exception {
        var file = directory.resolve("db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            try (var statement = connection.createStatement()) {
                for (var sql : SCHEMA) {
                    statement.execute(sql);
                }
            }
            insert(connection, "INSERT INTO meta VALUES (?, ?)", "last_compatible_version", 2);
            insert(connection, "INSERT INTO indexed_db_metadata VALUES (?, ?)", utf16("app"), 1);
        }
        var failure = assertThrows(ViridianException.class, () -> SqliteDatabaseReader.open(file, StorageBucket.defaultOf(ORIGIN)));
        assertEquals(ViridianException.Reason.UNSUPPORTED, failure.reason());
    }

    @Test
    @DisplayName("an origin with a LevelDB store is read from it, as Chromium does")
    void levelDbWins(@TempDir Path directory) throws IOException {
        var root = directory.resolve("IndexedDB");
        Files.createDirectories(root.resolve("https_example.com_0.indexeddb.leveldb"));
        Files.createDirectories(root.resolve("https_example.com_0"));
        Files.writeString(root.resolve("https_example.com_0").resolve(IndexedDbDirectory.sqliteFileName("app")), "");
        assertEquals(StorageBackend.LEVELDB, IndexedDbDirectory.Location.legacy(root, ORIGIN).backend().orElseThrow());
    }
}
