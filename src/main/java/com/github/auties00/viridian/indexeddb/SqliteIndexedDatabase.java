package com.github.auties00.viridian.indexeddb;

import com.github.auties00.viridian.IdbIndex;
import com.github.auties00.viridian.IdbKey;
import com.github.auties00.viridian.IdbKeyPath;
import com.github.auties00.viridian.IdbObjectStore;
import com.github.auties00.viridian.IdbRecord;
import com.github.auties00.viridian.IndexedDatabase;
import com.github.auties00.viridian.StorageBackend;
import com.github.auties00.viridian.StorageOrigin;
import com.github.auties00.viridian.ViridianException;
import com.github.auties00.viridian.compress.Snappy;
import com.github.auties00.viridian.compress.Zstd;
import com.github.auties00.viridian.sqlite.SqliteCursor;
import com.github.auties00.viridian.sqlite.SqliteDatabase;
import com.github.auties00.viridian.sqlite.SqliteRow;
import com.github.auties00.viridian.sqlite.SqliteTable;
import com.github.auties00.viridian.value.BlobSource;
import com.github.auties00.viridian.value.ExternalObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

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
public final class SqliteIndexedDatabase implements IndexedDatabase {
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
    private final StorageOrigin origin;
    private final String name;
    private final long version;
    private final List<IdbObjectStore> objectStores;
    private boolean closed;

    private SqliteIndexedDatabase(Path file, SqliteDatabase database, StorageOrigin origin, String name, long version,
                                  List<IdbObjectStore> objectStores) {
        this.file = file;
        this.database = database;
        this.origin = origin;
        this.name = name;
        this.version = version;
        this.objectStores = objectStores;
    }

    /// {@return the database in the SQLite file `file`, open; the caller closes it}
    ///
    /// @throws ViridianException if the file is not a database of the backend, or one of a schema this reader does not
    ///                           know
    /// @throws IOException       if the file cannot be read
    public static SqliteIndexedDatabase open(Path file, StorageOrigin origin) throws IOException {
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
                            keyPath(row.getBlob(storeTable.columnIndex("key_path"))),
                            row.getLong(storeTable.columnIndex("auto_increment")) != 0));
                }
            }
            var indexTable = table(database, "indexes", file);
            try (var cursor = indexTable.scan()) {
                while (cursor.next()) {
                    var row = cursor.row();
                    var storeId = row.getLong(indexTable.columnIndex("object_store_id"));
                    var keyPath = keyPath(row.getBlob(indexTable.columnIndex("key_path")));
                    if (keyPath == null) {
                        throw corrupted(file, "has an index without a key path");
                    }
                    var index = new IdbIndex(row.getLong(indexTable.columnIndex("id")),
                            IdbKeyCodec.utf16LittleEndian(row.getBlob(indexTable.columnIndex("name"))), keyPath,
                            row.getLong(indexTable.columnIndex("is_unique")) != 0, row.getLong(indexTable.columnIndex("multi_entry")) != 0);
                    for (var store : stores) {
                        if (store.id == storeId) {
                            store.indexes.add(index);
                        }
                    }
                }
            }
            var objectStores = new ArrayList<IdbObjectStore>();
            for (var store : stores) {
                objectStores.add(store.build());
            }
            return new SqliteIndexedDatabase(file, database, origin, name, version, List.copyOf(objectStores));
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
    public StorageOrigin origin() {
        return origin;
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
    public Stream<IdbRecord> records(String store) throws IOException {
        ensureOpen();
        var storeId = requireStore(store).id();
        var index = database.findIndex("records_by_key").orElseThrow(() -> corrupted(file, "has no records_by_key index"));
        var cursor = index.seek(List.of(storeId));
        var iterator = new Iterator<IdbRecord>() {
            private IdbRecord next;
            private boolean done;

            @Override
            public boolean hasNext() {
                if (next == null && !done) {
                    try {
                        if (cursor.next() && cursor.row().getLong(0) == storeId) {
                            next = recordAt(cursor.row().rowid());
                        } else {
                            done = true;
                        }
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                }
                return next != null;
            }

            @Override
            public IdbRecord next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                var result = next;
                next = null;
                return result;
            }
        };
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED | Spliterator.NONNULL), false)
                .onClose(cursor::close);
    }

    @Override
    public Optional<IdbRecord> record(String store, IdbKey key) throws IOException {
        ensureOpen();
        var storeId = requireStore(store).id();
        var encodedKey = IdbKeyCodec.encodeSortable(key);
        var index = database.findIndex("records_by_key").orElseThrow(() -> corrupted(file, "has no records_by_key index"));
        try (var cursor = index.seek(List.of(storeId, encodedKey))) {
            if (cursor.next() && cursor.row().getLong(0) == storeId && Arrays.equals(cursor.row().getBlob(1), encodedKey)) {
                return Optional.of(recordAt(cursor.row().rowid()));
            }
            return Optional.empty();
        }
    }

    /// {@return the record of the `records` row `rowId`}
    private StoredRecord recordAt(long rowId) throws IOException {
        var records = table(database, "records", file);
        var row = records.findByRowid(rowId).orElseThrow(() -> corrupted(file, "indexes record row " + rowId + ", which is not there"));
        IdbKey key;
        try {
            key = IdbKeyCodec.decodeSortable(row.getBlob(records.columnIndex("key")));
        } catch (IOException exception) {
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
        return new StoredRecord(key, externalObjects(rowId), value, 0);
    }

    /// {@return the external objects of record `recordRowId`: its blobs and files by row id, then its handles}
    private List<ExternalObject> externalObjects(long recordRowId) throws IOException {
        var references = database.findIndex("blob_references_by_record");
        if (references.isEmpty()) {
            return List.of();
        }
        var blobs = table(database, "blobs", file);
        var blobsAndFiles = new ArrayList<ExternalObject>();
        var handles = new ArrayList<ExternalObject>();
        try (var cursor = references.get().seek(List.of(recordRowId))) {
            while (cursor.next() && cursor.row().get(0) instanceof Long rowId && rowId == recordRowId) {
                var blobRowId = cursor.row().getLong(1);
                var row = blobs.findByRowid(blobRowId).orElseThrow(() -> corrupted(file, "refers to blob row " + blobRowId + ", which is not there"));
                var type = row.getLong(blobs.columnIndex("object_type"));
                if (type == FILE_SYSTEM_ACCESS_HANDLE) {
                    var token = row.getBlob(blobs.columnIndex("bytes"));
                    handles.add(new ExternalObject.FileSystemAccessHandle(token == null ? new byte[0] : token));
                    continue;
                }
                var mimeType = row.isNull(blobs.columnIndex("mime_type")) ? "" : row.getText(blobs.columnIndex("mime_type"));
                var size = row.isNull(blobs.columnIndex("size_bytes")) ? -1 : row.getLong(blobs.columnIndex("size_bytes"));
                var source = blobSource(blobRowId, row.isNull(blobs.columnIndex("bytes")));
                if (type == BLOB) {
                    blobsAndFiles.add(new ExternalObject.Blob(mimeType, size, source));
                } else if (type == FILE) {
                    // The column is declared BLOB, but Chromium binds the name as text
                    var fileName = switch (row.get(blobs.columnIndex("file_name"))) {
                        case String text -> text;
                        case byte[] utf16 -> IdbKeyCodec.utf16LittleEndian(utf16);
                        case null, default -> "";
                    };
                    var modified = row.isNull(blobs.columnIndex("last_modified")) ? null
                            : WINDOWS_EPOCH.plus(row.getLong(blobs.columnIndex("last_modified")), ChronoUnit.MICROS);
                    blobsAndFiles.add(new ExternalObject.File(fileName, mimeType, size, modified, source));
                } else {
                    throw corrupted(file, "has a blob of object type " + type);
                }
            }
        }
        var all = new ArrayList<ExternalObject>(blobsAndFiles);
        all.addAll(handles);
        return all;
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
        return "IndexedDatabase[" + origin + ", " + name + ", SQLite]";
    }

    private static SqliteTable table(SqliteDatabase database, String name, Path file) throws IOException {
        return database.findTable(name).orElseThrow(() -> corrupted(file, "has no " + name + " table"));
    }

    /// {@return the key path a stored key path holds: none for NULL, a single path, or parts joined by spaces}
    private static IdbKeyPath keyPath(byte[] stored) throws IOException {
        if (stored == null) {
            return null;
        }
        var parts = IdbKeyCodec.utf16LittleEndian(stored).split(" ", -1);
        return parts.length == 1 ? new IdbKeyPath.Single(parts[0]) : new IdbKeyPath.Compound(List.of(parts));
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
        private final List<IdbIndex> indexes = new ArrayList<>();

        StoreBuilder(long id, String name, IdbKeyPath keyPath, boolean autoIncrement) {
            this.id = id;
            this.name = name;
            this.keyPath = keyPath;
            this.autoIncrement = autoIncrement;
        }

        IdbObjectStore build() {
            indexes.sort((first, second) -> Long.compare(first.id(), second.id()));
            return new IdbObjectStore(id, name, keyPath, autoIncrement, indexes);
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
