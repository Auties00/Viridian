package com.github.auties00.viridian.value;

/// A JavaScript primitive wrapper object, such as `new Number(1)`: an object, compared by identity, around a boolean,
/// number, BigInt or string.
public final class JsWrapper implements JsValue {
    private final JsValue primitive;

    JsWrapper(JsValue primitive) {
        this.primitive = primitive;
    }

    /// {@return the wrapped primitive: a [JsBoolean], [JsNumber], [JsBigInt] or [JsString]}
    public JsValue primitive() {
        return primitive;
    }

    @Override
    public String toString() {
        return "Object(" + primitive + ")";
    }
}
