package com.github.auties00.viridian.value;

/// A platform object a page stored that JavaScript itself does not define: a Web Crypto key, a `Blob` or `File`, image
/// data, a geometry object, and the others Blink knows how to serialize.
public sealed interface JsHostObject extends JsValue permits JsCryptoKey, JsBlob, JsFile, JsFileList, JsImageData,
        JsImageBitmap, JsDomPoint, JsDomRect, JsDomQuad, JsDomMatrix, JsDomException, JsFileSystemHandle, JsRtcCertificate,
        JsDomFileSystem {
}
