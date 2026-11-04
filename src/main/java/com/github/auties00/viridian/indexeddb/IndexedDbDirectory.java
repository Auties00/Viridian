package com.github.auties00.viridian.indexeddb;

import com.github.auties00.viridian.IdbDatabaseReader;
import com.github.auties00.viridian.StorageBackend;
import com.github.auties00.viridian.StorageBucket;
import com.github.auties00.viridian.StorageOrigin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/// Where a profile keeps IndexedDB data, and what is there.
///
/// The data of one storage bucket is a [Location]: a LevelDB store holding all of the bucket's databases, the
/// directory of that store's blobs, and a directory of SQLite databases, one file per database, each named by the
/// Base32 of the SHA-256 of the database name's UTF-16 code units, or `0` for the empty name. An origin's first-party
/// default bucket keeps them in the profile's `IndexedDB` directory as `<identifier>.indexeddb.leveldb`,
/// `<identifier>.indexeddb.blob` and `<identifier>`; any other bucket in its own directory, as `indexeddb.leveldb`,
/// `indexeddb.blob` and the directory itself. Chromium keeps a bucket whose LevelDB store exists on LevelDB.
public final class IndexedDbDirectory {
    private static final String LEVELDB_SUFFIX = ".indexeddb.leveldb";
    private static final String BLOB_SUFFIX = ".indexeddb.blob";
    private static final String BUCKET_LEVELDB = "indexeddb.leveldb";
    private static final String BUCKET_BLOBS = "indexeddb.blob";
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private IndexedDbDirectory() {
    }

    /// Where the IndexedDB data of one storage bucket is.
    ///
    /// @param levelDb the LevelDB store
    /// @param blobs   the directory of the LevelDB store's blobs
    /// @param sqlite  the directory of the SQLite databases, which may hold the other two
    public record Location(Path levelDb, Path blobs, Path sqlite) {
        /// {@return the location of the first-party default bucket of `origin` in `root`, a profile's `IndexedDB`
        /// directory}
        public static Location legacy(Path root, StorageOrigin origin) {
            return new Location(root.resolve(origin.identifier() + LEVELDB_SUFFIX), root.resolve(origin.identifier() + BLOB_SUFFIX),
                    root.resolve(origin.identifier()));
        }

        /// {@return the location of a bucket kept in `directory`, its `IndexedDB` directory under `WebStorage`}
        public static Location bucket(Path directory) {
            return new Location(directory.resolve(BUCKET_LEVELDB), directory.resolve(BUCKET_BLOBS), directory);
        }

        /// {@return the location with every path relative to `profile`}
        public Location relativeTo(Path profile) {
            return new Location(profile.relativize(levelDb), profile.relativize(blobs), profile.relativize(sqlite));
        }

        /// {@return whether `backend` holds data here: a LevelDB store, or at least one SQLite database}
        public boolean has(StorageBackend backend) throws IOException {
            return switch (backend) {
                case LEVELDB -> Files.isDirectory(levelDb);
                case SQLITE -> !sqliteFiles(sqlite).isEmpty();
            };
        }

        /// {@return the backend Chromium reads here: LevelDB when its store exists, else SQLite when it has a database,
        /// else none}
        public Optional<StorageBackend> backend() throws IOException {
            if (has(StorageBackend.LEVELDB)) {
                return Optional.of(StorageBackend.LEVELDB);
            }
            return has(StorageBackend.SQLITE) ? Optional.of(StorageBackend.SQLITE) : Optional.empty();
        }

        /// {@return the SQLite file of the database `name`}
        public Path sqliteFile(String name) {
            return sqlite.resolve(sqliteFileName(name));
        }
    }

    /// {@return the origins whose first-party default bucket has IndexedDB data in `root`, a profile's `IndexedDB`
    /// directory, in either backend}
    ///
    /// @throws IOException if the directory cannot be listed
    public static List<StorageOrigin> origins(Path root) throws IOException {
        var identifiers = new TreeSet<String>();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var entries = Files.list(root)) {
            for (var entry : (Iterable<Path>) entries::iterator) {
                var name = entry.getFileName().toString();
                if (name.endsWith(LEVELDB_SUFFIX) && Files.isDirectory(entry)) {
                    identifiers.add(name.substring(0, name.length() - LEVELDB_SUFFIX.length()));
                } else if (!name.endsWith(BLOB_SUFFIX) && Files.isDirectory(entry) && !sqliteFiles(entry).isEmpty()) {
                    identifiers.add(name);
                }
            }
        }
        var origins = new ArrayList<StorageOrigin>();
        for (var identifier : identifiers) {
            try {
                origins.add(StorageOrigin.ofIdentifier(identifier));
            } catch (IllegalArgumentException _) {
                // Not an origin's storage, or a name this reader does not know
            }
        }
        return origins;
    }

    /// {@return the databases at `location` in `backend`, open, by name; the caller closes them}
    ///
    /// A database a page created but failed to open is left out, as Chromium leaves it out.
    ///
    /// @param bucket the bucket the databases belong to
    /// @throws IOException if the files cannot be read, or the metadata does not decode
    public static List<IdbDatabaseReader> openAll(Location location, StorageBucket bucket, StorageBackend backend) throws IOException {
        var readers = new TreeMap<String, IdbDatabaseReader>();
        try {
            switch (backend) {
                case LEVELDB -> {
                    if (Files.isDirectory(location.levelDb())) {
                        for (var database : LevelDbDatabaseReader.openAll(location.levelDb(), location.blobs(), bucket)) {
                            readers.put(database.name(), database);
                        }
                    }
                }
                case SQLITE -> {
                    for (var file : sqliteFiles(location.sqlite())) {
                        var database = SqliteDatabaseReader.open(file, bucket);
                        if (database.version() > 0) {
                            readers.put(database.name(), database);
                        } else {
                            database.close();
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException failure) {
            for (var reader : readers.values()) {
                try {
                    reader.close();
                } catch (IOException closing) {
                    failure.addSuppressed(closing);
                }
            }
            throw failure;
        }
        return List.copyOf(readers.values());
    }

    /// {@return the names of the databases at `location` in `backend`, sorted}
    ///
    /// @throws IOException if the files cannot be read, or the metadata does not decode
    public static List<String> names(Location location, StorageBucket bucket, StorageBackend backend) throws IOException {
        var names = new ArrayList<String>();
        for (var reader : openAll(location, bucket, backend)) {
            names.add(reader.name());
            reader.close();
        }
        return List.copyOf(names);
    }

    /// {@return the database `name` at `location` in `backend`, open, or an empty optional if there is none}
    ///
    /// @throws IOException if the files cannot be read, or the metadata does not decode
    public static Optional<IdbDatabaseReader> open(Location location, StorageBucket bucket, String name, StorageBackend backend)
            throws IOException {
        return switch (backend) {
            case LEVELDB -> {
                IdbDatabaseReader found = null;
                for (var database : openAll(location, bucket, backend)) {
                    if (found == null && database.name().equals(name)) {
                        found = database;
                    } else {
                        database.close();
                    }
                }
                yield Optional.ofNullable(found);
            }
            case SQLITE -> {
                var file = location.sqliteFile(name);
                if (!Files.isRegularFile(file)) {
                    yield Optional.empty();
                }
                var database = SqliteDatabaseReader.open(file, bucket);
                if (!database.name().equals(name) || database.version() == 0) {
                    database.close();
                    yield Optional.empty();
                }
                yield Optional.of(database);
            }
        };
    }

    /// {@return the database files of a SQLite directory, sidecars and blob directories left out}
    public static List<Path> sqliteFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        var files = new TreeMap<String, Path>();
        try (var entries = Files.list(directory)) {
            for (var entry : (Iterable<Path>) entries::iterator) {
                var name = entry.getFileName().toString();
                if (Files.isRegularFile(entry) && (name.equals("0") || isBase32Digest(name))) {
                    files.put(name, entry);
                }
            }
        } catch (NoSuchFileException _) {
            return List.of();
        }
        return List.copyOf(files.values());
    }

    /// {@return the file name Chromium gives the SQLite file of the database `name`}
    static String sqliteFileName(String name) {
        if (name.isEmpty()) {
            return "0";
        }
        var bytes = new byte[name.length() * 2];
        for (var i = 0; i < name.length(); i++) {
            bytes[2 * i] = (byte) name.charAt(i);
            bytes[2 * i + 1] = (byte) (name.charAt(i) >>> 8);
        }
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Every Java platform has SHA-256", exception);
        }
        // RFC 4648 Base32, without padding
        var encoded = new StringBuilder();
        var buffer = 0;
        var bits = 0;
        for (var value : digest) {
            buffer = buffer << 8 | (value & 0xFF);
            bits += 8;
            while (bits >= 5) {
                encoded.append(BASE32.charAt((buffer >>> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            encoded.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        }
        return encoded.toString();
    }

    /// {@return whether `name` is the Base32 of a SHA-256 digest: 52 characters of the alphabet}
    private static boolean isBase32Digest(String name) {
        if (name.length() != 52) {
            return false;
        }
        for (var i = 0; i < name.length(); i++) {
            if (BASE32.indexOf(name.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }
}
