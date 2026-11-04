package com.github.auties00.viridian;

import com.github.auties00.viridian.domstorage.LevelDbDomStorage;
import com.github.auties00.viridian.domstorage.SqliteDomStorage;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/// The `sessionStorage` a profile kept for the browsing sessions a browser restores, such as its tabs. It can only be
/// read: a session belongs to the browser that keeps it.
///
/// A profile keeps it in one database, on LevelDB (`Session Storage`) or SQLite (`SessionStorage`). The storage of
/// [ChromiumProfile#sessionStorage()] reads the one Chromium reads, SQLite when there are both; that of
/// [ChromiumProfile#sessionStorage(StorageBackend)] the given one.
///
/// {@snippet lang = java :
/// for (SessionStorageArea area : profile.sessionStorage().areas(origin)) {
///     System.out.println("session " + area.namespaceId() + ": " + area.items());
/// }
/// }
public final class SessionStorage {
    private final ChromiumProfile profile;
    private final StorageBackend backend;

    SessionStorage(ChromiumProfile profile, StorageBackend backend) {
        this.profile = profile;
        this.backend = backend;
    }

    /// {@return the backend this storage reads, or an empty optional if the profile has no `sessionStorage` there}
    public Optional<StorageBackend> backend() {
        var levelDb = Files.isDirectory(profile.directory().resolve(ChromiumProfile.SESSION_STORAGE_LEVELDB));
        var sqlite = Files.isRegularFile(profile.directory().resolve(ChromiumProfile.SESSION_STORAGE_SQLITE));
        if (backend != null) {
            return (backend == StorageBackend.LEVELDB ? levelDb : sqlite) ? Optional.of(backend) : Optional.empty();
        }
        if (sqlite) {
            return Optional.of(StorageBackend.SQLITE);
        }
        return levelDb ? Optional.of(StorageBackend.LEVELDB) : Optional.empty();
    }

    /// {@return the origins whose first-party key has `sessionStorage`, in the backend this storage reads}
    ///
    /// @throws ViridianException if the database's schema is newer than this reader knows
    /// @throws IOException       if the Session Storage database cannot be read
    public List<StorageOrigin> origins() throws IOException {
        return storageKeys().stream().filter(StorageKey::isFirstParty).map(StorageKey::origin).toList();
    }

    /// {@return every storage key with `sessionStorage`, in the backend this storage reads, partitioned ones included}
    ///
    /// @throws ViridianException if the database's schema is newer than this reader knows
    /// @throws IOException       if the Session Storage database cannot be read
    public List<StorageKey> storageKeys() throws IOException {
        var backend = backend();
        if (backend.isEmpty()) {
            return List.of();
        }
        return switch (backend.get()) {
            case LEVELDB -> LevelDbDomStorage.sessionStorageKeys(profile.directory().resolve(ChromiumProfile.SESSION_STORAGE_LEVELDB));
            case SQLITE -> SqliteDomStorage.sessionStorageKeys(profile.directory().resolve(ChromiumProfile.SESSION_STORAGE_SQLITE));
        };
    }

    /// {@return the `sessionStorage` of `origin`'s first-party key, as [#areas(StorageKey)] reads it}
    ///
    /// @throws ViridianException if the database's schema is newer than this reader knows, or a value does not decode
    /// @throws IOException       if the Session Storage database cannot be read
    public List<SessionStorageArea> areas(StorageOrigin origin) throws IOException {
        return areas(StorageKey.of(origin));
    }

    /// {@return `key`'s `sessionStorage`, one area per browsing session the browser kept, in session id order; empty
    /// if it has none}
    ///
    /// @throws ViridianException if the database's schema is newer than this reader knows, or a value does not decode
    /// @throws IOException       if the Session Storage database cannot be read
    public List<SessionStorageArea> areas(StorageKey key) throws IOException {
        Objects.requireNonNull(key, "key");
        var backend = backend();
        if (backend.isEmpty()) {
            return List.of();
        }
        return switch (backend.get()) {
            case LEVELDB -> LevelDbDomStorage.sessionStorage(profile.directory().resolve(ChromiumProfile.SESSION_STORAGE_LEVELDB), key);
            case SQLITE -> SqliteDomStorage.sessionStorage(profile.directory().resolve(ChromiumProfile.SESSION_STORAGE_SQLITE), key);
        };
    }

    @Override
    public String toString() {
        return "SessionStorage[" + profile.directory() + (backend == null ? "" : ", " + backend) + "]";
    }
}
