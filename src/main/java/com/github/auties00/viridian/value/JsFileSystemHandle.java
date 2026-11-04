package com.github.auties00.viridian.value;

import java.util.Objects;

/// A File System Access handle: a `FileSystemFileHandle` or `FileSystemDirectoryHandle`.
///
/// The handle names a file or directory the user granted the page; the grant itself is a token the browser keeps,
/// which has no meaning outside it.
///
/// @param kind whether the handle is to a file or a directory
/// @param name the name of the file or directory
public record JsFileSystemHandle(Kind kind, String name) implements JsHostObject {
    /// Creates a handle.
    public JsFileSystemHandle {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(name, "name");
    }

    /// What a handle is to.
    public enum Kind {
        /// A file.
        FILE,
        /// A directory.
        DIRECTORY
    }
}
