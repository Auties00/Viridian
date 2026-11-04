package com.github.auties00.viridian;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Storage keys and buckets as Chromium serializes and names them.
class StorageKeyTest {
    private static final StorageOrigin ORIGIN = StorageOrigin.parse("http://127.0.0.1:8080");

    @Test
    @DisplayName("a key serializes as Chromium serializes it, and Local Storage names a first-party one without the slash")
    void serialization() {
        assertEquals("http://127.0.0.1:8080/", StorageKey.of(ORIGIN).serialize());
        assertEquals("http://127.0.0.1:8080", StorageKey.of(ORIGIN).serializeForLocalStorage());
        var partitioned = StorageKey.partitioned(ORIGIN, "http://localhost");
        assertEquals("http://127.0.0.1:8080/^0http://localhost", partitioned.serialize());
        assertEquals(partitioned.serialize(), partitioned.serializeForLocalStorage());
        assertEquals("http://127.0.0.1:8080/^31", StorageKey.crossSiteAncestor(ORIGIN).serialize());
        assertTrue(StorageKey.of(ORIGIN).isFirstParty());
        assertFalse(partitioned.isFirstParty());
        assertEquals(Optional.of("http://localhost"), partitioned.topLevelSite());
        assertThrows(IllegalArgumentException.class, () -> StorageKey.partitioned(ORIGIN, "http://localhost:80"));
        assertThrows(IllegalArgumentException.class, () -> StorageKey.partitioned(ORIGIN, "localhost"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://a.test/", "https://a.test/^0https://b.test", "https://a.test/^31", "https://a.test:444/^1123^2456",
            "https://a.test/^41^52^6https://c.test"})
    @DisplayName("every form Chromium writes parses back to itself")
    void roundTrip(String serialized) {
        assertEquals(serialized, StorageKey.parse(serialized).serialize());
    }

    @Test
    @DisplayName("parsing takes a first-party key without its slash, reads a nonce, and refuses what is not a key")
    void parsing() {
        assertEquals(StorageKey.of(StorageOrigin.parse("https://a.test")), StorageKey.parse("https://a.test"));
        assertEquals(Optional.of("123:456"), StorageKey.parse("https://a.test/^1123^2456").nonce());
        for (var invalid : new String[]{"not a url", "https://a.test^0https://b.test", "https://a.test/^9x", "https://a.test/path/"}) {
            assertThrows(IllegalArgumentException.class, () -> StorageKey.parse(invalid), invalid);
        }
    }

    @Test
    @DisplayName("a bucket is named as navigator.storageBuckets names one, the default bucket as Chromium names it")
    void buckets() {
        assertEquals("_default", StorageBucket.defaultOf(ORIGIN).name());
        assertTrue(StorageBucket.defaultOf(ORIGIN).isDefault());
        assertEquals(StorageBucket.defaultOf(StorageKey.of(ORIGIN)), StorageBucket.defaultOf(ORIGIN));
        new StorageBucket(StorageKey.of(ORIGIN), "drafts-2_x");
        for (var invalid : new String[]{"", "_private", "-dash", "Upper", "a".repeat(64), "with space"}) {
            assertThrows(IllegalArgumentException.class, () -> new StorageBucket(StorageKey.of(ORIGIN), invalid), invalid);
        }
    }
}
