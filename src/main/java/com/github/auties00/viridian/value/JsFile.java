package com.github.auties00.viridian.value;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/// A `File`: a [JsBlob] with a name and a modification time.
public final class JsFile implements JsHostObject {
    private final String name;
    private final String type;
    private final long size;
    private final Instant lastModified;
    private final BlobSource source;

    JsFile(String name, String type, long size, Instant lastModified, BlobSource source) {
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
        this.size = size;
        this.lastModified = lastModified;
        this.source = source;
    }

    /// {@return the file name}
    public String name() {
        return name;
    }

    /// {@return the MIME type, empty if the file had none}
    public String type() {
        return type;
    }

    /// {@return the size in bytes, or -1 if the storage did not record it}
    public long size() {
        return size;
    }

    /// {@return when the file was last modified, or an empty optional if that was not recorded}
    public Optional<Instant> lastModified() {
        return Optional.ofNullable(lastModified);
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
            throw new IOException("The bytes of the file " + name + " were not stored with the value");
        }
        return source.open();
    }

    @Override
    public String toString() {
        return "File(" + name + ", " + size + ")";
    }
}
