package com.github.auties00.viridian.sqlite;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Shared fixtures for the SQLite reader tests: a JDBC connection to the reference implementation, reached
/// reflectively because the module under test does not read `java.sql`, and helpers that turn rows from either side
/// into comparable lists and compare a whole database with SQLite's own view of it.
final class SqliteTesting {
    private SqliteTesting() {
    }

    /// The text encodings, with the `PRAGMA encoding` value that selects each.
    static final List<String> ENCODINGS = List.of("UTF-8", "UTF-16le", "UTF-16be");

    /// {@return [#ENCODINGS], as a parameter source}
    static List<String> encodings() {
        return ENCODINGS;
    }

    /// A BLOB compared by content, so that rows holding one compare with `equals`.
    record Blob(byte[] bytes) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Blob blob && Arrays.equals(bytes, blob.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }

        @Override
        public String toString() {
            var hex = HexFormat.of().formatHex(bytes, 0, Math.min(bytes.length, 32));
            return "x'" + hex + (bytes.length > 32 ? "...' (" + bytes.length + " bytes)" : "'");
        }
    }

    /// {@return `value` in comparable form: `Integer` widened to `Long`, `byte[]` wrapped in a [Blob]}
    static Object normalize(Object value) {
        return switch (value) {
            case Integer integer -> integer.longValue();
            case byte[] bytes -> new Blob(bytes);
            case null, default -> value;
        };
    }

    /// {@return the values of `row`, normalized}
    static List<Object> values(SqliteRow row) {
        var values = new ArrayList<>(row.size());
        for (var i = 0; i < row.size(); i++) {
            values.add(normalize(row.get(i)));
        }
        return values;
    }

    /// {@return every remaining row of `cursor`, normalized, closing it}
    static List<List<Object>> read(SqliteCursor cursor) throws IOException {
        try (cursor) {
            var rows = new ArrayList<List<Object>>();
            while (cursor.next()) {
                rows.add(values(cursor.row()));
            }
            return rows;
        }
    }

    /// {@return every remaining row of `cursor`, normalized and preceded by its rowid, closing it}
    static List<List<Object>> readWithRowid(SqliteCursor cursor) throws IOException {
        try (cursor) {
            var rows = new ArrayList<List<Object>>();
            while (cursor.next()) {
                var row = new ArrayList<>();
                row.add(cursor.row().rowid());
                row.addAll(values(cursor.row()));
                rows.add(row);
            }
            return rows;
        }
    }

    /// Copies `file` and those of its `-wal` and `-journal` siblings that exist, and its `-shm` if `shm` is set,
    /// into `directory`.
    ///
    /// @return the copy of `file`
    static Path copyDatabase(Path file, Path directory, boolean shm) throws IOException {
        Files.createDirectories(directory);
        var target = directory.resolve(file.getFileName());
        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
        for (var suffix : shm ? List.of("-wal", "-journal", "-shm") : List.of("-wal", "-journal")) {
            var sibling = SqliteBytes.sibling(file, suffix);
            if (Files.exists(sibling)) {
                Files.copy(sibling, SqliteBytes.sibling(target, suffix), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return target;
    }

    /// The values whose storage covers every serial type: integers of each width and at each width's edges, the
    /// constants 0 and 1, reals, empty and multi-page text and BLOBs, and `NULL`.
    static List<Object> specialValues() {
        var random = new Random(7);
        var values = new ArrayList<>();
        for (var value : new long[]{0, 1, -1, 2, 127, 128, -128, -129, 255, 32767, 32768, -32768, -32769, 1 << 23, (1 << 23) - 1,
                -(1 << 23), -(1 << 23) - 1, 1L << 31, (1L << 31) - 1, -(1L << 31), -(1L << 31) - 1, 1L << 47, (1L << 47) - 1,
                -(1L << 47), -(1L << 47) - 1, Long.MIN_VALUE, Long.MAX_VALUE}) {
            values.add(value);
        }
        values.add(0.0);
        values.add(-0.0);
        values.add(1.5);
        values.add(-2.25);
        values.add(3.0);
        values.add(1e300);
        values.add(-1e-300);
        values.add(Double.MIN_VALUE);
        values.add(Double.POSITIVE_INFINITY);
        values.add(Double.NEGATIVE_INFINITY);
        values.add(9.223372036854775807E18);
        values.add("");
        values.add("text");
        values.add("na" + (char) 0xEF + "ve " + Character.toString(0x1F600) + " " + (char) 0xFF5E);
        values.add(randomText(random, 70_000));
        values.add(new byte[0]);
        values.add(new byte[]{0, 1, 2, (byte) 0xFF});
        values.add(randomBytes(random, 150_000));
        values.add(null);
        return values;
    }

    /// {@return `name` as a quoted SQL identifier}
    static String quote(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    /// Asserts that every table and index the reader can read returns what JDBC returns, in the same order, and that
    /// the schema, the columns and the index columns match SQLite's own description of them.
    static void assertMatchesJdbc(SqliteDatabase database, Jdbc jdbc) throws IOException {
        var expectedSchema = jdbc.query("SELECT type, name, tbl_name, rootpage, sql FROM sqlite_schema ORDER BY rowid");
        var actualSchema = new ArrayList<List<Object>>();
        for (var entry : database.schema()) {
            actualSchema.add(Arrays.asList(entry.type(), entry.name(), entry.tableName(), entry.rootPage(), entry.sql()));
        }
        assertEquals(expectedSchema, actualSchema, "sqlite_schema");
        for (var entry : database.schema()) {
            if (entry.type().equals("table") && !entry.sql().startsWith("CREATE VIRTUAL")) {
                assertTableMatches(database, jdbc, entry.name());
            }
        }
        for (var entry : database.schema()) {
            if (entry.type().equals("index")) {
                assertIndexMatches(database, jdbc, entry.name(), entry.sql());
            }
        }
    }

    /// Asserts that table `name` reads as JDBC reads it.
    static void assertTableMatches(SqliteDatabase database, Jdbc jdbc, String name) throws IOException {
        var table = database.findTable(name).orElseThrow();
        var info = jdbc.query("SELECT name, type, hidden FROM pragma_table_xinfo(?)", name);
        var expectedColumns = new ArrayList<List<Object>>();
        var virtual = false;
        for (var column : info) {
            expectedColumns.add(List.of(column.get(0), column.get(1)));
            virtual |= (Long) column.get(2) == 2;
        }
        var actualColumns = new ArrayList<List<Object>>();
        for (var column : table.columns()) {
            actualColumns.add(List.of(column.name(), column.declaredType()));
        }
        assertEquals(expectedColumns, actualColumns, "columns of " + name);
        if (virtual) {
            assertThrows(IOException.class, table::scan, "scan of " + name);
            return;
        }
        if (table.withoutRowid()) {
            var primaryKey = (String) jdbc.value("SELECT name FROM pragma_index_list(?) WHERE origin = 'pk'", name);
            var expected = jdbc.query("SELECT * FROM " + quote(name) + " ORDER BY " + orderBy(jdbc, primaryKey, true));
            assertRows(expected, read(table.scan()), "rows of " + name);
        } else {
            var expected = jdbc.query("SELECT _rowid_, * FROM " + quote(name) + " ORDER BY _rowid_");
            assertRows(expected, readWithRowid(table.scan()), "rows of " + name);
        }
    }

    /// Asserts that index `name`, created by `sql` or by a constraint if it is `null`, reads as JDBC orders it.
    static void assertIndexMatches(SqliteDatabase database, Jdbc jdbc, String name, String sql) throws IOException {
        var index = database.findIndex(name).orElseThrow();
        var keyColumns = jdbc.query("SELECT cid, name FROM pragma_index_xinfo(?) WHERE key = 1 ORDER BY seqno", name);
        var expectedColumns = new ArrayList<>();
        for (var i = 0; i < keyColumns.size(); i++) {
            var column = keyColumns.get(i);
            expectedColumns.add((Long) column.get(0) == -2 ? index.columns().get(i) : column.get(1));
        }
        assertEquals(expectedColumns, index.columns(), "columns of " + name);
        var fields = jdbc.query("SELECT cid, name FROM pragma_index_xinfo(?) ORDER BY seqno", name);
        var select = new ArrayList<String>();
        for (var i = 0; i < fields.size(); i++) {
            var cid = (Long) fields.get(i).get(0);
            select.add(cid == -1 ? "_rowid_" : cid == -2 ? "(" + index.columns().get(i) + ")" : quote((String) fields.get(i).get(1)));
        }
        var where = "";
        if (sql != null) {
            var position = sql.toUpperCase().lastIndexOf(" WHERE ");
            if (position >= 0) {
                where = " WHERE " + sql.substring(position + 7);
            }
        }
        var expected = jdbc.query("SELECT " + String.join(", ", select) + " FROM " + quote(index.tableName()) + where
                + " ORDER BY " + orderBy(jdbc, name, false).formatted(select.toArray()));
        assertRows(expected, read(index.scan()), "entries of " + name);
    }

    /// Asserts that `actual` equals `expected`, reporting only the first row that differs.
    static void assertRows(List<List<Object>> expected, List<List<Object>> actual, String what) {
        for (var i = 0; i < Math.min(expected.size(), actual.size()); i++) {
            if (!expected.get(i).equals(actual.get(i))) {
                assertEquals(expected.get(i), actual.get(i), what + ", row " + i);
            }
        }
        assertEquals(expected.size(), actual.size(), what + ": row count");
    }

    /// {@return the `ORDER BY` terms that sort as index `index` does}
    ///
    /// @param keyOnly whether to sort by the key columns only, naming them, rather than by every field through `%s`
    ///                placeholders for the selected expressions
    private static String orderBy(Jdbc jdbc, String index, boolean keyOnly) {
        var fields = jdbc.query("SELECT name, desc, coll, key FROM pragma_index_xinfo(?) ORDER BY seqno", index);
        var terms = new ArrayList<String>();
        for (var field : fields) {
            if (keyOnly && (Long) field.get(3) == 0) {
                continue;
            }
            var term = keyOnly ? quote((String) field.get(0)) : "%s";
            terms.add(term + " COLLATE " + field.get(2) + ((Long) field.get(1) == 1 ? " DESC" : ""));
        }
        return String.join(", ", terms);
    }

    /// {@return a random string of `length` characters, mixing ASCII, accented letters and supplementary characters}
    static String randomText(Random random, int length) {
        var builder = new StringBuilder(length);
        while (builder.length() < length) {
            var kind = random.nextInt(10);
            if (kind < 6) {
                builder.append((char) ('a' + random.nextInt(26)));
            } else if (kind < 8) {
                builder.append((char) (0xC0 + random.nextInt(0x100)));
            } else if (kind < 9) {
                builder.append((char) (0xFF00 + random.nextInt(0xF0)));
            } else {
                builder.appendCodePoint(0x1F600 + random.nextInt(0x40));
            }
        }
        return builder.toString();
    }

    /// {@return `length` random bytes}
    static byte[] randomBytes(Random random, int length) {
        var bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }

    /// A JDBC connection to a SQLite database through the xerial driver.
    static final class Jdbc implements AutoCloseable {
        private static final Method GET_CONNECTION;
        private static final Method CREATE_STATEMENT;
        private static final Method PREPARE_STATEMENT;
        private static final Method CLOSE_CONNECTION;
        private static final Method EXECUTE;
        private static final Method CLOSE_STATEMENT;
        private static final Method SET_OBJECT;
        private static final Method EXECUTE_UPDATE;
        private static final Method EXECUTE_QUERY;
        private static final Method ADD_BATCH;
        private static final Method EXECUTE_BATCH;
        private static final Method NEXT;
        private static final Method GET_OBJECT;
        private static final Method GET_META_DATA;
        private static final Method GET_COLUMN_COUNT;
        private static final Method CLOSE_RESULT;

        static {
            try {
                Class.forName("org.sqlite.JDBC");
                var driverManager = Class.forName("java.sql.DriverManager");
                var connection = Class.forName("java.sql.Connection");
                var statement = Class.forName("java.sql.Statement");
                var prepared = Class.forName("java.sql.PreparedStatement");
                var resultSet = Class.forName("java.sql.ResultSet");
                var metaData = Class.forName("java.sql.ResultSetMetaData");
                GET_CONNECTION = driverManager.getMethod("getConnection", String.class);
                CREATE_STATEMENT = connection.getMethod("createStatement");
                PREPARE_STATEMENT = connection.getMethod("prepareStatement", String.class);
                CLOSE_CONNECTION = connection.getMethod("close");
                EXECUTE = statement.getMethod("execute", String.class);
                CLOSE_STATEMENT = statement.getMethod("close");
                SET_OBJECT = prepared.getMethod("setObject", int.class, Object.class);
                EXECUTE_UPDATE = prepared.getMethod("executeUpdate");
                EXECUTE_QUERY = prepared.getMethod("executeQuery");
                ADD_BATCH = prepared.getMethod("addBatch");
                EXECUTE_BATCH = statement.getMethod("executeBatch");
                NEXT = resultSet.getMethod("next");
                GET_OBJECT = resultSet.getMethod("getObject", int.class);
                GET_META_DATA = resultSet.getMethod("getMetaData");
                GET_COLUMN_COUNT = metaData.getMethod("getColumnCount");
                CLOSE_RESULT = resultSet.getMethod("close");
            } catch (ReflectiveOperationException exception) {
                throw new ExceptionInInitializerError(exception);
            }
        }

        private final Object connection;

        private Jdbc(Object connection) {
            this.connection = connection;
        }

        static Jdbc open(Path file) {
            return new Jdbc(call(GET_CONNECTION, null, "jdbc:sqlite:" + file.toAbsolutePath()));
        }

        /// Runs each statement in turn.
        Jdbc execute(String... statements) {
            for (var sql : statements) {
                var statement = call(CREATE_STATEMENT, connection);
                try {
                    call(EXECUTE, statement, sql);
                } finally {
                    call(CLOSE_STATEMENT, statement);
                }
            }
            return this;
        }

        /// Runs `sql` with `parameters` bound, returning the number of rows changed.
        int update(String sql, Object... parameters) {
            var statement = call(PREPARE_STATEMENT, connection, sql);
            try {
                bind(statement, parameters);
                return (Integer) call(EXECUTE_UPDATE, statement);
            } finally {
                call(CLOSE_STATEMENT, statement);
            }
        }

        /// Runs `sql` once per parameter row, in one transaction.
        void batch(String sql, List<Object[]> rows) {
            execute("BEGIN");
            var statement = call(PREPARE_STATEMENT, connection, sql);
            try {
                for (var row : rows) {
                    bind(statement, row);
                    call(ADD_BATCH, statement);
                }
                call(EXECUTE_BATCH, statement);
            } finally {
                call(CLOSE_STATEMENT, statement);
            }
            execute("COMMIT");
        }

        /// {@return the rows `sql` returns with `parameters` bound, normalized}
        List<List<Object>> query(String sql, Object... parameters) {
            var statement = call(PREPARE_STATEMENT, connection, sql);
            try {
                bind(statement, parameters);
                var result = call(EXECUTE_QUERY, statement);
                try {
                    var columns = (Integer) call(GET_COLUMN_COUNT, call(GET_META_DATA, result));
                    var rows = new ArrayList<List<Object>>();
                    while ((Boolean) call(NEXT, result)) {
                        var row = new ArrayList<>(columns);
                        for (var i = 1; i <= columns; i++) {
                            row.add(normalize(call(GET_OBJECT, result, i)));
                        }
                        rows.add(row);
                    }
                    return rows;
                } finally {
                    call(CLOSE_RESULT, result);
                }
            } finally {
                call(CLOSE_STATEMENT, statement);
            }
        }

        /// {@return the single value `sql` returns}
        Object value(String sql, Object... parameters) {
            return query(sql, parameters).getFirst().getFirst();
        }

        private static void bind(Object statement, Object[] parameters) {
            for (var i = 0; i < parameters.length; i++) {
                call(SET_OBJECT, statement, i + 1, parameters[i]);
            }
        }

        @Override
        public void close() {
            call(CLOSE_CONNECTION, connection);
        }

        private static Object call(Method method, Object target, Object... arguments) {
            try {
                return method.invoke(target, arguments);
            } catch (InvocationTargetException exception) {
                throw new IllegalStateException("JDBC failed: " + exception.getCause().getMessage(), exception.getCause());
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }
}
