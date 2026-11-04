package com.github.auties00.viridian.indexeddb;

import com.github.auties00.viridian.value.JsValue;
import com.github.auties00.viridian.value.SerializedValue;

import java.io.IOException;

/// Serializes the values of the records an IndexedDB writer stores, in the format a page's would be stored in.
@FunctionalInterface
public interface ValueSerializer {
    /// {@return `value` serialized: Blink's envelope around V8's wire format, and the blobs, files and handles it
    /// refers to}
    ///
    /// @throws IOException if the value cannot be serialized, or a blob or file in it has no bytes to store
    SerializedValue serialize(JsValue value) throws IOException;
}
