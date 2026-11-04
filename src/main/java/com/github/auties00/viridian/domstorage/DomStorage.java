package com.github.auties00.viridian.domstorage;

import com.github.auties00.viridian.StorageOrigin;
import com.github.auties00.viridian.ViridianException;
import com.github.auties00.viridian.leveldb.LevelDb;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/// Reads Chromium's DOM Storage: `localStorage` and `sessionStorage`, each a LevelDB database of the profile.
///
/// Local Storage (`Local Storage/leveldb`) keys an item by `_`, the origin, a 0 byte and the item's key. Keys and
/// values both start with a format byte, 1 for Latin-1 characters and 0 for little-endian UTF-16 code units; the
/// other keys (`VERSION`, `META:<origin>`, `METAACCESS:<origin>`) are bookkeeping.
///
/// ```text
/// "_https://example.com" 00 01 "theme"    -> 01 "dark"
/// ```
///
/// Session Storage (`Session Storage`) maps each session and origin to a numbered map, whose items hold their keys as
/// UTF-8 and their values as little-endian UTF-16, without a format byte:
///
/// ```text
/// "namespace-<session id>-<origin>/"      -> "<map number>"
/// "map-<map number>-" <key, UTF-8>        -> <value, UTF-16LE>
/// ```
///
/// Only first-party storage is read: a storage key with a `^`, which partitioned and opaque storage keys have, is not
/// an origin's own.
public final class DomStorage {
    private static final int UTF16 = 0;
    private static final int LATIN1 = 1;
    private static final byte[] NAMESPACE_PREFIX = "namespace-".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] MAP_PREFIX = "map-".getBytes(StandardCharsets.US_ASCII);
    // The length of a session id, a GUID
    private static final int NAMESPACE_ID_LENGTH = 36;

    private DomStorage() {
    }

    /// {@return the `localStorage` items of `origin`, by key, from the Local Storage database in `directory`}
    ///
    /// @throws ViridianException if an item does not decode
    /// @throws IOException       if the database cannot be read
    public static Map<String, String> localStorage(Path directory, StorageOrigin origin) throws IOException {
        var prefix = ("_" + origin.serialize()).getBytes(StandardCharsets.ISO_8859_1);
        var entries = LevelDb.read(directory, key -> key.length > prefix.length && matchesItemOrigin(key, prefix));
        var items = new TreeMap<String, String>();
        for (var entry : entries) {
            var separator = indexOf(entry.key(), (byte) 0, 1);
            items.put(decodeLocal(Arrays.copyOfRange(entry.key(), separator + 1, entry.key().length)), decodeLocal(entry.value()));
        }
        return items;
    }

    /// {@return the origins with `localStorage` items in the Local Storage database in `directory`}
    ///
    /// @throws IOException if the database cannot be read
    public static List<StorageOrigin> localStorageOrigins(Path directory) throws IOException {
        var origins = new TreeSet<String>();
        for (var entry : LevelDb.read(directory, key -> key.length > 1 && key[0] == '_')) {
            var separator = indexOf(entry.key(), (byte) 0, 1);
            if (separator > 1) {
                origins.add(new String(entry.key(), 1, separator - 1, StandardCharsets.ISO_8859_1));
            }
        }
        return parseOrigins(origins);
    }

    /// {@return the `sessionStorage` areas of `origin`, one per session, from the Session Storage database in
    /// `directory`}
    ///
    /// @throws IOException if the database cannot be read
    public static List<SessionStorageItems> sessionStorage(Path directory, StorageOrigin origin) throws IOException {
        var entries = LevelDb.read(directory, key -> startsWith(key, NAMESPACE_PREFIX) || startsWith(key, MAP_PREFIX));
        var mapsBySession = new LinkedHashMap<String, String>();
        for (var entry : entries) {
            if (!startsWith(entry.key(), NAMESPACE_PREFIX) || entry.key().length <= NAMESPACE_PREFIX.length + NAMESPACE_ID_LENGTH + 1) {
                continue;
            }
            var namespace = new String(entry.key(), NAMESPACE_PREFIX.length, NAMESPACE_ID_LENGTH, StandardCharsets.US_ASCII);
            var storageKey = new String(entry.key(), NAMESPACE_PREFIX.length + NAMESPACE_ID_LENGTH + 1,
                    entry.key().length - NAMESPACE_PREFIX.length - NAMESPACE_ID_LENGTH - 1, StandardCharsets.ISO_8859_1);
            if (isOrigin(storageKey, origin)) {
                mapsBySession.put(namespace, new String(entry.value(), StandardCharsets.US_ASCII).strip());
            }
        }
        var itemsByMap = new HashMap<String, Map<String, String>>();
        for (var entry : entries) {
            if (!startsWith(entry.key(), MAP_PREFIX)) {
                continue;
            }
            var separator = indexOf(entry.key(), (byte) '-', MAP_PREFIX.length);
            if (separator < 0) {
                continue;
            }
            var map = new String(entry.key(), MAP_PREFIX.length, separator - MAP_PREFIX.length, StandardCharsets.US_ASCII);
            if (!mapsBySession.containsValue(map)) {
                continue;
            }
            var key = new String(entry.key(), separator + 1, entry.key().length - separator - 1, StandardCharsets.UTF_8);
            itemsByMap.computeIfAbsent(map, _ -> new TreeMap<>()).put(key, utf16(entry.value()));
        }
        var areas = new ArrayList<SessionStorageItems>();
        for (var session : mapsBySession.entrySet()) {
            areas.add(new SessionStorageItems(session.getKey(), itemsByMap.getOrDefault(session.getValue(), Map.of())));
        }
        return areas;
    }

    /// The items of one session, as [#sessionStorage(Path, StorageOrigin)] returns them.
    ///
    /// @param namespaceId the session's id
    /// @param items       the items, by key
    public record SessionStorageItems(String namespaceId, Map<String, String> items) {
    }

    /// {@return whether an item key starts with the origin prefix followed by the 0 separator}
    private static boolean matchesItemOrigin(byte[] key, byte[] prefix) {
        return Arrays.equals(key, 0, prefix.length, prefix, 0, prefix.length) && key[prefix.length] == 0;
    }

    /// {@return whether a storage key, an origin possibly with a trailing slash, is `origin`'s first-party key}
    private static boolean isOrigin(String storageKey, StorageOrigin origin) {
        if (storageKey.indexOf('^') >= 0) {
            return false;
        }
        var trimmed = storageKey.endsWith("/") ? storageKey.substring(0, storageKey.length() - 1) : storageKey;
        return trimmed.equals(origin.serialize());
    }

    private static List<StorageOrigin> parseOrigins(Iterable<String> serialized) {
        var origins = new ArrayList<StorageOrigin>();
        for (var origin : serialized) {
            if (origin.indexOf('^') >= 0) {
                continue;
            }
            try {
                origins.add(StorageOrigin.parse(origin));
            } catch (IllegalArgumentException _) {
                // A storage key this reader does not know how to name
            }
        }
        return origins;
    }

    /// {@return a Local Storage string: a format byte, then the characters}
    private static String decodeLocal(byte[] data) throws ViridianException {
        if (data.length == 0) {
            return "";
        }
        return switch (data[0]) {
            case LATIN1 -> new String(data, 1, data.length - 1, StandardCharsets.ISO_8859_1);
            case UTF16 -> {
                if ((data.length - 1) % 2 != 0) {
                    throw new ViridianException(ViridianException.Reason.CORRUPTED, "A Local Storage string has an odd number of UTF-16 bytes");
                }
                yield utf16(Arrays.copyOfRange(data, 1, data.length));
            }
            default -> throw new ViridianException(ViridianException.Reason.CORRUPTED, "A Local Storage string has format byte " + data[0]);
        };
    }

    /// {@return little-endian UTF-16 code units, kept as they are, lone surrogates included; a trailing odd byte is
    /// dropped}
    private static String utf16(byte[] data) {
        var characters = new char[data.length / 2];
        for (var i = 0; i < characters.length; i++) {
            characters[i] = (char) ((data[2 * i] & 0xFF) | (data[2 * i + 1] & 0xFF) << 8);
        }
        return new String(characters);
    }

    private static boolean startsWith(byte[] key, byte[] prefix) {
        return key.length >= prefix.length && Arrays.equals(key, 0, prefix.length, prefix, 0, prefix.length);
    }

    private static int indexOf(byte[] data, byte value, int from) {
        for (var i = from; i < data.length; i++) {
            if (data[i] == value) {
                return i;
            }
        }
        return -1;
    }
}
