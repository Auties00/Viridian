package com.github.auties00.viridian.value;

/// A `DOMPoint` or `DOMPointReadOnly`.
///
/// @param x        the x coordinate
/// @param y        the y coordinate
/// @param z        the z coordinate
/// @param w        the perspective
/// @param readOnly whether it is a `DOMPointReadOnly`
public record JsDomPoint(double x, double y, double z, double w, boolean readOnly) implements JsHostObject {
}
