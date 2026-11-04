package com.github.auties00.viridian.domstorage;

import com.github.auties00.viridian.ChromiumProfile;
import com.github.auties00.viridian.SessionStorageArea;
import com.github.auties00.viridian.StorageBackend;
import com.github.auties00.viridian.StorageOrigin;
import com.github.auties00.viridian.ViridianException;
import com.github.auties00.viridian.leveldb.LevelDbWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Local and Session Storage on both backends, against databases written by hand on LevelDB and through SQLite itself,
/// with the schema Chromium creates.
class DomStorageTest {
    private static final StorageOrigin ORIGIN = StorageOrigin.parse("https://example.com");
    private static final String NI_HAO = "你好";
    private static final String STEP = "stép";
    private static final String FIRST_SESSION = "11111111_2222_3333_4444_555555555555";
    private static final String SECOND_SESSION = "aaaaaaaa_bbbb_cccc_dddd_eeeeeeeeeeee";
    private static final String THIRD_SESSION = "99999999_8888_7777_6666_555555555555";

    private static final List<String> LOCAL_STORAGE_SCHEMA = List.of(
            "CREATE TABLE maps(row_id INTEGER PRIMARY KEY AUTOINCREMENT,storage_key BLOB NOT NULL,last_accessed INTEGER,last_modified INTEGER,total_size INTEGER)",
            "CREATE UNIQUE INDEX maps_by_storage_key ON maps(storage_key)",
            "CREATE TABLE map_entries(map_id INTEGER NOT NULL,value_compression_type INTEGER NOT NULL,key BLOB NOT NULL,value BLOB NOT NULL,PRIMARY KEY(map_id, key)) WITHOUT ROWID",
            "CREATE TABLE meta(key LONGVARCHAR NOT NULL UNIQUE PRIMARY KEY, value LONGVARCHAR)");

    private static final List<String> SESSION_STORAGE_SCHEMA = List.of(
            "CREATE TABLE session_metadata(session_id TEXT NOT NULL,storage_key BLOB NOT NULL,map_id INTEGER NOT NULL,PRIMARY KEY(session_id, storage_key)) WITHOUT ROWID",
            "CREATE TABLE map_entries(map_id INTEGER NOT NULL,value_compression_type INTEGER NOT NULL,key BLOB NOT NULL,value BLOB NOT NULL,PRIMARY KEY(map_id, key)) WITHOUT ROWID",
            "CREATE TABLE meta(key LONGVARCHAR NOT NULL UNIQUE PRIMARY KEY, value LONGVARCHAR)");

    private static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static byte[] local(String value, boolean utf16) {
        return concat(new byte[]{(byte) (utf16 ? 0 : 1)}, value.getBytes(utf16 ? StandardCharsets.UTF_16LE : StandardCharsets.ISO_8859_1));
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static void sqlite(Path file, List<String> schema, SqlWork work) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            try (var statement = connection.createStatement()) {
                for (var sql : schema) {
                    statement.execute(sql);
                }
            }
            insert(connection, "INSERT INTO meta VALUES (?, ?)", "version", 1);
            insert(connection, "INSERT INTO meta VALUES (?, ?)", "last_compatible_version", 1);
            work.run(connection);
        }
    }

    private static void insert(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (var i = 0; i < values.length; i++) {
                statement.setObject(i + 1, values[i]);
            }
            statement.executeUpdate();
        }
    }

    @FunctionalInterface
    private interface SqlWork {
        void run(Connection connection) throws Exception;
    }

    @Nested
    @DisplayName("on LevelDB")
    class LevelDb {
        @Test
        @DisplayName("an origin's localStorage items read in both encodings, other origins and bookkeeping left out")
        void localStorage(@TempDir Path directory) throws IOException {
            new LevelDbWriter()
                    .put(ascii("VERSION"), ascii("1"))
                    .put(ascii("META:https://example.com"), new byte[]{8, 1})
                    .put(concat(ascii("_https://example.com"), new byte[]{0}, local("theme", false)), local("dark", false))
                    .put(concat(ascii("_https://example.com"), new byte[]{0}, local("greeting", true)), local(NI_HAO, true))
                    .put(concat(ascii("_https://example.com.evil"), new byte[]{0}, local("theme", false)), local("light", false))
                    .put(concat(ascii("_https://other.com"), new byte[]{0}, local("x", false)), local("y", false))
                    .put(concat(ascii("_https://example.com^0https://top.com"), new byte[]{0}, local("partitioned", false)), local("z", false))
                    .writeLog(directory, 3);
            assertEquals(Map.of("theme", "dark", "greeting", NI_HAO), LevelDbDomStorage.localStorage(directory, ORIGIN));
            assertEquals(List.of(StorageOrigin.parse("https://example.com"), StorageOrigin.parse("https://example.com.evil"),
                    StorageOrigin.parse("https://other.com")), LevelDbDomStorage.localStorageOrigins(directory));
        }

        @Test
        @DisplayName("an origin's sessionStorage reads per session, from the maps the sessions name")
        void sessionStorage(@TempDir Path directory) throws IOException {
            new LevelDbWriter()
                    .put(ascii("version"), ascii("1"))
                    .put(ascii("next-map-id"), ascii("3"))
                    .put(ascii("namespace-" + FIRST_SESSION + "-https://example.com/"), ascii("1"))
                    .put(ascii("namespace-" + SECOND_SESSION + "-https://example.com/"), ascii("2"))
                    .put(ascii("namespace-" + SECOND_SESSION + "-https://other.com/"), ascii("0"))
                    .put(concat(ascii("map-1-"), STEP.getBytes(StandardCharsets.UTF_8)), "1".getBytes(StandardCharsets.UTF_16LE))
                    .put(concat(ascii("map-2-"), STEP.getBytes(StandardCharsets.UTF_8)), NI_HAO.getBytes(StandardCharsets.UTF_16LE))
                    .put(concat(ascii("map-0-"), "secret".getBytes(StandardCharsets.UTF_8)), "no".getBytes(StandardCharsets.UTF_16LE))
                    .writeLog(directory, 3);
            assertEquals(List.of(new SessionStorageArea(FIRST_SESSION, Map.of(STEP, "1")), new SessionStorageArea(SECOND_SESSION, Map.of(STEP, NI_HAO))),
                    LevelDbDomStorage.sessionStorage(directory, ORIGIN));
        }
    }

    @Nested
    @DisplayName("on SQLite")
    class Sqlite {
        @Test
        @DisplayName("an origin's localStorage items read in both encodings and every compression, other storage keys left out")
        void localStorage(@TempDir Path directory) throws Exception {
            var file = directory.resolve("LocalStorage");
            var long1 = "zstd ".repeat(200);
            var long2 = "snappy ".repeat(200);
            sqlite(file, LOCAL_STORAGE_SCHEMA, connection -> {
                insert(connection, "INSERT INTO maps(storage_key, last_accessed, last_modified, total_size) VALUES (?, 1, 1, 0)", ascii("https://example.com/"));
                insert(connection, "INSERT INTO maps(storage_key, last_accessed, last_modified, total_size) VALUES (?, 1, 1, 0)", ascii("https://example.com.evil/"));
                insert(connection, "INSERT INTO maps(storage_key, last_accessed, last_modified, total_size) VALUES (?, 1, 1, 0)", ascii("https://example.com/^0https://top.com"));
                insert(connection, "INSERT INTO maps(storage_key, last_accessed, last_modified, total_size) VALUES (?, 1, 1, 0)", ascii("https://other.com/"));
                var entry = "INSERT INTO map_entries VALUES (?, ?, ?, ?)";
                insert(connection, entry, 1, 0, local("theme", false), local("dark", false));
                insert(connection, entry, 1, 0, local("greeting", true), local(NI_HAO, true));
                insert(connection, entry, 1, 1, local("zstd", false), com.github.luben.zstd.Zstd.compress(local(long1, true), -4));
                insert(connection, entry, 1, 2, local("snappy", false), org.xerial.snappy.Snappy.compress(local(long2, false)));
                insert(connection, entry, 2, 0, local("theme", false), local("light", false));
                insert(connection, entry, 3, 0, local("partitioned", false), local("z", false));
                insert(connection, entry, 4, 0, local("x", false), local("y", false));
            });
            assertEquals(Map.of("theme", "dark", "greeting", NI_HAO, "zstd", long1, "snappy", long2), SqliteDomStorage.localStorage(file, ORIGIN));
            assertEquals(List.of(StorageOrigin.parse("https://example.com"), StorageOrigin.parse("https://example.com.evil"),
                    StorageOrigin.parse("https://other.com")), SqliteDomStorage.localStorageOrigins(file));
        }

        @Test
        @DisplayName("an origin's sessionStorage reads per session, sessions cloned from one another sharing a map")
        void sessionStorage(@TempDir Path directory) throws Exception {
            var file = directory.resolve("SessionStorage");
            sqlite(file, SESSION_STORAGE_SCHEMA, connection -> {
                var session = "INSERT INTO session_metadata VALUES (?, ?, ?)";
                insert(connection, session, FIRST_SESSION, ascii("https://example.com/"), 0);
                insert(connection, session, SECOND_SESSION, ascii("https://example.com/"), 0);
                insert(connection, session, THIRD_SESSION, ascii("https://example.com/"), 1);
                insert(connection, session, SECOND_SESSION, ascii("https://other.com/"), 2);
                var entry = "INSERT INTO map_entries VALUES (?, ?, ?, ?)";
                insert(connection, entry, 0, 0, STEP.getBytes(StandardCharsets.UTF_8), "1".getBytes(StandardCharsets.UTF_16LE));
                insert(connection, entry, 1, 0, STEP.getBytes(StandardCharsets.UTF_8), NI_HAO.getBytes(StandardCharsets.UTF_16LE));
                insert(connection, entry, 2, 0, "secret".getBytes(StandardCharsets.UTF_8), "no".getBytes(StandardCharsets.UTF_16LE));
            });
            // Sessions come in session id order, as on LevelDB
            assertEquals(List.of(new SessionStorageArea(FIRST_SESSION, Map.of(STEP, "1")), new SessionStorageArea(THIRD_SESSION, Map.of(STEP, NI_HAO)),
                    new SessionStorageArea(SECOND_SESSION, Map.of(STEP, "1"))), SqliteDomStorage.sessionStorage(file, ORIGIN));
        }

        @Test
        @DisplayName("a schema newer than the reader knows is unsupported")
        void newerSchema(@TempDir Path directory) throws Exception {
            var file = directory.resolve("LocalStorage");
            sqlite(file, LOCAL_STORAGE_SCHEMA, connection -> {
                try (var statement = connection.createStatement()) {
                    statement.execute("UPDATE meta SET value = '2' WHERE key = 'last_compatible_version'");
                }
            });
            var failure = assertThrows(ViridianException.class, () -> SqliteDomStorage.localStorage(file, ORIGIN));
            assertEquals(ViridianException.Reason.UNSUPPORTED, failure.reason());
        }
    }

    @Test
    @DisplayName("a profile reads the SQLite database when both backends hold its storage, and either when asked")
    void profileBackend(@TempDir Path profileDirectory) throws Exception {
        new LevelDbWriter()
                .put(concat(ascii("_https://example.com"), new byte[]{0}, local("from", false)), local("leveldb", false))
                .writeLog(profileDirectory.resolve("Local Storage").resolve("leveldb"), 3);
        var profile = ChromiumProfile.open(profileDirectory);
        assertEquals(Optional.of(StorageBackend.LEVELDB), profile.localStorageBackend());
        assertEquals(Optional.empty(), profile.sessionStorageBackend());
        assertEquals(Map.of("from", "leveldb"), profile.localStorage(ORIGIN));

        sqlite(profileDirectory.resolve("LocalStorage"), LOCAL_STORAGE_SCHEMA, connection -> {
            insert(connection, "INSERT INTO maps(storage_key, last_accessed, last_modified, total_size) VALUES (?, 1, 1, 0)", ascii("https://example.com/"));
            insert(connection, "INSERT INTO map_entries VALUES (?, ?, ?, ?)", 1, 0, local("from", false), local("sqlite", false));
        });
        assertEquals(Optional.of(StorageBackend.SQLITE), profile.localStorageBackend());
        assertEquals(Map.of("from", "sqlite"), profile.localStorage(ORIGIN));
        assertEquals(Map.of("from", "leveldb"), profile.localStorage(ORIGIN, StorageBackend.LEVELDB));
        assertEquals(List.of(ORIGIN), profile.localStorageOrigins());
        Files.delete(profileDirectory.resolve("LocalStorage"));
        assertEquals(Optional.of(StorageBackend.LEVELDB), profile.localStorageBackend());
    }
}
