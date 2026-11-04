package com.github.auties00.viridian.indexeddb;

import com.github.auties00.viridian.IdbDatabaseReader;
import com.github.auties00.viridian.IdbIndex;
import com.github.auties00.viridian.IdbKey;
import com.github.auties00.viridian.IdbKeyPath;
import com.github.auties00.viridian.IdbObjectStore;
import com.github.auties00.viridian.IdbRecord;
import com.github.auties00.viridian.StorageBackend;
import com.github.auties00.viridian.StorageBucket;
import com.github.auties00.viridian.ViridianException;
import com.github.auties00.viridian.compress.Snappy;
import com.github.auties00.viridian.compress.Zstd;
import com.github.auties00.viridian.sqlite.SqliteCursor;
import com.github.auties00.viridian.sqlite.SqliteDatabase;
import com.github.auties00.viridian.sqlite.SqliteRow;
import com.github.auties00.viridian.sqlite.SqliteTable;
import com.github.auties00.viridian.value.BlobSource;
import com.github.auties00.viridian.io.ValueAccess;
import com.github.auties00.viridian.value.JsBlob;
import com.github.auties00.viridian.value.JsValue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.stream.Stream;

/// An IndexedDB database of Chromium's SQLite backend: one SQLite file, opened read-only, with the database's
/// metadata, records and blobs.
///
/// The schema this reads, version 1 of the backend's own versioning:
///
/// ```text
/// indexed_db_metadata (name BLOB, version INTEGER)
/// object_stores (id INTEGER PRIMARY KEY, name BLOB, key_path BLOB, auto_increment INTEGER, key_generator_current_number INTEGER)
/// indexes (object_store_id, id, name BLOB, key_path BLOB, is_unique, multi_entry) WITHOUT ROWID
/// records (row_id INTEGER PRIMARY KEY, object_store_id, compression_type, key BLOB, value BLOB), indexed by (object_store_id, key)
/// blobs (row_id INTEGER PRIMARY KEY, object_type, mime_type TEXT, size_bytes, file_name BLOB, last_modified, bytes BLOB)
/// overflow_blob_chunks (row_id INTEGER PRIMARY KEY, blob_row_id, chunk_index, bytes BLOB), indexed by (blob_row_id, chunk_index)
/// blob_references (row_id INTEGER PRIMARY KEY, blob_row_id, record_row_id), indexed by (record_row_id, blob_row_id)
/// ```
///
/// Names and key paths are little-endian UTF-16, a compound key path's parts joined by spaces. Keys are in the
/// sortable form, so the records index orders them as IndexedDB does. A value is stored raw, zstd-compressed or
/// Snappy-compressed (compression type 0, 1 or 2). A blob's bytes are in its row and, past 5 MiB, in overflow
/// chunks; a blob migrated from the LevelDB backend has no bytes in its row and is a file `<database file>_/<blob
/// row id in hexadecimal>`.
public final class SqliteDatabaseReader implements IdbDatabaseReader, DatabaseSource {
    // The backend's schema versions this reader knows
    private static final int SCHEMA_VERSION = 1;
    private static final int UNCOMPRESSED = 0;
    private static final int ZSTD = 1;
    private static final int SNAPPY = 2;
    private static final int BLOB = 0;
    private static final int FILE = 1;
    private static final int FILE_SYSTEM_ACCESS_HANDLE = 2;
    // base::Time's epoch, 1601-01-01, as Chromium stores file times
    private static final Instant WINDOWS_EPOCH = Instant.parse("1601-01-01T00:00:00Z");

    private final Path file;
    private final SqliteDatabase database;
    private final StorageBucket bucket;
    private final String name;
    private final long version;
    private final List<IdbObjectStore> objectStores;
    // The id and key generator of each object store, by name
    private final Map<String, Long> storeIds;
    private final Map<String, Long> keyGenerators;
    private boolean closed;

    private SqliteDatabaseReader(Path file, SqliteDatabase database, StorageBucket bucket, String name, long version,
                                 List<IdbObjectStore> objectStores, Map<String, Long> storeIds, Map<String, Long> keyGenerators) {
        this.file = file;
        this.database = database;
        this.bucket = bucket;
        this.name = name;
        this.version = version;
        this.objectStores = objectStores;
        this.storeIds = storeIds;
        this.keyGenerators = keyGenerators;
    }

    /// {@return the database in the SQLite file `file`, open; the caller closes it}
    ///
    /// @throws ViridianException if the file is not a database of the backend, or one of a schema this reader does not
    ///                           know
    /// @throws IOException       if the file cannot be read
    public static SqliteDatabaseReader open(Path file, StorageBucket bucket) throws IOException {
        var database = SqliteDatabase.open(file);
        try {
            checkSchema(database, file);
            var metadata = table(database, "indexed_db_metadata", file);
            String name = null;
            var version = 0L;
            try (var cursor = metadata.scan()) {
                if (cursor.next()) {
                    name = IdbKeyCodec.utf16LittleEndian(cursor.row().getBlob(metadata.columnIndex("name")));
                    version = cursor.row().getLong(metadata.columnIndex("version"));
                }
            }
            if (name == null) {
                throw corrupted(file, "has no metadata row");
            }
            var stores = new ArrayList<StoreBuilder>();
            var storeTable = table(database, "object_stores", file);
            try (var cursor = storeTable.scan()) {
                while (cursor.next()) {
                    var row = cursor.row();
                    stores.add(new StoreBuilder(row.getLong(storeTable.columnIndex("id")),
                            IdbKeyCodec.utf16LittleEndian(row.getBlob(storeTable.columnIndex("name"))),
                            keyPath(row.getBlob(storeTable.columnIndex("key_path")), file),
                            row.getLong(storeTable.columnIndex("auto_increment")) != 0,
                            row.getLong(storeTable.columnIndex("key_generator_current_number"))));
                }
            }
            var indexTable = table(database, "indexes", file);
            try (var cursor = indexTable.scan()) {
                while (cursor.next()) {
                    var row = cursor.row();
                    var storeId = row.getLong(indexTable.columnIndex("object_store_id"));
                    var keyPath = keyPath(row.getBlob(indexTable.columnIndex("key_path")), file);
                    if (keyPath == null) {
                        throw corrupted(file, "has an index without a key path");
                    }
                    IdbIndex index;
                    try {
                        index = new IdbIndex(IdbKeyCodec.utf16LittleEndian(row.getBlob(indexTable.columnIndex("name"))), keyPath,
                                row.getLong(indexTable.columnIndex("is_unique")) != 0, row.getLong(indexTable.columnIndex("multi_entry")) != 0);
                    } catch (IllegalArgumentException exception) {
                        throw new ViridianException(ViridianException.Reason.CORRUPTED, "The IndexedDB database " + file.getFileName()
                                + " has an index IndexedDB does not make", exception);
                    }
                    for (var store : stores) {
                        if (store.id == storeId) {
                            store.indexes.put(row.getLong(indexTable.columnIndex("id")), index);
                        }
                    }
                }
            }
            var objectStores = new ArrayList<IdbObjectStore>();
            var storeIds = new LinkedHashMap<String, Long>();
            var keyGenerators = new LinkedHashMap<String, Long>();
            stores.sort((first, second) -> Long.compare(first.id, second.id));
            for (var store : stores) {
                objectStores.add(store.build(file));
                storeIds.put(store.name, store.id);
                if (store.autoIncrement) {
                    keyGenerators.put(store.name, store.keyGenerator);
                }
            }
            return new SqliteDatabaseReader(file, database, bucket, name, version, List.copyOf(objectStores), storeIds, keyGenerators);
        } catch (IOException | RuntimeException exception) {
            try {
                database.close();
            } catch (IOException closing) {
                exception.addSuppressed(closing);
            }
            throw exception;
        }
    }

    /// Fails unless the backend's schema is one this reader knows, as Chromium's `meta` table records it.
    private static void checkSchema(SqliteDatabase database, Path file) throws IOException {
        var meta = database.findTable("meta");
        if (meta.isEmpty()) {
            return;
        }
        try (var cursor = meta.get().scan()) {
            while (cursor.next()) {
                var row = cursor.row();
                var key = row.get(0);
                if ("last_compatible_version".equals(key)) {
                    var value = number(row.get(1));
                    if (value > SCHEMA_VERSION) {
                        throw new ViridianException(ViridianException.Reason.UNSUPPORTED, "The IndexedDB database " + file.getFileName()
                                + " has schema version " + value + ", newer than this reader knows");
                    }
                }
            }
        }
    }

    @Override
    public StorageBucket bucket() {
        return bucket;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public long version() {
        return version;
    }

    @Override
    public StorageBackend backend() {
        return StorageBackend.SQLITE;
    }

    @Override
    public List<IdbObjectStore> objectStores() {
        return objectStores;
    }

    @Override
    public OptionalLong keyGenerator(String store) {
        requireStore(store);
        var generator = keyGenerators.get(store);
        return generator == null ? OptionalLong.empty() : OptionalLong.of(generator);
    }

    @Override
    public OptionalLong keyGenerator(IdbObjectStore store) {
        return keyGenerator(store.name());
    }

    @Override
    public long count(String store) throws IOException {
        ensureOpen();
        var storeId = requireStoreId(store);
        var index = database.findIndex("records_by_key").orElseThrow(() -> corrupted(file, "has no records_by_key index"));
        var count = 0L;
        try (var cursor = index.seek(List.of(storeId))) {
            while (cursor.next() && cursor.row().getLong(0) == storeId) {
                count++;
            }
        }
        return count;
    }

    @Override
    public Optional<JsValue> get(String store, IdbKey key) throws IOException {
        ensureOpen();
        var definition = requireStore(store);
        var storeId = requireStoreId(store);
        var encodedKey = IdbKeyCodec.encodeSortable(key);
        var index = database.findIndex("records_by_key").orElseThrow(() -> corrupted(file, "has no records_by_key index"));
        try (var cursor = index.seek(List.of(storeId, encodedKey))) {
            if (cursor.next() && cursor.row().getLong(0) == storeId && Arrays.equals(cursor.row().getBlob(1), encodedKey)) {
                return Optional.of(recordAt(cursor.row().rowid()).value(definition));
            }
            return Optional.empty();
        }
    }

    @Override
    public Stream<IdbRecord> records(String store) throws IOException {
        return StoredCursor.stream(openCursor(store));
    }

    @Override
    public StoredCursor openCursor(String store) throws IOException {
        ensureOpen();
        var definition = requireStore(store);
        var storeId = requireStoreId(store);
        var index = database.findIndex("records_by_key").orElseThrow(() -> corrupted(file, "has no records_by_key index"));
        var cursor = index.seek(List.of(storeId));
        return new StoredCursor(new StoredCursor.Records() {
            private boolean done;

            @Override
            public StoredRecord next() throws IOException {
                if (!done && cursor.next() && cursor.row().getLong(0) == storeId) {
                    return recordAt(cursor.row().rowid());
                }
                done = true;
                return null;
            }

            @Override
            public void close() {
                cursor.close();
            }
        }, definition);
    }

    @Override
    public RecordCursor records(IdbObjectStore store) throws IOException {
        return StoredCursor.stored(openCursor(store.name()));
    }

    /// {@return the record of the `records` row `rowId`}
    private StoredRecord recordAt(long rowId) throws IOException {
        var records = table(database, "records", file);
        var row = records.findByRowid(rowId).orElseThrow(() -> corrupted(file, "indexes record row " + rowId + ", which is not there"));
        IdbKey key;
        try {
            key = IdbKeyCodec.decodeSortable(row.getBlob(records.columnIndex("key")));
        } catch (IOException | IllegalArgumentException exception) {
            throw new ViridianException(ViridianException.Reason.CORRUPTED, "A record key of " + name + " does not decode", exception);
        }
        var stored = row.getBlob(records.columnIndex("value"));
        var value = switch ((int) row.getLong(records.columnIndex("compression_type"))) {
            case UNCOMPRESSED -> stored;
            case ZSTD -> Zstd.decompress(stored, 0, stored.length, StoredRecord.MAX_VALUE_LENGTH);
            case SNAPPY -> Snappy.decompress(stored, 0, stored.length, StoredRecord.MAX_VALUE_LENGTH);
            default -> throw new ViridianException(ViridianException.Reason.UNSUPPORTED, "A record of " + name
                    + " has compression type " + row.getLong(records.columnIndex("compression_type")));
        };
        var blobs = new ArrayList<JsBlob>();
        var tokens = new ArrayList<byte[]>();
        externalObjects(rowId, blobs, tokens);
        return new StoredRecord(key, blobs, tokens, value, 0);
    }

    /// Reads the blobs and files of record `recordRowId`, by row id, and its File System Access tokens.
    private void externalObjects(long recordRowId, List<JsBlob> blobsAndFiles, List<byte[]> handles) throws IOException {
        var references = database.findIndex("blob_references_by_record");
        if (references.isEmpty()) {
            return;
        }
        var access = ValueAccess.get();
        var blobs = table(database, "blobs", file);
        try (var cursor = references.get().seek(List.of(recordRowId))) {
            while (cursor.next() && cursor.row().get(0) instanceof Long rowId && rowId == recordRowId) {
                var blobRowId = cursor.row().getLong(1);
                var row = blobs.findByRowid(blobRowId).orElseThrow(() -> corrupted(file, "refers to blob row " + blobRowId + ", which is not there"));
                var type = row.getLong(blobs.columnIndex("object_type"));
                if (type == FILE_SYSTEM_ACCESS_HANDLE) {
                    var token = row.getBlob(blobs.columnIndex("bytes"));
                    handles.add(token == null ? new byte[0] : token);
                    continue;
                }
                var mimeType = row.isNull(blobs.columnIndex("mime_type")) ? "" : row.getText(blobs.columnIndex("mime_type"));
                var legacy = row.isNull(blobs.columnIndex("bytes"));
                var size = row.isNull(blobs.columnIndex("size_bytes")) ? legacySize(blobRowId, legacy) : row.getLong(blobs.columnIndex("size_bytes"));
                var source = blobSource(blobRowId, legacy);
                if (type == BLOB) {
                    blobsAndFiles.add(access.blob(mimeType, size, source));
                } else if (type == FILE) {
                    // The column is declared BLOB, but Chromium binds the name as text
                    var fileName = switch (row.get(blobs.columnIndex("file_name"))) {
                        case String text -> text;
                        case byte[] utf16 -> IdbKeyCodec.utf16LittleEndian(utf16);
                        case null, default -> "";
                    };
                    var modified = row.isNull(blobs.columnIndex("last_modified")) ? null
                            : WINDOWS_EPOCH.plus(row.getLong(blobs.columnIndex("last_modified")), ChronoUnit.MICROS);
                    blobsAndFiles.add(access.file(fileName, mimeType, size, modified, source));
                } else {
                    throw corrupted(file, "has a blob of object type " + type);
                }
            }
        }
    }

    /// {@return the size of a blob whose row records none: that of its file, if it is kept in one, else 0}
    private long legacySize(long blobRowId, boolean legacy) throws IOException {
        var legacyFile = file.resolveSibling(file.getFileName() + "_").resolve(Long.toHexString(blobRowId));
        return legacy && Files.isRegularFile(legacyFile) ? Files.size(legacyFile) : 0;
    }

    /// {@return where the bytes of blob row `blobRowId` are: its rows, or the file of a migrated blob}
    private BlobSource blobSource(long blobRowId, boolean legacy) {
        if (legacy) {
            var legacyFile = file.resolveSibling(file.getFileName() + "_").resolve(Long.toHexString(blobRowId));
            return () -> {
                try {
                    return Files.newInputStream(legacyFile);
                } catch (NoSuchFileException exception) {
                    throw new ViridianException(ViridianException.Reason.MISSING, "The file of blob " + blobRowId + " of " + name + " is gone: " + legacyFile, exception);
                }
            };
        }
        return () -> new BlobStream(file, blobRowId);
    }

    private IdbObjectStore requireStore(String store) {
        return objectStore(store).orElseThrow(() -> new IllegalArgumentException("The database " + name + " has no object store " + store));
    }

    private long requireStoreId(String store) {
        requireStore(store);
        return storeIds.get(store);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("The database is closed");
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            database.close();
        }
    }

    @Override
    public String toString() {
        return "IdbDatabaseReader[" + bucket + ", " + name + ", SQLite]";
    }

    private static SqliteTable table(SqliteDatabase database, String name, Path file) throws IOException {
        return database.findTable(name).orElseThrow(() -> corrupted(file, "has no " + name + " table"));
    }

    /// {@return the key path a stored key path holds: none for NULL, a single path, or parts joined by spaces}
    private static IdbKeyPath keyPath(byte[] stored, Path file) throws IOException {
        if (stored == null) {
            return null;
        }
        var parts = IdbKeyCodec.utf16LittleEndian(stored).split(" ", -1);
        try {
            return parts.length == 1 ? new IdbKeyPath.Single(parts[0]) : new IdbKeyPath.Compound(List.of(parts));
        } catch (IllegalArgumentException exception) {
            throw corrupted(file, "has a key path that is not valid");
        }
    }

    private static long number(Object value) {
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

    private static ViridianException corrupted(Path file, String what) {
        return new ViridianException(ViridianException.Reason.CORRUPTED, "The IndexedDB database " + file.getFileName() + " " + what);
    }

    /// An object store as its rows describe it.
    private static final class StoreBuilder {
        private final long id;
        private final String name;
        private final IdbKeyPath keyPath;
        private final boolean autoIncrement;
        private final long keyGenerator;
        // The indexes by id
        private final TreeMap<Long, IdbIndex> indexes = new TreeMap<>();

        StoreBuilder(long id, String name, IdbKeyPath keyPath, boolean autoIncrement, long keyGenerator) {
            this.id = id;
            this.name = name;
            this.keyPath = keyPath;
            this.autoIncrement = autoIncrement;
            this.keyGenerator = keyGenerator;
        }

        /// {@return the store's definition}
        ///
        /// @throws ViridianException if the rows describe a store `createObjectStore` would refuse
        IdbObjectStore build(Path file) throws ViridianException {
            var builder = IdbObjectStore.builder(name);
            if (keyPath != null) {
                builder.keyPath(keyPath);
            }
            if (autoIncrement) {
                builder.autoIncrement();
            }
            indexes.values().forEach(builder::index);
            try {
                return builder.build();
            } catch (IllegalArgumentException exception) {
                throw corrupted(file, "has the object store " + name + ", which IndexedDB does not make");
            }
        }
    }

    /// The bytes of a blob: its row's bytes, then its overflow chunks in order, read from a connection of its own so
    /// that it outlives the database it came from.
    private static final class BlobStream extends InputStream {
        private final SqliteDatabase database;
        private final SqliteTable chunks;
        private final SqliteCursor chunkCursor;
        private final long blobRowId;
        private InputStream current;

        BlobStream(Path file, long blobRowId) throws IOException {
            this.blobRowId = blobRowId;
            this.database = SqliteDatabase.open(file);
            try {
                var blobs = table(database, "blobs", file);
                var row = blobs.findByRowid(blobRowId).orElseThrow(() -> new ViridianException(ViridianException.Reason.MISSING,
                        "The blob row " + blobRowId + " of " + file.getFileName() + " is gone"));
                var bytes = row.getBlob(blobs.columnIndex("bytes"));
                this.current = new ByteArrayInputStream(bytes == null ? new byte[0] : bytes);
                this.chunks = database.findTable("overflow_blob_chunks").orElse(null);
                var chunkIndex = database.findIndex("overflow_chunks_by_blob");
                this.chunkCursor = chunks == null || chunkIndex.isEmpty() ? null : chunkIndex.get().seek(List.of(blobRowId));
            } catch (IOException | RuntimeException exception) {
                database.close();
                throw exception;
            }
        }

        @Override
        public int read() throws IOException {
            var one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            while (true) {
                var read = current.read(target, offset, length);
                if (read >= 0 || !nextChunk()) {
                    return read;
                }
            }
        }

        /// Moves to the next overflow chunk, if there is one.
        private boolean nextChunk() throws IOException {
            if (chunkCursor == null || !chunkCursor.next() || !(chunkCursor.row().get(0) instanceof Long owner) || owner != blobRowId) {
                return false;
            }
            SqliteRow chunk = chunks.findByRowid(chunkCursor.row().rowid()).orElseThrow(() -> new ViridianException(
                    ViridianException.Reason.CORRUPTED, "An overflow chunk of blob " + blobRowId + " is indexed but not there"));
            current = new ByteArrayInputStream(chunk.getBlob(chunks.columnIndex("bytes")));
            return true;
        }

        @Override
        public void close() throws IOException {
            try {
                if (chunkCursor != null) {
                    chunkCursor.close();
                }
            } finally {
                database.close();
            }
        }
    }
}
