package com.github.auties00.viridian.value;

import java.util.Objects;

/// A JavaScript regular expression.
///
/// @param source the pattern, as `RegExp.prototype.source` gives it
/// @param flags  the flags, as `RegExp.prototype.flags` gives them: some of `dgimsuvy`, in that order
public record JsRegExp(String source, String flags) implements JsValue {
    /// Creates a regular expression.
    public JsRegExp {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(flags, "flags");
    }

    @Override
    public String toString() {
        return "/" + source + "/" + flags;
    }
}
