package com.github.auties00.viridian.indexeddb;

import com.github.auties00.viridian.IndexedDatabase;
import com.github.auties00.viridian.StorageBackend;
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

/// The `IndexedDB` directory of a profile: which origins have IndexedDB data, in which backend, and where.
///
/// An origin's LevelDB store is `<identifier>.indexeddb.leveldb`, with its blobs in `<identifier>.indexeddb.blob`;
/// its SQLite databases are files of a directory named by the identifier alone, each named by the Base32 of the
/// SHA-256 of the database name's UTF-16 code units, or `0` for the empty name. Chromium keeps an origin whose LevelDB
/// store exists on LevelDB, and this reader does the same unless asked for a backend.
public final class IndexedDbDirectory {
    private static final String LEVELDB_SUFFIX = ".indexeddb.leveldb";
    private static final String BLOB_SUFFIX = ".indexeddb.blob";
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private final Path root;

    /// Creates the view of `root`, the profile's `IndexedDB` directory, which need not exist.
    public IndexedDbDirectory(Path root) {
        this.root = root;
    }

    /// {@return the origins with IndexedDB data, in either backend}
    ///
    /// @throws IOException if the directory cannot be listed
    public List<StorageOrigin> origins() throws IOException {
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
                // Not an origin's storage: a bucket directory, or a name this reader does not know
            }
        }
        return origins;
    }

    /// {@return the backend that holds `origin`'s data, or an empty optional if it has none}
    ///
    /// @throws IOException if the directory cannot be listed
    public Optional<StorageBackend> backend(StorageOrigin origin) throws IOException {
        if (Files.isDirectory(levelDbDirectory(origin))) {
            return Optional.of(StorageBackend.LEVELDB);
        }
        var sqlite = root.resolve(origin.identifier());
        return Files.isDirectory(sqlite) && !sqliteFiles(sqlite).isEmpty() ? Optional.of(StorageBackend.SQLITE) : Optional.empty();
    }

    /// {@return the names of `origin`'s databases in `backend`, sorted}
    ///
    /// @throws IOException if the files cannot be read, or the metadata does not decode
    public List<String> names(StorageOrigin origin, StorageBackend backend) throws IOException {
        var names = new TreeSet<String>();
        switch (backend) {
            case LEVELDB -> {
                if (Files.isDirectory(levelDbDirectory(origin))) {
                    for (var database : LevelDbIndexedDatabase.openAll(levelDbDirectory(origin), blobDirectory(origin), origin)) {
                        names.add(database.name());
                    }
                }
            }
            case SQLITE -> {
                var directory = root.resolve(origin.identifier());
                if (Files.isDirectory(directory)) {
                    for (var file : sqliteFiles(directory)) {
                        try (var database = SqliteIndexedDatabase.open(file, origin)) {
                            if (database.version() > 0) {
                                names.add(database.name());
                            }
                        }
                    }
                }
            }
        }
        return List.copyOf(names);
    }

    /// {@return the database `name` of `origin` in `backend`, open, or an empty optional if there is none}
    ///
    /// @throws IOException if the files cannot be read, or the metadata does not decode
    public Optional<IndexedDatabase> open(StorageOrigin origin, String name, StorageBackend backend) throws IOException {
        return switch (backend) {
            case LEVELDB -> {
                if (!Files.isDirectory(levelDbDirectory(origin))) {
                    yield Optional.empty();
                }
                for (var database : LevelDbIndexedDatabase.openAll(levelDbDirectory(origin), blobDirectory(origin), origin)) {
                    if (database.name().equals(name)) {
                        yield Optional.of(database);
                    }
                }
                yield Optional.empty();
            }
            case SQLITE -> {
                var file = root.resolve(origin.identifier()).resolve(sqliteFileName(name));
                if (!Files.isRegularFile(file)) {
                    yield Optional.empty();
                }
                var database = SqliteIndexedDatabase.open(file, origin);
                if (!database.name().equals(name)) {
                    database.close();
                    yield Optional.empty();
                }
                yield Optional.of(database);
            }
        };
    }

    private Path levelDbDirectory(StorageOrigin origin) {
        return root.resolve(origin.identifier() + LEVELDB_SUFFIX);
    }

    private Path blobDirectory(StorageOrigin origin) {
        return root.resolve(origin.identifier() + BLOB_SUFFIX);
    }

    /// {@return the database files of an origin's SQLite directory, sidecars and blob directories left out}
    private static List<Path> sqliteFiles(Path directory) throws IOException {
        var files = new TreeMap<String, Path>();
        try (var entries = Files.list(directory)) {
            for (var entry : (Iterable<Path>) entries::iterator) {
                var name = entry.getFileName().toString();
                if (Files.isRegularFile(entry) && !name.endsWith("-wal") && !name.endsWith("-shm") && !name.endsWith("-journal")
                        && (name.equals("0") || isBase32Digest(name))) {
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
