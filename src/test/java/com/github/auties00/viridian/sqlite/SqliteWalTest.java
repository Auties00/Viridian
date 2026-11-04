package com.github.auties00.viridian.sqlite;

import com.github.auties00.viridian.sqlite.SqliteTesting.Jdbc;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Checks the write-ahead log replay against SQLite: copies of a database and its `-wal` taken while a connection
/// holds frames that were never checkpointed, mid-transaction, with frames appended or cut off, and rewritten with
/// big-endian checksums. The tests build and re-checksum log images with their own implementation of the checksum,
/// and confirm with SQLite that the crafted logs mean what the tests expect.
@DisplayName("Write-ahead log")
class SqliteWalTest {
    @TempDir
    Path directory;

    /// Opens a WAL-mode database at `file` that keeps every frame in the log, and fills it over several transactions.
    private static Jdbc walDatabase(Path file) {
        var jdbc = Jdbc.open(file);
        jdbc.execute("PRAGMA page_size = 1024", "PRAGMA journal_mode = WAL", "PRAGMA wal_autocheckpoint = 0",
                "CREATE TABLE t(id INTEGER PRIMARY KEY, a TEXT, b BLOB)", "CREATE INDEX t_by_a ON t(a)");
        var random = new Random(17);
        var rows = new ArrayList<Object[]>();
        for (var i = 0; i < 1500; i++) {
            rows.add(new Object[]{(long) i, SqliteTesting.randomText(random, random.nextInt(40)),
                    SqliteTesting.randomBytes(random, random.nextInt(i % 100 == 0 ? 3000 : 30))});
        }
        jdbc.batch("INSERT INTO t VALUES (?, ?, ?)", rows);
        for (var i = 0; i < 20; i++) {
            jdbc.update("UPDATE t SET a = ? WHERE id % 20 = ?", "round " + i, (long) i);
            jdbc.update("INSERT INTO t(a, b) VALUES (?, ?)", "added " + i, SqliteTesting.randomBytes(random, 2000));
            jdbc.update("DELETE FROM t WHERE id = ?", (long) i * 7);
        }
        return jdbc;
    }

    /// {@return the rows of table `t`, or the message of the exception reading them threw}
    private static Object outcome(Path file) {
        try (var database = SqliteDatabase.open(file)) {
            return SqliteTesting.read(database.findTable("t").orElseThrow().scan());
        } catch (IOException exception) {
            return exception.getMessage();
        }
    }

    @Test
    @DisplayName("reads frames that were never checkpointed")
    void uncheckpointed() throws IOException {
        var file = directory.resolve("live.db");
        try (var jdbc = walDatabase(file)) {
            var copy = SqliteTesting.copyDatabase(file, directory.resolve("copy"), false);
            assertTrue(Files.size(SqliteBytes.sibling(copy, "-wal")) > 100_000);
            try (var database = SqliteDatabase.open(copy)) {
                SqliteTesting.assertMatchesJdbc(database, jdbc);
            }
            try (var database = SqliteDatabase.open(file)) {
                SqliteTesting.assertMatchesJdbc(database, jdbc);
            }
            var mainOnly = directory.resolve("main-only").resolve("live.db");
            Files.createDirectories(mainOnly.getParent());
            Files.copy(file, mainOnly);
            try (var withLog = SqliteDatabase.open(copy); var withoutLog = SqliteDatabase.open(mainOnly)) {
                assertNotEquals(withLog.schema(), withoutLog.schema());
            }
        }
    }

    @Test
    @DisplayName("ignores the frames of a transaction that has not committed")
    void uncommitted() throws IOException {
        var file = directory.resolve("open.db");
        try (var jdbc = walDatabase(file)) {
            var committedSize = Files.size(SqliteBytes.sibling(file, "-wal"));
            jdbc.execute("PRAGMA cache_size = 10", "PRAGMA cache_spill = 10", "BEGIN");
            jdbc.update("UPDATE t SET a = 'uncommitted', b = randomblob(400)");
            jdbc.update("INSERT INTO t(a, b) SELECT 'more', randomblob(600) FROM t");
            var copy = SqliteTesting.copyDatabase(file, directory.resolve("copy"), false);
            jdbc.execute("ROLLBACK");
            // A spilled frame is rewritten in place when its page spills again, and SQLite repairs the checksums of
            // the frames after it only at commit, so the frames past the last commit are partly invalid.
            assertTrue(Files.size(SqliteBytes.sibling(copy, "-wal")) > committedSize + 100_000, "the transaction spilled no frames into the log");
            try (var database = SqliteDatabase.open(copy)) {
                SqliteTesting.assertMatchesJdbc(database, jdbc);
            }
        }
    }

    @Nested
    @DisplayName("Frames after the last commit")
    class Appended {
        @Test
        @DisplayName("ignores garbage appended to the log")
        void garbage() throws IOException {
            var file = directory.resolve("garbage.db");
            try (var jdbc = walDatabase(file)) {
                var copy = SqliteTesting.copyDatabase(file, directory.resolve("copy"), false);
                var wal = SqliteBytes.sibling(copy, "-wal");
                var garbage = SqliteTesting.randomBytes(new Random(1), 3 * (24 + 1024) + 100);
                Files.write(wal, garbage, java.nio.file.StandardOpenOption.APPEND);
                try (var database = SqliteDatabase.open(copy)) {
                    SqliteTesting.assertMatchesJdbc(database, jdbc);
                }
            }
        }

        @ParameterizedTest(name = "committed: {0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("applies a valid appended frame only once it commits")
        void validFrame(boolean committed) throws IOException {
            var file = directory.resolve("valid.db");
            try (var jdbc = walDatabase(file)) {
                var root = (Long) jdbc.value("SELECT rootpage FROM sqlite_schema WHERE name = 't'");
                var pages = (Long) jdbc.value("PRAGMA page_count");
                var copies = List.of(directory.resolve("ours"), directory.resolve("theirs"));
                for (var target : copies) {
                    var copy = SqliteTesting.copyDatabase(file, target, false);
                    var image = WalImage.read(SqliteBytes.sibling(copy, "-wal"));
                    image.append(root.intValue(), committed ? pages.intValue() : 0, new byte[1024]);
                    image.write(SqliteBytes.sibling(copy, "-wal"));
                }
                var ours = copies.getFirst().resolve("valid.db");
                var theirs = copies.getLast().resolve("valid.db");
                if (committed) {
                    try (var database = SqliteDatabase.open(ours)) {
                        var table = database.findTable("t").orElseThrow();
                        var failure = assertThrows(IOException.class, () -> SqliteTesting.read(table.scan()));
                        assertTrue(failure.getMessage().contains("page " + root), failure.getMessage());
                    }
                    try (var reference = Jdbc.open(theirs)) {
                        assertThrows(IllegalStateException.class, () -> reference.query("SELECT * FROM t"));
                    }
                } else {
                    try (var database = SqliteDatabase.open(ours); var reference = Jdbc.open(theirs)) {
                        SqliteTesting.assertMatchesJdbc(database, reference);
                        SqliteTesting.assertMatchesJdbc(database, jdbc);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("drops a frame cut off at the end of the log, and the transaction it would commit")
    void truncatedTail() throws IOException {
        var file = directory.resolve("cut.db");
        try (var jdbc = walDatabase(file)) {
            var rows = jdbc.value("SELECT count(*) FROM t");
            var copies = List.of(directory.resolve("ours"), directory.resolve("theirs"));
            for (var target : copies) {
                var copy = SqliteTesting.copyDatabase(file, target, false);
                var wal = SqliteBytes.sibling(copy, "-wal");
                try (var channel = java.nio.channels.FileChannel.open(wal, java.nio.file.StandardOpenOption.WRITE)) {
                    channel.truncate(channel.size() - 500);
                }
            }
            try (var database = SqliteDatabase.open(copies.getFirst().resolve("cut.db"));
                 var reference = Jdbc.open(copies.getLast().resolve("cut.db"))) {
                assertNotEquals(rows, reference.value("SELECT count(*) FROM t"), "the cut did not lose the last transaction");
                SqliteTesting.assertMatchesJdbc(database, reference);
            }
        }
    }

    @Test
    @DisplayName("reads a log whose checksums are big-endian")
    void bigEndian() throws IOException {
        var file = directory.resolve("order.db");
        try (var jdbc = walDatabase(file)) {
            var copies = List.of(directory.resolve("ours"), directory.resolve("theirs"));
            for (var target : copies) {
                var copy = SqliteTesting.copyDatabase(file, target, false);
                var image = WalImage.read(SqliteBytes.sibling(copy, "-wal"));
                assertEquals(false, image.bigEndian);
                image.rechecksum(true);
                image.write(SqliteBytes.sibling(copy, "-wal"));
            }
            try (var database = SqliteDatabase.open(copies.getFirst().resolve("order.db"));
                 var reference = Jdbc.open(copies.getLast().resolve("order.db"))) {
                SqliteTesting.assertMatchesJdbc(database, reference);
                SqliteTesting.assertMatchesJdbc(database, jdbc);
            }
        }
    }

    @Nested
    @DisplayName("Unusable logs")
    class Unusable {
        @Test
        @DisplayName("ignores an empty log and one whose header checksum fails")
        void ignored() throws IOException {
            var file = directory.resolve("plain.db");
            try (var jdbc = Jdbc.open(file)) {
                jdbc.execute("CREATE TABLE t(id INTEGER PRIMARY KEY, a TEXT)", "INSERT INTO t VALUES (1, 'one'), (2, 'two')");
            }
            var expected = outcome(file);
            Files.write(SqliteBytes.sibling(file, "-wal"), new byte[0]);
            assertEquals(expected, outcome(file));
            var walFile = directory.resolve("wal.db");
            try (var _ = walDatabase(walFile)) {
                var image = WalImage.read(SqliteBytes.sibling(walFile, "-wal"));
                image.bytes[24] ^= 1;
                image.write(SqliteBytes.sibling(file, "-wal"));
            }
            assertEquals(expected, outcome(file));
        }

        @Test
        @DisplayName("rejects a log of another page size or format version")
        void rejected() throws IOException {
            var file = directory.resolve("reject.db");
            try (var _ = walDatabase(file)) {
                var copy = SqliteTesting.copyDatabase(file, directory.resolve("copy"), false);
                var wal = SqliteBytes.sibling(copy, "-wal");
                var original = Files.readAllBytes(wal);
                var image = WalImage.read(wal);
                image.header().putInt(8, 2048);
                image.rechecksum(false);
                image.write(wal);
                var pageSize = assertThrows(IOException.class, () -> SqliteDatabase.open(copy));
                assertTrue(pageSize.getMessage().contains("2048"), pageSize.getMessage());
                Files.write(wal, original);
                image = WalImage.read(wal);
                image.header().putInt(4, 3007001);
                image.rechecksum(false);
                image.write(wal);
                var version = assertThrows(IOException.class, () -> SqliteDatabase.open(copy));
                assertTrue(version.getMessage().contains("3007001"), version.getMessage());
            }
        }
    }

    @Test
    @DisplayName("creates no file next to the database, -shm included")
    void createsNothing() throws IOException {
        var file = directory.resolve("quiet.db");
        try (var jdbc = walDatabase(file)) {
            var copy = SqliteTesting.copyDatabase(file, directory.resolve("copy"), false);
            List<Path> before;
            try (var files = Files.list(copy.getParent())) {
                before = files.sorted().toList();
            }
            try (var database = SqliteDatabase.open(copy)) {
                SqliteTesting.assertMatchesJdbc(database, jdbc);
            }
            try (Stream<Path> files = Files.list(copy.getParent())) {
                assertEquals(before, files.sorted().toList());
            }
            assertTrue(Files.notExists(SqliteBytes.sibling(copy, "-shm")));
        }
    }

    /// An editable image of a write-ahead log, with its own implementation of the checksum.
    static final class WalImage {
        /// A frame of the log.
        record Frame(int offset, int page, int commitSize) {
        }

        byte[] bytes;
        final int pageSize;
        boolean bigEndian;
        private final List<Frame> frames = new ArrayList<>();

        private WalImage(byte[] bytes) {
            this.bytes = bytes;
            var header = ByteBuffer.wrap(bytes);
            this.bigEndian = (header.getInt(0) & 1) == 1;
            this.pageSize = header.getInt(8);
            var sums = sum(0, 24, 0, 0);
            var salt1 = header.getInt(16);
            var salt2 = header.getInt(20);
            for (var offset = 32; offset + 24 + pageSize <= bytes.length; offset += 24 + pageSize) {
                if (header.getInt(offset + 8) != salt1 || header.getInt(offset + 12) != salt2) {
                    break;
                }
                sums = sum(offset, 8, sums[0], sums[1]);
                sums = sum(offset + 24, pageSize, sums[0], sums[1]);
                if (sums[0] != header.getInt(offset + 16) || sums[1] != header.getInt(offset + 20)) {
                    break;
                }
                frames.add(new Frame(offset, header.getInt(offset), header.getInt(offset + 4)));
            }
        }

        static WalImage read(Path file) throws IOException {
            return new WalImage(Files.readAllBytes(file));
        }

        List<Frame> frames() {
            return frames;
        }

        ByteBuffer header() {
            return ByteBuffer.wrap(bytes);
        }

        /// Recomputes every checksum of the valid frames in the given byte order, dropping the frames after them.
        void rechecksum(boolean bigEndian) {
            this.bigEndian = bigEndian;
            var end = frames.isEmpty() ? 32 : frames.getLast().offset() + 24 + pageSize;
            bytes = java.util.Arrays.copyOf(bytes, end);
            var buffer = ByteBuffer.wrap(bytes);
            buffer.putInt(0, 0x377F0682 | (bigEndian ? 1 : 0));
            var sums = sum(0, 24, 0, 0);
            buffer.putInt(24, sums[0]).putInt(28, sums[1]);
            for (var frame : frames) {
                sums = sum(frame.offset(), 8, sums[0], sums[1]);
                sums = sum(frame.offset() + 24, pageSize, sums[0], sums[1]);
                buffer.putInt(frame.offset() + 16, sums[0]).putInt(frame.offset() + 20, sums[1]);
            }
        }

        /// Appends a frame with a valid checksum after the valid frames.
        void append(int page, int commitSize, byte[] data) {
            rechecksum(bigEndian);
            var offset = bytes.length;
            var sums = frames.isEmpty() ? new int[]{header().getInt(24), header().getInt(28)}
                    : new int[]{header().getInt(frames.getLast().offset() + 16), header().getInt(frames.getLast().offset() + 20)};
            bytes = java.util.Arrays.copyOf(bytes, offset + 24 + pageSize);
            var buffer = ByteBuffer.wrap(bytes);
            buffer.putInt(offset, page).putInt(offset + 4, commitSize).putInt(offset + 8, buffer.getInt(16)).putInt(offset + 12, buffer.getInt(20));
            System.arraycopy(data, 0, bytes, offset + 24, pageSize);
            sums = sum(offset, 8, sums[0], sums[1]);
            sums = sum(offset + 24, pageSize, sums[0], sums[1]);
            buffer.putInt(offset + 16, sums[0]).putInt(offset + 20, sums[1]);
            frames.add(new Frame(offset, page, commitSize));
        }

        void write(Path file) throws IOException {
            Files.write(file, bytes);
        }

        private int[] sum(int offset, int length, int first, int second) {
            var words = ByteBuffer.wrap(bytes, offset, length).order(bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
            while (words.hasRemaining()) {
                first += words.getInt() + second;
                second += words.getInt() + first;
            }
            return new int[]{first, second};
        }
    }
}
