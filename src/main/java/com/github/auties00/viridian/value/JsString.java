package com.github.auties00.viridian.value;

import java.util.Objects;

/// A JavaScript string primitive.
///
/// A JavaScript string is a sequence of UTF-16 code units that need not be valid UTF-16, as is a Java string: a lone
/// surrogate stays as it was stored.
///
/// @param value the string
public record JsString(String value) implements JsValue {
    /// Creates a string.
    public JsString {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return '"' + value + '"';
    }
}
