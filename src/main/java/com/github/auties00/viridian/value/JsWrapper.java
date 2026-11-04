package com.github.auties00.viridian.value;

import java.util.Objects;

/// A JavaScript primitive wrapper object, such as `new Number(1)`: an object, compared by identity, around a boolean,
/// number, BigInt or string.
public final class JsWrapper implements JsValue {
    private final JsPrimitive primitive;

    private JsWrapper(JsPrimitive primitive) {
        this.primitive = primitive;
    }

    /// {@return a `Boolean` object around `primitive`, as `new Boolean(primitive)` makes}
    public static JsWrapper of(JsBoolean primitive) {
        return new JsWrapper(Objects.requireNonNull(primitive, "primitive"));
    }

    /// {@return a `Number` object around `primitive`, as `new Number(primitive)` makes}
    public static JsWrapper of(JsNumber primitive) {
        return new JsWrapper(Objects.requireNonNull(primitive, "primitive"));
    }

    /// {@return a `BigInt` object around `primitive`, as `Object(primitive)` makes}
    public static JsWrapper of(JsBigInt primitive) {
        return new JsWrapper(Objects.requireNonNull(primitive, "primitive"));
    }

    /// {@return a `String` object around `primitive`, as `new String(primitive)` makes}
    public static JsWrapper of(JsString primitive) {
        return new JsWrapper(Objects.requireNonNull(primitive, "primitive"));
    }

    /// {@return the wrapped primitive: a [JsBoolean], [JsNumber], [JsBigInt] or [JsString]}
    public JsPrimitive primitive() {
        return primitive;
    }

    @Override
    public String toString() {
        return "Object(" + primitive + ")";
    }
}
