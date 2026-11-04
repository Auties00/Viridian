package com.github.auties00.viridian.value;

/// A platform object a page stored that JavaScript itself does not define: a Web Crypto key, a `Blob` or `File`, image
/// data, a geometry object, and the others Blink knows how to serialize.
///
/// A [JsFile] is a [JsBlob], so a pattern switch names it first.
public sealed interface JsHostObject extends JsValue permits JsCryptoKey, JsBlob, JsFileList, JsImageData,
        JsImageBitmap, JsDomPoint, JsDomRect, JsDomQuad, JsDomMatrix, JsDomException, JsFileSystemHandle, JsRtcCertificate,
        JsDomFileSystem {
}
