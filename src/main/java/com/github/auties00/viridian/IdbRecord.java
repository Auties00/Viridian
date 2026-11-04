package com.github.auties00.viridian;

import com.github.auties00.viridian.value.JsValue;

import java.util.Objects;

/// A record of an object store: its key and its value, decoded, as a page reads them.
///
/// @param key   the key
/// @param value the value
public record IdbRecord(IdbKey key, JsValue value) {
    /// Creates a record.
    public IdbRecord {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
    }
}
