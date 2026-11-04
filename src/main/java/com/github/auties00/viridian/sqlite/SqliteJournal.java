package com.github.auties00.viridian.sqlite;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/// The pages a hot rollback journal restores: the state of a database before the transaction a writer was in the
/// middle of, or crashed during.
///
/// A rollback journal, the `-journal` file, holds the original content of every page a transaction changes. It is
/// hot when it exists, is not empty and does not start with a zero byte, since committing deletes, truncates or
/// zeroes it. It is read as SQLite plays it back, but into memory instead of into the database: one or more
/// segments, each a header padded to the sector size followed by page records.
///
/// ```text
/// header  0  magic D9 D5 05 F9 20 A1 63 D7            record  0  page number
///         8  records in this segment; 0xFFFFFFFF            4  the original page
///            means as many as the file holds          4 + page  checksum: the nonce plus every 200th byte of
///        12  checksum nonce                                      the page, from the end
///        16  database size in pages before the transaction
///        20  sector size (first header only)
///        24  page size (first header only)
/// ```
///
/// Playback stops at the first record whose page number is 0 or the lock-byte page, or whose checksum fails; records
/// for pages beyond the original size are skipped, since those pages are cut away. A journal that ends with the
/// name of a super-journal belongs to a multi-database transaction, which committed if that file no longer exists.
///
/// @spec https://www.sqlite.org/fileformat2.html Database File Format
/// @spec https://www.sqlite.org/atomiccommit.html Atomic Commit In SQLite
final class SqliteJournal {
    /// The bytes every journal header starts with, and that end a super-journal name.
    private static final byte[] MAGIC = {(byte) 0xD9, (byte) 0xD5, 0x05, (byte) 0xF9, 0x20, (byte) 0xA1, 0x63, (byte) 0xD7};

    /// The most page bytes a journal may restore, all held in memory.
    private static final long MAX_BYTES = 512L << 20;

    /// The offset of the lock byte, whose page never holds content.
    private static final long LOCK_BYTE_OFFSET = 0x40000000L;

    /// The longest super-journal name read, a bound on what a hostile journal can make this class allocate.
    private static final int MAX_NAME = 65536;

    /// The page size the journal was written with.
    private final int pageSize;

    /// The database size in pages before the transaction.
    private final long originalPageCount;

    /// The original content of each page the transaction changed.
    private final Map<Integer, byte[]> pages;

    /// Wraps a played-back journal.
    private SqliteJournal(int pageSize, long originalPageCount, Map<Integer, byte[]> pages) {
        this.pageSize = pageSize;
        this.originalPageCount = originalPageCount;
        this.pages = pages;
    }

    /// Plays back the journal at `path` into memory, if it is hot.
    ///
    /// The journal is read in full and closed before this method returns, so that a writer can still delete it.
    ///
    /// @param databasePageSize the page size in the database header, used when the journal records none, or -1 if
    ///                         the header holds no valid one
    /// @return the journal, or `null` if it is absent, not hot, belongs to a committed multi-database transaction, or
    ///         has no valid header, in each of which cases SQLite restores nothing
    /// @throws IOException if the journal restores more than 512 MiB of pages
    static SqliteJournal readHot(Path path, int databasePageSize) throws IOException {
        FileChannel channel;
        try {
            channel = FileChannel.open(path, StandardOpenOption.READ);
        } catch (NoSuchFileException absent) {
            return null;
        }
        try (channel) {
            var size = channel.size();
            var first = new byte[1];
            if (size == 0 || SqliteBytes.read(channel, 0, first, 0, 1) < 1 || first[0] == 0 || committedElsewhere(channel, size)) {
                return null;
            }
            return playback(path, channel, size, databasePageSize);
        }
    }

    /// Reads every segment of the journal open in `channel`.
    private static SqliteJournal playback(Path path, FileChannel channel, long size, int databasePageSize) throws IOException {
        var header = new byte[28];
        var pages = new HashMap<Integer, byte[]>();
        var offset = 0L;
        var sectorSize = 0;
        var pageSize = 0;
        var originalPageCount = -1L;
        var restored = 0L;
        segments:
        while (true) {
            if (originalPageCount >= 0) {
                offset = (offset + sectorSize - 1) / sectorSize * sectorSize;
            }
            if (offset + header.length > size) {
                break;
            }
            SqliteBytes.read(channel, offset, header, 0, header.length);
            if (!Arrays.equals(header, 0, MAGIC.length, MAGIC, 0, MAGIC.length)) {
                break;
            }
            var records = SqliteBytes.u32(header, 8);
            var nonce = SqliteBytes.i32(header, 12);
            var databasePages = SqliteBytes.u32(header, 16);
            if (originalPageCount < 0) {
                sectorSize = SqliteBytes.i32(header, 20);
                pageSize = SqliteBytes.i32(header, 24);
                if (pageSize == 0) {
                    pageSize = databasePageSize;
                }
                if (pageSize < 512 || pageSize > 65536 || (pageSize & (pageSize - 1)) != 0
                        || sectorSize < 32 || sectorSize > 65536 || (sectorSize & (sectorSize - 1)) != 0) {
                    break;
                }
            }
            if (offset + sectorSize > size) {
                break;
            }
            offset += sectorSize;
            if (originalPageCount < 0) {
                originalPageCount = databasePages;
            }
            var recordSize = pageSize + 8;
            if (records == 0xFFFFFFFFL) {
                records = (size - offset) / recordSize;
            }
            var lockBytePage = LOCK_BYTE_OFFSET / pageSize + 1;
            var record = new byte[recordSize];
            for (var i = 0L; i < records; i++) {
                if (offset + recordSize > size) {
                    break segments;
                }
                SqliteBytes.read(channel, offset, record, 0, recordSize);
                offset += recordSize;
                var page = SqliteBytes.u32(record, 0);
                if (page == 0 || page == lockBytePage) {
                    break segments;
                }
                if (page > originalPageCount || page > Integer.MAX_VALUE) {
                    continue;
                }
                var sum = nonce;
                for (var j = pageSize - 200; j > 0; j -= 200) {
                    sum += record[4 + j] & 0xFF;
                }
                if (sum != SqliteBytes.i32(record, 4 + pageSize)) {
                    break segments;
                }
                if (pages.put((int) page, Arrays.copyOfRange(record, 4, 4 + pageSize)) == null) {
                    restored += pageSize;
                    if (restored > MAX_BYTES) {
                        throw new IOException("The hot journal " + path + " restores more than " + (MAX_BYTES >> 20)
                                + " MiB of pages, too many to apply in memory");
                    }
                }
            }
        }
        return originalPageCount < 0 ? null : new SqliteJournal(pageSize, originalPageCount, pages);
    }

    /// {@return whether the journal names a super-journal that no longer exists, which means that the multi-database
    /// transaction it belongs to committed and nothing is restored}
    ///
    /// The name sits at the end of the journal: the name, its length, a checksum that subtracts every byte of the
    /// name, and the journal magic. A name whose checksum fails is ignored, as SQLite ignores it.
    private static boolean committedElsewhere(FileChannel channel, long size) throws IOException {
        if (size < 16) {
            return false;
        }
        var tail = new byte[16];
        SqliteBytes.read(channel, size - 16, tail, 0, 16);
        var length = SqliteBytes.u32(tail, 0);
        if (!Arrays.equals(tail, 8, 16, MAGIC, 0, MAGIC.length) || length == 0 || length > size - 16 || length > MAX_NAME) {
            return false;
        }
        var name = new byte[(int) length];
        SqliteBytes.read(channel, size - 16 - length, name, 0, name.length);
        var signed = SqliteBytes.i32(tail, 4);
        var unsigned = signed;
        for (var value : name) {
            signed -= value;
            unsigned -= value & 0xFF;
        }
        if (signed != 0 && unsigned != 0) {
            return false;
        }
        var end = 0;
        while (end < name.length && name[end] != 0) {
            end++;
        }
        if (end == 0) {
            return false;
        }
        try {
            return !Files.exists(Path.of(new String(name, 0, end, StandardCharsets.UTF_8)));
        } catch (InvalidPathException unusable) {
            return true;
        }
    }

    /// {@return the page size the journal was written with}
    int pageSize() {
        return pageSize;
    }

    /// {@return the database size in pages before the transaction}
    long originalPageCount() {
        return originalPageCount;
    }

    /// {@return the original content of `page`, or `null` if the transaction did not change it}
    byte[] page(int page) {
        return pages.get(page);
    }
}
