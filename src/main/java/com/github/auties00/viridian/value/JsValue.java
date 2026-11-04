package com.github.auties00.viridian.value;

import java.math.BigInteger;

/// A JavaScript value, as a page stored it with the structured clone algorithm: in IndexedDB, or anywhere else
/// Chromium persists a serialized script value.
///
/// Values are immutable. A [JsPrimitive] is a record compared by value. Every other value is an object, which a
/// stored value refers to by identity, as JavaScript does: it may hold one object twice, or contain itself, and both
/// decoding and [StructuredClone#serialize(JsValue)] keep those references, cycles included. That holds for the
/// objects that are records too, such as a [JsDate] or a [JsDomPoint]: two equal records are two objects unless they
/// are one instance, so a [JsMap] or [JsSet] holds them as two keys, as JavaScript does. The classes, [JsObject],
/// [JsArray], [JsMap] and the others, compare by identity; [#deepEquals(JsValue, JsValue)] compares two values by
/// what they hold.
///
/// Values that hold other values are built with a [JsBuilder], which can hold another builder before either is built:
/// that is how a value comes to contain itself.
///
/// {@snippet lang = java :
/// JsObject user = JsObject.builder()
///         .put("name", "Alice")
///         .put("age", 30)
///         .put("nickname", JsValue.NULL)
///         .put("tags", JsArray.of("admin", "owner"))
///         .build();
/// switch (value) {
///     case JsPrimitive primitive -> System.out.println("a primitive: " + primitive);
///     case JsHostObject platform -> System.out.println("a platform object: " + platform);
///     default -> System.out.println("a JavaScript object: " + value);
/// }
/// }
public sealed interface JsValue permits JsPrimitive, JsObject, JsArray, JsDate, JsRegExp, JsMap, JsSet, JsArrayBuffer,
        JsArrayBufferView, JsWrapper, JsError, JsHostObject {
    /// `undefined`.
    JsUndefined UNDEFINED = new JsUndefined();

    /// `null`.
    JsNull NULL = new JsNull();

    /// {@return the string `value`}
    static JsString of(String value) {
        return new JsString(value);
    }

    /// {@return the number `value`}
    static JsNumber of(double value) {
        return new JsNumber(value);
    }

    /// {@return the number `value`, exactly}
    ///
    /// @throws ArithmeticException if `value` is more than 2^53 from zero, past which a JavaScript number does not hold
    ///                             every integer; [#of(double)] rounds it, and [#of(BigInteger)] keeps it as a BigInt
    static JsNumber of(long value) {
        if (value > JsNumber.MAX_SAFE_INTEGER || value < -JsNumber.MAX_SAFE_INTEGER) {
            throw new ArithmeticException(value + " is past 2^53, where a JavaScript number may not hold it exactly: use of(double)");
        }
        return new JsNumber(value);
    }

    /// {@return the boolean `value`}
    static JsBoolean of(boolean value) {
        return JsBoolean.of(value);
    }

    /// {@return the BigInt `value`}
    static JsBigInt of(BigInteger value) {
        return new JsBigInt(value);
    }

    /// {@return whether `first` and `second` hold the same: the same primitives, and objects of the same kind with the
    /// same contents, shared and cyclic in the same places}
    ///
    /// Primitives compare as their records do, so `NaN` equals itself and `-0` differs from `0`. Two objects are
    /// equal when every object one holds pairs with exactly one object of the other, of the same kind, holding equal
    /// contents in the same order: properties, elements and holes, entries, buffer bytes and the buffers views share,
    /// and the fields of platform objects. So `[a, a]` differs from `[a, b]` even when `a` and `b` hold the same, and
    /// two cycles are equal when they loop in the same places. Blobs and files compare by their type, size, name and
    /// modification time, not by their bytes, which reading would take I/O for. Arrays compare by their elements,
    /// whether or not [JsArray#isSparse()] says the same of both.
    static boolean deepEquals(JsValue first, JsValue second) {
        return JsEquality.deepEquals(first, second);
    }
}
