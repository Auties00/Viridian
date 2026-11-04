package com.github.auties00.viridian.value;

import com.github.auties00.viridian.ViridianException;
import com.github.auties00.viridian.io.ValueAccess;

import java.util.List;
import java.util.Objects;

/// Reads and writes the values Chromium serializes with the structured clone algorithm: V8's wire format, inside the
/// version envelope Blink adds, with Blink's platform objects as host objects.
///
/// This is the format of IndexedDB values once their IndexedDB wrapping is undone, of `history.state`, and of the
/// other values Chromium persists for a page. Every V8 wire format version up to 16 and every Blink version up to 21
/// is read, as are values written before Blink had an envelope of its own.
///
/// {@snippet lang = java :
/// JsValue value = StructuredClone.deserialize(bytes);
/// if (value instanceof JsObject object) {
///     System.out.println(object.getString("name").orElse("?"));
/// }
///
/// SerializedValue serialized = StructuredClone.serialize(JsObject.builder()
///         .put("name", "Alice")
///         .put("photo", JsBlob.of(photo, "image/png"))
///         .build());
/// JsValue back = StructuredClone.deserialize(serialized);
/// }
///
/// A value refers to the blobs and files it holds by index, into the list a store keeps beside it: the blobs of a
/// [SerializedValue], or those an IndexedDB record lists. A File System Access handle refers to its grant's token the
/// same way; a value decoded here, without the tokens, reads its handles without them.
///
/// # Writing
///
/// [#serialize(JsValue)] writes what Chrome writes for IndexedDB, but for its version. Blink's envelope is version
/// 21, with its trailer, the list of the platform interfaces the value holds, which every Chrome since 109 reads.
/// V8's header is version 15 where Chrome since 149 writes 16: version 16 only lets lengths and offsets of array
/// buffers and views pass 32 bits, and encodes every shorter one exactly as version 15 does, but a Chrome before 149
/// refuses any value of version 16. So a value is written as version 15, which every Chrome since 100 reads, and as
/// version 16 only when it holds a resizable buffer whose maximum length needs it.
///
/// What a value holds decides everything else, except where V8 writes from how it keeps a value in memory, which a
/// [JsValue] does not record, and where Chrome writes what it cannot read back. In the first case the serializer
/// writes what V8 writes for the value as a page most likely built it, which reads back the same either way:
///
/// - a number is an integer when it is one of 31 bits, a double otherwise, as V8 keeps numbers on 64-bit Chrome; but
///   every element of an array of numbers that are not all such integers is a double, as V8 keeps such an array;
/// - a string is one byte a character when every character fits, two bytes otherwise;
/// - an array is written in sequence when it has no holes and was not read by index, and by index otherwise, as V8
///   writes an array that has ever had holes;
/// - a `DOMException` is written as such even when its name is `QuotaExceededError`, which Chrome since 145 writes
///   as an exception of its own, with its quota, that an older Chrome cannot read.
///
/// In the second, it writes what Chrome reads: a lone surrogate in the name or message of a `DOMException`, which
/// Blink keeps as UTF-8, is written as U+FFFD, since a value Blink wrote with one does not read back at all, and a
/// value nested more than 1000 levels deep is refused, since Chrome 157 fails to read objects nested 1400 levels
/// deep, the limit of its reader being its thread's stack.
///
/// @spec https://github.com/v8/v8/blob/main/src/objects/value-serializer.cc V8 value serializer
/// @spec https://chromium.googlesource.com/chromium/src/+/main/third_party/blink/renderer/bindings/core/v8/serialization/ Blink script value serialization
public final class StructuredClone {
    static {
        ValueAccess.register(new ValueAccessImpl());
    }

    private StructuredClone() {
    }

    /// {@return the value `data` holds, with no blobs or files stored beside it}
    ///
    /// A `Blob` or `File` the value refers to by index then fails to decode; one it describes inline decodes without
    /// its bytes.
    ///
    /// @throws ViridianException if the value does not decode, or holds an object this reader does not support
    public static JsValue deserialize(byte[] data) throws ViridianException {
        return deserialize(data, List.of());
    }

    /// {@return the value `data` holds, its blob and file indexes referring to `blobs`}
    ///
    /// A blob index names the blob at that position; one that names a [JsFile] reads as a blob of the file's bytes.
    /// A file index names a file at that position, and reads a plain blob there as a file without a name.
    ///
    /// @throws ViridianException if the value does not decode, refers to a blob that is not there, or holds an object
    ///                           this reader does not support
    public static JsValue deserialize(byte[] data, List<? extends JsBlob> blobs) throws ViridianException {
        return V8Decoder.decode(data, 0, data.length, List.copyOf(blobs), null);
    }

    /// {@return the value `value` holds, as [#deserialize(byte\[\], List)] reads its bytes and blobs}
    ///
    /// @throws ViridianException if the value does not decode, or holds an object this reader does not support
    public static JsValue deserialize(SerializedValue value) throws ViridianException {
        var bytes = value.rawBytes();
        return V8Decoder.decode(bytes, 0, bytes.length, value.blobs(), null);
    }

    /// {@return `value` serialized as Chrome serializes it for IndexedDB, with the blobs and files it refers to}
    ///
    /// An object the value holds more than once, itself included, is written once and referred back to after, so it
    /// reads back as one object. Blobs, files and the files of file lists are referred to by index, as IndexedDB
    /// refers to the blobs it stores beside a record: [SerializedValue#blobs()] lists them in the order the value
    /// refers to them, one for each blob or file it holds and one for each file of each file list. A store that writes
    /// the value stores them beside it in that order, and fails on one that has no bytes.
    ///
    /// @throws IllegalArgumentException if the value holds a [JsFileSystemHandle], whose grant only the browser that
    ///                                  issued it holds, or is nested more than 1000 levels deep
    public static SerializedValue serialize(JsValue value) {
        return V8Encoder.encode(Objects.requireNonNull(value, "value"));
    }
}
