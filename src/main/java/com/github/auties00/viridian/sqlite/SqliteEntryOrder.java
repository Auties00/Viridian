package com.github.auties00.viridian.sqlite;

import java.util.Comparator;

/// The order of the entries of an index b-tree, which holds an index or a `WITHOUT ROWID` table: the records compared
/// field by field as SQLite compares values.
///
/// The storage classes order `NULL` first, then numbers, with INTEGER and REAL compared by value, then text, compared
/// by the field's collation, then BLOBs, compared byte by byte with a prefix first. A field marked descending reverses
/// its comparison. When every compared field is equal, the record with fewer fields sorts first, which makes a record
/// of a key's leading fields sort before every entry that starts with them.
///
/// @spec https://www.sqlite.org/datatype3.html#comparisons Comparison Expressions
final class SqliteEntryOrder implements Comparator<byte[]> {
    /// The storage class of `NULL`, which sorts first.
    private static final int NULL = 0;

    /// The storage class of INTEGER and REAL values.
    private static final int NUMBER = 1;

    /// The storage class of text.
    private static final int TEXT = 2;

    /// The storage class of BLOBs, which sort last.
    private static final int BLOB = 3;

    /// The collation of each field; fields past the end compare as [SqliteCollation#BINARY].
    private final SqliteCollation[] collations;

    /// Whether each field sorts in descending order; fields past the end sort in ascending order.
    private final boolean[] descending;

    /// The number of leading fields [#compare(byte\[\], byte\[\])] compares.
    private final int fields;

    /// Creates an order.
    ///
    /// @param fields the number of leading fields that order two entries: all of them for an index, the primary key's
    ///               for a `WITHOUT ROWID` table
    SqliteEntryOrder(SqliteCollation[] collations, boolean[] descending, int fields) {
        this.collations = collations;
        this.descending = descending;
        this.fields = fields;
    }

    /// {@return the sign of comparing the records `first` and `second` over their leading fields}
    @Override
    public int compare(byte[] first, byte[] second) {
        return compare(first, second, fields);
    }

    /// {@return the sign of comparing the records `first` and `second` over at most their first `count` fields}
    int compare(byte[] first, byte[] second, int count) {
        var firstHeader = (int) SqliteBytes.varint(first, 0);
        var secondHeader = (int) SqliteBytes.varint(second, 0);
        var firstPosition = SqliteBytes.varintEnd(first, 0, firstHeader);
        var secondPosition = SqliteBytes.varintEnd(second, 0, secondHeader);
        var firstBody = firstHeader;
        var secondBody = secondHeader;
        for (var i = 0; i < count; i++) {
            var firstMore = firstPosition < firstHeader;
            var secondMore = secondPosition < secondHeader;
            if (!firstMore || !secondMore) {
                return firstMore == secondMore ? 0 : firstMore ? 1 : -1;
            }
            var firstSerial = SqliteBytes.varint(first, firstPosition);
            var secondSerial = SqliteBytes.varint(second, secondPosition);
            firstPosition = SqliteBytes.varintEnd(first, firstPosition, firstHeader);
            secondPosition = SqliteBytes.varintEnd(second, secondPosition, secondHeader);
            var result = compareField(i, first, firstBody, firstSerial, second, secondBody, secondSerial);
            if (result != 0) {
                return i < descending.length && descending[i] ? -result : result;
            }
            firstBody += (int) contentSize(firstSerial);
            secondBody += (int) contentSize(secondSerial);
        }
        return 0;
    }

    /// {@return the sign of comparing the field of serial type `firstType` at `first[firstOffset]` with the one of
    /// serial type `secondType` at `second[secondOffset]`, both field `field` of their record}
    private int compareField(int field, byte[] first, int firstOffset, long firstType, byte[] second, int secondOffset, long secondType) {
        var firstClass = storageClass(firstType);
        var secondClass = storageClass(secondType);
        if (firstClass != secondClass) {
            return Integer.compare(firstClass, secondClass);
        }
        return switch (firstClass) {
            case NULL -> 0;
            case NUMBER -> {
                if (firstType == 7 && secondType == 7) {
                    var a = SqliteRecord.real(first, firstOffset);
                    var b = SqliteRecord.real(second, secondOffset);
                    yield a < b ? -1 : a > b ? 1 : 0;
                }
                if (firstType == 7) {
                    yield -SqliteKey.compareIntegerToReal(SqliteRecord.integer(second, secondOffset, secondType), SqliteRecord.real(first, firstOffset));
                }
                if (secondType == 7) {
                    yield SqliteKey.compareIntegerToReal(SqliteRecord.integer(first, firstOffset, firstType), SqliteRecord.real(second, secondOffset));
                }
                yield Long.compare(SqliteRecord.integer(first, firstOffset, firstType), SqliteRecord.integer(second, secondOffset, secondType));
            }
            case TEXT -> {
                var collation = field < collations.length ? collations[field] : SqliteCollation.BINARY;
                yield collation.compare(first, firstOffset, firstOffset + (int) contentSize(firstType),
                        second, secondOffset, secondOffset + (int) contentSize(secondType));
            }
            default -> SqliteCollation.BINARY.compare(first, firstOffset, firstOffset + (int) contentSize(firstType),
                    second, secondOffset, secondOffset + (int) contentSize(secondType));
        };
    }

    /// {@return the storage class of a field of serial type `type`, which is never a reserved type}
    private static int storageClass(long type) {
        if (type == 0) {
            return NULL;
        }
        if (type < 12) {
            return NUMBER;
        }
        return (type & 1) == 0 ? BLOB : TEXT;
    }

    /// {@return the content size of a field of serial type `type`, which is never a reserved type}
    private static long contentSize(long type) {
        if (type >= 12) {
            return (type - 12) >>> 1;
        }
        return switch ((int) type) {
            case 0, 8, 9 -> 0;
            case 5 -> 6;
            case 6, 7 -> 8;
            default -> type;
        };
    }
}
