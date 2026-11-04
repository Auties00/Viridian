package com.github.auties00.viridian.sqlite;

import com.github.auties00.viridian.sqlite.SqliteTesting.Jdbc;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Checks the reader against SQLite on databases of every page size and text encoding, each holding rowid tables
/// with and without a rowid alias, a `WITHOUT ROWID` table, and plain, composite, unique and partial indexes filled
/// with values of every serial type.
@DisplayName("Database formats")
class SqliteFormatTest {
    @TempDir
    Path directory;

    static Stream<Arguments> formats() {
        return Stream.of(512, 1024, 4096, 65536)
                .flatMap(pageSize -> SqliteTesting.ENCODINGS.stream().map(encoding -> Arguments.of(pageSize, encoding)));
    }

    @ParameterizedTest(name = "{0}-byte pages in {1}")
    @MethodSource("formats")
    @DisplayName("reads every table and index as SQLite does")
    void matchesSqlite(int pageSize, String encoding) throws IOException {
        var file = directory.resolve("format.db");
        try (var jdbc = Jdbc.open(file)) {
            jdbc.execute("PRAGMA page_size = " + pageSize, "PRAGMA encoding = '" + encoding + "'");
            populate(jdbc, pageSize);
        }
        try (var database = SqliteDatabase.open(file); var jdbc = Jdbc.open(file)) {
            assertEquals(pageSize, database.pageSize());
            assertEquals(switch (encoding) {
                case "UTF-8" -> SqliteTextEncoding.UTF_8;
                case "UTF-16le" -> SqliteTextEncoding.UTF_16LE;
                default -> SqliteTextEncoding.UTF_16BE;
            }, database.textEncoding());
            assertEquals(jdbc.value("PRAGMA page_count"), (long) database.pageCount());
            SqliteTesting.assertMatchesJdbc(database, jdbc);
        }
    }

    @Test
    @DisplayName("treats a zero-length file as an empty database")
    void emptyFile() throws IOException {
        var file = Files.createFile(directory.resolve("empty.db"));
        try (var database = SqliteDatabase.open(file)) {
            assertTrue(database.schema().isEmpty());
            assertTrue(database.findTable("anything").isEmpty());
            assertEquals(0, database.pageCount());
        }
    }

    @Test
    @DisplayName("reads the header fields SQLite reports")
    void header() throws IOException {
        var file = directory.resolve("header.db");
        try (var jdbc = Jdbc.open(file)) {
            jdbc.execute("PRAGMA user_version = 31337", "CREATE TABLE t(a)");
        }
        try (var database = SqliteDatabase.open(file)) {
            assertEquals(31337, database.userVersion());
            assertEquals(SqlitePager.DEFAULT_PAGE_SIZE, database.pageSize());
            assertEquals(SqliteTextEncoding.UTF_8, database.textEncoding());
        }
    }

    /// Creates the format test's tables and fills them.
    static void populate(Jdbc jdbc, int pageSize) {
        jdbc.execute(
                "CREATE TABLE items(id INTEGER PRIMARY KEY, name TEXT, score REAL, data BLOB, anything, small INT)",
                "CREATE INDEX items_by_name ON items(name, score)",
                "CREATE UNIQUE INDEX items_by_small ON items(small)",
                "CREATE INDEX items_partial ON items(score) WHERE data IS NULL",
                "CREATE TABLE plain(a, b, c)",
                "CREATE INDEX plain_by_cb ON plain(c, b)",
                "CREATE TABLE pairs(x TEXT, y INTEGER, z, w REAL, PRIMARY KEY(y, x)) WITHOUT ROWID",
                "CREATE INDEX pairs_by_z ON pairs(z)",
                "CREATE INDEX pairs_by_wx ON pairs(w, x)");
        var random = new Random(pageSize);
        var special = SqliteTesting.specialValues();
        var items = new ArrayList<Object[]>();
        var plain = new ArrayList<Object[]>();
        var pairs = new ArrayList<Object[]>();
        var rows = 2000;
        for (var i = 0; i < rows; i++) {
            var value = special(special, i, random);
            var name = random.nextInt(20) == 0 ? null : SqliteTesting.randomText(random, random.nextInt(30));
            Object score = switch (random.nextInt(4)) {
                case 0 -> null;
                case 1 -> (double) random.nextInt(100);
                default -> random.nextDouble() * 1000 - 500;
            };
            var data = random.nextInt(3) == 0 ? null : SqliteTesting.randomBytes(random, random.nextInt(i % 97 == 0 ? 5000 : 40));
            items.add(new Object[]{(long) i * 37 - 30_000, name, score, data, value, (long) i});
            plain.add(new Object[]{value, SqliteTesting.randomText(random, random.nextInt(12)), (long) random.nextInt(50)});
            pairs.add(new Object[]{"k" + i, (long) random.nextInt(100), special(special, i + 11, random), random.nextDouble()});
        }
        jdbc.batch("INSERT INTO items VALUES (?, ?, ?, ?, ?, ?)", items);
        jdbc.batch("INSERT INTO plain VALUES (?, ?, ?)", plain);
        jdbc.batch("INSERT INTO pairs VALUES (?, ?, ?, ?)", pairs);
    }

    /// {@return the special value for row `row`, with the multi-page ones only in the first round}
    private static Object special(List<Object> special, int row, Random random) {
        var value = special.get(row % special.size());
        if (row >= special.size() && (value instanceof String text && text.length() > 1000 || value instanceof byte[] bytes && bytes.length > 1000)) {
            return SqliteTesting.randomText(random, 5);
        }
        return value;
    }
}
