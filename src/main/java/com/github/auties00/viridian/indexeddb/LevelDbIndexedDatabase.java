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
import com.github.auties00.viridian.io.ByteCursor;
import com.github.auties00.viridian.leveldb.LevelDb;
import com.github.auties00.viridian.value.BlobSource;
import com.github.auties00.viridian.value.ExternalObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/// An IndexedDB database of Chromium's LevelDB backend, where one LevelDB database holds all of an origin's
/// IndexedDB databases.
///
/// Opening a database reads its metadata; each read of an object store reads the origin's LevelDB files again,
/// keeping only that store's records, which it sorts by key since the backend's key encoding is not byte-ordered.
/// The database holds no file open between calls.
///
/// A record's blobs are files of the origin's blob directory, `<database id>/<second byte of the blob number>/<blob
/// number>` in lowercase hexadecimal.
public final class LevelDbIndexedDatabase implements IndexedDatabase {
    // Global metadata types
    private static final int SCHEMA_VERSION = 0;
    private static final int DATABASE_NAME = 201;
    // Database metadata types
    private static final int USER_VERSION = 4;
    private static final int OBJECT_STORE_METADATA = 50;
    private static final int INDEX_METADATA = 100;
    // Object store metadata types
    private static final int STORE_NAME = 0;
    private static final int STORE_KEY_PATH = 1;
    private static final int STORE_AUTO_INCREMENT = 2;
    private static final int STORE_HAS_KEY_PATH = 6;
    // Index metadata types
    private static final int INDEX_NAME = 0;
    private static final int INDEX_UNIQUE = 1;
    private static final int INDEX_KEY_PATH = 2;
    private static final int INDEX_MULTI_ENTRY = 3;
    // The version a database is left at by a page that failed to open it, which Chromium does not list
    private static final long DEFAULT_VERSION = 0;
    // Schema versions below this one store external objects in the older encoding
    private static final int EXTERNAL_OBJECTS_V4_SCHEMA = 4;
    // base::Time's epoch, 1601-01-01, as Chromium stores file times
    private static final Instant WINDOWS_EPOCH = Instant.parse("1601-01-01T00:00:00Z");

    private final Path directory;
    private final Path blobDirectory;
    private final StorageOrigin origin;
    private final String name;
    private final long databaseId;
    private final long version;
    private final List<IdbObjectStore> objectStores;
    private final boolean legacyExternalObjects;
    private boolean closed;

    private LevelDbIndexedDatabase(Path directory, Path blobDirectory, StorageOrigin origin, String name, long databaseId,
                                   long version, List<IdbObjectStore> objectStores, boolean legacyExternalObjects) {
        this.directory = directory;
        this.blobDirectory = blobDirectory;
        this.origin = origin;
        this.name = name;
        this.databaseId = databaseId;
        this.version = version;
        this.objectStores = objectStores;
        this.legacyExternalObjects = legacyExternalObjects;
    }

    /// {@return every database of the origin in the LevelDB `directory`, by name}
    ///
    /// @param blobDirectory the origin's blob directory, which need not exist
    /// @throws NoSuchFileException if `directory` holds no LevelDB database
    /// @throws IOException         if the files cannot be read, or the metadata does not decode
    public static List<LevelDbIndexedDatabase> openAll(Path directory, Path blobDirectory, StorageOrigin origin) throws IOException {
        var metadata = LevelDb.read(directory, key -> {
            var prefix = LevelDbKeys.decode(key);
            return prefix != null && prefix.isMetadata();
        });
        long schemaVersion = 0;
        var databaseIds = new TreeMap<String, Long>();
        var databaseEntries = new HashMap<Long, List<LevelDb.Entry>>();
        for (var entry : metadata) {
            var prefix = LevelDbKeys.decode(entry.key());
            var input = new ByteCursor(entry.key(), prefix.length(), entry.key().length);
            if (!input.hasRemaining()) {
                continue;
            }
            if (prefix.databaseId() == 0) {
                switch (input.u8()) {
                    case SCHEMA_VERSION -> schemaVersion = littleEndianInt(entry.value());
                    case DATABASE_NAME -> {
                        // The origin, which a LevelDB store of one origin holds for every name, then the name
                        var first = IdbKeyCodec.readUtf16BigEndian(input, input.varintLength());
                        var databaseName = input.hasRemaining() ? IdbKeyCodec.readUtf16BigEndian(input, input.varintLength()) : first;
                        databaseIds.put(databaseName, littleEndianInt(entry.value()));
                    }
                    default -> {
                    }
                }
            } else {
                databaseEntries.computeIfAbsent(prefix.databaseId(), _ -> new ArrayList<>()).add(entry);
            }
        }
        var databases = new ArrayList<LevelDbIndexedDatabase>();
        for (var named : databaseIds.entrySet()) {
            var database = parseDatabase(directory, blobDirectory, origin, named.getKey(), named.getValue(),
                    databaseEntries.getOrDefault(named.getValue(), List.of()), schemaVersion < EXTERNAL_OBJECTS_V4_SCHEMA);
            if (database != null) {
                databases.add(database);
            }
        }
        return databases;
    }

    /// {@return the database the metadata entries describe, or `null` for one a page failed to open}
    private static LevelDbIndexedDatabase parseDatabase(Path directory, Path blobDirectory, StorageOrigin origin, String name,
                                                        long databaseId, List<LevelDb.Entry> entries, boolean legacyExternalObjects)
            throws IOException {
        var version = DEFAULT_VERSION;
        var stores = new TreeMap<Long, StoreBuilder>();
        for (var entry : entries) {
            var prefix = LevelDbKeys.decode(entry.key());
            var input = new ByteCursor(entry.key(), prefix.length(), entry.key().length);
            if (!input.hasRemaining()) {
                continue;
            }
            switch (input.u8()) {
                case USER_VERSION -> version = new ByteCursor(entry.value()).varint64();
                case OBJECT_STORE_METADATA -> {
                    var store = stores.computeIfAbsent(input.varint64(), StoreBuilder::new);
                    if (input.remaining() != 1) {
                        continue;
                    }
                    switch (input.u8()) {
                        case STORE_NAME -> store.name = utf16BigEndian(entry.value());
                        case STORE_KEY_PATH -> store.keyPath = keyPath(entry.value());
                        case STORE_AUTO_INCREMENT -> store.autoIncrement = littleEndianInt(entry.value()) != 0;
                        case STORE_HAS_KEY_PATH -> store.hasKeyPath = entry.value().length > 0 && entry.value()[0] != 0;
                        default -> {
                        }
                    }
                }
                case INDEX_METADATA -> {
                    var store = stores.computeIfAbsent(input.varint64(), StoreBuilder::new);
                    var index = store.indexes.computeIfAbsent(input.varint64(), IndexBuilder::new);
                    if (input.remaining() != 1) {
                        continue;
                    }
                    switch (input.u8()) {
                        case INDEX_NAME -> index.name = utf16BigEndian(entry.value());
                        case INDEX_UNIQUE -> index.unique = entry.value().length > 0 && entry.value()[0] != 0;
                        case INDEX_KEY_PATH -> index.keyPath = keyPath(entry.value());
                        case INDEX_MULTI_ENTRY -> index.multiEntry = entry.value().length > 0 && entry.value()[0] != 0;
                        default -> {
                        }
                    }
                }
                default -> {
                }
            }
        }
        if (version == DEFAULT_VERSION) {
            return null;
        }
        var objectStores = new ArrayList<IdbObjectStore>();
        for (var store : stores.values()) {
            if (store.name != null) {
                objectStores.add(store.build());
            }
        }
        return new LevelDbIndexedDatabase(directory, blobDirectory, origin, name, databaseId, version, List.copyOf(objectStores),
                legacyExternalObjects);
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
        return StorageBackend.LEVELDB;
    }

    @Override
    public List<IdbObjectStore> objectStores() {
        return objectStores;
    }

    @Override
    public Stream<IdbRecord> records(String store) throws IOException {
        ensureOpen();
        var storeId = requireStore(store).id();
        var dataPrefix = LevelDbKeys.encode(databaseId, storeId, LevelDbKeys.OBJECT_STORE_DATA);
        var blobPrefix = LevelDbKeys.encode(databaseId, storeId, LevelDbKeys.BLOB_ENTRY);
        var entries = LevelDb.read(directory, key -> startsWith(key, dataPrefix) || startsWith(key, blobPrefix));
        var blobEntries = new HashMap<KeyBytes, byte[]>();
        for (var entry : entries) {
            if (entry.startsWith(blobPrefix)) {
                blobEntries.put(new KeyBytes(Arrays.copyOfRange(entry.key(), blobPrefix.length, entry.key().length)), entry.value());
            }
        }
        var records = new ArrayList<StoredRecord>();
        for (var entry : entries) {
            if (entry.startsWith(dataPrefix)) {
                var encodedKey = Arrays.copyOfRange(entry.key(), dataPrefix.length, entry.key().length);
                records.add(record(encodedKey, entry.value(), blobEntries.get(new KeyBytes(encodedKey))));
            }
        }
        records.sort(Comparator.comparing(StoredRecord::key));
        return records.stream().map(IdbRecord.class::cast);
    }

    @Override
    public Optional<IdbRecord> record(String store, IdbKey key) throws IOException {
        ensureOpen();
        var storeId = requireStore(store).id();
        var encodedKey = IdbKeyCodec.encodeLevelDb(key);
        var dataKey = concat(LevelDbKeys.encode(databaseId, storeId, LevelDbKeys.OBJECT_STORE_DATA), encodedKey);
        var blobKey = concat(LevelDbKeys.encode(databaseId, storeId, LevelDbKeys.BLOB_ENTRY), encodedKey);
        var entries = LevelDb.read(directory, candidate -> Arrays.equals(candidate, dataKey) || Arrays.equals(candidate, blobKey));
        byte[] value = null;
        byte[] blobs = null;
        for (var entry : entries) {
            if (Arrays.equals(entry.key(), dataKey)) {
                value = entry.value();
            } else {
                blobs = entry.value();
            }
        }
        return value == null ? Optional.empty() : Optional.of(record(encodedKey, value, blobs));
    }

    /// {@return the record of an encoded key, its stored value and its blob entry, if any}
    private StoredRecord record(byte[] encodedKey, byte[] value, byte[] blobEntry) throws IOException {
        IdbKey key;
        try {
            key = IdbKeyCodec.decodeLevelDb(new ByteCursor(encodedKey));
        } catch (IOException exception) {
            throw new ViridianException(ViridianException.Reason.CORRUPTED, "A record key of " + name + " does not decode", exception);
        }
        var input = new ByteCursor(value);
        // The record's version, which Chromium uses to match index entries to it
        input.varint64();
        var externals = blobEntry == null ? List.<ExternalObject>of() : externalObjects(blobEntry);
        return new StoredRecord(key, externals, value, input.position());
    }

    /// {@return the external objects a blob entry lists}
    ///
    /// From schema version 4 an object is a type byte (0 blob, 1 file, 2 File System Access handle), then for a blob
    /// or file a varint blob number, the MIME type as a length-prefixed UTF-16BE string and a varint size, and for a
    /// file its name the same way and a varint modification time in microseconds since 1601; for a handle, its
    /// varint-prefixed token. Before, an object was a file flag byte, a blob number, a type, then a name for a file
    /// or a size for a blob.
    private List<ExternalObject> externalObjects(byte[] blobEntry) throws IOException {
        var input = new ByteCursor(blobEntry);
        var objects = new ArrayList<ExternalObject>();
        while (input.hasRemaining()) {
            if (legacyExternalObjects) {
                var isFile = input.u8() != 0;
                var blobNumber = input.varint64();
                var type = IdbKeyCodec.readUtf16BigEndian(input, input.varintLength());
                if (isFile) {
                    var fileName = IdbKeyCodec.readUtf16BigEndian(input, input.varintLength());
                    objects.add(new ExternalObject.File(fileName, type, -1, null, blobFile(blobNumber)));
                } else {
                    objects.add(new ExternalObject.Blob(type, input.varint64(), blobFile(blobNumber)));
                }
                continue;
            }
            var objectType = input.u8();
            switch (objectType) {
                case 0, 1 -> {
                    var blobNumber = input.varint64();
                    var type = IdbKeyCodec.readUtf16BigEndian(input, input.varintLength());
                    var size = input.varint64();
                    if (objectType == 0) {
                        objects.add(new ExternalObject.Blob(type, size, blobFile(blobNumber)));
                    } else {
                        var fileName = IdbKeyCodec.readUtf16BigEndian(input, input.varintLength());
                        var modified = WINDOWS_EPOCH.plus(input.varint64(), ChronoUnit.MICROS);
                        objects.add(new ExternalObject.File(fileName, type, size, modified, blobFile(blobNumber)));
                    }
                }
                case 2 -> objects.add(new ExternalObject.FileSystemAccessHandle(input.lengthPrefixed()));
                default -> throw new ViridianException(ViridianException.Reason.CORRUPTED, "A blob entry of " + name + " has object type " + objectType);
            }
        }
        return objects;
    }

    /// {@return where the bytes of blob `blobNumber` are}
    private BlobSource blobFile(long blobNumber) {
        var file = blobDirectory.resolve(Long.toHexString(databaseId))
                .resolve(String.format("%02x", (blobNumber >>> 8) & 0xFF))
                .resolve(Long.toHexString(blobNumber));
        return () -> {
            try {
                return Files.newInputStream(file);
            } catch (NoSuchFileException exception) {
                throw new ViridianException(ViridianException.Reason.MISSING, "The file of blob " + blobNumber + " of " + name + " is gone: " + file, exception);
            }
        };
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
    public void close() {
        closed = true;
    }

    @Override
    public String toString() {
        return "IndexedDatabase[" + origin + ", " + name + ", LevelDB]";
    }

    /// {@return the key path an encoded key path holds}
    ///
    /// A typed key path is `00 00` and a type byte: 0 none, 1 a string with its length, 2 a varint count of strings.
    /// Anything else is a legacy key path, the whole value a UTF-16BE string.
    private static IdbKeyPath keyPath(byte[] value) throws IOException {
        if (value.length < 3 || value[0] != 0 || value[1] != 0) {
            return new IdbKeyPath.Single(utf16BigEndian(value));
        }
        var input = new ByteCursor(value, 3, value.length);
        return switch (value[2]) {
            case 0 -> null;
            case 1 -> new IdbKeyPath.Single(IdbKeyCodec.readUtf16BigEndian(input, input.varintLength()));
            case 2 -> {
                var count = input.varintLength();
                var paths = new ArrayList<String>(Math.min(count, 64));
                for (var i = 0; i < count; i++) {
                    paths.add(IdbKeyCodec.readUtf16BigEndian(input, input.varintLength()));
                }
                yield new IdbKeyPath.Compound(paths);
            }
            default -> throw new IOException("A key path has type " + value[2]);
        };
    }

    private static String utf16BigEndian(byte[] value) throws IOException {
        if (value.length % 2 != 0) {
            throw new IOException("A UTF-16 string has an odd length of " + value.length + " bytes");
        }
        return IdbKeyCodec.readUtf16BigEndian(new ByteCursor(value), value.length / 2);
    }

    /// {@return the little-endian integer Chromium's `EncodeInt` writes, in as few bytes as it needs}
    private static long littleEndianInt(byte[] value) throws IOException {
        if (value.length < 1 || value.length > 8) {
            throw new IOException("An integer of " + value.length + " bytes");
        }
        return LevelDbKeys.littleEndian(value, 0, value.length);
    }

    private static boolean startsWith(byte[] key, byte[] prefix) {
        return key.length >= prefix.length && Arrays.equals(key, 0, prefix.length, prefix, 0, prefix.length);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        var result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    /// An object store as its metadata entries describe it, read in any order.
    private static final class StoreBuilder {
        private final long id;
        private final TreeMap<Long, IndexBuilder> indexes = new TreeMap<>();
        private String name;
        private IdbKeyPath keyPath;
        private Boolean hasKeyPath;
        private boolean autoIncrement;

        StoreBuilder(long id) {
            this.id = id;
        }

        IdbObjectStore build() {
            var indexList = new ArrayList<IdbIndex>();
            for (var index : indexes.values()) {
                if (index.name != null && index.keyPath != null) {
                    indexList.add(new IdbIndex(index.id, index.name, index.keyPath, index.unique, index.multiEntry));
                }
            }
            // Stores of old schemas have no flag, and a key path that decodes to none
            var path = Boolean.FALSE.equals(hasKeyPath) ? null : keyPath;
            return new IdbObjectStore(id, name, path, autoIncrement, indexList);
        }
    }

    /// An index as its metadata entries describe it, read in any order.
    private static final class IndexBuilder {
        private final long id;
        private String name;
        private IdbKeyPath keyPath;
        private boolean unique;
        private boolean multiEntry;

        IndexBuilder(long id) {
            this.id = id;
        }
    }

    /// An encoded key compared by content.
    private record KeyBytes(byte[] bytes) {
        @Override
        public boolean equals(Object other) {
            return other instanceof KeyBytes key && Arrays.equals(bytes, key.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }
    }
}
