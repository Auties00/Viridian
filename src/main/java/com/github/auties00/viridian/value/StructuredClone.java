package com.github.auties00.viridian.value;

import com.github.auties00.viridian.ViridianException;

import java.util.List;
import java.util.Objects;

/// Decodes the values Chromium serializes with the structured clone algorithm: V8's wire format, inside the version
/// envelope Blink adds, with Blink's platform objects as host objects.
///
/// This is the format of IndexedDB values once their IndexedDB wrapping is undone, of `history.state`, and of the
/// other values Chromium persists for a page. Every V8 wire format version up to 16 and every Blink version up to 21
/// is read, as are values written before Blink had an envelope of its own.
///
/// {@snippet lang = java :
/// JsValue value = StructuredClone.deserialize(bytes);
/// if (value instanceof JsObject object) {
///     System.out.println(object.get("name"));
/// }
/// }
public final class StructuredClone {
    private StructuredClone() {
    }

    /// {@return the value `data` holds, with no external objects to refer to}
    ///
    /// A `Blob` or `File` the value refers to by index then fails to decode; one it describes inline decodes without
    /// its bytes.
    ///
    /// @throws ViridianException if the value does not decode, or holds an object this reader does not support
    public static JsValue deserialize(byte[] data) throws ViridianException {
        return deserialize(data, 0, data.length, List.of());
    }

    /// {@return the value `data[offset, offset + length)` holds}
    ///
    /// @param externalObjects the objects the value refers to by index: for an IndexedDB record, the blobs, files and
    ///                        handles stored with it. A blob or file index counts the blobs and files among them, in
    ///                        order; handles are referred to by name only
    /// @throws ViridianException if the value does not decode, refers to an external object that is not there, or
    ///                           holds an object this reader does not support
    /// @throws IndexOutOfBoundsException if the range is not within `data`
    public static JsValue deserialize(byte[] data, int offset, int length, List<? extends ExternalObject> externalObjects)
            throws ViridianException {
        Objects.checkFromIndexSize(offset, length, data.length);
        return V8Decoder.decode(data, offset, offset + length, List.copyOf(externalObjects));
    }
}
