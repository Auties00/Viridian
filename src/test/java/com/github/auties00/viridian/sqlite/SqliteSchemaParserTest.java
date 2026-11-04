package com.github.auties00.viridian.sqlite;

import com.github.auties00.viridian.sqlite.SqliteSchemaParser.Generated;
import com.github.auties00.viridian.sqlite.SqliteSqlTokenizer.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Unit tests of the SQL tokenizer, the `CREATE` statement parser and the type-affinity rules, on inputs the
/// oracle tests do not reach: malformed statements must fail with an [IOException] and nothing else.
@DisplayName("Schema statements")
class SqliteSchemaParserTest {
    @Nested
    @DisplayName("Tokenizer")
    class Tokenizer {
        @Test
        @DisplayName("reads quoted identifiers, strings, numbers, BLOBs and comments")
        void tokens() throws IOException {
            var tokens = SqliteSqlTokenizer.tokenize("\"a\"\"b\" [c d] `e``f` 'g''h' 1_000.5e-3 0x1F x'00ff' -- note\n /* block */ ?1 ->> ;");
            assertEquals(List.of(Kind.QUOTED, Kind.QUOTED, Kind.QUOTED, Kind.STRING, Kind.NUMBER, Kind.NUMBER, Kind.BLOB,
                    Kind.VARIABLE, Kind.SYMBOL, Kind.SYMBOL, Kind.END), tokens.stream().map(SqliteSqlTokenizer.Token::kind).toList());
            assertEquals(List.of("a\"b", "c d", "e`f", "g'h", "1000.5e-3", "0x1F", "00ff", "?1", "->>", ";", ""),
                    tokens.stream().map(SqliteSqlTokenizer.Token::text).toList());
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"'open", "\"open", "[open", "`open", "x'0", "x'zz'"})
        @DisplayName("rejects unterminated and malformed tokens")
        void malformed(String sql) {
            assertThrows(IOException.class, () -> SqliteSqlTokenizer.tokenize(sql));
        }
    }

    @Nested
    @DisplayName("CREATE TABLE")
    class CreateTable {
        @Test
        @DisplayName("extracts columns, types, collations, defaults and generated columns")
        void columns() throws IOException {
            var table = SqliteSchemaParser.parseTable("""
                    CREATE TEMP TABLE IF NOT EXISTS main."t" (
                        a integer PRIMARY KEY DESC,
                        b VARCHAR ( 10 ) COLLATE NOCASE DEFAULT 'x' NOT NULL,
                        "c" "text",
                        d AS (a * 2) STORED,
                        e GENERATED ALWAYS AS (b || 'x'),
                        f UNSIGNED BIG INT DEFAULT -5 CHECK (f > (0)),
                        UNIQUE (b, "c" COLLATE BINARY), FOREIGN KEY (f) REFERENCES other ON DELETE SET DEFAULT
                    ) STRICT, WITHOUT ROWID""");
            assertEquals("t", table.name());
            assertTrue(table.strict());
            assertTrue(table.withoutRowid());
            var columns = table.columns();
            assertEquals(List.of("a", "b", "c", "d", "e", "f"), columns.stream().map(SqliteSchemaParser.Column::name).toList());
            assertEquals(List.of("INTEGER", "VARCHAR ( 10 )", "TEXT", "", "", "UNSIGNED BIG INT"),
                    columns.stream().map(SqliteSchemaParser.Column::declaredType).toList());
            assertEquals("NOCASE", columns.get(1).collation());
            assertEquals(Generated.STORED, columns.get(3).generated());
            assertEquals(Generated.VIRTUAL, columns.get(4).generated());
            assertEquals(SqliteSchemaParser.LiteralKind.INTEGER, columns.get(5).defaultValue().kind());
            assertEquals(1, columns.get(5).defaultValue().negations());
            assertEquals(2, table.keys().size());
            assertTrue(table.keys().getFirst().descendingInColumn());
            assertEquals("BINARY", table.keys().getLast().terms().getLast().collation());
            assertEquals(-1, table.rowidAlias());
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {
                "CREATE TABLE t(id INTEGER PRIMARY KEY)|0",
                "CREATE TABLE t(x, id INTEGER, PRIMARY KEY(id DESC))|1",
                "CREATE TABLE t(id INTEGER PRIMARY KEY DESC)|-1",
                "CREATE TABLE t(id INTEGER PRIMARY KEY) WITHOUT ROWID|-1",
                "CREATE TABLE t(id INT PRIMARY KEY)|-1",
                "CREATE TABLE t(id [INTEGER] PRIMARY KEY)|0",
                "CREATE TABLE t(id INTEGER, PRIMARY KEY('id'))|0",
        })
        @DisplayName("finds the rowid alias")
        void rowidAlias(String sql, int alias) throws IOException {
            assertEquals(alias, SqliteSchemaParser.parseTable(sql).rowidAlias());
        }

        @Test
        @DisplayName("recognises a virtual table")
        void virtual() throws IOException {
            var table = SqliteSchemaParser.parseTable("CREATE VIRTUAL TABLE docs USING fts5(body, tokenize = 'porter')");
            assertTrue(table.virtual());
            assertEquals("docs", table.name());
        }

        @Test
        @DisplayName("reads every literal form of DEFAULT")
        void defaults() throws IOException {
            var table = SqliteSchemaParser.parseTable(
                    "CREATE TABLE t(a DEFAULT x'0aff', b DEFAULT (('s')), c DEFAULT +1.5, d DEFAULT CURRENT_TIME, e DEFAULT (1 + 2), f DEFAULT bare, g DEFAULT NULL)");
            var values = table.columns().stream().map(SqliteSchemaParser.Column::defaultValue).toList();
            assertArrayEquals(new byte[]{0x0A, (byte) 0xFF}, values.get(0).blob());
            assertEquals("s", values.get(1).text());
            assertEquals(SqliteSchemaParser.LiteralKind.REAL, values.get(2).kind());
            assertEquals(SqliteSchemaParser.LiteralKind.NONE, values.get(3).kind());
            assertEquals(SqliteSchemaParser.LiteralKind.NONE, values.get(4).kind());
            assertEquals("bare", values.get(5).text());
            assertEquals(SqliteSchemaParser.LiteralKind.NULL, values.get(6).kind());
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"", "garbage", "CREATE", "CREATE TABLE", "CREATE TABLE t", "CREATE TABLE t(", "CREATE TABLE t(a",
                "CREATE TABLE t(a,", "CREATE TABLE t(a CHECK (", "CREATE TABLE t(a) WITHOUT", "CREATE TABLE t AS SELECT 1",
                "CREATE TABLE t(a, PRIMARY KEY(a + 1))", "CREATE TABLE t(a, PRIMARY)", "CREATE TABLE t((a))", "CREATE TABLE t(a DEFAULT"})
        @DisplayName("rejects malformed statements with an IOException")
        void malformed(String sql) {
            assertThrows(IOException.class, () -> SqliteSchemaParser.parseTable(sql));
        }

        @Test
        @DisplayName("tolerates what SQLite would have rejected but that does not change the layout")
        void tolerated() throws IOException {
            assertEquals(2, SqliteSchemaParser.parseTable("CREATE TABLE t(a, b) trailing words").columns().size());
            assertEquals(SqliteSchemaParser.LiteralKind.NONE, SqliteSchemaParser.parseTable("CREATE TABLE t(a DEFAULT)").columns().getFirst().defaultValue().kind());
            assertEquals("GENERATED", SqliteSchemaParser.parseTable("CREATE TABLE t(a GENERATED)").columns().getFirst().declaredType());
            assertTrue(SqliteSchemaParser.parseTable("CREATE TABLE t(a, CONSTRAINT c)").keys().isEmpty());
        }
    }

    @Nested
    @DisplayName("CREATE INDEX")
    class CreateIndex {
        @Test
        @DisplayName("separates column terms from expressions and reads their order and collation")
        void terms() throws IOException {
            var index = SqliteSchemaParser.parseIndex(
                    "CREATE UNIQUE INDEX IF NOT EXISTS main.i ON t (a, \"b\" COLLATE NOCASE DESC, lower(c) ASC, (d), e + 1, 'f') WHERE a > 0");
            assertTrue(index.unique());
            assertTrue(index.partial());
            assertEquals("i", index.name());
            assertEquals("t", index.table());
            var terms = index.terms();
            assertEquals("a", terms.get(0).column());
            assertEquals("b", terms.get(1).column());
            assertEquals("NOCASE", terms.get(1).collation());
            assertTrue(terms.get(1).descending());
            assertNull(terms.get(2).column());
            assertEquals("lower(c)", terms.get(2).expression());
            assertFalse(terms.get(2).descending());
            assertEquals("d", terms.get(3).column());
            assertNull(terms.get(4).column());
            assertEquals("f", terms.get(5).column());
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"CREATE INDEX", "CREATE INDEX i", "CREATE INDEX i ON", "CREATE INDEX i ON t", "CREATE INDEX i ON t(",
                "CREATE INDEX i ON t(a", "CREATE INDEX i ON t()", "CREATE INDEX i ON t(a,)", "CREATE TABLE t(a)"})
        @DisplayName("rejects malformed statements with an IOException")
        void malformed(String sql) {
            assertThrows(IOException.class, () -> SqliteSchemaParser.parseIndex(sql));
        }
    }

    @Nested
    @DisplayName("Type affinity")
    class Affinity {
        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "INT, INTEGER", "TINYINT, INTEGER", "BIGINT, INTEGER", "UNSIGNED BIG INT, INTEGER", "INT8, INTEGER",
                "FLOATING POINT, INTEGER", "CHARINT, INTEGER",
                "CHARACTER(20), TEXT", "VARCHAR(255), TEXT", "NATIVE CHARACTER(70), TEXT", "NVARCHAR(100), TEXT", "CLOB, TEXT",
                "TEXTBLOB, TEXT", "LONGVARCHAR, TEXT",
                "BLOB, BLOB", "REALBLOB, BLOB",
                "DOUBLE, REAL", "DOUBLE PRECISION, REAL", "FLOAT, REAL", "REAL, REAL",
                "NUMERIC, NUMERIC", "'DECIMAL(10,5)', NUMERIC", "BOOLEAN, NUMERIC", "DATETIME, NUMERIC", "STRING, NUMERIC",
        })
        @DisplayName("follows SQLite's rules for custom type names")
        void rules(String type, SqliteAffinity affinity) {
            assertEquals(affinity, SqliteAffinity.ofTypeName(type));
        }
    }
}
