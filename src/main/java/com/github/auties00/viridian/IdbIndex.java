package com.github.auties00.viridian;

import java.util.Objects;

/// An index of an object store.
///
/// @param id         the index's id within its object store
/// @param name       the name
/// @param keyPath    where in a record's value its key in the index is
/// @param unique     whether two records may not share a key in the index
/// @param multiEntry whether a key path that gives an array indexes the record under each element
public record IdbIndex(long id, String name, IdbKeyPath keyPath, boolean unique, boolean multiEntry) {
    /// Creates an index.
    public IdbIndex {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(keyPath, "keyPath");
    }
}
