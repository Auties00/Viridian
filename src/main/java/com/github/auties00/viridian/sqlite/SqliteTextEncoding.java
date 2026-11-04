package com.github.auties00.viridian.sqlite;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/// The text encoding of a SQLite database: every string the file holds uses it, the schema's included.
///
/// A database takes its encoding from the `PRAGMA encoding` that runs before its first table is created and keeps it
/// for life; the header stores it as a 32-bit integer at offset 56.
///
/// @spec https://www.sqlite.org/fileformat2.html Database File Format
public enum SqliteTextEncoding {
    /// UTF-8, header value 1 and the default.
    UTF_8(StandardCharsets.UTF_8),

    /// UTF-16 in little-endian byte order, header value 2.
    UTF_16LE(StandardCharsets.UTF_16LE),

    /// UTF-16 in big-endian byte order, header value 3.
    UTF_16BE(StandardCharsets.UTF_16BE);

    /// The charset that decodes and encodes this encoding.
    private final Charset charset;

    /// Binds the constant to its charset.
    SqliteTextEncoding(Charset charset) {
        this.charset = charset;
    }

    /// {@return the charset of this encoding}
    public Charset charset() {
        return charset;
    }

    /// {@return the encoding that the header's text-encoding field names}
    ///
    /// SQLite reads only the field's two low bits and treats 0, which a database with no schema yet may hold, as
    /// UTF-8; this method does the same.
    static SqliteTextEncoding ofHeader(long value) {
        return switch ((int) (value & 3)) {
            case 2 -> UTF_16LE;
            case 3 -> UTF_16BE;
            default -> UTF_8;
        };
    }

    /// {@return the text stored in `data[offset, offset + length)`}
    ///
    /// Malformed sequences decode to U+FFFD rather than failing: SQLite stores text without validating it.
    String decode(byte[] data, int offset, int length) {
        return new String(data, offset, length, charset);
    }

    /// {@return `text` in this encoding, the form SQLite's `BINARY` collation compares byte by byte}
    byte[] encode(String text) {
        return text.getBytes(charset);
    }
}
