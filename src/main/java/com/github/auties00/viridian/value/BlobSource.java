package com.github.auties00.viridian.value;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/// Where the bytes of a `Blob` or `File` are: a file of a profile, rows of its database, a file of the caller's, or
/// bytes in memory.
///
/// A source may be opened any number of times, and each stream starts from the first byte.
@FunctionalInterface
public interface BlobSource {
    /// {@return a stream of the bytes, which the caller closes}
    ///
    /// @throws IOException if the bytes are gone or cannot be read
    InputStream open() throws IOException;

    /// {@return a source of a copy of `bytes`}
    static BlobSource of(byte[] bytes) {
        var copy = bytes.clone();
        return () -> new ByteArrayInputStream(copy);
    }

    /// {@return a source of the bytes `file` holds when the source is opened}
    static BlobSource of(Path file) {
        Objects.requireNonNull(file, "file");
        return () -> Files.newInputStream(file);
    }
}
