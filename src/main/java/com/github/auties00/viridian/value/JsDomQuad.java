package com.github.auties00.viridian.value;

import java.util.Objects;

/// A `DOMQuad`: four points.
///
/// @param p1 the first point
/// @param p2 the second point
/// @param p3 the third point
/// @param p4 the fourth point
public record JsDomQuad(JsDomPoint p1, JsDomPoint p2, JsDomPoint p3, JsDomPoint p4) implements JsHostObject {
    /// Creates a quad.
    public JsDomQuad {
        Objects.requireNonNull(p1, "p1");
        Objects.requireNonNull(p2, "p2");
        Objects.requireNonNull(p3, "p3");
        Objects.requireNonNull(p4, "p4");
    }
}
