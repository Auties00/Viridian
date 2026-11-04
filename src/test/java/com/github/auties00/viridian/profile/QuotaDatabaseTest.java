package com.github.auties00.viridian.profile;

import com.github.auties00.viridian.ViridianException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// The quota database, checked against SQLite itself: what Chromium's schema holds is read, and a copy with new buckets
/// keeps every row and numbers them past every id the database gave.
class QuotaDatabaseTest {
    private static void chromium(Path file) throws SQLException {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE meta(key LONGVARCHAR NOT NULL UNIQUE PRIMARY KEY, value LONGVARCHAR)");
            statement.execute("INSERT INTO meta VALUES ('mmap_status', '-1'), ('version', '11'), ('last_compatible_version', '11'), ('IsBucketsBootstrapped', '1')");
            statement.execute("CREATE TABLE buckets(id INTEGER PRIMARY KEY AUTOINCREMENT, storage_key TEXT NOT NULL, host TEXT NOT NULL, name TEXT NOT NULL,"
                    + " use_count INTEGER NOT NULL, last_accessed INTEGER NOT NULL, last_modified INTEGER NOT NULL, expiration INTEGER NOT NULL,"
                    + " quota INTEGER NOT NULL, persistent INTEGER NOT NULL, durability INTEGER NOT NULL) STRICT");
            statement.execute("CREATE UNIQUE INDEX buckets_by_storage_key ON buckets(storage_key, name)");
            statement.execute("INSERT INTO buckets VALUES (1, 'https://a.test/', 'a.test', '_default', 3, 1, 2, 0, 0, 0, 0)");
            statement.execute("INSERT INTO buckets VALUES (5, 'https://a.test/', 'a.test', 'drafts', 0, 1, 2, 0, 0, 1, 0)");
            // A bucket deleted after the largest id was given, which a new bucket must not take again
            statement.execute("INSERT INTO buckets VALUES (9, 'https://b.test/', 'b.test', '_default', 0, 1, 2, 0, 0, 0, 0)");
            statement.execute("DELETE FROM buckets WHERE id = 9");
        }
    }

    private static List<String> rows(Path file, String query) throws SQLException {
        var rows = new ArrayList<String>();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file); var result = connection.createStatement().executeQuery(query)) {
            while (result.next()) {
                var row = new StringBuilder();
                for (var i = 1; i <= result.getMetaData().getColumnCount(); i++) {
                    row.append(i == 1 ? "" : "|").append(result.getString(i));
                }
                rows.add(row.toString());
            }
        }
        return rows;
    }

    @Test
    @DisplayName("the buckets of Chromium's database read by id")
    void reads(@TempDir Path directory) throws Exception {
        var file = directory.resolve("QuotaManager");
        chromium(file);
        assertEquals(List.of(new QuotaDatabase.Bucket(1, "https://a.test/", "_default"), new QuotaDatabase.Bucket(5, "https://a.test/", "drafts")),
                QuotaDatabase.read(file));
        assertEquals(List.of(), QuotaDatabase.read(directory.resolve("missing")));
    }

    @Test
    @DisplayName("a copy keeps every row and numbers new buckets past the largest id ever given")
    void registers(@TempDir Path directory) throws Exception {
        var existing = directory.resolve("QuotaManager");
        chromium(existing);
        var target = directory.resolve("copy");
        var ids = QuotaDatabase.register(existing, target, List.of(
                new QuotaDatabase.Registration("https://a.test/^0https://c.test", "a.test", "_default"),
                new QuotaDatabase.Registration("https://c.test/", "c.test", "inbox")));
        assertEquals(List.of(10L, 11L), ids);
        assertEquals(List.of("ok"), rows(target, "PRAGMA integrity_check"));
        assertEquals(List.of("1|https://a.test/|_default|3", "5|https://a.test/|drafts|0", "10|https://a.test/^0https://c.test|_default|0",
                "11|https://c.test/|inbox|0"), rows(target, "SELECT id, storage_key, name, use_count FROM buckets ORDER BY id"));
        assertEquals(List.of("buckets|11"), rows(target, "SELECT name, seq FROM sqlite_sequence"));
        assertEquals(rows(existing, "SELECT key, value FROM meta"), rows(target, "SELECT key, value FROM meta"));
        assertEquals(List.of("0|0|0|0"), rows(target, "SELECT DISTINCT expiration, quota, persistent, durability FROM buckets WHERE id > 9"));
        assertEquals(List.of("1"), rows(target, "SELECT count(*) FROM buckets WHERE id = 10 AND last_accessed > 13400000000000000"));
        assertThrows(ViridianException.class, () -> QuotaDatabase.register(existing, directory.resolve("again"),
                List.of(new QuotaDatabase.Registration("https://a.test/", "a.test", "drafts"))));
    }

    @Test
    @DisplayName("a fresh database has Chromium's schema and version, and the buckets registered from 1")
    void fresh(@TempDir Path directory) throws IOException, SQLException {
        var target = directory.resolve("QuotaManager");
        assertEquals(List.of(1L), QuotaDatabase.register(directory.resolve("missing"), target,
                List.of(new QuotaDatabase.Registration("https://a.test/", "a.test", "drafts"))));
        assertEquals(List.of("ok"), rows(target, "PRAGMA integrity_check"));
        assertEquals(List.of("last_compatible_version|11", "mmap_status|-1", "version|11"), rows(target, "SELECT key, value FROM meta ORDER BY key"));
        assertEquals(List.of(new QuotaDatabase.Bucket(1, "https://a.test/", "drafts")), QuotaDatabase.read(target));
        assertEquals(List.of("buckets_by_expiration", "buckets_by_host", "buckets_by_last_accessed", "buckets_by_last_modified", "buckets_by_storage_key"),
                rows(target, "SELECT name FROM sqlite_schema WHERE type = 'index' AND name LIKE 'buckets%' ORDER BY name"));
    }
}
