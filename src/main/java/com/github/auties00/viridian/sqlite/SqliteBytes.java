package com.github.auties00.viridian.sqlite;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Arrays;

/// Reads and writes the big-endian integers and varints of the SQLite file formats, and reads the files that hold them.
///
/// A varint is one to nine bytes, most significant group first. Each of the first eight bytes contributes its low
/// seven bits and continues the varint when its high bit is set; a ninth byte contributes all eight bits.
///
/// @spec https://www.sqlite.org/fileformat2.html Database File Format
final class SqliteBytes {
    /// Never instantiated.
    private SqliteBytes() {
    }

    /// {@return the unsigned byte at `offset`}
    static int u8(byte[] data, int offset) {
        return data[offset] & 0xFF;
    }

    /// {@return the unsigned big-endian 16-bit integer at `offset`}
    static int u16(byte[] data, int offset) {
        return (data[offset] & 0xFF) << 8 | data[offset + 1] & 0xFF;
    }

    /// {@return the big-endian 32-bit integer at `offset`, as a signed `int`}
    static int i32(byte[] data, int offset) {
        return (data[offset] & 0xFF) << 24
                | (data[offset + 1] & 0xFF) << 16
                | (data[offset + 2] & 0xFF) << 8
                | data[offset + 3] & 0xFF;
    }

    /// {@return the unsigned big-endian 32-bit integer at `offset`}
    static long u32(byte[] data, int offset) {
        return i32(data, offset) & 0xFFFFFFFFL;
    }

    /// {@return the little-endian 32-bit integer at `offset`}
    static int i32LittleEndian(byte[] data, int offset) {
        return data[offset] & 0xFF
                | (data[offset + 1] & 0xFF) << 8
                | (data[offset + 2] & 0xFF) << 16
                | (data[offset + 3] & 0xFF) << 24;
    }

    /// {@return the offset just past the varint that starts at `offset`, or -1 if it does not end before `limit`}
    static int varintEnd(byte[] data, int offset, int limit) {
        var end = Math.min(limit, offset + 9);
        for (var i = offset; i < end; i++) {
            if ((data[i] & 0x80) == 0 || i == offset + 8) {
                return i + 1;
            }
        }
        return -1;
    }

    /// {@return the value of the varint at `offset`, which [#varintEnd(byte\[\], int, int)] has bounded}
    ///
    /// A nine-byte varint fills all 64 bits, so the value is negative when its top bit is set, as a rowid can be.
    static long varint(byte[] data, int offset) {
        var value = 0L;
        for (var i = 0; i < 8; i++) {
            var current = data[offset + i];
            value = value << 7 | current & 0x7F;
            if ((current & 0x80) == 0) {
                return value;
            }
        }
        return value << 8 | data[offset + 8] & 0xFF;
    }

    /// Writes `value` as an unsigned big-endian 16-bit integer at `offset`.
    static void putU16(byte[] data, int offset, int value) {
        data[offset] = (byte) (value >>> 8);
        data[offset + 1] = (byte) value;
    }

    /// Writes `value` as a big-endian 32-bit integer at `offset`.
    static void putI32(byte[] data, int offset, int value) {
        data[offset] = (byte) (value >>> 24);
        data[offset + 1] = (byte) (value >>> 16);
        data[offset + 2] = (byte) (value >>> 8);
        data[offset + 3] = (byte) value;
    }

    /// {@return the number of bytes the varint of `value` takes, from 1 to 9}
    ///
    /// A value with any of its top eight bits set takes all nine bytes; any other takes one byte per seven bits.
    static int varintLength(long value) {
        if ((value & 0xFF00000000000000L) != 0) {
            return 9;
        }
        var length = 1;
        while ((value >>>= 7) != 0) {
            length++;
        }
        return length;
    }

    /// Writes the varint of `value` at `offset`, in the shortest form SQLite writes.
    ///
    /// @return the offset just past the varint
    static int putVarint(byte[] data, int offset, long value) {
        if ((value & 0xFF00000000000000L) != 0) {
            data[offset + 8] = (byte) value;
            value >>>= 8;
            for (var i = 7; i >= 0; i--) {
                data[offset + i] = (byte) (value & 0x7F | 0x80);
                value >>>= 7;
            }
            return offset + 9;
        }
        var length = varintLength(value);
        for (var i = length - 1; i >= 0; i--) {
            data[offset + i] = (byte) (value & 0x7F | (i == length - 1 ? 0 : 0x80));
            value >>>= 7;
        }
        return offset + length;
    }

    /// Reads `length` bytes of `channel` at `position` into `destination`, zero-filling what lies past the end of the
    /// file, as SQLite does for a short read.
    ///
    /// @return how many bytes came from the file
    static int read(FileChannel channel, long position, byte[] destination, int offset, int length) throws IOException {
        var buffer = ByteBuffer.wrap(destination, offset, length);
        var read = 0;
        while (buffer.hasRemaining()) {
            var count = channel.read(buffer, position + read);
            if (count < 0) {
                break;
            }
            read += count;
        }
        Arrays.fill(destination, offset + read, offset + length, (byte) 0);
        return read;
    }

    /// {@return the file next to `database` whose name is the database's followed by `suffix`, such as `-wal`}
    static Path sibling(Path database, String suffix) {
        var name = database.getFileName();
        if (name == null) {
            return Path.of(database + suffix);
        }
        return database.resolveSibling(name + suffix);
    }

    /// {@return whether `first` and `second` are equal when ASCII letters are folded to one case}
    ///
    /// SQLite folds only ASCII when it compares identifiers, so `"STRASSE"` and `"strasse"` match but `"I"` and the
    /// dotless `"i"` do not, unlike [String#equalsIgnoreCase(String)].
    static boolean equalsIgnoreCase(String first, String second) {
        if (first.length() != second.length()) {
            return false;
        }
        for (var i = 0; i < first.length(); i++) {
            if (lowercase(first.charAt(i)) != lowercase(second.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /// {@return `text` with its ASCII letters in lower case and every other character unchanged}
    static String lowercase(String text) {
        var builder = new StringBuilder(text.length());
        for (var i = 0; i < text.length(); i++) {
            builder.append(lowercase(text.charAt(i)));
        }
        return builder.toString();
    }

    /// {@return `character` in lower case if it is an ASCII letter, else unchanged}
    static char lowercase(char character) {
        return character >= 'A' && character <= 'Z' ? (char) (character + ('a' - 'A')) : character;
    }
}
