package com.github.auties00.viridian;

import java.util.List;
import java.util.Objects;

/// The key path of an object store or index: where in a value its key is.
///
/// @spec https://w3c.github.io/IndexedDB/#key-path-construct Indexed Database API 3.0
public sealed interface IdbKeyPath {
    /// A key path of one identifier chain, such as `"user.id"`, or the empty string, the value itself.
    ///
    /// @param path the path
    record Single(String path) implements IdbKeyPath {
        /// Creates a key path.
        public Single {
            Objects.requireNonNull(path, "path");
        }
    }

    /// A key path of several paths, whose key is an array.
    ///
    /// @param paths the paths
    record Compound(List<String> paths) implements IdbKeyPath {
        /// Creates a key path, copying `paths`.
        public Compound {
            paths = List.copyOf(paths);
        }
    }
}
