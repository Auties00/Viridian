package com.github.auties00.viridian;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/// An IndexedDB key: a number, a date, a string, a binary key or an array of keys.
///
/// Keys are ordered as IndexedDB orders them: numbers before dates before strings before binary keys before arrays;
/// numbers and dates numerically, strings by UTF-16 code unit, binary keys by unsigned byte, and arrays element by
/// element, then by length.
///
/// @spec https://w3c.github.io/IndexedDB/#key-construct Indexed Database API 3.0
public sealed interface IdbKey extends Comparable<IdbKey> {
    /// A number key.
    ///
    /// @param value the number, never `NaN`
    record NumberKey(double value) implements IdbKey {
        /// Creates a number key.
        ///
        /// @throws IllegalArgumentException if `value` is `NaN`, which is not a valid key
        public NumberKey {
            if (Double.isNaN(value)) {
                throw new IllegalArgumentException("NaN is not a valid key");
            }
        }
    }

    /// A date key.
    ///
    /// @param time the milliseconds since the Unix epoch
    record DateKey(double time) implements IdbKey {
        /// Creates a date key.
        ///
        /// @throws IllegalArgumentException if `time` is `NaN`, an invalid date, which is not a valid key
        public DateKey {
            if (Double.isNaN(time)) {
                throw new IllegalArgumentException("An invalid date is not a valid key");
            }
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
            return "BinaryKey" + Arrays.toString(bytes);
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
