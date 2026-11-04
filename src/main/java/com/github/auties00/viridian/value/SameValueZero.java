package com.github.auties00.viridian.value;

/// The keys under which [JsMap] and [JsSet] find their entries: JavaScript's SameValueZero, which compares primitives
/// by value, `NaN` equal to itself and `-0` to `0`, and objects by identity.
///
/// @spec https://tc39.es/ecma262/#sec-samevaluezero SameValueZero
final class SameValueZero {
    private static final JsNumber ZERO = new JsNumber(0);

    private SameValueZero() {
    }

    /// {@return `value` as a Map or Set stores it: `-0` becomes `0`}
    static JsValue normalize(JsValue value) {
        return value instanceof JsNumber number && number.value() == 0 ? ZERO : value;
    }

    /// {@return a key equal to the key of every value SameValueZero-equal to `value`, and to no other}
    static Object keyOf(JsValue value) {
        return switch (normalize(value)) {
            // Records compare their double components as Double.compare does, every NaN alike
            case JsUndefined undefined -> undefined;
            case JsNull none -> none;
            case JsBoolean bool -> bool;
            case JsNumber number -> number;
            case JsBigInt bigInt -> bigInt;
            case JsString string -> string;
            case JsValue object -> new Identity(object);
        };
    }

    /// An object compared by identity, as JavaScript compares objects, whatever its Java class compares.
    private record Identity(JsValue object) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Identity identity && identity.object == object;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(object);
        }
    }
}
