package com.github.auties00.viridian.domstorage;

import com.github.auties00.viridian.SessionStorageArea;
import com.github.auties00.viridian.StorageKey;
import com.github.auties00.viridian.ViridianException;
import com.github.auties00.viridian.compress.Snappy;
import com.github.auties00.viridian.compress.Zstd;
import com.github.auties00.viridian.sqlite.SqliteDatabase;
import com.github.auties00.viridian.sqlite.SqliteRow;
import com.github.auties00.viridian.sqlite.SqliteTable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/// Reads Chromium's DOM Storage on its SQLite backend: `localStorage` and `sessionStorage`, each one SQLite file of
/// the profile, `LocalStorage` and `SessionStorage`.
///
/// The schema this reads, version 1 of the backend's own versioning:
///
/// ```text
/// maps (row_id INTEGER PRIMARY KEY, storage_key BLOB, last_accessed, last_modified, total_size)    LocalStorage only
/// session_metadata (session_id TEXT, storage_key BLOB, map_id) WITHOUT ROWID                     SessionStorage only
/// map_entries (map_id, value_compression_type, key BLOB, value BLOB) WITHOUT ROWID, keyed by (map_id, key)
/// ```
///
/// Local Storage keeps one map per storage key, numbered by its row; its keys and values start with a format byte, as
/// on LevelDB. Session Storage points each session and storage key at a map, which the sessions cloned from one
/// another share; its keys are UTF-8 and its values little-endian UTF-16, as on LevelDB. A storage key is its
/// serialization, such as `https://example.com/` or `https://example.com/^0https://news.example`. A value of 350 bytes
/// or more may be compressed: its
/// compression type is 0 for none, 1 for zstd, 2 for Snappy.
public final class SqliteDomStorage {
    // The backend's schema versions this reader knows
    private static final int SCHEMA_VERSION = 1;
    private static final int UNCOMPRESSED = 0;
    private static final int ZSTD = 1;
    private static final int SNAPPY = 2;
    // The longest array a JVM allocates; a value that claims more is damage
    private static final int MAX_VALUE_LENGTH = Integer.MAX_VALUE - 16;

    private SqliteDomStorage() {
    }

    /// {@return the `localStorage` items of `key`, by key, from the Local Storage database `file`}
    ///
    /// @throws ViridianException if the schema is newer than this reader knows, or an item does not decode
    /// @throws IOException       if the database cannot be read
    public static Map<String, String> localStorage(Path file, StorageKey key) throws IOException {
        try (var database = open(file)) {
            var maps = table(database, "maps", file);
            var items = new TreeMap<String, String>();
            try (var cursor = maps.scan()) {
                while (cursor.next()) {
                    var row = cursor.row();
                    if (key.equals(DomStorageText.storageKey(storageKey(row, maps)))) {
                        var mapId = row.getLong(maps.columnIndex("row_id"));
                        for (var entry : entries(database, file, mapId).entrySet()) {
                            items.put(DomStorageText.decodeLocal(entry.getKey()), DomStorageText.decodeLocal(entry.getValue()));
                        }
                    }
                }
            }
            return items;
        }
    }

    /// {@return the storage keys with `localStorage` items in the Local Storage database `file`}
    ///
    /// @throws ViridianException if the schema is newer than this reader knows
    /// @throws IOException       if the database cannot be read
    public static List<StorageKey> localStorageKeys(Path file) throws IOException {
        try (var database = open(file)) {
            var maps = table(database, "maps", file);
            var storageKeys = new ArrayList<String>();
            try (var cursor = maps.scan()) {
                while (cursor.next()) {
                    storageKeys.add(storageKey(cursor.row(), maps));
                }
            }
            return DomStorageText.storageKeys(storageKeys);
        }
    }

    /// {@return the storage keys with `sessionStorage` in the Session Storage database `file`}
    ///
    /// @throws ViridianException if the schema is newer than this reader knows
    /// @throws IOException       if the database cannot be read
    public static List<StorageKey> sessionStorageKeys(Path file) throws IOException {
        try (var database = open(file)) {
            var sessions = table(database, "session_metadata", file);
            var storageKeys = new ArrayList<String>();
            try (var cursor = sessions.scan()) {
                while (cursor.next()) {
                    storageKeys.add(storageKey(cursor.row(), sessions));
                }
            }
            return DomStorageText.storageKeys(storageKeys);
        }
    }

    /// {@return the `sessionStorage` areas of `key`, one per session, from the Session Storage database `file`}
    ///
    /// @throws ViridianException if the schema is newer than this reader knows, or a value does not decompress
    /// @throws IOException       if the database cannot be read
    public static List<SessionStorageArea> sessionStorage(Path file, StorageKey key) throws IOException {
        try (var database = open(file)) {
            var sessions = table(database, "session_metadata", file);
            var mapsBySession = new LinkedHashMap<String, Long>();
            try (var cursor = sessions.scan()) {
                while (cursor.next()) {
                    var row = cursor.row();
                    if (key.equals(DomStorageText.storageKey(storageKey(row, sessions)))) {
                        mapsBySession.put(row.getText(sessions.columnIndex("session_id")), row.getLong(sessions.columnIndex("map_id")));
                    }
                }
            }
            var itemsByMap = new HashMap<Long, Map<String, String>>();
            var areas = new ArrayList<SessionStorageArea>();
            for (var session : mapsBySession.entrySet()) {
                var items = itemsByMap.get(session.getValue());
                if (items == null) {
                    items = new TreeMap<>();
                    for (var entry : entries(database, file, session.getValue()).entrySet()) {
                        items.put(new String(entry.getKey(), StandardCharsets.UTF_8), DomStorageText.utf16(entry.getValue(), 0));
                    }
                    itemsByMap.put(session.getValue(), items);
                }
                areas.add(new SessionStorageArea(session.getKey(), items));
            }
            return areas;
        }
    }

    /// {@return the keys and decompressed values of the map `mapId`, in key order}
    private static Map<byte[], byte[]> entries(SqliteDatabase database, Path file, long mapId) throws IOException {
        var table = table(database, "map_entries", file);
        var entries = new LinkedHashMap<byte[], byte[]>();
        try (var cursor = table.seek(List.of(mapId))) {
            while (cursor.next() && cursor.row().get(table.columnIndex("map_id")) instanceof Long id && id == mapId) {
                var row = cursor.row();
                var stored = row.getBlob(table.columnIndex("value"));
                var value = switch ((int) row.getLong(table.columnIndex("value_compression_type"))) {
                    case UNCOMPRESSED -> stored;
                    case ZSTD -> Zstd.decompress(stored, 0, stored.length, MAX_VALUE_LENGTH);
                    case SNAPPY -> Snappy.decompress(stored, 0, stored.length, MAX_VALUE_LENGTH);
                    default -> throw new ViridianException(ViridianException.Reason.UNSUPPORTED, "A DOM Storage value of "
                            + file.getFileName() + " has compression type " + row.getLong(table.columnIndex("value_compression_type")));
                };
                entries.put(row.getBlob(table.columnIndex("key")), value);
            }
        }
        return entries;
    }

    /// {@return the storage key of a `maps` or `session_metadata` row, which Chromium stores as a BLOB of its
    /// serialization}
    private static String storageKey(SqliteRow row, SqliteTable table) {
        return switch (row.get(table.columnIndex("storage_key"))) {
            case byte[] bytes -> new String(bytes, StandardCharsets.ISO_8859_1);
            case String text -> text;
            case null, default -> "";
        };
    }

    /// Opens `file`, failing unless its schema is one this reader knows, as Chromium's `meta` table records it.
    private static SqliteDatabase open(Path file) throws IOException {
        var database = SqliteDatabase.open(file);
        try {
            var meta = database.findTable("meta");
            if (meta.isPresent()) {
                try (var cursor = meta.get().scan()) {
                    while (cursor.next()) {
                        var row = cursor.row();
                        if ("last_compatible_version".equals(row.get(0)) && version(row.get(1)) > SCHEMA_VERSION) {
                            throw new ViridianException(ViridianException.Reason.UNSUPPORTED, "The DOM Storage database " + file.getFileName()
                                    + " has schema version " + row.get(1) + ", newer than this reader knows");
                        }
                    }
                }
            }
            return database;
        } catch (IOException | RuntimeException exception) {
            database.close();
            throw exception;
        }
    }

    static long version(Object value) {
        return switch (value) {
            case Long number -> number;
            case String text -> {
                try {
                    yield Long.parseLong(text.strip());
                } catch (NumberFormatException _) {
                    yield 0;
                }
            }
            case null, default -> 0;
        };
    }

    private static SqliteTable table(SqliteDatabase database, String name, Path file) throws IOException {
        return database.findTable(name).orElseThrow(() -> new ViridianException(ViridianException.Reason.CORRUPTED,
                "The DOM Storage database " + file.getFileName() + " has no " + name + " table"));
    }
}
