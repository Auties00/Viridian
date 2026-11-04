package com.github.auties00.viridian.indexeddb;

import com.github.auties00.viridian.IdbKey;
import com.github.auties00.viridian.IdbKeyPath;
import com.github.auties00.viridian.IdbObjectStore;
import com.github.auties00.viridian.ViridianException;
import com.github.auties00.viridian.compress.Snappy;
import com.github.auties00.viridian.io.ByteCursor;
import com.github.auties00.viridian.io.ValueAccess;
import com.github.auties00.viridian.value.JsBlob;
import com.github.auties00.viridian.value.JsValue;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/// A record read from either backend: its key, the blobs and File System Access tokens stored beside it, and the
/// stored bytes of its value, from which [#serializedValue()] undoes Blink's IndexedDB wrapping.
///
/// Blink wraps a value in one of two ways, after a version tag and the pseudo-version 17, which no real serialized
/// value has:
///
/// ```text
/// FF 11 02 <raw Snappy stream>             the value, compressed
/// FF 11 01 <varint size> <varint index>    the value, too large to keep inline, in the record's last blob
/// ```
///
/// The blob of a value too large to keep inline may itself hold a compressed value. The SQLite backend never moves
/// a value into a blob, but may compress the stored bytes once more, which the backend has undone before the bytes
/// get here.
public final class StoredRecord {
    /// The version tag a wrapped value starts with, as every serialized value does.
    static final int VERSION_TAG = 0xFF;
    /// The serialized value format version whose encoding Blink reuses to mark wrapped values.
    static final int WRAPPED = 0x11;
    /// The wrapping of a value held in the record's last blob.
    static final int IN_BLOB = 1;
    /// The wrapping of a compressed value.
    static final int SNAPPY = 2;
    // The largest value a record may hold, a guard against a damaged length
    static final int MAX_VALUE_LENGTH = Integer.MAX_VALUE - 16;

    private final IdbKey key;
    private final List<JsBlob> blobs;
    private final List<byte[]> tokens;
    private final byte[] stored;
    private final int offset;

    /// Creates a record.
    ///
    /// @param blobs  the blobs and files stored beside the value, in index order, the one holding a value too large to
    ///               keep inline last
    /// @param tokens the File System Access tokens stored beside it, in index order
    /// @param stored the bytes the backend stores for the value, from `offset`
    public StoredRecord(IdbKey key, List<JsBlob> blobs, List<byte[]> tokens, byte[] stored, int offset) {
        this.key = Objects.requireNonNull(key, "key");
        this.blobs = List.copyOf(blobs);
        this.tokens = List.copyOf(tokens);
        this.stored = stored;
        this.offset = offset;
    }

    /// {@return the record's key}
    public IdbKey key() {
        return key;
    }

    /// {@return the blobs and files the value refers to, in index order: those stored beside the record, without the
    /// one that holds a value too large to keep inline}
    public List<JsBlob> valueBlobs() {
        var wrapped = stored.length - offset >= 3 && (stored[offset] & 0xFF) == VERSION_TAG && (stored[offset + 1] & 0xFF) == WRAPPED
                && (stored[offset + 2] & 0xFF) == IN_BLOB;
        return wrapped && !blobs.isEmpty() ? blobs.subList(0, blobs.size() - 1) : blobs;
    }

    /// {@return the value as stored, decoded}
    ///
    /// @throws ViridianException if the value is damaged, its blob is missing, or it holds an object this reader does
    ///                           not support
    /// @throws IOException       if the blob that holds the value cannot be read
    public JsValue storedValue() throws IOException {
        var serialized = serializedValue();
        return ValueAccess.get().decode(serialized, 0, serialized.length, valueBlobs(), tokens);
    }

    /// {@return the value as a page reads it from `store`: as stored, with the record's key injected at the key path
    /// of a store with a key generator, as Blink injects it}
    ///
    /// @throws ViridianException if the value is damaged, its blob is missing, or it holds an object this reader does
    ///                           not support
    /// @throws IOException       if the blob that holds the value cannot be read
    public JsValue value(IdbObjectStore store) throws IOException {
        var value = storedValue();
        if (store.autoIncrement() && store.keyPath().orElse(null) instanceof IdbKeyPath.Single single && !single.path().isEmpty()) {
            ValueAccess.get().inject(value, single.identifiers(), IdbKeyPaths.toValue(key));
        }
        return value;
    }

    /// {@return the serialized value, with the wrapping IndexedDB adds undone}
    ///
    /// @throws ViridianException if the value is damaged, or its blob is missing
    /// @throws IOException       if the blob that holds the value cannot be read
    public byte[] serializedValue() throws IOException {
        return unwrap(stored, offset, true);
    }

    private byte[] unwrap(byte[] data, int start, boolean mayBeInBlob) throws IOException {
        if (data.length - start >= 3 && (data[start] & 0xFF) == VERSION_TAG && (data[start + 1] & 0xFF) == WRAPPED) {
            switch (data[start + 2] & 0xFF) {
                case SNAPPY -> {
                    return Snappy.decompress(data, start + 3, data.length - start - 3, MAX_VALUE_LENGTH);
                }
                case IN_BLOB -> {
                    if (!mayBeInBlob) {
                        throw new ViridianException(ViridianException.Reason.CORRUPTED, "The blob that holds a value is itself a reference to a blob");
                    }
                    var input = new ByteCursor(data, start + 3, data.length);
                    var size = input.varint64();
                    var index = input.varint64();
                    if (blobs.isEmpty() || index != blobs.size() - 1) {
                        throw new ViridianException(ViridianException.Reason.CORRUPTED, "A value refers to blob " + index
                                + " to hold it, but the record's last blob is " + (blobs.size() - 1));
                    }
                    if (size < 0 || size > MAX_VALUE_LENGTH) {
                        throw new ViridianException(ViridianException.Reason.CORRUPTED, "A value held in a blob is " + Long.toUnsignedString(size) + " bytes");
                    }
                    byte[] contents;
                    try (InputStream stream = blobs.getLast().open()) {
                        contents = stream.readNBytes((int) size + 1);
                    }
                    if (contents.length != size) {
                        throw new ViridianException(ViridianException.Reason.CORRUPTED, "The blob that holds a value has "
                                + contents.length + " bytes instead of " + size);
                    }
                    return unwrap(contents, 0, false);
                }
                default -> throw new ViridianException(ViridianException.Reason.UNSUPPORTED,
                        "A value has wrapping " + (data[start + 2] & 0xFF) + ", which this reader does not know");
            }
        }
        return Arrays.copyOfRange(data, start, data.length);
    }

    @Override
    public String toString() {
        return "IdbRecord[" + key + "]";
    }
}
