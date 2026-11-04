package com.github.auties00.viridian.leveldb;

import com.github.auties00.viridian.compress.Snappy;
import com.github.auties00.viridian.compress.Zstd;
import com.github.auties00.viridian.io.ByteCursor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/// Reads the entries of a LevelDB table file, one block at a time.
///
/// A table ends with a 48-byte footer: the handles of the meta-index and index blocks, padding, and an 8-byte magic
/// number. The index block maps to the data blocks. Every block is followed by a 1-byte compression type (0 none, 1
/// Snappy, 2 zstd) and a masked CRC32C of the block and that byte. A block is a run of prefix-compressed entries
/// followed by its array of restart points. Every key of a table is an internal key: the user key, then the
/// little-endian `sequence << 8 | type`, type 1 for a value and 0 for a deletion.
///
/// @spec https://github.com/google/leveldb/blob/main/doc/table_format.md LevelDB table format
final class LevelDbTable {
    private static final int FOOTER_SIZE = 48;
    private static final long MAGIC = 0xdb4775248b80fb57L;
    private static final int TRAILER_SIZE = 5;
    // Blocks are a few KiB; a single huge entry makes a block of its own size, bounded here against damage
    private static final int MAX_BLOCK_SIZE = 512 * 1024 * 1024;

    private LevelDbTable() {
    }

    /// Passes every entry of the table at `path` to `sink`.
    ///
    /// @throws IOException if the table cannot be read, a checksum fails, or a block does not decode
    static void read(Path path, LevelDb.EntrySink sink) throws IOException {
        try (var channel = FileChannel.open(path, StandardOpenOption.READ)) {
            var size = channel.size();
            if (size < FOOTER_SIZE) {
                throw new IOException("The table " + path.getFileName() + " is shorter than its footer");
            }
            var footer = readFully(channel, size - FOOTER_SIZE, FOOTER_SIZE);
            var magic = new ByteCursor(footer, FOOTER_SIZE - 8, FOOTER_SIZE).i64le();
            if (magic != MAGIC) {
                throw new IOException("The file " + path.getFileName() + " is not a LevelDB table");
            }
            var handles = new ByteCursor(footer, 0, FOOTER_SIZE - 8);
            handles.varint64();
            handles.varint64();
            var indexOffset = handles.varint64();
            var indexSize = handles.varint64();
            var index = readBlock(channel, size, indexOffset, indexSize, path);
            forEachEntry(index, path, (_, handleBytes) -> {
                var handle = new ByteCursor(handleBytes);
                var block = readBlock(channel, size, handle.varint64(), handle.varint64(), path);
                forEachEntry(block, path, (internalKey, value) -> {
                    if (internalKey.length < 8) {
                        throw new IOException("The table " + path.getFileName() + " has an internal key shorter than its trailer");
                    }
                    var trailer = new ByteCursor(internalKey, internalKey.length - 8, internalKey.length).i64le();
                    var type = (int) (trailer & 0xFF);
                    var userKey = Arrays.copyOf(internalKey, internalKey.length - 8);
                    switch (type) {
                        case 1 -> sink.accept(userKey, trailer >>> 8, value);
                        case 0 -> sink.accept(userKey, trailer >>> 8, null);
                        default -> throw new IOException("The table " + path.getFileName() + " has an entry of unknown type " + type);
                    }
                });
            });
        }
    }

    /// {@return the decompressed contents of the block at `offset`, after checking its checksum}
    private static byte[] readBlock(FileChannel channel, long fileSize, long offset, long size, Path path) throws IOException {
        if (offset < 0 || size < 0 || size > MAX_BLOCK_SIZE || offset > fileSize - TRAILER_SIZE - size) {
            throw new IOException("The table " + path.getFileName() + " has a block handle outside the file");
        }
        var length = (int) size;
        var raw = readFully(channel, offset, length + TRAILER_SIZE);
        var type = raw[length];
        var stored = new ByteCursor(raw, length + 1, length + TRAILER_SIZE).i32le();
        if (stored != LevelDb.maskedBlockCrc(raw, 0, length, type)) {
            throw new IOException("The table " + path.getFileName() + " has a block at " + offset + " whose checksum fails");
        }
        return switch (type) {
            case 0 -> Arrays.copyOf(raw, length);
            case 1 -> Snappy.decompress(raw, 0, length, MAX_BLOCK_SIZE);
            case 2 -> Zstd.decompress(raw, 0, length, MAX_BLOCK_SIZE);
            default -> throw new IOException("The table " + path.getFileName() + " has a block of unknown compression " + type);
        };
    }

    /// Passes each key and value of a block to `consumer`, in block order.
    private static void forEachEntry(byte[] block, Path path, BlockConsumer consumer) throws IOException {
        if (block.length < 4) {
            throw new IOException("The table " + path.getFileName() + " has a block shorter than its restart count");
        }
        var restarts = Integer.toUnsignedLong(new ByteCursor(block, block.length - 4, block.length).i32le());
        var limit = block.length - 4 - 4 * restarts;
        if (limit < 0) {
            throw new IOException("The table " + path.getFileName() + " has a block whose restart array overruns it");
        }
        var input = new ByteCursor(block, 0, (int) limit);
        var previous = new byte[0];
        while (input.hasRemaining()) {
            var shared = input.varintLength();
            var unshared = input.varintLength();
            var valueLength = input.varintLength();
            if (shared > previous.length) {
                throw new IOException("The table " + path.getFileName() + " has an entry sharing more of its key than the previous key has");
            }
            input.require(unshared, "a key");
            var key = new byte[shared + unshared];
            System.arraycopy(previous, 0, key, 0, shared);
            System.arraycopy(block, input.position(), key, shared, unshared);
            input.skip(unshared);
            consumer.accept(key, input.bytes(valueLength));
            previous = key;
        }
    }

    private static byte[] readFully(FileChannel channel, long position, int length) throws IOException {
        var buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer, position + buffer.position()) < 0) {
                throw new IOException("The file ends before " + length + " bytes at " + position);
            }
        }
        return buffer.array();
    }

    /// Receives the entries of a block.
    @FunctionalInterface
    private interface BlockConsumer {
        void accept(byte[] key, byte[] value) throws IOException;
    }
}
