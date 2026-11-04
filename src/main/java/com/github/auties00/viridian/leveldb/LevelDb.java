package com.github.auties00.viridian.leveldb;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/// Reads the live entries of a LevelDB database directory, as Chromium writes them for a profile's IndexedDB, Local
/// Storage and Session Storage.
///
/// The reader never writes to the directory and takes no lock. It reads `CURRENT` to find the descriptor
/// (`MANIFEST-*`), replays the descriptor's version edits to learn which table files are live and which logs are
/// newer than the last flush, then merges the entries of those tables and logs: for each key the entry with the
/// highest sequence number wins, and a key whose winning entry is a deletion is not live. A directory without
/// `CURRENT` is read from every table and log it holds.
///
/// A log whose tail was cut short by a crash is read up to its last complete record, and a record whose checksum
/// fails is dropped with the rest of its block, as LevelDB itself recovers a log. A table block whose checksum fails
/// is damage, and fails the read.
///
/// A database another process is writing may compact while it is read, deleting a table the descriptor listed; the
/// read then starts over from the new descriptor, a few times at most.
///
/// @spec https://github.com/google/leveldb/blob/main/doc/table_format.md LevelDB table format
/// @spec https://github.com/google/leveldb/blob/main/doc/log_format.md LevelDB log format
public final class LevelDb {
    // How many times a read starts over when a compaction deletes a table under it
    private static final int ATTEMPTS = 4;
    private static final Pattern FILE_NAME = Pattern.compile("(\\d+)\\.(log|ldb|sst)");

    private LevelDb() {
    }

    /// {@return the live entries of the database in `directory` whose user keys `keys` accepts, ordered by key bytes}
    ///
    /// Only the entries `keys` accepts are kept in memory while the files are read.
    ///
    /// @throws NoSuchFileException if `directory` holds no database
    /// @throws IOException if a file cannot be read, or does not decode
    public static List<Entry> read(Path directory, Predicate<byte[]> keys) throws IOException {
        NoSuchFileException vanished = null;
        for (var attempt = 0; attempt < ATTEMPTS; attempt++) {
            try {
                return readOnce(directory, keys);
            } catch (NoSuchFileException exception) {
                if (!Files.isDirectory(directory)) {
                    throw exception;
                }
                // A compaction deleted a file the descriptor listed: read the new descriptor
                vanished = exception;
            }
        }
        throw vanished;
    }

    private static List<Entry> readOnce(Path directory, Predicate<byte[]> keys) throws IOException {
        var tables = new ArrayList<Path>();
        var logs = new ArrayList<Path>();
        selectFiles(directory, tables, logs);
        var latest = new HashMap<Key, Versioned>();
        EntrySink sink = (key, sequence, value) -> {
            if (keys.test(key)) {
                latest.merge(new Key(key), new Versioned(sequence, value), (old, candidate) -> candidate.sequence() >= old.sequence() ? candidate : old);
            }
        };
        for (var table : tables) {
            LevelDbTable.read(table, sink);
        }
        for (var log : logs) {
            LevelDbLog.readBatches(log, sink);
        }
        var sorted = new TreeMap<Key, byte[]>();
        for (var entry : latest.entrySet()) {
            if (entry.getValue().value() != null) {
                sorted.put(entry.getKey(), entry.getValue().value());
            }
        }
        var result = new ArrayList<Entry>(sorted.size());
        for (var entry : sorted.entrySet()) {
            result.add(new Entry(entry.getKey().bytes(), entry.getValue()));
        }
        return result;
    }

    /// Selects the table and log files that hold the live state, in the order their entries are to be applied.
    private static void selectFiles(Path directory, List<Path> tables, List<Path> logs) throws IOException {
        var numbered = new HashMap<Long, Path>();
        var allLogs = new TreeMap<Long, Path>();
        try (var files = Files.list(directory)) {
            for (var file : (Iterable<Path>) files::iterator) {
                var matcher = FILE_NAME.matcher(file.getFileName().toString());
                if (!matcher.matches()) {
                    continue;
                }
                var number = Long.parseLong(matcher.group(1));
                if (matcher.group(2).equals("log")) {
                    allLogs.put(number, file);
                } else {
                    numbered.put(number, file);
                }
            }
        }
        var current = directory.resolve("CURRENT");
        if (!Files.isRegularFile(current)) {
            if (numbered.isEmpty() && allLogs.isEmpty()) {
                throw new NoSuchFileException(directory.toString(), null, "No LevelDB database is in the directory");
            }
            tables.addAll(numbered.values());
            logs.addAll(allLogs.values());
            return;
        }
        var manifestName = Files.readString(current, StandardCharsets.US_ASCII).strip();
        if (manifestName.isEmpty() || manifestName.contains("/") || manifestName.contains("\\")) {
            throw new IOException("CURRENT names an invalid descriptor: " + manifestName);
        }
        var state = LevelDbManifest.read(directory.resolve(manifestName));
        for (var number : state.liveTables()) {
            var table = numbered.get(number);
            if (table == null) {
                throw new NoSuchFileException(directory.resolve(number + ".ldb").toString(), null, "The descriptor lists a table that is not in the directory");
            }
            tables.add(table);
        }
        for (var entry : allLogs.entrySet()) {
            if (entry.getKey() >= state.logNumber() || entry.getKey() == state.previousLogNumber()) {
                logs.add(entry.getValue());
            }
        }
    }

    /// A live key and its value.
    ///
    /// @param key   the user key
    /// @param value the value
    public record Entry(byte[] key, byte[] value) {
        /// {@return whether the key starts with `prefix`}
        public boolean startsWith(byte[] prefix) {
            return key.length >= prefix.length && Arrays.equals(key, 0, prefix.length, prefix, 0, prefix.length);
        }
    }

    /// Receives every entry the files hold, live or not.
    @FunctionalInterface
    interface EntrySink {
        /// Receives an entry.
        ///
        /// @param value the value, or `null` for a deletion
        void accept(byte[] key, long sequence, byte[] value) throws IOException;
    }

    /// A user key compared by content and ordered by unsigned bytes, LevelDB's default order.
    private record Key(byte[] bytes) implements Comparable<Key> {
        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && Arrays.equals(bytes, key.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }

        @Override
        public int compareTo(Key other) {
            return Arrays.compareUnsigned(bytes, other.bytes);
        }
    }

    /// A value with the sequence number that wrote it.
    ///
    /// @param value the value, or `null` for a deletion
    private record Versioned(long sequence, byte[] value) {
    }

    /// The live table and log numbers of a descriptor.
    ///
    /// @param liveTables         the numbers of the live tables
    /// @param logNumber          the number of the oldest log still holding writes no table has
    /// @param previousLogNumber  the number of the log of a memtable being flushed, or -1
    record ManifestState(List<Long> liveTables, long logNumber, long previousLogNumber) {
    }

    /// {@return the masked CRC32C LevelDB stores of `data[offset, offset + length)` preceded by `type`}
    static int maskedCrc(byte type, byte[] data, int offset, int length) {
        var crc = new java.util.zip.CRC32C();
        crc.update(type);
        crc.update(data, offset, length);
        var value = (int) crc.getValue();
        // LevelDB masks stored CRCs, so that a CRC of data that embeds CRCs is not itself a likely CRC
        return ((value >>> 15) | (value << 17)) + 0xa282ead8;
    }

    /// {@return the masked CRC32C of `data[offset, offset + length)` followed by `type`, as table blocks store it}
    static int maskedBlockCrc(byte[] data, int offset, int length, byte type) {
        var crc = new java.util.zip.CRC32C();
        crc.update(data, offset, length);
        crc.update(type);
        var value = (int) crc.getValue();
        return ((value >>> 15) | (value << 17)) + 0xa282ead8;
    }
}
