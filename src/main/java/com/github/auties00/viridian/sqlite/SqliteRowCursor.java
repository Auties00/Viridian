package com.github.auties00.viridian.sqlite;

import java.io.IOException;

/// The [SqliteCursor] of a table scan or seek and of an index scan or seek: a b-tree cursor whose entries are read
/// as rows of one layout.
final class SqliteRowCursor implements SqliteCursor {
    /// Where the first call to [#next()] positions the b-tree cursor.
    enum Start {
        /// The first entry.
        FIRST,
        /// The first row whose rowid is at least [#startRowid].
        ROWID,
        /// The first entry at least equal to [#startKey].
        KEY
    }

    /// The b-tree position.
    private final SqliteBTreeCursor cursor;

    /// How entries read as rows.
    private final SqliteRowLayout layout;

    /// Where the first row is.
    private final Start start;

    /// The rowid to start from, for [Start#ROWID].
    private final long startRowid;

    /// The key to start from, for [Start#KEY].
    private final SqliteKey startKey;

    /// Whether the first call to [#next()] has happened.
    private boolean started;

    /// Whether the cursor is past its last row, closed, or failed.
    private boolean ended;

    /// The current row, or `null`.
    private SqliteRow row;

    /// Creates a cursor that is before its first row.
    SqliteRowCursor(SqliteBTreeCursor cursor, SqliteRowLayout layout, Start start, long startRowid, SqliteKey startKey) {
        this.cursor = cursor;
        this.layout = layout;
        this.start = start;
        this.startRowid = startRowid;
        this.startKey = startKey;
    }

    @Override
    public boolean next() throws IOException {
        if (ended) {
            return false;
        }
        row = null;
        ended = true;
        boolean found;
        if (started) {
            found = cursor.next();
        } else {
            started = true;
            found = switch (start) {
                case FIRST -> cursor.first();
                case ROWID -> cursor.seekRowid(startRowid);
                case KEY -> cursor.seek(startKey);
            };
        }
        if (!found) {
            return false;
        }
        row = layout.read(cursor);
        ended = false;
        return true;
    }

    @Override
    public SqliteRow row() {
        if (row == null) {
            throw new IllegalStateException("The cursor is not on a row: next() has not returned true for one");
        }
        return row;
    }

    @Override
    public void close() {
        ended = true;
        row = null;
    }
}
