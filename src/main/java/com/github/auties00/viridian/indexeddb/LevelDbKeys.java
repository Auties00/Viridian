package com.github.auties00.viridian.indexeddb;

import java.io.ByteArrayOutputStream;

/// The key prefix every key of Chromium's LevelDB IndexedDB backend starts with: the database, object store and
/// index the key belongs to.
///
/// The first byte packs the byte lengths of the three ids, `(databaseBytes - 1) << 5 | (storeBytes - 1) << 2 |
/// (indexBytes - 1)`, and each id follows as a little-endian integer of that many bytes, the fewest that hold it. All
/// ids 0 is the global metadata; only the database id, that database's metadata. Under an object store, index 1 holds
/// the records, 2 their versions, 3 their blobs, and ids from 30 on the indexes.
///
/// @param databaseId    the database id
/// @param objectStoreId the object store id
/// @param indexId       the index id
/// @param length        the prefix's length in bytes
record LevelDbKeys(long databaseId, long objectStoreId, long indexId, int length) {
    /// The index id of an object store's records.
    static final int OBJECT_STORE_DATA = 1;
    /// The index id of an object store's blob entries.
    static final int BLOB_ENTRY = 3;

    /// {@return the prefix `key` starts with, or `null` if it is too short to hold one}
    static LevelDbKeys decode(byte[] key) {
        if (key.length == 0) {
            return null;
        }
        var first = key[0] & 0xFF;
        var databaseBytes = (first >>> 5) + 1;
        var storeBytes = ((first >>> 2) & 7) + 1;
        var indexBytes = (first & 3) + 1;
        var length = 1 + databaseBytes + storeBytes + indexBytes;
        if (key.length < length) {
            return null;
        }
        return new LevelDbKeys(littleEndian(key, 1, databaseBytes), littleEndian(key, 1 + databaseBytes, storeBytes),
                littleEndian(key, 1 + databaseBytes + storeBytes, indexBytes), length);
    }

    /// {@return the prefix of the ids, encoded}
    static byte[] encode(long databaseId, long objectStoreId, long indexId) {
        var databaseBytes = minimalBytes(databaseId);
        var storeBytes = minimalBytes(objectStoreId);
        var indexBytes = minimalBytes(indexId);
        var output = new ByteArrayOutputStream();
        output.write((databaseBytes - 1) << 5 | (storeBytes - 1) << 2 | (indexBytes - 1));
        writeLittleEndian(output, databaseId, databaseBytes);
        writeLittleEndian(output, objectStoreId, storeBytes);
        writeLittleEndian(output, indexId, indexBytes);
        return output.toByteArray();
    }

    /// {@return whether the prefix is metadata: global, or of one database}
    boolean isMetadata() {
        return objectStoreId == 0 && indexId == 0;
    }

    /// {@return the little-endian unsigned integer of `data[offset, offset + length)`, `length` at most 8}
    static long littleEndian(byte[] data, int offset, int length) {
        long value = 0;
        for (var i = length - 1; i >= 0; i--) {
            value = value << 8 | (data[offset + i] & 0xFF);
        }
        return value;
    }

    private static int minimalBytes(long value) {
        var bytes = 1;
        while (bytes < 8 && (value >>> (8 * bytes)) != 0) {
            bytes++;
        }
        return bytes;
    }

    private static void writeLittleEndian(ByteArrayOutputStream output, long value, int bytes) {
        for (var i = 0; i < bytes; i++) {
            output.write((int) (value >>> (8 * i)));
        }
    }
}
