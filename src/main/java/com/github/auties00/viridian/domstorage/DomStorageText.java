package com.github.auties00.viridian.domstorage;

import com.github.auties00.viridian.StorageKey;
import com.github.auties00.viridian.ViridianException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.TreeMap;

/// The strings and storage keys both DOM Storage backends store the same way.
///
/// A Local Storage key or value starts with a format byte, 1 for Latin-1 characters and 0 for little-endian UTF-16
/// code units. A storage key is its serialization: Local Storage on LevelDB writes a first-party key as its origin
/// alone, without the trailing slash the other stores write, and a partitioned key whole, with its `^`.
final class DomStorageText {
    private static final int UTF16 = 0;
    private static final int LATIN1 = 1;

    private DomStorageText() {
    }

    /// {@return a Local Storage string: a format byte, then the characters}
    ///
    /// @throws ViridianException if the format byte is unknown, or UTF-16 characters have an odd number of bytes
    static String decodeLocal(byte[] data) throws ViridianException {
        if (data.length == 0) {
            return "";
        }
        return switch (data[0]) {
            case LATIN1 -> new String(data, 1, data.length - 1, StandardCharsets.ISO_8859_1);
            case UTF16 -> {
                if ((data.length - 1) % 2 != 0) {
                    throw new ViridianException(ViridianException.Reason.CORRUPTED, "A Local Storage string has an odd number of UTF-16 bytes");
                }
                yield utf16(data, 1);
            }
            default -> throw new ViridianException(ViridianException.Reason.CORRUPTED, "A Local Storage string has format byte " + data[0]);
        };
    }

    /// {@return `value` as a Local Storage string: Latin-1 when every character fits, the empty string included, as
    /// Blink chooses, and little-endian UTF-16 otherwise}
    static byte[] encodeLocal(String value) {
        var latin1 = true;
        for (var i = 0; i < value.length() && latin1; i++) {
            latin1 = value.charAt(i) <= 0xFF;
        }
        if (latin1) {
            var result = new byte[value.length() + 1];
            result[0] = LATIN1;
            for (var i = 0; i < value.length(); i++) {
                result[i + 1] = (byte) value.charAt(i);
            }
            return result;
        }
        var result = new byte[2 * value.length() + 1];
        result[0] = UTF16;
        for (var i = 0; i < value.length(); i++) {
            result[2 * i + 1] = (byte) value.charAt(i);
            result[2 * i + 2] = (byte) (value.charAt(i) >>> 8);
        }
        return result;
    }

    /// {@return the little-endian UTF-16 code units of `data` from `offset`, kept as they are, lone surrogates
    /// included; a trailing odd byte is dropped}
    static String utf16(byte[] data, int offset) {
        var characters = new char[(data.length - offset) / 2];
        for (var i = 0; i < characters.length; i++) {
            characters[i] = (char) ((data[offset + 2 * i] & 0xFF) | (data[offset + 2 * i + 1] & 0xFF) << 8);
        }
        return new String(characters);
    }

    /// {@return the storage key a database records as `stored`, with or without the trailing slash of a first-party
    /// key, or `null` if it is not one this reader knows}
    static StorageKey storageKey(String stored) {
        try {
            return StorageKey.parse(stored);
        } catch (IllegalArgumentException _) {
            return null;
        }
    }

    /// {@return the storage keys among `stored`, each once, by their serialization, leaving out those this reader does
    /// not know}
    static List<StorageKey> storageKeys(Iterable<String> stored) {
        var keys = new TreeMap<String, StorageKey>();
        for (var serialized : stored) {
            var key = storageKey(serialized);
            if (key != null) {
                keys.put(key.serialize(), key);
            }
        }
        return List.copyOf(keys.values());
    }
}
