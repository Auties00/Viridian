package com.github.auties00.viridian;

import com.github.auties00.viridian.domstorage.LevelDbDomStorage;
import com.github.auties00.viridian.domstorage.SqliteDomStorage;
import com.github.auties00.viridian.indexeddb.IndexedDbDirectory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/// A profile of a Chromium-based browser or app: the directory that holds its sites' storage.
///
/// {@snippet lang = java :
/// var profile = ChromiumProfile.open(Path.of(System.getenv("LOCALAPPDATA"), "Google", "Chrome", "User Data", "Default"));
/// var origin = StorageOrigin.parse("https://example.com");
/// try (var database = profile.openIndexedDb(origin, "app").orElseThrow();
///      var records = database.records("settings")) {
///     records.forEach(record -> System.out.println(record.key()));
/// }
/// }
///
/// A browser's profile is a directory such as `User Data/Default` in Chrome's user data directory; an Electron app's
/// is its `userData` directory, and a WebView2 app's its `EBWebView/Default` directory.
///
/// # Consistency
/// Viridian reads the files as they are, without the locks the browser takes. A profile the browser has closed reads
/// as the browser left it. One the browser is writing gives a best-effort view, which may lack the latest writes, or
/// fail to read when a file changes under the reader.
///
/// A profile holds no resource of its own; an [IndexedDatabase] may, until it is closed.
public final class ChromiumProfile {
    private final Path directory;
    private final IndexedDbDirectory indexedDb;

    private ChromiumProfile(Path directory) {
        this.directory = directory;
        this.indexedDb = new IndexedDbDirectory(directory.resolve("IndexedDB"));
    }

    /// {@return the profile in `directory`}
    ///
    /// @throws NoSuchFileException if `directory` is not a directory
    public static ChromiumProfile open(Path directory) throws IOException {
        Objects.requireNonNull(directory, "directory");
        if (!Files.isDirectory(directory)) {
            throw new NoSuchFileException(directory.toString(), null, "Not a directory");
        }
        return new ChromiumProfile(directory.toAbsolutePath());
    }

    /// {@return the profile's directory}
    public Path directory() {
        return directory;
    }

    /// {@return the origins with IndexedDB data, in either backend}
    ///
    /// @throws IOException if the profile's `IndexedDB` directory cannot be listed
    public List<StorageOrigin> indexedDbOrigins() throws IOException {
        return indexedDb.origins();
    }

    /// {@return the backend that holds `origin`'s IndexedDB data, or an empty optional if it has none}
    ///
    /// When an origin has data in both backends, the LevelDB one is the origin's, as it is for Chromium, which does not
    /// move an origin off LevelDB until it migrates its data.
    ///
    /// @throws IOException if the profile's `IndexedDB` directory cannot be listed
    public Optional<StorageBackend> indexedDbBackend(StorageOrigin origin) throws IOException {
        return indexedDb.backend(origin);
    }

    /// {@return the names of `origin`'s IndexedDB databases, in the backend that holds its data, sorted}
    ///
    /// A database a page created but failed to open is left out, as Chromium leaves it out.
    ///
    /// @throws ViridianException if the metadata does not decode
    /// @throws IOException       if the files cannot be read
    public List<String> indexedDbNames(StorageOrigin origin) throws IOException {
        var backend = indexedDb.backend(origin);
        return backend.isEmpty() ? List.of() : indexedDb.names(origin, backend.get());
    }

    /// {@return the names of `origin`'s IndexedDB databases in `backend`, sorted}
    ///
    /// @throws ViridianException if the metadata does not decode
    /// @throws IOException       if the files cannot be read
    public List<String> indexedDbNames(StorageOrigin origin, StorageBackend backend) throws IOException {
        return indexedDb.names(origin, Objects.requireNonNull(backend, "backend"));
    }

    /// {@return `origin`'s IndexedDB database `name`, in the backend that holds its data, open; the caller closes it}
    ///
    /// @throws ViridianException if the database's metadata does not decode, or its schema is newer than this reader
    ///                           knows
    /// @throws IOException       if the files cannot be read
    public Optional<IndexedDatabase> openIndexedDb(StorageOrigin origin, String name) throws IOException {
        var backend = indexedDb.backend(origin);
        return backend.isEmpty() ? Optional.empty() : indexedDb.open(origin, Objects.requireNonNull(name, "name"), backend.get());
    }

    /// {@return `origin`'s IndexedDB database `name` in `backend`, open; the caller closes it}
    ///
    /// @throws ViridianException if the database's metadata does not decode, or its schema is newer than this reader
    ///                           knows
    /// @throws IOException       if the files cannot be read
    public Optional<IndexedDatabase> openIndexedDb(StorageOrigin origin, String name, StorageBackend backend) throws IOException {
        return indexedDb.open(origin, Objects.requireNonNull(name, "name"), Objects.requireNonNull(backend, "backend"));
    }

    /// {@return the backend that holds the profile's `localStorage`, or an empty optional if it has none}
    ///
    /// When both backends hold it, the SQLite one is the profile's, as it is for Chromium, which creates a SQLite
    /// database only where it uses SQLite and deletes the LevelDB one once it has migrated it.
    public Optional<StorageBackend> localStorageBackend() {
        return domStorageBackend(localStorageSqlite(), localStorageLevelDb());
    }

    /// {@return `origin`'s `localStorage` items, by key, in the backend that holds the profile's; empty if it has none}
    ///
    /// @throws ViridianException if the database's schema is newer than this reader knows, or an item does not decode
    /// @throws IOException       if the Local Storage database cannot be read
    public Map<String, String> localStorage(StorageOrigin origin) throws IOException {
        Objects.requireNonNull(origin, "origin");
        var backend = localStorageBackend();
        return backend.isEmpty() ? Map.of() : localStorage(origin, backend.get());
    }

    /// {@return `origin`'s `localStorage` items, by key, in `backend`; empty if it has none}
    ///
    /// @throws ViridianException if the database's schema is newer than this reader knows, or an item does not decode
    /// @throws IOException       if the Local Storage database cannot be read
    public Map<String, String> localStorage(StorageOrigin origin, StorageBackend backend) throws IOException {
        Objects.requireNonNull(origin, "origin");
        return switch (Objects.requireNonNull(backend, "backend")) {
            case LEVELDB -> Files.isDirectory(localStorageLevelDb()) ? LevelDbDomStorage.localStorage(localStorageLevelDb(), origin) : Map.of();
            case SQLITE -> Files.isRegularFile(localStorageSqlite()) ? SqliteDomStorage.localStorage(localStorageSqlite(), origin) : Map.of();
        };
    }

    /// {@return the origins with `localStorage` items, in the backend that holds the profile's}
    ///
    /// @throws ViridianException if the database's schema is newer than this reader knows
    /// @throws IOException       if the Local Storage database cannot be read
    public List<StorageOrigin> localStorageOrigins() throws IOException {
        var backend = localStorageBackend();
        return backend.isEmpty() ? List.of() : localStorageOrigins(backend.get());
    }

    /// {@return the origins with `localStorage` items in `backend`}
    ///
    /// @throws ViridianException if the database's schema is newer than this reader knows
    /// @throws IOException       if the Local Storage database cannot be read
    public List<StorageOrigin> localStorageOrigins(StorageBackend backend) throws IOException {
        return switch (Objects.requireNonNull(backend, "backend")) {
            case LEVELDB -> Files.isDirectory(localStorageLevelDb()) ? LevelDbDomStorage.localStorageOrigins(localStorageLevelDb()) : List.of();
            case SQLITE -> Files.isRegularFile(localStorageSqlite()) ? SqliteDomStorage.localStorageOrigins(localStorageSqlite()) : List.of();
        };
    }

    /// {@return the backend that holds the profile's `sessionStorage`, or an empty optional if it has none}
    ///
    /// When both backends hold it, the SQLite one is the profile's, as for [#localStorageBackend()].
    public Optional<StorageBackend> sessionStorageBackend() {
        return domStorageBackend(sessionStorageSqlite(), sessionStorageLevelDb());
    }

    /// {@return `origin`'s `sessionStorage`, one area per browsing session the browser kept in session id order, in the
    /// backend that holds the profile's; empty if it has none}
    ///
    /// @throws ViridianException if the database's schema is newer than this reader knows, or a value does not decode
    /// @throws IOException       if the Session Storage database cannot be read
    public List<SessionStorageArea> sessionStorage(StorageOrigin origin) throws IOException {
        Objects.requireNonNull(origin, "origin");
        var backend = sessionStorageBackend();
        return backend.isEmpty() ? List.of() : sessionStorage(origin, backend.get());
    }

    /// {@return `origin`'s `sessionStorage` in `backend`, one area per browsing session the browser kept in session id
    /// order; empty if it has none}
    ///
    /// @throws ViridianException if the database's schema is newer than this reader knows, or a value does not decode
    /// @throws IOException       if the Session Storage database cannot be read
    public List<SessionStorageArea> sessionStorage(StorageOrigin origin, StorageBackend backend) throws IOException {
        Objects.requireNonNull(origin, "origin");
        return switch (Objects.requireNonNull(backend, "backend")) {
            case LEVELDB -> Files.isDirectory(sessionStorageLevelDb()) ? LevelDbDomStorage.sessionStorage(sessionStorageLevelDb(), origin) : List.of();
            case SQLITE -> Files.isRegularFile(sessionStorageSqlite()) ? SqliteDomStorage.sessionStorage(sessionStorageSqlite(), origin) : List.of();
        };
    }

    /// {@return the backend of a DOM Storage database: SQLite when its file exists, else LevelDB when its directory
    /// does}
    private static Optional<StorageBackend> domStorageBackend(Path sqlite, Path levelDb) {
        if (Files.isRegularFile(sqlite)) {
            return Optional.of(StorageBackend.SQLITE);
        }
        return Files.isDirectory(levelDb) ? Optional.of(StorageBackend.LEVELDB) : Optional.empty();
    }

    private Path localStorageLevelDb() {
        return directory.resolve("Local Storage").resolve("leveldb");
    }

    private Path localStorageSqlite() {
        return directory.resolve("LocalStorage");
    }

    private Path sessionStorageLevelDb() {
        return directory.resolve("Session Storage");
    }

    private Path sessionStorageSqlite() {
        return directory.resolve("SessionStorage");
    }

    @Override
    public String toString() {
        return "ChromiumProfile[" + directory + "]";
    }
}
