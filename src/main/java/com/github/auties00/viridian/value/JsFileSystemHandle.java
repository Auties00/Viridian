package com.github.auties00.viridian.value;

import java.util.Objects;
import java.util.Optional;

/// A File System Access handle a page stored: a `FileSystemFileHandle` or `FileSystemDirectoryHandle`.
///
/// The handle names a file or directory the user granted the page; the grant itself is the token the browser issued,
/// which IndexedDB keeps beside the record and which has no meaning outside that browser. A handle can only be read:
/// nothing but the browser can issue a grant, so [StructuredClone#serialize(JsValue)] refuses a value that holds one.
public final class JsFileSystemHandle implements JsHostObject {
    private final Kind kind;
    private final String name;
    private final byte[] token;

    JsFileSystemHandle(Kind kind, String name, byte[] token) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.name = Objects.requireNonNull(name, "name");
        this.token = token;
    }

    /// {@return whether the handle is to a file or a directory}
    public Kind kind() {
        return kind;
    }

    /// {@return the name of the file or directory}
    public String name() {
        return name;
    }

    /// {@return a copy of the token the browser issued for the grant, or an empty optional if the value was decoded
    /// without the tokens stored beside it}
    public Optional<byte[]> token() {
        return token == null ? Optional.empty() : Optional.of(token.clone());
    }

    /// {@return the token itself, or `null`}
    byte[] rawToken() {
        return token;
    }

    @Override
    public String toString() {
        return "FileSystemHandle(" + kind.name().toLowerCase() + ", " + name + ")";
    }

    /// What a handle is to.
    public enum Kind {
        /// A file.
        FILE,
        /// A directory.
        DIRECTORY
    }
}
