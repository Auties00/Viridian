package com.github.auties00.viridian;

import com.github.auties00.viridian.indexeddb.StoredCursor;
import com.github.auties00.viridian.value.JsBlob;
import com.github.auties00.viridian.value.JsValue;

import java.io.IOException;
import java.util.List;

/// The records of an object store, read one at a time in key order, each value decoded only when asked for.
///
/// A cursor is the way to read a store that may hold a damaged record: [#next()] fails only when the store itself
/// cannot be read, while [#value()] fails for one record, which the caller may skip.
///
/// {@snippet lang = java :
/// try (var cursor = database.openCursor("messages")) {
///     while (cursor.next()) {
///         try {
///             index(cursor.key(), cursor.value());
///         } catch (ViridianException damaged) {
///             skipped.put(cursor.key(), damaged);
///         }
///     }
/// }
/// }
///
/// # Lifecycle
/// A cursor may hold files open until it is closed, as its database does; it is not safe for concurrent use. Before
/// the first [#next()], and after one that returned `false`, there is no current record, and the accessors throw
/// [IllegalStateException].
public sealed interface IdbCursor extends AutoCloseable permits StoredCursor {
    /// Moves to the next record.
    ///
    /// @return whether there is one
    /// @throws ViridianException if the store's entries do not decode
    /// @throws IOException       if the store cannot be read
    boolean next() throws IOException;

    /// {@return the current record's key}
    IdbKey key();

    /// {@return the current record's value, decoded, as a page reads it}
    ///
    /// A value too large to keep inline is read from the blob that holds it. In a store with a key generator and a key
    /// path, the record's key is injected into the value at the key path, as Blink injects it: the value Chromium
    /// stores lacks the key the generator gave it.
    ///
    /// @throws ViridianException if the value is damaged, its blob is missing, or it holds an object this reader does
    ///                           not support
    /// @throws IOException       if the blob that holds the value cannot be read
    JsValue value() throws IOException;

    /// {@return the current record's value as stored, its IndexedDB wrapping undone: the bytes
    /// [com.github.auties00.viridian.value.StructuredClone] decodes with [#blobs()], without an injected key}
    ///
    /// @throws ViridianException if the value is damaged, or the blob that holds it is missing
    /// @throws IOException       if the blob that holds the value cannot be read
    byte[] serializedValue() throws IOException;

    /// {@return the blobs and files stored beside the current record, in the order its value refers to them}
    List<JsBlob> blobs();

    /// Releases the files the cursor holds open; closing a closed cursor does nothing.
    @Override
    void close() throws IOException;
}
