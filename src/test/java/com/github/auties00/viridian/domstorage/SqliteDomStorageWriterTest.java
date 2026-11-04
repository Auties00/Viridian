package com.github.auties00.viridian.domstorage;

import com.github.auties00.viridian.StorageKey;
import com.github.auties00.viridian.StorageOrigin;
import com.github.auties00.viridian.ViridianException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Writes Local Storage databases on the SQLite backend and checks them with SQLite and with the reader: the schema,
/// `meta` rows and storage key Chromium writes, Blink's string formats, and other storage keys copied untouched.
@DisplayName("Local Storage writer on SQLite")
class SqliteDomStorageWriterTest {
    private static final StorageOrigin ORIGIN = StorageOrigin.parse("https://example.com");

    // The sqlite_schema rows of a LocalStorage file Chrome 157 created, but for their root pages
    private static final List<List<Object>> CHROME_SCHEMA = List.of(
            List.of("table", "maps", "maps", "CREATE TABLE maps(row_id INTEGER PRIMARY KEY AUTOINCREMENT,storage_key BLOB NOT NULL,last_accessed INTEGER,last_modified INTEGER,total_size INTEGER)"),
            List.of("table", "sqlite_sequence", "sqlite_sequence", "CREATE TABLE sqlite_sequence(name,seq)"),
            List.of("index", "maps_by_storage_key", "maps", "CREATE UNIQUE INDEX maps_by_storage_key ON maps(storage_key)"),
            List.of("table", "map_entries", "map_entries", "CREATE TABLE map_entries(map_id INTEGER NOT NULL,value_compression_type INTEGER NOT NULL,key BLOB NOT NULL,value BLOB NOT NULL,PRIMARY KEY(map_id, key)) WITHOUT ROWID"),
            List.of("table", "meta", "meta", "CREATE TABLE meta(key LONGVARCHAR NOT NULL UNIQUE PRIMARY KEY, value LONGVARCHAR)"),
            Arrays.asList("index", "sqlite_autoindex_meta_1", "meta", null));

    /// {@return items in every string form: Latin-1, beyond it, lone surrogates, empty and long}
    static Map<String, String> items() {
        var items = new LinkedHashMap<String, String>();
        items.put("latin", "café");
        items.put("é key", "ascii value");
        items.put("unicode", "你好");
        items.put("emoji 😀", "value 😀");
        items.put("lone \uD800", "\uDC00 lone low");
        items.put("", "empty key");
        items.put("empty value", "");
        items.put("long latin", "ÿ".repeat(1_000_000));
        items.put("long unicode", "é你".repeat(300_000));
        return items;
    }

    private static List<List<Object>> query(Connection connection, String sql, Object... parameters) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (var i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            try (var result = statement.executeQuery()) {
                var rows = new ArrayList<List<Object>>();
                var columns = result.getMetaData().getColumnCount();
                while (result.next()) {
                    var row = new ArrayList<>();
                    for (var i = 1; i <= columns; i++) {
                        var value = result.getObject(i);
                        row.add(value instanceof Integer integer ? (Object) integer.longValue() : value);
                    }
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    private static Connection open(Path file) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + file);
    }

    /// Builds a Local Storage database as Chromium would leave it after several origins wrote and one was cleared,
    /// in write-ahead log mode with frames never checkpointed, and copies it with its log into `copy`.
    private static void existing(Path directory, Path copy) throws Exception {
        var file = directory.resolve("source").resolve("LocalStorage");
        Files.createDirectories(file.getParent());
        try (var connection = open(file); var statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA wal_autocheckpoint = 0");
            for (var sql : List.of(
                    "CREATE TABLE maps(row_id INTEGER PRIMARY KEY AUTOINCREMENT,storage_key BLOB NOT NULL,last_accessed INTEGER,last_modified INTEGER,total_size INTEGER)",
                    "CREATE UNIQUE INDEX maps_by_storage_key ON maps(storage_key)",
                    "CREATE TABLE map_entries(map_id INTEGER NOT NULL,value_compression_type INTEGER NOT NULL,key BLOB NOT NULL,value BLOB NOT NULL,PRIMARY KEY(map_id, key)) WITHOUT ROWID",
                    "CREATE TABLE meta(key LONGVARCHAR NOT NULL UNIQUE PRIMARY KEY, value LONGVARCHAR)",
                    "INSERT INTO meta VALUES ('mmap_status', -1), ('version', 1), ('last_compatible_version', 1)")) {
                statement.execute(sql);
            }
            var maps = connection.prepareStatement("INSERT INTO maps(storage_key, last_accessed, last_modified, total_size) VALUES (?, ?, ?, ?)");
            var keys = List.of("https://gone.example/", "https://other.example/", "https://example.com/", "https://example.com/^0https://top.example",
                    "http://example.com/", "https://cleared.example/", "https://compressed.example/");
            for (var i = 0; i < keys.size(); i++) {
                maps.setBytes(1, keys.get(i).getBytes(StandardCharsets.ISO_8859_1));
                maps.setLong(2, 13_400_000_000_000_000L + i);
                maps.setObject(3, i == 3 ? null : 13_400_000_000_000_100L + i);
                maps.setObject(4, i == 3 ? null : (long) i * 100);
                maps.executeUpdate();
            }
            var entries = connection.prepareStatement("INSERT INTO map_entries VALUES (?, ?, ?, ?)");
            for (var map = 1; map <= keys.size(); map++) {
                for (var i = 0; i < 40; i++) {
                    entries.setLong(1, map);
                    entries.setInt(2, map == 7 && i % 2 == 0 ? 1 : 0);
                    entries.setBytes(3, DomStorageText.encodeLocal("key " + i + (i % 3 == 0 ? " 你" : "")));
                    var value = DomStorageText.encodeLocal(("value " + map + " " + i).repeat(i % 10 == 0 ? 400 : 1));
                    entries.setBytes(4, map == 7 && i % 2 == 0 ? com.github.luben.zstd.Zstd.compress(value, 3) : value);
                    entries.executeUpdate();
                }
            }
            statement.execute("DELETE FROM maps WHERE row_id = 1");
            statement.execute("DELETE FROM map_entries WHERE map_id = 1");
            statement.execute("DELETE FROM maps WHERE row_id = 6");
            statement.execute("DELETE FROM map_entries WHERE map_id = 6");
            Files.createDirectories(copy.getParent());
            Files.copy(file, copy);
            Files.copy(Path.of(file + "-wal"), Path.of(copy + "-wal"));
        }
    }

    private static void assertIntact(Connection connection) throws SQLException {
        assertEquals(List.of(List.of("ok")), query(connection, "PRAGMA integrity_check"));
    }

    @Test
    @DisplayName("writes a fresh database with Chromium's schema, meta rows and string formats")
    void fresh(@TempDir Path directory) throws Exception {
        var target = directory.resolve("LocalStorage");
        var before = ChronoUnit.MICROS.between(Instant.EPOCH, Instant.now()) + 11_644_473_600_000_000L;
        SqliteDomStorageWriter.replaceLocalStorage(directory.resolve("missing"), target, Map.of(StorageKey.of(ORIGIN), items()));
        var after = ChronoUnit.MICROS.between(Instant.EPOCH, Instant.now()) + 11_644_473_600_000_000L;
        assertEquals(items(), SqliteDomStorage.localStorage(target, StorageKey.of(ORIGIN)));
        assertEquals(List.of(StorageKey.of(ORIGIN)), SqliteDomStorage.localStorageKeys(target));
        try (var connection = open(target)) {
            assertIntact(connection);
            assertEquals(CHROME_SCHEMA, query(connection, "SELECT type, name, tbl_name, sql FROM sqlite_schema ORDER BY rowid"));
            assertEquals(List.of(List.of("mmap_status", "text", "-1"), List.of("version", "text", "1"), List.of("last_compatible_version", "text", "1")),
                    query(connection, "SELECT key, typeof(value), value FROM meta ORDER BY rowid"));
            assertEquals(List.of(List.of("maps", 1L)), query(connection, "SELECT * FROM sqlite_sequence"));
            var map = query(connection, "SELECT row_id, typeof(storage_key), storage_key, last_accessed, last_modified, total_size FROM maps").getFirst();
            assertEquals(1L, map.get(0));
            assertEquals("blob", map.get(1));
            assertEquals("https://example.com/", new String((byte[]) map.get(2), StandardCharsets.ISO_8859_1));
            assertTrue((Long) map.get(3) >= before && (Long) map.get(3) <= after, "last_accessed is the time of writing");
            assertEquals(map.get(3), map.get(4));
            var size = 0L;
            for (var item : items().entrySet()) {
                size += DomStorageText.encodeLocal(item.getKey()).length + DomStorageText.encodeLocal(item.getValue()).length;
            }
            assertEquals(size, map.get(5));
            assertEquals(List.of(List.of(0L, "01636166E9")), query(connection, "SELECT value_compression_type, hex(value) FROM map_entries WHERE key = x'016C6174696E'"),
                    "a Latin-1 value has format byte 1");
            assertEquals(List.of(List.of("00604F7D59")), query(connection, "SELECT hex(value) FROM map_entries WHERE key = x'01756E69636F6465'"),
                    "a value beyond Latin-1 has format byte 0 and UTF-16LE code units");
            assertEquals(List.of(List.of("01656D707479206B6579")), query(connection, "SELECT hex(value) FROM map_entries WHERE key = x'01'"),
                    "the empty key is the Latin-1 format byte alone");
            assertEquals(List.of(List.of("006C006F006E0065002000" + "00D8")), query(connection, "SELECT hex(key) FROM map_entries WHERE hex(key) LIKE '006C006F006E%'"),
                    "a lone surrogate is kept as its code unit");
        }
    }

    @Test
    @DisplayName("copies every other storage key's map untouched and replaces the origin's in place")
    void copy(@TempDir Path directory) throws Exception {
        var existing = directory.resolve("profile").resolve("LocalStorage");
        existing(directory, existing);
        var target = directory.resolve("LocalStorage");
        var items = items();
        SqliteDomStorageWriter.replaceLocalStorage(existing, target, Map.of(StorageKey.of(ORIGIN), items));
        assertEquals(items, SqliteDomStorage.localStorage(target, StorageKey.of(ORIGIN)));
        for (var origin : List.of("https://other.example", "http://example.com", "https://compressed.example")) {
            assertEquals(SqliteDomStorage.localStorage(existing, StorageKey.of(StorageOrigin.parse(origin))), SqliteDomStorage.localStorage(target, StorageKey.of(StorageOrigin.parse(origin))), origin);
        }
        assertEquals(SqliteDomStorage.localStorageKeys(existing), SqliteDomStorage.localStorageKeys(target));
        try (var before = open(existing); var after = open(target)) {
            assertIntact(after);
            assertEquals(query(before, "SELECT type, name, tbl_name, sql FROM sqlite_schema ORDER BY rowid"),
                    query(after, "SELECT type, name, tbl_name, sql FROM sqlite_schema ORDER BY rowid"));
            for (var sql : List.of(
                    "SELECT * FROM meta ORDER BY rowid",
                    "SELECT * FROM sqlite_sequence ORDER BY rowid",
                    "SELECT row_id, hex(storage_key), last_accessed, last_modified, total_size FROM maps WHERE row_id <> 3 ORDER BY row_id",
                    "SELECT map_id, value_compression_type, hex(key), hex(value) FROM map_entries WHERE map_id <> 3 ORDER BY map_id, key")) {
                assertEquals(query(before, sql), query(after, sql), sql);
            }
            assertEquals(List.of(List.of(3L)), query(after, "SELECT row_id FROM maps WHERE storage_key = ?", "https://example.com/".getBytes(StandardCharsets.ISO_8859_1)));
            assertEquals(List.of(List.of((long) items.size())), query(after, "SELECT count(*) FROM map_entries WHERE map_id = 3"));
        }
    }

    @Test
    @DisplayName("drops the origin's map when it has no items, and gives a new origin the next AUTOINCREMENT row_id")
    void emptiedAndNew(@TempDir Path directory) throws Exception {
        var existing = directory.resolve("profile").resolve("LocalStorage");
        existing(directory, existing);
        var emptied = directory.resolve("emptied");
        SqliteDomStorageWriter.replaceLocalStorage(existing, emptied, Map.of(StorageKey.of(ORIGIN), Map.of()));
        assertEquals(Map.of(), SqliteDomStorage.localStorage(emptied, StorageKey.of(ORIGIN)));
        try (var connection = open(emptied)) {
            assertIntact(connection);
            assertEquals(List.of(List.of(2L), List.of(4L), List.of(5L), List.of(7L)), query(connection, "SELECT row_id FROM maps ORDER BY row_id"));
            assertEquals(List.of(List.of(0L)), query(connection, "SELECT count(*) FROM map_entries WHERE map_id = 3"));
        }
        var added = directory.resolve("added");
        var origin = StorageOrigin.parse("http://127.0.0.1:8080");
        SqliteDomStorageWriter.replaceLocalStorage(existing, added, Map.of(StorageKey.of(origin), Map.of("k", "v")));
        assertEquals(Map.of("k", "v"), SqliteDomStorage.localStorage(added, StorageKey.of(origin)));
        try (var connection = open(added)) {
            assertIntact(connection);
            assertEquals(List.of(List.of(8L, "http://127.0.0.1:8080/")),
                    query(connection, "SELECT row_id, CAST(storage_key AS TEXT) FROM maps WHERE row_id > 7"));
            assertEquals(List.of(List.of("maps", 8L)), query(connection, "SELECT * FROM sqlite_sequence"));
        }
    }

    @Test
    @DisplayName("refuses a database of another schema version and leaves no target behind")
    void otherVersion(@TempDir Path directory) throws Exception {
        var existing = directory.resolve("profile").resolve("LocalStorage");
        existing(directory, existing);
        try (var connection = open(existing); var statement = connection.createStatement()) {
            statement.execute("UPDATE meta SET value = '2' WHERE key = 'version'");
        }
        var target = directory.resolve("LocalStorage");
        var failure = assertThrows(ViridianException.class, () -> SqliteDomStorageWriter.replaceLocalStorage(existing, target, Map.of(StorageKey.of(ORIGIN), Map.of("k", "v"))));
        assertEquals(ViridianException.Reason.UNSUPPORTED, failure.reason());
        assertFalse(Files.exists(target));
    }
}
