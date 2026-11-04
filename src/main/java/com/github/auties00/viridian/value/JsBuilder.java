package com.github.auties00.viridian.value;

/// A builder of one value that holds other values: an object, array, `Map`, `Set` or error.
///
/// A builder builds exactly one instance. Until it is built, it may be passed wherever a value goes in another
/// builder, itself included; the value it builds then takes the place it was given, so values can share objects and
/// contain themselves although every value is immutable:
///
/// {@snippet lang = java :
/// JsObject.Builder node = JsObject.builder();
/// node.put("self", node);
/// JsObject cyclic = node.build();   // cyclic.get("self") is cyclic
/// }
///
/// Building a builder builds every builder it holds, directly or through others, so that no value can be reached
/// while it may still change. Once built, [#build()] returns the same instance again, and any change throws
/// [IllegalStateException]. A builder is not thread-safe.
///
/// @param <T> the type of the value built
public sealed interface JsBuilder<T extends JsValue> permits JsObject.Builder, JsArray.Builder, JsMap.Builder, JsSet.Builder,
        JsError.Builder {
    /// Builds the value, and every builder it holds.
    ///
    /// @return the value, the same instance on every call
    T build();
}
