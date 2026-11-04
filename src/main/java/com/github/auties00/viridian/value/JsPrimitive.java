package com.github.auties00.viridian.value;

/// A JavaScript primitive: `undefined`, `null`, a boolean, number, BigInt or string, compared by value.
public sealed interface JsPrimitive extends JsValue permits JsUndefined, JsNull, JsBoolean, JsNumber, JsBigInt, JsString {
}
