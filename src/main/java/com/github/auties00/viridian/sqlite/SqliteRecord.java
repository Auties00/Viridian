package com.github.auties00.viridian.sqlite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/// A record decoded as far as its header: the serial type and the position of every field over a payload.
///
/// A record is a header followed by a body. The header is its own length as a varint, then one varint serial type
/// per field; the body holds the fields' contents back to back, each as long as its serial type says:
///
/// ```text
/// 0         NULL                                  7       IEEE 754 binary64, big-endian
/// 1 to 6    integer in 1, 2, 3, 4, 6 or 8         8, 9    the integers 0 and 1, with no content
///           big-endian two's-complement bytes     10, 11  reserved for SQLite's internal use
/// N >= 12   even: BLOB of (N - 12) / 2 bytes; odd: text of (N - 13) / 2 bytes in the database's encoding
/// ```
///
/// [#parse(byte\[\], int, int)] checks that the header and the body fill the payload exactly, so decoding a field
/// afterwards never fails. A record keeps a reference to the bytes it was parsed from, which are never modified.
/// [#encode(Object\[\], long)] builds a record as SQLite writes one.
///
/// @spec https://www.sqlite.org/fileformat2.html Database File Format
final class SqliteRecord {
    /// The content sizes of the integer serial types 1 to 6, indexed by serial type.
    private static final int[] INTEGER_SIZES = {0, 1, 2, 3, 4, 6, 8};

    /// The bytes the record lies in.
    private final byte[] data;

    /// The serial type of each field, of which the first [#size] are meaningful.
    private final long[] types;

    /// The offset in [#data] of each field's content.
    private final int[] offsets;

    /// The number of fields.
    private final int size;

    /// Wraps a parsed header.
    private SqliteRecord(byte[] data, long[] types, int[] offsets, int size) {
        this.data = data;
        this.types = types;
        this.offsets = offsets;
        this.size = size;
    }

    /// Parses the header of the record that fills `data[start, start + length)`.
    ///
    /// @throws IOException if the header is truncated, uses a reserved serial type, or describes fields that do not
    ///                     fill the payload exactly
    static SqliteRecord parse(byte[] data, int start, int length) throws IOException {
        var end = start + length;
        var headerLengthEnd = SqliteBytes.varintEnd(data, start, end);
        if (headerLengthEnd < 0) {
            throw new IOException("A record's header length runs past its " + length + "-byte payload");
        }
        var headerLength = SqliteBytes.varint(data, start);
        if (headerLength < headerLengthEnd - start || headerLength > length) {
            throw new IOException("A record declares a " + headerLength + "-byte header in a " + length + "-byte payload");
        }
        var headerEnd = start + (int) headerLength;
        var capacity = Math.max(1, Math.min(16, (int) headerLength));
        var types = new long[capacity];
        var offsets = new int[capacity];
        var size = 0;
        var body = (long) headerEnd;
        var position = headerLengthEnd;
        while (position < headerEnd) {
            var next = SqliteBytes.varintEnd(data, position, headerEnd);
            if (next < 0) {
                throw new IOException("A serial type runs past the end of its record's header");
            }
            var type = SqliteBytes.varint(data, position);
            position = next;
            if (size == types.length) {
                types = Arrays.copyOf(types, size * 2);
                offsets = Arrays.copyOf(offsets, size * 2);
            }
            types[size] = type;
            offsets[size] = (int) body;
            size++;
            body += contentSize(type);
            if (body > end) {
                throw new IOException("A record's fields need more than its " + length + "-byte payload");
            }
        }
        if (body != end) {
            throw new IOException("A record's fields fill " + (body - start) + " of its " + length + " bytes");
        }
        return new SqliteRecord(data, types, offsets, size);
    }

    /// {@return the record of `values`, each `null`, a [Long], a [Double], a [String] or a `byte[]`, in the form SQLite
    /// writes it}
    ///
    /// Every integer takes the narrowest serial type that holds it, 0 and 1 the types 8 and 9 that schema format 4
    /// allows; a REAL is always type 7, and text is UTF-8.
    ///
    /// @throws IllegalArgumentException if the record would be larger than `maxLength` bytes
    static byte[] encode(Object[] values, long maxLength) {
        var types = new long[values.length];
        var texts = new byte[values.length][];
        var headerSize = 0L;
        var bodySize = 0L;
        for (var i = 0; i < values.length; i++) {
            switch (values[i]) {
                case null -> types[i] = 0;
                case Long integer -> {
                    types[i] = integerType(integer);
                    bodySize += integerWidth(types[i]);
                }
                case Double _ -> {
                    types[i] = 7;
                    bodySize += 8;
                }
                case String text -> {
                    texts[i] = text.getBytes(StandardCharsets.UTF_8);
                    types[i] = texts[i].length * 2L + 13;
                    bodySize += texts[i].length;
                }
                case byte[] blob -> {
                    types[i] = blob.length * 2L + 12;
                    bodySize += blob.length;
                }
                default -> throw new IllegalArgumentException("A record cannot hold a " + values[i].getClass().getName());
            }
            headerSize += SqliteBytes.varintLength(types[i]);
        }
        if (headerSize <= 126) {
            headerSize++;
        } else {
            var length = SqliteBytes.varintLength(headerSize);
            headerSize += length;
            if (length < SqliteBytes.varintLength(headerSize)) {
                headerSize++;
            }
        }
        var size = headerSize + bodySize;
        if (size > maxLength) {
            throw new IllegalArgumentException("A record of " + size + " bytes is larger than the " + maxLength + " SQLite allows");
        }
        var record = new byte[(int) size];
        var header = SqliteBytes.putVarint(record, 0, headerSize);
        var body = (int) headerSize;
        for (var i = 0; i < values.length; i++) {
            header = SqliteBytes.putVarint(record, header, types[i]);
            switch (values[i]) {
                case Long integer -> {
                    var width = integerWidth(types[i]);
                    for (var j = 0; j < width; j++) {
                        record[body + j] = (byte) (integer >>> 8 * (width - 1 - j));
                    }
                    body += width;
                }
                case Double real -> {
                    var bits = Double.doubleToRawLongBits(real);
                    SqliteBytes.putI32(record, body, (int) (bits >>> 32));
                    SqliteBytes.putI32(record, body + 4, (int) bits);
                    body += 8;
                }
                case String _ -> {
                    System.arraycopy(texts[i], 0, record, body, texts[i].length);
                    body += texts[i].length;
                }
                case byte[] blob -> {
                    System.arraycopy(blob, 0, record, body, blob.length);
                    body += blob.length;
                }
                case null, default -> {
                }
            }
        }
        return record;
    }

    /// {@return the content size of the integer serial type `type`, 0 for the types 8 and 9}
    private static int integerWidth(long type) {
        return type <= 6 ? INTEGER_SIZES[(int) type] : 0;
    }

    /// {@return the narrowest serial type that holds `value`}
    private static long integerType(long value) {
        if (value == 0 || value == 1) {
            return 8 + value;
        }
        var magnitude = value < 0 ? ~value : value;
        if (magnitude <= 127) {
            return 1;
        }
        if (magnitude <= 32767) {
            return 2;
        }
        if (magnitude <= 8388607) {
            return 3;
        }
        if (magnitude <= 2147483647) {
            return 4;
        }
        if (magnitude <= 140737488355327L) {
            return 5;
        }
        return 6;
    }

    /// {@return how many bytes of content a field of serial type `type` has}
    ///
    /// @throws IOException for the reserved serial types 10 and 11, and for one too large to be a length
    static long contentSize(long type) throws IOException {
        if (type < 0) {
            throw new IOException("Serial type " + Long.toUnsignedString(type) + " describes a field larger than any record");
        }
        if (type >= 12) {
            return (type - 12) >>> 1;
        }
        return switch ((int) type) {
            case 0, 8, 9 -> 0;
            case 1, 2, 3, 4 -> type;
            case 5 -> 6;
            case 6, 7 -> 8;
            default -> throw new IOException("Serial type " + type + " is reserved for SQLite's internal use and never appears in a database file");
        };
    }

    /// {@return the number of fields}
    int size() {
        return size;
    }

    /// {@return the serial type of `field`}
    long serialType(int field) {
        return types[field];
    }

    /// {@return whether `field` reads as `NULL`, which a NaN stored as a REAL does, as in SQLite}
    boolean isNull(int field) {
        var type = types[field];
        return type == 0 || type == 7 && Double.isNaN(real(data, offsets[field]));
    }

    /// {@return whether `field` holds an integer}
    boolean isInteger(int field) {
        var type = types[field];
        return type >= 1 && type <= 6 || type == 8 || type == 9;
    }

    /// {@return the value of `field`, an integer field, as a `long`}
    long integer(int field) {
        return integer(data, offsets[field], types[field]);
    }

    /// {@return the value of `field`: `null`, a [Long], a [Double], a [String] or a `byte[]` of its own}
    Object value(int field, SqliteTextEncoding encoding) {
        var type = types[field];
        var offset = offsets[field];
        if (type >= 12) {
            var length = (int) ((type - 12) >>> 1);
            if ((type & 1) == 0) {
                return Arrays.copyOfRange(data, offset, offset + length);
            }
            return encoding.decode(data, offset, length);
        }
        if (type == 0) {
            return null;
        }
        if (type == 7) {
            var real = real(data, offset);
            return Double.isNaN(real) ? null : real;
        }
        return integer(data, offset, type);
    }

    /// {@return the integer of serial type `type`, one of 1 to 6, 8 and 9, whose content starts at `offset`}
    static long integer(byte[] data, int offset, long type) {
        if (type == 8) {
            return 0;
        }
        if (type == 9) {
            return 1;
        }
        var size = INTEGER_SIZES[(int) type];
        long value = data[offset];
        for (var i = 1; i < size; i++) {
            value = value << 8 | data[offset + i] & 0xFF;
        }
        return value;
    }

    /// {@return the big-endian IEEE 754 binary64 at `offset`}
    static double real(byte[] data, int offset) {
        return Double.longBitsToDouble((long) SqliteBytes.i32(data, offset) << 32 | SqliteBytes.u32(data, offset + 4));
    }

    /// {@return how many payload bytes, counted from the start of the record, comparing its first `fields` fields
    /// needs}
    ///
    /// Only `data[start, start + available)` of the payload's `payloadSize` bytes is present, the part stored on the
    /// b-tree page. When the header itself does not fit there the result is the header's length, and the caller asks
    /// again once it has that many bytes.
    ///
    /// @throws IOException if the header is malformed, or the record has fewer than `fields` fields
    static long prefixLength(byte[] data, int start, int available, long payloadSize, int fields) throws IOException {
        if (fields == 0) {
            return 0;
        }
        var limit = start + available;
        var headerLengthEnd = SqliteBytes.varintEnd(data, start, limit);
        if (headerLengthEnd < 0) {
            if (available >= Math.min(9, payloadSize)) {
                throw new IOException("An index entry's header length runs past its " + payloadSize + "-byte payload");
            }
            return Math.min(9, payloadSize);
        }
        var headerLength = SqliteBytes.varint(data, start);
        if (headerLength < headerLengthEnd - start || headerLength > payloadSize) {
            throw new IOException("An index entry declares a " + headerLength + "-byte header in a " + payloadSize + "-byte payload");
        }
        if (headerLength > available) {
            return headerLength;
        }
        var headerEnd = start + (int) headerLength;
        var position = headerLengthEnd;
        var body = headerLength;
        for (var i = 0; i < fields; i++) {
            if (position >= headerEnd) {
                throw new IOException("An index entry has " + i + " fields, fewer than the " + fields + " its key compares");
            }
            var next = SqliteBytes.varintEnd(data, position, headerEnd);
            if (next < 0) {
                throw new IOException("A serial type runs past the end of an index entry's header");
            }
            body += contentSize(SqliteBytes.varint(data, position));
            position = next;
            if (body > payloadSize) {
                throw new IOException("An index entry's fields need more than its " + payloadSize + "-byte payload");
            }
        }
        return body;
    }

    /// {@return the sign of comparing the record that starts at `start` with `key`, over the key's fields}
    ///
    /// The bytes [#prefixLength(byte\[\], int, int, long, int)] asked for must be present, so the header has been
    /// checked and every compared field is in range.
    static int comparePrefix(byte[] data, int start, SqliteKey key) {
        var headerLengthEnd = SqliteBytes.varintEnd(data, start, data.length);
        var body = start + (int) SqliteBytes.varint(data, start);
        var position = headerLengthEnd;
        for (var i = 0; i < key.size(); i++) {
            var next = SqliteBytes.varintEnd(data, position, data.length);
            var type = SqliteBytes.varint(data, position);
            position = next;
            var result = key.compare(i, data, body, type);
            if (result != 0) {
                return result;
            }
            body += (int) (type >= 12 ? (type - 12) >>> 1 : switch ((int) type) {
                case 0, 8, 9 -> 0;
                case 5 -> 6;
                case 6, 7 -> 8;
                default -> type;
            });
        }
        return 0;
    }
}
