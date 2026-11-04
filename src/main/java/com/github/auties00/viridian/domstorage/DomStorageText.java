package com.github.auties00.viridian.domstorage;

import com.github.auties00.viridian.StorageOrigin;
import com.github.auties00.viridian.ViridianException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/// The strings and storage keys both DOM Storage backends store the same way.
///
/// A Local Storage key or value starts with a format byte, 1 for Latin-1 characters and 0 for little-endian UTF-16
/// code units. A storage key is an origin, with a trailing slash on SQLite and in Session Storage; a partitioned or
/// opaque one has a `^`, and is not an origin's own.
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

    /// {@return the little-endian UTF-16 code units of `data` from `offset`, kept as they are, lone surrogates
    /// included; a trailing odd byte is dropped}
    static String utf16(byte[] data, int offset) {
        var characters = new char[(data.length - offset) / 2];
        for (var i = 0; i < characters.length; i++) {
            characters[i] = (char) ((data[offset + 2 * i] & 0xFF) | (data[offset + 2 * i + 1] & 0xFF) << 8);
        }
        return new String(characters);
    }

    /// {@return whether `storageKey`, an origin with or without a trailing slash, is `origin`'s first-party key}
    static boolean isOrigin(String storageKey, StorageOrigin origin) {
        if (storageKey.indexOf('^') >= 0) {
            return false;
        }
        var trimmed = storageKey.endsWith("/") ? storageKey.substring(0, storageKey.length() - 1) : storageKey;
        return trimmed.equals(origin.serialize());
    }

    /// {@return the origins of the first-party storage keys among `storageKeys`, leaving out the others}
    static List<StorageOrigin> origins(Iterable<String> storageKeys) {
        var origins = new ArrayList<StorageOrigin>();
        for (var storageKey : storageKeys) {
            if (storageKey.indexOf('^') >= 0) {
                continue;
            }
            try {
                origins.add(StorageOrigin.parse(storageKey));
            } catch (IllegalArgumentException _) {
                // A storage key this reader does not know how to name
            }
        }
        return origins;
    }
}
