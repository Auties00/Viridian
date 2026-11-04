package com.github.auties00.viridian.value;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/// An object a stored value refers to by its index rather than holding it: a `Blob`, a `File`, or a File System
/// Access handle, as IndexedDB keeps them beside a record.
public sealed interface ExternalObject {
    /// A blob.
    ///
    /// @param type   the MIME type, empty if there is none
    /// @param size   the size in bytes
    /// @param source where the bytes are
    record Blob(String type, long size, BlobSource source) implements ExternalObject {
        /// Creates a blob.
        public Blob {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(source, "source");
        }
    }

    /// A file.
    ///
    /// @param name         the file name
    /// @param type         the MIME type, empty if there is none
    /// @param size         the size in bytes, or -1 if it was not recorded
    /// @param lastModified when the file was last modified, or `null` if that was not recorded
    /// @param source       where the bytes are
    record File(String name, String type, long size, Instant lastModified, BlobSource source) implements ExternalObject {
        /// Creates a file.
        public File {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(source, "source");
        }

        /// {@return when the file was last modified, or an empty optional if that was not recorded}
        public Optional<Instant> lastModifiedTime() {
            return Optional.ofNullable(lastModified);
        }
    }

    /// A File System Access handle, kept as the token the browser issued for it.
    ///
    /// @param token the token, which has no meaning outside the browser that issued it
    record FileSystemAccessHandle(byte[] token) implements ExternalObject {
        /// Creates a handle, copying `token`.
        public FileSystemAccessHandle {
            token = token.clone();
        }

        /// {@return a copy of the token}
        @Override
        public byte[] token() {
            return token.clone();
        }
    }
}
