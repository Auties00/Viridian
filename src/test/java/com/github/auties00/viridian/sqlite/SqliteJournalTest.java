package com.github.auties00.viridian.sqlite;

import com.github.auties00.viridian.sqlite.SqliteTesting.Jdbc;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Checks that a hot rollback journal is played back in memory: copies of a database taken while a transaction has
/// spilled changed pages into the file read as the state before that transaction, as SQLite itself reads them, and
/// journals that a commit has emptied, or that belong to a committed multi-database transaction, are ignored.
@DisplayName("Rollback journal")
class SqliteJournalTest {
    @TempDir
    Path directory;

    /// Creates a rollback-mode database and starts a transaction that spills changed pages into the database file.
    private static Jdbc spilledTransaction(Path file, String synchronous) {
        var jdbc = Jdbc.open(file);
        jdbc.execute("PRAGMA page_size = 1024", "PRAGMA journal_mode = DELETE", "PRAGMA synchronous = " + synchronous,
                "CREATE TABLE t(id INTEGER PRIMARY KEY, a TEXT, b BLOB)", "CREATE INDEX t_by_a ON t(a)", "CREATE TABLE u(x, y)");
        var random = new Random(19);
        var rows = new ArrayList<Object[]>();
        for (var i = 0; i < 3000; i++) {
            rows.add(new Object[]{(long) i, SqliteTesting.randomText(random, random.nextInt(50)),
                    SqliteTesting.randomBytes(random, random.nextInt(i % 100 == 0 ? 4000 : 40))});
        }
        jdbc.batch("INSERT INTO t VALUES (?, ?, ?)", rows);
        jdbc.execute("INSERT INTO u SELECT a, id FROM t WHERE id % 5 = 0");
        jdbc.execute("PRAGMA cache_size = 10", "BEGIN");
        jdbc.update("UPDATE t SET a = 'changed ' || id, b = randomblob(300)");
        jdbc.update("DELETE FROM t WHERE id % 3 = 0");
        jdbc.update("INSERT INTO t(a, b) SELECT 'more', randomblob(900) FROM t LIMIT 800");
        jdbc.execute("DROP TABLE u");
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

    @ParameterizedTest(name = "synchronous = {0}")
    @ValueSource(strings = {"FULL", "NORMAL", "OFF"})
    @DisplayName("reads the state before an interrupted transaction")
    void hotJournal(String synchronous) throws IOException {
        var file = directory.resolve("hot.db");
        try (var jdbc = spilledTransaction(file, synchronous)) {
            var copy = SqliteTesting.copyDatabase(file, directory.resolve("ours"), false);
            var theirs = SqliteTesting.copyDatabase(file, directory.resolve("theirs"), false);
            jdbc.execute("ROLLBACK");
            assertTrue(Files.size(SqliteBytes.sibling(copy, "-journal")) > 10_000, "the transaction journaled nothing");
            var withoutJournal = directory.resolve("main-only").resolve("hot.db");
            Files.createDirectories(withoutJournal.getParent());
            Files.copy(copy, withoutJournal);
            assertNotEquals(outcome(copy), outcome(withoutJournal), "the transaction did not spill into the database file");
            try (var database = SqliteDatabase.open(copy)) {
                SqliteTesting.assertMatchesJdbc(database, jdbc);
            }
            try (var database = SqliteDatabase.open(copy); var reference = Jdbc.open(theirs)) {
                SqliteTesting.assertMatchesJdbc(database, reference);
            }
        }
    }

    @ParameterizedTest(name = "journal_mode = {0}")
    @ValueSource(strings = {"PERSIST", "TRUNCATE"})
    @DisplayName("ignores the journal a commit leaves behind")
    void committedJournal(String mode) throws IOException {
        var file = directory.resolve("left.db");
        try (var jdbc = Jdbc.open(file)) {
            jdbc.execute("PRAGMA journal_mode = " + mode, "CREATE TABLE t(id INTEGER PRIMARY KEY, a)");
            for (var i = 0; i < 50; i++) {
                jdbc.update("INSERT INTO t(a) VALUES (?)", "row " + i);
            }
            jdbc.update("UPDATE t SET a = 'updated' WHERE id < 10");
            assertTrue(Files.exists(SqliteBytes.sibling(file, "-journal")));
            var copy = SqliteTesting.copyDatabase(file, directory.resolve("copy"), false);
            try (var database = SqliteDatabase.open(copy)) {
                SqliteTesting.assertMatchesJdbc(database, jdbc);
            }
        }
    }

    @Test
    @DisplayName("ignores the journal of a committed multi-database transaction and plays back one still pending")
    void superJournal() throws IOException {
        var file = directory.resolve("super.db");
        try (var jdbc = spilledTransaction(file, "FULL")) {
            var committed = SqliteTesting.copyDatabase(file, directory.resolve("committed"), false);
            var pending = SqliteTesting.copyDatabase(file, directory.resolve("pending"), false);
            jdbc.execute("ROLLBACK");
            var withoutJournal = directory.resolve("main-only").resolve("super.db");
            Files.createDirectories(withoutJournal.getParent());
            Files.copy(committed, withoutJournal);
            var existing = Files.writeString(directory.resolve("super-journal"), "x");
            appendSuperJournal(SqliteBytes.sibling(committed, "-journal"), directory.resolve("gone-super-journal").toString());
            appendSuperJournal(SqliteBytes.sibling(pending, "-journal"), existing.toString());
            assertEquals(outcome(withoutJournal), outcome(committed));
            try (var database = SqliteDatabase.open(pending)) {
                SqliteTesting.assertMatchesJdbc(database, jdbc);
            }
        }
    }

    @Test
    @DisplayName("ignores a journal whose header is zeroed or whose magic is wrong")
    void invalidHeader() throws IOException {
        var file = directory.resolve("bad.db");
        try (var jdbc = spilledTransaction(file, "FULL")) {
            var zeroed = SqliteTesting.copyDatabase(file, directory.resolve("zeroed"), false);
            var wrongMagic = SqliteTesting.copyDatabase(file, directory.resolve("magic"), false);
            jdbc.execute("ROLLBACK");
            var withoutJournal = directory.resolve("main-only").resolve("bad.db");
            Files.createDirectories(withoutJournal.getParent());
            Files.copy(zeroed, withoutJournal);
            try (var channel = java.nio.channels.FileChannel.open(SqliteBytes.sibling(zeroed, "-journal"), StandardOpenOption.WRITE)) {
                channel.write(ByteBuffer.wrap(new byte[28]), 0);
            }
            try (var channel = java.nio.channels.FileChannel.open(SqliteBytes.sibling(wrongMagic, "-journal"), StandardOpenOption.WRITE)) {
                channel.write(ByteBuffer.wrap(new byte[]{1}), 3);
            }
            var expected = outcome(withoutJournal);
            assertEquals(expected, outcome(zeroed));
            assertEquals(expected, outcome(wrongMagic));
        }
    }

    /// Appends the record naming super-journal `name` to `journal`, as SQLite writes it for a multi-database commit.
    private static void appendSuperJournal(Path journal, String name) throws IOException {
        var bytes = name.getBytes(StandardCharsets.UTF_8);
        var checksum = 0;
        for (var value : bytes) {
            checksum += value;
        }
        var record = ByteBuffer.allocate(4 + bytes.length + 16)
                .putInt((int) (0x40000000L / 1024 + 1))
                .put(bytes)
                .putInt(bytes.length)
                .putInt(checksum)
                .put(new byte[]{(byte) 0xD9, (byte) 0xD5, 0x05, (byte) 0xF9, 0x20, (byte) 0xA1, 0x63, (byte) 0xD7});
        Files.write(journal, Arrays.copyOf(record.array(), record.position()), StandardOpenOption.APPEND);
    }
}
