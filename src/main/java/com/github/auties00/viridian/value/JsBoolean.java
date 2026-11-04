package com.github.auties00.viridian.value;

/// A JavaScript boolean primitive.
///
/// @param value the boolean
public record JsBoolean(boolean value) implements JsPrimitive {
    /// `true`.
    public static final JsBoolean TRUE = new JsBoolean(true);

    /// `false`.
    public static final JsBoolean FALSE = new JsBoolean(false);

    /// {@return the value of `value`}
    public static JsBoolean of(boolean value) {
        return value ? TRUE : FALSE;
    }

    @Override
    public String toString() {
        return Boolean.toString(value);
    }
}
