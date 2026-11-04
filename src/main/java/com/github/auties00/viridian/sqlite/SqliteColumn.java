package com.github.auties00.viridian.sqlite;

/// A column of a table, as its `CREATE TABLE` statement declares it.
///
/// @param name         the column's name, without quotes
/// @param declaredType the declared type as SQLite reports it: one of `ANY`, `BLOB`, `INT`, `INTEGER`, `REAL` and
///                     `TEXT` in upper case when it is exactly one of them, otherwise as written, such as
///                     `VARCHAR(255)`; empty when the column has none
/// @param rowidAlias   whether the column is an alias for the rowid, an `INTEGER PRIMARY KEY` of a rowid table: its
///                     record field holds `NULL`, and reading the column yields the rowid
public record SqliteColumn(String name, String declaredType, boolean rowidAlias) {
}
