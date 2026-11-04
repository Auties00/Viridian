package com.github.auties00.viridian.sqlite;

import java.util.Arrays;
import java.util.List;

/// A key to seek a b-tree to, compared with stored fields as SQLite compares values.
///
/// The storage classes order `NULL` first, then numbers, with INTEGER and REAL compared by value, then text, then
/// BLOB. Text is compared under the `BINARY` collation, byte by byte in the database's encoding, so the key's strings
/// are encoded once, when the key is built; BLOBs are compared byte by byte. A shorter value that is a prefix of a
/// longer one sorts first. Values are compared as given: no column affinity is applied to them.
///
/// @spec https://www.sqlite.org/datatype3.html Datatypes In SQLite
final class SqliteKey {
    /// The storage class of `NULL`, which sorts first.
    private static final int NULL = 0;

    /// The storage class of INTEGER and REAL values.
    private static final int NUMBER = 1;

    /// The storage class of text.
    private static final int TEXT = 2;

    /// The storage class of BLOBs, which sort last.
    private static final int BLOB = 3;

    /// The storage class of each key value.
    private final int[] classes;

    /// Each key value: `null`, a [Long], a [Double], or the bytes of a text or a BLOB.
    private final Object[] values;

    /// Wraps normalised key values.
    private SqliteKey(int[] classes, Object[] values) {
        this.classes = classes;
        this.values = values;
    }

    /// {@return the key of `values`, whose strings are encoded in `encoding`}
    ///
    /// A value may be `null`, a [Long], [Integer], [Short] or [Byte], a [Double] or [Float], a [String] or a `byte[]`.
    /// A NaN is `NULL`, as SQLite stores it.
    ///
    /// @throws IllegalArgumentException if a value is of another type
    static SqliteKey of(List<?> values, SqliteTextEncoding encoding) {
        var classes = new int[values.size()];
        var normalised = new Object[values.size()];
        for (var i = 0; i < classes.length; i++) {
            switch (values.get(i)) {
                case null -> classes[i] = NULL;
                case Long value -> {
                    classes[i] = NUMBER;
                    normalised[i] = value;
                }
                case Integer value -> {
                    classes[i] = NUMBER;
                    normalised[i] = value.longValue();
                }
                case Short value -> {
                    classes[i] = NUMBER;
                    normalised[i] = value.longValue();
                }
                case Byte value -> {
                    classes[i] = NUMBER;
                    normalised[i] = value.longValue();
                }
                case Double value when value.isNaN() -> classes[i] = NULL;
                case Double value -> {
                    classes[i] = NUMBER;
                    normalised[i] = value;
                }
                case Float value when value.isNaN() -> classes[i] = NULL;
                case Float value -> {
                    classes[i] = NUMBER;
                    normalised[i] = value.doubleValue();
                }
                case String value -> {
                    classes[i] = TEXT;
                    normalised[i] = encoding.encode(value);
                }
                case byte[] value -> {
                    classes[i] = BLOB;
                    normalised[i] = value.clone();
                }
                default -> throw new IllegalArgumentException("Key value " + i + " is a " + values.get(i).getClass().getName()
                        + "; a key value must be null, an integer, a floating-point number, a String or a byte[]");
            }
        }
        return new SqliteKey(classes, normalised);
    }

    /// {@return the number of fields the key compares}
    int size() {
        return classes.length;
    }

    /// {@return the sign of comparing the stored field of serial type `type`, whose content starts at `offset`, with
    /// key value `index`}
    int compare(int index, byte[] data, int offset, long type) {
        var keyClass = classes[index];
        int storedClass;
        if (type == 0) {
            storedClass = NULL;
        } else if (type == 7) {
            storedClass = Double.isNaN(SqliteRecord.real(data, offset)) ? NULL : NUMBER;
        } else if (type < 12) {
            storedClass = NUMBER;
        } else {
            storedClass = (type & 1) == 0 ? BLOB : TEXT;
        }
        if (storedClass != keyClass) {
            return Integer.compare(storedClass, keyClass);
        }
        return switch (storedClass) {
            case NULL -> 0;
            case NUMBER -> compareNumbers(data, offset, type, values[index]);
            default -> {
                var bytes = (byte[]) values[index];
                var length = (int) ((type - 12) >>> 1);
                yield Integer.signum(Arrays.compareUnsigned(data, offset, offset + length, bytes, 0, bytes.length));
            }
        };
    }

    /// {@return the sign of comparing the stored number of serial type `type` with the key number `key`}
    private static int compareNumbers(byte[] data, int offset, long type, Object key) {
        if (type == 7) {
            var stored = SqliteRecord.real(data, offset);
            return switch (key) {
                case Long integer -> -compareIntegerToReal(integer, stored);
                case Double real -> stored < real ? -1 : stored > real ? 1 : 0;
                default -> throw new AssertionError(key);
            };
        }
        var stored = SqliteRecord.integer(data, offset, type);
        return switch (key) {
            case Long integer -> Long.compare(stored, integer);
            case Double real -> compareIntegerToReal(stored, real);
            default -> throw new AssertionError(key);
        };
    }

    /// {@return the sign of comparing `integer` with `real` exactly, which converting either to the other's type
    /// would not be for integers beyond 2^53}
    ///
    /// This is SQLite's own comparison; `real` is never NaN, since NaN is `NULL`.
    static int compareIntegerToReal(long integer, double real) {
        if (real < -9223372036854775808.0) {
            return 1;
        }
        if (real >= 9223372036854775808.0) {
            return -1;
        }
        var truncated = (long) real;
        if (integer != truncated) {
            return integer < truncated ? -1 : 1;
        }
        var converted = (double) integer;
        return converted < real ? -1 : converted > real ? 1 : 0;
    }
}
