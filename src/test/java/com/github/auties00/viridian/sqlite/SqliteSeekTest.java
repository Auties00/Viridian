package com.github.auties00.viridian.sqlite;

import com.github.auties00.viridian.sqlite.SqliteTesting.Jdbc;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Checks index and table seeks against SQLite's `WHERE ... >= ?` with `ORDER BY` over hundreds of random keys of
/// every storage class, and rowid lookups for present and absent rowids, in each text encoding. The indexed values
/// mix every storage class and include long text and BLOBs sharing long prefixes, so that comparisons read into
/// overflow pages.
@DisplayName("Seeks and lookups")
class SqliteSeekTest {
    @TempDir
    Path directory;

    /// The candidate key values, drawn from the indexed data and beyond it.
    private static List<Object> candidates(Random random) {
        var values = new ArrayList<>(SqliteTesting.specialValues());
        values.removeIf(value -> value instanceof String text && text.length() > 1000 || value instanceof byte[] bytes && bytes.length > 1000);
        for (var i = 0; i < 40; i++) {
            values.add((long) random.nextInt(2001) - 1000);
            values.add(random.nextInt(2001) - 1000.5);
            values.add((double) (random.nextInt(201) - 100));
            values.add(SqliteTesting.randomText(random, random.nextInt(6)));
            values.add(SqliteTesting.randomBytes(random, random.nextInt(4)));
            values.add(PREFIX + SqliteTesting.randomText(random, 3));
            values.add(concat(BLOB_PREFIX, SqliteTesting.randomBytes(random, 2)));
        }
        return values;
    }

    /// A text prefix long enough that index entries holding it overflow their page.
    private static final String PREFIX = "p".repeat(1500);

    /// A BLOB prefix long enough that index entries holding it overflow their page.
    private static final byte[] BLOB_PREFIX = new byte[1800];

    private static byte[] concat(byte[] first, byte[] second) {
        var result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    /// Creates the seek test's database in `encoding`.
    private Path create(String encoding) {
        var file = directory.resolve("seek-" + encoding + ".db");
        try (var jdbc = Jdbc.open(file)) {
            jdbc.execute("PRAGMA page_size = 1024", "PRAGMA encoding = '" + encoding + "'",
                    "CREATE TABLE m(k, v)", "CREATE INDEX m_by_k ON m(k)", "CREATE INDEX m_by_kv ON m(k, v)",
                    "CREATE TABLE t(id INTEGER PRIMARY KEY, a TEXT, b)",
                    "CREATE TABLE w(a, b, c, PRIMARY KEY(a, b)) WITHOUT ROWID", "CREATE INDEX w_by_c ON w(c)");
            var random = new Random(encoding.hashCode());
            var values = candidates(random);
            var m = new ArrayList<Object[]>();
            var t = new ArrayList<Object[]>();
            var w = new ArrayList<Object[]>();
            for (var i = 0; i < 4000; i++) {
                var key = values.get(random.nextInt(values.size()));
                var v = switch (random.nextInt(4)) {
                    case 0 -> null;
                    case 1 -> (Object) (long) random.nextInt(10);
                    case 2 -> random.nextDouble();
                    default -> SqliteTesting.randomText(random, 2);
                };
                m.add(new Object[]{key, v});
                t.add(new Object[]{random.nextLong(), SqliteTesting.randomText(random, 10), key});
                w.add(new Object[]{key == null ? (Object) (long) i : key, (long) i, v});
            }
            for (var extreme : List.of(Long.MIN_VALUE, Long.MAX_VALUE, 0L, -1L, 1L)) {
                t.add(new Object[]{extreme, "extreme", null});
            }
            jdbc.batch("INSERT INTO m VALUES (?, ?)", m);
            jdbc.batch("INSERT OR IGNORE INTO t VALUES (?, ?, ?)", t);
            jdbc.batch("INSERT INTO w VALUES (?, ?, ?)", w);
        }
        return file;
    }

    /// {@return the condition that an entry's fields `fields` are at least `key`, as SQLite orders index entries,
    /// with the bound values appended to `parameters`}
    ///
    /// SQLite's `>` and `=` are `NULL` when either side is, so the condition spells out that `NULL` sorts first and
    /// equals itself.
    private static String atLeast(List<String> fields, List<?> key, List<Object> parameters) {
        var field = fields.getFirst();
        var value = key.getFirst();
        if (key.size() == 1) {
            if (value == null) {
                return "1";
            }
            parameters.add(value);
            return field + " >= ?";
        }
        String greater;
        if (value == null) {
            greater = field + " IS NOT NULL";
        } else {
            greater = field + " > ?";
            parameters.add(value);
        }
        parameters.add(value);
        var rest = atLeast(fields.subList(1, fields.size()), key.subList(1, key.size()), parameters);
        return "(" + greater + " OR (" + field + " IS ? AND (" + rest + ")))";
    }

    @Nested
    @DisplayName("Index seeks")
    class IndexSeeks {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.viridian.sqlite.SqliteTesting#encodings")
        @DisplayName("start at the first entry at least equal to the key")
        void randomKeys(String encoding) throws IOException {
            var file = create(encoding);
            var random = new Random(41);
            var values = candidates(random);
            try (var database = SqliteDatabase.open(file); var jdbc = Jdbc.open(file)) {
                var single = database.findIndex("m_by_k").orElseThrow();
                var pair = database.findIndex("m_by_kv").orElseThrow();
                for (var round = 0; round < 300; round++) {
                    var first = values.get(random.nextInt(values.size()));
                    var second = random.nextInt(5) == 0 ? null : values.get(random.nextInt(values.size()));
                    List<?> key = switch (round % 4) {
                        case 0, 1 -> Arrays.asList(first);
                        case 2 -> Arrays.asList(first, second);
                        default -> Arrays.asList(first, second, (long) random.nextInt(4000));
                    };
                    var index = key.size() == 1 ? single : pair;
                    var fields = key.size() == 1 ? List.of("k", "_rowid_") : List.of("k", "v", "_rowid_");
                    var parameters = new ArrayList<>();
                    var condition = atLeast(fields.subList(0, key.size()), key, parameters);
                    var expected = jdbc.query("SELECT " + String.join(", ", fields) + " FROM m WHERE " + condition
                            + " ORDER BY " + String.join(", ", fields) + " LIMIT 60", parameters.toArray());
                    var actual = new ArrayList<List<Object>>();
                    try (var cursor = index.seek(key)) {
                        while (actual.size() < 60 && cursor.next()) {
                            actual.add(SqliteTesting.values(cursor.row()));
                        }
                    }
                    SqliteTesting.assertRows(expected, actual, "seek of " + index.name() + " to " + key);
                }
            }
        }

        @Test
        @DisplayName("reach the end past the last entry and the start before the first")
        void bounds() throws IOException {
            var file = create("UTF-8");
            try (var database = SqliteDatabase.open(file)) {
                var index = database.findIndex("m_by_k").orElseThrow();
                var largest = new byte[4000];
                Arrays.fill(largest, (byte) 0xFF);
                assertTrue(SqliteTesting.read(index.seek(List.of(largest))).isEmpty());
                assertEquals(SqliteTesting.read(index.scan()), SqliteTesting.read(index.seek(Arrays.asList((Object) null))));
            }
        }

        @Test
        @DisplayName("reject keys longer than an entry, or of a type SQLite cannot store")
        void invalidKeys() throws IOException {
            var file = create("UTF-8");
            try (var database = SqliteDatabase.open(file)) {
                var index = database.findIndex("m_by_k").orElseThrow();
                assertThrows(IllegalArgumentException.class, () -> index.seek(List.of(1L, 2L, 3L)));
                assertThrows(IllegalArgumentException.class, () -> index.seek(List.of(BigDecimal.ONE)));
                assertThrows(IllegalArgumentException.class, () -> database.findTable("t").orElseThrow().seek(List.of("text")));
                assertThrows(IllegalArgumentException.class, () -> database.findTable("w").orElseThrow().seek(List.of(1L, 2L, 3L)));
            }
        }
    }

    @Nested
    @DisplayName("Table seeks and lookups")
    class TableSeeks {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.viridian.sqlite.SqliteTesting#encodings")
        @DisplayName("look rows up by rowid, present or absent")
        void findByRowid(String encoding) throws IOException {
            var file = create(encoding);
            var random = new Random(43);
            try (var database = SqliteDatabase.open(file); var jdbc = Jdbc.open(file)) {
                var table = database.findTable("t").orElseThrow();
                var rowids = new ArrayList<Long>();
                for (var row : jdbc.query("SELECT _rowid_ FROM t ORDER BY random() LIMIT 300")) {
                    var rowid = (Long) row.getFirst();
                    rowids.add(rowid);
                    rowids.add(rowid + 1);
                    rowids.add(rowid - 1);
                }
                for (var i = 0; i < 100; i++) {
                    rowids.add(random.nextLong());
                }
                rowids.addAll(List.of(Long.MIN_VALUE, Long.MAX_VALUE, 0L, -1L, 1L, Long.MIN_VALUE + 1, Long.MAX_VALUE - 1));
                for (var rowid : rowids) {
                    var expected = jdbc.query("SELECT _rowid_, * FROM t WHERE _rowid_ = ?", rowid);
                    var found = table.findByRowid(rowid);
                    var actual = new ArrayList<List<Object>>();
                    found.ifPresent(row -> {
                        var values = new ArrayList<>();
                        values.add(row.rowid());
                        values.addAll(SqliteTesting.values(row));
                        actual.add(values);
                    });
                    assertEquals(expected, actual, "rowid " + rowid);
                }
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.viridian.sqlite.SqliteTesting#encodings")
        @DisplayName("seek a rowid table by rowid and a WITHOUT ROWID table by primary key")
        void seek(String encoding) throws IOException {
            var file = create(encoding);
            var random = new Random(47);
            var values = candidates(random);
            try (var database = SqliteDatabase.open(file); var jdbc = Jdbc.open(file)) {
                var rowidTable = database.findTable("t").orElseThrow();
                var keyed = database.findTable("w").orElseThrow();
                for (var round = 0; round < 150; round++) {
                    var rowid = random.nextLong();
                    var expected = jdbc.query("SELECT _rowid_, * FROM t WHERE _rowid_ >= ? ORDER BY _rowid_ LIMIT 20", rowid);
                    var actual = new ArrayList<List<Object>>();
                    try (var cursor = rowidTable.seek(List.of(rowid))) {
                        while (actual.size() < 20 && cursor.next()) {
                            var row = new ArrayList<>();
                            row.add(cursor.row().rowid());
                            row.addAll(SqliteTesting.values(cursor.row()));
                            actual.add(row);
                        }
                    }
                    SqliteTesting.assertRows(expected, actual, "seek of t to " + rowid);
                    List<?> key = round % 2 == 0
                            ? Arrays.asList(values.get(random.nextInt(values.size())))
                            : Arrays.asList(values.get(random.nextInt(values.size())), (long) random.nextInt(4000));
                    var parameters = new ArrayList<>();
                    var condition = atLeast(List.of("a", "b").subList(0, key.size()), key, parameters);
                    var keyedExpected = jdbc.query("SELECT * FROM w WHERE " + condition + " ORDER BY a, b LIMIT 20", parameters.toArray());
                    var keyedActual = new ArrayList<List<Object>>();
                    try (var cursor = keyed.seek(key)) {
                        while (keyedActual.size() < 20 && cursor.next()) {
                            keyedActual.add(SqliteTesting.values(cursor.row()));
                        }
                    }
                    SqliteTesting.assertRows(keyedExpected, keyedActual, "seek of w to " + key);
                }
            }
        }
    }
}
