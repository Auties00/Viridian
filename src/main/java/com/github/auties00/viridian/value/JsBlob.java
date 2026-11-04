package com.github.auties00.viridian.value;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/// A `Blob`: its MIME type and size, and its bytes where the storage that held the value keeps them.
///
/// A [JsFile] is a blob too, as a `File` is a `Blob` in JavaScript, so a pattern that matches both names `JsFile`
/// first.
///
/// A blob read from storage reads its bytes from the profile it came from, when [#open()] is called: that profile must
/// stay in place for as long as the blob may be read, even once the value or database it came from is done with. A
/// blob a value describes without storing its bytes, as an inline `Blob` of a serialized value does, has none:
/// [#hasBytes()] is `false`, and writing it to storage fails.
///
/// The factories take the type as the `Blob` constructor does: one with a character outside U+0020 to U+007E
/// becomes empty, and any other is lowercased.
///
/// {@snippet lang = java :
/// JsBlob note = JsBlob.of("hello".getBytes(StandardCharsets.UTF_8), "text/plain");
/// JsBlob clip = JsBlob.of(Path.of("clip.mp4"), "video/mp4");   // read when the value is written
/// }
public sealed class JsBlob implements JsHostObject permits JsFile {
    private final String type;
    private final long size;
    private final BlobSource source;

    JsBlob(String type, long size, BlobSource source) {
        this.type = Objects.requireNonNull(type, "type");
        this.size = size;
        this.source = source;
    }

    /// {@return a blob of a copy of `bytes`, of MIME type `type`}
    public static JsBlob of(byte[] bytes, String type) {
        return new JsBlob(normalizeType(type), bytes.length, BlobSource.of(bytes));
    }

    /// {@return a blob of the bytes of `file`, of MIME type `type`, with the size the file has now}
    ///
    /// The file is read when the blob is: it must keep its size until then.
    ///
    /// @throws IOException if the file's size cannot be read
    public static JsBlob of(Path file, String type) throws IOException {
        return new JsBlob(normalizeType(type), Files.size(file), BlobSource.of(file));
    }

    /// {@return a blob of the `size` bytes `source` holds, of MIME type `type`}
    ///
    /// @throws IllegalArgumentException if `size` is negative
    public static JsBlob of(BlobSource source, long size, String type) {
        Objects.requireNonNull(source, "source");
        if (size < 0) {
            throw new IllegalArgumentException("A blob cannot have " + size + " bytes");
        }
        return new JsBlob(normalizeType(type), size, source);
    }

    /// {@return `type` as the `Blob` constructor keeps it: empty if a character is outside U+0020 to U+007E, else in
    /// ASCII lowercase}
    ///
    /// @spec https://w3c.github.io/FileAPI/#constructorBlob File API
    static String normalizeType(String type) {
        for (var i = 0; i < type.length(); i++) {
            if (type.charAt(i) < 0x20 || type.charAt(i) > 0x7E) {
                return "";
            }
        }
        return type.toLowerCase(Locale.ROOT);
    }

    /// {@return the MIME type, empty if the blob had none}
    public String type() {
        return type;
    }

    /// {@return the size in bytes}
    public long size() {
        return size;
    }

    /// {@return whether the blob has bytes to read: `false` for one a value describes without storing them}
    public boolean hasBytes() {
        return source != null;
    }

    /// {@return a stream of the bytes, which the caller closes}
    ///
    /// @throws com.github.auties00.viridian.ViridianException with reason `MISSING` if the blob was stored, but its
    ///                                                        bytes are gone from the profile
    /// @throws IOException if the blob has no bytes to read, or they cannot be read
    public InputStream open() throws IOException {
        if (source == null) {
            throw new IOException("The bytes of " + this + " were not stored with the value");
        }
        return source.open();
    }

    /// {@return the bytes, read whole}
    ///
    /// @throws OutOfMemoryError if the blob is larger than an array can be
    /// @throws IOException      as [#open()] does
    public byte[] readAllBytes() throws IOException {
        try (var input = open()) {
            return input.readAllBytes();
        }
    }

    /// {@return where the bytes are, or `null` for a blob without bytes}
    BlobSource source() {
        return source;
    }

    @Override
    public String toString() {
        return "Blob(" + size + (type.isEmpty() ? "" : ", " + type) + ")";
    }
}
