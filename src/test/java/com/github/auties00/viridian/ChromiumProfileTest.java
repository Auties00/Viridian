package com.github.auties00.viridian;

import com.github.auties00.viridian.value.JsBlob;
import com.github.auties00.viridian.value.JsFile;
import com.github.auties00.viridian.value.JsObject;
import com.github.auties00.viridian.value.JsValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The profile facades end to end on profiles of temporary directories: what each write leaves, on both backends,
/// grouped in edits, and refused while the profile is in use. Real Chrome reading what they write is the CI's job.
class ChromiumProfileTest {
    private static final StorageOrigin ORIGIN = StorageOrigin.parse("https://app.example.com");
    private static final IdbObjectStore SETTINGS = IdbObjectStore.of("settings");
    private static final IdbObjectStore PEOPLE = IdbObjectStore.builder("people").keyPath("id").index("byName", "name").build();

    private static IdbDatabase database(String name, String theme) {
        return IdbDatabase.builder(name, 1)
                .put(SETTINGS, IdbKey.of("theme"), JsValue.of(theme))
                .put(PEOPLE, JsObject.builder().put("id", 1).put("name", "Alice").build())
                .build();
    }

    private static ChromiumProfile profile(Path root) throws IOException {
        return ChromiumProfile.create(root.resolve("User Data").resolve("Default"));
    }

    @Nested
    @DisplayName("locating profiles")
    class Locating {
        @Test
        @DisplayName("a user data directory lists its profiles and is refused as a profile")
        void userData(@TempDir Path root) throws IOException {
            var userData = Files.createDirectories(root.resolve("User Data"));
            Files.writeString(userData.resolve("Local State"), "{}");
            for (var name : List.of("Profile 10", "Profile 2", "Default", "System Profile")) {
                Files.writeString(Files.createDirectories(userData.resolve(name)).resolve("Preferences"), "{}");
            }
            Files.createDirectories(userData.resolve("Crashpad"));
            var chrome = ChromiumUserData.open(userData);
            assertEquals(List.of("Default", "Profile 2", "Profile 10", "System Profile"), chrome.profileNames());
            assertEquals(userData.resolve("Default"), chrome.profile("Default").directory());
            var failure = assertThrows(IllegalArgumentException.class, () -> ChromiumProfile.open(userData));
            assertTrue(failure.getMessage().contains("Default"), failure.getMessage());
            assertThrows(NoSuchFileException.class, () -> ChromiumProfile.open(userData.resolve("missing")));
            assertFalse(chrome.isInUse());
        }

        @Test
        @DisplayName("an Electron app's directory is a profile, its own lock directory")
        void electron(@TempDir Path root) throws IOException {
            var app = Files.createDirectories(root.resolve("MyApp"));
            Files.writeString(app.resolve("Local State"), "{}");
            Files.writeString(app.resolve("Preferences"), "{}");
            Files.createDirectories(app.resolve("IndexedDB"));
            var profile = ChromiumProfile.open(app);
            assertEquals(app, profile.lockDirectory());
            var partition = ChromiumProfile.open(Files.createDirectories(app.resolve("Partitions").resolve("work")));
            assertEquals(app, partition.lockDirectory());
        }

        @Test
        @DisplayName("create makes an empty profile and refuses a directory that holds anything")
        void create(@TempDir Path root) throws IOException {
            var profile = profile(root);
            assertTrue(Files.isDirectory(profile.directory()));
            assertEquals(root.resolve("User Data"), profile.lockDirectory());
            Files.writeString(profile.directory().resolve("file"), "");
            assertThrows(FileAlreadyExistsException.class, () -> ChromiumProfile.create(profile.directory()));
            assertTrue(profile.indexedDb().origins().isEmpty());
            assertTrue(profile.localStorage().backend().isEmpty());
        }
    }

    @Nested
    @DisplayName("IndexedDB")
    class IndexedDb {
        @ParameterizedTest
        @EnumSource(StorageBackend.class)
        @DisplayName("put adds or replaces one database and keeps the others; replaceAll and delete leave exactly what they say")
        void writes(StorageBackend backend, @TempDir Path root) throws IOException {
            var profile = profile(root);
            var indexedDb = profile.indexedDb(backend);
            indexedDb.put(ORIGIN, database("app", "dark"));
            indexedDb.put(ORIGIN, database("other", "light"));
            assertEquals(List.of("app", "other"), indexedDb.names(ORIGIN));
            assertEquals(Optional.of(backend), profile.indexedDb().backend(ORIGIN));
            indexedDb.put(ORIGIN, database("app", "blue"));
            var app = indexedDb.read(ORIGIN, "app").orElseThrow();
            assertEquals(JsValue.of("blue"), app.records("settings").get(IdbKey.of("theme")));
            assertTrue(JsValue.deepEquals(database("app", "blue").records(PEOPLE).get(IdbKey.of(1)), app.records(PEOPLE).get(IdbKey.of(1))));
            assertEquals(JsValue.of("light"), indexedDb.read(ORIGIN, "other").orElseThrow().records("settings").get(IdbKey.of("theme")));

            indexedDb.delete(ORIGIN, "other");
            assertEquals(List.of("app"), indexedDb.names(ORIGIN));
            indexedDb.replaceAll(ORIGIN, List.of(database("third", "x"), database("fourth", "y")));
            assertEquals(List.of("fourth", "third"), indexedDb.names(ORIGIN));
            assertThrows(IllegalArgumentException.class, () -> indexedDb.replaceAll(ORIGIN, List.of(database("a", "1"), database("a", "2"))));
            indexedDb.replaceAll(ORIGIN, List.of());
            assertTrue(indexedDb.names(ORIGIN).isEmpty());
            assertTrue(profile.indexedDb().origins().isEmpty());
        }

        @Test
        @DisplayName("the default storage writes an origin on the backend it is on, and a pinned one deletes the other backend's data")
        void backends(@TempDir Path root) throws IOException {
            var profile = profile(root);
            profile.indexedDb(StorageBackend.SQLITE).put(ORIGIN, database("app", "dark"));
            profile.indexedDb().put(ORIGIN, database("other", "light"));
            assertEquals(Optional.of(StorageBackend.SQLITE), profile.indexedDb().backend(ORIGIN));
            assertEquals(List.of("app", "other"), profile.indexedDb().names(ORIGIN));
            assertTrue(profile.indexedDb(StorageBackend.LEVELDB).origins().isEmpty());

            profile.indexedDb(StorageBackend.LEVELDB).put(ORIGIN, database("moved", "x"));
            assertEquals(Optional.of(StorageBackend.LEVELDB), profile.indexedDb().backend(ORIGIN));
            assertEquals(List.of("moved"), profile.indexedDb().names(ORIGIN));
            assertTrue(profile.indexedDb(StorageBackend.SQLITE).names(ORIGIN).isEmpty());
            assertFalse(Files.exists(profile.directory().resolve("IndexedDB").resolve(ORIGIN.identifier())));

            var fresh = StorageOrigin.parse("https://fresh.example.com");
            profile.indexedDb().put(fresh, database("app", "y"));
            assertEquals(Optional.of(StorageBackend.LEVELDB), profile.indexedDb().backend(fresh));
        }

        @ParameterizedTest
        @EnumSource(StorageBackend.class)
        @DisplayName("copyFrom copies every database of an origin, blobs and files included, from another profile")
        void copy(StorageBackend backend, @TempDir Path root) throws IOException {
            var source = profile(root.resolve("source"));
            var store = IdbObjectStore.of("files");
            var file = JsFile.of("note.txt", "hello".getBytes(StandardCharsets.UTF_8), "text/plain", Instant.ofEpochSecond(1_600_000_000));
            var withBlobs = IdbDatabase.builder("blobs", 2)
                    .put(store, IdbKey.of(1), JsObject.builder().put("blob", JsBlob.of(new byte[]{1, 2, 3}, "application/x")).put("file", file).build())
                    .build();
            source.indexedDb(StorageBackend.SQLITE == backend ? StorageBackend.LEVELDB : StorageBackend.SQLITE)
                    .replaceAll(ORIGIN, List.of(withBlobs, database("app", "dark")));

            var target = profile(root.resolve("target"));
            target.indexedDb(backend).copyFrom(source.indexedDb(), ORIGIN);
            assertEquals(List.of("app", "blobs"), target.indexedDb().names(ORIGIN));
            assertEquals(Optional.of(backend), target.indexedDb().backend(ORIGIN));
            var copied = target.indexedDb().read(ORIGIN, "blobs").orElseThrow();
            assertEquals(2, copied.version());
            var value = (JsObject) copied.records("files").get(IdbKey.of(1));
            assertArrayEquals(new byte[]{1, 2, 3}, value.get("blob", JsBlob.class).orElseThrow().readAllBytes());
            var copiedFile = value.get("file", JsFile.class).orElseThrow();
            assertEquals("note.txt", copiedFile.name());
            assertEquals(Optional.of(Instant.ofEpochSecond(1_600_000_000)), copiedFile.lastModified());
            assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), copiedFile.readAllBytes());
        }

        @ParameterizedTest
        @EnumSource(StorageBackend.class)
        @DisplayName("a reader counts, looks up and walks a store, and reads the whole database back as written")
        void reader(StorageBackend backend, @TempDir Path root) throws IOException {
            var profile = profile(root);
            var written = database("app", "dark");
            profile.indexedDb(backend).put(ORIGIN, written);
            try (var reader = profile.indexedDb().open(ORIGIN, "app").orElseThrow()) {
                assertEquals(backend, reader.backend());
                assertEquals(1, reader.count("people"));
                assertEquals(Optional.of(JsValue.of("dark")), reader.get("settings", IdbKey.of("theme")));
                assertTrue(reader.get("settings", IdbKey.of("missing")).isEmpty());
                assertThrows(IllegalArgumentException.class, () -> reader.count("missing"));
                try (var cursor = reader.openCursor("people")) {
                    assertThrows(IllegalStateException.class, cursor::key);
                    assertTrue(cursor.next());
                    assertEquals(IdbKey.of(1), cursor.key());
                    assertTrue(cursor.serializedValue().length > 0);
                    assertFalse(cursor.next());
                }
                var read = reader.readAll();
                assertEquals(written.objectStores(), read.objectStores());
                assertTrue(JsValue.deepEquals(written.records(PEOPLE).get(IdbKey.of(1)), read.records(PEOPLE).get(IdbKey.of(1))));
            }
        }
    }

    @Nested
    @DisplayName("generated keys")
    class Generated {
        private static final IdbObjectStore NOTES = IdbObjectStore.builder("notes").keyPath("id").autoIncrement()
                .index("byId", "id", IdbIndex.Option.UNIQUE).build();

        @ParameterizedTest
        @EnumSource(StorageBackend.class)
        @DisplayName("a store keeps its key generator, which may be past every key, through writing, reading and copying")
        void generators(StorageBackend backend, @TempDir Path root) throws IOException {
            var log = IdbObjectStore.builder("log").autoIncrement().build();
            var database = IdbDatabase.builder("app", 1).put(log, JsValue.of("one")).keyGenerator(log, 100).build();
            var source = profile(root.resolve("source"));
            source.indexedDb(backend).put(ORIGIN, database);
            try (var reader = source.indexedDb().open(ORIGIN, "app").orElseThrow()) {
                assertEquals(java.util.OptionalLong.of(100), reader.keyGenerator("log"));
                assertEquals(java.util.OptionalLong.of(100), reader.readAll().keyGenerator("log"));
            }
            var target = profile(root.resolve("target"));
            target.indexedDb(backend == StorageBackend.LEVELDB ? StorageBackend.SQLITE : StorageBackend.LEVELDB).copyFrom(source.indexedDb(), ORIGIN);
            assertEquals(java.util.OptionalLong.of(100), target.indexedDb().read(ORIGIN, "app").orElseThrow().keyGenerator("log"));
        }

        @ParameterizedTest
        @EnumSource(StorageBackend.class)
        @DisplayName("a generated key is stored without the value and injected on reading, as Blink injects it")
        void injection(StorageBackend backend, @TempDir Path root) throws IOException {
            var database = IdbDatabase.builder("app", 1)
                    .put(NOTES, JsObject.builder().put("text", "hi").build())
                    .put(NOTES, JsObject.builder().put("text", "given").put("id", 10).build())
                    .build();
            var profile = profile(root);
            profile.indexedDb(backend).put(ORIGIN, database);
            try (var reader = profile.indexedDb().open(ORIGIN, "app").orElseThrow(); var cursor = reader.openCursor("notes")) {
                assertTrue(cursor.next());
                var value = assertInstanceOf(JsObject.class, cursor.value());
                assertEquals(java.util.OptionalInt.of(1), value.getInt("id"));
                var stored = assertInstanceOf(JsObject.class, com.github.auties00.viridian.value.StructuredClone.deserialize(cursor.serializedValue(), cursor.blobs()));
                assertFalse(stored.has("id"), "the generated key is not in the value as stored");
                assertTrue(cursor.next());
                var given = com.github.auties00.viridian.value.StructuredClone.deserialize(cursor.serializedValue(), cursor.blobs());
                assertTrue(((JsObject) given).has("id"), "a key the page gave stays in the value");
            }
            var copy = profile(root.resolve("copy"));
            copy.indexedDb(backend).copyFrom(profile.indexedDb(), ORIGIN);
            try (var reader = copy.indexedDb().open(ORIGIN, "app").orElseThrow(); var cursor = reader.openCursor("notes")) {
                assertTrue(cursor.next());
                assertFalse(((JsObject) com.github.auties00.viridian.value.StructuredClone.deserialize(cursor.serializedValue(), cursor.blobs())).has("id"));
            }
            assertEquals(java.util.OptionalLong.of(11), copy.indexedDb().read(ORIGIN, "app").orElseThrow().keyGenerator("notes"));
        }
    }

    @Nested
    @DisplayName("browser versions")
    class Versions {
        @Test
        @DisplayName("values a profile's browser is too old to read are refused, as its records name it")
        void refused(@TempDir Path root) throws IOException {
            var profile = profile(root);
            Files.writeString(profile.lockDirectory().resolve("Last Version"), "100.0.4896.60");
            var failure = assertThrows(ViridianException.class, () -> profile.indexedDb().put(ORIGIN, database("app", "dark")));
            assertEquals(ViridianException.Reason.UNSUPPORTED, failure.reason());
            assertTrue(profile.indexedDb().origins().isEmpty());
            profile.localStorage().replaceAll(ORIGIN, Map.of("strings", "need no version"));

            Files.writeString(profile.lockDirectory().resolve("Last Version"), "120.0.6099.71");
            profile.indexedDb().put(ORIGIN, database("app", "dark"));
            var wide = IdbDatabase.builder("wide", 1)
                    .put(SETTINGS, IdbKey.of("buffer"), com.github.auties00.viridian.value.JsArrayBuffer.ofResizable(new byte[1], 1L << 33))
                    .build();
            assertThrows(ViridianException.class, () -> profile.indexedDb().put(ORIGIN, wide));
            Files.writeString(profile.lockDirectory().resolve("Last Version"), "157.0.8103.38");
            profile.indexedDb().put(ORIGIN, wide);
            assertEquals(List.of("app", "wide"), profile.indexedDb().names(ORIGIN));
        }

        @Test
        @DisplayName("a profile's Preferences name its browser when its user data directory does not, and no record means any")
        void records(@TempDir Path root) throws IOException {
            var profile = profile(root);
            profile.indexedDb().put(ORIGIN, database("app", "dark"));
            Files.writeString(profile.directory().resolve("Preferences"), "{\"extensions\":{\"last_chrome_version\":\"108.0.5359.71\"}}");
            assertThrows(ViridianException.class, () -> profile.indexedDb().put(ORIGIN, database("app", "blue")));
            Files.writeString(profile.directory().resolve("Preferences"), "{\"extensions\":{\"last_chrome_version\":\"109.0.5414.74\"}}");
            profile.indexedDb().put(ORIGIN, database("app", "blue"));
        }
    }

    @Nested
    @DisplayName("storage buckets and keys")
    class Buckets {
        private static final StorageBucket DRAFTS = new StorageBucket(StorageKey.of(ORIGIN), "drafts");
        private static final StorageBucket EMBEDDED = StorageBucket.defaultOf(StorageKey.partitioned(ORIGIN, "https://news.example"));

        @ParameterizedTest
        @EnumSource(StorageBackend.class)
        @DisplayName("a named or partitioned bucket is registered in the quota database and kept in its directory of WebStorage")
        void registered(StorageBackend backend, @TempDir Path root) throws IOException {
            var profile = profile(root);
            profile.write(edit -> {
                edit.indexedDb(backend).put(ORIGIN, database("app", "first party"));
                edit.indexedDb(backend).put(DRAFTS, database("app", "drafts"));
                edit.indexedDb(backend).put(EMBEDDED, database("app", "embedded"));
            });
            assertEquals(List.of(StorageBucket.defaultOf(ORIGIN), DRAFTS, EMBEDDED), profile.indexedDb().buckets());
            assertEquals(List.of(ORIGIN), profile.indexedDb().origins());
            for (var entry : Map.of(StorageBucket.defaultOf(ORIGIN), "first party", DRAFTS, "drafts", EMBEDDED, "embedded").entrySet()) {
                var read = profile.indexedDb().read(entry.getKey(), "app").orElseThrow();
                assertEquals(JsValue.of(entry.getValue()), read.records("settings").get(IdbKey.of("theme")), entry.getKey().toString());
                assertEquals(Optional.of(backend), profile.indexedDb().backend(entry.getKey()));
            }
            var webStorage = profile.directory().resolve("WebStorage");
            assertTrue(Files.isRegularFile(webStorage.resolve("QuotaManager")));
            assertTrue(Files.isDirectory(webStorage.resolve("1").resolve("IndexedDB")));
            assertTrue(Files.isDirectory(webStorage.resolve("2").resolve("IndexedDB")));
            assertTrue(Files.isDirectory(profile.directory().resolve("IndexedDB")));

            var more = new StorageBucket(StorageKey.of(ORIGIN), "more");
            profile.indexedDb().put(more, database("other", "x"));
            assertTrue(Files.isDirectory(webStorage.resolve("3").resolve("IndexedDB")));
            profile.indexedDb().put(DRAFTS, database("second", "y"));
            assertEquals(List.of("app", "second"), profile.indexedDb().names(DRAFTS));
            assertFalse(Files.exists(webStorage.resolve("4")));
            assertTrue(profile.indexedDb().names(new StorageBucket(StorageKey.of(ORIGIN), "missing")).isEmpty());
        }

        @Test
        @DisplayName("every bucket copies into another profile, which registers it anew")
        void copy(@TempDir Path root) throws IOException {
            var source = profile(root.resolve("source"));
            source.indexedDb().put(DRAFTS, database("app", "drafts"));
            source.indexedDb().put(EMBEDDED, database("app", "embedded"));
            var target = profile(root.resolve("target"));
            target.indexedDb().put(ORIGIN, database("existing", "kept"));
            target.write(edit -> {
                for (var bucket : source.indexedDb().buckets()) {
                    edit.indexedDb().copyFrom(source.indexedDb(), bucket);
                }
            });
            assertEquals(List.of(StorageBucket.defaultOf(ORIGIN), DRAFTS, EMBEDDED), target.indexedDb().buckets());
            assertEquals(JsValue.of("embedded"), target.indexedDb().read(EMBEDDED, "app").orElseThrow().records("settings").get(IdbKey.of("theme")));
        }

        @ParameterizedTest
        @EnumSource(StorageBackend.class)
        @DisplayName("a partitioned key's localStorage is kept apart from the origin's first-party one")
        void localStorage(StorageBackend backend, @TempDir Path root) throws IOException {
            var profile = profile(root);
            var embedded = StorageKey.partitioned(ORIGIN, "https://news.example");
            profile.localStorage(backend).replaceAll(ORIGIN, Map.of("party", "first"));
            profile.localStorage(backend).replaceAll(embedded, Map.of("party", "third"));
            assertEquals(Map.of("party", "first"), profile.localStorage().items(ORIGIN));
            assertEquals(Map.of("party", "third"), profile.localStorage().items(embedded));
            assertEquals(List.of(StorageKey.of(ORIGIN), embedded), profile.localStorage().storageKeys());
            assertEquals(List.of(ORIGIN), profile.localStorage().origins());
            profile.localStorage().clear(embedded);
            assertEquals(List.of(StorageKey.of(ORIGIN)), profile.localStorage().storageKeys());
        }
    }

    @Nested
    @DisplayName("localStorage")
    class Local {
        @Test
        @DisplayName("the default storage writes every backend the profile has, kept equal, and LevelDB when it has none")
        void backends(@TempDir Path root) throws IOException {
            var profile = profile(root);
            profile.localStorage().replaceAll(ORIGIN, Map.of("a", "1"));
            assertEquals(Optional.of(StorageBackend.LEVELDB), profile.localStorage().backend());
            profile.localStorage(StorageBackend.SQLITE).replaceAll(ORIGIN, Map.of("a", "sqlite"));
            assertEquals(Optional.of(StorageBackend.SQLITE), profile.localStorage().backend());
            profile.localStorage().replaceAll(ORIGIN, Map.of("b", "2"));
            assertEquals(Map.of("b", "2"), profile.localStorage(StorageBackend.LEVELDB).items(ORIGIN));
            assertEquals(Map.of("b", "2"), profile.localStorage(StorageBackend.SQLITE).items(ORIGIN));
            assertEquals(Optional.of("2"), profile.localStorage().item(ORIGIN, "b"));
            var other = StorageOrigin.parse("https://other.example.com");
            profile.localStorage().copyFrom(profile.localStorage(), ORIGIN);
            profile.localStorage().replaceAll(other, Map.of("c", "3"));
            profile.localStorage().clear(ORIGIN);
            assertEquals(List.of(other), profile.localStorage().origins());
            assertEquals(List.of(other), profile.localStorage(StorageBackend.LEVELDB).origins());
        }
    }

    @Nested
    @DisplayName("edits")
    class Edits {
        @Test
        @DisplayName("an edit commits every store it touches together, and its reads see the profile before it")
        void together(@TempDir Path root) throws IOException {
            var profile = profile(root);
            profile.write(edit -> {
                edit.indexedDb().put(ORIGIN, database("app", "dark"));
                edit.indexedDb().put(ORIGIN, database("other", "light"));
                edit.localStorage().replaceAll(ORIGIN, Map.of("session", "restored"));
                assertTrue(edit.indexedDb().names(ORIGIN).isEmpty(), "reads see the profile before the edit");
            });
            assertEquals(List.of("app", "other"), profile.indexedDb().names(ORIGIN));
            assertEquals(Map.of("session", "restored"), profile.localStorage().items(ORIGIN));
            try (var entries = Files.list(profile.directory())) {
                assertTrue(entries.noneMatch(entry -> entry.getFileName().toString().startsWith(".viridian-")));
            }
        }

        @Test
        @DisplayName("an editor that throws changes nothing, and an edit's facades refuse once it is over")
        void abandoned(@TempDir Path root) throws IOException {
            var profile = profile(root);
            var leaked = new AtomicReference<ProfileEdit>();
            assertThrows(IOException.class, () -> profile.write(edit -> {
                leaked.set(edit);
                edit.localStorage().replaceAll(ORIGIN, Map.of("a", "b"));
                throw new IOException("the caller gives up");
            }));
            assertTrue(profile.localStorage().backend().isEmpty());
            assertThrows(IllegalStateException.class, () -> leaked.get().indexedDb());
            assertThrows(IllegalStateException.class, () -> profile.write(edit -> {
                edit.indexedDb(StorageBackend.LEVELDB).put(ORIGIN, database("a", "1"));
                edit.indexedDb(StorageBackend.SQLITE).put(ORIGIN, database("b", "2"));
            }));
            assertTrue(profile.indexedDb().origins().isEmpty());
        }

        @Test
        @DisplayName("a write is refused while another writer, a thread or the same one, holds the profile")
        void inUse(@TempDir Path root) throws Exception {
            var profile = profile(root);
            var holding = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var writer = Thread.ofPlatform().start(() -> {
                try {
                    profile.write(_ -> {
                        holding.countDown();
                        try {
                            release.await();
                        } catch (InterruptedException exception) {
                            throw new IOException(exception);
                        }
                    });
                } catch (IOException exception) {
                    throw new RuntimeException(exception);
                }
            });
            holding.await();
            try {
                assertTrue(profile.isInUse());
                var failure = assertThrows(ProfileInUseException.class, () -> profile.localStorage().replaceAll(ORIGIN, Map.of("a", "b")));
                assertEquals(ViridianException.Reason.IN_USE, failure.reason());
                assertFalse(profile.needsRecovery());
            } finally {
                release.countDown();
                writer.join();
            }
            assertFalse(profile.isInUse());
            assertThrows(ProfileInUseException.class, () -> profile.write(_ -> profile.localStorage().replaceAll(ORIGIN, Map.of("a", "b"))));
        }

        @Test
        @DisplayName("a replacement a crash left behind needs recovery, which drops it when it was not committed")
        void recovery(@TempDir Path root) throws IOException {
            var profile = profile(root);
            profile.localStorage().replaceAll(ORIGIN, Map.of("a", "1"));
            Files.createDirectories(profile.directory().resolve(".viridian-crashed").resolve("new").resolve("0"));
            assertTrue(profile.needsRecovery());
            profile.recover();
            assertFalse(profile.needsRecovery());
            assertEquals(Map.of("a", "1"), profile.localStorage().items(ORIGIN));
        }
    }

    @Test
    @DisplayName("a value read from a store holds what was written, typed accessors included")
    void values(@TempDir Path root) throws IOException {
        var profile = profile(root);
        profile.indexedDb().put(ORIGIN, database("app", "dark"));
        var person = assertInstanceOf(JsObject.class, profile.indexedDb().read(ORIGIN, "app").orElseThrow().records(PEOPLE).get(IdbKey.of(1)));
        assertEquals("Alice", person.getString("name").orElseThrow());
    }
}
