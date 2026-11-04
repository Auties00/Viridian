package com.github.auties00.viridian.leveldb;

import com.github.auties00.viridian.io.ByteCursor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/// Reads LevelDB's log format: the write-ahead logs and, in the same framing, the descriptor.
///
/// A log is a sequence of 32 KiB blocks of physical records: a 4-byte masked CRC32C, a 2-byte little-endian length
/// and a 1-byte type (full, first, middle or last fragment of a logical record), then the payload. A block's last
/// bytes, too few for a header, are zero padding.
///
/// @spec https://github.com/google/leveldb/blob/main/doc/log_format.md LevelDB log format
final class LevelDbLog {
    static final int BLOCK_SIZE = 32 * 1024;
    private static final int HEADER_SIZE = 7;
    private static final int FULL = 1;
    private static final int FIRST = 2;
    private static final int MIDDLE = 3;
    private static final int LAST = 4;
    private static final int TYPE_DELETION = 0;
    private static final int TYPE_VALUE = 1;

    private LevelDbLog() {
    }

    /// Passes each logical record of the log at `path` to `consumer`, in order.
    ///
    /// A record cut short by the end of the file ends the log, as a crash leaves it. Otherwise a record whose checksum
    /// fails, or of an unknown type, is dropped with the rest of its block, as LevelDB recovers a write-ahead log; when
    /// `strict`, as for a descriptor, whose every edit matters, it fails the read instead.
    ///
    /// @throws IOException if the file cannot be read, or, when `strict`, a record is damaged
    static void readRecords(Path path, boolean strict, RecordConsumer consumer) throws IOException {
        try (var channel = FileChannel.open(path, StandardOpenOption.READ)) {
            var block = new byte[BLOCK_SIZE];
            var fragments = new ByteArrayOutputStream();
            var inFragment = false;
            var position = 0L;
            while (true) {
                var read = readBlock(channel, position, block);
                if (read <= 0) {
                    return;
                }
                var offset = 0;
                while (BLOCK_SIZE - offset >= HEADER_SIZE && offset + HEADER_SIZE <= read) {
                    var length = (block[offset + 4] & 0xFF) | (block[offset + 5] & 0xFF) << 8;
                    var type = block[offset + 6] & 0xFF;
                    if (type == 0 && length == 0) {
                        // Zero bytes: the padding of a preallocated or recycled file
                        break;
                    }
                    if (offset + HEADER_SIZE + length > read) {
                        if (read < BLOCK_SIZE) {
                            // The file ends inside the record: a write a crash cut short
                            return;
                        }
                        damaged(strict, path, position + offset, "runs past its block");
                        fragments.reset();
                        inFragment = false;
                        break;
                    }
                    var stored = (block[offset] & 0xFF) | (block[offset + 1] & 0xFF) << 8
                            | (block[offset + 2] & 0xFF) << 16 | (block[offset + 3] & 0xFF) << 24;
                    if (stored != LevelDb.maskedCrc((byte) type, block, offset + HEADER_SIZE, length)) {
                        damaged(strict, path, position + offset, "fails its checksum");
                        fragments.reset();
                        inFragment = false;
                        break;
                    }
                    var start = offset + HEADER_SIZE;
                    switch (type) {
                        case FULL -> {
                            fragments.reset();
                            inFragment = false;
                            consumer.accept(Arrays.copyOfRange(block, start, start + length));
                        }
                        case FIRST -> {
                            fragments.reset();
                            fragments.write(block, start, length);
                            inFragment = true;
                        }
                        case MIDDLE -> {
                            if (inFragment) {
                                fragments.write(block, start, length);
                            }
                        }
                        case LAST -> {
                            if (inFragment) {
                                fragments.write(block, start, length);
                                consumer.accept(fragments.toByteArray());
                            }
                            fragments.reset();
                            inFragment = false;
                        }
                        default -> {
                            damaged(strict, path, position + offset, "has unknown type " + type);
                            fragments.reset();
                            inFragment = false;
                            offset = BLOCK_SIZE;
                            continue;
                        }
                    }
                    offset = start + length;
                }
                if (read < BLOCK_SIZE) {
                    return;
                }
                position += BLOCK_SIZE;
            }
        }
    }

    /// Passes each write of the log at `path` to `sink`, as its write batches record them.
    ///
    /// A batch is an 8-byte little-endian sequence number, a 4-byte count, then that many operations: tag 1 and a
    /// varint-prefixed key and value for a put, tag 0 and a varint-prefixed key for a deletion. The operations of a
    /// batch take consecutive sequence numbers.
    static void readBatches(Path path, LevelDb.EntrySink sink) throws IOException {
        readRecords(path, false, record -> applyBatch(record, sink));
    }

    private static void applyBatch(byte[] batch, LevelDb.EntrySink sink) throws IOException {
        var input = new ByteCursor(batch);
        var sequence = input.i64le();
        var count = Integer.toUnsignedLong(input.i32le());
        for (var i = 0L; i < count; i++) {
            var type = input.u8();
            var key = input.lengthPrefixed();
            var value = switch (type) {
                case TYPE_VALUE -> input.lengthPrefixed();
                case TYPE_DELETION -> null;
                default -> throw new IOException("A write batch has an operation of unknown type " + type);
            };
            sink.accept(key, sequence + i, value);
        }
        if (input.hasRemaining()) {
            throw new IOException("A write batch has bytes past its " + count + " operations");
        }
    }

    /// Fails when `strict`, for a damaged record at `position`; otherwise the record is dropped.
    private static void damaged(boolean strict, Path path, long position, String what) throws IOException {
        if (strict) {
            throw new IOException("The record at " + position + " of " + path.getFileName() + " " + what);
        }
    }

    /// {@return how many bytes of the block at `position` were read into `block`; fewer than a block at the end}
    private static int readBlock(FileChannel channel, long position, byte[] block) throws IOException {
        var buffer = ByteBuffer.wrap(block);
        while (buffer.hasRemaining()) {
            var read = channel.read(buffer, position + buffer.position());
            if (read < 0) {
                break;
            }
        }
        return buffer.position();
    }

    /// Receives the logical records of a log.
    @FunctionalInterface
    interface RecordConsumer {
        /// Receives a record, which it may keep.
        void accept(byte[] record) throws IOException;
    }
}
