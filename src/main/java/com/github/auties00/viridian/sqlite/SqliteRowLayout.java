package com.github.auties00.viridian.sqlite;

import java.io.IOException;

/// How the values of a row map onto the fields of the records of one table or index.
///
/// A rowid table's record holds one field per stored column, in declaration order, with `NULL` in the field of a
/// rowid alias, whose value is the cell's rowid; `VIRTUAL` generated columns have no field. A `WITHOUT ROWID`
/// table's record holds its primary key columns first, then its other stored columns in declaration order. An index
/// entry holds the indexed values, then the rowid of its row or, for a `WITHOUT ROWID` table, the primary key
/// columns the index does not already hold. A record with fewer fields than its table has columns predates an
/// `ALTER TABLE ADD COLUMN`, and the missing columns read as their defaults.
///
/// @spec https://www.sqlite.org/fileformat2.html Database File Format
final class SqliteRowLayout {
    /// The field number of a column that reads as the rowid.
    static final int ROWID = -1;

    /// The field number of a `VIRTUAL` generated column, which has no field.
    static final int NOT_STORED = -2;

    /// Where a row's rowid comes from.
    enum RowidSource {
        /// The table b-tree cell's key, for a rowid table.
        CELL,
        /// The last field of the entry, for an index on a rowid table.
        LAST_FIELD,
        /// Nowhere: the row belongs to a `WITHOUT ROWID` table.
        NONE
    }

    /// What the rows belong to, such as `table t`, for error messages.
    private final String owner;

    /// The text encoding.
    private final SqliteTextEncoding encoding;

    /// The record field of each value, or [#ROWID].
    private final int[] fields;

    /// The value of each column when its field is past the end of the record.
    private final Object[] defaults;

    /// Whether each value has REAL affinity, so that an INTEGER field reads as a REAL.
    private final boolean[] real;

    /// Where the rowid comes from.
    private final RowidSource rowidSource;

    /// The fields every record must have.
    private final int minimumFields;

    /// Creates a layout.
    SqliteRowLayout(String owner, SqliteTextEncoding encoding, int[] fields, Object[] defaults, boolean[] real,
                    RowidSource rowidSource, int minimumFields) {
        this.owner = owner;
        this.encoding = encoding;
        this.fields = fields;
        this.defaults = defaults;
        this.real = real;
        this.rowidSource = rowidSource;
        this.minimumFields = minimumFields;
    }

    /// {@return the row the cursor is on}
    ///
    /// @throws IOException if the row's record is malformed, or an index entry lacks fields or a rowid
    SqliteRow read(SqliteBTreeCursor cursor) throws IOException {
        var record = cursor.record();
        if (record.size() < minimumFields) {
            throw new IOException("An entry of " + owner + " has " + record.size() + " fields, fewer than the " + minimumFields + " it needs");
        }
        return switch (rowidSource) {
            case CELL -> new SqliteRow(this, record, cursor.rowid(), true);
            case LAST_FIELD -> {
                var field = minimumFields - 1;
                if (!record.isInteger(field)) {
                    throw new IOException("An entry of " + owner + " ends with a field of serial type " + record.serialType(field)
                            + " where the rowid of its row belongs");
                }
                yield new SqliteRow(this, record, record.integer(field), true);
            }
            case NONE -> new SqliteRow(this, record, 0, false);
        };
    }

    /// {@return the number of values in a row}
    int size() {
        return fields.length;
    }

    /// {@return the record field of value `column`, or [#ROWID]}
    int field(int column) {
        return fields[column];
    }

    /// {@return the value of `column` when its field is past the end of the record}
    Object defaultValue(int column) {
        return defaults[column];
    }

    /// {@return whether `column` has REAL affinity}
    boolean real(int column) {
        return real[column];
    }

    /// {@return the text encoding}
    SqliteTextEncoding encoding() {
        return encoding;
    }
}
