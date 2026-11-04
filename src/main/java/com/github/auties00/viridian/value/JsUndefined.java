package com.github.auties00.viridian.value;

/// The JavaScript `undefined`.
public record JsUndefined() implements JsValue {
    /// The value.
    public static final JsUndefined INSTANCE = new JsUndefined();

    @Override
    public String toString() {
        return "undefined";
    }
}
