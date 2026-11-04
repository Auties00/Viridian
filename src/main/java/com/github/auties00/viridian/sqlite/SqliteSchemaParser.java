package com.github.auties00.viridian.sqlite;

import com.github.auties00.viridian.sqlite.SqliteSqlTokenizer.Kind;
import com.github.auties00.viridian.sqlite.SqliteSqlTokenizer.Token;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/// Parses the `CREATE TABLE` and `CREATE INDEX` statements of `sqlite_schema` into what reading and writing their
/// b-trees needs.
///
/// The parser is tolerant: SQLite has already accepted every statement a reader sees, so it extracts the columns,
/// their types, collations, defaults, `NOT NULL` constraints and generated-column kinds, the `PRIMARY KEY` and
/// `UNIQUE` constraints, the table options, and whether the statement has `CHECK` constraints or conflict clauses that
/// let a violating insert succeed, and skips everything else (the `CHECK` expressions themselves, foreign-key clauses,
/// constraint names) without interpreting it.
///
/// @spec https://www.sqlite.org/lang_createtable.html CREATE TABLE
/// @spec https://www.sqlite.org/lang_createindex.html CREATE INDEX
final class SqliteSchemaParser {
    /// The type names SQLite recognises exactly, which it reports in upper case and which STRICT tables require.
    private static final List<String> STANDARD_TYPES = List.of("ANY", "BLOB", "INT", "INTEGER", "REAL", "TEXT");

    /// The keywords that end a column's type and start one of its constraints.
    private static final Set<String> COLUMN_CONSTRAINTS = Set.of(
            "constraint", "primary", "not", "null", "unique", "check", "default", "collate", "references", "as");

    /// The keywords that start a table constraint, which no bare column name can be.
    private static final Set<String> TABLE_CONSTRAINTS = Set.of("constraint", "primary", "unique", "check", "foreign");

    /// Whether a generated column's value occupies a record field.
    enum Generated {
        /// An ordinary column.
        NONE,
        /// A `STORED` generated column, whose value is stored like an ordinary column's.
        STORED,
        /// A `VIRTUAL` generated column, which has no record field and is computed when read.
        VIRTUAL
    }

    /// A parsed column.
    ///
    /// @param name           the column's name
    /// @param declaredType   the type as SQLite reports it, see [SqliteColumn#declaredType()]
    /// @param integerType    whether the type is exactly `INTEGER`, the condition for a rowid alias
    /// @param collation      the `COLLATE` constraint's collation, or `null` for the default `BINARY`
    /// @param defaultValue   the `DEFAULT` constraint's value, or [Literal#NONE]
    /// @param generated      whether and how the column is generated
    /// @param affinity       the column's type affinity
    /// @param notNull        whether the column has a `NOT NULL` constraint
    record Column(String name, String declaredType, boolean integerType, String collation, Literal defaultValue,
                  Generated generated, SqliteAffinity affinity, boolean notNull) {
    }

    /// A term of a key: a column of a `PRIMARY KEY`, `UNIQUE` or `CREATE INDEX` list.
    ///
    /// @param column     the column's name if the term is one, or `null` if it is an expression
    /// @param expression the term as written, without its `COLLATE` and sort order
    /// @param collation  the term's own `COLLATE`, or `null` for the column's
    /// @param descending whether the term is `DESC`
    record Term(String column, String expression, String collation, boolean descending) {
    }

    /// A `PRIMARY KEY` or `UNIQUE` constraint, in the order the statement declares it.
    ///
    /// @param primaryKey         whether the constraint is the primary key
    /// @param terms              the constrained columns
    /// @param inColumn           whether the constraint is part of a column definition rather than a table constraint
    /// @param descendingInColumn whether a column's `PRIMARY KEY` is followed by `DESC`, which keeps an `INTEGER`
    ///                           column from being a rowid alias
    /// @param autoincrement      whether the `PRIMARY KEY` is marked `AUTOINCREMENT`
    record KeyConstraint(boolean primaryKey, List<Term> terms, boolean inColumn, boolean descendingInColumn,
                         boolean autoincrement) {
    }

    /// A parsed `CREATE TABLE` or `CREATE VIRTUAL TABLE` statement.
    ///
    /// @param name         the table's name
    /// @param columns      the columns in declaration order; empty for a virtual table
    /// @param keys         the `PRIMARY KEY` and `UNIQUE` constraints in declaration order
    /// @param withoutRowid whether the table is `WITHOUT ROWID`
    /// @param strict       whether the table is `STRICT`
    /// @param virtual      whether the statement is `CREATE VIRTUAL TABLE`
    /// @param checks       whether the table or one of its columns has a `CHECK` constraint
    /// @param lenient      whether a conflict clause resolves a violation with `REPLACE` or `IGNORE`, so that an insert
    ///                     violating the constraint does not fail
    record Table(String name, List<Column> columns, List<KeyConstraint> keys, boolean withoutRowid, boolean strict,
                 boolean virtual, boolean checks, boolean lenient) {
        /// {@return the primary key constraint, or `null` if the table declares none}
        KeyConstraint primaryKey() {
            for (var key : keys) {
                if (key.primaryKey()) {
                    return key;
                }
            }
            return null;
        }

        /// {@return the index of the column named `name`, compared as SQLite compares identifiers, or -1}
        int columnIndex(String name) {
            for (var i = 0; i < columns.size(); i++) {
                if (SqliteBytes.equalsIgnoreCase(columns.get(i).name(), name)) {
                    return i;
                }
            }
            return -1;
        }

        /// {@return whether the primary key is one `INTEGER` column not marked `DESC` in its column constraint}
        ///
        /// For a rowid table this makes the column the rowid alias; a `WITHOUT ROWID` table with such a key builds
        /// its primary-key index only once the statement ends, after every `UNIQUE` index, which shifts the numbers
        /// of the indexes SQLite names after the table.
        boolean integerPrimaryKey() {
            var key = primaryKey();
            if (key == null || key.terms().size() != 1 || key.inColumn() && key.descendingInColumn()) {
                return false;
            }
            var column = key.terms().getFirst().column();
            var index = column == null ? -1 : columnIndex(column);
            return index >= 0 && columns.get(index).integerType();
        }

        /// {@return the index of the column that aliases the rowid, or -1 if none does}
        int rowidAlias() {
            return withoutRowid || !integerPrimaryKey() ? -1 : columnIndex(primaryKey().terms().getFirst().column());
        }
    }

    /// A parsed `CREATE INDEX` statement.
    ///
    /// @param name    the index's name
    /// @param table   the indexed table's name
    /// @param unique  whether the index is `UNIQUE`
    /// @param terms   the indexed columns and expressions
    /// @param partial whether the index has a `WHERE` clause
    /// @param where   the `WHERE` clause's expression as written, without a trailing `;`, or `null` if there is none
    record Index(String name, String table, boolean unique, List<Term> terms, boolean partial, String where) {
    }

    /// A `DEFAULT` value as written, before the column's affinity converts it.
    ///
    /// @param kind      what the value is
    /// @param text      a number's or string's text
    /// @param blob      a BLOB's bytes
    /// @param negations how many unary minus signs precede the value
    record Literal(LiteralKind kind, String text, byte[] blob, int negations) {
        /// The default of a column with no `DEFAULT`, or with one that is not a literal.
        static final Literal NONE = new Literal(LiteralKind.NONE, null, null, 0);
    }

    /// The kinds of `DEFAULT` value.
    enum LiteralKind {
        /// Not a literal: no default, or an expression such as `CURRENT_TIMESTAMP` or `(1 + 2)`.
        NONE,
        /// `NULL`.
        NULL,
        /// An integer literal, decimal or hexadecimal.
        INTEGER,
        /// A literal with a fraction or an exponent.
        REAL,
        /// A string literal, or a bare identifier, which SQLite reads as one.
        STRING,
        /// A BLOB literal.
        BLOB,
        /// `TRUE`.
        TRUE,
        /// `FALSE`.
        FALSE
    }

    /// A column as it is being parsed.
    private static final class ColumnBuilder {
        /// The column's name.
        private final String name;

        /// The type as written, from its first token to its last.
        private String rawType = "";

        /// The `COLLATE` constraint's collation, or `null`.
        private String collation;

        /// The `DEFAULT` constraint's value.
        private Literal defaultValue = Literal.NONE;

        /// Whether and how the column is generated.
        private Generated generated = Generated.NONE;

        /// Whether the column has a `NOT NULL` constraint.
        private boolean notNull;

        /// Starts a column named `name`.
        private ColumnBuilder(String name) {
            this.name = name;
        }
    }

    /// The statement being parsed.
    private final String sql;

    /// The statement's tokens, ending with one of kind [Kind#END].
    private final List<Token> tokens;

    /// The index of the next token.
    private int position;

    /// Whether a `CHECK` constraint has been seen.
    private boolean checks;

    /// Whether a conflict clause naming `REPLACE` or `IGNORE` has been seen.
    private boolean lenient;

    /// Whether a key term parsed since this was last cleared was followed by `AUTOINCREMENT`.
    private boolean autoincrementTerm;

    /// Tokenizes `sql`.
    private SqliteSchemaParser(String sql) throws IOException {
        this.sql = sql;
        this.tokens = SqliteSqlTokenizer.tokenize(sql);
    }

    /// {@return the table that the `CREATE TABLE` or `CREATE VIRTUAL TABLE` statement `sql` defines}
    ///
    /// @throws IOException if `sql` is not such a statement
    static Table parseTable(String sql) throws IOException {
        return new SqliteSchemaParser(sql).table();
    }

    /// {@return the index that the `CREATE INDEX` statement `sql` defines}
    ///
    /// @throws IOException if `sql` is not such a statement
    static Index parseIndex(String sql) throws IOException {
        return new SqliteSchemaParser(sql).index();
    }

    /// Parses a `CREATE TABLE` statement.
    private Table table() throws IOException {
        expectWord("create");
        if (!acceptWord("temp")) {
            acceptWord("temporary");
        }
        if (acceptWord("virtual")) {
            expectWord("table");
            skipIfNotExists();
            return new Table(qualifiedName(), List.of(), List.of(), false, false, true, false, false);
        }
        expectWord("table");
        skipIfNotExists();
        var name = qualifiedName();
        if (isWord(peek(), "as")) {
            throw error("the statement is CREATE TABLE ... AS SELECT, which sqlite_schema never holds");
        }
        expectSymbol("(");
        var columns = new ArrayList<ColumnBuilder>();
        var keys = new ArrayList<KeyConstraint>();
        var inConstraints = false;
        while (true) {
            if (!inConstraints && peek().kind() == Kind.WORD && TABLE_CONSTRAINTS.contains(SqliteBytes.lowercase(peek().text()))) {
                inConstraints = true;
            }
            if (inConstraints) {
                tableConstraint(keys);
            } else {
                column(columns, keys);
            }
            if (acceptSymbol(",")) {
                continue;
            }
            if (acceptSymbol(")")) {
                break;
            }
            if (!inConstraints) {
                throw error("expected ',' or ')' after a column");
            }
        }
        var withoutRowid = false;
        var strict = false;
        while (true) {
            if (acceptWord("without")) {
                expectWord("rowid");
                withoutRowid = true;
            } else if (acceptWord("strict")) {
                strict = true;
            } else {
                break;
            }
            if (!acceptSymbol(",")) {
                break;
            }
        }
        var finished = new ArrayList<Column>(columns.size());
        for (var column : columns) {
            finished.add(finish(column, strict));
        }
        return new Table(name, List.copyOf(finished), List.copyOf(keys), withoutRowid, strict, false, checks, lenient);
    }

    /// Parses a `CREATE INDEX` statement.
    private Index index() throws IOException {
        expectWord("create");
        var unique = acceptWord("unique");
        expectWord("index");
        skipIfNotExists();
        var name = qualifiedName();
        expectWord("on");
        var table = name();
        var terms = terms(true);
        var partial = acceptWord("where");
        String where = null;
        if (partial) {
            var start = peek().start();
            var end = start;
            while (peek().kind() != Kind.END && !isSymbol(peek(), ";")) {
                end = next().end();
            }
            where = sql.substring(start, end);
        }
        return new Index(name, table, unique, terms, partial, where);
    }

    /// Parses a column definition, adding its `PRIMARY KEY` and `UNIQUE` constraints to `keys`.
    private void column(List<ColumnBuilder> columns, List<KeyConstraint> keys) throws IOException {
        var column = new ColumnBuilder(name());
        columns.add(column);
        var typeStart = -1;
        var typeEnd = -1;
        while (isTypeToken(peek())) {
            var token = next();
            if (typeStart < 0) {
                typeStart = token.start();
            }
            typeEnd = token.end();
        }
        if (typeStart >= 0 && isSymbol(peek(), "(")) {
            typeEnd = skipParentheses();
        }
        if (typeStart >= 0) {
            column.rawType = sql.substring(typeStart, typeEnd);
        }
        while (true) {
            var token = peek();
            if (token.kind() == Kind.END || isSymbol(token, ",") || isSymbol(token, ")")) {
                return;
            }
            if (token.kind() != Kind.WORD) {
                if (isSymbol(token, "(")) {
                    skipParentheses();
                } else {
                    next();
                }
                continue;
            }
            next();
            switch (SqliteBytes.lowercase(token.text())) {
                case "constraint" -> name();
                case "primary" -> {
                    expectWord("key");
                    var descending = acceptWord("desc");
                    if (!descending) {
                        acceptWord("asc");
                    }
                    conflictClause();
                    var autoincrement = acceptWord("autoincrement");
                    var term = new Term(column.name, column.name, null, descending);
                    keys.add(new KeyConstraint(true, List.of(term), true, descending, autoincrement));
                }
                case "not" -> {
                    if (acceptWord("null")) {
                        column.notNull = true;
                        conflictClause();
                    } else if (acceptWord("deferrable")) {
                        deferrableClause();
                    }
                }
                case "null" -> conflictClause();
                case "unique" -> {
                    conflictClause();
                    keys.add(new KeyConstraint(false, List.of(new Term(column.name, column.name, null, false)), true, false, false));
                }
                case "check" -> {
                    checks = true;
                    skipParentheses();
                }
                case "default" -> column.defaultValue = defaultValue();
                case "collate" -> column.collation = name();
                case "references" -> foreignKeyClause();
                case "generated" -> {
                    expectWord("always");
                    expectWord("as");
                    column.generated = generatedClause();
                }
                case "as" -> column.generated = generatedClause();
                default -> {
                }
            }
        }
    }

    /// Parses a table constraint, adding it to `keys` if it is a `PRIMARY KEY` or a `UNIQUE`.
    private void tableConstraint(List<KeyConstraint> keys) throws IOException {
        if (acceptWord("constraint")) {
            name();
            var next = peek();
            if (next.kind() == Kind.END || isSymbol(next, ",") || isSymbol(next, ")")) {
                return;
            }
        }
        if (acceptWord("primary")) {
            expectWord("key");
            autoincrementTerm = false;
            var terms = terms(false);
            keys.add(new KeyConstraint(true, terms, false, false, autoincrementTerm));
            conflictClause();
        } else if (acceptWord("unique")) {
            keys.add(new KeyConstraint(false, terms(false), false, false, false));
            conflictClause();
        } else if (acceptWord("check")) {
            checks = true;
            skipParentheses();
            conflictClause();
        } else if (acceptWord("foreign")) {
            expectWord("key");
            skipParentheses();
            expectWord("references");
            foreignKeyClause();
        } else {
            throw error("expected a table constraint");
        }
    }

    /// Parses a parenthesised list of key terms.
    ///
    /// @param expressions whether a term may be an expression, as in `CREATE INDEX` but not in a constraint
    private List<Term> terms(boolean expressions) throws IOException {
        expectSymbol("(");
        var terms = new ArrayList<Term>();
        while (true) {
            var start = position;
            var depth = 0;
            while (true) {
                var token = peek();
                if (token.kind() == Kind.END) {
                    throw error("the column list is never closed");
                }
                if (depth == 0 && (isSymbol(token, ",") || isSymbol(token, ")"))) {
                    break;
                }
                if (isSymbol(token, "(")) {
                    depth++;
                } else if (isSymbol(token, ")")) {
                    depth--;
                }
                next();
            }
            terms.add(term(start, position, expressions));
            if (!acceptSymbol(",")) {
                expectSymbol(")");
                return List.copyOf(terms);
            }
        }
    }

    /// {@return the key term made of the tokens in `[start, end)`}
    private Term term(int start, int end, boolean expressions) throws IOException {
        var last = end;
        if (last - start > 1 && isWord(tokens.get(last - 1), "autoincrement")) {
            autoincrementTerm = true;
            last--;
        }
        var descending = false;
        if (last - start > 1 && (isWord(tokens.get(last - 1), "asc") || isWord(tokens.get(last - 1), "desc"))) {
            descending = isWord(tokens.get(last - 1), "desc");
            last--;
        }
        String collation = null;
        if (last - start > 2 && isWord(tokens.get(last - 2), "collate") && isName(tokens.get(last - 1))) {
            collation = tokens.get(last - 1).text();
            last -= 2;
        }
        if (last == start) {
            throw error("a key term is empty");
        }
        var first = start;
        var stop = last;
        while (stop - first >= 3 && isSymbol(tokens.get(first), "(") && isSymbol(tokens.get(stop - 1), ")") && enclosed(first, stop - 1)) {
            first++;
            stop--;
        }
        var column = stop - first == 1 && isName(tokens.get(first)) ? tokens.get(first).text() : null;
        if (column == null && !expressions) {
            throw error("a PRIMARY KEY or UNIQUE constraint lists an expression");
        }
        var expression = sql.substring(tokens.get(start).start(), tokens.get(last - 1).end());
        return new Term(column, expression, collation, descending);
    }

    /// {@return whether the `(` token at `open` is closed by the `)` token at `close` rather than earlier}
    private boolean enclosed(int open, int close) {
        var depth = 0;
        for (var i = open; i < close; i++) {
            if (isSymbol(tokens.get(i), "(")) {
                depth++;
            } else if (isSymbol(tokens.get(i), ")") && --depth == 0) {
                return false;
            }
        }
        return depth == 1;
    }

    /// Parses the value of a `DEFAULT` constraint.
    ///
    /// SQLite accepts any constant expression there, but reads only literals, possibly signed or parenthesised, as a
    /// column's value when a record predates the column; anything else is [Literal#NONE].
    private Literal defaultValue() {
        var depth = 0;
        var negations = 0;
        while (true) {
            var token = peek();
            if (isSymbol(token, "(")) {
                depth++;
            } else if (isSymbol(token, "-")) {
                negations++;
            } else if (!isSymbol(token, "+")) {
                break;
            }
            next();
        }
        var token = peek();
        if (token.kind() == Kind.END || depth == 0 && (isSymbol(token, ",") || isSymbol(token, ")"))) {
            return Literal.NONE;
        }
        next();
        var literal = switch (token.kind()) {
            case NUMBER -> new Literal(isIntegerLiteral(token.text()) ? LiteralKind.INTEGER : LiteralKind.REAL, token.text(), null, negations);
            case STRING, QUOTED -> new Literal(LiteralKind.STRING, token.text(), null, negations);
            case BLOB -> new Literal(LiteralKind.BLOB, null, HexFormat.of().parseHex(token.text()), negations);
            case WORD -> switch (SqliteBytes.lowercase(token.text())) {
                case "null" -> new Literal(LiteralKind.NULL, null, null, negations);
                case "true" -> new Literal(LiteralKind.TRUE, null, null, negations);
                case "false" -> new Literal(LiteralKind.FALSE, null, null, negations);
                case "current_time", "current_date", "current_timestamp" -> Literal.NONE;
                default -> new Literal(LiteralKind.STRING, token.text(), null, negations);
            };
            default -> {
                if (isSymbol(token, ")")) {
                    depth--;
                }
                yield Literal.NONE;
            }
        };
        while (depth > 0) {
            var next = next();
            if (isSymbol(next, ")")) {
                depth--;
                continue;
            }
            literal = Literal.NONE;
            if (next.kind() == Kind.END) {
                break;
            }
            if (isSymbol(next, "(")) {
                depth++;
            }
        }
        return literal;
    }

    /// Parses the parenthesised expression and the storage of a generated column.
    private Generated generatedClause() throws IOException {
        skipParentheses();
        if (acceptWord("stored")) {
            return Generated.STORED;
        }
        acceptWord("virtual");
        return Generated.VIRTUAL;
    }

    /// Skips a foreign-key clause after its `REFERENCES` keyword.
    private void foreignKeyClause() throws IOException {
        name();
        if (isSymbol(peek(), "(")) {
            skipParentheses();
        }
        while (true) {
            if (acceptWord("on")) {
                next();
                if (!acceptWord("set")) {
                    acceptWord("no");
                }
                next();
            } else if (acceptWord("match")) {
                name();
            } else if (isWord(peek(), "not") && isWord(peek(1), "deferrable")) {
                next();
                next();
                deferrableClause();
            } else if (acceptWord("deferrable")) {
                deferrableClause();
            } else {
                return;
            }
        }
    }

    /// Skips what follows `DEFERRABLE`: an optional `INITIALLY DEFERRED` or `INITIALLY IMMEDIATE`.
    private void deferrableClause() {
        if (acceptWord("initially")) {
            next();
        }
    }

    /// Skips an `ON CONFLICT` clause, if one comes next, noting whether it lets a violating insert succeed.
    private void conflictClause() {
        if (isWord(peek(), "on") && isWord(peek(1), "conflict")) {
            next();
            next();
            var resolution = next();
            lenient |= isWord(resolution, "replace") || isWord(resolution, "ignore");
        }
    }

    /// Skips `IF NOT EXISTS`, if it comes next.
    private void skipIfNotExists() {
        if (isWord(peek(), "if") && isWord(peek(1), "not") && isWord(peek(2), "exists")) {
            next();
            next();
            next();
        }
    }

    /// Skips the parenthesised group that starts at the next token.
    ///
    /// @return the offset in the statement just past the closing parenthesis
    private int skipParentheses() throws IOException {
        expectSymbol("(");
        var depth = 1;
        while (true) {
            var token = next();
            if (token.kind() == Kind.END) {
                throw error("a parenthesis is never closed");
            }
            if (isSymbol(token, "(")) {
                depth++;
            } else if (isSymbol(token, ")") && --depth == 0) {
                return token.end();
            }
        }
    }

    /// {@return a name optionally qualified by a schema, without the schema}
    private String qualifiedName() throws IOException {
        var name = name();
        if (acceptSymbol(".")) {
            name = name();
        }
        return name;
    }

    /// {@return the identifier at the next token, bare, quoted or written as a string as SQLite allows}
    private String name() throws IOException {
        var token = peek();
        if (!isName(token)) {
            throw error("expected a name");
        }
        next();
        return token.text();
    }

    /// Converts a parsed column, whose table's `STRICT` option is now known, into its final form.
    private static Column finish(ColumnBuilder column, boolean strict) {
        var raw = column.rawType;
        if (raw.isEmpty()) {
            return new Column(column.name, "", false, column.collation, column.defaultValue, column.generated, SqliteAffinity.BLOB,
                    column.notNull);
        }
        var span = raw;
        if (span.length() >= 3) {
            span = dequoteToken(span);
            for (var standard : STANDARD_TYPES) {
                if (SqliteBytes.equalsIgnoreCase(span, standard)) {
                    var affinity = switch (standard) {
                        case "ANY" -> strict ? SqliteAffinity.BLOB : SqliteAffinity.NUMERIC;
                        case "BLOB" -> SqliteAffinity.BLOB;
                        case "INT", "INTEGER" -> SqliteAffinity.INTEGER;
                        case "REAL" -> SqliteAffinity.REAL;
                        default -> SqliteAffinity.TEXT;
                    };
                    return new Column(column.name, standard, standard.equals("INTEGER"), column.collation,
                            column.defaultValue, column.generated, affinity, column.notNull);
                }
            }
        }
        var declared = dequote(span);
        return new Column(column.name, declared, false, column.collation, column.defaultValue, column.generated,
                SqliteAffinity.ofTypeName(declared), column.notNull);
    }

    /// {@return `span` without its enclosing quotes if it is one quoted token with no quote inside}
    private static String dequoteToken(String span) {
        if (span.length() < 2 || !isQuote(span.charAt(0))) {
            return span;
        }
        for (var i = 1; i < span.length() - 1; i++) {
            if (isQuote(span.charAt(i))) {
                return span;
            }
        }
        return span.substring(1, span.length() - 1);
    }

    /// {@return the content of the quoted text that starts `text`, or `text` itself if it does not start with a quote}
    ///
    /// Like SQLite's own dequoting, this reads up to the closing quote and drops whatever follows it.
    private static String dequote(String text) {
        if (text.isEmpty() || !isQuote(text.charAt(0))) {
            return text;
        }
        var quote = text.charAt(0) == '[' ? ']' : text.charAt(0);
        var builder = new StringBuilder();
        for (var i = 1; i < text.length(); i++) {
            var character = text.charAt(i);
            if (character == quote) {
                if (i + 1 < text.length() && text.charAt(i + 1) == quote) {
                    builder.append(quote);
                    i++;
                } else {
                    break;
                }
            } else {
                builder.append(character);
            }
        }
        return builder.toString();
    }

    /// {@return whether `character` opens a quoted token}
    private static boolean isQuote(char character) {
        return character == '"' || character == '\'' || character == '`' || character == '[';
    }

    /// {@return whether a numeric literal's text is an integer, decimal or hexadecimal, rather than a real}
    private static boolean isIntegerLiteral(String text) {
        if (text.length() > 2 && text.charAt(0) == '0' && (text.charAt(1) == 'x' || text.charAt(1) == 'X')) {
            return true;
        }
        return text.indexOf('.') < 0 && text.indexOf('e') < 0 && text.indexOf('E') < 0;
    }

    /// {@return whether `token` can be a column's type: an identifier or a string, but no constraint keyword}
    private boolean isTypeToken(Token token) {
        return switch (token.kind()) {
            case QUOTED, STRING -> true;
            case WORD -> {
                var word = SqliteBytes.lowercase(token.text());
                if (word.equals("generated")) {
                    yield !isWord(peek(1), "always");
                }
                yield !COLUMN_CONSTRAINTS.contains(word);
            }
            default -> false;
        };
    }

    /// {@return whether `token` can be a name}
    private static boolean isName(Token token) {
        return token.kind() == Kind.WORD || token.kind() == Kind.QUOTED || token.kind() == Kind.STRING;
    }

    /// {@return whether `token` is the bare keyword `word`, given in lower case}
    private static boolean isWord(Token token, String word) {
        return token.kind() == Kind.WORD && SqliteBytes.equalsIgnoreCase(token.text(), word);
    }

    /// {@return whether `token` is the symbol `symbol`}
    private static boolean isSymbol(Token token, String symbol) {
        return token.kind() == Kind.SYMBOL && token.text().equals(symbol);
    }

    /// {@return the next token, without consuming it}
    private Token peek() {
        return peek(0);
    }

    /// {@return the token `ahead` places after the next one, or the end token if there is none}
    private Token peek(int ahead) {
        return tokens.get(Math.min(position + ahead, tokens.size() - 1));
    }

    /// {@return the next token, consuming it unless it is the end token}
    private Token next() {
        var token = tokens.get(position);
        if (token.kind() != Kind.END) {
            position++;
        }
        return token;
    }

    /// {@return whether the next token is the keyword `word`, consuming it if it is}
    private boolean acceptWord(String word) {
        if (isWord(peek(), word)) {
            next();
            return true;
        }
        return false;
    }

    /// {@return whether the next token is the symbol `symbol`, consuming it if it is}
    private boolean acceptSymbol(String symbol) {
        if (isSymbol(peek(), symbol)) {
            next();
            return true;
        }
        return false;
    }

    /// Consumes the keyword `word`.
    ///
    /// @throws IOException if the next token is anything else
    private void expectWord(String word) throws IOException {
        if (!acceptWord(word)) {
            throw error("expected " + word.toUpperCase());
        }
    }

    /// Consumes the symbol `symbol`.
    ///
    /// @throws IOException if the next token is anything else
    private void expectSymbol(String symbol) throws IOException {
        if (!acceptSymbol(symbol)) {
            throw error("expected '" + symbol + "'");
        }
    }

    /// {@return an exception reporting that the statement `problem`, at the next token}
    private IOException error(String problem) {
        var excerpt = sql.length() > 120 ? sql.substring(0, 120) + "..." : sql;
        return new IOException("Cannot parse the schema statement \"" + excerpt + "\": " + problem + " at offset " + peek().start());
    }
}
