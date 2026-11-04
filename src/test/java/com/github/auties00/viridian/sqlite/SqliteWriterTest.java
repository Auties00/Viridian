package com.github.auties00.viridian.sqlite;

import com.github.auties00.viridian.sqlite.SqliteTesting.Jdbc;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Writes databases with [SqliteWriter] and checks each against SQLite itself: SQLite opens the file and finds it
/// intact, Viridian's reader reads every table and index as SQLite does, and the file holds what SQLite stores when it
/// runs the same statements and inserts into a database of its own, every value of the same type, every refused row
/// refused by both.
@DisplayName("SQLite writer")
class SqliteWriterTest {
    @TempDir
    Path directory;

    /// The number of databases written so far, which names the next one.
    private int written;

    /// A row to insert.
    ///
    /// @param table   the table
    /// @param values  the values, in column order
    /// @param refused whether SQLite refuses the row
    private record Insert(String table, List<Object> values, boolean refused) {
    }

    /// A database to write both ways and compare.
    private final class Case {
        private final List<String> statements = new ArrayList<>();
        private final List<Insert> inserts = new ArrayList<>();
        private int pageSize = 4096;
        private int userVersion;

        Case pageSize(int pageSize) {
            this.pageSize = pageSize;
            return this;
        }

        Case userVersion(int userVersion) {
            this.userVersion = userVersion;
            return this;
        }

        Case execute(String... statements) {
            this.statements.addAll(List.of(statements));
            return this;
        }

        Case insert(String table, Object... values) {
            inserts.add(new Insert(table, Arrays.asList(values), false));
            return this;
        }

        Case refuse(String table, Object... values) {
            inserts.add(new Insert(table, Arrays.asList(values), true));
            return this;
        }

        /// Writes the database with the writer and through SQLite, checks the written one and compares the two.
        ///
        /// @return the written database
        Path check() throws IOException {
            var file = directory.resolve("written-" + written + ".db");
            var reference = directory.resolve("reference-" + written + ".db");
            written++;
            try (var writer = SqliteWriter.create(file, pageSize)) {
                if (userVersion != 0) {
                    writer.userVersion(userVersion);
                }
                statements.forEach(writer::execute);
                for (var insert : inserts) {
                    if (insert.refused()) {
                        assertThrows(RuntimeException.class, () -> writer.insert(insert.table(), insert.values()), "refused " + insert);
                    } else {
                        writer.insert(insert.table(), insert.values());
                    }
                }
                writer.finish();
            }
            try (var jdbc = Jdbc.open(reference)) {
                jdbc.execute("PRAGMA page_size = " + pageSize, "PRAGMA user_version = " + userVersion);
                jdbc.execute(statements.toArray(String[]::new));
                jdbc.execute("BEGIN");
                for (var insert : inserts) {
                    var sql = "INSERT INTO " + SqliteTesting.quote(insert.table()) + " VALUES ("
                            + String.join(", ", Collections.nCopies(insert.values().size(), "?")) + ")";
                    // The driver binds a Byte as its text, unlike every other integer type
                    var values = insert.values().stream().map(value -> value instanceof Byte number ? (Object) number.longValue() : value).toArray();
                    if (insert.refused()) {
                        assertThrows(IllegalStateException.class, () -> jdbc.update(sql, values), "SQLite refuses " + insert);
                    } else {
                        jdbc.update(sql, values);
                    }
                }
                jdbc.execute("COMMIT");
            }
            assertWritten(file, reference);
            return file;
        }
    }

    /// Checks that SQLite finds `file` intact, that the reader reads it as SQLite does, and that it holds what
    /// `reference` does.
    private static void assertWritten(Path file, Path reference) throws IOException {
        try (var actual = Jdbc.open(file); var expected = Jdbc.open(reference); var database = SqliteDatabase.open(file)) {
            assertEquals(List.of(List.of("ok")), actual.query("PRAGMA integrity_check"), "integrity check");
            assertEquals(expected.query("SELECT type, name, tbl_name, sql FROM sqlite_schema ORDER BY rowid"),
                    actual.query("SELECT type, name, tbl_name, sql FROM sqlite_schema ORDER BY rowid"), "sqlite_schema");
            for (var row : expected.query("SELECT name, wr FROM pragma_table_list WHERE schema = 'main' AND type = 'table' AND name <> 'sqlite_schema'")) {
                var name = (String) row.get(0);
                var withoutRowid = (Long) row.get(1) == 1;
                var select = new ArrayList<String>();
                var order = new ArrayList<String>();
                if (!withoutRowid) {
                    select.add("_rowid_");
                    order.add("_rowid_");
                }
                for (var column : expected.query("SELECT name FROM pragma_table_info(?)", name)) {
                    var quoted = SqliteTesting.quote((String) column.get(0));
                    select.add("typeof(" + quoted + ")");
                    select.add(quoted);
                    order.add(Integer.toString(select.size()));
                }
                var sql = "SELECT " + String.join(", ", select) + " FROM " + SqliteTesting.quote(name) + " ORDER BY " + String.join(", ", order);
                SqliteTesting.assertRows(expected.query(sql), actual.query(sql), "rows of " + name);
            }
            assertEquals(expected.value("PRAGMA user_version"), actual.value("PRAGMA user_version"), "user version");
            assertEquals(expected.value("PRAGMA encoding"), actual.value("PRAGMA encoding"), "encoding");
            assertEquals(0L, actual.value("PRAGMA freelist_count"), "free pages");
            SqliteTesting.assertMatchesJdbc(database, actual);
        }
    }

    /// {@return `prefix` followed by the four bytes of `suffix`, a key no other suffix gives}
    private static byte[] concat(byte[] prefix, int suffix) {
        var key = Arrays.copyOf(prefix, prefix.length + 4);
        for (var i = 0; i < 4; i++) {
            key[prefix.length + i] = (byte) (suffix >>> 24 - 8 * i);
        }
        return key;
    }

    /// {@return the mixed schema most tests write: a rowid alias, a `WITHOUT ROWID` table, indexes of every kind}
    private static String[] mixedSchema() {
        return new String[]{
                "CREATE TABLE items(id INTEGER PRIMARY KEY, name TEXT NOT NULL, size INTEGER, score REAL, data BLOB, tag)",
                "CREATE INDEX items_by_name ON items(name COLLATE NOCASE DESC, size)",
                "CREATE UNIQUE INDEX items_by_tag ON items(tag) WHERE tag IS NOT NULL",
                "CREATE INDEX items_without_data ON items(size) WHERE data IS NULL",
                "CREATE TABLE pairs(a TEXT, b INTEGER, c, PRIMARY KEY(b DESC, a)) WITHOUT ROWID",
                "CREATE INDEX pairs_by_c ON pairs(c)",
        };
    }

    /// Adds `count` random rows of the mixed schema to `test`, a few of them large enough to overflow.
    private static Case mixedRows(Case test, Random random, int count) {
        for (var i = 0; i < count; i++) {
            var large = i % 97 == 0;
            test.insert("items", i % 3 == 0 ? null : (long) (i * 7 + 3), SqliteTesting.randomText(random, large ? 20_000 : 1 + random.nextInt(60)),
                    random.nextInt(4) == 0 ? null : (long) random.nextInt(1000), random.nextInt(3) == 0 ? (Object) (double) random.nextInt(9) : random.nextDouble(),
                    random.nextBoolean() ? null : SqliteTesting.randomBytes(random, large ? 70_000 : random.nextInt(300)),
                    random.nextInt(5) == 0 ? "tag" + i : null);
            test.insert("pairs", SqliteTesting.randomText(random, random.nextInt(large ? 5_000 : 20)) + i, (long) random.nextInt(50) - 25,
                    random.nextBoolean() ? (Object) SqliteTesting.randomBytes(random, random.nextInt(large ? 30_000 : 50)) : (Object) (long) i);
        }
        return test;
    }

    @Nested
    @DisplayName("Page sizes and sizes of data")
    class Sizes {
        @ParameterizedTest(name = "{0}-byte pages")
        @ValueSource(ints = {512, 1024, 2048, 4096, 8192, 16384, 32768, 65536})
        @DisplayName("writes every page size SQLite allows")
        void pageSizes(int pageSize) throws IOException {
            var file = mixedRows(new Case().pageSize(pageSize).execute(mixedSchema()), new Random(pageSize), 1500).check();
            try (var database = SqliteDatabase.open(file)) {
                assertEquals(pageSize, database.pageSize());
            }
        }

        @Test
        @DisplayName("builds b-trees several levels deep for tens of thousands of rows")
        void manyRows() throws IOException {
            var random = new Random(5);
            var test = new Case().pageSize(512).execute(
                    "CREATE TABLE log(id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, message TEXT, payload BLOB)",
                    "CREATE INDEX log_by_at ON log(at)",
                    "CREATE INDEX log_by_message ON log(message, at DESC)",
                    "CREATE TABLE counts(key TEXT PRIMARY KEY, n INTEGER) WITHOUT ROWID");
            for (var i = 0; i < 30_000; i++) {
                test.insert("log", null, (long) random.nextInt(1_000_000), "m" + random.nextInt(5000), SqliteTesting.randomBytes(random, random.nextInt(40)));
                if (i % 3 == 0) {
                    test.insert("counts", "key-" + i, (long) i);
                }
            }
            var file = test.check();
            try (var database = SqliteDatabase.open(file)) {
                for (var name : List.of("log", "counts")) {
                    var entry = database.schema().stream().filter(row -> row.name().equals(name)).findFirst().orElseThrow();
                    assertTrue(database.treeDepth(entry.rootPage(), name.equals("log")) >= 3, name + " is at least 3 levels deep");
                }
                var index = database.schema().stream().filter(row -> row.name().equals("log_by_message")).findFirst().orElseThrow();
                assertTrue(database.treeDepth(index.rootPage(), false) >= 3, "log_by_message is at least 3 levels deep");
            }
        }

        @ParameterizedTest(name = "{0}-byte pages")
        @ValueSource(ints = {512, 4096, 65536})
        @DisplayName("spills rows of several megabytes to overflow pages")
        void largeRows(int pageSize) throws IOException {
            var random = new Random(11);
            new Case().pageSize(pageSize)
                    .execute("CREATE TABLE big(id INTEGER PRIMARY KEY, data BLOB, text TEXT)",
                            "CREATE TABLE keyed(k BLOB PRIMARY KEY, v) WITHOUT ROWID",
                            "CREATE INDEX big_by_text ON big(text)")
                    .insert("big", null, new byte[]{1}, "")
                    .insert("big", null, SqliteTesting.randomBytes(random, 5 << 20), "x")
                    .insert("big", null, null, SqliteTesting.randomText(random, 3 << 20))
                    .insert("big", null, SqliteTesting.randomBytes(random, 2 << 20), SqliteTesting.randomText(random, 100_000))
                    .insert("keyed", SqliteTesting.randomBytes(random, 1 << 20), SqliteTesting.randomBytes(random, 3 << 20))
                    .insert("keyed", new byte[0], "small")
                    .check();
        }

        @Test
        @DisplayName("never hands out the page that holds SQLite's lock byte, 2^30 bytes in")
        void lockBytePage() throws IOException {
            try (var channel = FileChannel.open(directory.resolve("pages.db"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                var pages = new SqlitePageWriter(channel, 65536);
                var previous = 1;
                while (previous < 16_390) {
                    var page = pages.allocate();
                    assertTrue(page == previous + 1 || previous == 16_384 && page == 16_386, "page " + page + " after " + previous);
                    previous = page;
                }
            }
        }

        @Test
        @DisplayName("writes an empty database SQLite opens")
        void empty() throws IOException {
            new Case().check();
            new Case().pageSize(65536).userVersion(-7).check();
        }

        @Test
        @DisplayName("roots sqlite_schema at page 1 whatever fits there")
        void schemaOnPageOne() throws IOException {
            new Case().pageSize(512).execute("CREATE TABLE t(" + "c".repeat(400) + " INTEGER)").insert("t", 1).check();
            var test = new Case().pageSize(512);
            for (var i = 0; i < 200; i++) {
                test.execute("CREATE TABLE table_" + i + "(id INTEGER PRIMARY KEY, value TEXT UNIQUE, other" + "x".repeat(i % 50) + ")");
            }
            test.execute("CREATE VIEW long_view AS SELECT '" + "v".repeat(10_000) + "'");
            test.check();
        }
    }

    @Nested
    @DisplayName("Affinity")
    class Affinity {
        private static final List<String> TYPES = List.of("", "TEXT", "NUMERIC", "INTEGER", "REAL", "BLOB", "VARCHAR(10)",
                "DOUBLE PRECISION", "FLOATING POINT", "INT8", "CLOB", "DATETIME", "BOOLEAN");

        private static final List<Object> TEXTS = List.of("12", " 12 ", "+5", "-0", "007", "1e3", "1.0", "1.5", ".5", "5.",
                "-0.0", "0x10", "abc", "12abc", "", "  ", "9223372036854775807", "9223372036854775808", "-9223372036854775808",
                "-9223372036854775809", "1e999", "-1e999", "1e-999", "140737488355327", "140737488355328", "1_000", "1e", "\t3\n",
                "123456789012345678901234567890", "4.9e-324", "2.5E+3", "Infinity", "NaN");

        /// {@return whether a column of type `type` has TEXT affinity}
        private static boolean text(String type) {
            return type.equals("TEXT") || type.startsWith("VARCHAR") || type.equals("CLOB");
        }

        @Test
        @DisplayName("converts every kind of value by every affinity as SQLite does")
        void matrix() throws IOException {
            var test = new Case();
            for (var i = 0; i < TYPES.size(); i++) {
                test.execute("CREATE TABLE t" + i + "(v " + TYPES.get(i) + ")",
                        "CREATE TABLE w" + i + "(k INTEGER PRIMARY KEY, v " + TYPES.get(i) + ") WITHOUT ROWID",
                        "CREATE INDEX t" + i + "_by_v ON t" + i + "(v)");
                var values = new ArrayList<>(SqliteTesting.specialValues());
                values.addAll(TEXTS);
                values.addAll(List.of(Integer.MIN_VALUE, (short) -3, (byte) 7, 1.5f, Float.MAX_VALUE, Double.NaN, Float.NaN,
                        1L << 47, (1L << 47) - 1, -(1L << 47), -(1L << 47) - 1, 1e15, 1e16, -1e15, 4.5e15, 0.1));
                var key = 0L;
                for (var value : values) {
                    if (text(TYPES.get(i)) && (value instanceof Double || value instanceof Float) && !Double.isNaN(((Number) value).doubleValue())) {
                        continue;
                    }
                    test.insert("t" + i, value);
                    test.insert("w" + i, key++, value);
                }
            }
            test.check();
        }

        @Test
        @DisplayName("refuses a REAL for a TEXT column, whose text form depends on the SQLite version")
        void realIntoText() throws IOException {
            try (var writer = SqliteWriter.create(directory.resolve("text.db"), 4096)) {
                writer.execute("CREATE TABLE t(a TEXT, b VARCHAR(3))");
                assertThrows(IllegalArgumentException.class, () -> writer.insert("t", List.of(1.5, "x")));
                assertThrows(IllegalArgumentException.class, () -> writer.insert("t", List.of("x", 2.0)));
                writer.insert("t", Arrays.asList(Double.NaN, 3L));
            }
        }
    }

    @Nested
    @DisplayName("Rowids")
    class Rowids {
        @Test
        @DisplayName("takes the next rowid or the alias's value, as SQLite does")
        void aliases() throws IOException {
            new Case()
                    .execute("CREATE TABLE a(id INTEGER PRIMARY KEY, v)", "CREATE TABLE plain(v)", "CREATE TABLE d(id INTEGER PRIMARY KEY DESC, v)")
                    .insert("a", null, "first").insert("a", 10, "ten").insert("a", null, "eleven").insert("a", -5, "negative")
                    .insert("a", "20", "text twenty").insert("a", 30.0, "real thirty").insert("a", " 40 ", "spaced").insert("a", null, "next")
                    .insert("a", 3, "out of order").insert("a", Long.MIN_VALUE, "smallest")
                    .refuse("a", 10, "duplicate").refuse("a", 2.5, "fraction").refuse("a", "abc", "text").refuse("a", new byte[]{1}, "blob")
                    .refuse("a", "10", "duplicate as text")
                    .insert("plain", "x").insert("plain", "y")
                    .insert("d", 5, "five").refuse("d", 5, "five again").insert("d", null, "null").insert("d", null, "null again")
                    .check();
        }

        @Test
        @DisplayName("keeps sqlite_sequence as SQLite does for AUTOINCREMENT tables")
        void autoincrement() throws IOException {
            new Case()
                    .execute("CREATE TABLE first(id INTEGER PRIMARY KEY AUTOINCREMENT, v)",
                            "CREATE TABLE empty(id INTEGER PRIMARY KEY AUTOINCREMENT, v)",
                            "CREATE TABLE second(id INTEGER, v UNIQUE, PRIMARY KEY(id AUTOINCREMENT))",
                            "CREATE TABLE third(id INTEGER PRIMARY KEY AUTOINCREMENT, v)")
                    .insert("sqlite_sequence", "third", 100)
                    .insert("second", null, "a").insert("first", null, "a").insert("first", 50, "b").insert("first", 10, "c")
                    .insert("first", null, "d").insert("second", -3, "negative").insert("third", null, "after 100")
                    .insert("sqlite_sequence", "first", 1000).insert("first", null, "stays with the first row")
                    .refuse("second", null, "a")
                    .check();
        }

        @Test
        @DisplayName("stops an AUTOINCREMENT table at the largest rowid, and finds an unused one for any other table")
        void largest() throws IOException {
            var file = directory.resolve("largest.db");
            try (var writer = SqliteWriter.create(file, 4096)) {
                writer.execute("CREATE TABLE a(id INTEGER PRIMARY KEY AUTOINCREMENT)");
                writer.execute("CREATE TABLE p(id INTEGER PRIMARY KEY)");
                writer.insert("a", List.of(Long.MAX_VALUE));
                assertThrows(IllegalStateException.class, () -> writer.insert("a", Arrays.asList((Object) null)));
                writer.insert("p", List.of(Long.MAX_VALUE));
                var rowid = writer.insert("p", Arrays.asList((Object) null));
                assertTrue(rowid > 0 && rowid < Long.MAX_VALUE);
                writer.finish();
            }
            try (var jdbc = Jdbc.open(file); var database = SqliteDatabase.open(file)) {
                assertEquals(List.of(List.of("ok")), jdbc.query("PRAGMA integrity_check"));
                assertEquals(List.of(List.of("a", Long.MAX_VALUE)), jdbc.query("SELECT * FROM sqlite_sequence"));
                SqliteTesting.assertMatchesJdbc(database, jdbc);
            }
        }
    }

    @Nested
    @DisplayName("Constraints")
    class Constraints {
        @Test
        @DisplayName("refuses the rows SQLite refuses and keeps nothing of them")
        void violations() throws IOException {
            new Case()
                    .execute("CREATE TABLE t(id INTEGER PRIMARY KEY, a TEXT NOT NULL, b UNIQUE, c TEXT COLLATE NOCASE, d, UNIQUE(c), UNIQUE(d, b))",
                            "CREATE TABLE w(k TEXT, n INTEGER, v, PRIMARY KEY(k, n)) WITHOUT ROWID",
                            "CREATE UNIQUE INDEX w_by_v ON w(v) WHERE v IS NOT NULL",
                            "CREATE TABLE r(k TEXT COLLATE RTRIM PRIMARY KEY, v)")
                    .insert("t", null, "a", 1, "Mixed", null).insert("t", null, "b", null, null, null).insert("t", null, "c", null, null, null)
                    .refuse("t", null, null, 2, "other", null).refuse("t", null, "d", 1, "x", null).refuse("t", null, "e", 3, "MIXED", null)
                    .refuse("t", null, Double.NaN, 4, "y", null).refuse("t", 1, "f", 5, "z", null)
                    .insert("t", null, "g", 6, "mixed ", 6).insert("t", null, 7, 7, "seven", 6).refuse("t", null, "h", 1.0, "w", null)
                    .insert("w", "x", 1, null).insert("w", "x", 2, null).insert("w", "x", "3", 5).refuse("w", "x", 3.0, 6)
                    .refuse("w", null, 4, 7).refuse("w", "y", 1, 5).insert("w", "X", 1, "five")
                    .insert("r", "key", 1).refuse("r", "key  ", 2).insert("r", " key", 3)
                    .check();
        }

        @Test
        @DisplayName("enforces nothing a partial index leaves out")
        void partial() throws IOException {
            new Case()
                    .execute("CREATE TABLE t(a, b)",
                            "CREATE UNIQUE INDEX t_null_b ON t(a) WHERE b IS NULL",
                            "CREATE UNIQUE INDEX t_set_b ON t(a) WHERE (b NOTNULL)",
                            "CREATE INDEX t_isnull ON t(b) WHERE a ISNULL",
                            "CREATE INDEX t_not_null ON t(a, b) WHERE b NOT NULL")
                    .insert("t", 1, null).insert("t", 1, "x").refuse("t", 1, null).refuse("t", 1, "y").insert("t", 2, "x")
                    .insert("t", null, null).insert("t", null, null).insert("t", null, 5)
                    .check();
        }
    }

    @Nested
    @DisplayName("Schemas")
    class Schemas {
        @Test
        @DisplayName("creates the indexes constraints imply, numbered and stored as SQLite does")
        void autoIndexes() throws IOException {
            var test = new Case().execute(
                    "CREATE TABLE meta(key LONGVARCHAR NOT NULL UNIQUE PRIMARY KEY, value LONGVARCHAR)",
                    "CREATE TABLE u(a UNIQUE, b, c, UNIQUE(b, c), UNIQUE(c, b), UNIQUE(a), UNIQUE(b COLLATE NOCASE, c), PRIMARY KEY(c))",
                    "CREATE TABLE wr(a UNIQUE, b PRIMARY KEY, c UNIQUE) WITHOUT ROWID",
                    "CREATE TABLE wr_first(a PRIMARY KEY, b UNIQUE) WITHOUT ROWID",
                    "CREATE TABLE wr_int(a INTEGER PRIMARY KEY, b UNIQUE, c UNIQUE) WITHOUT ROWID",
                    "CREATE TABLE wr_dup(a, b, c UNIQUE, PRIMARY KEY(b, a, b)) WITHOUT ROWID",
                    "CREATE TABLE pk_desc(a TEXT PRIMARY KEY DESC, b)",
                    "CREATE TABLE alias_unique(id INTEGER PRIMARY KEY UNIQUE, v)",
                    "CREATE INDEX u_by_b ON u(b DESC, a COLLATE RTRIM)");
            var random = new Random(17);
            for (var i = 0; i < 400; i++) {
                test.insert("meta", "key" + i, random.nextInt(9));
                test.insert("u", "a" + i, i % 50, "c" + i);
                test.insert("wr", "a" + i, (long) i, i % 2 == 0 ? null : "c" + i);
                test.insert("wr_first", SqliteTesting.randomText(random, 5) + i, i);
                test.insert("wr_int", (long) (i * 13 % 400), "b" + i, i % 3 == 0 ? null : (long) i);
                test.insert("wr_dup", (long) (i % 7), "b" + i, null);
                test.insert("pk_desc", "k" + i, i);
                test.insert("alias_unique", null, i);
            }
            test.check();
        }

        @Test
        @DisplayName("stores the statement text SQLite stores")
        void statementText() throws IOException {
            new Case().execute(
                    "create   table   IF NOT EXISTS   \"Mixed Case\" (  a  int , \"b c\" text  )   ;  ",
                    "CREATE TABLE IF NOT EXISTS \"mixed case\"(other)",
                    "CREATE TABLE main.qualified(x)",
                    "CREATE TABLE opts(a PRIMARY KEY, b) WITHOUT ROWID  ",
                    "CREATE TABLE opts_semi(a PRIMARY KEY, b) WITHOUT ROWID ; -- comment",
                    "CREATE TABLE commented(a /* inline */, b) -- trailing",
                    "CREATE TABLE [bracketed](`back` TEXT, 'single')",
                    "create unique index if not exists idx ON \"Mixed Case\"(a)  ",
                    "CREATE INDEX IF NOT EXISTS idx ON qualified(x)",
                    "CREATE INDEX main.idx2 ON QUALIFIED (x DESC) ;",
                    "CREATE VIEW v AS SELECT * FROM qualified   ;",
                    "CREATE VIEW IF NOT EXISTS v2(n) AS SELECT x FROM qualified WHERE x > 1 -- tail\n  ",
                    "CREATE TRIGGER trg AFTER INSERT ON Qualified BEGIN SELECT 1; SELECT 2; END;",
                    "CREATE TRIGGER IF NOT EXISTS main.trg2 BEFORE UPDATE OF x ON qualified FOR EACH ROW WHEN new.x > 0 BEGIN SELECT 1; END",
                    "CREATE TRIGGER v_insert INSTEAD OF INSERT ON v BEGIN INSERT INTO qualified VALUES (new.x); END")
                    .insert("Mixed Case", 1, "one").insert("qualified", 2).insert("opts", 3, 4).insert("bracketed", "x", "y")
                    .check();
        }

        @Test
        @DisplayName("writes Chromium's Local Storage schema")
        void localStorage() throws IOException {
            var test = new Case().execute(
                    "CREATE TABLE maps(row_id INTEGER PRIMARY KEY AUTOINCREMENT,storage_key BLOB NOT NULL,last_accessed INTEGER,last_modified INTEGER,total_size INTEGER)",
                    "CREATE UNIQUE INDEX maps_by_storage_key ON maps(storage_key)",
                    "CREATE TABLE map_entries(map_id INTEGER NOT NULL,value_compression_type INTEGER NOT NULL,key BLOB NOT NULL,value BLOB NOT NULL,PRIMARY KEY(map_id, key)) WITHOUT ROWID;",
                    "CREATE TABLE meta(key LONGVARCHAR NOT NULL UNIQUE PRIMARY KEY, value LONGVARCHAR)");
            test.insert("meta", "mmap_status", -1L).insert("meta", "version", 1L).insert("meta", "last_compatible_version", 1L);
            var random = new Random(23);
            for (var map = 1; map <= 20; map++) {
                test.insert("maps", null, ("https://site" + map + ".example/").getBytes(), 13_400_000_000_000_000L + map, 13_400_000_000_000_000L, (long) map * 10);
                for (var i = 0; i < map * 10; i++) {
                    test.insert("map_entries", (long) map, 0, concat(SqliteTesting.randomBytes(random, random.nextInt(30)), i),
                            SqliteTesting.randomBytes(random, random.nextInt(map == 7 ? 100_000 : 500)));
                }
            }
            test.refuse("maps", null, "https://site1.example/".getBytes(), null, null, null);
            test.check();
        }

        @Test
        @DisplayName("writes Chromium's IndexedDB schema, triggers included")
        void indexedDb() throws IOException {
            var test = new Case().execute(
                    "CREATE TABLE indexed_db_metadata (name BLOB NOT NULL, version INTEGER NOT NULL)",
                    "CREATE TABLE object_stores (id INTEGER PRIMARY KEY, name BLOB NOT NULL, key_path BLOB, auto_increment INTEGER NOT NULL, key_generator_current_number INTEGER NOT NULL)",
                    "CREATE TABLE indexes (object_store_id INTEGER NOT NULL, id INTEGER NOT NULL, name BLOB NOT NULL, key_path BLOB, is_unique INTEGER NOT NULL, multi_entry INTEGER NOT NULL, PRIMARY KEY (object_store_id, id)) WITHOUT ROWID",
                    "CREATE TABLE records (row_id INTEGER PRIMARY KEY, object_store_id INTEGER NOT NULL, compression_type INTEGER NOT NULL, key BLOB NOT NULL, value BLOB NOT NULL)",
                    "CREATE UNIQUE INDEX records_by_key ON records(object_store_id, key)",
                    "CREATE TABLE index_references (record_row_id INTEGER NOT NULL, index_id INTEGER NOT NULL, key BLOB NOT NULL, object_store_id INTEGER NOT NULL, record_key BLOB NOT NULL, PRIMARY KEY (record_row_id, index_id, key)) WITHOUT ROWID",
                    "CREATE INDEX index_references_by_key ON index_references (object_store_id, index_id, key, record_key)",
                    "CREATE TABLE blobs (row_id INTEGER PRIMARY KEY AUTOINCREMENT, object_type INTEGER NOT NULL, mime_type TEXT, size_bytes INTEGER, file_name BLOB, last_modified INTEGER,  bytes BLOB)",
                    "CREATE INDEX legacy_blobs ON blobs (object_type) WHERE bytes IS NULL",
                    "CREATE TABLE overflow_blob_chunks (row_id INTEGER PRIMARY KEY, blob_row_id INTEGER NOT NULL, chunk_index INTEGER NOT NULL, bytes BLOB NOT NULL)",
                    "CREATE INDEX overflow_chunks_by_blob ON overflow_blob_chunks (blob_row_id, chunk_index)",
                    "CREATE TABLE blob_references (row_id INTEGER PRIMARY KEY, blob_row_id INTEGER NOT NULL, record_row_id INTEGER)",
                    "CREATE INDEX blob_references_by_blob ON blob_references (blob_row_id)",
                    "CREATE INDEX blob_references_by_record ON blob_references (record_row_id, blob_row_id)",
                    "CREATE TRIGGER on_record_deleted AFTER DELETE ON records BEGIN  DELETE FROM index_references WHERE record_row_id = OLD.row_id;  DELETE FROM blob_references WHERE record_row_id = OLD.row_id;END",
                    "CREATE TRIGGER on_blob_reference_deleted  AFTER DELETE ON blob_references WHEN NOT EXISTS  (SELECT 1 FROM blob_references WHERE blob_row_id = OLD.blob_row_id) BEGIN  DELETE FROM blobs WHERE row_id = OLD.blob_row_id;END",
                    "CREATE TRIGGER on_blob_deleted  AFTER DELETE ON blobs BEGIN  DELETE FROM overflow_blob_chunks WHERE blob_row_id = OLD.row_id;END",
                    "CREATE TABLE meta(key LONGVARCHAR NOT NULL UNIQUE PRIMARY KEY, value LONGVARCHAR)");
            var random = new Random(31);
            test.insert("indexed_db_metadata", "database".getBytes(StandardCharsets.UTF_16LE), 3L);
            for (var store = 1; store <= 4; store++) {
                test.insert("object_stores", store, ("store" + store).getBytes(StandardCharsets.UTF_16LE),
                        store == 2 ? null : "id".getBytes(StandardCharsets.UTF_16LE), store % 2, 1L);
                for (var index = 1; index <= 2; index++) {
                    test.insert("indexes", store, index, ("index" + index).getBytes(StandardCharsets.UTF_16LE), null, index - 1, 0);
                }
                for (var i = 0; i < 300; i++) {
                    test.insert("records", null, store, i % 2, concat(SqliteTesting.randomBytes(random, random.nextInt(20)), i),
                            SqliteTesting.randomBytes(random, random.nextInt(i % 100 == 0 ? 50_000 : 400)));
                }
            }
            for (var i = 0; i < 20; i++) {
                test.insert("blobs", null, i % 2, "image/png", (long) i * 100, i % 2 == 1 ? "file.png".getBytes() : null,
                        i % 2 == 1 ? 13_400_000_000_000_000L : null, i % 5 == 0 ? null : SqliteTesting.randomBytes(random, i * 1000));
                test.insert("blob_references", null, i + 1, i % 4 == 0 ? null : (long) i + 1);
            }
            test.insert("overflow_blob_chunks", null, 2, 1, SqliteTesting.randomBytes(random, 200_000));
            test.insert("meta", "mmap_status", -1L).insert("meta", "version", 1L).insert("meta", "last_compatible_version", 1L);
            test.check();
        }
    }

    @Nested
    @DisplayName("STRICT tables")
    class Strict {
        @Test
        @DisplayName("Chromium's quota database, a STRICT table, is written as SQLite writes it")
        void quotaManager() throws Exception {
            var test = new Case().execute(
                    "CREATE TABLE meta(key LONGVARCHAR NOT NULL UNIQUE PRIMARY KEY, value LONGVARCHAR)",
                    "CREATE TABLE buckets(id INTEGER PRIMARY KEY AUTOINCREMENT, storage_key TEXT NOT NULL, host TEXT NOT NULL, name TEXT NOT NULL,"
                            + " use_count INTEGER NOT NULL, last_accessed INTEGER NOT NULL, last_modified INTEGER NOT NULL, expiration INTEGER NOT NULL,"
                            + " quota INTEGER NOT NULL, persistent INTEGER NOT NULL, durability INTEGER NOT NULL) STRICT",
                    "CREATE UNIQUE INDEX buckets_by_storage_key ON buckets(storage_key, name)",
                    "CREATE INDEX buckets_by_host ON buckets(host)",
                    "CREATE INDEX buckets_by_last_accessed ON buckets(last_accessed)",
                    "CREATE INDEX buckets_by_last_modified ON buckets(last_modified)",
                    "CREATE INDEX buckets_by_expiration ON buckets(expiration)");
            test.insert("meta", "mmap_status", "-1").insert("meta", "version", "11").insert("meta", "last_compatible_version", "11");
            for (var i = 1; i <= 40; i++) {
                test.insert("buckets", null, "http://host" + i + ".test/", "host" + i + ".test", i % 3 == 0 ? "drafts" : "_default",
                        0L, 13_400_000_000_000_000L + i, 13_400_000_000_000_000L + i, 0L, 0L, 0L, 0L);
            }
            test.check();
        }

        @Test
        @DisplayName("a STRICT table refuses a value its column's type cannot hold, after converting it as the type's affinity does")
        void types(@org.junit.jupiter.api.io.TempDir Path directory) throws IOException {
            try (var writer = SqliteWriter.create(directory.resolve("strict.db"), 4096)) {
                writer.execute("CREATE TABLE t(i INTEGER, r REAL, t TEXT, b BLOB, a ANY) STRICT");
                writer.insert("t", java.util.Arrays.asList(1L, 2L, "text", new byte[]{1}, "anything"));
                writer.insert("t", java.util.Arrays.asList("3", 1.5, 7L, null, 4L));
                assertThrows(IllegalArgumentException.class, () -> writer.insert("t", java.util.Arrays.asList("not a number", 1.0, "t", null, null)));
                assertThrows(IllegalArgumentException.class, () -> writer.insert("t", java.util.Arrays.asList(1L, 1.0, "t", "text in a blob", null)));
            }
        }
    }

    @Nested
    @DisplayName("Refusals")
    class Refusals {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "CREATE TABLE t(a CHECK (a > 0))",
                "CREATE TABLE t(a, CHECK (a > 0))",
                "CREATE TABLE t(a VARCHAR(10)) STRICT",
                "CREATE TABLE t(a, b AS (a + 1))",
                "CREATE TABLE t(a UNIQUE ON CONFLICT REPLACE)",
                "CREATE TABLE t(a PRIMARY KEY ON CONFLICT IGNORE)",
                "CREATE TABLE t(a COLLATE unknown)",
                "CREATE TABLE t(a, a)",
                "CREATE TABLE t(a PRIMARY KEY, b PRIMARY KEY)",
                "CREATE TABLE t(a TEXT PRIMARY KEY AUTOINCREMENT)",
                "CREATE TABLE t(a) WITHOUT ROWID",
                "CREATE TABLE t AS SELECT 1",
                "CREATE TEMP TABLE t(a)",
                "CREATE TABLE temp.t(a)",
                "CREATE VIRTUAL TABLE t USING fts5(a)",
                "CREATE TABLE sqlite_mine(a)",
                "CREATE TABLE existing(a)",
                "CREATE INDEX i ON existing(a + 1)",
                "CREATE INDEX i ON existing(a) WHERE a > 1",
                "CREATE INDEX i ON existing(a) WHERE a IS NULL OR b IS NULL",
                "CREATE INDEX i ON existing(a COLLATE unknown)",
                "CREATE INDEX i ON missing(a)",
                "CREATE INDEX i ON existing(missing)",
                "CREATE INDEX existing ON existing(a)",
                "CREATE TRIGGER trg AFTER INSERT ON missing BEGIN SELECT 1; END",
                "CREATE TABLE a(x); CREATE TABLE b(y)",
                "DROP TABLE existing",
                "INSERT INTO existing VALUES (1)",
                "",
                "CREATE",
                "CREATE TABLE",
                "CREATE TABLE IF NOT",
                "CREATE INDEX i ON",
                "CREATE TRIGGER trg",
                "CREATE UNIQUE TABLE u(a)",
        })
        @DisplayName("refuses statements it cannot honour")
        void statements(String statement) throws IOException {
            try (var writer = SqliteWriter.create(directory.resolve("refused.db"), 4096)) {
                writer.execute("CREATE TABLE existing(a, b)");
                assertThrows(IllegalArgumentException.class, () -> writer.execute(statement));
            }
        }

        @Test
        @DisplayName("refuses values it cannot store and rows of the wrong shape")
        void values() throws IOException {
            try (var writer = SqliteWriter.create(directory.resolve("values.db"), 4096)) {
                writer.execute("CREATE TABLE t(a, b)");
                assertThrows(IllegalArgumentException.class, () -> writer.insert("t", List.of(1, true)));
                assertThrows(IllegalArgumentException.class, () -> writer.insert("t", List.of(1, "lone \uD800 surrogate")));
                assertThrows(IllegalArgumentException.class, () -> writer.insert("t", List.of(1)));
                assertThrows(IllegalArgumentException.class, () -> writer.insert("missing", List.of(1, 2)));
                assertThrows(IllegalArgumentException.class, () -> writer.insert("sqlite_sequence", List.of(1, 2)));
                writer.insert("t", List.of(1, "pair 😀"));
                assertThrows(IllegalStateException.class, () -> writer.execute("CREATE INDEX late ON t(a)"));
            }
        }

        @Test
        @DisplayName("deletes the file unless finished, and refuses to overwrite one")
        void lifecycle() throws IOException {
            var file = directory.resolve("life.db");
            try (var writer = SqliteWriter.create(file, 4096)) {
                writer.execute("CREATE TABLE t(a)");
                writer.insert("t", List.of(new byte[100_000]));
                assertTrue(Files.exists(file));
            }
            assertFalse(Files.exists(file));
            assertThrows(IllegalArgumentException.class, () -> SqliteWriter.create(file, 1000));
            assertThrows(IllegalArgumentException.class, () -> SqliteWriter.create(file, 256));
            assertFalse(Files.exists(file));
            var writer = SqliteWriter.create(file, 4096);
            writer.finish();
            assertThrows(IllegalStateException.class, writer::finish);
            assertThrows(IllegalStateException.class, () -> writer.execute("CREATE TABLE t(a)"));
            writer.close();
            assertThrows(IllegalStateException.class, () -> writer.insert("t", List.of(1)));
            assertTrue(Files.exists(file));
            assertThrows(FileAlreadyExistsException.class, () -> SqliteWriter.create(file, 4096));
            Files.write(SqliteBytes.sibling(directory.resolve("journaled.db"), "-journal"), new byte[512]);
            assertThrows(FileAlreadyExistsException.class, () -> SqliteWriter.create(directory.resolve("journaled.db"), 4096));
        }
    }
}
