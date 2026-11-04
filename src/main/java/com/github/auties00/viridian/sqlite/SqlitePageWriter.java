package com.github.auties00.viridian.sqlite;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/// The pages of a database being written: it hands out page numbers in increasing order and writes each page once.
///
/// Page 1 is reserved for the database header and the root of `sqlite_schema`, which are known last, so numbering
/// starts at 2. The page holding the byte at offset 2^30, which SQLite's file locks cover, never holds content: it is
/// skipped, and left as a hole of zeros in the file. Pages written in the order they were numbered are gathered into
/// one write; any other page is written on its own.
///
/// @spec https://www.sqlite.org/fileformat2.html Database File Format
final class SqlitePageWriter {
    /// The offset of the byte that SQLite's file locks cover.
    private static final long LOCK_BYTE_OFFSET = 0x40000000L;

    /// The bytes of consecutive pages gathered before they are written.
    private static final int BUFFER_BYTES = 1 << 20;

    /// The file written to.
    private final FileChannel file;

    /// The size of every page.
    private final int pageSize;

    /// The page that holds the lock byte.
    private final int lockBytePage;

    /// The consecutive pages not written yet.
    private final ByteBuffer buffer;

    /// The number of the first page in [#buffer], valid while it holds any.
    private int bufferStart;

    /// The highest page number handed out, or 1 when only page 1 is taken.
    private int pageCount = 1;

    /// Wraps `file`, whose pages are `pageSize` bytes.
    SqlitePageWriter(FileChannel file, int pageSize) {
        this.file = file;
        this.pageSize = pageSize;
        this.lockBytePage = (int) (LOCK_BYTE_OFFSET / pageSize) + 1;
        this.buffer = ByteBuffer.allocate(Math.max(pageSize, BUFFER_BYTES));
    }

    /// {@return a new page number, the next after the last one handed out}
    ///
    /// @throws IllegalStateException if the database already has the most pages SQLite allows
    int allocate() {
        if (pageCount == Integer.MAX_VALUE - 1) {
            throw new IllegalStateException("The database has the most pages SQLite allows, " + pageCount);
        }
        pageCount++;
        if (pageCount == lockBytePage) {
            pageCount++;
        }
        return pageCount;
    }

    /// Writes `content`, a whole page, as page `page`.
    void write(int page, byte[] content) throws IOException {
        if (buffer.position() > 0 && page != bufferStart + buffer.position() / pageSize) {
            flush();
        }
        if (buffer.position() == 0) {
            bufferStart = page;
        }
        buffer.put(content, 0, pageSize);
        if (!buffer.hasRemaining()) {
            flush();
        }
    }

    /// Writes the gathered pages.
    void flush() throws IOException {
        buffer.flip();
        var position = (long) (bufferStart - 1) * pageSize;
        while (buffer.hasRemaining()) {
            position += file.write(buffer, position);
        }
        buffer.clear();
    }

    /// {@return the size of every page}
    int pageSize() {
        return pageSize;
    }

    /// {@return the number of pages in the database: the highest page number handed out}
    int pageCount() {
        return pageCount;
    }
}
