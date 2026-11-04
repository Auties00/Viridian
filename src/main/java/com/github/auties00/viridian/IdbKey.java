package com.github.auties00.viridian;

import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/// An IndexedDB key: a number, a date, a string, a binary key or an array of keys.
///
/// Keys are ordered as IndexedDB orders them: numbers before dates before strings before binary keys before arrays;
/// numbers and dates numerically, strings by UTF-16 code unit, binary keys by unsigned byte, and arrays element by
/// element, then by length. Equality agrees with that order, so `0` and `-0` are one key, which a sorted map and a
/// hash set both treat as such; a number key keeps the sign it was made with all the same.
///
/// {@snippet lang = java :
/// IdbKey theme = IdbKey.of("theme");
/// IdbKey user = IdbKey.of(42);
/// IdbKey entry = IdbKey.of(IdbKey.of("chat-7"), IdbKey.of(1001));   // an array key
/// }
///
/// @spec https://w3c.github.io/IndexedDB/#key-construct Indexed Database API 3.0: keys
public sealed interface IdbKey extends Comparable<IdbKey> {
    /// {@return the string key `value`}
    static StringKey of(String value) {
        return new StringKey(value);
    }

    /// {@return the number key `value`}
    ///
    /// @throws IllegalArgumentException if `value` is `NaN`, which is not a valid key
    static NumberKey of(double value) {
        return new NumberKey(value);
    }

    /// {@return the number key `value`, exactly}
    ///
    /// @throws ArithmeticException if `value` is more than 2^53 from zero, past which a number key may not hold it
    static NumberKey of(long value) {
        if (value > 1L << 53 || value < -(1L << 53)) {
            throw new ArithmeticException(value + " is past 2^53, where a number key may not hold it exactly: use of(double)");
        }
        return new NumberKey(value);
    }

    /// {@return the date key of `time`, to the millisecond}
    ///
    /// @throws IllegalArgumentException if `time` is more than 8.64e15 milliseconds from the epoch, which no date can be
    static DateKey of(Instant time) {
        long millis;
        try {
            millis = Objects.requireNonNull(time, "time").toEpochMilli();
        } catch (ArithmeticException _) {
            millis = Long.MAX_VALUE;
        }
        return new DateKey(millis);
    }

    /// {@return the binary key of a copy of `bytes`}
    static BinaryKey of(byte[] bytes) {
        return new BinaryKey(bytes);
    }

    /// {@return the array key of `elements`}
    static ArrayKey of(IdbKey... elements) {
        return new ArrayKey(Arrays.asList(elements));
    }

    /// A number key.
    ///
    /// @param value the number, never `NaN`; `-0` keeps its sign, though it equals `0`
    record NumberKey(double value) implements IdbKey {
        /// Creates a number key.
        ///
        /// @throws IllegalArgumentException if `value` is `NaN`, which is not a valid key
        public NumberKey {
            if (Double.isNaN(value)) {
                throw new IllegalArgumentException("NaN is not a valid key");
            }
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof NumberKey key && key.value == value;
        }

        @Override
        public int hashCode() {
            return Double.hashCode(value == 0 ? 0 : value);
        }

        @Override
        public String toString() {
            return value == Math.rint(value) && Math.abs(value) < 0x1p63 && !(value == 0 && 1 / value < 0)
                    ? Long.toString((long) value) : Double.toString(value);
        }
    }

    /// A date key.
    ///
    /// @param time the milliseconds since the Unix epoch, a whole number, at most 8.64e15 from it
    record DateKey(double time) implements IdbKey {
        // The furthest a date may be from the epoch, in milliseconds: 100 million days
        private static final double MAX_TIME = 8.64e15;

        /// Creates a date key, clipping its time as JavaScript's `TimeClip` does: truncated to a whole millisecond.
        ///
        /// @throws IllegalArgumentException if `time` is not finite or is more than 8.64e15 from the epoch, an invalid
        ///                                  date, which is not a valid key
        /// @spec https://tc39.es/ecma262/#sec-timeclip TimeClip
        public DateKey {
            if (!Double.isFinite(time) || Math.abs(time) > MAX_TIME) {
                throw new IllegalArgumentException("An invalid date is not a valid key: " + time);
            }
            time = (long) time;
        }

        /// {@return the instant of the date}
        public Instant instant() {
            return Instant.ofEpochMilli((long) time);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof DateKey key && key.time == time;
        }

        @Override
        public int hashCode() {
            return Double.hashCode(time == 0 ? 0 : time);
        }

        @Override
        public String toString() {
            return "Date(" + instant() + ")";
        }
    }

    /// A string key.
    ///
    /// @param value the string
    record StringKey(String value) implements IdbKey {
        /// Creates a string key.
        public StringKey {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String toString() {
            return '"' + value + '"';
        }
    }

    /// A binary key, from an `ArrayBuffer` or a view of one.
    ///
    /// @param bytes the bytes
    record BinaryKey(byte[] bytes) implements IdbKey {
        /// Creates a binary key, copying `bytes`.
        public BinaryKey {
            bytes = bytes.clone();
        }

        /// {@return a copy of the bytes}
        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof BinaryKey key && Arrays.equals(bytes, key.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }

        @Override
        public String toString() {
            return "Binary(" + HexFormat.of().formatHex(bytes) + ")";
        }
    }

    /// An array key.
    ///
    /// @param elements the keys it holds
    record ArrayKey(List<IdbKey> elements) implements IdbKey {
        /// Creates an array key, copying `elements`.
        public ArrayKey {
            elements = List.copyOf(elements);
        }

        @Override
        public String toString() {
            return elements.toString();
        }
    }

    @Override
    default int compareTo(IdbKey other) {
        var rank = Integer.compare(rank(this), rank(other));
        if (rank != 0) {
            return rank;
        }
        return switch (this) {
            case NumberKey number -> Double.compare(number.value() == 0 ? 0 : number.value(), ((NumberKey) other).value() == 0 ? 0 : ((NumberKey) other).value());
            case DateKey date -> Double.compare(date.time() == 0 ? 0 : date.time(), ((DateKey) other).time() == 0 ? 0 : ((DateKey) other).time());
            case StringKey string -> string.value().compareTo(((StringKey) other).value());
            case BinaryKey binary -> Arrays.compareUnsigned(binary.bytes, ((BinaryKey) other).bytes);
            case ArrayKey array -> {
                var others = ((ArrayKey) other).elements();
                var length = Math.min(array.elements().size(), others.size());
                for (var i = 0; i < length; i++) {
                    var element = array.elements().get(i).compareTo(others.get(i));
                    if (element != 0) {
                        yield element;
                    }
                }
                yield Integer.compare(array.elements().size(), others.size());
            }
        };
    }

    private static int rank(IdbKey key) {
        return switch (key) {
            case NumberKey _ -> 0;
            case DateKey _ -> 1;
            case StringKey _ -> 2;
            case BinaryKey _ -> 3;
            case ArrayKey _ -> 4;
        };
    }
}
