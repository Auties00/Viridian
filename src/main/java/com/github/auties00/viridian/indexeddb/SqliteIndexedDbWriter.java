package com.github.auties00.viridian.indexeddb;

import com.github.auties00.viridian.IdbKey;
import com.github.auties00.viridian.IdbKeyPath;
import com.github.auties00.viridian.IdbObjectStore;
import com.github.auties00.viridian.ViridianException;
import com.github.auties00.viridian.sqlite.SqliteWriter;
import com.github.auties00.viridian.value.JsBlob;
import com.github.auties00.viridian.value.JsFile;
import com.github.auties00.viridian.value.JsValue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/// Writes an IndexedDB database as Chromium's SQLite backend stores it: one SQLite file holding its metadata, records,
/// index entries and the bytes of its blobs and files, which Chrome opens and keeps using as its own. A bucket's
/// databases are files of one directory, each named by [#fileName(String)].
///
/// Each file is the one Chrome 157 leaves after a page creates the database and puts its records: the schema
/// [SqliteDatabaseReader] reads, created by Chromium's statements in its order, and the `meta` rows `sql::MetaTable`
/// keeps as text: `mmap_status` -1, `version` and `last_compatible_version` 1, `v8_data_version`, the data format of
/// the database's values as `IndexedDBDataFormatVersion` encodes it, the newest V8 version among them in the high 32
/// bits and the newest Blink version in the low ones, and `created_time`, when the file was written. The tables hold:
///
/// | Table | Rows |
/// |---|---|
/// | `indexed_db_metadata` | the database's name and version |
/// | `object_stores` | each store, numbered from 1 in list order, with the number its key generator gives next, as the database records it or one past its largest number key |
/// | `indexes` | each index, numbered in each store from 31, one past the largest id of a store without indexes |
/// | `records` | each record, numbered from 1 store by store in key order, its value uncompressed |
/// | `index_references` | each key an index holds a record under, with the record's store and key |
/// | `blobs` | each blob and file of a value, numbered from 1 record by record |
/// | `overflow_blob_chunks` | the bytes of a blob past its first 5 MiB, in chunks of 5 MiB numbered from 1 |
/// | `blob_references` | the record each blob belongs to |
///
/// The backend gives a value the blobs and files of its record in the order of their row ids, so a record's blobs and
/// files are numbered in the order its value refers to them. Names and
/// key paths are little-endian UTF-16, a compound key path's parts joined by spaces, and keys are in the sortable
/// form. A blob's MIME type and a file's name are text, unpaired surrogates replaced by U+FFFD as Chromium converts
/// them; a file's modification time is in microseconds since 1601-01-01 as `base::Time` counts, 0 when it was not
/// recorded; an empty blob has an empty `bytes`, never `NULL`, which marks a blob kept in a file. Values are stored
/// whole, as the serializer gives them: Blink neither compresses the
/// values it sends to this backend nor moves large ones into blobs.
///
/// @implNote This implementation never compresses a value, where Chrome compresses one of 450 bytes or more with zstd
/// when that saves a fifth of it; Chrome reads an uncompressed value, compression type 0, like any other.
public final class SqliteIndexedDbWriter {
    // The page size Chromium's sql::Database gives a new database
    private static final int PAGE_SIZE = 4096;
    // The backend's schema version, which the meta table records
    private static final long SCHEMA_VERSION = 1;
    // kMmapSuccess: the file was found sound, so SQLite may map it
    private static final long MMAP_SUCCESS = -1;
    // Chromium's statements, from CreateSchema in the backend's DatabaseConnection and sql::MetaTable, in their order
    private static final List<String> SCHEMA = List.of(
            "CREATE TABLE indexed_db_metadata (name BLOB NOT NULL, version INTEGER NOT NULL)",
            "CREATE TABLE object_stores (id INTEGER PRIMARY KEY, name BLOB NOT NULL, key_path BLOB, auto_increment INTEGER NOT NULL,"
                    + " key_generator_current_number INTEGER NOT NULL)",
            "CREATE TABLE indexes (object_store_id INTEGER NOT NULL, id INTEGER NOT NULL, name BLOB NOT NULL, key_path BLOB,"
                    + " is_unique INTEGER NOT NULL, multi_entry INTEGER NOT NULL, PRIMARY KEY (object_store_id, id)) WITHOUT ROWID",
            "CREATE TABLE records (row_id INTEGER PRIMARY KEY, object_store_id INTEGER NOT NULL, compression_type INTEGER NOT NULL,"
                    + " key BLOB NOT NULL, value BLOB NOT NULL)",
            "CREATE UNIQUE INDEX records_by_key ON records(object_store_id, key)",
            "CREATE TABLE index_references (record_row_id INTEGER NOT NULL, index_id INTEGER NOT NULL, key BLOB NOT NULL,"
                    + " object_store_id INTEGER NOT NULL, record_key BLOB NOT NULL, PRIMARY KEY (record_row_id, index_id, key)) WITHOUT ROWID",
            "CREATE INDEX index_references_by_key ON index_references (object_store_id, index_id, key, record_key)",
            "CREATE TABLE blobs (row_id INTEGER PRIMARY KEY AUTOINCREMENT, object_type INTEGER NOT NULL, mime_type TEXT,"
                    + " size_bytes INTEGER, file_name BLOB, last_modified INTEGER,  bytes BLOB)",
            "CREATE INDEX legacy_blobs ON blobs (object_type) WHERE bytes IS NULL",
            "CREATE TABLE overflow_blob_chunks (row_id INTEGER PRIMARY KEY, blob_row_id INTEGER NOT NULL, chunk_index INTEGER NOT NULL,"
                    + " bytes BLOB NOT NULL)",
            "CREATE INDEX overflow_chunks_by_blob ON overflow_blob_chunks (blob_row_id, chunk_index)",
            "CREATE TABLE blob_references (row_id INTEGER PRIMARY KEY, blob_row_id INTEGER NOT NULL, record_row_id INTEGER)",
            "CREATE INDEX blob_references_by_blob ON blob_references (blob_row_id)",
            "CREATE INDEX blob_references_by_record ON blob_references (record_row_id, blob_row_id)",
            "CREATE TRIGGER on_record_deleted AFTER DELETE ON records BEGIN  DELETE FROM index_references WHERE record_row_id = OLD.row_id;"
                    + "  DELETE FROM blob_references WHERE record_row_id = OLD.row_id;END",
            "CREATE TRIGGER on_blob_reference_deleted  AFTER DELETE ON blob_references WHEN NOT EXISTS  (SELECT 1 FROM blob_references"
                    + " WHERE blob_row_id = OLD.blob_row_id) BEGIN  DELETE FROM blobs WHERE row_id = OLD.blob_row_id;END",
            "CREATE TRIGGER on_blob_deleted  AFTER DELETE ON blobs BEGIN  DELETE FROM overflow_blob_chunks WHERE blob_row_id = OLD.row_id;END",
            "CREATE TABLE meta(key LONGVARCHAR NOT NULL UNIQUE PRIMARY KEY, value LONGVARCHAR)");
    // CompressionType::kUncompressed
    private static final int UNCOMPRESSED = 0;
    // IndexedDBExternalObject::ObjectType
    private static final int OBJECT_BLOB = 0;
    private static final int OBJECT_FILE = 1;
    // GetMaxBlobSize(): the most bytes of a blob one row holds, the rest going to overflow chunks of this size
    private static final int CHUNK_SIZE = 5 * 1024 * 1024;
    // base::Time's epoch, 1601-01-01, which file and creation times count from
    private static final Instant WINDOWS_EPOCH = Instant.parse("1601-01-01T00:00:00Z");

    private final SqliteWriter sqlite;
    private final ValueSerializer serializer;
    private final IdbDataFormat format = new IdbDataFormat();
    // The row ids the next record and the next blob take
    private long nextRecord = 1;
    private long nextBlob = 1;

    private SqliteIndexedDbWriter(SqliteWriter sqlite, ValueSerializer serializer) {
        this.sqlite = sqlite;
        this.serializer = serializer;
    }

    /// Writes `database` as the SQLite file `file`, which must not exist, forcing it to disk once written.
    ///
    /// The database's records are read from it one at a time, as they are written. A write that fails leaves no file.
    ///
    /// @param serializer serializes each record's value, as a page's would be serialized
    /// @return the data format of the values written
    /// @throws IllegalArgumentException if a store's records are not in key order, two records share a key in a unique
    ///                                  index, or a value cannot be stored
    /// @throws ViridianException        if a blob or file in a value has no bytes, or supplies other than its size in
    ///                                  bytes
    /// @throws IOException              if a record or a blob's bytes cannot be read, or the file cannot be written
    public static IdbDataFormat write(Path file, DatabaseSource database, ValueSerializer serializer) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(serializer, "serializer");
        try (var sqlite = SqliteWriter.create(file, PAGE_SIZE)) {
            var writer = new SqliteIndexedDbWriter(sqlite, serializer);
            writer.writeDatabase(database);
            sqlite.finish();
            return writer.format;
        }
    }

    /// {@return the name of the file of the database `name` in its origin's directory, as Chromium names it: the
    /// Base32 of the SHA-256 of the name's UTF-16 code units, or `0` for the empty name}
    public static String fileName(String name) {
        return IndexedDbDirectory.sqliteFileName(name);
    }

    private void writeDatabase(DatabaseSource database) throws IOException {
        SCHEMA.forEach(sqlite::execute);
        sqlite.insert("indexed_db_metadata", List.of(utf16(database.name()), database.version()));
        for (var i = 0; i < database.objectStores().size(); i++) {
            writeObjectStore(i + 1, database, database.objectStores().get(i));
        }
        sqlite.insert("meta", List.of("mmap_status", MMAP_SUCCESS));
        sqlite.insert("meta", List.of("version", SCHEMA_VERSION));
        sqlite.insert("meta", List.of("last_compatible_version", SCHEMA_VERSION));
        sqlite.insert("meta", List.of("v8_data_version", format.encoded()));
        sqlite.insert("meta", List.of("created_time", WINDOWS_EPOCH.until(Instant.now(), ChronoUnit.MICROS)));
    }

    private void writeObjectStore(long storeId, DatabaseSource database, IdbObjectStore store) throws IOException {
        for (var i = 0; i < store.indexes().size(); i++) {
            var index = store.indexes().get(i);
            sqlite.insert("indexes", List.of(storeId, (long) IdbWriterSupport.FIRST_INDEX_ID + i, utf16(index.name()), keyPath(index.keyPath()),
                    index.unique() ? 1L : 0L, index.multiEntry() ? 1L : 0L));
        }
        var indexKeys = new IdbWriterSupport.IndexKeys(store);
        var order = new IdbWriterSupport.RecordOrder(store);
        try (var records = database.records(store)) {
            while (records.next()) {
                var key = records.key();
                order.note(key);
                writeRecord(storeId, store, key, records.value(), indexKeys);
            }
        }
        sqlite.insert("object_stores", Arrays.asList(storeId, utf16(store.name()), keyPath(store.keyPath().orElse(null)),
                store.autoIncrement() ? 1L : 0L, order.keyGenerator(database)));
    }

    /// Writes a record, its index references and its blobs.
    private void writeRecord(long storeId, IdbObjectStore store, IdbKey key, JsValue value, IdbWriterSupport.IndexKeys indexKeys)
            throws IOException {
        var recordId = nextRecord++;
        var serialized = serializer.serialize(value);
        IdbWriterSupport.requireBytes(serialized, key, store);
        format.note(serialized.bytes());
        var encodedKey = IdbKeyCodec.encodeSortable(key);
        sqlite.insert("records", List.of(recordId, storeId, (long) UNCOMPRESSED, encodedKey, serialized.bytes()));
        for (var i = 0; i < store.indexes().size(); i++) {
            var indexId = (long) IdbWriterSupport.FIRST_INDEX_ID + i;
            for (var indexKey : indexKeys.of(i, key, value)) {
                sqlite.insert("index_references", List.of(recordId, indexId, IdbKeyCodec.encodeSortable(indexKey), storeId, encodedKey));
            }
        }
        // The backend lists a record's blobs and files by row id, in the order of their indices
        for (var blob : serialized.blobs()) {
            writeExternal(recordId, blob, key, store);
        }
    }

    /// Writes a blob or file of record `recordId`, and the reference from the record to it.
    private void writeExternal(long recordId, JsBlob external, IdbKey key, IdbObjectStore store) throws IOException {
        var blobId = nextBlob++;
        var first = writeChunks(blobId, external, key, store);
        switch (external) {
            case JsFile file -> {
                var modified = file.lastModified().map(time -> WINDOWS_EPOCH.until(time, ChronoUnit.MICROS)).orElse(0L);
                sqlite.insert("blobs", Arrays.asList(blobId, (long) OBJECT_FILE, wellFormed(file.type()), first.size(), wellFormed(file.name()),
                        modified, first.bytes()));
            }
            case JsBlob blob -> sqlite.insert("blobs", Arrays.asList(blobId, (long) OBJECT_BLOB, wellFormed(blob.type()), first.size(), null, null,
                    first.bytes()));
        }
        sqlite.insert("blob_references", Arrays.asList(null, blobId, recordId));
    }

    /// Reads a blob's bytes, writing those past the first chunk as its overflow chunks.
    ///
    /// @return the blob's first chunk, and its size
    /// @throws ViridianException if the blob supplies other than its size in bytes
    private FirstChunk writeChunks(long blobId, JsBlob blob, IdbKey key, IdbObjectStore store) throws IOException {
        if (!blob.hasBytes()) {
            return new FirstChunk(new byte[0], 0);
        }
        var size = blob.size();
        byte[] first;
        var total = 0L;
        try (InputStream input = blob.open()) {
            first = input.readNBytes(CHUNK_SIZE);
            total = first.length;
            var chunk = first;
            for (var index = 1L; chunk.length == CHUNK_SIZE; index++) {
                chunk = input.readNBytes(CHUNK_SIZE);
                if (chunk.length == 0) {
                    break;
                }
                sqlite.insert("overflow_blob_chunks", Arrays.asList(null, blobId, index, chunk));
                total += chunk.length;
            }
        }
        if (size >= 0 && total != size) {
            throw new ViridianException(ViridianException.Reason.CORRUPTED, "The source of a blob in record " + key + " of "
                    + store.name() + " supplied " + total + " bytes instead of its " + size);
        }
        return new FirstChunk(first, total);
    }

    /// The start of a blob, which its own row holds.
    ///
    /// @param bytes the blob's first bytes, at most a chunk
    /// @param size  the size of the whole blob
    private record FirstChunk(byte[] bytes, long size) {
    }

    /// {@return a key path as the backend stores it: `NULL` for none, a path, or the paths of a compound one joined by
    /// spaces, as little-endian UTF-16}
    private static byte[] keyPath(IdbKeyPath keyPath) {
        return switch (keyPath) {
            case null -> null;
            case IdbKeyPath.Single single -> utf16(single.path());
            case IdbKeyPath.Compound compound -> utf16(String.join(" ", compound.paths()));
        };
    }

    /// {@return the little-endian UTF-16 code units of `value`, as the backend stores names}
    private static byte[] utf16(String value) {
        var bytes = new byte[value.length() * 2];
        for (var i = 0; i < value.length(); i++) {
            bytes[2 * i] = (byte) value.charAt(i);
            bytes[2 * i + 1] = (byte) (value.charAt(i) >>> 8);
        }
        return bytes;
    }

    /// {@return `value` with every unpaired surrogate replaced by U+FFFD, as Chromium converts text it binds as UTF-8}
    private static String wellFormed(String value) {
        StringBuilder result = null;
        for (var i = 0; i < value.length(); i++) {
            var c = value.charAt(i);
            var paired = Character.isHighSurrogate(c) ? i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))
                    : !Character.isLowSurrogate(c) || i > 0 && Character.isHighSurrogate(value.charAt(i - 1));
            if (!paired && result == null) {
                result = new StringBuilder(value.length()).append(value, 0, i);
            }
            if (result != null) {
                result.append(paired ? c : '\uFFFD');
            }
        }
        return result == null ? value : result.toString();
    }
}
