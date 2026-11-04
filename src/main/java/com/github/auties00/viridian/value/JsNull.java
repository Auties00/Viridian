package com.github.auties00.viridian.value;

/// The JavaScript `null`.
public record JsNull() implements JsValue {
    /// The value.
    public static final JsNull INSTANCE = new JsNull();

    @Override
    public String toString() {
        return "null";
    }
}
