package com.github.auties00.viridian.sqlite;

import java.io.IOException;

/// A forward-only cursor over the rows of a table or the entries of an index, in b-tree order.
///
/// A cursor reads its b-tree as it advances, a page at a time, and keeps no row but the current one. It holds no
/// operating-system resource, so [#close()] only ends it. Like its database, a cursor is not safe for concurrent use.
public interface SqliteCursor extends AutoCloseable {
    /// Advances to the next row and reads it in full, overflow pages included.
    ///
    /// Once this method throws, the cursor is at its end.
    ///
    /// @return `true` if the cursor is on a row, `false` at the end or once the cursor is closed
    /// @throws IOException if the database is closed or a page the row needs is malformed
    boolean next() throws IOException;

    /// {@return the current row, which stays readable after the cursor moves}
    ///
    /// @throws IllegalStateException if the last call to [#next()] did not return `true`
    SqliteRow row();

    /// Ends the cursor, so that [#next()] returns `false`; closing it again has no effect.
    @Override
    void close();
}
