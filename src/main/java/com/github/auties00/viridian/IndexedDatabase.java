package com.github.auties00.viridian;

import com.github.auties00.viridian.indexeddb.LevelDbIndexedDatabase;
import com.github.auties00.viridian.indexeddb.SqliteIndexedDatabase;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/// An IndexedDB database of an origin, open for reading.
///
/// # Lifecycle
/// A database may hold files open, which [#close()] releases; so does closing a stream of its records. A closed
/// database refuses every call.
///
/// # Threading
/// A database is not safe for concurrent use. A profile that Chromium is writing gives a best-effort view, which
/// may be inconsistent; reading a profile the browser has closed gives the state it left.
public sealed interface IndexedDatabase extends AutoCloseable permits LevelDbIndexedDatabase, SqliteIndexedDatabase {
    /// {@return the origin the database belongs to}
    StorageOrigin origin();

    /// {@return the database's name}
    String name();

    /// {@return the version the page last opened the database with}
    long version();

    /// {@return how Chromium stores the database}
    StorageBackend backend();

    /// {@return the object stores, by id}
    List<IdbObjectStore> objectStores();

    /// {@return the object store named `name`, or an empty optional if there is none}
    default Optional<IdbObjectStore> objectStore(String name) {
        for (var store : objectStores()) {
            if (store.name().equals(name)) {
                return Optional.of(store);
            }
        }
        return Optional.empty();
    }

    /// {@return the records of the object store `store`, in key order, as a stream the caller closes}
    ///
    /// A failure to read the files while the stream is consumed is thrown as an [UncheckedIOException].
    ///
    /// @throws IllegalArgumentException if the database has no object store named `store`
    /// @throws IOException              if the store cannot be read
    Stream<IdbRecord> records(String store) throws IOException;

    /// {@return the record of `store` with key `key`, or an empty optional if there is none}
    ///
    /// @throws IllegalArgumentException if the database has no object store named `store`
    /// @throws IOException              if the store cannot be read
    Optional<IdbRecord> record(String store, IdbKey key) throws IOException;

    /// Releases the files the database holds open; closing a closed database does nothing.
    @Override
    void close() throws IOException;
}
