package com.github.auties00.viridian.indexeddb;

import com.github.auties00.viridian.IdbKey;
import com.github.auties00.viridian.IdbKeyPath;
import com.github.auties00.viridian.IdbRecord;
import com.github.auties00.viridian.StorageBucket;
import com.github.auties00.viridian.StorageOrigin;
import com.github.auties00.viridian.leveldb.LevelDbWriter;
import com.github.auties00.viridian.value.JsBlob;
import com.github.auties00.viridian.value.JsObject;
import com.github.auties00.viridian.value.JsString;
import com.github.auties00.viridian.value.V8Writer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The LevelDB IndexedDB backend against an origin's store written by hand: metadata, records, blobs and wrapped
/// values.
class LevelDbDatabaseReaderTest {
    private static final StorageOrigin ORIGIN = StorageOrigin.parse("https://example.com");

    private static byte[] key(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static byte[] stringWithLength(String value) {
        var out = new ByteArrayOutputStream();
        LevelDbWriter.writeVarint(out, value.length());
        out.writeBytes(value.getBytes(StandardCharsets.UTF_16BE));
        return out.toByteArray();
    }

    private static byte[] varint(long value) {
        var out = new ByteArrayOutputStream();
        LevelDbWriter.writeVarint(out, value);
        return out.toByteArray();
    }

    private static byte[] value(byte[] serialized) {
        return key(varint(1), serialized);
    }

    @Test
    @DisplayName("databases, stores, indexes and records read as the backend wrote them, records in key order")
    void readsEverything(@TempDir Path root) throws IOException {
        var directory = root.resolve("https_example.com_0.indexeddb.leveldb");
        var blobs = root.resolve("https_example.com_0.indexeddb.blob");
        var writer = new LevelDbWriter();
        var global = LevelDbKeys.encode(0, 0, 0);
        writer.put(key(global, new byte[]{0}), new byte[]{5});
        writer.put(key(global, new byte[]{(byte) 201}, stringWithLength("https_example.com_0"), stringWithLength("app")), new byte[]{1});
        writer.put(key(global, new byte[]{(byte) 201}, stringWithLength("https_example.com_0"), stringWithLength("abandoned")), new byte[]{2});
        var database = LevelDbKeys.encode(1, 0, 0);
        writer.put(key(database, new byte[]{4}), varint(3));
        writer.put(key(LevelDbKeys.encode(2, 0, 0), new byte[]{4}), varint(0));
        // Object store 1, "users", key path "id"; object store 2, "files", no key path, auto-increment
        writer.put(key(database, new byte[]{50}, varint(1), new byte[]{0}), "users".getBytes(StandardCharsets.UTF_16BE));
        writer.put(key(database, new byte[]{50}, varint(1), new byte[]{1}), key(new byte[]{0, 0, 1}, stringWithLength("id")));
        writer.put(key(database, new byte[]{50}, varint(1), new byte[]{6}), new byte[]{1});
        writer.put(key(database, new byte[]{50}, varint(1), new byte[]{2}), new byte[]{0});
        writer.put(key(database, new byte[]{50}, varint(2), new byte[]{0}), "files".getBytes(StandardCharsets.UTF_16BE));
        writer.put(key(database, new byte[]{50}, varint(2), new byte[]{1}), new byte[]{0, 0, 0});
        writer.put(key(database, new byte[]{50}, varint(2), new byte[]{6}), new byte[]{0});
        writer.put(key(database, new byte[]{50}, varint(2), new byte[]{2}), new byte[]{1});
        // Index 30 of "users", "byName", unique, on "name"
        writer.put(key(database, new byte[]{100}, varint(1), varint(30), new byte[]{0}), "byName".getBytes(StandardCharsets.UTF_16BE));
        writer.put(key(database, new byte[]{100}, varint(1), varint(30), new byte[]{1}), new byte[]{1});
        writer.put(key(database, new byte[]{100}, varint(1), varint(30), new byte[]{2}), key(new byte[]{0, 0, 1}, stringWithLength("name")));
        writer.put(key(database, new byte[]{100}, varint(1), varint(30), new byte[]{3}), new byte[]{0});

        var users = LevelDbKeys.encode(1, 1, LevelDbKeys.OBJECT_STORE_DATA);
        var bob = V8Writer.blink().tag('o').latin1("name").latin1("bob").tag('{').varint(1).toByteArray();
        var alice = V8Writer.blink().tag('o').latin1("name").latin1("alice").tag('{').varint(1).toByteArray();
        writer.put(key(users, IdbKeyCodec.encodeLevelDb(new IdbKey.StringKey("u2"))), value(bob));
        writer.put(key(users, IdbKeyCodec.encodeLevelDb(new IdbKey.StringKey("u1"))), value(alice));
        // A Snappy-wrapped value
        var wrapped = key(new byte[]{(byte) 0xFF, 0x11, 0x02}, LevelDbWriter.snappyLiterals(bob));
        writer.put(key(users, IdbKeyCodec.encodeLevelDb(new IdbKey.NumberKey(-5))), value(wrapped));

        // A record of "files" whose value holds a blob, and one whose value is itself in a blob
        var files = LevelDbKeys.encode(1, 2, LevelDbKeys.OBJECT_STORE_DATA);
        var fileBlobs = LevelDbKeys.encode(1, 2, LevelDbKeys.BLOB_ENTRY);
        var withBlob = V8Writer.blink().tag('\\').tag('i').varint(0).toByteArray();
        var oneKey = IdbKeyCodec.encodeLevelDb(new IdbKey.NumberKey(1));
        writer.put(key(files, oneKey), value(withBlob));
        writer.put(key(fileBlobs, oneKey), key(new byte[]{0}, varint(0x1234), stringWithLength("text/plain"), varint(5)));
        Files.createDirectories(blobs.resolve("1").resolve("12"));
        Files.writeString(blobs.resolve("1").resolve("12").resolve("1234"), "hello");
        var large = V8Writer.blink().latin1("x".repeat(70_000)).toByteArray();
        var twoKey = IdbKeyCodec.encodeLevelDb(new IdbKey.NumberKey(2));
        writer.put(key(files, twoKey), value(key(new byte[]{(byte) 0xFF, 0x11, 0x01}, varint(large.length), varint(0))));
        writer.put(key(fileBlobs, twoKey), key(new byte[]{0}, varint(0x20), stringWithLength(""), varint(large.length)));
        Files.createDirectories(blobs.resolve("1").resolve("00"));
        Files.write(blobs.resolve("1").resolve("00").resolve("20"), large);
        writer.writeLog(directory, 3);

        var databases = LevelDbDatabaseReader.openAll(directory, blobs, StorageBucket.defaultOf(ORIGIN));
        assertEquals(1, databases.size(), "the abandoned database is left out");
        try (var app = databases.getFirst()) {
            assertEquals("app", app.name());
            assertEquals(3, app.version());
            var usersStore = app.objectStore("users").orElseThrow();
            assertEquals(java.util.Optional.of(new IdbKeyPath.Single("id")), usersStore.keyPath());
            var index = usersStore.index("byName").orElseThrow();
            assertTrue(index.unique());
            assertEquals(new IdbKeyPath.Single("name"), index.keyPath());
            var filesStore = app.objectStore("files").orElseThrow();
            assertTrue(filesStore.autoIncrement());
            assertTrue(filesStore.keyPath().isEmpty());

            List<IdbRecord> records;
            try (var stream = app.records("users")) {
                records = stream.toList();
            }
            assertEquals(List.of(new IdbKey.NumberKey(-5), new IdbKey.StringKey("u1"), new IdbKey.StringKey("u2")),
                    records.stream().map(IdbRecord::key).toList());
            assertEquals(new JsString("bob"), assertInstanceOf(JsObject.class, records.getFirst().value()).get("name").orElseThrow());
            assertEquals(new JsString("alice"), assertInstanceOf(JsObject.class, records.get(1).value()).get("name").orElseThrow());

            var blobRecord = app.get("files", new IdbKey.NumberKey(1)).orElseThrow();
            try (var stream = assertInstanceOf(JsBlob.class, blobRecord).open()) {
                assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), stream.readAllBytes());
            }
            var inBlob = app.get("files", new IdbKey.NumberKey(2)).orElseThrow();
            assertEquals(new JsString("x".repeat(70_000)), inBlob);
            assertTrue(app.get("files", new IdbKey.NumberKey(3)).isEmpty());
        }
    }
}
