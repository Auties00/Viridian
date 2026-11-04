package com.github.auties00.viridian.sqlite;

import com.github.auties00.viridian.sqlite.SqliteSchemaParser.Generated;
import com.github.auties00.viridian.sqlite.SqliteSchemaParser.Term;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/// A table of a database: its columns and its rows.
///
/// A rowid table keeps its rows in a table b-tree keyed by rowid; a `WITHOUT ROWID` table keeps them in an index
/// b-tree keyed by its primary key, so it is scanned in primary key order and has no rowids.
///
/// A table with a `VIRTUAL` generated column can be inspected but not read: the column's values are not stored, and
/// computing them would take evaluating SQL expressions.
///
/// @spec https://www.sqlite.org/fileformat2.html Database File Format
/// @spec https://www.sqlite.org/withoutrowid.html Clustered Indexes and the WITHOUT ROWID Optimization
public final class SqliteTable {
    /// The column of a `WITHOUT ROWID` table's primary key that a record field holds, with how the key orders it.
    ///
    /// @param column     the column's index in the table
    /// @param collation  the collation the key compares the column with
    /// @param descending whether the key sorts the column in descending order
    record KeyColumn(int column, String collation, boolean descending) {
    }

    /// The pages of the database.
    private final SqlitePager pager;

    /// The table's name as `sqlite_schema` records it.
    private final String name;

    /// The root page of the table's b-tree.
    private final long rootPage;

    /// The parsed `CREATE TABLE` statement.
    private final SqliteSchemaParser.Table definition;

    /// The columns in declaration order.
    private final List<SqliteColumn> columns;

    /// For a `WITHOUT ROWID` table, the primary key columns in record order, without duplicates; else empty.
    private final List<KeyColumn> primaryKey;

    /// How a record maps onto the columns.
    private final SqliteRowLayout layout;

    /// Why the rows cannot be read, or `null` if they can.
    private final String unreadable;

    /// The cursor that looks rows up by rowid, created on first use and reused.
    private SqliteBTreeCursor lookup;

    /// Builds a table from its parsed definition.
    private SqliteTable(SqlitePager pager, String name, long rootPage, SqliteSchemaParser.Table definition) throws IOException {
        this.pager = pager;
        this.name = name;
        this.rootPage = rootPage;
        this.definition = definition;
        var parsed = definition.columns();
        var count = parsed.size();
        var alias = definition.rowidAlias();
        var columns = new ArrayList<SqliteColumn>(count);
        var fields = new int[count];
        var defaults = new Object[count];
        var real = new boolean[count];
        String unreadable = null;
        for (var i = 0; i < count; i++) {
            var column = parsed.get(i);
            columns.add(new SqliteColumn(column.name(), column.declaredType(), i == alias));
            defaults[i] = column.affinity().defaultValue(column.defaultValue());
            real[i] = column.affinity() == SqliteAffinity.REAL;
            if (column.generated() == Generated.VIRTUAL && unreadable == null) {
                unreadable = "Table " + name + " has the VIRTUAL generated column " + column.name()
                        + ", whose values are not stored and which this reader cannot compute";
            }
        }
        this.columns = List.copyOf(columns);
        if (definition.withoutRowid()) {
            this.primaryKey = primaryKey(name, definition, pager.schemaFormat());
            Arrays.fill(fields, SqliteRowLayout.NOT_STORED);
            for (var i = 0; i < primaryKey.size(); i++) {
                var column = primaryKey.get(i).column();
                if (fields[column] == SqliteRowLayout.NOT_STORED) {
                    fields[column] = i;
                }
            }
            var next = primaryKey.size();
            for (var i = 0; i < count; i++) {
                if (fields[i] == SqliteRowLayout.NOT_STORED && parsed.get(i).generated() != Generated.VIRTUAL) {
                    fields[i] = next++;
                }
            }
        } else {
            this.primaryKey = List.of();
            var storage = 0;
            for (var i = 0; i < count; i++) {
                if (parsed.get(i).generated() == Generated.VIRTUAL) {
                    fields[i] = SqliteRowLayout.NOT_STORED;
                } else {
                    fields[i] = i == alias ? SqliteRowLayout.ROWID : storage;
                    storage++;
                }
            }
        }
        this.unreadable = unreadable;
        this.layout = new SqliteRowLayout("table " + name, pager.textEncoding(), fields, defaults, real,
                definition.withoutRowid() ? SqliteRowLayout.RowidSource.NONE : SqliteRowLayout.RowidSource.CELL, 0);
    }

    /// {@return the table that the `sqlite_schema` row `entry` describes}
    ///
    /// @throws IOException if the row has no statement, the statement cannot be parsed, or it creates a virtual
    ///                     table, which keeps no rows of its own in the file
    static SqliteTable of(SqlitePager pager, SqliteSchemaEntry entry) throws IOException {
        if (entry.sql() == null) {
            throw new IOException("Table " + entry.name() + " has no CREATE TABLE statement in sqlite_schema");
        }
        var definition = SqliteSchemaParser.parseTable(entry.sql());
        if (definition.virtual()) {
            throw new IOException("Table " + entry.name() + " is a virtual table, which keeps no rows of its own in the database file");
        }
        return new SqliteTable(pager, entry.name(), entry.rootPage(), definition);
    }

    /// {@return the `sqlite_schema` table itself, whose b-tree is rooted at page 1}
    static SqliteTable schema(SqlitePager pager) throws IOException {
        var definition = SqliteSchemaParser.parseTable("CREATE TABLE sqlite_schema(type text, name text, tbl_name text, rootpage int, sql text)");
        return new SqliteTable(pager, "sqlite_schema", 1, definition);
    }

    /// {@return the primary key columns of the `WITHOUT ROWID` table `definition`, in record order, without the
    /// repeated ones that SQLite drops: a column listed again with the same collation}
    private static List<KeyColumn> primaryKey(String name, SqliteSchemaParser.Table definition, int schemaFormat) throws IOException {
        var key = definition.primaryKey();
        if (key == null) {
            throw new IOException("Table " + name + " is WITHOUT ROWID but declares no PRIMARY KEY");
        }
        var columns = new ArrayList<KeyColumn>();
        for (var term : key.terms()) {
            var column = definition.columnIndex(term.column());
            if (column < 0) {
                throw new IOException("The PRIMARY KEY of table " + name + " names " + term.column() + ", which is not one of its columns");
            }
            var collation = collation(definition, column, term);
            var duplicate = false;
            for (var existing : columns) {
                if (existing.column() == column && SqliteBytes.equalsIgnoreCase(existing.collation(), collation)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                columns.add(new KeyColumn(column, collation, term.descending() && schemaFormat >= 4));
            }
        }
        return List.copyOf(columns);
    }

    /// {@return the collation of `term` over column `column`: its own, else the column's, else `BINARY`}
    static String collation(SqliteSchemaParser.Table definition, int column, Term term) {
        if (term.collation() != null) {
            return term.collation();
        }
        if (column >= 0 && definition.columns().get(column).collation() != null) {
            return definition.columns().get(column).collation();
        }
        return "BINARY";
    }

    /// {@return the table's name, as `sqlite_schema` records it}
    public String name() {
        return name;
    }

    /// {@return the columns, in declaration order}
    public List<SqliteColumn> columns() {
        return columns;
    }

    /// {@return the position of the column named `name`, compared as SQLite compares identifiers: ignoring the case of
    /// ASCII letters}
    ///
    /// @param name the column's name, unquoted
    /// @throws IllegalArgumentException if no column has that name
    public int columnIndex(String name) {
        var index = definition.columnIndex(Objects.requireNonNull(name, "name"));
        if (index < 0) {
            throw new IllegalArgumentException("Table " + this.name + " has no column named " + name);
        }
        return index;
    }

    /// {@return whether the table is `WITHOUT ROWID`}
    public boolean withoutRowid() {
        return definition.withoutRowid();
    }

    /// {@return a cursor over every row, in rowid order or, for a `WITHOUT ROWID` table, primary key order}
    ///
    /// @throws IOException if the table has a `VIRTUAL` generated column, or its root page is out of range
    public SqliteCursor scan() throws IOException {
        checkReadable();
        return new SqliteRowCursor(tree(true), layout, SqliteRowCursor.Start.FIRST, 0, null);
    }

    /// {@return the row with `rowid`, or an empty optional if there is none}
    ///
    /// The lookup descends the table b-tree once, by binary search on each page.
    ///
    /// @param rowid the rowid to look up, any `long`
    /// @throws IllegalStateException if the table is `WITHOUT ROWID`
    /// @throws IOException           if the table has a `VIRTUAL` generated column, or a page on the way is malformed
    public Optional<SqliteRow> findByRowid(long rowid) throws IOException {
        if (definition.withoutRowid()) {
            throw new IllegalStateException("Table " + name + " is WITHOUT ROWID, so its rows have no rowid");
        }
        checkReadable();
        if (lookup == null) {
            lookup = tree(false);
        }
        if (!lookup.find(rowid)) {
            return Optional.empty();
        }
        return Optional.of(layout.read(lookup));
    }

    /// {@return a cursor over the rows from the first whose key is at least `key`, in the order of [#scan()]}
    ///
    /// For a rowid table, `key` is one integer, a rowid. For a `WITHOUT ROWID` table it is a prefix of the primary
    /// key, compared field by field as SQLite compares values, `NULL` first, then numbers, then text, then BLOBs; a
    /// value may be `null`, an integer type, a [Double] or [Float], a [String] or a `byte[]`.
    ///
    /// @param key the rowid, or the leading primary key values, to start from
    /// @throws IllegalArgumentException if `key` is not one integer for a rowid table, or is longer than the primary
    ///                                  key or holds a value of another type for a `WITHOUT ROWID` table
    /// @throws IOException              if the table has a `VIRTUAL` generated column, or a compared primary key
    ///                                  column is `DESC` or uses a collation other than `BINARY`, which this reader
    ///                                  cannot order
    public SqliteCursor seek(List<?> key) throws IOException {
        Objects.requireNonNull(key, "key");
        checkReadable();
        if (!definition.withoutRowid()) {
            if (key.size() != 1 || !(key.getFirst() instanceof Long || key.getFirst() instanceof Integer
                    || key.getFirst() instanceof Short || key.getFirst() instanceof Byte)) {
                throw new IllegalArgumentException("Table " + name + " is a rowid table, which is sought by one integer rowid");
            }
            return new SqliteRowCursor(tree(true), layout, SqliteRowCursor.Start.ROWID, ((Number) key.getFirst()).longValue(), null);
        }
        if (key.size() > primaryKey.size()) {
            throw new IllegalArgumentException("The key has " + key.size() + " values, but the primary key of table " + name
                    + " has " + primaryKey.size() + " columns");
        }
        for (var i = 0; i < key.size(); i++) {
            var column = primaryKey.get(i);
            var columnName = columns.get(column.column()).name();
            if (!SqliteBytes.equalsIgnoreCase(column.collation(), "BINARY")) {
                throw new IOException("Cannot seek table " + name + ": its primary key column " + columnName + " uses the "
                        + column.collation() + " collation, which this reader cannot order");
            }
            if (column.descending()) {
                throw new IOException("Cannot seek table " + name + ": its primary key column " + columnName + " is DESC");
            }
        }
        return new SqliteRowCursor(tree(true), layout, SqliteRowCursor.Start.KEY, 0, SqliteKey.of(key, pager.textEncoding()));
    }

    /// {@return the terms of the index that SQLite names `indexName` after this table's constraints}
    ///
    /// SQLite creates such an index for every `UNIQUE` constraint and for a primary key that is not the rowid, in the
    /// order the statement declares them, skipping one that repeats an earlier index's columns and collations, and
    /// names it `sqlite_autoindex_<table>_<n>`. A `WITHOUT ROWID` table's `INTEGER` primary key is indexed last,
    /// once the statement has ended; its primary key index is the table itself, so it has no row in `sqlite_schema`.
    ///
    /// @throws IOException if no constraint yields an index of that name
    List<Term> autoIndexTerms(String indexName) throws IOException {
        var indexes = new ArrayList<List<Term>>();
        var deferred = definition.withoutRowid() && definition.integerPrimaryKey();
        for (var key : definition.keys()) {
            if (key.primaryKey() && (definition.rowidAlias() >= 0 || deferred)) {
                continue;
            }
            addDistinct(indexes, key.terms());
        }
        if (deferred) {
            addDistinct(indexes, definition.primaryKey().terms());
        }
        for (var i = 0; i < indexes.size(); i++) {
            if (SqliteBytes.equalsIgnoreCase("sqlite_autoindex_" + name + "_" + (i + 1), indexName)) {
                return indexes.get(i);
            }
        }
        throw new IOException("Index " + indexName + " has no CREATE INDEX statement and is none of the indexes the constraints of table "
                + name + " create");
    }

    /// Adds `terms` to `indexes` unless an index there has the same columns with the same collations.
    private void addDistinct(List<List<Term>> indexes, List<Term> terms) {
        for (var existing : indexes) {
            if (existing.size() != terms.size()) {
                continue;
            }
            var same = true;
            for (var i = 0; i < terms.size() && same; i++) {
                var first = definition.columnIndex(existing.get(i).column());
                var second = definition.columnIndex(terms.get(i).column());
                same = first == second && SqliteBytes.equalsIgnoreCase(collation(definition, first, existing.get(i)),
                        collation(definition, second, terms.get(i)));
            }
            if (same) {
                return;
            }
        }
        indexes.add(terms);
    }

    /// {@return the parsed `CREATE TABLE` statement}
    SqliteSchemaParser.Table definition() {
        return definition;
    }

    /// {@return the primary key columns of a `WITHOUT ROWID` table in record order, or an empty list}
    List<KeyColumn> primaryKey() {
        return primaryKey;
    }

    /// {@return a new cursor over the table's b-tree}
    ///
    /// @param traversal whether the cursor walks the tree rather than only descending once
    private SqliteBTreeCursor tree(boolean traversal) throws IOException {
        return new SqliteBTreeCursor(pager, rootPage, !definition.withoutRowid(), traversal);
    }

    /// Checks that the rows can be read.
    ///
    /// @throws IOException if the table has a `VIRTUAL` generated column
    private void checkReadable() throws IOException {
        if (unreadable != null) {
            throw new IOException(unreadable);
        }
    }

    /// {@return the table's name and columns}
    @Override
    public String toString() {
        return "SqliteTable[name=" + name + ", columns=" + columns + ", withoutRowid=" + definition.withoutRowid() + "]";
    }
}
