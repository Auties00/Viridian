package com.github.auties00.viridian.sqlite;

import com.github.auties00.viridian.sqlite.SqliteTesting.Jdbc;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Checks the reader against SQLite on the table and index shapes a schema can declare, and on the page layouts that
/// deletion, auto-vacuum, reserved bytes and large row counts produce.
@DisplayName("Schema shapes and page layouts")
class SqliteShapesTest {
    @TempDir
    Path directory;

    /// Creates a database with `statements`, then checks every table and index against SQLite.
    private Path assertShape(String... statements) throws IOException {
        var file = directory.resolve("shape.db");
        try (var jdbc = Jdbc.open(file)) {
            jdbc.execute(statements);
        }
        try (var database = SqliteDatabase.open(file); var jdbc = Jdbc.open(file)) {
            SqliteTesting.assertMatchesJdbc(database, jdbc);
        }
        return file;
    }

    @Nested
    @DisplayName("Rowid aliases")
    class RowidAliases {
        @ParameterizedTest(name = "{0} -> alias {1}")
        @CsvSource(delimiter = '|', value = {
                "id INTEGER PRIMARY KEY, v|true",
                "id integer primary key, v|true",
                "id INTEGER PRIMARY KEY ASC, v|true",
                "id INTEGER PRIMARY KEY DESC, v|false",
                "id INT PRIMARY KEY, v|false",
                "id BIGINT PRIMARY KEY, v|false",
                "id INTEGER PRIMARY KEY AUTOINCREMENT, v|true",
                "id INTEGER CONSTRAINT named PRIMARY KEY ON CONFLICT REPLACE, v|true",
                "id \"INTEGER\" PRIMARY KEY, v|true",
                "id INTEGER, v, PRIMARY KEY(id)|true",
                "id INTEGER, v, PRIMARY KEY(id DESC)|true",
                "id INTEGER, v, PRIMARY KEY(id, v)|false",
                "id, v|false",
        })
        @DisplayName("follow SQLite's INTEGER PRIMARY KEY rule")
        void rule(String columns, boolean alias) throws IOException {
            var file = directory.resolve("alias.db");
            try (var jdbc = Jdbc.open(file)) {
                jdbc.execute("CREATE TABLE t(" + columns + ")");
                jdbc.update("INSERT INTO t(id, v) VALUES (100, 'a')");
                jdbc.update("INSERT INTO t(id, v) VALUES (-5, 'b')");
                assertEquals(alias, jdbc.query("SELECT _rowid_ = id FROM t").stream().allMatch(row -> row.getFirst().equals(1L)));
            }
            try (var database = SqliteDatabase.open(file); var jdbc = Jdbc.open(file)) {
                var table = database.findTable("t").orElseThrow();
                assertEquals(alias, table.columns().getFirst().rowidAlias());
                assertFalse(table.columns().getLast().rowidAlias());
                SqliteTesting.assertMatchesJdbc(database, jdbc);
                var row = table.findByRowid(alias ? 100 : 1).orElseThrow();
                assertEquals(100L, row.getLong(0));
            }
        }
    }

    @Nested
    @DisplayName("WITHOUT ROWID tables")
    class WithoutRowid {
        @Test
        @DisplayName("map a composite key declared out of column order")
        void compositeKey() throws IOException {
            var file = directory.resolve("w.db");
            try (var jdbc = Jdbc.open(file)) {
                jdbc.execute(
                        "CREATE TABLE w(a, b TEXT, c INTEGER, d REAL, PRIMARY KEY(c, a)) WITHOUT ROWID",
                        "CREATE TABLE dup(a, b, c, PRIMARY KEY(c, a, c)) WITHOUT ROWID",
                        "CREATE TABLE single(k TEXT PRIMARY KEY, v) WITHOUT ROWID",
                        "CREATE TABLE desc_key(a, b, c, PRIMARY KEY(a DESC, b)) WITHOUT ROWID",
                        "CREATE TABLE late(x, y, z, PRIMARY KEY(z, y)) WITHOUT ROWID",
                        "CREATE INDEX late_by_x ON late(x)",
                        "CREATE INDEX late_by_yx ON late(y, x)",
                        "CREATE INDEX w_by_d ON w(d, a)");
                var random = new Random(3);
                var rows = new ArrayList<Object[]>();
                var dup = new ArrayList<Object[]>();
                var single = new ArrayList<Object[]>();
                var late = new ArrayList<Object[]>();
                for (var i = 0; i < 1500; i++) {
                    rows.add(new Object[]{SqliteTesting.randomText(random, 6) + i, "b" + i, (long) random.nextInt(40), random.nextInt(5) == 0 ? (Object) 4.0 : random.nextDouble()});
                    dup.add(new Object[]{(long) i, SqliteTesting.randomBytes(random, 8), (long) (i % 7)});
                    single.add(new Object[]{"key" + i, i % 3 == 0 ? null : (long) i});
                    late.add(new Object[]{SqliteTesting.randomText(random, 3), (long) random.nextInt(1000), "z" + i});
                }
                jdbc.batch("INSERT INTO w VALUES (?, ?, ?, ?)", rows);
                jdbc.batch("INSERT INTO dup VALUES (?, ?, ?)", dup);
                jdbc.batch("INSERT INTO single VALUES (?, ?)", single);
                jdbc.execute("INSERT INTO desc_key SELECT c, a, d FROM w");
                jdbc.batch("INSERT INTO late VALUES (?, ?, ?)", late);
            }
            try (var database = SqliteDatabase.open(file); var jdbc = Jdbc.open(file)) {
                SqliteTesting.assertMatchesJdbc(database, jdbc);
                var table = database.findTable("w").orElseThrow();
                assertTrue(table.withoutRowid());
                assertThrows(IllegalStateException.class, () -> table.findByRowid(1));
                try (var cursor = table.scan()) {
                    assertTrue(cursor.next());
                    assertThrows(IllegalStateException.class, () -> cursor.row().rowid());
                }
            }
        }
    }

    @Nested
    @DisplayName("ALTER TABLE ADD COLUMN")
    class AddColumn {
        @Test
        @DisplayName("reads the declared default for rows written before the column existed")
        void defaults() throws IOException {
            var file = directory.resolve("alter.db");
            try (var jdbc = Jdbc.open(file)) {
                jdbc.execute("CREATE TABLE t(id INTEGER PRIMARY KEY, a)", "CREATE TABLE w(k PRIMARY KEY, a) WITHOUT ROWID");
                for (var i = 0; i < 300; i++) {
                    jdbc.update("INSERT INTO t(a) VALUES (?)", (long) i);
                    jdbc.update("INSERT INTO w VALUES (?, ?)", (long) i, "v" + i);
                }
                var columns = List.of(
                        "c1 DEFAULT 5", "c2 TEXT DEFAULT 'abc'", "c3 REAL DEFAULT 3", "c4 INTEGER DEFAULT '12'",
                        "c5 TEXT DEFAULT 1.50", "c6 DEFAULT -7", "c7 BLOB DEFAULT x'00ff'", "c8 DEFAULT NULL", "c9 DEFAULT TRUE",
                        "c10 NUMERIC DEFAULT '1e3'", "c11 DEFAULT (-(-5))", "c12 TEXT DEFAULT 007", "c13 DEFAULT 1.0",
                        "c14 INTEGER DEFAULT 2.5", "c15 TEXT DEFAULT 12345678901", "c16 REAL DEFAULT '2'", "c17 DEFAULT ('paren')",
                        "c18 VARCHAR(5) DEFAULT -2.5e2", "c19 DEFAULT FALSE", "c20 INT DEFAULT ' 42 '", "c21 DEFAULT \"ident\"",
                        "c22 TEXT DEFAULT -2147483648", "c23 DEFAULT +3", "c24 REAL", "c25 TEXT DEFAULT (- '4')");
                for (var column : columns) {
                    jdbc.execute("ALTER TABLE t ADD COLUMN " + column, "ALTER TABLE w ADD COLUMN " + column);
                }
                for (var i = 0; i < 50; i++) {
                    jdbc.update("INSERT INTO t(a, c1, c24) VALUES (?, ?, ?)", (long) -i, "set", 1.0 * i);
                    jdbc.update("INSERT INTO w(k, a, c2) VALUES (?, ?, ?)", "x" + i, (long) i, "set");
                }
            }
            try (var database = SqliteDatabase.open(file); var jdbc = Jdbc.open(file)) {
                SqliteTesting.assertMatchesJdbc(database, jdbc);
            }
        }

        @Test
        @DisplayName("converts every kind of literal default by every affinity as SQLite does")
        void affinityMatrix() throws IOException {
            var literals = List.of("1.0", "1e3", "2.5", "'1.0'", "'5'", "5", "-1.0", "(1.0)", "0x10", "'0x10'", "1.0e0", "TRUE",
                    "'abc'", "9223372036854775807", "9223372036854775808", "-9223372036854775808", "1e999", "' 7 '", "'1e3'",
                    "-'4'", "007", "(-(-1.0))", "(-(1.0))", "(-(-5))", "(- '4')", "x''", "NULL", "1_000");
            var file = directory.resolve("matrix.db");
            try (var jdbc = Jdbc.open(file)) {
                jdbc.execute("CREATE TABLE t(a)", "INSERT INTO t VALUES (1), (2)");
                var column = 0;
                for (var type : List.of("", "TEXT", "NUMERIC", "INTEGER", "REAL", "VARCHAR(10)", "DOUBLE PRECISION", "BLOB")) {
                    for (var literal : literals) {
                        jdbc.execute("ALTER TABLE t ADD COLUMN c" + column++ + " " + type + " DEFAULT " + literal);
                    }
                }
            }
            try (var database = SqliteDatabase.open(file); var jdbc = Jdbc.open(file)) {
                SqliteTesting.assertMatchesJdbc(database, jdbc);
            }
        }
    }

    @Nested
    @DisplayName("Generated columns")
    class GeneratedColumns {
        @Test
        @DisplayName("read STORED columns from their record field")
        void stored() throws IOException {
            assertShape(
                    "CREATE TABLE g(a INTEGER, b INTEGER GENERATED ALWAYS AS (a * 2) STORED, c TEXT AS (a || 'x') STORED, d)",
                    "INSERT INTO g(a, d) VALUES (1, 'one'), (2, NULL), (NULL, x'01')",
                    "CREATE INDEX g_by_b ON g(b)");
        }

        @Test
        @DisplayName("refuse to read a table with a VIRTUAL column, but read an index on it")
        void virtual() throws IOException {
            var file = assertShape(
                    "CREATE TABLE v(a INTEGER, b AS (a + 1), c GENERATED ALWAYS AS (a * 3) VIRTUAL, d)",
                    "INSERT INTO v(a, d) VALUES (1, 'one'), (2, NULL), (5, 'five')",
                    "CREATE INDEX v_by_b ON v(b)");
            try (var database = SqliteDatabase.open(file)) {
                var table = database.findTable("v").orElseThrow();
                assertEquals(4, table.columns().size());
                var failure = assertThrows(IOException.class, () -> table.findByRowid(1));
                assertTrue(failure.getMessage().contains("VIRTUAL"), failure.getMessage());
            }
        }
    }

    @Nested
    @DisplayName("Constraint indexes")
    class ConstraintIndexes {
        @Test
        @DisplayName("derive the columns of the sqlite_autoindex indexes from the constraints")
        void autoIndexes() throws IOException {
            var file = assertShape(
                    "CREATE TABLE u(a TEXT UNIQUE, b INT, c, UNIQUE(b, c), PRIMARY KEY(c, b), UNIQUE(c, b))",
                    "CREATE TABLE meta(key LONGVARCHAR NOT NULL UNIQUE PRIMARY KEY, value LONGVARCHAR)",
                    "CREATE TABLE ipk(id INTEGER PRIMARY KEY DESC, x UNIQUE COLLATE NOCASE, y UNIQUE)",
                    "CREATE TABLE wr(id INTEGER PRIMARY KEY, x UNIQUE, y, UNIQUE(y, x)) WITHOUT ROWID",
                    "CREATE TABLE wt(x UNIQUE, id TEXT PRIMARY KEY, y UNIQUE) WITHOUT ROWID",
                    "INSERT INTO u VALUES ('p', 1, 'q'), ('r', 2, NULL), (NULL, 3, 'q')",
                    "INSERT INTO meta VALUES ('version', '7'), ('last_compatible_version', '5')",
                    "INSERT INTO ipk VALUES (3, 'A', 1), (1, 'b', 2), (2, 'C', NULL)",
                    "INSERT INTO wr VALUES (5, 'x5', 'y'), (2, 'x2', 'y'), (9, NULL, NULL)",
                    "INSERT INTO wt VALUES ('m', 'n', 1), ('o', 'a', 2)");
            try (var database = SqliteDatabase.open(file)) {
                var autoIndexes = database.schema().stream().filter(entry -> entry.type().equals("index") && entry.sql() == null).count();
                assertEquals(11, autoIndexes);
            }
        }
    }

    @Nested
    @DisplayName("Statement syntax")
    class Syntax {
        @Test
        @DisplayName("parses quoted identifiers, comments and every kind of constraint")
        void odd() throws IOException {
            var file = assertShape(
                    "CREATE TABLE other(id INTEGER PRIMARY KEY, name TEXT)",
                    """
                    CREATE TABLE IF NOT EXISTS "odd ""name"" table" ( -- a comment, with a ) in it
                        [first col] "VARCHAR"(10) NOT NULL DEFAULT 'x', /* a block ( comment */
                        `second` 'text' COLLATE NOCASE,
                        'third' DOUBLE PRECISION CHECK (third > 0) REFERENCES other(id) ON DELETE SET NULL ON UPDATE NO ACTION DEFERRABLE INITIALLY DEFERRED,
                        fourth UNSIGNED BIG INT CONSTRAINT nn NOT NULL ON CONFLICT REPLACE,
                        "key" BLOB UNIQUE,
                        fifth NUMERIC(10, 2) NULL,
                        sixth generated,
                        CONSTRAINT pk PRIMARY KEY ([first col], fourth) ON CONFLICT ABORT,
                        CHECK (fourth >= 0)
                        FOREIGN KEY (second) REFERENCES other(name) MATCH FULL NOT DEFERRABLE
                    )""",
                    "CREATE INDEX \"odd index\" ON \"odd \"\"name\"\" table\" (\"second\" COLLATE BINARY DESC, `third`)",
                    "CREATE TABLE \"CaseTest\"(\"MiXeD\" TEXT, other_col)",
                    "INSERT INTO \"odd \"\"name\"\" table\" VALUES ('a', 'B', 1.5, 2, x'01', 3.25, 'g'), ('c', 'd', 2, 1, NULL, 4, NULL)",
                    "INSERT INTO CaseTest VALUES ('x', 1)");
            try (var database = SqliteDatabase.open(file)) {
                var table = database.findTable("ODD \"NAME\" TABLE").orElseThrow();
                assertEquals(1, table.columnIndex("SECOND"));
                assertThrows(IllegalArgumentException.class, () -> table.columnIndex("missing"));
                assertTrue(database.findTable("casetest").isPresent());
                assertTrue(database.findTable("missing").isEmpty());
            }
        }

        @Test
        @DisplayName("reads STRICT tables")
        void strict() throws IOException {
            assertShape(
                    "CREATE TABLE s(a INT, b TEXT, c REAL, d BLOB, e ANY, f INTEGER PRIMARY KEY) STRICT",
                    "INSERT INTO s VALUES (1, 'b', 2.0, x'ff', 'any', 10), (NULL, NULL, 3, NULL, 7, 11), (2, 'c', 4.5, x'', 2.5, 12)",
                    "ALTER TABLE s ADD COLUMN g REAL DEFAULT 3");
        }

        @Test
        @DisplayName("scans indexes it cannot seek: collated, descending, expression and partial")
        void unseekableIndexes() throws IOException {
            var file = assertShape(
                    "CREATE TABLE t(a INTEGER, b TEXT COLLATE NOCASE, c REAL)",
                    "INSERT INTO t VALUES (1, 'Bee', 1.5), (2, 'ant', NULL), (3, 'CAT', -2), (4, NULL, 4), (5, 'bee', 8)",
                    "CREATE INDEX t_nocase ON t(b)",
                    "CREATE INDEX t_binary ON t(b COLLATE BINARY)",
                    "CREATE INDEX t_desc ON t(c DESC, a)",
                    "CREATE INDEX t_expr ON t(lower(b), c + 1)",
                    "CREATE INDEX t_partial ON t(a) WHERE c > 0");
            try (var database = SqliteDatabase.open(file)) {
                for (var name : List.of("t_nocase", "t_desc", "t_expr")) {
                    var index = database.findIndex(name).orElseThrow();
                    var failure = assertThrows(IOException.class, () -> index.seek(List.of(1L)));
                    assertTrue(failure.getMessage().contains(name), failure.getMessage());
                    assertEquals(SqliteTesting.read(index.scan()), SqliteTesting.read(index.seek(List.of())));
                }
                assertEquals(2, SqliteTesting.read(database.findIndex("t_binary").orElseThrow().seek(List.of("ant"))).size());
                assertEquals(List.of(List.of(4L, 4L), List.of(5L, 5L)), SqliteTesting.read(database.findIndex("t_partial").orElseThrow().seek(List.of(4L))));
            }
        }

        @Test
        @DisplayName("lists views and triggers but finds only tables, sqlite_schema included")
        void otherObjects() throws IOException {
            var file = assertShape(
                    "CREATE TABLE t(a)",
                    "CREATE VIEW v AS SELECT a FROM t",
                    "CREATE TRIGGER tr AFTER INSERT ON t BEGIN SELECT 1; END",
                    "INSERT INTO t VALUES (1)");
            try (var database = SqliteDatabase.open(file)) {
                assertTrue(database.findTable("v").isEmpty());
                assertTrue(database.findIndex("tr").isEmpty());
                var schema = database.findTable("SQLITE_MASTER").orElseThrow();
                var rows = SqliteTesting.read(schema.scan());
                assertEquals(database.schema().size(), rows.size());
                assertEquals("tr", rows.getLast().get(1));
            }
        }

        @Test
        @DisplayName("refuses a virtual table but reads its shadow tables")
        void virtualTable() throws IOException {
            var file = directory.resolve("fts.db");
            try (var jdbc = Jdbc.open(file)) {
                try {
                    jdbc.execute("CREATE VIRTUAL TABLE docs USING fts5(body)");
                } catch (IllegalStateException unavailable) {
                    assumeTrue(false, "the driver's SQLite has no FTS5");
                }
                jdbc.execute("INSERT INTO docs VALUES ('hello world'), ('other text')");
            }
            try (var database = SqliteDatabase.open(file); var jdbc = Jdbc.open(file)) {
                var failure = assertThrows(IOException.class, () -> database.findTable("docs"));
                assertTrue(failure.getMessage().contains("virtual"), failure.getMessage());
                SqliteTesting.assertMatchesJdbc(database, jdbc);
            }
        }
    }

    @Nested
    @DisplayName("Page layouts")
    class Layouts {
        @Test
        @DisplayName("read trees of three or more levels")
        void deepTrees() throws IOException {
            var file = directory.resolve("deep.db");
            try (var jdbc = Jdbc.open(file)) {
                jdbc.execute("PRAGMA page_size = 512", "CREATE TABLE t(id INTEGER PRIMARY KEY, a TEXT, b)", "CREATE INDEX t_by_a ON t(a, b)");
                var random = new Random(11);
                var rows = new ArrayList<Object[]>();
                for (var i = 0; i < 20_000; i++) {
                    rows.add(new Object[]{(long) random.nextInt(1_000_000_000), SqliteTesting.randomText(random, 20), (long) i});
                }
                jdbc.batch("INSERT OR IGNORE INTO t VALUES (?, ?, ?)", rows);
            }
            try (var database = SqliteDatabase.open(file); var jdbc = Jdbc.open(file)) {
                var table = (Long) jdbc.value("SELECT rootpage FROM sqlite_schema WHERE name = 't'");
                var index = (Long) jdbc.value("SELECT rootpage FROM sqlite_schema WHERE name = 't_by_a'");
                assertTrue(database.treeDepth(table, true) >= 3, "table depth " + database.treeDepth(table, true));
                assertTrue(database.treeDepth(index, false) >= 3, "index depth " + database.treeDepth(index, false));
                SqliteTesting.assertMatchesJdbc(database, jdbc);
            }
        }

        @Test
        @DisplayName("ignore the freelist pages that deletions leave")
        void freelist() throws IOException {
            var file = directory.resolve("free.db");
            try (var jdbc = Jdbc.open(file)) {
                jdbc.execute("PRAGMA page_size = 1024", "CREATE TABLE t(id INTEGER PRIMARY KEY, a BLOB)", "CREATE INDEX t_by_a ON t(a)");
                var random = new Random(5);
                var rows = new ArrayList<Object[]>();
                for (var i = 0; i < 4000; i++) {
                    rows.add(new Object[]{(long) i, SqliteTesting.randomBytes(random, random.nextInt(i % 50 == 0 ? 4000 : 100))});
                }
                jdbc.batch("INSERT INTO t VALUES (?, ?)", rows);
                jdbc.execute("DELETE FROM t WHERE id % 3 <> 0");
                assertTrue((Long) jdbc.value("PRAGMA freelist_count") > 10);
            }
            try (var database = SqliteDatabase.open(file); var jdbc = Jdbc.open(file)) {
                SqliteTesting.assertMatchesJdbc(database, jdbc);
            }
        }

        @Test
        @DisplayName("skip the pointer-map pages of auto_vacuum = FULL")
        void autoVacuum() throws IOException {
            var file = directory.resolve("vacuum.db");
            try (var jdbc = Jdbc.open(file)) {
                jdbc.execute("PRAGMA page_size = 512", "PRAGMA auto_vacuum = FULL", "CREATE TABLE t(id INTEGER PRIMARY KEY, a TEXT)",
                        "CREATE TABLE u(x, y)", "CREATE INDEX u_by_y ON u(y)");
                var random = new Random(9);
                var rows = new ArrayList<Object[]>();
                for (var i = 0; i < 3000; i++) {
                    rows.add(new Object[]{(long) i, SqliteTesting.randomText(random, random.nextInt(i % 40 == 0 ? 3000 : 60))});
                }
                jdbc.batch("INSERT INTO t VALUES (?, ?)", rows);
                jdbc.execute("INSERT INTO u SELECT a, id FROM t", "DELETE FROM t WHERE id % 4 = 1");
                assertEquals(1L, jdbc.value("PRAGMA auto_vacuum"));
            }
            try (var database = SqliteDatabase.open(file); var jdbc = Jdbc.open(file)) {
                SqliteTesting.assertMatchesJdbc(database, jdbc);
            }
        }

        @Test
        @DisplayName("keep b-tree content out of the reserved bytes at the end of each page")
        void reservedBytes() throws IOException {
            var file = directory.resolve("reserved.db");
            try (var jdbc = Jdbc.open(file)) {
                jdbc.execute("PRAGMA page_size = 1024", "PRAGMA user_version = 1");
            }
            // SQLite sets reserved bytes only through a file control the driver does not expose, so they are written
            // into the header of the still-empty database, with page 1's cell content area moved to match.
            try (var channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                channel.write(ByteBuffer.wrap(new byte[]{32}), 20);
                channel.write(ByteBuffer.wrap(new byte[]{(byte) (992 >> 8), (byte) (992 & 0xFF)}), 105);
            }
            try (var jdbc = Jdbc.open(file)) {
                jdbc.execute("CREATE TABLE t(id INTEGER PRIMARY KEY, a TEXT, b BLOB)", "CREATE INDEX t_by_a ON t(a)");
                var random = new Random(13);
                var rows = new ArrayList<Object[]>();
                for (var i = 0; i < 2000; i++) {
                    rows.add(new Object[]{(long) i, SqliteTesting.randomText(random, random.nextInt(200)),
                            SqliteTesting.randomBytes(random, random.nextInt(i % 30 == 0 ? 5000 : 50))});
                }
                jdbc.batch("INSERT INTO t VALUES (?, ?, ?)", rows);
                jdbc.execute("VACUUM");
                assertEquals("ok", jdbc.value("PRAGMA integrity_check"));
            }
            try (var channel = FileChannel.open(file)) {
                var header = ByteBuffer.allocate(21);
                channel.read(header, 0);
                assertEquals(32, header.get(20));
            }
            try (var database = SqliteDatabase.open(file); var jdbc = Jdbc.open(file)) {
                SqliteTesting.assertMatchesJdbc(database, jdbc);
            }
        }
    }
}
