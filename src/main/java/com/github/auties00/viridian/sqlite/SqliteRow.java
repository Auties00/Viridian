package com.github.auties00.viridian.sqlite;

import java.util.HexFormat;
import java.util.Objects;
import java.util.StringJoiner;

/// A row of a table, or an entry of an index, read in full.
///
/// A table row's values are its columns in declaration order. An index entry's values are the indexed columns and
/// expressions, then the rowid of the row it points to or, for an index on a `WITHOUT ROWID` table, that row's
/// primary key columns that the index does not hold already.
///
/// Values are decoded when asked for, from bytes the row keeps, so reading one never fails. A value is `null`, a
/// [Long], a [Double], a [String] or a `byte[]`: INTEGER, REAL, TEXT and BLOB in SQLite's terms. A column of REAL
/// affinity reads as a [Double] even where SQLite stored its integral value as an INTEGER, as SQLite itself returns
/// it. A row stays readable after its cursor moves on.
///
/// @spec https://www.sqlite.org/datatype3.html Datatypes In SQLite
public final class SqliteRow {
    /// How values map onto the record.
    private final SqliteRowLayout layout;

    /// The row's record.
    private final SqliteRecord record;

    /// The rowid, if [#hasRowid].
    private final long rowid;

    /// Whether the row has a rowid.
    private final boolean hasRowid;

    /// Wraps a record read from a b-tree.
    SqliteRow(SqliteRowLayout layout, SqliteRecord record, long rowid, boolean hasRowid) {
        this.layout = layout;
        this.record = record;
        this.rowid = rowid;
        this.hasRowid = hasRowid;
    }

    /// {@return the rowid of the row, or, for an index entry, of the row it points to}
    ///
    /// @throws IllegalStateException if the row belongs to a `WITHOUT ROWID` table, or the entry to an index on one
    public long rowid() {
        if (!hasRowid) {
            throw new IllegalStateException("The row belongs to a WITHOUT ROWID table, whose rows have no rowid");
        }
        return rowid;
    }

    /// {@return the number of values}
    public int size() {
        return layout.size();
    }

    /// {@return whether value `column` is `NULL`}
    ///
    /// @param column the value's position, from 0 to [#size()] exclusive
    /// @throws IndexOutOfBoundsException if `column` is not between 0 and [#size()]
    public boolean isNull(int column) {
        Objects.checkIndex(column, layout.size());
        var field = layout.field(column);
        if (field == SqliteRowLayout.ROWID) {
            return false;
        }
        if (field >= record.size()) {
            return layout.defaultValue(column) == null;
        }
        return record.isNull(field);
    }

    /// {@return value `column`: `null`, a [Long], a [Double], a [String], or a `byte[]` that the caller owns}
    ///
    /// @param column the value's position, from 0 to [#size()] exclusive
    /// @throws IndexOutOfBoundsException if `column` is not between 0 and [#size()]
    public Object get(int column) {
        Objects.checkIndex(column, layout.size());
        var field = layout.field(column);
        Object value;
        if (field == SqliteRowLayout.ROWID) {
            value = rowid;
        } else if (field >= record.size()) {
            value = layout.defaultValue(column);
            if (value instanceof byte[] bytes) {
                value = bytes.clone();
            }
        } else {
            value = record.value(field, layout.encoding());
        }
        if (value instanceof Long integer && layout.real(column)) {
            return integer.doubleValue();
        }
        return value;
    }

    /// {@return value `column`, an INTEGER}
    ///
    /// @param column the value's position, from 0 to [#size()] exclusive
    /// @throws IllegalStateException     if the value is not an INTEGER
    /// @throws IndexOutOfBoundsException if `column` is not between 0 and [#size()]
    public long getLong(int column) {
        if (get(column) instanceof Long value) {
            return value;
        }
        throw new IllegalStateException("Value " + column + " is " + describe(column) + ", not an INTEGER");
    }

    /// {@return value `column`, a REAL}
    ///
    /// @param column the value's position, from 0 to [#size()] exclusive
    /// @throws IllegalStateException     if the value is not a REAL
    /// @throws IndexOutOfBoundsException if `column` is not between 0 and [#size()]
    public double getDouble(int column) {
        if (get(column) instanceof Double value) {
            return value;
        }
        throw new IllegalStateException("Value " + column + " is " + describe(column) + ", not a REAL");
    }

    /// {@return value `column`, a BLOB the caller owns, or `null` if it is `NULL`}
    ///
    /// @param column the value's position, from 0 to [#size()] exclusive
    /// @throws IllegalStateException     if the value is neither a BLOB nor `NULL`
    /// @throws IndexOutOfBoundsException if `column` is not between 0 and [#size()]
    public byte[] getBlob(int column) {
        var value = get(column);
        if (value == null || value instanceof byte[]) {
            return (byte[]) value;
        }
        throw new IllegalStateException("Value " + column + " is " + describe(column) + ", not a BLOB");
    }

    /// {@return value `column`, a TEXT, or `null` if it is `NULL`}
    ///
    /// @param column the value's position, from 0 to [#size()] exclusive
    /// @throws IllegalStateException     if the value is neither a TEXT nor `NULL`
    /// @throws IndexOutOfBoundsException if `column` is not between 0 and [#size()]
    public String getText(int column) {
        var value = get(column);
        if (value == null || value instanceof String) {
            return (String) value;
        }
        throw new IllegalStateException("Value " + column + " is " + describe(column) + ", not a TEXT");
    }

    /// {@return the storage class of value `column`, as an article and a noun for messages}
    private String describe(int column) {
        return switch (get(column)) {
            case null -> "NULL";
            case Long _ -> "an INTEGER";
            case Double _ -> "a REAL";
            case String _ -> "a TEXT";
            default -> "a BLOB";
        };
    }

    /// {@return the rowid, if any, and the values, with BLOBs in SQL's `x'...'` notation}
    @Override
    public String toString() {
        var values = new StringJoiner(", ", "[", "]");
        for (var i = 0; i < size(); i++) {
            values.add(switch (get(i)) {
                case null -> "NULL";
                case byte[] blob -> "x'" + HexFormat.of().formatHex(blob) + "'";
                case String text -> "'" + text.replace("'", "''") + "'";
                case Object other -> other.toString();
            });
        }
        return hasRowid ? "SqliteRow[rowid=" + rowid + ", values=" + values + "]" : "SqliteRow[values=" + values + "]";
    }
}
