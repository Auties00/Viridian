package com.github.auties00.viridian.value;

/// The JavaScript `null`, which [JsValue#NULL] holds.
public record JsNull() implements JsPrimitive {
    @Override
    public String toString() {
        return "null";
    }
}
