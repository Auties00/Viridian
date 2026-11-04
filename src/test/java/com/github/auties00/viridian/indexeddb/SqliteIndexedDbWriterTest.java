package com.github.auties00.viridian.indexeddb;

import com.github.auties00.viridian.IdbIndex;
import com.github.auties00.viridian.IdbKey;
import com.github.auties00.viridian.IdbKeyPath;
import com.github.auties00.viridian.IdbRecord;
import com.github.auties00.viridian.StorageBackend;
import com.github.auties00.viridian.StorageBucket;
import com.github.auties00.viridian.StorageOrigin;
import com.github.auties00.viridian.ViridianException;
import com.github.auties00.viridian.io.ValueAccess;
import com.github.auties00.viridian.value.StructuredClone;
import com.github.auties00.viridian.value.JsArray;
import com.github.auties00.viridian.value.JsBlob;
import com.github.auties00.viridian.value.JsDescriber;
import com.github.auties00.viridian.value.JsFile;
import com.github.auties00.viridian.value.JsNumber;
import com.github.auties00.viridian.value.JsObject;
import com.github.auties00.viridian.value.JsString;
import com.github.auties00.viridian.value.JsValue;
import com.github.auties00.viridian.value.SerializedValue;
import com.github.auties00.viridian.value.StructuredClone;
import com.github.auties00.viridian.value.V8Writer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The SQLite IndexedDB writer against Viridian's reader, SQLite itself through sqlite-jdbc, and the rows Chrome 157
/// writes, with values serialized by [StructuredClone].
@DisplayName("IndexedDB writer on SQLite")
class SqliteIndexedDbWriterTest {
    private static final StorageOrigin ORIGIN = StorageOrigin.parse("http://127.0.0.1:8080");
    // The data format version of values StructuredClone writes, V8 15 and Blink 21, and of Chrome 157's, V8 16
    private static final long V8_15 = 15L << 32 | 21;
    private static final long V8_16 = 16L << 32 | 21;

    // The sqlite_schema rows of a database file Chrome 157 created, but for their root pages
    private static final List<List<Object>> CHROME_SCHEMA = List.of(
            List.of("table", "indexed_db_metadata", "indexed_db_metadata", "CREATE TABLE indexed_db_metadata (name BLOB NOT NULL, version INTEGER NOT NULL)"),
            List.of("table", "object_stores", "object_stores", "CREATE TABLE object_stores (id INTEGER PRIMARY KEY, name BLOB NOT NULL, key_path BLOB, auto_increment INTEGER NOT NULL, key_generator_current_number INTEGER NOT NULL)"),
            List.of("table", "indexes", "indexes", "CREATE TABLE indexes (object_store_id INTEGER NOT NULL, id INTEGER NOT NULL, name BLOB NOT NULL, key_path BLOB, is_unique INTEGER NOT NULL, multi_entry INTEGER NOT NULL, PRIMARY KEY (object_store_id, id)) WITHOUT ROWID"),
            List.of("table", "records", "records", "CREATE TABLE records (row_id INTEGER PRIMARY KEY, object_store_id INTEGER NOT NULL, compression_type INTEGER NOT NULL, key BLOB NOT NULL, value BLOB NOT NULL)"),
            List.of("index", "records_by_key", "records", "CREATE UNIQUE INDEX records_by_key ON records(object_store_id, key)"),
            List.of("table", "index_references", "index_references", "CREATE TABLE index_references (record_row_id INTEGER NOT NULL, index_id INTEGER NOT NULL, key BLOB NOT NULL, object_store_id INTEGER NOT NULL, record_key BLOB NOT NULL, PRIMARY KEY (record_row_id, index_id, key)) WITHOUT ROWID"),
            List.of("index", "index_references_by_key", "index_references", "CREATE INDEX index_references_by_key ON index_references (object_store_id, index_id, key, record_key)"),
            List.of("table", "blobs", "blobs", "CREATE TABLE blobs (row_id INTEGER PRIMARY KEY AUTOINCREMENT, object_type INTEGER NOT NULL, mime_type TEXT, size_bytes INTEGER, file_name BLOB, last_modified INTEGER,  bytes BLOB)"),
            List.of("table", "sqlite_sequence", "sqlite_sequence", "CREATE TABLE sqlite_sequence(name,seq)"),
            List.of("index", "legacy_blobs", "blobs", "CREATE INDEX legacy_blobs ON blobs (object_type) WHERE bytes IS NULL"),
            List.of("table", "overflow_blob_chunks", "overflow_blob_chunks", "CREATE TABLE overflow_blob_chunks (row_id INTEGER PRIMARY KEY, blob_row_id INTEGER NOT NULL, chunk_index INTEGER NOT NULL, bytes BLOB NOT NULL)"),
            List.of("index", "overflow_chunks_by_blob", "overflow_blob_chunks", "CREATE INDEX overflow_chunks_by_blob ON overflow_blob_chunks (blob_row_id, chunk_index)"),
            List.of("table", "blob_references", "blob_references", "CREATE TABLE blob_references (row_id INTEGER PRIMARY KEY, blob_row_id INTEGER NOT NULL, record_row_id INTEGER)"),
            List.of("index", "blob_references_by_blob", "blob_references", "CREATE INDEX blob_references_by_blob ON blob_references (blob_row_id)"),
            List.of("index", "blob_references_by_record", "blob_references", "CREATE INDEX blob_references_by_record ON blob_references (record_row_id, blob_row_id)"),
            List.of("trigger", "on_record_deleted", "records", "CREATE TRIGGER on_record_deleted AFTER DELETE ON records BEGIN  DELETE FROM index_references WHERE record_row_id = OLD.row_id;  DELETE FROM blob_references WHERE record_row_id = OLD.row_id;END"),
            List.of("trigger", "on_blob_reference_deleted", "blob_references", "CREATE TRIGGER on_blob_reference_deleted  AFTER DELETE ON blob_references WHEN NOT EXISTS  (SELECT 1 FROM blob_references WHERE blob_row_id = OLD.blob_row_id) BEGIN  DELETE FROM blobs WHERE row_id = OLD.blob_row_id;END"),
            List.of("trigger", "on_blob_deleted", "blobs", "CREATE TRIGGER on_blob_deleted  AFTER DELETE ON blobs BEGIN  DELETE FROM overflow_blob_chunks WHERE blob_row_id = OLD.row_id;END"),
            List.of("table", "meta", "meta", "CREATE TABLE meta(key LONGVARCHAR NOT NULL UNIQUE PRIMARY KEY, value LONGVARCHAR)"),
            Arrays.asList("index", "sqlite_autoindex_meta_1", "meta", null));

    private static void write(Path directory, List<Content.Database> databases) throws IOException {
        Content.writeSqlite(directory, databases, StructuredClone::serialize);
    }

    private static Path file(Path directory, String name) {
        return directory.resolve(IndexedDbDirectory.sqliteFileName(name));
    }

    private static JsObject object(Object... properties) {
        var map = new LinkedHashMap<String, JsValue>();
        for (var i = 0; i < properties.length; i += 2) {
            map.put((String) properties[i], (JsValue) properties[i + 1]);
        }
        return JsObject.of(map);
    }

    private static Content.Record record(IdbKey key, JsValue value) {
        return new Content.Record(key, value);
    }

    private static IdbKey number(double value) {
        return new IdbKey.NumberKey(value);
    }

    private static IdbKey string(String value) {
        return new IdbKey.StringKey(value);
    }

    private static List<IdbRecord> records(SqliteDatabaseReader database, String store) throws IOException {
        try (var stream = database.records(store)) {
            return stream.toList();
        }
    }

    private static Content.Store people() {
        var byName = new IdbIndex("byName", new IdbKeyPath.Single("name"), true, false);
        var tags = new IdbIndex("tags", new IdbKeyPath.Single("tags"), false, true);
        var pair = new IdbIndex("pair", new IdbKeyPath.Compound(List.of("first", "last")), false, false);
        return Content.store("people", new IdbKeyPath.Single("id"), false, List.of(byName, tags, pair), List.of(
                record(number(2), object("id", new JsNumber(2), "name", new JsString("bob"), "tags", JsArray.of(new JsString("b"), new JsString("shared")),
                        "first", new JsString("Bob"), "last", new JsString("B"))),
                record(number(1), object("id", new JsNumber(1), "name", new JsString("alice"), "tags", JsArray.of(new JsString("a"), new JsString("shared")),
                        "first", new JsString("Alice"), "last", new JsString("A")))));
    }

    private static Connection open(Path file) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + file);
    }

    private static List<List<Object>> query(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            var rows = new ArrayList<List<Object>>();
            var columns = result.getMetaData().getColumnCount();
            while (result.next()) {
                var row = new ArrayList<>();
                for (var i = 1; i <= columns; i++) {
                    var value = result.getObject(i);
                    row.add(switch (value) {
                        case Integer integer -> (Object) integer.longValue();
                        case byte[] bytes -> HexFormat.of().formatHex(bytes);
                        case null, default -> value;
                    });
                }
                rows.add(row);
            }
            return rows;
        }
    }

    private static String hex(String utf16) {
        return HexFormat.of().formatHex(utf16.getBytes(StandardCharsets.UTF_16LE));
    }

    private static void assertSound(Path file) throws SQLException {
        try (var connection = open(file)) {
            assertEquals(List.of(List.of("ok")), query(connection, "PRAGMA integrity_check"));
        }
    }

    @Nested
    @DisplayName("read back by Viridian")
    class Readback {
        @Test
        @DisplayName("databases, stores, indexes and records read back as written, records in key order")
        void everything(@TempDir Path root) throws IOException, SQLException {
            var values = Content.store("values", null, false, List.of(), List.of(
                    record(string("b"), new JsString("two-byte 你 \uD800")),
                    record(string("a"), object("nested", JsArray.of(new JsNumber(1), new JsString("x"))))));
            var auto = Content.store("auto", null, true, List.of(), List.of(record(number(1), new JsString("first")), record(number(7.5), new JsString("second"))));
            var empty = Content.store("empty", new IdbKeyPath.Compound(List.of("a", "b")), false, List.of(), List.of());
            var blank = Content.store("", new IdbKeyPath.Single(""), false, List.of(), List.of(record(string("self"), new JsString("self"))));
            var directory = root.resolve("http_127.0.0.1_8080");
            write(directory, List.of(
                    new Content.Database("viridian", 3, List.of(values, people(), auto, empty, blank)),
                    new Content.Database("", 1, List.of(Content.store("only", null, false, List.of(), List.of(record(number(1), new JsString("one"))))))));

            assertEquals(List.of(ORIGIN), IndexedDbDirectory.origins(root));
            assertEquals(List.of("", "viridian"), IndexedDbDirectory.names(IndexedDbDirectory.Location.legacy(root, ORIGIN), StorageBucket.defaultOf(ORIGIN), StorageBackend.SQLITE));
            try (var unnamed = SqliteDatabaseReader.open(directory.resolve("0"), StorageBucket.defaultOf(ORIGIN))) {
                assertEquals(1, unnamed.version());
                assertEquals(new JsString("one"), records(unnamed, "only").getFirst().value());
            }
            try (var viridian = SqliteDatabaseReader.open(file(directory, "viridian"), StorageBucket.defaultOf(ORIGIN))) {
                assertEquals(3, viridian.version());
                assertEquals(List.of("values", "people", "auto", "empty", ""), viridian.objectStores().stream().map(store -> store.name()).toList());
                var peopleStore = viridian.objectStore("people").orElseThrow();
                assertEquals(Optional.of(new IdbKeyPath.Single("id")), peopleStore.keyPath());
                assertEquals(List.of(
                        new IdbIndex("byName", new IdbKeyPath.Single("name"), true, false),
                        new IdbIndex("tags", new IdbKeyPath.Single("tags"), false, true),
                        new IdbIndex("pair", new IdbKeyPath.Compound(List.of("first", "last")), false, false)), peopleStore.indexes());
                assertTrue(viridian.objectStore("auto").orElseThrow().autoIncrement());
                assertEquals(Optional.of(new IdbKeyPath.Compound(List.of("a", "b"))), viridian.objectStore("empty").orElseThrow().keyPath());
                assertEquals(Optional.of(new IdbKeyPath.Single("")), viridian.objectStore("").orElseThrow().keyPath());
                var valueRecords = records(viridian, "values");
                assertEquals(List.of(string("a"), string("b")), valueRecords.stream().map(IdbRecord::key).toList());
                assertEquals(JsDescriber.describe(values.records().get(1).value()), JsDescriber.describe(valueRecords.getFirst().value()));
                assertEquals(new JsString("two-byte 你 \uD800"), valueRecords.get(1).value());
                assertEquals(List.of(number(1), number(2)), records(viridian, "people").stream().map(IdbRecord::key).toList());
                assertEquals(List.of(), records(viridian, "empty"));
                assertEquals(new JsString("second"), viridian.get("auto", number(7.5)).orElseThrow());
            }
            assertSound(directory.resolve("0"));
            assertSound(file(directory, "viridian"));
        }

        @Test
        @DisplayName("no databases create no directory")
        void none(@TempDir Path root) throws IOException {
            write(root.resolve("http_127.0.0.1_8080"), List.of());
            assertFalse(Files.exists(root.resolve("http_127.0.0.1_8080")));
        }

        @Test
        @DisplayName("blobs, files and an empty blob read back with their bytes, type, name and time")
        void blobs(@TempDir Path root) throws IOException {
            var modified = Instant.parse("2020-09-13T12:26:40.123Z");
            var value = JsArray.of(JsBlob.of("hello blob".getBytes(StandardCharsets.UTF_8), "text/plain"),
                    JsFile.of("name.txt", "file body".getBytes(StandardCharsets.UTF_8), "text/plain", modified),
                    JsBlob.of(new byte[0], ""));
            var store = Content.store("files", null, false, List.of(), List.of(record(string("blobs"), value)));
            write(root, List.of(new Content.Database("app", 1, List.of(store))));

            try (var database = SqliteDatabaseReader.open(file(root, "app"), StorageBucket.defaultOf(ORIGIN))) {
                var record = records(database, "files").getFirst();
                assertEquals(JsDescriber.describe(value), JsDescriber.describe(record.value()));
                var file = assertInstanceOf(JsFile.class, assertInstanceOf(JsArray.class, record.value()).values().get(1));
                assertEquals("name.txt", file.name());
                assertEquals(modified, file.lastModified().orElseThrow());
            }
        }

        @Test
        @DisplayName("a blob past 5 MiB continues in overflow chunks, and a large value is stored whole")
        void large(@TempDir Path root) throws IOException, SQLException {
            var bytes = new byte[12 * 1024 * 1024 + 7];
            new Random(3).nextBytes(bytes);
            var text = new JsString("large value ".repeat(300_000));
            var store = Content.store("files", null, false, List.of(), List.of(
                    record(string("blob"), JsBlob.of(bytes, "application/octet-stream")), record(string("text"), text)));
            write(root, List.of(new Content.Database("app", 1, List.of(store))));

            try (var connection = open(file(root, "app"))) {
                assertEquals(List.of(List.of(1L, 0L, 5L * 1024 * 1024, (long) bytes.length)),
                        query(connection, "SELECT row_id, object_type, length(bytes), size_bytes FROM blobs"));
                assertEquals(List.of(List.of(1L, 1L, 5L * 1024 * 1024), List.of(1L, 2L, 2L * 1024 * 1024 + 7)),
                        query(connection, "SELECT blob_row_id, chunk_index, length(bytes) FROM overflow_blob_chunks ORDER BY chunk_index"));
                assertEquals(List.of(List.of(0L, (long) StructuredClone.serialize(text).bytes().length)),
                        query(connection, "SELECT compression_type, length(value) FROM records WHERE row_id = 2"));
                assertEquals(List.of(List.of("ok")), query(connection, "PRAGMA integrity_check"));
            }
            try (var database = SqliteDatabaseReader.open(file(root, "app"), StorageBucket.defaultOf(ORIGIN))) {
                var blob = assertInstanceOf(JsBlob.class, database.get("files", string("blob")).orElseThrow());
                try (var stream = blob.open()) {
                    assertArrayEquals(bytes, stream.readAllBytes());
                }
                assertEquals(text, database.get("files", string("text")).orElseThrow());
            }
        }

        @Test
        @DisplayName("a blob and a file of unknown size and time are stored in the order of their indices")
        void handles(@TempDir Path root) throws IOException, SQLException {
            ValueSerializer serializer = _ -> new SerializedValue(V8Writer.blink().tag('\\').tag('i').varint(0).toByteArray(),
                    List.of(ValueAccess.get().blob("text/plain", 2, () -> new ByteArrayInputStream(new byte[]{'h', 'i'})),
                            ValueAccess.get().file("unknown", "", -1, null, () -> new ByteArrayInputStream(new byte[]{'x'}))));
            var store = Content.store("handles", null, false, List.of(), List.of(record(number(1), new JsString("ignored"))));
            Content.writeSqlite(root, List.of(new Content.Database("app", 1, List.of(store))), serializer);

            try (var database = SqliteDatabaseReader.open(file(root, "app"), StorageBucket.defaultOf(ORIGIN))) {
                List<JsBlob> externals;
                try (var cursor = database.openCursor("handles")) {
                    assertTrue(cursor.next());
                    externals = cursor.blobs();
                }
                assertEquals(2, externals.size());
                assertEquals("text/plain", externals.getFirst().type());
                assertEquals(1, assertInstanceOf(JsFile.class, externals.get(1)).size());
            }
            try (var connection = open(file(root, "app"))) {
                assertEquals(List.of(
                        Arrays.asList(2L, 1L, 1L, 0L, "78")),
                        query(connection, "SELECT row_id, object_type, size_bytes, last_modified, hex(bytes) FROM blobs WHERE row_id > 1"));
            }
        }
    }

    @Nested
    @DisplayName("the rows Chrome writes")
    class Rows {
        @Test
        @DisplayName("the schema, user version, page size and meta rows of a file Chrome 157 creates")
        void schema(@TempDir Path root) throws IOException, SQLException {
            var before = Instant.now();
            write(root, List.of(new Content.Database("viridian", 3, List.of())));
            var after = Instant.now();
            try (var connection = open(file(root, "viridian"))) {
                var schema = new ArrayList<List<Object>>();
                for (var row : query(connection, "SELECT type, name, tbl_name, sql FROM sqlite_schema")) {
                    schema.add(row);
                }
                assertEquals(CHROME_SCHEMA, schema);
                assertEquals(List.of(List.of(0L)), query(connection, "PRAGMA user_version"));
                assertEquals(List.of(List.of(4096L)), query(connection, "PRAGMA page_size"));
                var meta = query(connection, "SELECT key, value, typeof(value) FROM meta");
                assertEquals(List.of(
                        List.of("mmap_status", "-1", "text"),
                        List.of("version", "1", "text"),
                        List.of("last_compatible_version", "1", "text"),
                        List.of("v8_data_version", Long.toString(V8_15), "text")), meta.subList(0, 4));
                assertEquals("created_time", meta.get(4).getFirst());
                var created = Instant.parse("1601-01-01T00:00:00Z").plus(Long.parseLong((String) meta.get(4).get(1)), ChronoUnit.MICROS);
                assertFalse(created.isBefore(before.truncatedTo(ChronoUnit.MICROS)) || created.isAfter(after));
                assertEquals(List.of(List.of(hex("viridian"), 3L)), query(connection, "SELECT name, version FROM indexed_db_metadata"));
                assertEquals(List.of(List.of("ok")), query(connection, "PRAGMA integrity_check"));
            }
        }

        @Test
        @DisplayName("store, index, record and index reference rows, as Chrome 157 writes them")
        void store(@TempDir Path root) throws IOException, SQLException {
            var values = Content.store("values", null, false, List.of(), List.of());
            write(root, List.of(new Content.Database("viridian", 3, List.of(values, people()))));
            try (var connection = open(file(root, "viridian"))) {
                assertEquals(List.of(
                        Arrays.asList(1L, hex("values"), null, 0L, 1L),
                        List.of(2L, hex("people"), hex("id"), 0L, 1L)), query(connection, "SELECT * FROM object_stores"));
                assertEquals(List.of(
                        List.of(2L, 31L, hex("byName"), hex("name"), 1L, 0L),
                        List.of(2L, 32L, hex("tags"), hex("tags"), 0L, 1L),
                        List.of(2L, 33L, hex("pair"), hex("first last"), 0L, 0L)), query(connection, "SELECT * FROM indexes"));
                assertEquals(List.of(List.of(1L, 2L, 0L, "10bff0000000000000"), List.of(2L, 2L, 0L, "10c000000000000000")),
                        query(connection, "SELECT row_id, object_store_id, compression_type, key FROM records"));
                // Captured from the database Chrome 157 wrote for the same records, with its record row ids
                assertEquals(List.of(
                        List.of(1L, 31L, "30626d6a646600", 2L, "10bff0000000000000"),
                        List.of(1L, 32L, "306200", 2L, "10bff0000000000000"),
                        List.of(1L, 32L, "3074696273666500", 2L, "10bff0000000000000"),
                        List.of(1L, 33L, "5030426d6a64660030420000", 2L, "10bff0000000000000"),
                        List.of(2L, 31L, "3063706300", 2L, "10c000000000000000"),
                        List.of(2L, 32L, "306300", 2L, "10c000000000000000"),
                        List.of(2L, 32L, "3074696273666500", 2L, "10c000000000000000"),
                        List.of(2L, 33L, "50304370630030430000", 2L, "10c000000000000000")),
                        query(connection, "SELECT * FROM index_references ORDER BY record_row_id, index_id, key"));
            }
        }

        @Test
        @DisplayName("blob rows: object types, text types and names, times in microseconds since 1601, the sequence")
        void blobs(@TempDir Path root) throws IOException, SQLException {
            var value = JsArray.of(JsBlob.of("hello blob".getBytes(StandardCharsets.UTF_8), "text/plain"),
                    JsFile.of("name.txt", "file body".getBytes(StandardCharsets.UTF_8), "text/plain", Instant.ofEpochMilli(1_600_000_000_000L)),
                    JsFile.of("lone \uD800.txt", new byte[0], "", Instant.parse("1600-12-31T23:59:59Z")));
            var store = Content.store("files", null, false, List.of(), List.of(record(string("blobs"), value), record(string("none"), new JsNumber(1))));
            write(root, List.of(new Content.Database("app", 1, List.of(store))));
            try (var connection = open(file(root, "app"))) {
                // The first two rows as Chrome 157 wrote them for the same blob and file
                assertEquals(List.of(
                        Arrays.asList(1L, 0L, "text/plain", "text", 10L, null, "null", null, "68656C6C6F20626C6F62", "blob"),
                        Arrays.asList(2L, 1L, "text/plain", "text", 9L, "name.txt", "text", 13_244_473_600_000_000L, "66696C6520626F6479", "blob"),
                        Arrays.asList(3L, 1L, "", "text", 0L, "lone \uFFFD.txt", "text", -1_000_000L, "", "blob")),
                        query(connection, "SELECT row_id, object_type, mime_type, typeof(mime_type), size_bytes, file_name, typeof(file_name), "
                                + "last_modified, hex(bytes), typeof(bytes) FROM blobs"));
                assertEquals(List.of(List.of(1L, 1L, 1L), List.of(2L, 2L, 1L), List.of(3L, 3L, 1L)), query(connection, "SELECT * FROM blob_references"));
                assertEquals(List.of(List.of("blobs", 3L)), query(connection, "SELECT * FROM sqlite_sequence"));
            }
        }

        @Test
        @DisplayName("an auto-increment store's key generator continues past its largest number key, up to 2^53 + 1")
        void keyGenerator(@TempDir Path root) throws IOException, SQLException {
            var stores = List.of(
                    Content.store("auto", null, true, List.of(), List.of(record(number(3.5), new JsString("a")), record(number(-9), new JsString("b")),
                            record(string("z"), new JsString("c")))),
                    Content.store("huge", null, true, List.of(), List.of(record(number(Double.POSITIVE_INFINITY), new JsString("a")))),
                    Content.store("plain", null, false, List.of(), List.of(record(number(10), new JsString("a")))));
            write(root, List.of(new Content.Database("app", 1, stores)));
            try (var connection = open(file(root, "app"))) {
                assertEquals(List.of(List.of(4L), List.of((1L << 53) + 1), List.of(1L)),
                        query(connection, "SELECT key_generator_current_number FROM object_stores ORDER BY id"));
            }
        }

        @Test
        @DisplayName("the data format version is the newest V8 version among the values, V8 15 for none")
        void dataFormat(@TempDir Path root) throws IOException, SQLException {
            var chrome = HexFormat.of().parseHex("ff15fe000000000000001100000000ff100022036f6e65");
            ValueSerializer serializer = value -> value instanceof JsString string && string.value().equals("chrome")
                    ? new SerializedValue(chrome, List.of()) : StructuredClone.serialize(value);
            var mixed = Content.store("mixed", null, false, List.of(), List.of(record(number(1), new JsString("chrome")),
                    record(number(2), new JsString("viridian"))));
            var own = Content.store("own", null, false, List.of(), List.of(record(number(1), new JsString("viridian"))));
            Content.writeSqlite(root, List.of(new Content.Database("mixed", 1, List.of(mixed)),
                    new Content.Database("own", 1, List.of(own))), serializer);
            for (var expected : List.of(List.of("mixed", V8_16), List.of("own", V8_15))) {
                try (var connection = open(file(root, (String) expected.getFirst()))) {
                    assertEquals(List.of(List.of(Long.toString((Long) expected.get(1)))),
                            query(connection, "SELECT value FROM meta WHERE key = 'v8_data_version'"));
                }
            }
        }

        @Test
        @DisplayName("SQLite runs Chromium's triggers: deleting a record deletes its index references, blobs and chunks")
        void triggers(@TempDir Path root) throws IOException, SQLException {
            var bytes = new byte[6 * 1024 * 1024];
            var index = new IdbIndex("byName", new IdbKeyPath.Single("name"), false, false);
            var store = Content.store("files", null, false, List.of(index), List.of(
                    record(number(1), object("name", new JsString("big"), "blob", JsBlob.of(bytes, ""))),
                    record(number(2), object("name", new JsString("small"), "blob", JsBlob.of(new byte[]{1}, "")))));
            write(root, List.of(new Content.Database("app", 1, List.of(store))));
            try (var connection = open(file(root, "app")); var statement = connection.createStatement()) {
                statement.execute("PRAGMA recursive_triggers=ON");
                statement.execute("DELETE FROM records WHERE row_id = 1");
                assertEquals(List.of(List.of(2L)), query(connection, "SELECT record_row_id FROM index_references"));
                assertEquals(List.of(List.of(2L)), query(connection, "SELECT row_id FROM blobs"));
                assertEquals(List.of(List.of(0L)), query(connection, "SELECT count(*) FROM overflow_blob_chunks"));
                assertEquals(List.of(List.of("ok")), query(connection, "PRAGMA integrity_check"));
            }
        }
    }

    @Nested
    @DisplayName("what it refuses")
    class Refusals {
        @Test
        @DisplayName("two records with one key in a unique index, leaving no database file")
        void unique(@TempDir Path root) throws IOException {
            var index = new IdbIndex("byName", new IdbKeyPath.Single("name"), true, false);
            var store = Content.store("people", null, false, List.of(index), List.of(
                    record(number(1), object("name", new JsString("same"))), record(number(2), object("name", new JsString("same")))));
            assertThrows(IllegalArgumentException.class, () -> write(root, List.of(new Content.Database("second", 1, List.of(store)))));
            try (var files = Files.list(root)) {
                assertEquals(List.of(), files.toList());
            }
        }

        @Test
        @DisplayName("two records whose keys IndexedDB holds equal, 0 and -0")
        void equalKeys(@TempDir Path root) {
            var store = Content.store("values", null, false, List.of(), List.of(
                    record(number(0), new JsString("zero")), record(number(-0.0), new JsString("negative zero"))));
            assertThrows(IllegalArgumentException.class, () -> write(root, List.of(new Content.Database("app", 1, List.of(store)))));
        }

        @Test
        @DisplayName("a blob without a source, naming its record")
        void missing(@TempDir Path root) {
            ValueSerializer serializer = _ -> new SerializedValue(V8Writer.blink().tag('\\').tag('i').varint(0).toByteArray(),
                    List.of(StructuredClone.deserialize(V8Writer.blink().tag('\\').tag('b').blinkString("uuid").blinkString("").varint(5).toByteArray(), List.of()) instanceof JsBlob blob ? blob : null));
            var store = Content.store("files", null, false, List.of(), List.of(record(number(1), new JsString("ignored"))));
            var failure = assertThrows(ViridianException.class, () -> Content.writeSqlite(root, List.of(new Content.Database("app", 1, List.of(store))), serializer));
            assertEquals(ViridianException.Reason.MISSING, failure.reason());
            assertTrue(failure.getMessage().contains("files"), failure.getMessage());
        }

        @Test
        @DisplayName("a blob whose source supplies other than its size, leaving no database file")
        void blobSize(@TempDir Path root) throws IOException {
            ValueSerializer lying = _ -> new SerializedValue(V8Writer.blink().tag('\\').tag('i').varint(0).toByteArray(),
                    List.of(ValueAccess.get().blob("", 5, () -> new ByteArrayInputStream(new byte[3]))));
            var store = Content.store("files", null, false, List.of(), List.of(record(number(1), new JsString("ignored"))));
            assertThrows(ViridianException.class, () -> Content.writeSqlite(root, List.of(new Content.Database("app", 1, List.of(store))), lying));
            try (var files = Files.list(root)) {
                assertEquals(List.of(), files.toList());
            }
        }
    }
}
