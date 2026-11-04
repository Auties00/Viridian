package com.github.auties00.viridian.domstorage;

import com.github.auties00.viridian.StorageKey;
import com.github.auties00.viridian.ViridianException;
import com.github.auties00.viridian.sqlite.SqliteDatabase;
import com.github.auties00.viridian.sqlite.SqliteTable;
import com.github.auties00.viridian.sqlite.SqliteWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Writes Chromium's Local Storage on its SQLite backend: the `LocalStorage` file of a profile, as Chromium itself
/// creates and fills it.
///
/// The file has the schema [SqliteDomStorage] reads, created by the statements Chromium runs, in its order, with the
/// `meta` rows of `sql::MetaTable`: `mmap_status` -1, `version` 1 and `last_compatible_version` 1, all as text. A
/// storage key is its serialization, a first-party one its origin with a trailing slash; keys and values start with
/// the format byte Blink picks, 1 for
/// Latin-1 when every character fits in a byte and 0 for little-endian UTF-16 otherwise, and values are stored
/// uncompressed, compression type 0, which Chromium reads like any other. A map's `last_accessed` and `last_modified`
/// are the time it was written, in microseconds since 1601-01-01 as Chromium's `base::Time` counts, and its
/// `total_size` the bytes of its keys and values, format bytes included, as Chromium counts its quota.
public final class SqliteDomStorageWriter {
    // The backend's schema version, which the meta table records
    private static final int SCHEMA_VERSION = 1;
    // The page size Chromium's sql::Database gives a new database
    private static final int PAGE_SIZE = 4096;
    // The microseconds from base::Time's epoch, 1601-01-01, to the Unix epoch
    private static final long WINDOWS_EPOCH_OFFSET = 11_644_473_600_000_000L;
    // Chromium's statements, from LocalStorageSqlite, MapEntriesTable and sql::MetaTable
    private static final List<String> SCHEMA = List.of(
            "CREATE TABLE maps(row_id INTEGER PRIMARY KEY AUTOINCREMENT,storage_key BLOB NOT NULL,last_accessed INTEGER,last_modified INTEGER,total_size INTEGER)",
            "CREATE UNIQUE INDEX maps_by_storage_key ON maps(storage_key)",
            "CREATE TABLE map_entries(map_id INTEGER NOT NULL,value_compression_type INTEGER NOT NULL,key BLOB NOT NULL,value BLOB NOT NULL,PRIMARY KEY(map_id, key)) WITHOUT ROWID;",
            "CREATE TABLE meta(key LONGVARCHAR NOT NULL UNIQUE PRIMARY KEY, value LONGVARCHAR)");
    private static final int UNCOMPRESSED = 0;

    private SqliteDomStorageWriter() {
    }

    /// Writes `target` as a copy of the Local Storage database `existing` in which the `localStorage` items of each
    /// storage key of `replacements` are exactly those it maps to.
    ///
    /// Every other storage key's map is copied with its usage metadata and its entries unchanged, compressed values as
    /// they are, and so are the `meta` rows and `sqlite_sequence`. A replaced key's map keeps its `row_id` if it had
    /// one, else takes the next one as `AUTOINCREMENT` gives it; with no items, the key has no map at all, as Chromium
    /// leaves an emptied area. When `existing` does not exist, `target` is a fresh database holding the replaced keys'
    /// items only.
    ///
    /// The existing database is only read, its write-ahead log included; the target is written in rollback-journal
    /// mode, which Chromium switches to write-ahead logging when it opens it.
    ///
    /// @param existing     the Local Storage database to copy, which need not exist
    /// @param target       the file to write, which must not exist
    /// @param replacements the items of each storage key replaced, by key
    /// @throws ViridianException if `existing` has a schema version other than the one Chromium writes, or rows its
    ///                           schema does not allow
    /// @throws IOException       if `existing` cannot be read or `target` cannot be written; `target` is then not
    ///                           left behind
    public static void replaceLocalStorage(Path existing, Path target, Map<StorageKey, Map<String, String>> replacements) throws IOException {
        Objects.requireNonNull(existing, "existing");
        Objects.requireNonNull(target, "target");
        var copy = Files.exists(existing) ? Contents.read(existing, replacements.keySet()) : null;
        try (var writer = SqliteWriter.create(target, PAGE_SIZE)) {
            SCHEMA.forEach(writer::execute);
            try {
                if (copy != null) {
                    for (var row : copy.sequence) {
                        writer.insert("sqlite_sequence", row);
                    }
                    for (var row : copy.meta) {
                        writer.insert("meta", row);
                    }
                } else {
                    writer.insert("meta", List.of("mmap_status", -1L));
                    writer.insert("meta", List.of("version", (long) SCHEMA_VERSION));
                    writer.insert("meta", List.of("last_compatible_version", (long) SCHEMA_VERSION));
                }
                var now = ChronoUnit.MICROS.between(Instant.EPOCH, Instant.now()) + WINDOWS_EPOCH_OFFSET;
                if (copy != null) {
                    for (var row : copy.maps) {
                        writer.insert("maps", row);
                    }
                    for (var row : copy.entries) {
                        writer.insert("map_entries", row);
                    }
                }
                for (var replacement : replacements.entrySet()) {
                    if (replacement.getValue().isEmpty()) {
                        continue;
                    }
                    var entries = new ArrayList<byte[][]>(replacement.getValue().size());
                    var totalSize = 0L;
                    for (var item : replacement.getValue().entrySet()) {
                        var key = DomStorageText.encodeLocal(Objects.requireNonNull(item.getKey(), "key"));
                        var value = DomStorageText.encodeLocal(Objects.requireNonNull(item.getValue(), "value"));
                        entries.add(new byte[][]{key, value});
                        totalSize += key.length + value.length;
                    }
                    var storageKey = replacement.getKey().serialize().getBytes(StandardCharsets.ISO_8859_1);
                    var previous = copy == null ? null : copy.replacedRowids.get(replacement.getKey());
                    var mapId = writer.insert("maps", Arrays.asList(previous, storageKey, now, now, totalSize));
                    for (var entry : entries) {
                        writer.insert("map_entries", List.of(mapId, UNCOMPRESSED, entry[0], entry[1]));
                    }
                }
            } catch (IllegalArgumentException exception) {
                throw new ViridianException(ViridianException.Reason.CORRUPTED, "The Local Storage database " + existing.getFileName()
                        + " has rows its schema does not allow", exception);
            }
            writer.finish();
        }
    }

    /// What the copy keeps of an existing Local Storage database, every row as its values in column order.
    private static final class Contents {
        private final List<List<Object>> sequence = new ArrayList<>();
        private final List<List<Object>> meta = new ArrayList<>();
        private final List<List<Object>> maps = new ArrayList<>();
        private final List<List<Object>> entries = new ArrayList<>();
        // The row_id of the map of each replaced origin that has one
        private final Map<StorageKey, Long> replacedRowids = new HashMap<>();

        /// {@return the rows of `file` to copy, without the maps of `keys` and their entries}
        ///
        /// @throws ViridianException if the schema version is not the one Chromium writes, or a table is missing
        private static Contents read(Path file, Set<StorageKey> keys) throws IOException {
            try (var database = SqliteDatabase.open(file)) {
                var contents = new Contents();
                var meta = table(database, "meta", file);
                var version = 0L;
                var compatible = 0L;
                for (var row : rows(meta, "key", "value")) {
                    contents.meta.add(row);
                    if ("version".equals(row.getFirst())) {
                        version = SqliteDomStorage.version(row.get(1));
                    } else if ("last_compatible_version".equals(row.getFirst())) {
                        compatible = SqliteDomStorage.version(row.get(1));
                    }
                }
                if (version != SCHEMA_VERSION || compatible > SCHEMA_VERSION) {
                    throw new ViridianException(ViridianException.Reason.UNSUPPORTED, "The Local Storage database " + file.getFileName()
                            + " has schema version " + version + ", compatible with " + compatible + "; only version " + SCHEMA_VERSION + " is written");
                }
                var sequence = database.findTable("sqlite_sequence");
                if (sequence.isPresent()) {
                    contents.sequence.addAll(rows(sequence.get(), "name", "seq"));
                }
                var originMaps = new HashSet<Long>();
                for (var row : rows(table(database, "maps", file), "row_id", "storage_key", "last_accessed", "last_modified", "total_size")) {
                    var storageKey = switch (row.get(1)) {
                        case byte[] bytes -> new String(bytes, StandardCharsets.ISO_8859_1);
                        case String text -> text;
                        case null, default -> "";
                    };
                    var parsed = DomStorageText.storageKey(storageKey);
                    var replaced = parsed != null && keys.contains(parsed) ? parsed : null;
                    if (replaced != null) {
                        originMaps.add((Long) row.getFirst());
                        contents.replacedRowids.putIfAbsent(replaced, (Long) row.getFirst());
                    } else {
                        contents.maps.add(row);
                    }
                }
                for (var row : rows(table(database, "map_entries", file), "map_id", "value_compression_type", "key", "value")) {
                    if (!(row.getFirst() instanceof Long mapId) || !originMaps.contains(mapId)) {
                        contents.entries.add(row);
                    }
                }
                return contents;
            }
        }

        /// {@return every row of `table`, in its b-tree order, as the values of `columns`}
        private static List<List<Object>> rows(SqliteTable table, String... columns) throws IOException {
            var indexes = new int[columns.length];
            for (var i = 0; i < columns.length; i++) {
                indexes[i] = table.columnIndex(columns[i]);
            }
            var rows = new ArrayList<List<Object>>();
            try (var cursor = table.scan()) {
                while (cursor.next()) {
                    var row = cursor.row();
                    var values = new ArrayList<>(columns.length);
                    for (var index : indexes) {
                        values.add(row.get(index));
                    }
                    rows.add(values);
                }
            }
            return rows;
        }

        private static SqliteTable table(SqliteDatabase database, String name, Path file) throws IOException {
            return database.findTable(name).orElseThrow(() -> new ViridianException(ViridianException.Reason.CORRUPTED,
                    "The Local Storage database " + file.getFileName() + " has no " + name + " table"));
        }
    }
}
