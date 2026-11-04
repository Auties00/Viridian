package com.github.auties00.viridian.value;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/// A `File`: a [JsBlob] with a name and a modification time.
///
/// The factories take the type as the `File` constructor does: one with a character outside U+0020 to U+007E
/// becomes empty, and any other is lowercased.
///
/// {@snippet lang = java :
/// JsFile report = JsFile.of(Path.of("report.pdf"), "application/pdf");   // name, size and time from the file
/// JsFile note = JsFile.of("a.txt", "abc".getBytes(StandardCharsets.UTF_8), "text/plain", Instant.now());
/// }
public final class JsFile extends JsBlob {
    private final String name;
    private final Instant lastModified;

    JsFile(String name, String type, long size, Instant lastModified, BlobSource source) {
        super(type, size, source);
        this.name = Objects.requireNonNull(name, "name");
        this.lastModified = lastModified;
    }

    /// {@return a file named `name` of a copy of `bytes`, of MIME type `type`, last modified at `lastModified`}
    public static JsFile of(String name, byte[] bytes, String type, Instant lastModified) {
        return new JsFile(Objects.requireNonNull(name, "name"), normalizeType(type), bytes.length,
                Objects.requireNonNull(lastModified, "lastModified"), BlobSource.of(bytes));
    }

    /// {@return a file of the bytes of `file`, of MIME type `type`, with the file's name, and the size and modification
    /// time it has now}
    ///
    /// The file is read when the value is written: it must keep its size until then.
    ///
    /// @throws IOException if the file's size or modification time cannot be read
    public static JsFile of(Path file, String type) throws IOException {
        var fileName = file.getFileName();
        return new JsFile(fileName == null ? "" : fileName.toString(), normalizeType(type), Files.size(file),
                Files.getLastModifiedTime(file).toInstant(), BlobSource.of(file));
    }

    /// {@return a file named `name` of the `size` bytes `source` holds, of MIME type `type`, last modified at
    /// `lastModified`}
    ///
    /// @throws IllegalArgumentException if `size` is negative
    public static JsFile of(String name, BlobSource source, long size, String type, Instant lastModified) {
        Objects.requireNonNull(source, "source");
        if (size < 0) {
            throw new IllegalArgumentException("A file cannot have " + size + " bytes");
        }
        return new JsFile(Objects.requireNonNull(name, "name"), normalizeType(type), size,
                Objects.requireNonNull(lastModified, "lastModified"), source);
    }

    /// {@return the file name}
    public String name() {
        return name;
    }

    /// {@inheritDoc}
    ///
    /// A file a value describes inline, without a snapshot of its state, has a size of -1: such a value does not
    /// record it. A file read from IndexedDB always has its size.
    @Override
    public long size() {
        return super.size();
    }

    /// {@return when the file was last modified, or an empty optional if that was not recorded}
    public Optional<Instant> lastModified() {
        return Optional.ofNullable(lastModified);
    }

    @Override
    public String toString() {
        return "File(" + name + ", " + size() + ")";
    }
}
