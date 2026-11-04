package com.github.auties00.viridian.indexeddb;

import com.github.auties00.viridian.IdbKey;
import com.github.auties00.viridian.IdbObjectStore;
import com.github.auties00.viridian.value.JsValue;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.OptionalLong;

/// A database an IndexedDB writer writes: its name and version, and for each of its object stores, the store's
/// definition, its key generator and its records, read one at a time so that a database need not fit in memory.
///
/// A record's value is the one a store keeps, which for a store with a key generator and a key path lacks the key
/// the generator gave it: Blink injects that key when it reads the value back, and the writers derive its index keys
/// as Chromium does, the record's key standing in for it.
public interface DatabaseSource {
    /// {@return the database's name}
    String name();

    /// {@return the database's version, from 1 to 2^53 - 1}
    long version();

    /// {@return the object stores, which the writers number from 1 in this order}
    List<IdbObjectStore> objectStores();

    /// {@return the number the key generator of `store` gives next, or an empty optional to derive it from the
    /// records: one past the largest number key, or 1}
    OptionalLong keyGenerator(IdbObjectStore store);

    /// {@return the records of `store`, in key order; the caller closes the cursor}
    ///
    /// @throws IOException if the records cannot be read
    RecordCursor records(IdbObjectStore store) throws IOException;

    /// The records of a store, read one at a time.
    interface RecordCursor extends Closeable {
        /// Moves to the next record.
        ///
        /// @return whether there is one
        /// @throws IOException if it cannot be read
        boolean next() throws IOException;

        /// {@return the record's key}
        IdbKey key();

        /// {@return the record's value, as the store keeps it}
        ///
        /// @throws IOException if it cannot be read or decoded
        JsValue value() throws IOException;
    }
}
