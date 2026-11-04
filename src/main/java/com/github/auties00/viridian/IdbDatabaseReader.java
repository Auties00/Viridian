package com.github.auties00.viridian;

import com.github.auties00.viridian.indexeddb.DatabaseSource;
import com.github.auties00.viridian.indexeddb.LevelDbDatabaseReader;
import com.github.auties00.viridian.indexeddb.SqliteDatabaseReader;
import com.github.auties00.viridian.value.JsValue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Stream;

/// An IndexedDB database of a profile, open for reading, record by record.
///
/// {@snippet lang = java :
/// try (var database = profile.indexedDb().open(origin, "mail").orElseThrow();
///      var records = database.records("messages")) {
///     long unread = records
///             .filter(record -> record.value() instanceof JsObject message && message.getBoolean("unread").orElse(false))
///             .count();
/// }
/// }
///
/// Values are decoded as a page reads them: in a store with a key generator and a key path, each record's key is
/// injected into its value at the key path, as Blink injects it. [IdbCursor#serializedValue()] gives the value as
/// stored.
///
/// # Lifecycle
/// A reader may hold files open, which [#close()] releases; so do closing a stream of its records and a cursor. A
/// closed reader refuses every call. The values it returns stay usable once it is closed, but a blob or file in one
/// reads its bytes from the profile, which must stay in place for as long as they may be read.
///
/// # Threading and consistency
/// A reader is not safe for concurrent use. It reads the files as they are, without the locks the browser takes: a
/// profile the browser has closed reads as the browser left it, and one the browser is writing gives a best-effort
/// view, which may lack its latest writes, or fail to read when a file changes under the reader.
public sealed interface IdbDatabaseReader extends AutoCloseable permits LevelDbDatabaseReader, SqliteDatabaseReader {
    /// {@return the storage bucket the database belongs to}
    StorageBucket bucket();

    /// {@return the origin the database belongs to, its bucket's}
    default StorageOrigin origin() {
        return bucket().key().origin();
    }

    /// {@return the database's name}
    String name();

    /// {@return the version the page last opened the database with}
    long version();

    /// {@return how Chromium stores the database}
    StorageBackend backend();

    /// {@return the object stores, in the order they were created}
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

    /// {@return the number the key generator of the object store `store` gives the next record added without a key,
    /// or an empty optional if the store has no key generator}
    ///
    /// @throws IllegalArgumentException if the database has no object store named `store`
    OptionalLong keyGenerator(String store);

    /// {@return how many records the object store `store` holds}
    ///
    /// @throws IllegalArgumentException if the database has no object store named `store`
    /// @throws IOException              if the store cannot be read
    long count(String store) throws IOException;

    /// {@return the value of the record of `store` with key `key`, decoded, or an empty optional if there is none}
    ///
    /// @throws IllegalArgumentException if the database has no object store named `store`
    /// @throws ViridianException        if the value does not decode
    /// @throws IOException              if the store cannot be read
    Optional<JsValue> get(String store, IdbKey key) throws IOException;

    /// {@return the records of the object store `store`, in key order, each decoded as it is reached, as a stream the
    /// caller closes}
    ///
    /// Nothing in the stream throws a checked exception: a failure to read or decode a record while the stream is
    /// consumed is thrown as an [UncheckedIOException], whose cause is often a [ViridianException], as
    /// `Files.lines` reports its failures. [#openCursor(String)] reads a store record by record instead.
    ///
    /// @throws IllegalArgumentException if the database has no object store named `store`
    /// @throws IOException              if the store cannot be read
    Stream<IdbRecord> records(String store) throws IOException;

    /// {@return a cursor over the records of the object store `store`, in key order; the caller closes it}
    ///
    /// @throws IllegalArgumentException if the database has no object store named `store`
    /// @throws IOException              if the store cannot be read
    IdbCursor openCursor(String store) throws IOException;

    /// {@return the whole database as a value: its version, object stores, key generators and records, every value
    /// decoded}
    ///
    /// A blob or file in a value keeps reading its bytes from the profile, which must stay in place for as long as
    /// they may be read; the reader itself may be closed.
    ///
    /// @throws ViridianException if a value does not decode
    /// @throws IOException       if the database cannot be read
    default IdbDatabase readAll() throws IOException {
        return IdbDatabase.of((DatabaseSource) this);
    }

    /// Releases the files the reader holds open; closing a closed reader does nothing.
    @Override
    void close() throws IOException;
}
