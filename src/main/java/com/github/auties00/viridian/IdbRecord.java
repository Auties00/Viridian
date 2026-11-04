package com.github.auties00.viridian;

import com.github.auties00.viridian.indexeddb.StoredRecord;
import com.github.auties00.viridian.value.ExternalObject;
import com.github.auties00.viridian.value.JsValue;
import com.github.auties00.viridian.value.StructuredClone;

import java.io.IOException;
import java.util.List;

/// A record of an object store: its key, its value, and the blobs, files and handles stored beside it.
///
/// The value is decoded when [#value()] is called, so a record whose value does not decode can still be listed by
/// key.
public sealed interface IdbRecord permits StoredRecord {
    /// {@return the record's key}
    IdbKey key();

    /// {@return the blobs, files and File System Access handles stored with the record, in the order its value refers
    /// to them}
    List<ExternalObject> externalObjects();

    /// {@return the serialized value, with the wrapping IndexedDB adds undone: the bytes [StructuredClone] decodes}
    ///
    /// A value too large to keep inline is read from the blob that holds it, and a compressed one is decompressed.
    ///
    /// @throws ViridianException if the value is damaged, or its blob is missing
    /// @throws IOException       if the blob that holds the value cannot be read
    byte[] serializedValue() throws IOException;

    /// {@return the decoded value}
    ///
    /// @throws ViridianException if the value is damaged, its blob is missing, or it holds an object this reader does
    ///                           not support
    /// @throws IOException       if the blob that holds the value cannot be read
    default JsValue value() throws IOException {
        var serialized = serializedValue();
        return StructuredClone.deserialize(serialized, 0, serialized.length, externalObjects());
    }
}
