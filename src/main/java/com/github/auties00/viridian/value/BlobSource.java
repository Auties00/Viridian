package com.github.auties00.viridian.value;

import java.io.IOException;
import java.io.InputStream;

/// Where the bytes of a stored `Blob` or `File` are: a file of the profile, or rows of its database.
@FunctionalInterface
public interface BlobSource {
    /// {@return a stream of the bytes, which the caller closes}
    ///
    /// @throws IOException if the bytes are gone or cannot be read
    InputStream open() throws IOException;
}
