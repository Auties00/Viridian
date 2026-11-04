package com.github.auties00.viridian.sqlite;

/// A row of `sqlite_schema`, the table rooted at page 1 that lists every table, index, view and trigger of a database.
///
/// @param type      `table`, `index`, `view` or `trigger`
/// @param name      the object's name
/// @param tableName the table the object belongs to; for a table, its own name
/// @param rootPage  the page number of the object's b-tree root, or 0 for a view, a trigger or a virtual table
/// @param sql       the `CREATE` statement as it was written, or `null` for an index that SQLite creates itself to
///                  enforce a `UNIQUE` or `PRIMARY KEY` constraint
/// @spec https://www.sqlite.org/fileformat2.html Database File Format
public record SqliteSchemaEntry(String type, String name, String tableName, long rootPage, String sql) {
}
