package com.github.auties00.viridian;

import com.github.auties00.viridian.value.JsArray;
import com.github.auties00.viridian.value.JsBlob;
import com.github.auties00.viridian.value.JsObject;
import com.github.auties00.viridian.value.JsString;
import com.github.auties00.viridian.value.JsValue;
import com.github.auties00.viridian.value.StructuredClone;
import com.github.auties00.viridian.value.V8Writer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The database model: keys, key paths, store definitions, and the rules [IdbDatabase.Builder] applies at each call.
class IdbDatabaseTest {
    private static final IdbObjectStore SETTINGS = IdbObjectStore.of("settings");
    private static final IdbObjectStore PEOPLE = IdbObjectStore.builder("people").keyPath("id")
            .index("byEmail", "email", IdbIndex.Option.UNIQUE)
            .index("byTag", "tags", IdbIndex.Option.MULTI_ENTRY)
            .build();
    private static final IdbObjectStore LOG = IdbObjectStore.builder("log").autoIncrement().build();
    private static final IdbObjectStore NOTES = IdbObjectStore.builder("notes").keyPath("meta.id").autoIncrement().build();

    private static JsObject person(int id, String email) {
        return JsObject.builder().put("id", id).put("email", email).put("tags", JsArray.of("a", "b")).build();
    }

    @Nested
    @DisplayName("keys")
    class Keys {
        @Test
        @DisplayName("equal when IndexedDB orders them equal, 0 and -0 included, the sign kept")
        void zero() {
            assertEquals(IdbKey.of(0.0), IdbKey.of(-0.0));
            assertEquals(IdbKey.of(0.0).hashCode(), IdbKey.of(-0.0).hashCode());
            assertEquals(1 / -0.0, 1 / IdbKey.of(-0.0).value());
            var map = new TreeMap<IdbKey, String>();
            map.put(IdbKey.of(0.0), "zero");
            map.put(IdbKey.of(-0.0), "negative zero");
            assertEquals(1, map.size());
        }

        @Test
        @DisplayName("a date key is clipped to whole milliseconds, and an invalid date refused")
        void dates() {
            assertEquals(5, new IdbKey.DateKey(5.7).time());
            assertEquals(IdbKey.of(Instant.ofEpochMilli(5)), new IdbKey.DateKey(5));
            assertThrows(IllegalArgumentException.class, () -> new IdbKey.DateKey(9e15));
            assertThrows(IllegalArgumentException.class, () -> new IdbKey.DateKey(Double.NaN));
        }

        @Test
        @DisplayName("a long past 2^53 is refused, which a number key would hold rounded")
        void exact() {
            assertEquals(IdbKey.of(9007199254740992.0), IdbKey.of(9007199254740992L));
            assertThrows(ArithmeticException.class, () -> IdbKey.of(9007199254740993L));
            assertThrows(IllegalArgumentException.class, () -> IdbKey.of(Double.NaN));
            assertEquals(new IdbKey.ArrayKey(List.of(IdbKey.of("a"), IdbKey.of(1))), IdbKey.of(IdbKey.of("a"), IdbKey.of(1)));
        }
    }

    @Nested
    @DisplayName("store definitions")
    class Definitions {
        @Test
        @DisplayName("refuse what createObjectStore and createIndex refuse")
        void refusals() {
            assertThrows(IllegalArgumentException.class, () -> IdbObjectStore.builder("x").keyPath(IdbKeyPath.compound("a", "b")).autoIncrement().build());
            assertThrows(IllegalArgumentException.class, () -> IdbObjectStore.builder("x").keyPath("").autoIncrement().build());
            assertThrows(IllegalArgumentException.class, () -> IdbObjectStore.builder("x").index("i", IdbKeyPath.compound("a", "b"), IdbIndex.Option.MULTI_ENTRY));
            assertThrows(IllegalArgumentException.class, () -> IdbObjectStore.builder("x").keyPath("a..b"));
            assertThrows(IllegalArgumentException.class, () -> IdbObjectStore.builder("x").index("i", "a").index("i", "b").build());
        }

        @Test
        @DisplayName("equal by name, key path, generator and indexes")
        void equality() {
            assertEquals(PEOPLE, PEOPLE.toBuilder().build());
            assertNotEquals(PEOPLE, PEOPLE.toBuilder().autoIncrement().build());
            assertEquals(Optional.of(IdbKeyPath.of("id")), PEOPLE.keyPath());
            assertTrue(SETTINGS.keyPath().isEmpty());
            assertEquals("meta.id", NOTES.keyPath().orElseThrow().toString());
        }
    }

    @Nested
    @DisplayName("building")
    class Building {
        @Test
        @DisplayName("an in-line key comes from the value, an out-of-line one is given beside it")
        void keys() {
            var database = IdbDatabase.builder("app", 3)
                    .put(PEOPLE, person(2, "b@example.com"))
                    .put(PEOPLE, person(1, "a@example.com"))
                    .put(SETTINGS, IdbKey.of("theme"), JsValue.of("dark"))
                    .build();
            assertEquals(List.of(IdbKey.of(1), IdbKey.of(2)), List.copyOf(database.records(PEOPLE).keySet()));
            assertEquals(JsValue.of("dark"), database.records("settings").get(IdbKey.of("theme")));
            assertEquals(List.of(PEOPLE, SETTINGS), database.objectStores());
            assertThrows(IllegalArgumentException.class, () -> IdbDatabase.builder("app", 1).put(PEOPLE, IdbKey.of(1), person(1, "x")));
            assertThrows(IllegalArgumentException.class, () -> IdbDatabase.builder("app", 1).put(SETTINGS, JsValue.of("no key")));
            assertThrows(IllegalArgumentException.class, () -> IdbDatabase.builder("app", 1).put(PEOPLE, JsObject.builder().put("id", JsValue.NULL).build()));
            assertThrows(IllegalArgumentException.class, () -> IdbDatabase.builder("app", 1).put(PEOPLE, JsObject.of()));
        }

        @Test
        @DisplayName("a generator numbers records from 1 and moves past every number key put")
        void generator() {
            var database = IdbDatabase.builder("app", 1)
                    .put(LOG, JsValue.of("first"))
                    .put(LOG, IdbKey.of(10.5), JsValue.of("explicit"))
                    .put(LOG, JsValue.of("after"))
                    .put(LOG, IdbKey.of("text"), JsValue.of("a string key does not move it"))
                    .build();
            assertEquals(List.of(IdbKey.of(1), IdbKey.of(10.5), IdbKey.of(11), IdbKey.of("text")), List.copyOf(database.records(LOG).keySet()));
            assertEquals(OptionalLong.of(12), database.keyGenerator("log"));
            assertThrows(IllegalArgumentException.class, () -> database.keyGenerator("settings"));
            assertTrue(IdbDatabase.builder("app", 1).objectStore(SETTINGS).build().keyGenerator("settings").isEmpty());
            assertThrows(IllegalArgumentException.class, () -> database.toBuilder().keyGenerator(LOG, 11));
            assertEquals(OptionalLong.of(100), database.toBuilder().keyGenerator(LOG, 100).build().keyGenerator("log"));
            var exhausted = IdbDatabase.builder("app", 1).put(LOG, IdbKey.of(0x1p53), JsValue.of("last"));
            assertThrows(IllegalArgumentException.class, () -> exhausted.put(LOG, JsValue.of("one too many")));
        }

        @Test
        @DisplayName("a generated in-line key is injected into the value, creating the objects on its path, as a page reads it")
        void injected() {
            var database = IdbDatabase.builder("app", 1)
                    .put(NOTES, JsObject.builder().put("text", "hi").build())
                    .put(NOTES, JsObject.builder().put("text", "given").put("meta", JsObject.builder().put("id", 7)).build())
                    .build();
            var first = (JsObject) database.records(NOTES).get(IdbKey.of(1));
            assertEquals(Optional.of(new JsString("hi")), first.get("text"));
            assertEquals(1, first.getObject("meta").orElseThrow().getInt("id").orElseThrow());
            assertTrue(database.records(NOTES).containsKey(IdbKey.of(7)));
            assertEquals(OptionalLong.of(8), database.keyGenerator("notes"));
            assertThrows(IllegalArgumentException.class, () -> IdbDatabase.builder("app", 1).put(NOTES, JsObject.builder().put("meta", 5).build()));
            assertThrows(IllegalArgumentException.class, () -> IdbDatabase.builder("app", 1).put(NOTES, JsValue.of("a string holds no key")));
        }

        @Test
        @DisplayName("a unique index refuses a second record under one key, at the put that breaks it, and a replaced record frees its keys")
        void unique() {
            var builder = IdbDatabase.builder("app", 1).put(PEOPLE, person(1, "same@example.com"));
            var failure = assertThrows(IllegalArgumentException.class, () -> builder.put(PEOPLE, person(2, "same@example.com")));
            assertTrue(failure.getMessage().contains("byEmail") && failure.getMessage().contains("people"), failure.getMessage());
            builder.put(PEOPLE, person(1, "changed@example.com"));
            builder.put(PEOPLE, person(2, "same@example.com"));
            builder.delete(PEOPLE, IdbKey.of(2));
            builder.put(PEOPLE, person(3, "same@example.com"));
            assertEquals(List.of(IdbKey.of(1), IdbKey.of(3)), List.copyOf(builder.build().records(PEOPLE).keySet()));
            var fromBuilt = builder.build().toBuilder();
            assertThrows(IllegalArgumentException.class, () -> fromBuilt.put(PEOPLE, person(4, "same@example.com")));
        }

        @Test
        @DisplayName("a value that cannot be stored is refused at its put")
        void unstorable() {
            var inline = JsObject.builder().put("blob", inlineBlob()).build();
            assertThrows(IllegalArgumentException.class, () -> IdbDatabase.builder("app", 1).put(SETTINGS, IdbKey.of("x"), inline));
            var deep = JsArray.builder();
            var current = deep;
            for (var i = 0; i < 1100; i++) {
                var next = JsArray.builder();
                current.add(next);
                current = next;
            }
            var nested = deep.build();
            assertThrows(IllegalArgumentException.class, () -> IdbDatabase.builder("app", 1).put(SETTINGS, IdbKey.of("x"), nested));
        }

        @Test
        @DisplayName("a store is defined once; build freezes the builder, and toBuilder leaves the database as it is")
        void lifecycle() {
            var builder = IdbDatabase.builder("app", 1).objectStore(SETTINGS);
            assertThrows(IllegalArgumentException.class, () -> builder.objectStore(IdbObjectStore.builder("settings").keyPath("k").build()));
            var built = builder.build();
            assertSame(built, builder.build());
            assertThrows(IllegalStateException.class, () -> builder.put(SETTINGS, IdbKey.of("a"), JsValue.of(1)));
            var changed = built.toBuilder().put(SETTINGS, IdbKey.of("a"), JsValue.of(1)).version(2).build();
            assertTrue(built.records("settings").isEmpty());
            assertEquals(Map.of(IdbKey.of("a"), JsValue.of(1)), changed.records(SETTINGS));
            assertEquals(2, changed.version());
            assertThrows(IllegalArgumentException.class, () -> IdbDatabase.builder("app", 0));
            assertThrows(IllegalArgumentException.class, () -> IdbDatabase.builder("app", 1L << 53));
            assertThrows(IllegalArgumentException.class, () -> built.records("missing"));
            assertThrows(IllegalArgumentException.class, () -> built.records(IdbObjectStore.builder("settings").autoIncrement().build()));
            assertFalse(built.objectStore("missing").isPresent());
        }
    }

    private static JsBlob inlineBlob() {
        try {
            return (JsBlob) StructuredClone.deserialize(V8Writer.blink().tag('\\').tag('b').blinkString("uuid").blinkString("").varint(5).toByteArray());
        } catch (ViridianException exception) {
            throw new AssertionError(exception);
        }
    }
}
