package com.github.auties00.viridian.indexeddb;

import com.github.auties00.viridian.IdbKey;
import com.github.auties00.viridian.io.ByteCursor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;

/// Encodes and decodes the two forms Chromium stores IndexedDB keys in.
///
/// The LevelDB backend writes a type byte (1 string, 2 date, 3 number, 4 array, 6 binary), then a varint length of
/// UTF-16 code units and big-endian code units for a string, a host-order (little-endian) double for a date or
/// number, a varint count and the elements for an array, and a varint length and the bytes for a binary key.
///
/// The SQLite backend writes a sortable form, whose unsigned byte order is the keys' IndexedDB order: a type byte
/// (0x10 number, 0x20 date, 0x30 string, 0x40 binary, 0x50 array), then for a number or date the double's bits made
/// sortable (all inverted for a negative number, the sign bit set for the others, `-0` as `0`), big-endian; for a
/// string each code unit in 1, 2 or 3 bytes (`c + 1` up to 0x7E, `0x80 | c >> 8` and `c` up to 0x3FFF, `0xFF` and
/// `c` big-endian beyond), then a 0 byte; for a binary key 8-byte chunks each after a 0x09 marker, the last padded
/// with zeros, then a byte from 1 to 8 counting the last chunk's payload, or a single 0 for an empty key; for an
/// array its elements, then a 0 byte.
final class IdbKeyCodec {
    // The LevelDB backend's type bytes
    private static final int STRING = 1;
    private static final int DATE = 2;
    private static final int NUMBER = 3;
    private static final int ARRAY = 4;
    private static final int BINARY = 6;
    // The sortable form's type bytes
    private static final int SORTABLE_NUMBER = 0x10;
    private static final int SORTABLE_DATE = 0x20;
    private static final int SORTABLE_STRING = 0x30;
    private static final int SORTABLE_BINARY = 0x40;
    private static final int SORTABLE_ARRAY = 0x50;
    private static final int SENTINEL = 0;
    private static final int CHUNK = 8;
    private static final int MARKER = CHUNK + 1;
    // IndexedDB::kMaximumDepth: arrays nest no deeper
    private static final int MAX_DEPTH = 2000;

    private IdbKeyCodec() {
    }

    /// {@return the key the LevelDB form at the cursor holds}
    ///
    /// @throws IOException if the key does not decode, or is not a valid key
    static IdbKey decodeLevelDb(ByteCursor input) throws IOException {
        return decodeLevelDb(input, 0);
    }

    private static IdbKey decodeLevelDb(ByteCursor input, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("A key nests arrays deeper than " + MAX_DEPTH);
        }
        var type = input.u8();
        return switch (type) {
            case STRING -> new IdbKey.StringKey(readUtf16BigEndian(input, input.varintLength()));
            case DATE -> date(input.f64le());
            case NUMBER -> number(input.f64le());
            case ARRAY -> {
                var count = input.varintLength();
                var elements = new ArrayList<IdbKey>(Math.min(count, 64));
                for (var i = 0; i < count; i++) {
                    elements.add(decodeLevelDb(input, depth + 1));
                }
                yield new IdbKey.ArrayKey(elements);
            }
            case BINARY -> new IdbKey.BinaryKey(input.lengthPrefixed());
            default -> throw new IOException("A key has type byte " + type + ", which is no valid key");
        };
    }

    /// {@return `key` in the LevelDB form}
    static byte[] encodeLevelDb(IdbKey key) {
        var output = new ByteArrayOutputStream();
        encodeLevelDb(key, output);
        return output.toByteArray();
    }

    private static void encodeLevelDb(IdbKey key, ByteArrayOutputStream output) {
        switch (key) {
            case IdbKey.StringKey string -> {
                output.write(STRING);
                writeVarint(output, string.value().length());
                for (var i = 0; i < string.value().length(); i++) {
                    var c = string.value().charAt(i);
                    output.write(c >>> 8);
                    output.write(c);
                }
            }
            case IdbKey.DateKey date -> {
                output.write(DATE);
                writeDoubleLittleEndian(output, date.time());
            }
            case IdbKey.NumberKey number -> {
                output.write(NUMBER);
                writeDoubleLittleEndian(output, number.value());
            }
            case IdbKey.ArrayKey array -> {
                output.write(ARRAY);
                writeVarint(output, array.elements().size());
                for (var element : array.elements()) {
                    encodeLevelDb(element, output);
                }
            }
            case IdbKey.BinaryKey binary -> {
                var bytes = binary.bytes();
                output.write(BINARY);
                writeVarint(output, bytes.length);
                output.writeBytes(bytes);
            }
        }
    }

    /// {@return the key the sortable form `data` holds, all of it}
    ///
    /// @throws IOException if the key does not decode, is not a valid key, or has bytes past its end
    static IdbKey decodeSortable(byte[] data) throws IOException {
        var input = new ByteCursor(data);
        var key = decodeSortable(input, 0);
        if (input.hasRemaining()) {
            throw new IOException("A key has " + input.remaining() + " bytes past its end");
        }
        return key;
    }

    private static IdbKey decodeSortable(ByteCursor input, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("A key nests arrays deeper than " + MAX_DEPTH);
        }
        var type = input.u8();
        return switch (type) {
            case SORTABLE_NUMBER -> number(sortableDouble(input));
            case SORTABLE_DATE -> date(sortableDouble(input));
            case SORTABLE_STRING -> new IdbKey.StringKey(sortableString(input));
            case SORTABLE_BINARY -> new IdbKey.BinaryKey(sortableBinary(input));
            case SORTABLE_ARRAY -> {
                var elements = new ArrayList<IdbKey>();
                while (input.peek() != SENTINEL) {
                    elements.add(decodeSortable(input, depth + 1));
                }
                input.u8();
                yield new IdbKey.ArrayKey(elements);
            }
            default -> throw new IOException("A sortable key has type byte 0x" + Integer.toHexString(type));
        };
    }

    /// {@return `key` in the sortable form}
    static byte[] encodeSortable(IdbKey key) {
        var output = new ByteArrayOutputStream();
        encodeSortable(key, output);
        return output.toByteArray();
    }

    private static void encodeSortable(IdbKey key, ByteArrayOutputStream output) {
        switch (key) {
            case IdbKey.NumberKey number -> {
                output.write(SORTABLE_NUMBER);
                writeSortableDouble(output, number.value());
            }
            case IdbKey.DateKey date -> {
                output.write(SORTABLE_DATE);
                writeSortableDouble(output, date.time());
            }
            case IdbKey.StringKey string -> {
                output.write(SORTABLE_STRING);
                for (var i = 0; i < string.value().length(); i++) {
                    var c = string.value().charAt(i);
                    if (c <= 0x7E) {
                        output.write(c + 1);
                    } else if (c <= 0x3FFF) {
                        output.write(0x80 | c >>> 8);
                        output.write(c);
                    } else {
                        output.write(0xFF);
                        output.write(c >>> 8);
                        output.write(c);
                    }
                }
                output.write(SENTINEL);
            }
            case IdbKey.BinaryKey binary -> {
                output.write(SORTABLE_BINARY);
                var bytes = binary.bytes();
                if (bytes.length == 0) {
                    output.write(SENTINEL);
                    return;
                }
                for (var i = 0; i < bytes.length; i += CHUNK) {
                    output.write(MARKER);
                    for (var j = 0; j < CHUNK; j++) {
                        output.write(i + j < bytes.length ? bytes[i + j] : 0);
                    }
                }
                var last = bytes.length % CHUNK;
                output.write(last == 0 ? CHUNK : last);
            }
            case IdbKey.ArrayKey array -> {
                output.write(SORTABLE_ARRAY);
                for (var element : array.elements()) {
                    encodeSortable(element, output);
                }
                output.write(SENTINEL);
            }
        }
    }

    private static double sortableDouble(ByteCursor input) throws IOException {
        var bits = input.i64be();
        bits = bits < 0 ? bits ^ Long.MIN_VALUE : ~bits;
        var value = Double.longBitsToDouble(bits);
        if (Double.isNaN(value) || Double.doubleToRawLongBits(value) == Long.MIN_VALUE) {
            throw new IOException("A sortable key holds NaN or -0, which no key is");
        }
        return value;
    }

    private static void writeSortableDouble(ByteArrayOutputStream output, double value) {
        var bits = Double.doubleToRawLongBits(value == 0 ? 0 : value);
        bits = bits < 0 ? ~bits : bits | Long.MIN_VALUE;
        for (var shift = 56; shift >= 0; shift -= 8) {
            output.write((int) (bits >>> shift));
        }
    }

    private static String sortableString(ByteCursor input) throws IOException {
        var characters = new StringBuilder();
        while (true) {
            var first = input.u8();
            if (first == SENTINEL) {
                return characters.toString();
            }
            if ((first & 0x80) == 0) {
                characters.append((char) (first - 1));
            } else if ((first & 0xC0) == 0x80) {
                var c = (char) ((first & 0x3F) << 8 | input.u8());
                if (c <= 0x7E) {
                    throw new IOException("A sortable string encodes a one-byte character in two bytes");
                }
                characters.append(c);
            } else if (first == 0xFF) {
                var c = (char) (input.u8() << 8 | input.u8());
                if (c <= 0x3FFF) {
                    throw new IOException("A sortable string encodes a two-byte character in three bytes");
                }
                characters.append(c);
            } else {
                throw new IOException("A sortable string has the invalid lead byte 0x" + Integer.toHexString(first));
            }
        }
    }

    private static byte[] sortableBinary(ByteCursor input) throws IOException {
        if (input.peek() == SENTINEL) {
            input.u8();
            return new byte[0];
        }
        var output = new ByteArrayOutputStream();
        while (true) {
            var marker = input.u8();
            if (marker == MARKER) {
                output.writeBytes(input.bytes(CHUNK));
            } else if (marker >= 1 && marker <= CHUNK) {
                var bytes = output.toByteArray();
                var padding = CHUNK - marker;
                if (bytes.length < CHUNK) {
                    throw new IOException("A sortable binary key ends before its first chunk");
                }
                for (var i = 0; i < padding; i++) {
                    if (bytes[bytes.length - 1 - i] != 0) {
                        throw new IOException("A sortable binary key's padding is not zeros");
                    }
                }
                return java.util.Arrays.copyOf(bytes, bytes.length - padding);
            } else {
                throw new IOException("A sortable binary key has the invalid marker " + marker);
            }
        }
    }

    private static IdbKey number(double value) throws IOException {
        if (Double.isNaN(value)) {
            throw new IOException("A key holds NaN, which no key is");
        }
        return new IdbKey.NumberKey(value);
    }

    private static IdbKey date(double value) throws IOException {
        if (Double.isNaN(value)) {
            throw new IOException("A key holds an invalid date, which no key is");
        }
        return new IdbKey.DateKey(value);
    }

    /// {@return `length` big-endian UTF-16 code units, kept as they are, lone surrogates included}
    static String readUtf16BigEndian(ByteCursor input, int length) throws IOException {
        if (length > input.remaining() / 2) {
            throw new IOException("A string of " + length + " code units runs past the data");
        }
        var characters = new char[length];
        for (var i = 0; i < length; i++) {
            characters[i] = (char) (input.u8() << 8 | input.u8());
        }
        return new String(characters);
    }

    /// {@return `bytes` decoded as little-endian UTF-16 code units, as the SQLite backend stores names}
    static String utf16LittleEndian(byte[] bytes) throws IOException {
        if (bytes.length % 2 != 0) {
            throw new IOException("A UTF-16 string has an odd length of " + bytes.length + " bytes");
        }
        var characters = new char[bytes.length / 2];
        for (var i = 0; i < characters.length; i++) {
            characters[i] = (char) ((bytes[2 * i] & 0xFF) | (bytes[2 * i + 1] & 0xFF) << 8);
        }
        return new String(characters);
    }

    static void writeVarint(ByteArrayOutputStream output, long value) {
        while ((value & ~0x7FL) != 0) {
            output.write((int) (value & 0x7F) | 0x80);
            value >>>= 7;
        }
        output.write((int) value);
    }

    private static void writeDoubleLittleEndian(ByteArrayOutputStream output, double value) {
        var bits = Double.doubleToRawLongBits(value);
        for (var shift = 0; shift < 64; shift += 8) {
            output.write((int) (bits >>> shift));
        }
    }
}
