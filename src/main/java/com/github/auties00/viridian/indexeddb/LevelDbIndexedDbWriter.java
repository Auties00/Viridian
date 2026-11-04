package com.github.auties00.viridian.indexeddb;

import com.github.auties00.viridian.IdbKeyPath;
import com.github.auties00.viridian.IdbObjectStore;
import com.github.auties00.viridian.StorageOrigin;
import com.github.auties00.viridian.ViridianException;
import com.github.auties00.viridian.compress.Snappy;
import com.github.auties00.viridian.leveldb.LevelDbBuilder;
import com.github.auties00.viridian.io.ValueAccess;
import com.github.auties00.viridian.IdbKey;
import com.github.auties00.viridian.value.BlobSource;
import com.github.auties00.viridian.value.JsBlob;
import com.github.auties00.viridian.value.JsFile;
import com.github.auties00.viridian.value.JsValue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Writes a storage bucket's IndexedDB databases as Chromium's LevelDB backend stores them: one LevelDB database
/// holding every database's metadata, records and index entries, and a blob directory holding the bytes of their blobs
/// and files, which Chrome opens and keeps using as its own.
///
/// The store is the one Chrome 157 leaves after a page creates the databases and puts their records, transaction
/// bookkeeping it has already cleaned up aside:
///
/// | Prefix: database, store, index | Rest of the key | Value |
/// |---|---|---|
/// | `0, 0, 0` | 0, 1, 2 | schema version 5, the largest database id, the data format version of the values, at least V8 15 and Blink 21 |
/// | `0, 0, 0` | 3, 4 | the recovery and active blob journals, empty |
/// | `0, 0, 0` | 50, 0 | the transaction scopes' metadata, version 1 |
/// | `0, 0, 0` | 201, `<identifier>@1`, name | the database's id, from 1 in list order |
/// | `db, 0, 0` | 3, 4, 5 | the largest object store id, the version, the next blob number |
/// | `db, 0, 0` | 50, store, 0 to 7 | name, key path, auto-increment, evictable, last record version, largest index id, has key path, key generator |
/// | `db, 0, 0` | 100, store, index, 0 to 3 | name, unique, key path, multi-entry |
/// | `db, 0, 0` | 200, name | the object store's id |
/// | `db, store, 1` | key | the record's version, then its stored value |
/// | `db, store, 2` | key | the record's version |
/// | `db, store, 3` | key | the record's blobs and files |
/// | `db, store, index` | index key, 0, key | the record's version, then its key |
///
/// Object stores are numbered from 1, and indexes from 31, one past the largest index id a new store starts with. A
/// store's key generator is the number the database records, or one past its largest number key.
/// Records take versions from 2 in key order. A value of at least 64 KiB is compressed with Snappy when that saves a
/// tenth of it, and one still larger than 64 KiB is moved into a blob of its record, as Blink wraps the values it
/// sends to the LevelDB backend, whose reads count on values no larger.
/// Blobs are numbered from 2 in each database, and an empty blob or file has no file, as Chrome writes them.
///
/// @implNote This implementation writes the store as a LevelDB log, which Chrome replays into tables when it first
/// opens it, so it never orders keys by Chrome's IndexedDB comparator.
public final class LevelDbIndexedDbWriter {
    /// The name of the comparator Chromium's IndexedDB backend orders its keys by.
    public static final String COMPARATOR = "idb_cmp1";

    private static final long SCHEMA_VERSION = 5;
    // A serialized LevelDBScopesMetadata of version 1
    private static final byte[] SCOPES_METADATA = {0x08, 0x01};
    // The origin a store of one origin names its databases under is its identifier and this suffix
    private static final String ORIGIN_SUFFIX = "@1";
    // Global metadata types
    private static final int SCHEMA_VERSION_TYPE = 0;
    private static final int MAX_DATABASE_ID = 1;
    private static final int DATA_VERSION_TYPE = 2;
    private static final int RECOVERY_BLOB_JOURNAL = 3;
    private static final int ACTIVE_BLOB_JOURNAL = 4;
    private static final int SCOPES_PREFIX = 50;
    private static final int SCOPES_GLOBAL_METADATA = 0;
    private static final int DATABASE_NAME = 201;
    // Database metadata types
    private static final int MAX_OBJECT_STORE_ID = 3;
    private static final int USER_VERSION = 4;
    private static final int BLOB_NUMBER_GENERATOR = 5;
    private static final int OBJECT_STORE_METADATA = 50;
    private static final int INDEX_METADATA = 100;
    private static final int OBJECT_STORE_NAMES = 200;
    // Object store metadata types
    private static final int STORE_NAME = 0;
    private static final int STORE_KEY_PATH = 1;
    private static final int STORE_AUTO_INCREMENT = 2;
    private static final int STORE_EVICTABLE = 3;
    private static final int STORE_LAST_VERSION = 4;
    private static final int STORE_MAX_INDEX_ID = 5;
    private static final int STORE_HAS_KEY_PATH = 6;
    private static final int STORE_KEY_GENERATOR = 7;
    // Index metadata types
    private static final int INDEX_NAME = 0;
    private static final int INDEX_UNIQUE = 1;
    private static final int INDEX_KEY_PATH = 2;
    private static final int INDEX_MULTI_ENTRY = 3;
    // kMinimumIndexId, the largest index id of a store without indexes
    private static final int MINIMUM_INDEX_ID = 30;
    // kBlobNumberGeneratorInitialNumber; 1 stands for all of a database's blobs in a journal
    private static final long FIRST_BLOB_NUMBER = 2;
    // The last version of a store without records
    private static final long INITIAL_LAST_VERSION = 1;
    // kIDBWrapThreshold: values from this size are compressed, and values past it moved into a blob
    private static final int WRAP_THRESHOLD = 64 * 1024;
    private static final String WRAPPER_TYPE = "application/vnd.blink-idb-value-wrapper";
    // IndexedDBExternalObject::ObjectType
    private static final int OBJECT_BLOB = 0;
    private static final int OBJECT_FILE = 1;
    // base::Time's epoch, 1601-01-01, which file times count from
    private static final Instant WINDOWS_EPOCH = Instant.parse("1601-01-01T00:00:00Z");

    private final LevelDbBuilder builder;
    private final Path blobDirectory;
    private final ValueSerializer serializer;
    /// The data format of the values written so far.
    private final IdbDataFormat format = new IdbDataFormat();

    private LevelDbIndexedDbWriter(LevelDbBuilder builder, Path blobDirectory, ValueSerializer serializer) {
        this.builder = builder;
        this.blobDirectory = blobDirectory;
        this.serializer = serializer;
    }

    /// Writes `databases` as an IndexedDB store of `origin`: the LevelDB database in `levelDbDirectory`, and the blobs
    /// in `blobDirectory`, which is created only if a record has a blob or file to store.
    ///
    /// Each database's records are read from it one at a time, as they are written. Either directory may exist if it
    /// is empty. A write that fails leaves no database in `levelDbDirectory`, but may leave blob files in
    /// `blobDirectory`.
    ///
    /// @param origin     the origin whose identifier the database names are recorded under
    /// @param serializer serializes each record's value, as a page's would be serialized
    /// @return the data format of the values written
    /// @throws IllegalArgumentException   if two databases share a name, a store's records are not in key order, two
    ///                                    records share a key in a unique index, or a value cannot be stored
    /// @throws DirectoryNotEmptyException if either directory exists and holds any file
    /// @throws ViridianException          if a blob or file in a value has no bytes, or supplies other than its size in
    ///                                    bytes
    /// @throws IOException                if a record or a blob's bytes cannot be read, or a file cannot be written
    public static IdbDataFormat write(Path levelDbDirectory, Path blobDirectory, StorageOrigin origin, List<? extends DatabaseSource> databases,
                                      ValueSerializer serializer) throws IOException {
        Objects.requireNonNull(levelDbDirectory, "levelDbDirectory");
        Objects.requireNonNull(blobDirectory, "blobDirectory");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(serializer, "serializer");
        IdbWriterSupport.requireDistinctNames(databases);
        if (Files.isDirectory(blobDirectory)) {
            try (var files = Files.list(blobDirectory)) {
                if (files.findAny().isPresent()) {
                    throw new DirectoryNotEmptyException(blobDirectory.toString());
                }
            }
        }
        try (var builder = LevelDbBuilder.create(levelDbDirectory, COMPARATOR)) {
            var writer = new LevelDbIndexedDbWriter(builder, blobDirectory, serializer);
            writer.writeGlobalMetadata(origin, databases);
            for (var i = 0; i < databases.size(); i++) {
                writer.writeDatabase(i + 1, databases.get(i));
            }
            writer.writeDataVersion();
            builder.finish();
            return writer.format;
        }
    }

    private void writeGlobalMetadata(StorageOrigin origin, List<? extends DatabaseSource> databases) throws IOException {
        var global = LevelDbKeys.encode(0, 0, 0);
        put(key(global, SCHEMA_VERSION_TYPE), integer(SCHEMA_VERSION));
        put(key(global, MAX_DATABASE_ID), integer(databases.size()));
        put(key(global, RECOVERY_BLOB_JOURNAL), new byte[0]);
        put(key(global, ACTIVE_BLOB_JOURNAL), new byte[0]);
        put(key(global, SCOPES_PREFIX, SCOPES_GLOBAL_METADATA), SCOPES_METADATA);
        for (var i = 0; i < databases.size(); i++) {
            var name = new ByteArrayOutputStream();
            name.writeBytes(global);
            name.write(DATABASE_NAME);
            writeStringWithLength(name, origin.identifier() + ORIGIN_SUFFIX);
            writeStringWithLength(name, databases.get(i).name());
            put(name.toByteArray(), integer(i + 1));
        }
    }

    private void writeDatabase(long databaseId, DatabaseSource database) throws IOException {
        var metadata = LevelDbKeys.encode(databaseId, 0, 0);
        put(key(metadata, MAX_OBJECT_STORE_ID), integer(database.objectStores().size()));
        put(key(metadata, USER_VERSION), varint(database.version()));
        var nextBlob = new long[]{FIRST_BLOB_NUMBER};
        for (var i = 0; i < database.objectStores().size(); i++) {
            writeObjectStore(databaseId, i + 1, database, database.objectStores().get(i), nextBlob);
        }
        put(key(metadata, BLOB_NUMBER_GENERATOR), varint(nextBlob[0]));
    }

    private void writeObjectStore(long databaseId, long storeId, DatabaseSource database, IdbObjectStore store, long[] nextBlob) throws IOException {
        var metadata = LevelDbKeys.encode(databaseId, 0, 0);
        var keyPath = store.keyPath().orElse(null);
        put(storeMetadataKey(metadata, storeId, STORE_NAME), string(store.name()));
        put(storeMetadataKey(metadata, storeId, STORE_KEY_PATH), keyPath(keyPath));
        put(storeMetadataKey(metadata, storeId, STORE_AUTO_INCREMENT), integer(store.autoIncrement() ? 1 : 0));
        put(storeMetadataKey(metadata, storeId, STORE_EVICTABLE), integer(0));
        put(storeMetadataKey(metadata, storeId, STORE_MAX_INDEX_ID), integer(MINIMUM_INDEX_ID + store.indexes().size()));
        put(storeMetadataKey(metadata, storeId, STORE_HAS_KEY_PATH), integer(keyPath == null ? 0 : 1));
        var namesKey = new ByteArrayOutputStream();
        namesKey.writeBytes(metadata);
        namesKey.write(OBJECT_STORE_NAMES);
        writeStringWithLength(namesKey, store.name());
        put(namesKey.toByteArray(), integer(storeId));

        for (var i = 0; i < store.indexes().size(); i++) {
            var index = store.indexes().get(i);
            var indexId = MINIMUM_INDEX_ID + 1 + i;
            put(indexMetadataKey(metadata, storeId, indexId, INDEX_NAME), string(index.name()));
            put(indexMetadataKey(metadata, storeId, indexId, INDEX_UNIQUE), integer(index.unique() ? 1 : 0));
            put(indexMetadataKey(metadata, storeId, indexId, INDEX_KEY_PATH), keyPath(index.keyPath()));
            put(indexMetadataKey(metadata, storeId, indexId, INDEX_MULTI_ENTRY), integer(index.multiEntry() ? 1 : 0));
        }
        var indexKeys = new IdbWriterSupport.IndexKeys(store);
        var order = new IdbWriterSupport.RecordOrder(store);

        var dataPrefix = LevelDbKeys.encode(databaseId, storeId, LevelDbKeys.OBJECT_STORE_DATA);
        var existsPrefix = LevelDbKeys.encode(databaseId, storeId, LevelDbKeys.EXISTS_ENTRY);
        var blobPrefix = LevelDbKeys.encode(databaseId, storeId, LevelDbKeys.BLOB_ENTRY);
        try (var records = database.records(store)) {
            while (records.next()) {
                var key = records.key();
                order.note(key);
                writeRecord(databaseId, storeId, store, INITIAL_LAST_VERSION + order.count(), key, records.value(), indexKeys,
                        dataPrefix, existsPrefix, blobPrefix, nextBlob);
            }
        }
        put(storeMetadataKey(metadata, storeId, STORE_LAST_VERSION), integer(INITIAL_LAST_VERSION + order.count()));
        put(storeMetadataKey(metadata, storeId, STORE_KEY_GENERATOR), integer(order.keyGenerator(database)));
    }

    /// Writes a record at `version`: its value, its existence entry, its blob entry and its index entries.
    private void writeRecord(long databaseId, long storeId, IdbObjectStore store, long version, IdbKey key, JsValue value,
                             IdbWriterSupport.IndexKeys indexKeys, byte[] dataPrefix, byte[] existsPrefix, byte[] blobPrefix,
                             long[] nextBlob) throws IOException {
        var primaryKey = IdbKeyCodec.encodeLevelDb(key);
        var serialized = serializer.serialize(value);
        IdbWriterSupport.requireBytes(serialized, key, store);
        format.note(serialized.bytes());
        var blobs = new ArrayList<>(serialized.blobs());
        var stored = storedValue(serialized.bytes(), blobs);

        var data = new ByteArrayOutputStream(stored.length + 10);
        IdbKeyCodec.writeVarint(data, version);
        data.writeBytes(stored);
        put(concat(dataPrefix, primaryKey), data.toByteArray());
        put(concat(existsPrefix, primaryKey), integer(version));
        if (!blobs.isEmpty()) {
            put(concat(blobPrefix, primaryKey), externalObjects(databaseId, blobs, nextBlob));
        }

        var indexValue = new ByteArrayOutputStream();
        IdbKeyCodec.writeVarint(indexValue, version);
        indexValue.writeBytes(primaryKey);
        for (var i = 0; i < store.indexes().size(); i++) {
            var indexPrefix = LevelDbKeys.encode(databaseId, storeId, MINIMUM_INDEX_ID + 1 + i);
            for (var indexKey : indexKeys.of(i, key, value)) {
                var entry = new ByteArrayOutputStream();
                entry.writeBytes(indexPrefix);
                entry.writeBytes(IdbKeyCodec.encodeLevelDb(indexKey));
                // The obsolete sequence number, always 0
                IdbKeyCodec.writeVarint(entry, 0);
                entry.writeBytes(primaryKey);
                put(entry.toByteArray(), indexValue.toByteArray());
            }
        }
    }

    /// Records the data-format version: the newest Blink and V8 versions among the values written, so that a Chrome
    /// that reads every one of them accepts the store; Chrome refuses a store recorded as newer than it is.
    private void writeDataVersion() throws IOException {
        put(key(LevelDbKeys.encode(0, 0, 0), DATA_VERSION_TYPE), integer(format.encoded()));
    }

    /// {@return the bytes stored for a serialized value, compressed or moved into a blob it appends to `blobs`}
    ///
    /// A value moved into a blob is stored as the blob's size and its index among `blobs`, the last.
    private static byte[] storedValue(byte[] value, List<JsBlob> blobs) {
        var stored = value;
        if (value.length >= WRAP_THRESHOLD) {
            var compressed = Snappy.compress(value, 0, value.length);
            if (compressed.length <= value.length * 0.9) {
                stored = new byte[3 + compressed.length];
                stored[0] = (byte) StoredRecord.VERSION_TAG;
                stored[1] = StoredRecord.WRAPPED;
                stored[2] = StoredRecord.SNAPPY;
                System.arraycopy(compressed, 0, stored, 3, compressed.length);
            }
        }
        if (stored.length <= WRAP_THRESHOLD) {
            return stored;
        }
        var contents = stored;
        BlobSource source = () -> new ByteArrayInputStream(contents);
        blobs.add(ValueAccess.get().blob(WRAPPER_TYPE, contents.length, source));
        var wrapper = new ByteArrayOutputStream();
        wrapper.write(StoredRecord.VERSION_TAG);
        wrapper.write(StoredRecord.WRAPPED);
        wrapper.write(StoredRecord.IN_BLOB);
        IdbKeyCodec.writeVarint(wrapper, contents.length);
        IdbKeyCodec.writeVarint(wrapper, blobs.size() - 1);
        return wrapper.toByteArray();
    }

    /// Writes the files of a record's blobs and files, numbered from `nextBlob`, which it advances.
    ///
    /// @return the record's blob entry
    private byte[] externalObjects(long databaseId, List<JsBlob> blobs, long[] nextBlob) throws IOException {
        var output = new ByteArrayOutputStream();
        for (var blob : blobs) {
            var number = nextBlob[0]++;
            switch (blob) {
                case JsFile file -> {
                    var modified = file.lastModified().orElse(null);
                    var size = writeBlobFile(databaseId, number, file, modified);
                    output.write(OBJECT_FILE);
                    IdbKeyCodec.writeVarint(output, number);
                    writeStringWithLength(output, file.type());
                    IdbKeyCodec.writeVarint(output, size);
                    writeStringWithLength(output, file.name());
                    IdbKeyCodec.writeVarint(output, windowsMicros(modified));
                }
                case JsBlob plain -> {
                    var size = writeBlobFile(databaseId, number, plain, null);
                    output.write(OBJECT_BLOB);
                    IdbKeyCodec.writeVarint(output, number);
                    writeStringWithLength(output, plain.type());
                    IdbKeyCodec.writeVarint(output, size);
                }
            }
        }
        return output.toByteArray();
    }

    /// Writes the file of blob `number`, with the modification time `modified` if not `null`, unless it is empty.
    ///
    /// @return the blob's size
    private long writeBlobFile(long databaseId, long number, JsBlob blob, Instant modified) throws IOException {
        var size = blob.size();
        if (size == 0) {
            return 0;
        }
        var directory = blobDirectory.resolve(Long.toHexString(databaseId)).resolve(String.format("%02x", (number >>> 8) & 0xFF));
        var file = directory.resolve(Long.toHexString(number));
        Files.createDirectories(directory);
        long written;
        try (InputStream input = blob.open();
             var output = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            written = input.transferTo(output);
        }
        if (size >= 0 && written != size) {
            throw new ViridianException(ViridianException.Reason.CORRUPTED, "The source of blob " + number + " supplied " + written
                    + " bytes instead of its " + size);
        }
        if (written == 0) {
            Files.delete(file);
        } else if (modified != null) {
            Files.setLastModifiedTime(file, FileTime.from(modified));
        }
        return written;
    }

    private void put(byte[] key, byte[] value) throws IOException {
        builder.put(key, value);
    }

    /// {@return a file time as Chromium stores it: microseconds since 1601, 0 for none or for one before 1601}
    private static long windowsMicros(Instant time) {
        if (time == null || time.isBefore(WINDOWS_EPOCH)) {
            return 0;
        }
        return WINDOWS_EPOCH.until(time, ChronoUnit.MICROS);
    }

    private static byte[] storeMetadataKey(byte[] metadata, long storeId, int type) {
        var output = new ByteArrayOutputStream();
        output.writeBytes(metadata);
        output.write(OBJECT_STORE_METADATA);
        IdbKeyCodec.writeVarint(output, storeId);
        output.write(type);
        return output.toByteArray();
    }

    private static byte[] indexMetadataKey(byte[] metadata, long storeId, long indexId, int type) {
        var output = new ByteArrayOutputStream();
        output.writeBytes(metadata);
        output.write(INDEX_METADATA);
        IdbKeyCodec.writeVarint(output, storeId);
        IdbKeyCodec.writeVarint(output, indexId);
        output.write(type);
        return output.toByteArray();
    }

    /// {@return a key path encoded with its type: `00 00`, then 0 for none, 1 and a string, or 2 and a count of
    /// strings}
    private static byte[] keyPath(IdbKeyPath keyPath) {
        var output = new ByteArrayOutputStream();
        output.write(0);
        output.write(0);
        switch (keyPath) {
            case null -> output.write(0);
            case IdbKeyPath.Single single -> {
                output.write(1);
                writeStringWithLength(output, single.path());
            }
            case IdbKeyPath.Compound compound -> {
                output.write(2);
                IdbKeyCodec.writeVarint(output, compound.paths().size());
                for (var path : compound.paths()) {
                    writeStringWithLength(output, path);
                }
            }
        }
        return output.toByteArray();
    }

    /// {@return `value` as Chromium's `EncodeInt` writes it: little-endian, in as few bytes as hold it, at least one}
    private static byte[] integer(long value) {
        var bytes = 1;
        while (bytes < 8 && (value >>> (8 * bytes)) != 0) {
            bytes++;
        }
        var result = new byte[bytes];
        for (var i = 0; i < bytes; i++) {
            result[i] = (byte) (value >>> (8 * i));
        }
        return result;
    }

    private static byte[] varint(long value) {
        var output = new ByteArrayOutputStream();
        IdbKeyCodec.writeVarint(output, value);
        return output.toByteArray();
    }

    /// {@return the big-endian UTF-16 code units of `value`, without a length}
    private static byte[] string(String value) {
        var result = new byte[value.length() * 2];
        for (var i = 0; i < value.length(); i++) {
            result[2 * i] = (byte) (value.charAt(i) >>> 8);
            result[2 * i + 1] = (byte) value.charAt(i);
        }
        return result;
    }

    private static void writeStringWithLength(ByteArrayOutputStream output, String value) {
        IdbKeyCodec.writeVarint(output, value.length());
        output.writeBytes(string(value));
    }

    private static byte[] key(byte[] prefix, int... types) {
        var result = new byte[prefix.length + types.length];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        for (var i = 0; i < types.length; i++) {
            result[prefix.length + i] = (byte) types[i];
        }
        return result;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        var result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
