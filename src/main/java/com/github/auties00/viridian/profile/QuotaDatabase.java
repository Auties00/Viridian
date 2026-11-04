package com.github.auties00.viridian.profile;

import com.github.auties00.viridian.ViridianException;
import com.github.auties00.viridian.sqlite.SqliteDatabase;
import com.github.auties00.viridian.sqlite.SqliteTable;
import com.github.auties00.viridian.sqlite.SqliteWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/// The quota database of a profile, `WebStorage/QuotaManager`, which numbers the storage buckets: the bucket of id `n`
/// keeps its data in `WebStorage/<n>`, all but an origin's first-party default bucket among them.
///
/// The database is version 11 of Chromium's own versioning, with one `STRICT` table:
///
/// ```text
/// buckets (id INTEGER PRIMARY KEY AUTOINCREMENT, storage_key TEXT, host TEXT, name TEXT, use_count INTEGER,
///          last_accessed INTEGER, last_modified INTEGER, expiration INTEGER, quota INTEGER, persistent INTEGER,
///          durability INTEGER), unique by (storage_key, name)
/// ```
///
/// A bucket's `storage_key` is its key's serialization, `host` the host of its origin, and `name` `_default` for a
/// key's default bucket. Times are microseconds since 1601-01-01, as `base::Time` counts them. Chromium deletes a
/// bucket that is neither persistent nor used for 400 days, so a bucket registered here is used now; and it deletes the
/// whole `WebStorage` directory when it cannot open the database, or its version is newer than it writes, so this
/// writer writes only the version Chromium 157 writes.
///
/// @spec https://chromium.googlesource.com/chromium/src/+/main/storage/browser/quota/quota_database.cc Chromium quota database
public final class QuotaDatabase {
    /// Where a profile keeps the database.
    public static final Path FILE = Path.of("WebStorage", "QuotaManager");
    /// Where a profile keeps its buckets' data, each in a directory named by its id.
    public static final Path BUCKETS = Path.of("WebStorage");

    private static final long SCHEMA_VERSION = 11;
    // kMmapSuccess: the file was found sound, so SQLite may map it
    private static final long MMAP_SUCCESS = -1;
    private static final int PAGE_SIZE = 4096;
    private static final List<String> COLUMNS = List.of("id", "storage_key", "host", "name", "use_count", "last_accessed",
            "last_modified", "expiration", "quota", "persistent", "durability");
    // Chromium's statements, from sql::MetaTable and QuotaDatabase::CreateSchema, in their order
    private static final List<String> SCHEMA = List.of(
            "CREATE TABLE meta(key LONGVARCHAR NOT NULL UNIQUE PRIMARY KEY, value LONGVARCHAR)",
            "CREATE TABLE buckets(id INTEGER PRIMARY KEY AUTOINCREMENT, storage_key TEXT NOT NULL, host TEXT NOT NULL, name TEXT NOT NULL,"
                    + " use_count INTEGER NOT NULL, last_accessed INTEGER NOT NULL, last_modified INTEGER NOT NULL, expiration INTEGER NOT NULL,"
                    + " quota INTEGER NOT NULL, persistent INTEGER NOT NULL, durability INTEGER NOT NULL) STRICT",
            "CREATE UNIQUE INDEX buckets_by_storage_key ON buckets(storage_key, name)",
            "CREATE INDEX buckets_by_host ON buckets(host)",
            "CREATE INDEX buckets_by_last_accessed ON buckets(last_accessed)",
            "CREATE INDEX buckets_by_last_modified ON buckets(last_modified)",
            "CREATE INDEX buckets_by_expiration ON buckets(expiration)");
    // base::Time's epoch, 1601-01-01
    private static final Instant WINDOWS_EPOCH = Instant.parse("1601-01-01T00:00:00Z");

    private QuotaDatabase() {
    }

    /// A bucket the database records.
    ///
    /// @param id         the bucket's id, which names the directory of its data
    /// @param storageKey the serialization of its storage key
    /// @param name       its name
    public record Bucket(long id, String storageKey, String name) {
        /// Creates a bucket.
        public Bucket {
            Objects.requireNonNull(storageKey, "storageKey");
            Objects.requireNonNull(name, "name");
        }
    }

    /// A bucket to record.
    ///
    /// @param storageKey the serialization of its storage key
    /// @param host       the host of its storage key's origin
    /// @param name       its name
    public record Registration(String storageKey, String host, String name) {
        /// Creates a registration.
        public Registration {
            Objects.requireNonNull(storageKey, "storageKey");
            Objects.requireNonNull(host, "host");
            Objects.requireNonNull(name, "name");
        }
    }

    /// {@return the buckets the database `file` records, by id; none if there is no such file}
    ///
    /// @throws ViridianException if the database has no `buckets` table, or a version newer than this reader knows
    /// @throws IOException       if the file cannot be read
    public static List<Bucket> read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try (var database = SqliteDatabase.open(file)) {
            checkVersion(database, file);
            var buckets = table(database, "buckets", file);
            var result = new ArrayList<Bucket>();
            try (var cursor = buckets.scan()) {
                while (cursor.next()) {
                    var row = cursor.row();
                    result.add(new Bucket(row.rowid(), String.valueOf(row.get(buckets.columnIndex("storage_key"))),
                            String.valueOf(row.get(buckets.columnIndex("name")))));
                }
            }
            return List.copyOf(result);
        }
    }

    /// Writes `target` as a copy of the database `existing`, or of a fresh one if there is none, that also records each
    /// of `registrations`, numbered past every id the database ever gave.
    ///
    /// Every row of `existing` is copied as it is, and so are its `meta` rows and its `sqlite_sequence`. A fresh
    /// database has the `meta` rows `sql::MetaTable` keeps, and no other, so that Chromium completes what it does to a
    /// new one when it first opens it. A registered bucket is used and modified now, never expires, has no quota of
    /// its own, is not persistent, and has relaxed durability, as a bucket a page opens without options is.
    ///
    /// @param target the file to write, which must not exist
    /// @return the ids of the registrations, in their order
    /// @throws ViridianException if `existing` has a version or columns other than those this writer writes, or already
    ///                           records one of the registrations
    /// @throws IOException       if `existing` cannot be read, or `target` cannot be written; `target` is then not left
    ///                           behind
    public static List<Long> register(Path existing, Path target, List<Registration> registrations) throws IOException {
        var meta = new ArrayList<List<Object>>();
        var sequence = new ArrayList<List<Object>>();
        var rows = new ArrayList<List<Object>>();
        var next = 1L;
        if (Files.isRegularFile(existing)) {
            try (var database = SqliteDatabase.open(existing)) {
                checkVersion(database, existing);
                var metaTable = table(database, "meta", existing);
                meta.addAll(rows(metaTable, List.of("key", "value")));
                var sequenceTable = database.findTable("sqlite_sequence");
                if (sequenceTable.isPresent()) {
                    for (var row : rows(sequenceTable.get(), List.of("name", "seq"))) {
                        sequence.add(row);
                        if ("buckets".equals(row.getFirst()) && row.get(1) instanceof Long seq) {
                            next = Math.max(next, seq + 1);
                        }
                    }
                }
                var buckets = table(database, "buckets", existing);
                for (var column : COLUMNS) {
                    if (buckets.columnIndex(column) < 0) {
                        throw unsupported(existing, "has no column " + column);
                    }
                }
                for (var row : rows(buckets, COLUMNS)) {
                    rows.add(row);
                    if (row.getFirst() instanceof Long id) {
                        next = Math.max(next, id + 1);
                    }
                    for (var registration : registrations) {
                        if (registration.storageKey().equals(row.get(1)) && registration.name().equals(row.get(3))) {
                            throw new ViridianException(ViridianException.Reason.CORRUPTED, "The quota database " + existing
                                    + " records the bucket " + registration.name() + " of " + registration.storageKey() + " already");
                        }
                    }
                }
            }
        } else {
            meta.add(List.of("mmap_status", MMAP_SUCCESS));
            meta.add(List.of("version", SCHEMA_VERSION));
            meta.add(List.of("last_compatible_version", SCHEMA_VERSION));
        }
        var now = WINDOWS_EPOCH.until(Instant.now(), ChronoUnit.MICROS);
        var ids = new ArrayList<Long>();
        try (var writer = SqliteWriter.create(target, PAGE_SIZE)) {
            SCHEMA.forEach(writer::execute);
            try {
                for (var row : sequence) {
                    writer.insert("sqlite_sequence", row);
                }
                for (var row : meta) {
                    writer.insert("meta", row);
                }
                for (var row : rows) {
                    writer.insert("buckets", row);
                }
                for (var registration : registrations) {
                    var id = next++;
                    writer.insert("buckets", Arrays.asList(id, registration.storageKey(), registration.host(), registration.name(),
                            0L, now, now, 0L, 0L, 0L, 0L));
                    ids.add(id);
                }
            } catch (IllegalArgumentException exception) {
                throw new ViridianException(ViridianException.Reason.CORRUPTED, "The quota database " + existing
                        + " has rows its schema does not allow", exception);
            }
            writer.finish();
        }
        return List.copyOf(ids);
    }

    /// {@return the directory that holds the data of the bucket of id `id`, relative to the profile}
    public static Path bucketDirectory(long id) {
        return BUCKETS.resolve(Long.toString(id));
    }

    /// Fails unless the database is of the version this reader and writer know, as its `meta` table records it.
    private static void checkVersion(SqliteDatabase database, Path file) throws IOException {
        var meta = database.findTable("meta");
        if (meta.isEmpty()) {
            return;
        }
        for (var row : rows(meta.get(), List.of("key", "value"))) {
            var number = number(row.get(1));
            if ("version".equals(row.getFirst()) && number != SCHEMA_VERSION) {
                throw unsupported(file, "has schema version " + number + "; only version " + SCHEMA_VERSION + " is known");
            }
        }
    }

    /// {@return every row of `table`, in its b-tree order, as the values of `columns`}
    private static List<List<Object>> rows(SqliteTable table, List<String> columns) throws IOException {
        var indexes = new int[columns.size()];
        for (var i = 0; i < indexes.length; i++) {
            indexes[i] = table.columnIndex(columns.get(i));
        }
        var rows = new ArrayList<List<Object>>();
        try (var cursor = table.scan()) {
            while (cursor.next()) {
                var row = cursor.row();
                var values = new ArrayList<>(indexes.length);
                for (var i = 0; i < indexes.length; i++) {
                    values.add(i == 0 && columns.getFirst().equals("id") ? row.rowid() : row.get(indexes[i]));
                }
                rows.add(values);
            }
        }
        return rows;
    }

    private static long number(Object value) {
        return switch (value) {
            case Long number -> number;
            case String text -> {
                try {
                    yield Long.parseLong(text.strip());
                } catch (NumberFormatException _) {
                    yield -1;
                }
            }
            case null, default -> -1;
        };
    }

    private static SqliteTable table(SqliteDatabase database, String name, Path file) throws IOException {
        return database.findTable(name).orElseThrow(() -> new ViridianException(ViridianException.Reason.CORRUPTED,
                "The quota database " + file + " has no " + name + " table"));
    }

    private static ViridianException unsupported(Path file, String what) {
        return new ViridianException(ViridianException.Reason.UNSUPPORTED, "The quota database " + file + " " + what);
    }
}
