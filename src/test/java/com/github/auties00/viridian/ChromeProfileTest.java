package com.github.auties00.viridian;

import com.github.auties00.viridian.value.JsDescriber;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// A profile a real Chrome wrote, which CI makes with `ci/chrome/generate.mjs`: everything Viridian reads must match
/// what the page read back, line for line.
///
/// `VIRIDIAN_CHROME_PROFILE` names the profile directory and `VIRIDIAN_CHROME_MANIFEST` the page's description of
/// what it stored.
@EnabledIfEnvironmentVariable(named = "VIRIDIAN_CHROME_MANIFEST", matches = ".+")
class ChromeProfileTest {
    private static String keyPath(IdbKeyPath path) {
        return switch (path) {
            case null -> "-";
            case IdbKeyPath.Single single -> "S" + JsDescriber.hex16(single.path());
            case IdbKeyPath.Compound compound -> "[" + compound.paths().stream().map(JsDescriber::hex16).collect(Collectors.joining(";")) + "]";
        };
    }

    private static String key(IdbKey key) {
        return switch (key) {
            case IdbKey.NumberKey number -> "N" + JsDescriber.number(number.value());
            case IdbKey.DateKey date -> "D" + JsDescriber.number(date.time());
            case IdbKey.StringKey string -> "S" + JsDescriber.hex16(string.value());
            case IdbKey.BinaryKey binary -> "X" + HexFormat.of().formatHex(binary.bytes());
            case IdbKey.ArrayKey array -> "[" + array.elements().stream().map(ChromeProfileTest::key).collect(Collectors.joining(";")) + "]";
        };
    }

    private static String unhex16(String hex) {
        var characters = new char[hex.length() / 4];
        for (var i = 0; i < characters.length; i++) {
            characters[i] = (char) Integer.parseInt(hex, 4 * i, 4 * i + 4, 16);
        }
        return new String(characters);
    }

    @Test
    @DisplayName("every database, store, record and storage item reads as the page that wrote it read it back")
    void matchesChrome() throws IOException {
        var manifest = Files.readAllLines(Path.of(System.getenv("VIRIDIAN_CHROME_MANIFEST")), StandardCharsets.UTF_8);
        var origin = StorageOrigin.parse(manifest.get(0).split("\t")[1]);
        var backend = StorageBackend.valueOf(manifest.get(1).split("\t")[1].toUpperCase());
        var expected = manifest.subList(2, manifest.size());
        var profile = ChromiumProfile.open(Path.of(System.getenv("VIRIDIAN_CHROME_PROFILE")));
        // The workflow runs Chrome with IndexedDB and DOM Storage on the same backend
        assertEquals(backend, profile.indexedDb().backend(origin).orElseThrow(), "the IndexedDB backend Chrome used");
        assertEquals(backend, profile.localStorage().backend().orElseThrow(), "the localStorage backend Chrome used");
        assertEquals(backend, profile.sessionStorage().backend().orElseThrow(), "the sessionStorage backend Chrome used");
        var indexedDbOrigins = profile.indexedDb().origins();
        assertTrue(indexedDbOrigins.contains(origin), origin + " is not among the IndexedDB origins " + indexedDbOrigins);
        var localStorageOrigins = profile.localStorage().origins();
        assertTrue(localStorageOrigins.contains(origin), origin + " is not among the localStorage origins " + localStorageOrigins);

        // Each context of the page, in the order the manifest names them: "" for its default bucket, "bucket:<name>" for a
        // named bucket, "partitioned:<top-level site>" for the page in a frame another site embeds
        var contexts = new LinkedHashSet<String>();
        contexts.add("");
        for (var line : expected) {
            contexts.add(context(line));
        }
        var actual = new ArrayList<String>();
        var local = new ArrayList<String>();
        for (var context : contexts) {
            var prefix = context.isEmpty() ? "" : "@" + context + "\t";
            var key = context.startsWith("partitioned:") ? StorageKey.partitioned(origin, context.substring("partitioned:".length())) : StorageKey.of(origin);
            var bucket = context.startsWith("bucket:") ? new StorageBucket(key, context.substring("bucket:".length())) : StorageBucket.defaultOf(key);
            assertTrue(profile.indexedDb().buckets().contains(bucket), bucket + " is not among the buckets " + profile.indexedDb().buckets());
            for (var line : expected) {
                if (!context(line).equals(context) || !content(line).startsWith("database\t")) {
                    continue;
                }
                var name = unhex16(content(line).split("\t")[1]);
                describe(profile, bucket, name, backend).forEach(described -> actual.add(prefix + described));
            }
            if (!context.startsWith("bucket:")) {
                profile.localStorage().items(key).forEach((item, value) -> local.add(prefix + String.join("\t", "local", JsDescriber.hex16(item), JsDescriber.hex16(value))));
            }
        }
        var session = new ArrayList<String>();
        for (var area : profile.sessionStorage().areas(origin)) {
            area.items().forEach((key, value) -> session.add(String.join("\t", "session", JsDescriber.hex16(key), JsDescriber.hex16(value))));
        }

        // Index entries are compared by ci/chrome/read.mjs, between Chrome's profile and a copy of it
        compare("IndexedDB", expected.stream().filter(line -> !content(line).startsWith("local\t") && !content(line).startsWith("session\t")
                && !content(line).startsWith("index\t")).toList(), actual);
        compare("localStorage", expected.stream().filter(line -> content(line).startsWith("local\t")).sorted().toList(), local.stream().sorted().toList());
        compare("sessionStorage", expected.stream().filter(line -> line.startsWith("session\t")).sorted().toList(), session.stream().sorted().toList());
    }

    /// {@return the context a manifest line belongs to: what follows its `@`, or "" for the page's default bucket}
    private static String context(String line) {
        return line.startsWith("@") ? line.substring(1, line.indexOf('\t')) : "";
    }

    /// {@return a manifest line without its context}
    private static String content(String line) {
        return line.startsWith("@") ? line.substring(line.indexOf('\t') + 1) : line;
    }

    /// {@return the lines that describe the database `name` of `bucket`, as the page describes it}
    private static List<String> describe(ChromiumProfile profile, StorageBucket bucket, String name, StorageBackend backend) throws IOException {
        var lines = new ArrayList<String>();
        try (var database = profile.indexedDb().open(bucket, name).orElseThrow(() -> new AssertionError("No database " + name + " in " + bucket))) {
            assertEquals(backend, database.backend());
            assertEquals(bucket, database.bucket());
            lines.add(String.join("\t", "database", JsDescriber.hex16(name), Long.toString(database.version())));
            var stores = new ArrayList<>(database.objectStores());
            // DOMStringList, which objectStoreNames and indexNames are, sorts its names
            stores.sort(Comparator.comparing(IdbObjectStore::name));
            for (var store : stores) {
                var indexes = store.indexes().stream()
                        .sorted(Comparator.comparing(IdbIndex::name))
                        .map(index -> JsDescriber.hex16(index.name()) + ":" + keyPath(index.keyPath()) + ":" + (index.unique() ? 1 : 0) + ":" + (index.multiEntry() ? 1 : 0))
                        .collect(Collectors.joining(","));
                lines.add(String.join("\t", "store", JsDescriber.hex16(name), JsDescriber.hex16(store.name()), keyPath(store.keyPath().orElse(null)),
                        store.autoIncrement() ? "1" : "0", indexes));
                try (var records = database.records(store.name())) {
                    records.forEach(record -> lines.add(String.join("\t", "record", JsDescriber.hex16(name), JsDescriber.hex16(store.name()),
                            key(record.key()), JsDescriber.describe(record.value()))));
                }
            }
            for (var store : stores) {
                var generator = database.keyGenerator(store.name());
                if (generator.isPresent()) {
                    lines.add(String.join("\t", "generator", JsDescriber.hex16(name), JsDescriber.hex16(store.name()), "N" + generator.getAsLong()));
                }
            }
        }
        return lines;
    }

    /// Fails on the first line that differs, showing both lines cut short, since a line may hold megabytes.
    private static void compare(String what, List<String> expected, List<String> actual) {
        for (var i = 0; i < Math.max(expected.size(), actual.size()); i++) {
            var wanted = i < expected.size() ? expected.get(i) : "<none>";
            var got = i < actual.size() ? actual.get(i) : "<none>";
            if (!wanted.equals(got)) {
                var at = 0;
                while (at < Math.min(wanted.length(), got.length()) && wanted.charAt(at) == got.charAt(at)) {
                    at++;
                }
                var from = Math.max(0, at - 120);
                fail(what + " line " + i + " differs at character " + at + ":\n  Chrome:   ..." + wanted.substring(from, Math.min(wanted.length(), at + 200))
                        + "\n  Viridian: ..." + got.substring(from, Math.min(got.length(), at + 200)));
            }
        }
    }
}
