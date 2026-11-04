package com.github.auties00.viridian;

import com.github.auties00.viridian.domstorage.LevelDbDomStorage;
import com.github.auties00.viridian.domstorage.SqliteDomStorage;

import java.io.IOException;
import java.nio.file.Files;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/// The `localStorage` of a profile, storage key by storage key.
///
/// {@snippet lang = java :
/// Map<String, String> items = profile.localStorage().items(origin);
/// profile.localStorage().replaceAll(origin, Map.of("theme", "dark", "session", "{\"id\":7}"));
/// }
///
/// Each method takes a [StorageKey], or a [StorageOrigin] for the origin's first-party key, which a page that is not
/// embedded by another site has. DOM Storage has no named buckets.
///
/// # Backends
/// A profile keeps all of its `localStorage` in one database, on LevelDB (`Local Storage/leveldb`) or SQLite
/// (`LocalStorage`); one that Chromium is moving to SQLite may have both. The storage of
/// [ChromiumProfile#localStorage()] reads the one Chromium reads, SQLite when there are both, and writes every one the
/// profile has, kept equal, since which one a Chrome reads depends on where its rollout of SQLite is, or LevelDB if the
/// profile has neither. That of [ChromiumProfile#localStorage(StorageBackend)] reads and writes the given backend only.
///
/// # Writing
/// A write replaces a storage key's items as a whole; every other key's are kept. On the storage of a profile, each
/// write is one step of its own, made as [ChromiumProfile#write(ProfileEditor)] makes one; on that of a
/// [ProfileEdit], writes are part of the edit, and reads still see the profile as it was before it.
public final class LocalStorage {
    private final ChromiumProfile profile;
    private final StorageBackend backend;
    private final ProfileTransaction transaction;

    LocalStorage(ChromiumProfile profile, StorageBackend backend, ProfileTransaction transaction) {
        this.profile = profile;
        this.backend = backend;
        this.transaction = transaction;
    }

    /// {@return the backend this storage reads, or an empty optional if the profile has no `localStorage` there}
    public Optional<StorageBackend> backend() {
        var levelDb = Files.isDirectory(profile.directory().resolve(ChromiumProfile.LOCAL_STORAGE_LEVELDB));
        var sqlite = Files.isRegularFile(profile.directory().resolve(ChromiumProfile.LOCAL_STORAGE_SQLITE));
        if (backend != null) {
            return (backend == StorageBackend.LEVELDB ? levelDb : sqlite) ? Optional.of(backend) : Optional.empty();
        }
        if (sqlite) {
            return Optional.of(StorageBackend.SQLITE);
        }
        return levelDb ? Optional.of(StorageBackend.LEVELDB) : Optional.empty();
    }

    /// {@return the origins whose first-party key has `localStorage` items, in the backend this storage reads}
    ///
    /// @throws ViridianException if the database's schema is newer than this reader knows
    /// @throws IOException       if the Local Storage database cannot be read
    public List<StorageOrigin> origins() throws IOException {
        return storageKeys().stream().filter(StorageKey::isFirstParty).map(StorageKey::origin).toList();
    }

    /// {@return every storage key with `localStorage` items, in the backend this storage reads, partitioned ones
    /// included}
    ///
    /// @throws ViridianException if the database's schema is newer than this reader knows
    /// @throws IOException       if the Local Storage database cannot be read
    public List<StorageKey> storageKeys() throws IOException {
        var backend = backend();
        if (backend.isEmpty()) {
            return List.of();
        }
        return switch (backend.get()) {
            case LEVELDB -> LevelDbDomStorage.localStorageKeys(profile.directory().resolve(ChromiumProfile.LOCAL_STORAGE_LEVELDB));
            case SQLITE -> SqliteDomStorage.localStorageKeys(profile.directory().resolve(ChromiumProfile.LOCAL_STORAGE_SQLITE));
        };
    }

    /// {@return the items of `origin`'s first-party key, by key; empty if it has none}
    ///
    /// @throws ViridianException if the database's schema is newer than this reader knows, or an item does not decode
    /// @throws IOException       if the Local Storage database cannot be read
    public Map<String, String> items(StorageOrigin origin) throws IOException {
        return items(StorageKey.of(origin));
    }

    /// {@return `key`'s items, by key; empty if it has none}
    ///
    /// @throws ViridianException if the database's schema is newer than this reader knows, or an item does not decode
    /// @throws IOException       if the Local Storage database cannot be read
    public Map<String, String> items(StorageKey key) throws IOException {
        Objects.requireNonNull(key, "key");
        var backend = backend();
        if (backend.isEmpty()) {
            return Map.of();
        }
        return switch (backend.get()) {
            case LEVELDB -> LevelDbDomStorage.localStorage(profile.directory().resolve(ChromiumProfile.LOCAL_STORAGE_LEVELDB), key);
            case SQLITE -> SqliteDomStorage.localStorage(profile.directory().resolve(ChromiumProfile.LOCAL_STORAGE_SQLITE), key);
        };
    }

    /// {@return the item `item` of `origin`'s first-party key, or an empty optional if it has none}
    ///
    /// @throws ViridianException if the database's schema is newer than this reader knows, or an item does not decode
    /// @throws IOException       if the Local Storage database cannot be read
    public Optional<String> item(StorageOrigin origin, String item) throws IOException {
        return item(StorageKey.of(origin), item);
    }

    /// {@return `key`'s item `item`, or an empty optional if it has none}
    ///
    /// @throws ViridianException if the database's schema is newer than this reader knows, or an item does not decode
    /// @throws IOException       if the Local Storage database cannot be read
    public Optional<String> item(StorageKey key, String item) throws IOException {
        Objects.requireNonNull(item, "item");
        return Optional.ofNullable(items(key).get(item));
    }

    /// Replaces the items of `origin`'s first-party key with `items`, keeping every other key's.
    ///
    /// @throws ProfileInUseException if a browser, an app or another writer has the profile or its Local Storage open
    /// @throws ViridianException      if a Local Storage database has a schema this writer does not write
    /// @throws IOException            if the files cannot be read or written
    public void replaceAll(StorageOrigin origin, Map<String, String> items) throws IOException {
        replaceAll(StorageKey.of(origin), items);
    }

    /// Replaces `key`'s items with `items`, keeping every other key's.
    ///
    /// @throws ProfileInUseException if a browser, an app or another writer has the profile or its Local Storage open
    /// @throws ViridianException      if a Local Storage database has a schema this writer does not write
    /// @throws IOException            if the files cannot be read or written
    public void replaceAll(StorageKey key, Map<String, String> items) throws IOException {
        Objects.requireNonNull(key, "key");
        var copy = Map.copyOf(items);
        write(transaction -> transaction.localStorage(key, targets(), copy));
    }

    /// Deletes the items of `origin`'s first-party key, keeping every other key's.
    ///
    /// @throws ProfileInUseException if a browser, an app or another writer has the profile or its Local Storage open
    /// @throws IOException           if the files cannot be read or written
    public void clear(StorageOrigin origin) throws IOException {
        clear(StorageKey.of(origin));
    }

    /// Deletes `key`'s items, keeping every other key's.
    ///
    /// @throws ProfileInUseException if a browser, an app or another writer has the profile or its Local Storage open
    /// @throws IOException           if the files cannot be read or written
    public void clear(StorageKey key) throws IOException {
        replaceAll(key, Map.of());
    }

    /// Replaces the items of `origin`'s first-party key with those it has in `source`, as they are now.
    ///
    /// @throws ProfileInUseException if a browser, an app or another writer has this profile or its Local Storage open
    /// @throws ViridianException      if the source's items do not decode
    /// @throws IOException            if the files cannot be read or written
    public void copyFrom(LocalStorage source, StorageOrigin origin) throws IOException {
        copyFrom(source, StorageKey.of(origin));
    }

    /// Replaces `key`'s items with those it has in `source`, as they are now.
    ///
    /// @throws ProfileInUseException if a browser, an app or another writer has this profile or its Local Storage open
    /// @throws ViridianException      if the source's items do not decode
    /// @throws IOException            if the files cannot be read or written
    public void copyFrom(LocalStorage source, StorageKey key) throws IOException {
        replaceAll(key, Objects.requireNonNull(source, "source").items(key));
    }

    /// {@return the backends a write goes to: the pinned one, or every one the profile has, or LevelDB}
    private Set<StorageBackend> targets() {
        if (backend != null) {
            return EnumSet.of(backend);
        }
        var targets = EnumSet.noneOf(StorageBackend.class);
        if (Files.isDirectory(profile.directory().resolve(ChromiumProfile.LOCAL_STORAGE_LEVELDB))) {
            targets.add(StorageBackend.LEVELDB);
        }
        if (Files.isRegularFile(profile.directory().resolve(ChromiumProfile.LOCAL_STORAGE_SQLITE))) {
            targets.add(StorageBackend.SQLITE);
        }
        return targets.isEmpty() ? EnumSet.of(StorageBackend.LEVELDB) : targets;
    }

    private void write(ProfileTransaction.Step step) throws IOException {
        if (transaction != null) {
            step.apply(transaction);
        } else {
            profile.write(edit -> step.apply(edit.transaction()));
        }
    }

    @Override
    public String toString() {
        return "LocalStorage[" + profile.directory() + (backend == null ? "" : ", " + backend) + "]";
    }
}
