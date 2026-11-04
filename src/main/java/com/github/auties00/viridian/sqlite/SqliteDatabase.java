package com.github.auties00.viridian.sqlite;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/// A SQLite database file, read in pure Java without SQLite itself.
///
/// [#open(Path)] reads the file as SQLite would see it after recovering it: the pages of a hot rollback journal, left
/// by a writer that crashed or is in the middle of a transaction, take the place of the pages that transaction
/// changed, and the committed frames of the write-ahead log, the `-wal` file, take the place of the pages they hold.
/// Frames after the last commit are ignored, so a transaction still being written is never seen. The `-shm` file is
/// never opened: the log is replayed from its own checksums.
///
/// Reading never writes, never creates a file and never takes a lock, so it cannot disturb SQLite or another process
/// using the database. The flip side is that a database another process is writing is read without coordination:
/// the journal and the log are read once, when the database is opened, and pages of the database file later, as they
/// are needed, so a writer that checkpoints or commits in the meantime can make the view inconsistent, which may
/// surface as an [IOException]. Copying the files while no transaction is active, or closing and reopening, gives a
/// consistent view.
///
/// Pages are read with positional reads and kept in a bounded cache of recently used pages; nothing is read in full
/// up front. A malformed or hostile file makes the methods that read it throw an [IOException] naming what is wrong,
/// rather than loop or fail otherwise.
///
/// A database is not safe for concurrent use: the database, and the tables, indexes, cursors and rows obtained from
/// it, must be used by one thread at a time. While it is open, the database keeps its file and its write-ahead log
/// open.
///
/// @spec https://www.sqlite.org/fileformat2.html Database File Format
/// @spec https://www.sqlite.org/walformat.html WAL-mode File Format
public final class SqliteDatabase implements AutoCloseable {
    /// The pages of the database.
    private final SqlitePager pager;

    /// The tables found so far, by name in lower case.
    private final Map<String, SqliteTable> tables = new HashMap<>();

    /// The indexes found so far, by name in lower case.
    private final Map<String, SqliteIndex> indexes = new HashMap<>();

    /// The rows of `sqlite_schema`, read on first use.
    private List<SqliteSchemaEntry> schema;

    /// Wraps an opened database.
    private SqliteDatabase(SqlitePager pager) {
        this.pager = pager;
    }

    /// Opens the database at `file` for reading.
    ///
    /// The database header is read and checked, a hot rollback journal next to the file is read into memory, and a
    /// write-ahead log next to it is replayed. A file of zero bytes is an empty database, as it is to SQLite.
    ///
    /// @param file the database file, whose `-wal` and `-journal` siblings are read too if they exist
    /// @return the open database
    /// @throws IOException if the file cannot be read, is not a SQLite database, uses a format this reader does not
    ///                     know, or its journal or log is inconsistent with it
    public static SqliteDatabase open(Path file) throws IOException {
        return new SqliteDatabase(SqlitePager.open(Objects.requireNonNull(file, "file")));
    }

    /// {@return the size of every page, a power of two from 512 to 65536}
    public int pageSize() {
        return pager.pageSize();
    }

    /// {@return the number of pages in the database}
    public int pageCount() {
        return pager.pageCount();
    }

    /// {@return the encoding of every string in the database}
    public SqliteTextEncoding textEncoding() {
        return pager.textEncoding();
    }

    /// {@return the user version, the integer that `PRAGMA user_version` sets}
    public int userVersion() {
        return pager.userVersion();
    }

    /// {@return the rows of `sqlite_schema`, which describe every table, index, view and trigger, in b-tree order}
    ///
    /// @throws IOException if the database is closed, or `sqlite_schema` is malformed
    public List<SqliteSchemaEntry> schema() throws IOException {
        if (schema == null) {
            var entries = new ArrayList<SqliteSchemaEntry>();
            if (pager.pageCount() > 0) {
                try (var cursor = SqliteTable.schema(pager).scan()) {
                    while (cursor.next()) {
                        entries.add(entry(cursor.row()));
                    }
                }
            }
            schema = List.copyOf(entries);
        }
        return schema;
    }

    /// {@return the `sqlite_schema` row that `row` holds}
    ///
    /// @throws IOException if a value is not of the type SQLite writes there
    private static SqliteSchemaEntry entry(SqliteRow row) throws IOException {
        if (!(row.get(0) instanceof String type) || !(row.get(1) instanceof String name) || !(row.get(2) instanceof String tableName)) {
            throw new IOException("Row " + row.rowid() + " of sqlite_schema is malformed: its type, name or table name is not text");
        }
        var rootPage = switch (row.get(3)) {
            case null -> 0L;
            case Long page -> page;
            default -> throw new IOException("Row " + row.rowid() + " of sqlite_schema is malformed: its root page is not an integer");
        };
        var sql = switch (row.get(4)) {
            case null -> null;
            case String text -> text;
            default -> throw new IOException("Row " + row.rowid() + " of sqlite_schema is malformed: its SQL is not text");
        };
        return new SqliteSchemaEntry(type, name, tableName, rootPage, sql);
    }

    /// {@return the table named `name`, or an empty optional if there is none}
    ///
    /// Names are compared as SQLite compares them, ignoring the case of ASCII letters only. Views are not tables;
    /// `sqlite_schema`, also known as `sqlite_master`, is.
    ///
    /// @param name the table's name, unquoted
    /// @throws IOException if the database is closed, `sqlite_schema` is malformed, the table's `CREATE TABLE`
    ///                     statement cannot be parsed, or the table is a virtual table, which keeps no rows of its own
    ///                     in the file
    public Optional<SqliteTable> findTable(String name) throws IOException {
        Objects.requireNonNull(name, "name");
        var key = SqliteBytes.lowercase(name);
        var table = tables.get(key);
        if (table != null) {
            return Optional.of(table);
        }
        if (key.equals("sqlite_schema") || key.equals("sqlite_master")) {
            table = SqliteTable.schema(pager);
        } else {
            for (var entry : schema()) {
                if (entry.type().equals("table") && SqliteBytes.equalsIgnoreCase(entry.name(), name)) {
                    table = SqliteTable.of(pager, entry);
                    break;
                }
            }
            if (table == null) {
                return Optional.empty();
            }
        }
        tables.put(key, table);
        return Optional.of(table);
    }

    /// {@return the index named `name`, or an empty optional if there is none}
    ///
    /// Names are compared as SQLite compares them, ignoring the case of ASCII letters only. The indexes SQLite creates
    /// for `UNIQUE` and `PRIMARY KEY` constraints, named `sqlite_autoindex_<table>_<n>`, are found too, their columns
    /// derived from the table's constraints.
    ///
    /// @param name the index's name, unquoted
    /// @throws IOException if the database is closed, `sqlite_schema` is malformed, the index's statement or its
    ///                     table's cannot be parsed, or its table is missing
    public Optional<SqliteIndex> findIndex(String name) throws IOException {
        Objects.requireNonNull(name, "name");
        var key = SqliteBytes.lowercase(name);
        var index = indexes.get(key);
        if (index != null) {
            return Optional.of(index);
        }
        for (var entry : schema()) {
            if (entry.type().equals("index") && SqliteBytes.equalsIgnoreCase(entry.name(), name)) {
                var table = findTable(entry.tableName())
                        .orElseThrow(() -> new IOException("Index " + entry.name() + " belongs to table " + entry.tableName() + ", which does not exist"));
                index = SqliteIndex.of(pager, entry, table);
                indexes.put(key, index);
                return Optional.of(index);
            }
        }
        return Optional.empty();
    }

    /// {@return the number of levels of the b-tree rooted at `rootPage`, along its leftmost path}
    ///
    /// @param table whether the b-tree is a table b-tree rather than an index b-tree
    int treeDepth(long rootPage, boolean table) throws IOException {
        var cursor = new SqliteBTreeCursor(pager, rootPage, table, true);
        return cursor.first() ? cursor.depth() : 1;
    }

    /// Closes the database file and its write-ahead log; tables, indexes and cursors of the database stop working,
    /// while rows already read stay readable. Closing again has no effect.
    ///
    /// @throws IOException if closing a file fails
    @Override
    public void close() throws IOException {
        pager.close();
    }
}
