package com.github.auties00.viridian.indexeddb;

import com.github.auties00.viridian.IdbIndex;
import com.github.auties00.viridian.IdbKey;
import com.github.auties00.viridian.IdbKeyPath;
import com.github.auties00.viridian.IdbRecord;
import com.github.auties00.viridian.StorageBucket;
import com.github.auties00.viridian.StorageOrigin;
import com.github.auties00.viridian.ViridianException;
import com.github.auties00.viridian.io.ByteCursor;
import com.github.auties00.viridian.leveldb.LevelDb;
import com.github.auties00.viridian.io.ValueAccess;
import com.github.auties00.viridian.value.JsArray;
import com.github.auties00.viridian.value.JsArrayBuffer;
import com.github.auties00.viridian.value.JsBlob;
import com.github.auties00.viridian.value.JsFile;
import com.github.auties00.viridian.value.JsNumber;
import com.github.auties00.viridian.value.JsObject;
import com.github.auties00.viridian.value.JsString;
import com.github.auties00.viridian.value.JsValue;
import com.github.auties00.viridian.value.SerializedValue;
import com.github.auties00.viridian.value.V8Writer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The LevelDB IndexedDB writer against Viridian's reader and the entries Chrome writes, with a serializer of the
/// few values the tests store, built with [V8Writer].
class LevelDbIndexedDbWriterTest {
    private static final StorageOrigin ORIGIN = StorageOrigin.parse("http://127.0.0.1:8080");

    /// Serializes strings, numbers, plain objects, dense arrays, buffers, blobs and files.
    private static SerializedValue serialize(JsValue value) {
        var writer = V8Writer.blink();
        var externals = new ArrayList<JsBlob>();
        write(writer, value, externals);
        return new SerializedValue(writer.toByteArray(), externals);
    }

    private static void write(V8Writer writer, JsValue value, List<JsBlob> externals) {
        switch (value) {
            case JsString string when string.value().chars().allMatch(c -> c <= 0xFF) -> writer.latin1(string.value());
            case JsString string -> writer.twoByte(string.value());
            case JsNumber number -> writer.number(number.value());
            case JsObject object -> {
                writer.tag('o');
                object.properties().forEach((key, property) -> {
                    writer.latin1(key);
                    write(writer, property, externals);
                });
                writer.tag('{').varint(object.size());
            }
            case JsArray array -> {
                writer.tag('A').varint(array.length());
                for (var element : array.values()) {
                    write(writer, element, externals);
                }
                writer.tag('$').varint(0).varint(array.length());
            }
            case JsArrayBuffer buffer -> writer.tag('B').varint(buffer.byteLength()).raw(buffer.toByteArray());
            case JsFile file -> {
                writer.tag('\\').tag('e').varint(externals.size());
                externals.add(file);
            }
            case JsBlob blob -> {
                writer.tag('\\').tag('i').varint(externals.size());
                externals.add(blob);
            }
            default -> throw new IllegalArgumentException("The test serializer does not write " + value);
        }
    }

    private static JsObject object(Object... properties) {
        var map = new LinkedHashMap<String, JsValue>();
        for (var i = 0; i < properties.length; i += 2) {
            map.put((String) properties[i], (JsValue) properties[i + 1]);
        }
        return JsObject.of(map);
    }

    private static JsArray array(JsValue... elements) {
        return JsArray.of(Arrays.asList(elements));
    }

    private static Content.Record record(IdbKey key, JsValue value) {
        return new Content.Record(key, value);
    }

    private static IdbKey number(double value) {
        return new IdbKey.NumberKey(value);
    }

    private static IdbKey string(String value) {
        return new IdbKey.StringKey(value);
    }

    private static Map<String, byte[]> entries(Path directory) throws IOException {
        var map = new TreeMap<String, byte[]>();
        for (var entry : LevelDb.read(directory, _ -> true)) {
            map.put(HexFormat.of().formatHex(entry.key()), entry.value());
        }
        return map;
    }

    private static String hex(byte[]... parts) {
        var builder = new StringBuilder();
        for (var part : parts) {
            builder.append(HexFormat.of().formatHex(part));
        }
        return builder.toString();
    }

    private static List<IdbRecord> records(LevelDbDatabaseReader database, String store) throws IOException {
        try (var stream = database.records(store)) {
            return stream.toList();
        }
    }

    private static Content.Store people() {
        var byName = new IdbIndex("byName", new IdbKeyPath.Single("name"), true, false);
        var tags = new IdbIndex("tags", new IdbKeyPath.Single("tags"), false, true);
        var pair = new IdbIndex("pair", new IdbKeyPath.Compound(List.of("first", "last")), false, false);
        return Content.store("people", new IdbKeyPath.Single("id"), false, List.of(byName, tags, pair), List.of(
                record(number(2), object("id", new JsNumber(2), "name", new JsString("bob"), "tags", array(new JsString("b"), new JsString("shared")),
                        "first", new JsString("Bob"), "last", new JsString("B"))),
                record(number(1), object("id", new JsNumber(1), "name", new JsString("alice"), "tags", array(new JsString("a"), new JsString("shared")),
                        "first", new JsString("Alice"), "last", new JsString("A")))));
    }

    @Nested
    @DisplayName("read back by Viridian")
    class Readback {
        @Test
        @DisplayName("databases, stores, indexes and records read back as written, records in key order")
        void everything(@TempDir Path root) throws IOException {
            var values = Content.store("values", null, false, List.of(), List.of(
                    record(string("b"), new JsString("two-byte 你")),
                    record(string("a"), object("nested", array(new JsNumber(1), new JsString("x"))))));
            var auto = Content.store("auto", null, true, List.of(), List.of(record(number(1), new JsString("first")), record(number(7.5), new JsString("second"))));
            var empty = Content.store("empty", new IdbKeyPath.Compound(List.of("a", "b")), false, List.of(), List.of());
            var databases = List.of(
                    new Content.Database("viridian", 3, List.of(values, people(), auto, empty)),
                    new Content.Database("second database", 1, List.of(Content.store("only", null, false, List.of(),
                            List.of(record(number(1), new JsString("one")))))));
            var directory = root.resolve("http_127.0.0.1_8080.indexeddb.leveldb");
            LevelDbIndexedDbWriter.write(directory, root.resolve("http_127.0.0.1_8080.indexeddb.blob"), ORIGIN, databases, LevelDbIndexedDbWriterTest::serialize);

            var read = LevelDbDatabaseReader.openAll(directory, root.resolve("http_127.0.0.1_8080.indexeddb.blob"), StorageBucket.defaultOf(ORIGIN));
            assertEquals(List.of("second database", "viridian"), read.stream().map(LevelDbDatabaseReader::name).toList());
            try (var second = read.getFirst(); var viridian = read.get(1)) {
                assertEquals(1, second.version());
                assertEquals(new JsString("one"), records(second, "only").getFirst().value());
                assertEquals(3, viridian.version());
                assertEquals(List.of("values", "people", "auto", "empty"), viridian.objectStores().stream().map(store -> store.name()).toList());
                var peopleStore = viridian.objectStore("people").orElseThrow();
                assertEquals(Optional.of(new IdbKeyPath.Single("id")), peopleStore.keyPath());
                assertEquals(List.of(
                        new IdbIndex("byName", new IdbKeyPath.Single("name"), true, false),
                        new IdbIndex("tags", new IdbKeyPath.Single("tags"), false, true),
                        new IdbIndex("pair", new IdbKeyPath.Compound(List.of("first", "last")), false, false)), peopleStore.indexes());
                assertTrue(viridian.objectStore("auto").orElseThrow().autoIncrement());
                assertEquals(Optional.of(new IdbKeyPath.Compound(List.of("a", "b"))), viridian.objectStore("empty").orElseThrow().keyPath());
                var valueRecords = records(viridian, "values");
                assertEquals(List.of(string("a"), string("b")), valueRecords.stream().map(IdbRecord::key).toList());
                var nested = assertInstanceOf(JsObject.class, valueRecords.getFirst().value()).get("nested").orElseThrow();
                assertEquals(List.of(new JsNumber(1), new JsString("x")), assertInstanceOf(JsArray.class, nested).values());
                assertEquals(new JsString("two-byte 你"), valueRecords.get(1).value());
                assertEquals(List.of(number(1), number(2)), records(viridian, "people").stream().map(IdbRecord::key).toList());
                assertEquals(List.of(), records(viridian, "empty"));
            }
        }

        @Test
        @DisplayName("blobs and files are stored in the blob directory, numbered from 2, an empty one without a file")
        void blobs(@TempDir Path root) throws IOException {
            var modified = Instant.parse("2020-09-13T12:26:40Z");
            var value = array(JsBlob.of("hello blob".getBytes(StandardCharsets.UTF_8), "text/plain"),
                    JsFile.of("name.txt", "file body".getBytes(StandardCharsets.UTF_8), "text/plain", modified),
                    JsBlob.of(new byte[0], ""));
            var store = Content.store("files", null, false, List.of(), List.of(record(string("blobs"), value)));
            var directory = root.resolve("db");
            var blobs = root.resolve("blobs");
            LevelDbIndexedDbWriter.write(directory, blobs, ORIGIN, List.of(new Content.Database("app", 1, List.of(store))), LevelDbIndexedDbWriterTest::serialize);

            assertEquals("hello blob", Files.readString(blobs.resolve("1").resolve("00").resolve("2")));
            assertEquals("file body", Files.readString(blobs.resolve("1").resolve("00").resolve("3")));
            assertEquals(modified, Files.getLastModifiedTime(blobs.resolve("1").resolve("00").resolve("3")).toInstant());
            assertFalse(Files.exists(blobs.resolve("1").resolve("00").resolve("4")));
            // The blob number generator continues past them
            assertArrayEquals(new byte[]{5}, entries(directory).get(hex(LevelDbKeys.encode(1, 0, 0), new byte[]{5})));

            try (var database = LevelDbDatabaseReader.openAll(directory, blobs, StorageBucket.defaultOf(ORIGIN)).getFirst()) {
                var record = records(database, "files").getFirst();
                var elements = assertInstanceOf(JsArray.class, record.value()).values();
                try (var stream = assertInstanceOf(JsBlob.class, elements.getFirst()).open()) {
                    assertEquals("hello blob", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
                }
                var file = assertInstanceOf(JsFile.class, elements.get(1));
                assertEquals("name.txt", file.name());
                assertEquals(modified, file.lastModified().orElseThrow());
                assertEquals(0, assertInstanceOf(JsBlob.class, elements.get(2)).size());
            }
        }

        @Test
        @DisplayName("a large compressible value is compressed, and a large incompressible one moved into a blob")
        void largeValues(@TempDir Path root) throws IOException {
            var noise = new byte[200_000];
            new Random(5).nextBytes(noise);
            var compressible = new JsString("large value ".repeat(9000));
            var incompressible = JsArrayBuffer.of(noise);
            var small = new JsString("medium value ".repeat(80));
            var store = Content.store("values", null, false, List.of(), List.of(
                    record(string("compressible"), compressible), record(string("incompressible"), incompressible), record(string("small"), small)));
            var directory = root.resolve("db");
            var blobs = root.resolve("blobs");
            LevelDbIndexedDbWriter.write(directory, blobs, ORIGIN, List.of(new Content.Database("app", 1, List.of(store))), LevelDbIndexedDbWriterTest::serialize);

            var stored = entries(directory);
            var data = LevelDbKeys.encode(1, 1, LevelDbKeys.OBJECT_STORE_DATA);
            var compressed = stored.get(hex(data, IdbKeyCodec.encodeLevelDb(string("compressible"))));
            assertEquals("ff1102", HexFormat.of().formatHex(compressed, 1, 4));
            assertTrue(compressed.length < 10_000);
            var wrapped = stored.get(hex(data, IdbKeyCodec.encodeLevelDb(string("incompressible"))));
            assertEquals("ff1101", HexFormat.of().formatHex(wrapped, 1, 4));
            var raw = stored.get(hex(data, IdbKeyCodec.encodeLevelDb(string("small"))));
            assertEquals("ff15", HexFormat.of().formatHex(raw, 1, 3));
            // The wrapper blob is the record's only blob, typed as Blink types it
            var blobEntry = stored.get(hex(LevelDbKeys.encode(1, 1, LevelDbKeys.BLOB_ENTRY), IdbKeyCodec.encodeLevelDb(string("incompressible"))));
            var cursor = new ByteCursor(blobEntry);
            assertEquals(0, cursor.u8());
            assertEquals(2, cursor.varint64());
            assertEquals("application/vnd.blink-idb-value-wrapper", IdbKeyCodec.readUtf16BigEndian(cursor, cursor.varintLength()));

            try (var database = LevelDbDatabaseReader.openAll(directory, blobs, StorageBucket.defaultOf(ORIGIN)).getFirst()) {
                var records = records(database, "values");
                assertEquals(compressible, records.getFirst().value());
                assertArrayEquals(noise, assertInstanceOf(JsArrayBuffer.class, records.get(1).value()).toByteArray());
                assertEquals(small, records.get(2).value());
            }
        }
    }

    @Nested
    @DisplayName("the entries Chrome writes")
    class Entries {
        @Test
        @DisplayName("the global metadata, as Chrome 157 writes it for a new store")
        void global(@TempDir Path root) throws IOException {
            var directory = root.resolve("db");
            LevelDbIndexedDbWriter.write(directory, root.resolve("blobs"), ORIGIN,
                    List.of(new Content.Database("viridian", 3, List.of())), LevelDbIndexedDbWriterTest::serialize);
            var stored = entries(directory);
            assertEquals("05", HexFormat.of().formatHex(stored.get("0000000000")));
            assertEquals("01", HexFormat.of().formatHex(stored.get("0000000001")));
            // No value was written, so the data version is the oldest format the serializer writes: V8 15, Blink 21
            assertEquals("150000000f", HexFormat.of().formatHex(stored.get("0000000002")));
            assertEquals("", HexFormat.of().formatHex(stored.get("0000000003")));
            assertEquals("", HexFormat.of().formatHex(stored.get("0000000004")));
            assertEquals("0801", HexFormat.of().formatHex(stored.get("000000003200")));
            // The name key of a database Chrome 157 wrote for http://127.0.0.1:65326, at this writer's port
            assertEquals("01", HexFormat.of().formatHex(stored.get(
                    "00000000c9150068007400740070005f003100320037002e0030002e0030002e0031005f0038003000380030004000310800760069007200690064006900610" + "06e")));
            assertEquals("03", HexFormat.of().formatHex(stored.get("0001000004")));
            assertEquals("02", HexFormat.of().formatHex(stored.get("0001000005")));
        }

        @Test
        @DisplayName("store, index, record, exists and index entries, as Chrome 157 writes them")
        void store(@TempDir Path root) throws IOException {
            var directory = root.resolve("db");
            LevelDbIndexedDbWriter.write(directory, root.resolve("blobs"), ORIGIN,
                    List.of(new Content.Database("viridian", 3, List.of(people()))), LevelDbIndexedDbWriterTest::serialize);
            var stored = entries(directory);
            // Captured from the store Chrome 157 wrote for the same database, with "people" as its second store
            var expected = new LinkedHashMap<String, String>();
            expected.put("0001000003", "01");
            expected.put("00010000320100", "00700065006f0070006c0065");
            expected.put("00010000320101", "0000010200690064");
            expected.put("00010000320102", "00");
            expected.put("00010000320103", "00");
            expected.put("00010000320104", "03");
            expected.put("00010000320105", "21");
            expected.put("00010000320106", "01");
            expected.put("00010000320107", "01");
            expected.put("0001000064011f00", "00620079004e0061006d0065");
            expected.put("0001000064011f01", "01");
            expected.put("0001000064011f02", "00000104006e0061006d0065");
            expected.put("0001000064011f03", "00");
            expected.put("0001000064012003", "01");
            expected.put("0001000064012102", "00000202050066006900720073007404006c006100730074");
            expected.put("00010000c80600700065006f0070006c0065", "01");
            expected.put("00010102030000000000000040", "03");
            expected.put("0001010203000000000000f03f", "02");
            expected.put("0001011f01030062006f006200030000000000000040", "03030000000000000040");
            expected.put("0001011f01050061006c0069006300650003000000000000f03f", "0203000000000000f03f");
            expected.put("00010120010100610003000000000000f03f", "0203000000000000f03f");
            expected.put("000101200101006200030000000000000040", "03030000000000000040");
            expected.put("00010120010600730068006100720065006400030000000000000040", "03030000000000000040");
            expected.put("0001012001060073006800610072006500640003000000000000f03f", "0203000000000000f03f");
            expected.put("00010121040201030042006f00620101004200030000000000000040", "03030000000000000040");
            expected.put("00010121040201050041006c006900630065010100410003000000000000f03f", "0203000000000000f03f");
            expected.forEach((key, value) -> assertEquals(value, stored.containsKey(key) ? HexFormat.of().formatHex(stored.get(key)) : null, key));
            // Every record has its version first, from 2 in key order
            assertEquals("02", HexFormat.of().formatHex(stored.get("0001010103000000000000f03f"), 0, 1));
        }

        @Test
        @DisplayName("an auto-increment store's key generator continues past its largest number key, up to 2^53 + 1")
        void keyGenerator(@TempDir Path root) throws IOException {
            var directory = root.resolve("db");
            var stores = List.of(
                    Content.store("auto", null, true, List.of(), List.of(record(number(3.5), new JsString("a")), record(number(-9), new JsString("b")),
                            record(string("z"), new JsString("c")))),
                    Content.store("huge", null, true, List.of(), List.of(record(number(Double.POSITIVE_INFINITY), new JsString("a")))),
                    Content.store("plain", null, false, List.of(), List.of(record(number(10), new JsString("a")))));
            LevelDbIndexedDbWriter.write(directory, root.resolve("blobs"), ORIGIN, List.of(new Content.Database("app", 1, stores)), LevelDbIndexedDbWriterTest::serialize);
            var stored = entries(directory);
            assertEquals("04", HexFormat.of().formatHex(stored.get("00010000320107")));
            assertEquals("01000000000020", HexFormat.of().formatHex(stored.get("00010000320207")));
            assertEquals("01", HexFormat.of().formatHex(stored.get("00010000320307")));
        }
    }

    @Nested
    @DisplayName("what it refuses")
    class Refusals {
        @Test
        @DisplayName("two records with one key in a unique index")
        void unique(@TempDir Path root) {
            var index = new IdbIndex("byName", new IdbKeyPath.Single("name"), true, false);
            var store = Content.store("people", null, false, List.of(index), List.of(
                    record(number(1), object("name", new JsString("same"))), record(number(2), object("name", new JsString("same")))));
            assertThrows(IllegalArgumentException.class, () -> LevelDbIndexedDbWriter.write(root.resolve("db"), root.resolve("blobs"), ORIGIN,
                    List.of(new Content.Database("app", 1, List.of(store))), LevelDbIndexedDbWriterTest::serialize));
        }

        @Test
        @DisplayName("two records whose keys IndexedDB holds equal, 0 and -0")
        void equalKeys(@TempDir Path root) {
            var store = Content.store("values", null, false, List.of(), List.of(
                    record(number(0), new JsString("zero")), record(number(-0.0), new JsString("negative zero"))));
            assertThrows(IllegalArgumentException.class, () -> LevelDbIndexedDbWriter.write(root.resolve("db"), root.resolve("blobs"), ORIGIN,
                    List.of(new Content.Database("app", 1, List.of(store))), LevelDbIndexedDbWriterTest::serialize));
        }

        @Test
        @DisplayName("two databases with one name")
        void names(@TempDir Path root) {
            assertThrows(IllegalArgumentException.class, () -> LevelDbIndexedDbWriter.write(root.resolve("db"), root.resolve("blobs"), ORIGIN,
                    List.of(new Content.Database("app", 1, List.of()), new Content.Database("app", 2, List.of())), LevelDbIndexedDbWriterTest::serialize));
        }

        @Test
        @DisplayName("a blob directory that holds a file")
        void blobDirectory(@TempDir Path root) throws IOException {
            Files.createDirectories(root.resolve("blobs"));
            Files.writeString(root.resolve("blobs").resolve("stale"), "");
            assertThrows(DirectoryNotEmptyException.class, () -> LevelDbIndexedDbWriter.write(root.resolve("db"), root.resolve("blobs"), ORIGIN,
                    List.of(), LevelDbIndexedDbWriterTest::serialize));
        }

        @Test
        @DisplayName("a blob whose source supplies other than its size, leaving no database")
        void blobSize(@TempDir Path root) throws IOException {
            ValueSerializer lying = _ -> new SerializedValue(V8Writer.blink().tag('\\').tag('i').varint(0).toByteArray(),
                    List.of(ValueAccess.get().blob("", 5, () -> new ByteArrayInputStream(new byte[3]))));
            var store = Content.store("files", null, false, List.of(), List.of(record(number(1), new JsString("ignored"))));
            assertThrows(ViridianException.class, () -> LevelDbIndexedDbWriter.write(root.resolve("db"), root.resolve("blobs"), ORIGIN,
                    List.of(new Content.Database("app", 1, List.of(store))), lying));
            try (var files = Files.list(root.resolve("db"))) {
                assertFalse(files.findAny().isPresent());
            }
        }
    }
}
