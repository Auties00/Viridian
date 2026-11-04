package com.github.auties00.viridian.sqlite;

import com.github.auties00.viridian.sqlite.SqliteSchemaParser.Term;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// An index of a table: its b-tree of entries, each the indexed values followed by what locates the row.
///
/// An entry of an index on a rowid table ends with the rowid of its row, which [SqliteRow#rowid()] returns and
/// [SqliteTable#findByRowid(long)] looks up. An entry of an index on a `WITHOUT ROWID` table ends instead with the
/// primary key columns that the index does not already hold. Entries are ordered by all their fields, so entries
/// with equal indexed values are in rowid or primary key order.
///
/// A partial index, one with a `WHERE` clause, holds entries only for the rows the clause selects, and is scanned and
/// sought like any other.
///
/// @spec https://www.sqlite.org/fileformat2.html Database File Format
/// @spec https://www.sqlite.org/lang_createindex.html CREATE INDEX
public final class SqliteIndex {
    /// The pages of the database.
    private final SqlitePager pager;

    /// The index's name.
    private final String name;

    /// The indexed table's name.
    private final String tableName;

    /// The indexed columns and expressions.
    private final List<String> columns;

    /// Whether the index is `UNIQUE`.
    private final boolean unique;

    /// The root page of the index's b-tree.
    private final long rootPage;

    /// How an entry's record maps onto its values.
    private final SqliteRowLayout layout;

    /// For each field of an entry, why a seek cannot compare it, or `null` if it can.
    private final String[] unseekable;

    /// Builds an index over `table` from its terms.
    private SqliteIndex(SqlitePager pager, String name, long rootPage, boolean unique, List<Term> terms, SqliteTable table) {
        this.pager = pager;
        this.name = name;
        this.tableName = table.name();
        this.rootPage = rootPage;
        this.unique = unique;
        var definition = table.definition();
        var labels = new ArrayList<String>();
        var fieldColumns = new ArrayList<Integer>();
        var collations = new ArrayList<String>();
        var problems = new ArrayList<String>();
        for (var term : terms) {
            var column = term.column() == null ? -1 : definition.columnIndex(term.column());
            var rowid = column < 0 && term.column() != null && !definition.withoutRowid() && isRowidName(term.column());
            var collation = SqliteTable.collation(definition, column, term);
            var descending = term.descending() && pager.schemaFormat() >= 4;
            labels.add(column >= 0 ? definition.columns().get(column).name() : term.column() != null ? term.column() : term.expression());
            fieldColumns.add(column);
            collations.add(collation);
            if (column < 0 && !rowid) {
                problems.add("its column " + labels.getLast() + " is an expression");
            } else if (!SqliteBytes.equalsIgnoreCase(collation, "BINARY")) {
                problems.add("its column " + labels.getLast() + " uses the " + collation + " collation, which this reader cannot order");
            } else if (descending) {
                problems.add("its column " + labels.getLast() + " is DESC");
            } else {
                problems.add(null);
            }
        }
        this.columns = List.copyOf(labels);
        var keyCount = terms.size();
        if (definition.withoutRowid()) {
            for (var key : table.primaryKey()) {
                var duplicate = false;
                for (var i = 0; i < keyCount; i++) {
                    if (fieldColumns.get(i) == key.column() && SqliteBytes.equalsIgnoreCase(collations.get(i), key.collation())) {
                        duplicate = true;
                        break;
                    }
                }
                if (duplicate) {
                    continue;
                }
                var columnName = definition.columns().get(key.column()).name();
                fieldColumns.add(key.column());
                if (!SqliteBytes.equalsIgnoreCase(key.collation(), "BINARY")) {
                    problems.add("its primary key column " + columnName + " uses the " + key.collation() + " collation, which this reader cannot order");
                } else if (key.descending()) {
                    problems.add("its primary key column " + columnName + " is DESC");
                } else {
                    problems.add(null);
                }
            }
        } else {
            fieldColumns.add(-1);
            problems.add(null);
        }
        var size = fieldColumns.size();
        var fields = new int[size];
        var real = new boolean[size];
        for (var i = 0; i < size; i++) {
            fields[i] = i;
            var column = fieldColumns.get(i);
            real[i] = column >= 0 && definition.columns().get(column).affinity() == SqliteAffinity.REAL;
        }
        this.unseekable = problems.toArray(String[]::new);
        this.layout = new SqliteRowLayout("index " + name, pager.textEncoding(), fields, new Object[size], real,
                definition.withoutRowid() ? SqliteRowLayout.RowidSource.NONE : SqliteRowLayout.RowidSource.LAST_FIELD, size);
    }

    /// {@return the index that the `sqlite_schema` row `entry` describes, over `table`}
    ///
    /// @throws IOException if the statement cannot be parsed, or the index has none and matches no constraint of the
    ///                     table
    static SqliteIndex of(SqlitePager pager, SqliteSchemaEntry entry, SqliteTable table) throws IOException {
        if (entry.sql() == null) {
            return new SqliteIndex(pager, entry.name(), entry.rootPage(), true, table.autoIndexTerms(entry.name()), table);
        }
        var definition = SqliteSchemaParser.parseIndex(entry.sql());
        return new SqliteIndex(pager, entry.name(), entry.rootPage(), definition.unique(), definition.terms(), table);
    }

    /// {@return whether `name` is one of the names by which SQLite lets a rowid table's rowid be referred to}
    private static boolean isRowidName(String name) {
        return SqliteBytes.equalsIgnoreCase(name, "rowid") || SqliteBytes.equalsIgnoreCase(name, "oid")
                || SqliteBytes.equalsIgnoreCase(name, "_rowid_");
    }

    /// {@return the index's name}
    public String name() {
        return name;
    }

    /// {@return the name of the table the index belongs to}
    public String tableName() {
        return tableName;
    }

    /// {@return the indexed columns, by the names the table declares, and expressions, as written, in index order}
    public List<String> columns() {
        return columns;
    }

    /// {@return whether the index is `UNIQUE`, as every index SQLite creates for a constraint is}
    public boolean unique() {
        return unique;
    }

    /// {@return a cursor over every entry, in index order}
    ///
    /// @throws IOException if the index's root page is out of range
    public SqliteCursor scan() throws IOException {
        return new SqliteRowCursor(tree(), layout, SqliteRowCursor.Start.FIRST, 0, null);
    }

    /// {@return a cursor over the entries from the first whose leading values are at least `key`, in index order}
    ///
    /// The key's values are compared with an entry's first `key.size()` fields as SQLite compares values: `NULL`
    /// first, then numbers, INTEGER and REAL by value, then text, byte by byte in the database's encoding, then BLOBs,
    /// byte by byte. A value may be `null`, a [Long], [Integer], [Short] or [Byte], a [Double] or [Float], a
    /// [String] or a `byte[]`. No column affinity is applied to the key, so a number never matches a TEXT column's
    /// entries. The key may extend past the indexed columns into the rowid or primary key columns that end an entry.
    ///
    /// The seek descends the b-tree once, by binary search on each page, reading of an entry with overflow pages only
    /// what the comparison needs.
    ///
    /// @param key the values to compare an entry's leading fields with; empty to start at the first entry
    /// @throws IllegalArgumentException if `key` has more values than an entry has fields, or a value of another type
    /// @throws IOException              if a compared field is an expression, is `DESC`, or uses a collation other than
    ///                                  `BINARY`, all of which this reader cannot order, or a page is malformed
    public SqliteCursor seek(List<?> key) throws IOException {
        Objects.requireNonNull(key, "key");
        if (key.size() > unseekable.length) {
            throw new IllegalArgumentException("The key has " + key.size() + " values, but an entry of index " + name + " has "
                    + unseekable.length + " fields");
        }
        var sqliteKey = SqliteKey.of(key, pager.textEncoding());
        for (var i = 0; i < key.size(); i++) {
            if (unseekable[i] != null) {
                throw new IOException("Cannot seek index " + name + ": " + unseekable[i]);
            }
        }
        return new SqliteRowCursor(tree(), layout, SqliteRowCursor.Start.KEY, 0, sqliteKey);
    }

    /// {@return a new cursor that walks the index's b-tree}
    private SqliteBTreeCursor tree() throws IOException {
        return new SqliteBTreeCursor(pager, rootPage, false, true);
    }

    /// {@return the index's name, table and columns}
    @Override
    public String toString() {
        return "SqliteIndex[name=" + name + ", table=" + tableName + ", columns=" + columns + ", unique=" + unique + "]";
    }
}
