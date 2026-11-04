package com.github.auties00.viridian.value;

/// The JavaScript `undefined`, which [JsValue#UNDEFINED] holds.
public record JsUndefined() implements JsPrimitive {
    @Override
    public String toString() {
        return "undefined";
    }
}
