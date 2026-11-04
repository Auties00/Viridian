package com.github.auties00.viridian.value;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;

/// A `Blob`: its MIME type and size, and its bytes where the storage that held the value keeps them.
public final class JsBlob implements JsHostObject {
    private final String type;
    private final long size;
    private final BlobSource source;

    JsBlob(String type, long size, BlobSource source) {
        this.type = Objects.requireNonNull(type, "type");
        this.size = size;
        this.source = source;
    }

    /// {@return the MIME type, empty if the blob had none}
    public String type() {
        return type;
    }

    /// {@return the size in bytes}
    public long size() {
        return size;
    }

    /// {@return where the bytes are, or an empty optional if the value was decoded without them}
    public Optional<BlobSource> source() {
        return Optional.ofNullable(source);
    }

    /// {@return a stream of the bytes, which the caller closes}
    ///
    /// @throws IOException if the value was decoded without the bytes, or they cannot be read
    public InputStream open() throws IOException {
        if (source == null) {
            throw new IOException("The bytes of the blob were not stored with the value");
        }
        return source.open();
    }

    @Override
    public String toString() {
        return "Blob(" + size + (type.isEmpty() ? "" : ", " + type) + ")";
    }
}
