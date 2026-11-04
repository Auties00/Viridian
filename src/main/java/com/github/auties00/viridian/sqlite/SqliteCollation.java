package com.github.auties00.viridian.sqlite;

import java.util.Arrays;

/// A collating sequence built into SQLite: how it orders two texts, and so the order of the index entries that compare
/// them.
///
/// Text is compared in its UTF-8 bytes, which order as the code points do. When the compared part of one text is a
/// prefix of the other's, the shorter text sorts first.
///
/// @spec https://www.sqlite.org/datatype3.html#collating_sequences Collating Sequences
enum SqliteCollation {
    /// Byte by byte; the default.
    BINARY,

    /// Byte by byte, with the 26 ASCII capital letters folded to lower case and every other character compared as it is.
    NOCASE,

    /// Byte by byte, ignoring the spaces, U+0020 only, that end either text.
    RTRIM;

    /// {@return the collation named `name`, ignoring the case of ASCII letters, or `null` if SQLite has none of that name}
    static SqliteCollation of(String name) {
        for (var collation : values()) {
            if (SqliteBytes.equalsIgnoreCase(collation.name(), name)) {
                return collation;
            }
        }
        return null;
    }

    /// {@return the sign of comparing the UTF-8 texts `first[firstStart, firstEnd)` and `second[secondStart, secondEnd)`}
    int compare(byte[] first, int firstStart, int firstEnd, byte[] second, int secondStart, int secondEnd) {
        return switch (this) {
            case BINARY -> Integer.signum(Arrays.compareUnsigned(first, firstStart, firstEnd, second, secondStart, secondEnd));
            case NOCASE -> {
                var length = Math.min(firstEnd - firstStart, secondEnd - secondStart);
                for (var i = 0; i < length; i++) {
                    var difference = fold(first[firstStart + i]) - fold(second[secondStart + i]);
                    if (difference != 0) {
                        yield Integer.signum(difference);
                    }
                }
                yield Integer.compare(firstEnd - firstStart, secondEnd - secondStart);
            }
            case RTRIM -> {
                while (firstEnd > firstStart && first[firstEnd - 1] == ' ') {
                    firstEnd--;
                }
                while (secondEnd > secondStart && second[secondEnd - 1] == ' ') {
                    secondEnd--;
                }
                yield Integer.signum(Arrays.compareUnsigned(first, firstStart, firstEnd, second, secondStart, secondEnd));
            }
        };
    }

    /// {@return the unsigned value of `value`, with an ASCII capital letter folded to lower case}
    private static int fold(byte value) {
        var unsigned = value & 0xFF;
        return unsigned >= 'A' && unsigned <= 'Z' ? unsigned + ('a' - 'A') : unsigned;
    }
}
