package com.github.auties00.viridian;

import java.util.Objects;

/// An index of an object store: its name, where in a record's value its key is, and how it holds records.
///
/// @param name       the name
/// @param keyPath    where in a record's value its key in the index is
/// @param unique     whether two records may not share a key in the index
/// @param multiEntry whether a key path that gives an array indexes the record under each element
public record IdbIndex(String name, IdbKeyPath keyPath, boolean unique, boolean multiEntry) {
    /// Creates an index.
    ///
    /// @throws IllegalArgumentException if the index is multi-entry with a compound key path, which `createIndex`
    ///                                  refuses
    public IdbIndex {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(keyPath, "keyPath");
        if (multiEntry && keyPath instanceof IdbKeyPath.Compound) {
            throw new IllegalArgumentException("The multi-entry index " + name + " cannot have the compound key path " + keyPath);
        }
    }

    /// The options of an index.
    public enum Option {
        /// Two records may not share a key in the index.
        UNIQUE,
        /// A key path that gives an array indexes the record under each element.
        MULTI_ENTRY
    }
}
