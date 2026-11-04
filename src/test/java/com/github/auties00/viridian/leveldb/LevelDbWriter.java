package com.github.auties00.viridian.leveldb;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/// Writes LevelDB's files the way LevelDB does, checksums included, so tests can build databases without it: logs
/// of write batches, single-data-block tables, and descriptors.
public final class LevelDbWriter {
    private final List<byte[]> batches = new ArrayList<>();
    private long sequence = 1;

    /// Adds a put to the log.
    public LevelDbWriter put(byte[] key, byte[] value) {
        batches.add(batch(sequence++, key, value));
        return this;
    }

    /// Adds a deletion to the log.
    public LevelDbWriter delete(byte[] key) {
        batches.add(batch(sequence++, key, null));
        return this;
    }

    /// {@return the sequence number the next write gets}
    public long nextSequence() {
        return sequence;
    }

    /// Writes the log as `<number>.log` in `directory`, with no descriptor.
    public void writeLog(Path directory, long number) throws IOException {
        Files.createDirectories(directory);
        Files.write(directory.resolve(String.format("%06d.log", number)), logRecords(batches));
    }

    /// {@return a write batch of one put, or one deletion when `value` is `null`}
    public static byte[] batch(long sequence, byte[] key, byte[] value) {
        var out = new ByteArrayOutputStream();
        writeFixed64(out, sequence);
        writeFixed32(out, 1);
        out.write(value == null ? 0 : 1);
        writeVarint(out, key.length);
        out.writeBytes(key);
        if (value != null) {
            writeVarint(out, value.length);
            out.writeBytes(value);
        }
        return out.toByteArray();
    }

    /// {@return `records` framed in 32 KiB log blocks, those that cross a block boundary fragmented}
    public static byte[] logRecords(List<byte[]> records) {
        var out = new ByteArrayOutputStream();
        var log = new LevelDbLogWriter(out);
        try {
            for (var record : records) {
                log.addRecord(record, 0, record.length);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return out.toByteArray();
    }

    /// {@return a table of one data block holding `internalEntries`, given in key order}
    ///
    /// @param compress turns the data block into what is stored for it, or `null` to store it raw
    /// @param type     the compression type byte of the data block
    public static byte[] table(Map<byte[], byte[]> internalEntries, Function<byte[], byte[]> compress, int type) {
        var out = new ByteArrayOutputStream();
        var data = new ByteArrayOutputStream();
        byte[] lastKey = new byte[0];
        for (var entry : internalEntries.entrySet()) {
            writeVarint(data, 0);
            writeVarint(data, entry.getKey().length);
            writeVarint(data, entry.getValue().length);
            data.writeBytes(entry.getKey());
            data.writeBytes(entry.getValue());
            lastKey = entry.getKey();
        }
        writeFixed32(data, 0);
        writeFixed32(data, 1);
        var dataBlock = data.toByteArray();
        var stored = compress == null ? dataBlock : compress.apply(dataBlock);
        var dataOffset = out.size();
        writeBlock(out, stored, compress == null ? 0 : type);

        var metaOffset = out.size();
        var meta = new ByteArrayOutputStream();
        writeFixed32(meta, 0);
        writeFixed32(meta, 1);
        var metaBlock = meta.toByteArray();
        writeBlock(out, metaBlock, 0);

        var index = new ByteArrayOutputStream();
        var handle = new ByteArrayOutputStream();
        writeVarint(handle, dataOffset);
        writeVarint(handle, stored.length);
        writeVarint(index, 0);
        writeVarint(index, lastKey.length);
        writeVarint(index, handle.size());
        index.writeBytes(lastKey);
        index.writeBytes(handle.toByteArray());
        writeFixed32(index, 0);
        writeFixed32(index, 1);
        var indexOffset = out.size();
        var indexBlock = index.toByteArray();
        writeBlock(out, indexBlock, 0);

        var footer = new ByteArrayOutputStream();
        writeVarint(footer, metaOffset);
        writeVarint(footer, metaBlock.length);
        writeVarint(footer, indexOffset);
        writeVarint(footer, indexBlock.length);
        footer.writeBytes(new byte[40 - footer.size()]);
        writeFixed64(footer, 0xdb4775248b80fb57L);
        out.writeBytes(footer.toByteArray());
        return out.toByteArray();
    }

    private static void writeBlock(ByteArrayOutputStream out, byte[] block, int type) {
        out.writeBytes(block);
        out.write(type);
        writeFixed32(out, LevelDb.maskedBlockCrc(block, 0, block.length, (byte) type));
    }

    /// {@return an internal key: the user key and its sequence and type}
    public static byte[] internalKey(byte[] userKey, long sequence, boolean deletion) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(userKey);
        writeFixed64(out, sequence << 8 | (deletion ? 0 : 1));
        return out.toByteArray();
    }

    /// {@return a descriptor of one version edit: a comparator, the log number, the live and deleted tables}
    public static byte[] manifest(long logNumber, List<Long> liveTables, List<Long> deletedTables) {
        var edit = new ByteArrayOutputStream();
        writeVarint(edit, 1);
        var comparator = "idb_cmp1".getBytes(StandardCharsets.US_ASCII);
        writeVarint(edit, comparator.length);
        edit.writeBytes(comparator);
        writeVarint(edit, 2);
        writeVarint(edit, logNumber);
        for (var table : liveTables) {
            writeVarint(edit, 7);
            writeVarint(edit, 0);
            writeVarint(edit, table);
            writeVarint(edit, 100);
            writeVarint(edit, 1);
            edit.write('a');
            writeVarint(edit, 1);
            edit.write('z');
        }
        for (var table : deletedTables) {
            writeVarint(edit, 6);
            writeVarint(edit, 0);
            writeVarint(edit, table);
        }
        return logRecords(List.of(edit.toByteArray()));
    }

    /// Writes `CURRENT` naming `MANIFEST-<number>`, and the descriptor.
    public static void writeDescriptor(Path directory, long number, byte[] manifest) throws IOException {
        Files.createDirectories(directory);
        Files.write(directory.resolve(String.format("MANIFEST-%06d", number)), manifest);
        Files.writeString(directory.resolve("CURRENT"), String.format("MANIFEST-%06d%n", number), StandardCharsets.US_ASCII);
    }

    /// {@return a raw Snappy stream of literal runs only, which every decoder accepts}
    public static byte[] snappyLiterals(byte[] input) {
        var out = new ByteArrayOutputStream();
        writeVarint(out, input.length);
        var offset = 0;
        while (offset < input.length) {
            var length = Math.min(65536, input.length - offset);
            if (length <= 60) {
                out.write((length - 1) << 2);
            } else if (length <= 256) {
                out.write(60 << 2);
                out.write(length - 1);
            } else {
                out.write(61 << 2);
                out.write((length - 1) & 0xFF);
                out.write((length - 1) >>> 8);
            }
            out.write(input, offset, length);
            offset += length;
        }
        return out.toByteArray();
    }

    /// Writes `value` as an unsigned varint.
    public static void writeVarint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7FL) != 0) {
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
    }

    /// Writes `value` as 4 little-endian bytes.
    public static void writeFixed32(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write(value >>> 8 & 0xFF);
        out.write(value >>> 16 & 0xFF);
        out.write(value >>> 24 & 0xFF);
    }

    /// Writes `value` as 8 little-endian bytes.
    public static void writeFixed64(ByteArrayOutputStream out, long value) {
        writeFixed32(out, (int) value);
        writeFixed32(out, (int) (value >>> 32));
    }
}
