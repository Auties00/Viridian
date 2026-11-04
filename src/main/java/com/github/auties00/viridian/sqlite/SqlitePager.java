package com.github.auties00.viridian.sqlite;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/// The pages of a database as of its last committed transaction, read on demand and kept in a bounded cache.
///
/// A page comes from the newest of three places: the committed frames of the write-ahead log, the pages a hot
/// rollback journal restores, then the database file. That is the order SQLite itself would see them in after
/// rolling back the journal and opening the log, which it does whatever the file-format bytes of the header say. The
/// `-shm` file is never opened, and nothing is ever written or locked.
///
/// The 100-byte database header at the start of page 1 gives the layout:
///
/// ```text
///  0  "SQLite format 3\0"                       28  database size in pages
/// 16  page size, big-endian; 1 means 65536       24  file change counter
/// 18  write version, read version: 1 rollback,   44  schema format number, 1 to 4
///     2 write-ahead log                          56  text encoding: 1 UTF-8, 2 UTF-16LE, 3 UTF-16BE
/// 20  bytes reserved at the end of each page     60  user version
/// 21  payload fractions, always 64, 32, 32       92  version-valid-for number
/// ```
///
/// The database size at offset 28 is trusted only when the version-valid-for number equals the change counter, as
/// SQLite trusts it; otherwise the size comes from the last commit in the log, the size a hot journal restores, or
/// the length of the file.
///
/// @spec https://www.sqlite.org/fileformat2.html Database File Format
/// @spec https://www.sqlite.org/walformat.html WAL-mode File Format
final class SqlitePager implements AutoCloseable {
    /// The page size SQLite gives a new database, and this reader an empty file.
    static final int DEFAULT_PAGE_SIZE = 4096;

    /// The bytes the cache may hold.
    private static final long CACHE_BYTES = 8L << 20;

    /// The size of the database header.
    private static final int HEADER_SIZE = 100;

    /// The header string every database starts with.
    private static final byte[] MAGIC = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);

    /// The offset of the byte that SQLite's file locks cover, whose page never holds content.
    private static final long LOCK_BYTE_OFFSET = 0x40000000L;

    /// The database file.
    private final FileChannel file;

    /// The write-ahead log, or `null` if there is none or it is empty.
    private final SqliteWal wal;

    /// The hot rollback journal, or `null` if there is none.
    private final SqliteJournal journal;

    /// The size of every page.
    private final int pageSize;

    /// The bytes of each page that b-tree content may use: the page size less the reserved bytes.
    private final int usableSize;

    /// The number of pages in the database.
    private final int pageCount;

    /// The page that holds the lock byte.
    private final int lockBytePage;

    /// The text encoding.
    private final SqliteTextEncoding textEncoding;

    /// The schema format number, which decides whether `DESC` index columns are honoured.
    private final int schemaFormat;

    /// The user version.
    private final int userVersion;

    /// The least recently used pages, evicted in access order once the cache holds [#CACHE_BYTES].
    private final Map<Integer, byte[]> cache;

    /// Whether [#close()] has run.
    private boolean closed;

    /// Wraps an opened database.
    private SqlitePager(FileChannel file, SqliteWal wal, SqliteJournal journal, int pageSize, int usableSize,
                        int pageCount, SqliteTextEncoding textEncoding, int schemaFormat, int userVersion) {
        this.file = file;
        this.wal = wal;
        this.journal = journal;
        this.pageSize = pageSize;
        this.usableSize = usableSize;
        this.pageCount = pageCount;
        this.lockBytePage = (int) (LOCK_BYTE_OFFSET / pageSize) + 1;
        this.textEncoding = textEncoding;
        this.schemaFormat = schemaFormat;
        this.userVersion = userVersion;
        var capacity = (int) Math.max(64, CACHE_BYTES / pageSize);
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, byte[]> eldest) {
                return size() > capacity;
            }
        };
    }

    /// Opens the database at `path` with its write-ahead log and hot journal, if it has them.
    ///
    /// @throws IOException if the file cannot be read or is not a database SQLite could open
    static SqlitePager open(Path path) throws IOException {
        var file = FileChannel.open(path, StandardOpenOption.READ);
        SqliteWal wal = null;
        try {
            var fileSize = file.size();
            if (fileSize == 0) {
                return empty(file);
            }
            var header = new byte[HEADER_SIZE];
            SqliteBytes.read(file, 0, header, 0, HEADER_SIZE);
            var journal = SqliteJournal.readHot(SqliteBytes.sibling(path, "-journal"), validPageSize(header));
            if (journal != null && journal.originalPageCount() == 0) {
                return empty(file);
            }
            var pageSize = journal != null ? journal.pageSize() : validPageSize(header);
            if (pageSize < 0) {
                checkHeader(path, header, -1);
            }
            wal = SqliteWal.open(SqliteBytes.sibling(path, "-wal"), pageSize);
            var page1 = journal == null ? null : journal.page(1);
            var frame = wal == null ? -1 : wal.find(1);
            if (frame >= 0) {
                page1 = wal.read(frame);
            }
            if (page1 == null) {
                page1 = new byte[pageSize];
                SqliteBytes.read(file, 0, page1, 0, pageSize);
            }
            checkHeader(path, page1, pageSize);
            long filePages;
            if (wal != null && wal.databaseSize() > 0) {
                filePages = wal.databaseSize();
            } else if (journal != null) {
                filePages = journal.originalPageCount();
            } else {
                filePages = (fileSize + pageSize - 1) / pageSize;
            }
            var headerPages = SqliteBytes.u32(page1, 28);
            var pages = headerPages != 0 && SqliteBytes.i32(page1, 24) == SqliteBytes.i32(page1, 92) ? headerPages : filePages;
            if (pages > filePages) {
                throw new IOException(path + " declares " + pages + " pages in its header, but only " + filePages + " exist");
            }
            if (pages > Integer.MAX_VALUE) {
                throw new IOException(path + " has " + pages + " pages, more than this reader supports");
            }
            var schemaFormat = SqliteBytes.i32(page1, 44);
            if (schemaFormat < 0 || schemaFormat > 4) {
                throw new IOException(path + " uses schema format " + Integer.toUnsignedString(schemaFormat) + "; formats 1 to 4 exist");
            }
            return new SqlitePager(file, wal, journal, pageSize, pageSize - SqliteBytes.u8(page1, 20), (int) pages,
                    SqliteTextEncoding.ofHeader(SqliteBytes.u32(page1, 56)), Math.max(1, schemaFormat), SqliteBytes.i32(page1, 60));
        } catch (IOException | RuntimeException | Error failure) {
            closeQuietly(wal, failure);
            closeQuietly(file, failure);
            throw failure;
        }
    }

    /// {@return the pager of a zero-length database file, which SQLite treats as an empty database}
    private static SqlitePager empty(FileChannel file) {
        return new SqlitePager(file, null, null, DEFAULT_PAGE_SIZE, DEFAULT_PAGE_SIZE, 0, SqliteTextEncoding.UTF_8, 4, 0);
    }

    /// {@return the page size that a database header declares, or -1 if it does not declare a valid one}
    ///
    /// The two bytes at offset 16 hold the size big-endian; a size of 65536 does not fit and is stored as 1.
    private static int validPageSize(byte[] header) {
        var pageSize = SqliteBytes.u8(header, 16) << 8 | SqliteBytes.u8(header, 17) << 16;
        return pageSize < 512 || pageSize > 65536 || (pageSize & (pageSize - 1)) != 0 ? -1 : pageSize;
    }

    /// Checks that `header` is a database header SQLite could read.
    ///
    /// @param pageSize the page size the header must declare, or -1 for none in particular
    /// @throws IOException naming the first field that is wrong
    private static void checkHeader(Path path, byte[] header, int pageSize) throws IOException {
        if (!Arrays.equals(header, 0, MAGIC.length, MAGIC, 0, MAGIC.length)) {
            throw new IOException(path + " is not a SQLite database: it does not start with \"SQLite format 3\"");
        }
        var declared = validPageSize(header);
        if (declared < 0) {
            throw new IOException(path + " declares an invalid page size, " + SqliteBytes.u16(header, 16));
        }
        if (pageSize >= 0 && declared != pageSize) {
            throw new IOException(path + " declares " + declared + "-byte pages on its current page 1, but its file, journal and log use "
                    + pageSize + "-byte pages");
        }
        var readVersion = SqliteBytes.u8(header, 19);
        if (readVersion > 2) {
            throw new IOException(path + " has file-format read version " + readVersion + ", which no released SQLite can read");
        }
        var usable = declared - SqliteBytes.u8(header, 20);
        if (usable < 480) {
            throw new IOException(path + " reserves " + SqliteBytes.u8(header, 20) + " bytes of each " + declared
                    + "-byte page, leaving fewer than the 480 a b-tree page needs");
        }
        if (header[21] != 64 || header[22] != 32 || header[23] != 32) {
            throw new IOException(path + " declares payload fractions " + SqliteBytes.u8(header, 21) + ", " + SqliteBytes.u8(header, 22)
                    + " and " + SqliteBytes.u8(header, 23) + "; SQLite requires 64, 32 and 32");
        }
    }

    /// Closes `closeable`, adding any failure to `failure`.
    private static void closeQuietly(AutoCloseable closeable, Throwable failure) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception closing) {
            failure.addSuppressed(closing);
        }
    }

    /// {@return the content of page `number`}
    ///
    /// The returned array is shared with the cache and must not be modified.
    ///
    /// @throws IOException if the database is closed, `number` is not a page of the database or is the lock-byte page,
    ///                     or the page cannot be read
    byte[] page(long number) throws IOException {
        if (closed) {
            throw new IOException("The database is closed");
        }
        if (number < 1 || number > pageCount) {
            throw new IOException("Page " + number + " is out of range: the database has " + pageCount + " pages");
        }
        if (number == lockBytePage) {
            throw new IOException("Page " + number + " holds the lock byte and never holds content");
        }
        var key = (int) number;
        var cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        byte[] page = null;
        var frame = wal == null ? -1 : wal.find(key);
        if (frame >= 0) {
            page = wal.read(frame);
        } else if (journal != null) {
            page = journal.page(key);
        }
        if (page == null) {
            page = new byte[pageSize];
            SqliteBytes.read(file, (number - 1) * pageSize, page, 0, pageSize);
        }
        cache.put(key, page);
        return page;
    }

    /// {@return the size of every page}
    int pageSize() {
        return pageSize;
    }

    /// {@return the bytes of each page that b-tree content may use}
    int usableSize() {
        return usableSize;
    }

    /// {@return the number of pages}
    int pageCount() {
        return pageCount;
    }

    /// {@return the text encoding}
    SqliteTextEncoding textEncoding() {
        return textEncoding;
    }

    /// {@return the schema format number, at least 1}
    int schemaFormat() {
        return schemaFormat;
    }

    /// {@return the user version}
    int userVersion() {
        return userVersion;
    }

    /// Closes the database file and the write-ahead log, and empties the cache; closing again has no effect.
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        cache.clear();
        try (file) {
            if (wal != null) {
                wal.close();
            }
        }
    }
}
