package com.github.auties00.viridian.sqlite;

import com.github.auties00.viridian.sqlite.SqliteTesting.Jdbc;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Corrupts valid databases, their write-ahead logs and their hot journals at random, with a fixed seed per case,
/// and checks that reading everything in them either succeeds or throws an [IOException], within a time bound;
/// pages served from the log are re-checksummed after corruption so that they reach the reader. A few hand-made
/// hostile structures, loops and out-of-range pointers, check the specific messages.
@DisplayName("Corrupted files")
class SqliteFuzzTest {
    @TempDir
    static Path directory;

    /// The files of a valid database to corrupt.
    record Base(String name, byte[] database, byte[] wal, byte[] journal) {
    }

    private static final List<Base> bases = new ArrayList<>();

    @BeforeAll
    static void createBases() throws IOException {
        bases.add(plain("plain", "UTF-8", 512, false));
        bases.add(plain("vacuum", "UTF-16be", 512, true));
        var walFile = directory.resolve("base-wal").resolve("wal.db");
        Files.createDirectories(walFile.getParent());
        try (var jdbc = Jdbc.open(walFile)) {
            jdbc.execute("PRAGMA page_size = 1024", "PRAGMA encoding = 'UTF-16le'", "PRAGMA journal_mode = WAL", "PRAGMA wal_autocheckpoint = 0");
            fill(jdbc, 21);
            bases.add(new Base("wal", Files.readAllBytes(walFile), Files.readAllBytes(SqliteBytes.sibling(walFile, "-wal")), null));
        }
        var hotFile = directory.resolve("base-hot").resolve("hot.db");
        Files.createDirectories(hotFile.getParent());
        try (var jdbc = Jdbc.open(hotFile)) {
            jdbc.execute("PRAGMA page_size = 512");
            fill(jdbc, 23);
            jdbc.execute("PRAGMA cache_size = 10", "BEGIN", "UPDATE t SET a = 'changed', b = randomblob(200)", "DELETE FROM w WHERE x % 2 = 0");
            bases.add(new Base("journal", Files.readAllBytes(hotFile), null, Files.readAllBytes(SqliteBytes.sibling(hotFile, "-journal"))));
            jdbc.execute("ROLLBACK");
        }
    }

    /// {@return a closed rollback-mode database filled with every structure the reader walks}
    private static Base plain(String name, String encoding, int pageSize, boolean vacuum) throws IOException {
        var file = directory.resolve("base-" + name).resolve(name + ".db");
        Files.createDirectories(file.getParent());
        try (var jdbc = Jdbc.open(file)) {
            jdbc.execute("PRAGMA page_size = " + pageSize, "PRAGMA encoding = '" + encoding + "'", "PRAGMA auto_vacuum = " + (vacuum ? "FULL" : "NONE"));
            fill(jdbc, name.hashCode());
            jdbc.execute("DELETE FROM t WHERE id % 4 = 0");
        }
        return new Base(name, Files.readAllBytes(file), null, null);
    }

    /// Creates and fills the tables and indexes of a base database.
    private static void fill(Jdbc jdbc, long seed) {
        jdbc.execute("CREATE TABLE t(id INTEGER PRIMARY KEY, a TEXT, b BLOB, c)",
                "CREATE INDEX t_by_a ON t(a, c)",
                "CREATE INDEX t_partial ON t(c) WHERE b IS NULL",
                "CREATE TABLE w(x INTEGER, y TEXT, z, PRIMARY KEY(y, x)) WITHOUT ROWID",
                "CREATE INDEX w_by_z ON w(z)",
                "CREATE TABLE plain(p, q DEFAULT 'q', r REAL)");
        var random = new Random(seed);
        var special = SqliteTesting.specialValues();
        var t = new ArrayList<Object[]>();
        var w = new ArrayList<Object[]>();
        var plain = new ArrayList<Object[]>();
        for (var i = 0; i < 400; i++) {
            var value = special.get(i % special.size());
            if (value instanceof byte[] bytes && bytes.length > 5000) {
                value = Arrays.copyOf(bytes, 3000);
            } else if (value instanceof String text && text.length() > 5000) {
                value = text.substring(0, 2000);
            }
            t.add(new Object[]{(long) i * 3, SqliteTesting.randomText(random, random.nextInt(i % 37 == 0 ? 900 : 30)),
                    random.nextInt(3) == 0 ? null : SqliteTesting.randomBytes(random, random.nextInt(i % 41 == 0 ? 2500 : 20)), value});
            w.add(new Object[]{(long) i, SqliteTesting.randomText(random, 1 + random.nextInt(8)), value});
            plain.add(new Object[]{value, "q" + i, random.nextDouble()});
        }
        jdbc.batch("INSERT INTO t VALUES (?, ?, ?, ?)", t);
        jdbc.batch("INSERT INTO w VALUES (?, ?, ?)", w);
        jdbc.batch("INSERT INTO plain VALUES (?, ?, ?)", plain);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"plain", "vacuum", "wal", "journal"})
    @DisplayName("read a randomly corrupted database or fail with an IOException")
    void randomCorruption(String name) throws IOException {
        var base = bases.stream().filter(candidate -> candidate.name().equals(name)).findFirst().orElseThrow();
        var file = directory.resolve("case-" + name).resolve("case.db");
        Files.createDirectories(file.getParent());
        for (var seed = 0; seed < 500; seed++) {
            var random = new Random(seed * 31L + name.hashCode());
            Files.write(file, corrupt(base.database(), random, base.wal() == null && base.journal() == null || random.nextBoolean()));
            if (base.wal() != null) {
                writeWal(SqliteBytes.sibling(file, "-wal"), base.wal(), random);
            }
            if (base.journal() != null) {
                Files.write(SqliteBytes.sibling(file, "-journal"), corrupt(base.journal(), random, random.nextBoolean()));
            }
            var label = name + " seed " + seed;
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> exercise(file, random), label);
        }
    }

    /// {@return `data` with random corruption, or unchanged if `corrupt` is not set}
    ///
    /// Corruption flips bits, writes plausible and implausible 16- and 32-bit values where page numbers, cell
    /// counts and cell offsets live, and sometimes truncates the file.
    private static byte[] corrupt(byte[] data, Random random, boolean corrupt) {
        var result = data.clone();
        if (!corrupt) {
            return result;
        }
        var edits = 1 + random.nextInt(8);
        var pageCount = Math.max(1, result.length / 512);
        for (var i = 0; i < edits; i++) {
            var offset = random.nextInt(4) == 0 ? random.nextInt(Math.min(result.length, 4096)) : random.nextInt(result.length);
            switch (random.nextInt(4)) {
                case 0 -> result[offset] ^= (byte) (1 << random.nextInt(8));
                case 1 -> result[offset] = (byte) random.nextInt(256);
                case 2 -> {
                    var value = switch (random.nextInt(6)) {
                        case 0 -> 0;
                        case 1 -> 1;
                        case 2 -> 0xFFFF;
                        case 3 -> random.nextInt(pageCount * 2 + 2);
                        default -> random.nextInt(65536);
                    };
                    if (offset + 2 <= result.length) {
                        result[offset] = (byte) (value >> 8);
                        result[offset + 1] = (byte) value;
                    }
                }
                default -> {
                    var value = switch (random.nextInt(6)) {
                        case 0 -> 0;
                        case 1 -> -1;
                        case 2 -> Integer.MAX_VALUE;
                        case 3 -> pageCount + 1;
                        default -> 1 + random.nextInt(pageCount * 2 + 2);
                    };
                    if (offset + 4 <= result.length) {
                        ByteBuffer.wrap(result).putInt(offset, value);
                    }
                }
            }
        }
        if (random.nextInt(10) == 0) {
            result = Arrays.copyOf(result, random.nextInt(result.length + 1));
        }
        return result;
    }

    /// Writes a corrupted copy of the log `wal`, usually with its checksums recomputed so that the corrupted pages
    /// count as committed.
    private static void writeWal(Path file, byte[] wal, Random random) throws IOException {
        Files.write(file, wal);
        var image = SqliteWalTest.WalImage.read(file);
        var corrupted = corrupt(Arrays.copyOfRange(image.bytes, 32, image.bytes.length), random, true);
        var bytes = Arrays.copyOf(image.bytes, 32 + corrupted.length);
        System.arraycopy(corrupted, 0, bytes, 32, corrupted.length);
        Files.write(file, bytes);
        if (random.nextInt(4) != 0) {
            var frames = image.frames().size();
            image.bytes = bytes;
            image.rechecksum(false);
            image.write(file);
            assertTrue(frames > 0);
        }
    }

    /// Reads every part of the database at `file`, accepting an [IOException] from any of them.
    private static void exercise(Path file, Random random) {
        try (var database = SqliteDatabase.open(file)) {
            database.pageSize();
            database.textEncoding();
            for (var entry : database.schema()) {
                try {
                    if (entry.type().equals("table")) {
                        var table = database.findTable(entry.name());
                        if (table.isPresent()) {
                            exercise(table.get(), random);
                        }
                    } else if (entry.type().equals("index")) {
                        var index = database.findIndex(entry.name());
                        if (index.isPresent()) {
                            exercise(index.get(), random);
                        }
                    }
                } catch (IOException expected) {
                    // Each entry fails on its own; the next one is read regardless.
                }
            }
            exercise(database.findTable("sqlite_master").orElseThrow(), random);
        } catch (IOException expected) {
            // A corrupted file may fail anywhere, as long as it fails this way.
        }
    }

    /// Scans `table`, reads every value, and looks rows up and seeks into it.
    private static void exercise(SqliteTable table, Random random) throws IOException {
        table.columns();
        var rowids = new ArrayList<Long>();
        var keys = new ArrayList<Object>();
        try (var cursor = table.scan()) {
            while (cursor.next()) {
                var row = cursor.row();
                touch(row);
                if (!table.withoutRowid()) {
                    rowids.add(row.rowid());
                }
                if (row.size() > 0 && keys.size() < 20) {
                    keys.add(row.get(0));
                }
            }
        }
        if (table.withoutRowid()) {
            for (var key : keys) {
                drain(table.seek(Arrays.asList(key)));
            }
        } else {
            rowids.add(random.nextLong());
            for (var i = 0; i < Math.min(rowids.size(), 20); i++) {
                var rowid = rowids.get(random.nextInt(rowids.size()));
                table.findByRowid(rowid).ifPresent(SqliteFuzzTest::touch);
                drain(table.seek(List.of(rowid)));
            }
        }
    }

    /// Scans `index`, reads every value, and seeks into it.
    private static void exercise(SqliteIndex index, Random random) throws IOException {
        index.columns();
        var keys = new ArrayList<Object>();
        try (var cursor = index.scan()) {
            while (cursor.next()) {
                touch(cursor.row());
                if (keys.size() < 20) {
                    keys.add(cursor.row().get(0));
                }
            }
        }
        keys.add(random.nextLong());
        keys.add(null);
        for (var key : keys) {
            drain(index.seek(Arrays.asList(key)));
        }
    }

    /// Reads every value of `row`.
    private static void touch(SqliteRow row) {
        for (var i = 0; i < row.size(); i++) {
            row.isNull(i);
            row.get(i);
        }
        row.toString();
    }

    /// Reads up to 50 rows of `cursor`, closing it.
    private static void drain(SqliteCursor cursor) throws IOException {
        try (cursor) {
            for (var i = 0; i < 50 && cursor.next(); i++) {
                touch(cursor.row());
            }
        }
    }

    @Nested
    @DisplayName("Hostile structures")
    class Hostile {
        /// Creates a database whose table `t` has a three-level tree and one row with a long overflow chain.
        private Path create() {
            var file = directory.resolve("hostile-" + System.nanoTime() + ".db");
            try (var jdbc = Jdbc.open(file)) {
                jdbc.execute("PRAGMA page_size = 512", "CREATE TABLE big(b BLOB)", "INSERT INTO big VALUES (randomblob(4000))",
                        "CREATE TABLE t(id INTEGER PRIMARY KEY, a TEXT)");
                var rows = new ArrayList<Object[]>();
                for (var i = 0; i < 3000; i++) {
                    rows.add(new Object[]{(long) i, "row number " + i});
                }
                jdbc.batch("INSERT INTO t VALUES (?, ?)", rows);
            }
            return file;
        }

        private static void writeInt(Path file, long offset, int value) throws IOException {
            try (var channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
                channel.write(ByteBuffer.allocate(4).putInt(0, value), offset);
            }
        }

        private static long root(Path file, String table) throws IOException {
            try (var database = SqliteDatabase.open(file)) {
                return database.schema().stream().filter(entry -> entry.name().equals(table)).findFirst().orElseThrow().rootPage();
            }
        }

        @Test
        @DisplayName("a b-tree whose rightmost child is its own root")
        void cycle() throws IOException {
            var file = create();
            var root = root(file, "t");
            writeInt(file, (root - 1) * 512 + 8, (int) root);
            try (var database = SqliteDatabase.open(file)) {
                var table = database.findTable("t").orElseThrow();
                var scan = assertThrows(IOException.class, () -> SqliteTesting.read(table.scan()));
                assertTrue(scan.getMessage().contains("twice"), scan.getMessage());
                var lookup = assertThrows(IOException.class, () -> table.findByRowid(Long.MAX_VALUE));
                assertTrue(lookup.getMessage().contains("loops"), lookup.getMessage());
            }
        }

        @Test
        @DisplayName("a child pointer past the last page")
        void outOfRange() throws IOException {
            var file = create();
            var root = root(file, "t");
            writeInt(file, (root - 1) * 512 + 8, 1_000_000);
            try (var database = SqliteDatabase.open(file)) {
                var table = database.findTable("t").orElseThrow();
                var failure = assertThrows(IOException.class, () -> table.findByRowid(Long.MAX_VALUE));
                assertTrue(failure.getMessage().contains("1000000"), failure.getMessage());
            }
        }

        @Test
        @DisplayName("an overflow chain that loops back on itself")
        void overflowCycle() throws IOException {
            var file = create();
            var root = root(file, "big");
            writeInt(file, root * 512, (int) root + 1);
            try (var database = SqliteDatabase.open(file)) {
                var table = database.findTable("big").orElseThrow();
                var failure = assertThrows(IOException.class, () -> SqliteTesting.read(table.scan()));
                assertTrue(failure.getMessage().contains("overflow chain"), failure.getMessage());
            }
        }

        @Test
        @DisplayName("a header that claims more pages than the file holds")
        void headerSize() throws IOException {
            var file = create();
            writeInt(file, 28, 1 << 20);
            writeInt(file, 24, 7);
            writeInt(file, 92, 7);
            var failure = assertThrows(IOException.class, () -> SqliteDatabase.open(file));
            assertTrue(failure.getMessage().contains("1048576"), failure.getMessage());
        }

        @Test
        @DisplayName("a file that is not a database at all")
        void notADatabase() throws IOException {
            var file = Files.write(directory.resolve("random.db"), SqliteTesting.randomBytes(new Random(2), 5000));
            var failure = assertThrows(IOException.class, () -> SqliteDatabase.open(file));
            assertTrue(failure.getMessage().contains("not a SQLite database"), failure.getMessage());
        }
    }
}
