package com.github.auties00.viridian.indexeddb;

import com.github.auties00.viridian.IdbKey;
import com.github.auties00.viridian.IdbRecord;
import com.github.auties00.viridian.ViridianException;
import com.github.auties00.viridian.compress.Snappy;
import com.github.auties00.viridian.io.ByteCursor;
import com.github.auties00.viridian.value.ExternalObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/// An [IdbRecord] read from either backend: its key, its external objects, and the stored bytes of its value, from
/// which [#serializedValue()] undoes Blink's IndexedDB wrapping.
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
public final class StoredRecord implements IdbRecord {
    private static final int VERSION_TAG = 0xFF;
    // The SSV format version whose encoding Blink reuses to mark wrapped values
    private static final int WRAPPED = 0x11;
    private static final int IN_BLOB = 1;
    private static final int SNAPPY = 2;
    // The largest value a record may hold, a guard against a damaged length
    static final int MAX_VALUE_LENGTH = Integer.MAX_VALUE - 16;

    private final IdbKey key;
    private final List<ExternalObject> externalObjects;
    private final byte[] stored;
    private final int offset;

    /// Creates a record.
    ///
    /// @param stored the bytes the backend stores for the value, from `offset`
    public StoredRecord(IdbKey key, List<ExternalObject> externalObjects, byte[] stored, int offset) {
        this.key = Objects.requireNonNull(key, "key");
        this.externalObjects = List.copyOf(externalObjects);
        this.stored = stored;
        this.offset = offset;
    }

    @Override
    public IdbKey key() {
        return key;
    }

    @Override
    public List<ExternalObject> externalObjects() {
        return externalObjects;
    }

    @Override
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
                    var blobs = externalObjects.stream().filter(external -> !(external instanceof ExternalObject.FileSystemAccessHandle)).toList();
                    if (blobs.isEmpty() || index != blobs.size() - 1) {
                        throw new ViridianException(ViridianException.Reason.CORRUPTED, "A value refers to blob " + index
                                + " to hold it, but the record's last blob is " + (blobs.size() - 1));
                    }
                    if (size < 0 || size > MAX_VALUE_LENGTH) {
                        throw new ViridianException(ViridianException.Reason.CORRUPTED, "A value held in a blob is " + Long.toUnsignedString(size) + " bytes");
                    }
                    var source = switch (blobs.getLast()) {
                        case ExternalObject.Blob blob -> blob.source();
                        case ExternalObject.File file -> file.source();
                        case ExternalObject.FileSystemAccessHandle _ -> throw new IllegalStateException("Handles are not blobs");
                    };
                    byte[] contents;
                    try (InputStream stream = source.open()) {
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
