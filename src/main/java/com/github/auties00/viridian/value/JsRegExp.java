package com.github.auties00.viridian.value;

import java.util.Objects;

/// A JavaScript regular expression.
///
/// The pattern is not compiled here: one JavaScript would reject, such as `(`, is stored all the same, and a browser
/// then fails to read the value back.
///
/// @param source the pattern, as `RegExp.prototype.source` gives it
/// @param flags  the flags, as `RegExp.prototype.flags` gives them: some of `dgilmsuvy`, in that order
public record JsRegExp(String source, String flags) implements JsValue {
    /// Creates a regular expression, putting its flags in their canonical order.
    ///
    /// The `l` flag, for V8's linear-time engine, is read by Chrome only when that engine is enabled.
    ///
    /// @throws IllegalArgumentException if a flag is unknown or repeated, or both `u` and `v` are set
    public JsRegExp {
        Objects.requireNonNull(source, "source");
        flags = JsNumbers.regExpFlags(JsNumbers.regExpBits(Objects.requireNonNull(flags, "flags")));
    }

    @Override
    public String toString() {
        return "/" + source + "/" + flags;
    }
}
