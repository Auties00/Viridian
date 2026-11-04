package com.github.auties00.viridian.domstorage;

import com.github.auties00.viridian.StorageKey;
import com.github.auties00.viridian.leveldb.LevelDb;
import com.github.auties00.viridian.leveldb.LevelDbBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/// Writes Chromium's Local Storage on its LevelDB backend, as [LevelDbDomStorage] reads it.
///
/// An origin's items are stored as Blink sends them to the browser, and its usage as Chromium records it when it
/// commits them: `META:<origin>` holds a `LocalStorageAreaWriteMetaData` message, the time of the commit and the
/// bytes the items take, their keys and values with their format bytes; `METAACCESS:<origin>` holds a
/// `LocalStorageAreaAccessMetaData` message, the time the origin last used them. Times are microseconds since 1601.
///
/// ```text
/// "VERSION"                             -> "1"
/// "META:https://example.com"            -> 08 <varint last modified> 10 <varint size in bytes>
/// "METAACCESS:https://example.com"      -> 08 <varint last accessed>
/// ```
public final class LevelDbDomStorageWriter {
    private static final byte[] VERSION_KEY = "VERSION".getBytes(StandardCharsets.US_ASCII);
    // kLocalStorageLevelDBVersion, the only version Chromium opens
    private static final byte[] VERSION = "1".getBytes(StandardCharsets.US_ASCII);
    private static final String WRITE_META_PREFIX = "META:";
    private static final String ACCESS_META_PREFIX = "METAACCESS:";
    // The fields of the usage messages: last_modified and last_accessed, then size_bytes, both varints
    private static final int TIME_FIELD = 1 << 3;
    private static final int SIZE_FIELD = 2 << 3;
    // base::Time's epoch, 1601-01-01, which its internal value counts microseconds from
    private static final Instant WINDOWS_EPOCH = Instant.parse("1601-01-01T00:00:00Z");
    // The files of a LevelDB database, which a copy of one does not take from it
    private static final Pattern LEVELDB_FILE = Pattern.compile("CURRENT|LOCK|LOG|LOG\\.old|MANIFEST-\\d+|\\d+\\.(log|ldb|sst|dbtmp)");

    private LevelDbDomStorageWriter() {
    }

    /// Writes the Local Storage database in `target` as a copy of the one in `existing`, or of a fresh one if there is
    /// none, in which the items of each storage key of `replacements` are exactly those it maps to, and every other
    /// entry is unchanged.
    ///
    /// A storage key's entries are named by its Local Storage serialization: its origin alone for a first-party key,
    /// its whole serialization for a partitioned one. Its usage records the time of the write; it has none when its
    /// items are empty, as Chromium deletes the usage of a key whose items it removes. Files of `existing` that are not
    /// the database's own are copied.
    ///
    /// @param existing     the Local Storage database to copy, which need not exist; it is only read
    /// @param target       the directory to write, which may exist if it is empty
    /// @param replacements the items of each storage key replaced, by key
    /// @throws DirectoryNotEmptyException if `target` exists and holds any file
    /// @throws IOException                if `existing` cannot be read, or `target` cannot be written
    public static void replaceLocalStorage(Path existing, Path target, Map<StorageKey, Map<String, String>> replacements) throws IOException {
        Objects.requireNonNull(existing, "existing");
        Objects.requireNonNull(target, "target");
        var replaced = new ArrayList<byte[][]>();
        for (var key : replacements.keySet()) {
            var serialized = key.serializeForLocalStorage();
            var serializedKey = serialized.getBytes(StandardCharsets.ISO_8859_1);
            var itemPrefix = new byte[serializedKey.length + 2];
            itemPrefix[0] = '_';
            System.arraycopy(serializedKey, 0, itemPrefix, 1, serializedKey.length);
            replaced.add(new byte[][]{itemPrefix, (WRITE_META_PREFIX + serialized).getBytes(StandardCharsets.ISO_8859_1),
                    (ACCESS_META_PREFIX + serialized).getBytes(StandardCharsets.ISO_8859_1)});
        }

        var entries = new ArrayList<LevelDb.Entry>();
        var hasVersion = false;
        if (holdsDatabase(existing)) {
            for (var entry : LevelDb.read(existing, _ -> true)) {
                var key = entry.key();
                var isReplaced = false;
                for (var keys : replaced) {
                    isReplaced |= entry.startsWith(keys[0]) || Arrays.equals(key, keys[1]) || Arrays.equals(key, keys[2]);
                }
                if (isReplaced) {
                    continue;
                }
                hasVersion |= Arrays.equals(key, VERSION_KEY);
                entries.add(entry);
            }
        }
        if (!hasVersion) {
            entries.add(new LevelDb.Entry(VERSION_KEY, VERSION));
        }
        var now = WINDOWS_EPOCH.until(Instant.now(), ChronoUnit.MICROS);
        var position = 0;
        for (var replacement : replacements.values()) {
            var keys = replaced.get(position++);
            var itemPrefix = keys[0];
            long size = 0;
            for (var item : replacement.entrySet()) {
                var key = DomStorageText.encodeLocal(Objects.requireNonNull(item.getKey(), "key"));
                var value = DomStorageText.encodeLocal(Objects.requireNonNull(item.getValue(), "value"));
                var itemKey = Arrays.copyOf(itemPrefix, itemPrefix.length + key.length);
                System.arraycopy(key, 0, itemKey, itemPrefix.length, key.length);
                entries.add(new LevelDb.Entry(itemKey, value));
                size += key.length + value.length;
            }
            if (!replacement.isEmpty()) {
                entries.add(new LevelDb.Entry(keys[1], usage(now, size)));
                entries.add(new LevelDb.Entry(keys[2], usage(now, -1)));
            }
        }
        LevelDbBuilder.write(target, LevelDbBuilder.BYTEWISE_COMPARATOR, entries);
        for (var file : otherFiles(existing)) {
            Files.copy(file, target.resolve(file.getFileName().toString()));
        }
    }

    /// {@return a usage message: the time, then the size unless it is negative}
    private static byte[] usage(long time, long size) {
        var output = new ByteArrayOutputStream();
        output.write(TIME_FIELD);
        writeVarint(output, time);
        if (size >= 0) {
            output.write(SIZE_FIELD);
            writeVarint(output, size);
        }
        return output.toByteArray();
    }

    /// {@return whether `directory` holds a LevelDB database: a descriptor, a log or a table}
    private static boolean holdsDatabase(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (var files = Files.list(directory)) {
            return files.map(file -> file.getFileName().toString())
                    .anyMatch(name -> name.equals("CURRENT") || name.endsWith(".log") || name.endsWith(".ldb") || name.endsWith(".sst"));
        }
    }

    /// {@return the regular files of `directory` that are not a LevelDB database's own}
    private static List<Path> otherFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> !LEVELDB_FILE.matcher(file.getFileName().toString()).matches())
                    .toList();
        }
    }

    private static void writeVarint(ByteArrayOutputStream output, long value) {
        while ((value & ~0x7FL) != 0) {
            output.write((int) (value & 0x7F) | 0x80);
            value >>>= 7;
        }
        output.write((int) value);
    }
}
