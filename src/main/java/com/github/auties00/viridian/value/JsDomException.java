package com.github.auties00.viridian.value;

import java.util.Objects;

/// A `DOMException`, or a `QuotaExceededError`, which is one.
///
/// @param name    the exception's name, such as `NotFoundError`
/// @param message the message
public record JsDomException(String name, String message) implements JsHostObject {
    /// Creates an exception.
    public JsDomException {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(message, "message");
    }
}
