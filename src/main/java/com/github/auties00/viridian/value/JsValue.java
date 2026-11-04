package com.github.auties00.viridian.value;

/// A JavaScript value, as a page stored it with the structured clone algorithm: in IndexedDB, or anywhere else
/// Chromium persists a serialized script value.
///
/// Primitives and immutable values are records compared by value. Objects, arrays, maps, sets, array buffers and
/// the other objects are classes compared by identity, as in JavaScript: a stored value may reference one object
/// twice, or contain itself, and the decoded tree keeps those references, cycles included.
///
/// A [JsObject], [JsArray], [JsMap] or [JsSet] a decoder returns is complete and never changes afterwards; until it
/// returns, the decoder fills them in, which is how a value can contain itself.
public sealed interface JsValue permits JsUndefined, JsNull, JsBoolean, JsNumber, JsBigInt, JsString, JsObject,
        JsArray, JsDate, JsRegExp, JsMap, JsSet, JsArrayBuffer, JsArrayBufferView, JsWrapper, JsError, JsHostObject {
}
