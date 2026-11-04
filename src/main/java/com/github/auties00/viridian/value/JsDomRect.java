package com.github.auties00.viridian.value;

/// A `DOMRect` or `DOMRectReadOnly`.
///
/// @param x        the x coordinate of the origin
/// @param y        the y coordinate of the origin
/// @param width    the width
/// @param height   the height
/// @param readOnly whether it is a `DOMRectReadOnly`
public record JsDomRect(double x, double y, double width, double height, boolean readOnly) implements JsHostObject {
}
