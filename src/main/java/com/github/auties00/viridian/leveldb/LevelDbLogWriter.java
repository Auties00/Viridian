package com.github.auties00.viridian.leveldb;

import java.io.IOException;
import java.io.OutputStream;

/// Writes LevelDB's log format, as [LevelDbLog] reads it: the logical records of a write-ahead log or a descriptor,
/// framed in 32 KiB blocks of physical records, each fragmented where it crosses a block boundary.
///
/// @spec https://github.com/google/leveldb/blob/main/doc/log_format.md LevelDB log format
final class LevelDbLogWriter {
    private static final int HEADER_SIZE = 7;
    private static final int FULL = 1;
    private static final int FIRST = 2;
    private static final int MIDDLE = 3;
    private static final int LAST = 4;

    private final OutputStream output;
    private final byte[] header = new byte[HEADER_SIZE];
    private int blockOffset;

    /// Creates a writer that appends records to `output`, which starts a block.
    LevelDbLogWriter(OutputStream output) {
        this.output = output;
    }

    /// Appends `record`, fragmenting it across blocks as needed; a block's tail too short for a header is zeros.
    void addRecord(byte[] record, int offset, int length) throws IOException {
        var first = true;
        var remaining = length;
        do {
            var leftover = LevelDbLog.BLOCK_SIZE - blockOffset;
            if (leftover < HEADER_SIZE) {
                output.write(new byte[leftover]);
                blockOffset = 0;
                leftover = LevelDbLog.BLOCK_SIZE;
            }
            var fragment = Math.min(remaining, leftover - HEADER_SIZE);
            var last = fragment == remaining;
            var type = first && last ? FULL : first ? FIRST : last ? LAST : MIDDLE;
            var crc = LevelDb.maskedCrc((byte) type, record, offset, fragment);
            header[0] = (byte) crc;
            header[1] = (byte) (crc >>> 8);
            header[2] = (byte) (crc >>> 16);
            header[3] = (byte) (crc >>> 24);
            header[4] = (byte) fragment;
            header[5] = (byte) (fragment >>> 8);
            header[6] = (byte) type;
            output.write(header);
            output.write(record, offset, fragment);
            blockOffset += HEADER_SIZE + fragment;
            offset += fragment;
            remaining -= fragment;
            first = false;
        } while (remaining > 0);
    }
}
