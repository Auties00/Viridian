package com.github.auties00.viridian.sqlite;

import com.github.auties00.viridian.sqlite.SqliteSqlTokenizer.Kind;
import com.github.auties00.viridian.sqlite.SqliteSqlTokenizer.Token;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// A new SQLite database file, written in one pass in pure Java without SQLite itself.
///
/// The caller declares the schema with [#execute(String)], then adds rows with [#insert(String, List)];
/// [#finish()] builds every b-tree bottom up, writes the file and forces it to disk. Until then the file holds only the
/// overflow pages of large rows, written as each row is inserted, so memory keeps at most about a page of each row,
/// and the entries of its indexes; [#close()] without [#finish()] deletes the file.
///
/// {@snippet lang = java :
/// try (var writer = SqliteWriter.create(file, 4096)) {
///     writer.execute("CREATE TABLE meta(key LONGVARCHAR NOT NULL UNIQUE PRIMARY KEY, value LONGVARCHAR)");
///     writer.insert("meta", List.of("version", 1));
///     writer.finish();
/// }
/// }
///
/// Rows are inserted as SQLite inserts them: each value is converted by its column's affinity, a rowid that is not
/// given is chosen as SQLite chooses it, `AUTOINCREMENT` keeps `sqlite_sequence` as SQLite does, and a row SQLite
/// would refuse, for a `NOT NULL`, `UNIQUE` or `PRIMARY KEY` constraint or a non-integer rowid, is refused with an
/// [IllegalArgumentException] before anything changes. Every index, those its constraints create included, gets an
/// entry for every row it covers, ordered as SQLite orders them.
///
/// The file is what SQLite itself would write for the same statements, up to how full its pages are: a UTF-8
/// database of schema format 4 in rollback-journal mode, with no free pages, no reserved bytes and no auto-vacuum,
/// whose `sqlite_schema` lists the objects in the order they were created, with the statement text SQLite stores.
///
/// A writer is not safe for concurrent use.
///
/// @spec https://www.sqlite.org/fileformat2.html Database File Format
/// @spec https://www.sqlite.org/lang_createtable.html CREATE TABLE
/// @spec https://www.sqlite.org/lang_createindex.html CREATE INDEX
/// @spec https://www.sqlite.org/autoinc.html SQLite Autoincrement
public final class SqliteWriter implements AutoCloseable {
    /// The SQLite version the header names as the last one to write the file, 3.54.1, `SQLITE_VERSION_NUMBER`.
    private static final int SQLITE_VERSION_NUMBER = 3_054_001;

    /// The header string every database starts with.
    private static final byte[] MAGIC = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);

    /// The statement SQLite creates `sqlite_sequence` with.
    private static final String SEQUENCE_SQL = "CREATE TABLE sqlite_sequence(name,seq)";

    /// What a partial index's `WHERE` tests: whether a column is `NULL`.
    ///
    /// @param column the column's name, unquoted
    /// @param isNull whether the index holds the rows where the column is `NULL`, rather than those where it is not
    record NullTest(String column, boolean isNull) {
    }

    /// A row of `sqlite_schema`.
    ///
    /// @param type      `table`, `index`, `view` or `trigger`
    /// @param name      the object's name
    /// @param tableName the table the object belongs to
    /// @param sql       the statement SQLite stores, or `null` for an index a constraint creates
    /// @param table     the table whose b-tree the row roots, or `null`
    /// @param index     the index whose b-tree the row roots, or `null`
    /// @param sequence  whether the row is `sqlite_sequence`'s
    private record SchemaRow(String type, String name, String tableName, String sql, SqliteWriterTable table,
                             SqliteWriterTable.Index index, boolean sequence) {
    }

    /// A row of `sqlite_sequence`.
    private static final class SequenceRow {
        /// The row's rowid.
        private final long rowid;

        /// The `name` column: the name of an `AUTOINCREMENT` table, for the rows SQLite adds.
        private final Object name;

        /// The `seq` column: the largest rowid the table has had, for the rows SQLite maintains.
        private Object seq;

        /// Creates a row.
        private SequenceRow(long rowid, Object name, Object seq) {
            this.rowid = rowid;
            this.name = name;
            this.seq = seq;
        }
    }

    /// The file written.
    private final Path file;

    /// The open file.
    private final FileChannel channel;

    /// The pages of the file.
    private final SqlitePageWriter pages;

    /// The cells and b-trees of the file.
    private final SqliteBTreeWriter trees;

    /// The rows of `sqlite_schema`, in creation order.
    private final List<SchemaRow> schema = new ArrayList<>();

    /// The tables, by name in lower case.
    private final Map<String, SqliteWriterTable> tables = new HashMap<>();

    /// The names of the views, in lower case.
    private final Map<String, String> views = new HashMap<>();

    /// The names of the indexes, in lower case.
    private final Map<String, String> indexes = new HashMap<>();

    /// The names of the triggers, in lower case.
    private final Map<String, String> triggers = new HashMap<>();

    /// The rows of `sqlite_sequence` in rowid order, or `null` until an `AUTOINCREMENT` table creates it.
    private List<SequenceRow> sequence;

    /// The root page of `sqlite_sequence`, once its b-tree is written.
    private int sequenceRoot;

    /// The user version.
    private int userVersion;

    /// The schema cookie: how many statements changed the schema.
    private int schemaCookie;

    /// Whether [#finish()] completed.
    private boolean finished;

    /// Whether writing failed, so the writer only closes.
    private boolean failed;

    /// Whether [#close()] has run.
    private boolean closed;

    /// Wraps a new, empty file.
    private SqliteWriter(Path file, FileChannel channel, int pageSize) {
        this.file = file;
        this.channel = channel;
        this.pages = new SqlitePageWriter(channel, pageSize);
        this.trees = new SqliteBTreeWriter(pages);
    }

    /// Creates the file `file` and starts writing a database with `pageSize`-byte pages to it.
    ///
    /// @param file     the database file, which must not exist, nor its `-journal` and `-wal` siblings, which SQLite
    ///                 would apply to it
    /// @param pageSize the size of every page, a power of two from 512 to 65536
    /// @return the writer, which owns the file until it is closed
    /// @throws IllegalArgumentException  if the page size is not one SQLite allows
    /// @throws FileAlreadyExistsException if the file or one of those siblings exists
    /// @throws IOException               if the file cannot be created
    public static SqliteWriter create(Path file, int pageSize) throws IOException {
        Objects.requireNonNull(file, "file");
        if (pageSize < 512 || pageSize > 65536 || (pageSize & (pageSize - 1)) != 0) {
            throw new IllegalArgumentException("A page size is a power of two from 512 to 65536: " + pageSize);
        }
        for (var suffix : List.of("-journal", "-wal")) {
            var sibling = SqliteBytes.sibling(file, suffix);
            if (Files.exists(sibling)) {
                throw new FileAlreadyExistsException(sibling.toString(), null, "SQLite would apply it to the new database");
            }
        }
        var channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.READ);
        return new SqliteWriter(file, channel, pageSize);
    }

    /// Sets the user version, the integer that `PRAGMA user_version` reads; it is 0 unless set.
    ///
    /// @throws IllegalStateException if the writer is finished or closed
    public void userVersion(int userVersion) {
        checkOpen();
        this.userVersion = userVersion;
    }

    /// Runs a `CREATE` statement, adding what it creates to the schema in the order of the calls.
    ///
    /// The statements are `CREATE TABLE`, `CREATE [UNIQUE] INDEX`, `CREATE VIEW` and `CREATE TRIGGER`, with an
    /// optional `IF NOT EXISTS`, which makes the statement do nothing when an object of the same kind has the name,
    /// and an optional `main.` before the name; one statement per call, with or without a final `;`. A table's
    /// `UNIQUE` and `PRIMARY KEY` constraints create the indexes SQLite creates for them, named
    /// `sqlite_autoindex_<table>_<n>`, right after the table; the first `AUTOINCREMENT` table creates
    /// `sqlite_sequence` after its own indexes. Views and triggers are only recorded: nothing evaluates them.
    ///
    /// An index orders its entries by the collation of each column, `BINARY`, `NOCASE` or `RTRIM`, ascending or
    /// descending; a partial index's `WHERE` must test one column with `IS NULL` or `IS NOT NULL`, or the `ISNULL`,
    /// `NOTNULL` and `NOT NULL` forms. An index is created before its table has rows, as its entries are made as
    /// rows are inserted.
    ///
    /// @param statement the statement
    /// @throws IllegalArgumentException if the statement is not one of these, is malformed, creates an object whose
    ///                                  name is taken or starts with `sqlite_`, refers to a table or column that does
    ///                                  not exist, or uses what this writer does not support: `TEMP` objects, virtual
    ///                                  tables, generated columns, `CHECK` constraints, conflict clauses
    ///                                  that `REPLACE` or `IGNORE`, indexes on expressions, and other `WHERE` clauses
    /// @throws IllegalStateException    if an index is created on a table that has rows, or the writer is finished or
    ///                                  closed
    public void execute(String statement) {
        Objects.requireNonNull(statement, "statement");
        checkOpen();
        List<Token> tokens;
        try {
            tokens = SqliteSqlTokenizer.tokenize(statement);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Cannot parse " + excerpt(statement) + ": " + exception.getMessage(), exception);
        }
        if (!isWord(token(tokens, 0), "create")) {
            throw new IllegalArgumentException("Not a CREATE statement: " + excerpt(statement));
        }
        var position = 1;
        if (isWord(token(tokens, position), "temp") || isWord(token(tokens, position), "temporary")) {
            throw new IllegalArgumentException("TEMP objects are not stored in the database file: " + excerpt(statement));
        }
        var unique = isWord(token(tokens, position), "unique");
        if (unique) {
            position++;
        }
        var kind = token(tokens, position);
        position++;
        var ifNotExists = isWord(token(tokens, position), "if") && isWord(token(tokens, position + 1), "not") && isWord(token(tokens, position + 2), "exists");
        if (ifNotExists) {
            position += 3;
        }
        var name = token(tokens, position);
        if (isSymbol(token(tokens, position + 1), ".")) {
            if (!SqliteBytes.equalsIgnoreCase(name.text(), "main")) {
                throw new IllegalArgumentException("Only the main schema is in the database file: " + excerpt(statement));
            }
            position += 2;
            name = token(tokens, position);
        }
        if (name.kind() != Kind.WORD && name.kind() != Kind.QUOTED && name.kind() != Kind.STRING) {
            throw new IllegalArgumentException("Expected a name at offset " + name.start() + " of " + excerpt(statement));
        }
        var trigger = isWord(kind, "trigger");
        var end = statementEnd(statement, tokens, trigger);
        var textEnd = end < tokens.size() - 1 ? token(tokens, end).start() : statement.length();
        if (unique && !isWord(kind, "index")) {
            throw new IllegalArgumentException("Only an index is UNIQUE: " + excerpt(statement));
        }
        try {
            if (isWord(kind, "table")) {
                createTable(statement, tokens, position, end, textEnd, ifNotExists);
            } else if (isWord(kind, "index")) {
                createIndex(statement, name, textEnd, unique, ifNotExists);
            } else if (isWord(kind, "view")) {
                createView(statement, name, textEnd, ifNotExists);
            } else if (trigger) {
                createTrigger(statement, tokens, position, end, ifNotExists);
            } else if (isWord(kind, "virtual")) {
                throw new IllegalArgumentException("Virtual tables are not supported: " + excerpt(statement));
            } else {
                throw new IllegalArgumentException("Not a CREATE TABLE, INDEX, VIEW or TRIGGER statement: " + excerpt(statement));
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Cannot parse " + excerpt(statement) + ": " + exception.getMessage(), exception);
        }
    }

    /// {@return the index in `tokens` of the token that ends the statement: its `;`, or the end token}
    ///
    /// A trigger's body holds `;`s of its own, so a trigger ends at its last `END`.
    ///
    /// @throws IllegalArgumentException if anything but comments follows the statement, or a trigger does not end
    ///                                  with `END`
    private static int statementEnd(String statement, List<Token> tokens, boolean trigger) {
        var last = tokens.size() - 1;
        if (trigger) {
            var end = last - 1;
            if (end > 0 && isSymbol(token(tokens, end), ";")) {
                end--;
            }
            if (end < 0 || !isWord(token(tokens, end), "end")) {
                throw new IllegalArgumentException("A trigger ends with END: " + excerpt(statement));
            }
            return end + 1;
        }
        for (var i = 0; i < last; i++) {
            if (isSymbol(token(tokens, i), ";")) {
                if (i + 1 != last) {
                    throw new IllegalArgumentException("More than one statement: " + excerpt(statement));
                }
                return i;
            }
        }
        return last;
    }

    /// Runs a `CREATE TABLE` statement.
    ///
    /// SQLite stores `CREATE TABLE` and the statement from the table's name: up to its closing parenthesis when the
    /// table has no options, and otherwise up to the `;` or the end of the text, whatever spaces come before them.
    ///
    /// @param position the position in `tokens` of the table's name
    /// @param end      the position in `tokens` of the token that ends the statement
    /// @param textEnd  the offset in `statement` of that token
    private void createTable(String statement, List<Token> tokens, int position, int end, int textEnd, boolean ifNotExists) throws IOException {
        var definition = SqliteSchemaParser.parseTable(statement);
        var name = definition.name();
        var key = SqliteBytes.lowercase(name);
        if (tables.containsKey(key) || views.containsKey(key)) {
            if (ifNotExists) {
                return;
            }
            throw new IllegalArgumentException("There is already a table or view named " + name);
        }
        if (indexes.containsKey(key)) {
            throw new IllegalArgumentException("There is already an index named " + name);
        }
        checkName(name);
        var open = position + 1;
        if (!isSymbol(token(tokens, open), "(")) {
            throw new IllegalArgumentException("Expected the column list of table " + name + ": " + excerpt(statement));
        }
        var close = open;
        for (var depth = 0; close < end; close++) {
            if (isSymbol(token(tokens, close), "(")) {
                depth++;
            } else if (isSymbol(token(tokens, close), ")") && --depth == 0) {
                break;
            }
        }
        var stop = close + 1 < end ? textEnd : token(tokens, close).end();
        var table = new SqliteWriterTable(name, definition);
        for (var index : table.indexes()) {
            if (index != null && indexes.containsKey(SqliteBytes.lowercase(index.name()))) {
                throw new IllegalArgumentException("There is already an index named " + index.name());
            }
        }
        tables.put(key, table);
        schema.add(new SchemaRow("table", name, name, "CREATE TABLE " + statement.substring(token(tokens, position).start(), stop), table, null, false));
        for (var index : table.indexes()) {
            if (index != null) {
                indexes.put(SqliteBytes.lowercase(index.name()), index.name());
                schema.add(new SchemaRow("index", index.name(), name, null, null, index, false));
            }
        }
        if (table.autoincrement() && sequence == null) {
            sequence = new ArrayList<>();
            schema.add(new SchemaRow("table", "sqlite_sequence", "sqlite_sequence", SEQUENCE_SQL, null, null, true));
        }
        schemaCookie++;
    }

    /// Runs a `CREATE INDEX` statement.
    ///
    /// SQLite stores `CREATE INDEX` or `CREATE UNIQUE INDEX` and the statement from the index's name up to the `;` or
    /// the end of the text.
    private void createIndex(String statement, Token name, int textEnd, boolean unique, boolean ifNotExists) throws IOException {
        var definition = SqliteSchemaParser.parseIndex(statement);
        var key = SqliteBytes.lowercase(definition.name());
        if (indexes.containsKey(key)) {
            if (ifNotExists) {
                return;
            }
            throw new IllegalArgumentException("There is already an index named " + definition.name());
        }
        if (tables.containsKey(key) || views.containsKey(key)) {
            throw new IllegalArgumentException("There is already a table or view named " + definition.name());
        }
        checkName(definition.name());
        var tableKey = SqliteBytes.lowercase(definition.table());
        var table = tables.get(tableKey);
        if (table == null) {
            throw new IllegalArgumentException(views.containsKey(tableKey) ? "Views may not be indexed: " + definition.table()
                    : "Index " + definition.name() + " is on table " + definition.table() + ", which does not exist");
        }
        if (table.size() > 0) {
            throw new IllegalStateException("Index " + definition.name() + " is created after rows were inserted into table "
                    + table.name() + "; create every index before inserting");
        }
        var index = table.addIndex(definition.name(), unique, definition.terms(), definition.where());
        indexes.put(key, definition.name());
        var sql = "CREATE" + (unique ? " UNIQUE" : "") + " INDEX " + statement.substring(name.start(), textEnd);
        schema.add(new SchemaRow("index", definition.name(), table.name(), sql, null, index, false));
        schemaCookie++;
    }

    /// Runs a `CREATE VIEW` statement.
    ///
    /// SQLite stores `CREATE VIEW` and the statement from the view's name up to the `;` or the end of the text, without
    /// the spaces before them.
    private void createView(String statement, Token name, int textEnd, boolean ifNotExists) {
        var key = SqliteBytes.lowercase(name.text());
        if (tables.containsKey(key) || views.containsKey(key)) {
            if (ifNotExists) {
                return;
            }
            throw new IllegalArgumentException("There is already a table or view named " + name.text());
        }
        if (indexes.containsKey(key)) {
            throw new IllegalArgumentException("There is already an index named " + name.text());
        }
        checkName(name.text());
        while (textEnd > name.end() && isSpace(statement.charAt(textEnd - 1))) {
            textEnd--;
        }
        views.put(key, name.text());
        schema.add(new SchemaRow("view", name.text(), name.text(), "CREATE VIEW " + statement.substring(name.start(), textEnd), null, null, false));
        schemaCookie++;
    }

    /// Runs a `CREATE TRIGGER` statement.
    ///
    /// SQLite stores `CREATE TRIGGER` and the statement from the trigger's name up to its `END`, and names the table
    /// as the statement writes it.
    ///
    /// @param position the position in `tokens` of the trigger's name
    /// @param end      the position in `tokens` just past the trigger's `END`
    private void createTrigger(String statement, List<Token> tokens, int position, int end, boolean ifNotExists) {
        var name = token(tokens, position);
        var key = SqliteBytes.lowercase(name.text());
        if (triggers.containsKey(key)) {
            if (ifNotExists) {
                return;
            }
            throw new IllegalArgumentException("There is already a trigger named " + name.text());
        }
        checkName(name.text());
        var on = position + 1;
        while (on < end && !isWord(token(tokens, on), "on")) {
            on++;
        }
        if (on + 1 >= end) {
            throw new IllegalArgumentException("Trigger " + name.text() + " names no table: " + excerpt(statement));
        }
        var target = token(tokens, on + 1);
        if (isSymbol(token(tokens, on + 2), ".")) {
            target = token(tokens, on + 3);
        }
        var tableKey = SqliteBytes.lowercase(target.text());
        if (!tables.containsKey(tableKey) && !views.containsKey(tableKey)) {
            throw new IllegalArgumentException("Trigger " + name.text() + " is on table " + target.text() + ", which does not exist");
        }
        triggers.put(key, name.text());
        var sql = "CREATE TRIGGER " + statement.substring(name.start(), token(tokens, end - 1).end());
        schema.add(new SchemaRow("trigger", name.text(), target.text(), sql, null, null, false));
        schemaCookie++;
    }

    /// {@return the test a partial index's `WHERE` expression `where` makes}
    ///
    /// @throws IllegalArgumentException if the expression is anything but a column tested with `IS NULL`, `IS NOT
    ///                                  NULL`, `ISNULL`, `NOTNULL` or `NOT NULL`, possibly in parentheses
    static NullTest nullTest(String where) {
        List<Token> tokens;
        try {
            tokens = SqliteSqlTokenizer.tokenize(where);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Cannot parse the WHERE clause " + where, exception);
        }
        var first = 0;
        var last = tokens.size() - 1;
        while (last - first >= 2 && isSymbol(tokens.get(first), "(") && isSymbol(tokens.get(last - 1), ")")) {
            first++;
            last--;
        }
        var count = last - first;
        var column = tokens.get(first);
        if (column.kind() == Kind.WORD || column.kind() == Kind.QUOTED) {
            if (count == 2 && isWord(tokens.get(first + 1), "isnull")) {
                return new NullTest(column.text(), true);
            }
            if (count == 2 && isWord(tokens.get(first + 1), "notnull")) {
                return new NullTest(column.text(), false);
            }
            if (count == 3 && isWord(tokens.get(first + 1), "is") && isWord(tokens.get(first + 2), "null")) {
                return new NullTest(column.text(), true);
            }
            if (count == 3 && isWord(tokens.get(first + 1), "not") && isWord(tokens.get(first + 2), "null")) {
                return new NullTest(column.text(), false);
            }
            if (count == 4 && isWord(tokens.get(first + 1), "is") && isWord(tokens.get(first + 2), "not") && isWord(tokens.get(first + 3), "null")) {
                return new NullTest(column.text(), false);
            }
        }
        throw new IllegalArgumentException("The WHERE clause " + where + " is not a column tested with IS NULL or IS NOT NULL, "
                + "the only partial indexes this writer evaluates");
    }

    /// Inserts a row into `table`, as `INSERT INTO table VALUES (...)` does.
    ///
    /// The values are in the order the table declares its columns, one per column. A value is `null`, a [Long],
    /// [Integer], [Short] or [Byte], a [Double] or [Float], a [String] or a `byte[]`; a NaN is stored as `NULL`, as
    /// SQLite stores it. Each is converted by its column's affinity as SQLite converts it: a number becomes text in a
    /// TEXT column, text that is a well-formed number becomes that number in an INTEGER, NUMERIC or REAL column, and
    /// so on. The value of an `INTEGER PRIMARY KEY` column is the rowid; when it is `null`, or the table has no such
    /// column, the rowid is one more than the largest one, or for an `AUTOINCREMENT` table one more than the largest
    /// it ever had, which `sqlite_sequence` records. Rows may be inserted into `sqlite_sequence` too, before the rows
    /// of the tables they name, to start a table's rowids above its `seq`.
    ///
    /// @param table  the table's name, compared ignoring the case of ASCII letters
    /// @param values the row's values
    /// @return the row's rowid, or 0 for a `WITHOUT ROWID` table
    /// @throws IllegalArgumentException if the table does not exist, the number of values is not its number of
    ///                                  columns, a value is of another type, a string is not well-formed UTF-16, a
    ///                                  REAL is given for a TEXT column, whose text form depends on the SQLite version,
    ///                                  the rowid alias's value is not an integer, or the row violates a `NOT NULL`,
    ///                                  `UNIQUE` or `PRIMARY KEY` constraint
    /// @throws IllegalStateException    if the table can take no more rowids, or the writer is finished or closed
    /// @throws IOException              if the overflow pages of a large row cannot be written
    public long insert(String table, List<?> values) throws IOException {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(values, "values");
        checkOpen();
        var normalized = new Object[values.size()];
        for (var i = 0; i < normalized.length; i++) {
            normalized[i] = normalize(values.get(i), i);
        }
        var key = SqliteBytes.lowercase(table);
        if (key.equals("sqlite_sequence") && sequence != null) {
            if (normalized.length != 2) {
                throw new IllegalArgumentException("Table sqlite_sequence has 2 columns, but " + normalized.length + " values were given");
            }
            var rowid = sequence.isEmpty() ? 1 : sequence.getLast().rowid + 1;
            sequence.add(new SequenceRow(rowid, normalized[0], normalized[1]));
            return rowid;
        }
        var target = tables.get(key);
        if (target == null) {
            throw new IllegalArgumentException("There is no table named " + table);
        }
        var sequenceRow = target.autoincrement() ? sequenceRow(target.name()) : null;
        var current = sequenceRow == null ? 0 : integer(sequenceRow.seq);
        try {
            var rowid = target.insert(normalized, current, trees);
            if (target.autoincrement() && rowid > current) {
                if (sequenceRow != null) {
                    sequenceRow.seq = rowid;
                } else {
                    sequence.add(new SequenceRow(sequence.isEmpty() ? 1 : sequence.getLast().rowid + 1, target.name(), rowid));
                }
            }
            return rowid;
        } catch (IOException exception) {
            failed = true;
            throw exception;
        }
    }

    /// {@return the first row of `sqlite_sequence` that names `table`, or `null`}
    private SequenceRow sequenceRow(String table) {
        for (var row : sequence) {
            if (row.name instanceof String name && name.equals(table)) {
                return row;
            }
        }
        return null;
    }

    /// {@return `value` as an integer, as SQLite reads a `seq` value: a REAL truncated, text by its leading integer,
    /// anything else 0}
    private static long integer(Object value) {
        return switch (value) {
            case Long integer -> integer;
            case Double real -> (long) real.doubleValue();
            case String text -> leadingInteger(text);
            case byte[] bytes -> leadingInteger(new String(bytes, StandardCharsets.UTF_8));
            case null, default -> 0;
        };
    }

    /// {@return the integer that `text` starts with, after any spaces, clamped to the `long` range; 0 if there is none}
    private static long leadingInteger(String text) {
        var position = 0;
        while (position < text.length() && isSpace(text.charAt(position))) {
            position++;
        }
        var negative = position < text.length() && text.charAt(position) == '-';
        if (position < text.length() && (text.charAt(position) == '-' || text.charAt(position) == '+')) {
            position++;
        }
        var magnitude = BigInteger.ZERO;
        while (position < text.length() && text.charAt(position) >= '0' && text.charAt(position) <= '9') {
            magnitude = magnitude.multiply(BigInteger.TEN).add(BigInteger.valueOf(text.charAt(position) - '0'));
            position++;
        }
        var value = negative ? magnitude.negate() : magnitude;
        return value.max(BigInteger.valueOf(Long.MIN_VALUE)).min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
    }

    /// {@return `value` as one of the types a record holds: `null`, a [Long], a [Double], a [String] or a `byte[]`}
    ///
    /// @throws IllegalArgumentException if `value` is of another type, or a string with an unpaired surrogate
    private static Object normalize(Object value, int position) {
        return switch (value) {
            case null -> null;
            case Long integer -> integer;
            case Integer integer -> integer.longValue();
            case Short integer -> integer.longValue();
            case Byte integer -> integer.longValue();
            case Double real -> real.isNaN() ? null : real;
            case Float real -> real.isNaN() ? null : real.doubleValue();
            case String text -> {
                for (var i = 0; i < text.length(); i++) {
                    var character = text.charAt(i);
                    if (Character.isHighSurrogate(character) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                        i++;
                    } else if (Character.isSurrogate(character)) {
                        throw new IllegalArgumentException("Value " + position + " has an unpaired surrogate at index " + i
                                + ", which UTF-8 cannot encode");
                    }
                }
                yield text;
            }
            case byte[] bytes -> bytes;
            default -> throw new IllegalArgumentException("Value " + position + " is a " + value.getClass().getName()
                    + "; a value is null, an integer, a floating-point number, a String or a byte[]");
        };
    }

    /// Builds every b-tree, writes the database header and forces the file to disk.
    ///
    /// The writer is finished afterwards, and only closes.
    ///
    /// @throws IllegalStateException if the writer is finished or closed
    /// @throws IOException           if the file cannot be written; the writer then deletes it when closed
    public void finish() throws IOException {
        checkOpen();
        try {
            for (var row : schema) {
                if (row.table() != null) {
                    row.table().write(trees);
                } else if (row.sequence()) {
                    writeSequence();
                }
            }
            var rowids = new long[schema.size()];
            var cells = new byte[schema.size()][];
            for (var i = 0; i < schema.size(); i++) {
                var row = schema.get(i);
                long rootPage;
                if (row.table() != null) {
                    rootPage = row.table().rootPage();
                } else if (row.index() != null) {
                    rootPage = row.index().rootPage();
                } else if (row.sequence()) {
                    rootPage = sequenceRoot;
                } else {
                    rootPage = 0;
                }
                var record = SqliteRecord.encode(new Object[]{row.type(), row.name(), row.tableName(), rootPage, row.sql()}, SqliteWriterTable.MAX_LENGTH);
                rowids[i] = i + 1;
                cells[i] = trees.tableCell(i + 1, record);
            }
            trees.writeTable(rowids, cells, schema.size(), 1);
            var first = trees.pageOne();
            writeHeader(first);
            pages.write(1, first);
            pages.flush();
            channel.force(true);
            finished = true;
        } catch (IOException | RuntimeException | Error failure) {
            failed = true;
            throw failure;
        }
    }

    /// Writes the b-tree of `sqlite_sequence`.
    private void writeSequence() throws IOException {
        var rowids = new long[sequence.size()];
        var cells = new byte[sequence.size()][];
        for (var i = 0; i < sequence.size(); i++) {
            var row = sequence.get(i);
            rowids[i] = row.rowid;
            cells[i] = trees.tableCell(row.rowid, SqliteRecord.encode(new Object[]{row.name, row.seq}, SqliteWriterTable.MAX_LENGTH));
        }
        sequenceRoot = trees.writeTable(rowids, cells, cells.length, 0);
    }

    /// Writes the 100-byte database header at the start of `page`, page 1.
    private void writeHeader(byte[] page) {
        System.arraycopy(MAGIC, 0, page, 0, MAGIC.length);
        var pageSize = pages.pageSize();
        SqliteBytes.putU16(page, 16, pageSize == 65536 ? 1 : pageSize);
        page[18] = 1;
        page[19] = 1;
        page[20] = 0;
        page[21] = 64;
        page[22] = 32;
        page[23] = 32;
        SqliteBytes.putI32(page, 24, 1);
        SqliteBytes.putI32(page, 28, pages.pageCount());
        SqliteBytes.putI32(page, 40, schemaCookie);
        SqliteBytes.putI32(page, 44, 4);
        SqliteBytes.putI32(page, 56, 1);
        SqliteBytes.putI32(page, 60, userVersion);
        SqliteBytes.putI32(page, 92, 1);
        SqliteBytes.putI32(page, 96, SQLITE_VERSION_NUMBER);
    }

    /// Closes the file; if [#finish()] did not complete, deletes it. Closing again has no effect.
    ///
    /// @throws IOException if the file cannot be closed or deleted
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            channel.close();
        } finally {
            if (!finished) {
                Files.deleteIfExists(file);
            }
        }
    }

    /// Checks that the writer still takes statements and rows.
    ///
    /// @throws IllegalStateException if it is finished, failed or closed
    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("The writer is closed");
        }
        if (finished) {
            throw new IllegalStateException("The writer is finished");
        }
        if (failed) {
            throw new IllegalStateException("The writer failed to write the file");
        }
    }

    /// Checks that `name` is not reserved for SQLite's own objects.
    ///
    /// @throws IllegalArgumentException if it starts with `sqlite_`, ignoring the case of ASCII letters
    private static void checkName(String name) {
        if (name.length() >= 7 && SqliteBytes.equalsIgnoreCase(name.substring(0, 7), "sqlite_")) {
            throw new IllegalArgumentException("The name " + name + " is reserved for SQLite's own objects");
        }
    }

    /// {@return the token at `position` in `tokens`, or the end token that closes them if `position` is past it}
    private static Token token(List<Token> tokens, int position) {
        return tokens.get(Math.min(position, tokens.size() - 1));
    }

    /// {@return whether `token` is the bare keyword `word`}
    private static boolean isWord(Token token, String word) {
        return token.kind() == Kind.WORD && SqliteBytes.equalsIgnoreCase(token.text(), word);
    }

    /// {@return whether `token` is the symbol `symbol`}
    private static boolean isSymbol(Token token, String symbol) {
        return token.kind() == Kind.SYMBOL && token.text().equals(symbol);
    }

    /// {@return whether `character` is whitespace to SQLite: space, tab, line feed, vertical tab, form feed or carriage
    /// return}
    private static boolean isSpace(char character) {
        return character == ' ' || character >= '\t' && character <= '\r';
    }

    /// {@return the start of `statement`, for an error message}
    private static String excerpt(String statement) {
        return "\"" + (statement.length() > 120 ? statement.substring(0, 120) + "..." : statement) + "\"";
    }
}
