package com.github.auties00.viridian.sqlite;

import com.github.auties00.viridian.sqlite.SqliteSchemaParser.Term;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;

/// A table of a database being written: its columns and constraints, the rows inserted so far and its indexes.
///
/// A rowid table keeps the table leaf cell of each row, overflow pages already written, and sorts them by rowid when
/// its b-tree is built. A `WITHOUT ROWID` table keeps its cells in primary key order as they are inserted. Every index
/// keeps its entries' records in index order. The keys every `UNIQUE` index, primary key and rowid hold are checked
/// on insert, so a row that SQLite would refuse is refused before anything changes.
///
/// @spec https://www.sqlite.org/lang_createtable.html CREATE TABLE
/// @spec https://www.sqlite.org/withoutrowid.html Clustered Indexes and the WITHOUT ROWID Optimization
final class SqliteWriterTable {
    /// The largest record SQLite reads or writes by default, `SQLITE_MAX_LENGTH`.
    static final long MAX_LENGTH = 1_000_000_000L;

    /// An index of the table, created by a constraint or by `CREATE INDEX`.
    static final class Index {
        /// The index's name.
        private final String name;

        /// Whether no two entries may have equal indexed values that are all non-`NULL`.
        private final boolean unique;

        /// The column of each field of an entry, the indexed ones first; -1 for the rowid.
        private final int[] columns;

        /// The number of indexed columns, which come first in an entry.
        private final int keyFields;

        /// The order of the entries.
        private final SqliteEntryOrder order;

        /// The column a partial index's `WHERE` tests, or -1 if the index is not partial.
        private final int whereColumn;

        /// Whether a partial index holds the rows whose [#whereColumn] is `NULL`, rather than those whose is not.
        private final boolean whereNull;

        /// The records of the entries, in index order.
        private final TreeSet<byte[]> entries;

        /// The index's root page, once its b-tree is written.
        private int rootPage;

        /// Creates an empty index.
        private Index(String name, boolean unique, int[] columns, int keyFields, SqliteEntryOrder order, int whereColumn, boolean whereNull) {
            this.name = name;
            this.unique = unique;
            this.columns = columns;
            this.keyFields = keyFields;
            this.order = order;
            this.whereColumn = whereColumn;
            this.whereNull = whereNull;
            this.entries = new TreeSet<>(order);
        }

        /// {@return the index's name}
        String name() {
            return name;
        }

        /// {@return the index's root page, once its b-tree is written}
        int rootPage() {
            return rootPage;
        }
    }

    /// A row of a `WITHOUT ROWID` table.
    ///
    /// @param key  a record that starts with the row's primary key fields
    /// @param cell the row's index leaf cell, overflow pages already written
    private record Row(byte[] key, byte[] cell) {
    }

    /// The table's name as `sqlite_schema` records it.
    private final String name;

    /// The parsed `CREATE TABLE` statement.
    private final SqliteSchemaParser.Table definition;

    /// The affinity of each column.
    private final SqliteAffinity[] affinities;

    /// Whether each column refuses `NULL`.
    private final boolean[] notNull;

    /// The column that aliases the rowid, or -1.
    private final int alias;

    /// Whether the table is `AUTOINCREMENT`, so that its rowids follow `sqlite_sequence`.
    private final boolean autoincrement;

    /// For a `WITHOUT ROWID` table, the column of each record field, primary key first; otherwise `null`.
    private final int[] recordColumns;

    /// For a `WITHOUT ROWID` table, the number of primary key fields that start its records.
    private final int primaryKeyFields;

    /// The collation of each primary key field of a `WITHOUT ROWID` table, which its secondary indexes order by too.
    private final SqliteCollation[] primaryKeyCollations;

    /// Whether each primary key field of a `WITHOUT ROWID` table is descending, as in its secondary indexes.
    private final boolean[] primaryKeyDescending;

    /// For a `WITHOUT ROWID` table, its rows in primary key order; otherwise `null`.
    private final TreeSet<Row> rows;

    /// The indexes: first those the constraints create, in the order SQLite numbers them, with `null` for the primary
    /// key of a `WITHOUT ROWID` table, then those `CREATE INDEX` creates.
    private final List<Index> indexes = new ArrayList<>();

    /// For a rowid table, the rowid of each row, in insertion order.
    private long[] rowids = new long[16];

    /// For a rowid table, the table leaf cell of each row, in insertion order.
    private byte[][] cells = new byte[16][];

    /// The number of rows.
    private int size;

    /// For a `STRICT` table, the declared type of each column, in upper case; `null` for any other table.
    private final String[] strictTypes;

    /// Whether the rowids were inserted in increasing order, so the cells need no sorting.
    private boolean ordered = true;

    /// The largest rowid, valid while the table has rows.
    private long maxRowid;

    /// Every rowid, once a rowid that may be taken had to be looked up; `null` until then, as a rowid larger than every
    /// other is free.
    private Set<Long> rowidSet;

    /// The table's root page, once its b-tree is written.
    private int rootPage;

    /// Creates the table that `definition` describes, with the indexes its constraints create.
    ///
    /// @throws IllegalArgumentException if the definition is one SQLite refuses, or uses what this writer does not
    ///                                  support
    SqliteWriterTable(String name, SqliteSchemaParser.Table definition) {
        this.name = name;
        this.definition = definition;
        var columns = definition.columns();
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("Table " + name + " has no columns");
        }
        if (definition.checks()) {
            throw new IllegalArgumentException("Table " + name + " has a CHECK constraint, which this writer cannot evaluate");
        }
        if (definition.lenient()) {
            throw new IllegalArgumentException("Table " + name + " has an ON CONFLICT REPLACE or IGNORE clause, which this writer does not honour");
        }
        var count = columns.size();
        this.affinities = new SqliteAffinity[count];
        this.notNull = new boolean[count];
        this.strictTypes = definition.strict() ? new String[count] : null;
        for (var i = 0; i < count; i++) {
            var column = columns.get(i);
            for (var j = 0; j < i; j++) {
                if (SqliteBytes.equalsIgnoreCase(columns.get(j).name(), column.name())) {
                    throw new IllegalArgumentException("Table " + name + " has two columns named " + column.name());
                }
            }
            if (column.generated() != SqliteSchemaParser.Generated.NONE) {
                throw new IllegalArgumentException("Column " + column.name() + " of table " + name + " is generated, which this writer does not support");
            }
            if (column.collation() != null && SqliteCollation.of(column.collation()) == null) {
                throw new IllegalArgumentException("Column " + column.name() + " of table " + name + " uses the collation "
                        + column.collation() + ", which SQLite does not have");
            }
            affinities[i] = column.affinity();
            notNull[i] = column.notNull();
            if (strictTypes != null) {
                var type = column.declaredType() == null ? "" : column.declaredType().toUpperCase(Locale.ROOT);
                if (!List.of("ANY", "BLOB", "INT", "INTEGER", "REAL", "TEXT").contains(type)) {
                    throw new IllegalArgumentException("Column " + column.name() + " of the STRICT table " + name + " has the type \""
                            + column.declaredType() + "\", which a STRICT table does not allow");
                }
                strictTypes[i] = type;
                if (type.equals("ANY")) {
                    // A STRICT table stores an ANY column's values as given, where another table gives the type NUMERIC affinity
                    affinities[i] = SqliteAffinity.BLOB;
                }
            }
        }
        SqliteSchemaParser.KeyConstraint primaryKey = null;
        for (var key : definition.keys()) {
            for (var term : key.terms()) {
                if (definition.columnIndex(term.column()) < 0) {
                    throw new IllegalArgumentException("Table " + name + " has no column named " + term.column());
                }
                if (term.collation() != null && SqliteCollation.of(term.collation()) == null) {
                    throw new IllegalArgumentException("A key of table " + name + " uses the collation " + term.collation()
                            + ", which SQLite does not have");
                }
            }
            if (key.primaryKey()) {
                if (primaryKey != null) {
                    throw new IllegalArgumentException("Table " + name + " has more than one primary key");
                }
                primaryKey = key;
            }
        }
        var withoutRowid = definition.withoutRowid();
        this.alias = definition.rowidAlias();
        this.autoincrement = primaryKey != null && primaryKey.autoincrement();
        if (autoincrement && (withoutRowid || alias < 0)) {
            throw new IllegalArgumentException(withoutRowid ? "AUTOINCREMENT is not allowed on the WITHOUT ROWID table " + name
                    : "AUTOINCREMENT is only allowed on an INTEGER PRIMARY KEY, which table " + name + " lacks");
        }
        if (withoutRowid) {
            if (primaryKey == null) {
                throw new IllegalArgumentException("Table " + name + " is WITHOUT ROWID but has no PRIMARY KEY");
            }
            var keyColumns = new ArrayList<Integer>();
            var keyCollations = new ArrayList<SqliteCollation>();
            var keyDescending = new ArrayList<Boolean>();
            for (var term : primaryKey.terms()) {
                var column = definition.columnIndex(term.column());
                var collation = collation(column, term);
                var duplicate = false;
                for (var i = 0; i < keyColumns.size(); i++) {
                    duplicate |= keyColumns.get(i) == column && keyCollations.get(i) == collation;
                }
                if (!duplicate) {
                    keyColumns.add(column);
                    keyCollations.add(collation);
                    keyDescending.add(term.descending());
                    notNull[column] = true;
                }
            }
            this.primaryKeyFields = keyColumns.size();
            var fields = new ArrayList<>(keyColumns);
            for (var i = 0; i < count; i++) {
                if (!keyColumns.contains(i)) {
                    fields.add(i);
                }
            }
            this.recordColumns = fields.stream().mapToInt(Integer::intValue).toArray();
            this.primaryKeyCollations = keyCollations.toArray(SqliteCollation[]::new);
            this.primaryKeyDescending = toArray(keyDescending);
            var order = new SqliteEntryOrder(primaryKeyCollations, primaryKeyDescending, primaryKeyFields);
            this.rows = new TreeSet<>((first, second) -> order.compare(first.key(), second.key()));
        } else {
            this.primaryKeyFields = 0;
            this.primaryKeyCollations = new SqliteCollation[0];
            this.primaryKeyDescending = new boolean[0];
            this.recordColumns = null;
            this.rows = null;
        }
        for (var terms : autoIndexes()) {
            addIndex("sqlite_autoindex_" + name + "_" + (indexes.size() + 1), true, terms, null);
        }
    }

    /// {@return the terms of the indexes SQLite creates for the table's `UNIQUE` and `PRIMARY KEY` constraints, in the
    /// order it numbers them, leaving out a `WITHOUT ROWID` table's primary key, which is the table itself}
    ///
    /// A constraint over the same columns with the same collations as an earlier one creates no index of its own;
    /// when it is the primary key, it makes the earlier index the primary key's. A `WITHOUT ROWID` table's `INTEGER`
    /// primary key is indexed only after every other constraint.
    private List<List<Term>> autoIndexes() {
        var indexes = new ArrayList<List<Term>>();
        var primary = -1;
        var deferred = definition.withoutRowid() && definition.integerPrimaryKey();
        for (var key : definition.keys()) {
            if (key.primaryKey() && (alias >= 0 || deferred)) {
                continue;
            }
            var existing = find(indexes, key.terms());
            if (existing < 0) {
                indexes.add(key.terms());
                existing = indexes.size() - 1;
            }
            if (key.primaryKey()) {
                primary = existing;
            }
        }
        if (deferred) {
            primary = find(indexes, definition.primaryKey().terms());
            if (primary < 0) {
                indexes.add(definition.primaryKey().terms());
                primary = indexes.size() - 1;
            }
        }
        if (definition.withoutRowid()) {
            indexes.set(primary, null);
        }
        return indexes;
    }

    /// {@return the position in `indexes` of the terms over the same columns with the same collations as `terms`, or -1}
    private int find(List<List<Term>> indexes, List<Term> terms) {
        for (var i = 0; i < indexes.size(); i++) {
            var existing = indexes.get(i);
            if (existing.size() != terms.size()) {
                continue;
            }
            var same = true;
            for (var j = 0; j < terms.size() && same; j++) {
                var first = definition.columnIndex(existing.get(j).column());
                var second = definition.columnIndex(terms.get(j).column());
                same = first == second && collation(first, existing.get(j)) == collation(second, terms.get(j));
            }
            if (same) {
                return i;
            }
        }
        return -1;
    }

    /// Adds an index over `terms`, or reserves the number of a `WITHOUT ROWID` table's primary key if `terms` is `null`.
    ///
    /// @param where a partial index's `WHERE` expression, or `null`
    /// @return the index, or `null` for the primary key
    /// @throws IllegalArgumentException if a term is not a column of the table, uses a collation SQLite does not have, or
    ///                                  the `WHERE` expression is not one this writer evaluates
    Index addIndex(String indexName, boolean unique, List<Term> terms, String where) {
        if (terms == null) {
            indexes.add(null);
            return null;
        }
        var columns = new ArrayList<Integer>();
        var collations = new ArrayList<SqliteCollation>();
        var descending = new ArrayList<Boolean>();
        for (var term : terms) {
            var column = term.column() == null ? -1 : definition.columnIndex(term.column());
            if (column < 0) {
                throw new IllegalArgumentException("Index " + indexName + " indexes " + term.expression()
                        + ", which is not a column of table " + name + "; this writer indexes columns only");
            }
            var collation = collation(column, term);
            if (collation == null) {
                throw new IllegalArgumentException("Index " + indexName + " uses the collation " + term.collation() + ", which SQLite does not have");
            }
            columns.add(column);
            collations.add(collation);
            descending.add(term.descending());
        }
        var keyFields = columns.size();
        if (definition.withoutRowid()) {
            for (var i = 0; i < primaryKeyFields; i++) {
                var column = recordColumns[i];
                var collation = primaryKeyCollations[i];
                var duplicate = false;
                for (var j = 0; j < keyFields; j++) {
                    duplicate |= columns.get(j) == column && collations.get(j) == collation;
                }
                if (!duplicate) {
                    columns.add(column);
                    collations.add(collation);
                    descending.add(primaryKeyDescending[i]);
                }
            }
        } else {
            columns.add(-1);
            collations.add(SqliteCollation.BINARY);
            descending.add(false);
        }
        var whereColumn = -1;
        var whereNull = false;
        if (where != null) {
            var predicate = SqliteWriter.nullTest(where);
            whereColumn = definition.columnIndex(predicate.column());
            if (whereColumn < 0) {
                throw new IllegalArgumentException("The WHERE clause of index " + indexName + " names " + predicate.column()
                        + ", which is not a column of table " + name);
            }
            whereNull = predicate.isNull();
        }
        var order = new SqliteEntryOrder(collations.toArray(SqliteCollation[]::new), toArray(descending), Integer.MAX_VALUE);
        var index = new Index(indexName, unique, columns.stream().mapToInt(Integer::intValue).toArray(), keyFields, order, whereColumn, whereNull);
        indexes.add(index);
        return index;
    }

    /// {@return the collation of `term` over column `column`: its own, else the column's, else `BINARY`; `null` if it
    /// names one SQLite does not have}
    private SqliteCollation collation(int column, Term term) {
        return SqliteCollation.of(SqliteTable.collation(definition, column, term));
    }

    /// {@return `values` as a `boolean[]`}
    private static boolean[] toArray(List<Boolean> values) {
        var array = new boolean[values.size()];
        for (var i = 0; i < array.length; i++) {
            array[i] = values.get(i);
        }
        return array;
    }

    /// Fails unless `value`, converted by its column's affinity, has the storage class the column's `STRICT` type
    /// names, as SQLite refuses a value it cannot convert losslessly.
    ///
    /// @throws IllegalArgumentException if it has another
    /// @spec https://www.sqlite.org/stricttables.html STRICT Tables
    private void checkStrict(int column, Object value) {
        if (value == null) {
            return;
        }
        var allowed = switch (strictTypes[column]) {
            case "INT", "INTEGER" -> value instanceof Long;
            case "REAL" -> value instanceof Double || value instanceof Long;
            case "TEXT" -> value instanceof String;
            case "BLOB" -> value instanceof byte[];
            default -> true;
        };
        if (!allowed) {
            throw new IllegalArgumentException("Column " + columnName(column) + " of the STRICT table " + name + " holds "
                    + strictTypes[column] + " values, not " + value.getClass().getSimpleName() + ": cannot store "
                    + strictTypes[column] + " value");
        }
    }

    /// Inserts a row.
    ///
    /// @param values   the row's values in column order, each `null`, a [Long], a [Double], a [String] or a `byte[]`
    /// @param sequence the table's `sqlite_sequence` value, for an `AUTOINCREMENT` table, or 0 if it has none
    /// @param trees    the b-tree writer that makes the row's cell and writes its overflow pages
    /// @return the row's rowid, or 0 for a `WITHOUT ROWID` table
    /// @throws IllegalArgumentException if the row violates a constraint or a value cannot be stored
    /// @throws IllegalStateException    if the table can take no more rowids
    long insert(Object[] values, long sequence, SqliteBTreeWriter trees) throws IOException {
        if (values.length != affinities.length) {
            throw new IllegalArgumentException("Table " + name + " has " + affinities.length + " columns, but " + values.length + " values were given");
        }
        var stored = new Object[values.length];
        for (var i = 0; i < values.length; i++) {
            stored[i] = i == alias ? null : affinities[i].store(values[i]);
            if (strictTypes != null && i != alias) {
                checkStrict(i, stored[i]);
            }
        }
        var rowid = 0L;
        var explicit = false;
        if (!definition.withoutRowid()) {
            if (alias >= 0 && values[alias] != null) {
                if (!(SqliteAffinity.INTEGER.store(values[alias]) instanceof Long integer)) {
                    throw new IllegalArgumentException("Column " + columnName(alias) + " of table " + name
                            + " aliases the rowid, so it holds integers only: datatype mismatch");
                }
                rowid = integer;
                explicit = true;
            } else {
                rowid = nextRowid(sequence);
            }
        }
        for (var i = 0; i < values.length; i++) {
            if (notNull[i] && i != alias && stored[i] == null) {
                throw new IllegalArgumentException("NOT NULL constraint failed: " + name + "." + columnName(i));
            }
        }
        if (explicit && containsRowid(rowid)) {
            throw new IllegalArgumentException("UNIQUE constraint failed: " + name + "." + (alias >= 0 ? columnName(alias) : "rowid"));
        }
        byte[] record;
        byte[] key = null;
        if (definition.withoutRowid()) {
            var fields = new Object[recordColumns.length];
            for (var i = 0; i < fields.length; i++) {
                fields[i] = stored[recordColumns[i]];
            }
            record = SqliteRecord.encode(fields, MAX_LENGTH);
            key = record;
            if (rows.contains(new Row(key, null))) {
                throw new IllegalArgumentException("UNIQUE constraint failed: " + name + " primary key");
            }
        } else {
            record = SqliteRecord.encode(stored, MAX_LENGTH);
        }
        var entries = new byte[indexes.size()][];
        for (var i = 0; i < indexes.size(); i++) {
            var index = indexes.get(i);
            if (index == null || index.whereColumn >= 0 && (field(stored, rowid, index.whereColumn) == null) != index.whereNull) {
                continue;
            }
            var fields = new Object[index.columns.length];
            var hasNull = false;
            for (var j = 0; j < fields.length; j++) {
                fields[j] = field(stored, rowid, index.columns[j]);
                hasNull |= j < index.keyFields && fields[j] == null;
            }
            entries[i] = SqliteRecord.encode(fields, MAX_LENGTH);
            if (index.unique && !hasNull) {
                var probe = SqliteRecord.encode(Arrays.copyOf(fields, index.keyFields), MAX_LENGTH);
                var existing = index.entries.ceiling(probe);
                if (existing != null && index.order.compare(existing, probe, index.keyFields) == 0) {
                    throw new IllegalArgumentException("UNIQUE constraint failed: index " + index.name + " of table " + name);
                }
            }
        }
        if (definition.withoutRowid()) {
            var cell = trees.indexCell(record);
            if (cell.length != SqliteBytes.varintLength(record.length) + record.length) {
                var keyFields = new Object[primaryKeyFields];
                for (var i = 0; i < primaryKeyFields; i++) {
                    keyFields[i] = stored[recordColumns[i]];
                }
                key = SqliteRecord.encode(keyFields, MAX_LENGTH);
            }
            rows.add(new Row(key, cell));
        } else {
            append(rowid, trees.tableCell(rowid, record));
        }
        for (var i = 0; i < indexes.size(); i++) {
            if (entries[i] != null) {
                indexes.get(i).entries.add(entries[i]);
            }
        }
        size++;
        return rowid;
    }

    /// {@return the value of column `column` of a row, the rowid for -1 and for the rowid alias}
    private Object field(Object[] stored, long rowid, int column) {
        return column < 0 || column == alias ? (Object) rowid : stored[column];
    }

    /// {@return the rowid SQLite gives a new row whose rowid is not given}
    ///
    /// It is one more than the largest rowid, or 1 for an empty table, and for an `AUTOINCREMENT` table at least one
    /// more than `sequence`. Once the largest rowid is the largest `long`, an ordinary table takes an unused positive
    /// rowid at random, and an `AUTOINCREMENT` table is full.
    private long nextRowid(long sequence) {
        long candidate;
        if (size == 0) {
            candidate = 1;
        } else if (maxRowid == Long.MAX_VALUE) {
            if (autoincrement) {
                throw new IllegalStateException("Table " + name + " is full: its AUTOINCREMENT rowids reached the largest integer");
            }
            if (rowidSet == null) {
                rowidSet = new HashSet<>();
                for (var i = 0; i < size; i++) {
                    rowidSet.add(rowids[i]);
                }
            }
            do {
                candidate = (ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE >> 1) + 1;
            } while (rowidSet.contains(candidate));
            return candidate;
        } else {
            candidate = maxRowid + 1;
        }
        if (autoincrement) {
            if (sequence == Long.MAX_VALUE) {
                throw new IllegalStateException("Table " + name + " is full: its sqlite_sequence value is the largest integer");
            }
            candidate = Math.max(candidate, sequence + 1);
        }
        return candidate;
    }

    /// {@return whether a row has `rowid`}
    private boolean containsRowid(long rowid) {
        if (size == 0 || ordered && rowid > maxRowid) {
            return false;
        }
        if (rowidSet == null) {
            rowidSet = new HashSet<>();
            for (var i = 0; i < size; i++) {
                rowidSet.add(rowids[i]);
            }
        }
        return rowidSet.contains(rowid);
    }

    /// Appends the cell of a new row of a rowid table.
    private void append(long rowid, byte[] cell) {
        if (size == rowids.length) {
            rowids = Arrays.copyOf(rowids, size * 2);
            cells = Arrays.copyOf(cells, size * 2);
        }
        rowids[size] = rowid;
        cells[size] = cell;
        if (size > 0 && rowid < maxRowid) {
            ordered = false;
        }
        if (size == 0 || rowid > maxRowid) {
            maxRowid = rowid;
        }
        if (rowidSet != null) {
            rowidSet.add(rowid);
        }
    }

    /// Writes the table's b-tree and its indexes' b-trees.
    void write(SqliteBTreeWriter trees) throws IOException {
        if (definition.withoutRowid()) {
            var leafCells = new ArrayList<byte[]>(rows.size());
            for (var row : rows) {
                leafCells.add(row.cell());
            }
            rootPage = trees.writeIndex(leafCells);
        } else {
            if (!ordered) {
                sortByRowid();
            }
            rootPage = trees.writeTable(rowids, cells, size, 0);
        }
        rowids = null;
        cells = null;
        if (rows != null) {
            rows.clear();
        }
        for (var index : indexes) {
            if (index == null) {
                continue;
            }
            var leafCells = new ArrayList<byte[]>(index.entries.size());
            for (var entry : index.entries) {
                leafCells.add(trees.indexCell(entry));
            }
            index.entries.clear();
            index.rootPage = trees.writeIndex(leafCells);
        }
    }

    /// Sorts the rows of a rowid table by rowid.
    private void sortByRowid() {
        var order = new Integer[size];
        for (var i = 0; i < size; i++) {
            order[i] = i;
        }
        var keys = rowids;
        Arrays.sort(order, (first, second) -> Long.compare(keys[first], keys[second]));
        var sortedRowids = new long[size];
        var sortedCells = new byte[size][];
        for (var i = 0; i < size; i++) {
            sortedRowids[i] = rowids[order[i]];
            sortedCells[i] = cells[order[i]];
        }
        rowids = sortedRowids;
        cells = sortedCells;
        ordered = true;
    }

    /// {@return the name of column `column`}
    private String columnName(int column) {
        return definition.columns().get(column).name();
    }

    /// {@return the table's name}
    String name() {
        return name;
    }

    /// {@return the parsed `CREATE TABLE` statement}
    SqliteSchemaParser.Table definition() {
        return definition;
    }

    /// {@return the indexes, with `null` for the primary key of a `WITHOUT ROWID` table, in the order SQLite numbers
    /// those its constraints create and then in creation order}
    List<Index> indexes() {
        return indexes;
    }

    /// {@return the number of rows}
    int size() {
        return size;
    }

    /// {@return whether the table is `AUTOINCREMENT`}
    boolean autoincrement() {
        return autoincrement;
    }

    /// {@return the table's root page, once its b-tree is written}
    int rootPage() {
        return rootPage;
    }
}
