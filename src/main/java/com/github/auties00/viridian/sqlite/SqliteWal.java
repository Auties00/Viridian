package com.github.auties00.viridian.sqlite;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/// The committed frames of a database's write-ahead log, the `-wal` file, found by replaying it as SQLite's recovery
/// does, without the `-shm` index.
///
/// The log is a 32-byte header, then frames of a 24-byte header and one page each:
///
/// ```text
/// header  0  magic 0x377F0682 or 0x377F0683       frame  0  page number
///         4  format version, 3007000                     4  database size in pages after a commit, else 0
///         8  page size                                   8  salt 1 and salt 2, copied from the header
///        12  checkpoint sequence number                 16  checksum 1 and checksum 2
///        16  salt 1 and salt 2                          24  the page
///        24  checksum 1 and checksum 2 of bytes 0 to 23
/// ```
///
/// A frame is valid while its salts match the header's and its checksum matches the running checksum over the
/// header, every earlier frame, the first 8 bytes of its own header and its page. The low bit of the magic picks the
/// byte order the checksum reads 32-bit words in: 1 for big-endian, 0 for little-endian. Replay stops at the first
/// invalid frame, and only frames up to the last valid commit frame, one whose database size is nonzero, are part
/// of the snapshot; for each page the newest such frame wins.
///
/// The log stays open so that a page is read from it only when it is needed. Its contents are fixed when it is
/// opened; a writer that resets the log afterwards makes later reads fail or return the new contents.
///
/// @spec https://www.sqlite.org/walformat.html WAL-mode File Format
/// @spec https://www.sqlite.org/fileformat2.html Database File Format
final class SqliteWal implements AutoCloseable {
    /// The size of the log header.
    private static final int HEADER_SIZE = 32;

    /// The size of a frame header.
    private static final int FRAME_HEADER_SIZE = 24;

    /// The magic number with its byte-order bit cleared.
    private static final int MAGIC = 0x377F0682;

    /// The only format version SQLite has defined.
    private static final int VERSION = 3007000;

    /// The open log.
    private final FileChannel channel;

    /// The page size, which every frame carries one of.
    private final int pageSize;

    /// The database size in pages that the last valid commit frame records.
    private final long databaseSize;

    /// The page numbers of the open-addressing table that maps a page to its newest committed frame; 0 is empty.
    private final int[] pages;

    /// The file offset of the page data of the frame at the same slot of [#pages].
    private final long[] offsets;

    /// Wraps a replayed log.
    private SqliteWal(FileChannel channel, int pageSize, long databaseSize, int[] pages, long[] offsets) {
        this.channel = channel;
        this.pageSize = pageSize;
        this.databaseSize = databaseSize;
        this.pages = pages;
        this.offsets = offsets;
    }

    /// Opens and replays the log at `path`.
    ///
    /// @param pageSize the database's page size, which the log's must match
    /// @return the log, or `null` if there is none, it is empty, or its header is invalid, all of which SQLite
    ///         treats as an empty log
    /// @throws IOException if the log is valid but of an unknown format version or another page size
    static SqliteWal open(Path path, int pageSize) throws IOException {
        FileChannel channel;
        try {
            channel = FileChannel.open(path, StandardOpenOption.READ);
        } catch (NoSuchFileException absent) {
            return null;
        }
        try {
            var wal = replay(path, channel, pageSize);
            if (wal == null) {
                channel.close();
            }
            return wal;
        } catch (IOException | RuntimeException | Error failure) {
            try {
                channel.close();
            } catch (IOException closing) {
                failure.addSuppressed(closing);
            }
            throw failure;
        }
    }

    /// Replays the log open in `channel`.
    private static SqliteWal replay(Path path, FileChannel channel, int pageSize) throws IOException {
        var size = channel.size();
        if (size < HEADER_SIZE) {
            return null;
        }
        var header = new byte[HEADER_SIZE];
        SqliteBytes.read(channel, 0, header, 0, HEADER_SIZE);
        var magic = SqliteBytes.i32(header, 0);
        var logPageSize = SqliteBytes.u32(header, 8);
        if ((magic & ~1) != MAGIC || logPageSize < 512 || logPageSize > 65536 || (logPageSize & (logPageSize - 1)) != 0) {
            return null;
        }
        var bigEndian = (magic & 1) == 1;
        var sums = new int[2];
        checksum(header, 0, 24, bigEndian, sums);
        if (sums[0] != SqliteBytes.i32(header, 24) || sums[1] != SqliteBytes.i32(header, 28)) {
            return null;
        }
        var version = SqliteBytes.i32(header, 4);
        if (version != VERSION) {
            throw new IOException("The write-ahead log " + path + " has format version " + Integer.toUnsignedString(version)
                    + "; only " + VERSION + " is defined");
        }
        if (logPageSize != pageSize) {
            throw new IOException("The write-ahead log " + path + " holds " + logPageSize + "-byte pages, but the database's pages are "
                    + pageSize + " bytes");
        }
        var salt1 = SqliteBytes.i32(header, 16);
        var salt2 = SqliteBytes.i32(header, 20);
        var frameSize = FRAME_HEADER_SIZE + pageSize;
        var frameCount = (size - HEADER_SIZE) / frameSize;
        if (frameCount > Integer.MAX_VALUE) {
            throw new IOException("The write-ahead log " + path + " has more frames than this reader supports");
        }
        var framePages = new int[(int) frameCount];
        var frame = new byte[frameSize];
        var committed = 0;
        var databaseSize = 0L;
        for (var index = 0; index < frameCount; index++) {
            var offset = HEADER_SIZE + (long) index * frameSize;
            if (SqliteBytes.read(channel, offset, frame, 0, frameSize) < frameSize) {
                break;
            }
            var page = SqliteBytes.u32(frame, 0);
            if (page == 0 || SqliteBytes.i32(frame, 8) != salt1 || SqliteBytes.i32(frame, 12) != salt2) {
                break;
            }
            checksum(frame, 0, 8, bigEndian, sums);
            checksum(frame, FRAME_HEADER_SIZE, pageSize, bigEndian, sums);
            if (sums[0] != SqliteBytes.i32(frame, 16) || sums[1] != SqliteBytes.i32(frame, 20)) {
                break;
            }
            framePages[index] = page > Integer.MAX_VALUE ? 0 : (int) page;
            var commitSize = SqliteBytes.u32(frame, 4);
            if (commitSize != 0) {
                committed = index + 1;
                databaseSize = commitSize;
            }
        }
        var capacity = Integer.highestOneBit(Math.max(4, committed * 2 + 1)) << 1;
        var pages = new int[capacity];
        var offsets = new long[capacity];
        for (var index = 0; index < committed; index++) {
            var page = framePages[index];
            if (page == 0) {
                continue;
            }
            var slot = slot(pages, page);
            pages[slot] = page;
            offsets[slot] = HEADER_SIZE + (long) index * frameSize + FRAME_HEADER_SIZE;
        }
        return new SqliteWal(channel, pageSize, databaseSize, pages, offsets);
    }

    /// Adds the checksum of `data[offset, offset + length)`, read as pairs of 32-bit words, to the running `sums`.
    ///
    /// @param bigEndian whether the words are big-endian rather than little-endian
    static void checksum(byte[] data, int offset, int length, boolean bigEndian, int[] sums) {
        var first = sums[0];
        var second = sums[1];
        for (var i = offset; i < offset + length; i += 8) {
            if (bigEndian) {
                first += SqliteBytes.i32(data, i) + second;
                second += SqliteBytes.i32(data, i + 4) + first;
            } else {
                first += SqliteBytes.i32LittleEndian(data, i) + second;
                second += SqliteBytes.i32LittleEndian(data, i + 4) + first;
            }
        }
        sums[0] = first;
        sums[1] = second;
    }

    /// {@return the slot of `table` that holds `page`, or the empty slot where it belongs}
    private static int slot(int[] table, int page) {
        var mask = table.length - 1;
        var slot = (page * 0x9E3779B9) >>> 7 & mask;
        while (table[slot] != 0 && table[slot] != page) {
            slot = slot + 1 & mask;
        }
        return slot;
    }

    /// {@return the database size in pages after the last committed transaction, or 0 if no transaction committed}
    long databaseSize() {
        return databaseSize;
    }

    /// {@return the file offset of the newest committed copy of `page`, or -1 if the log holds none}
    long find(int page) {
        var slot = slot(pages, page);
        return pages[slot] == page ? offsets[slot] : -1;
    }

    /// {@return the page whose data starts at `offset`, as [#find(int)] returned it}
    ///
    /// @throws IOException if the log has shrunk since it was opened, which a writer resetting it causes
    byte[] read(long offset) throws IOException {
        var page = new byte[pageSize];
        if (SqliteBytes.read(channel, offset, page, 0, pageSize) < pageSize) {
            throw new IOException("The write-ahead log has shrunk since the database was opened, as a writer resetting it does");
        }
        return page;
    }

    /// Closes the log.
    @Override
    public void close() throws IOException {
        channel.close();
    }
}
