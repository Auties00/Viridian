package com.github.auties00.viridian.profile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Replacing stores of a profile, and recovering from a replacement cut short at each point. A crash is simulated by
/// leaving the staging directory as the replacement would have left it.
class StoreReplacementTest {
    private static List<Path> leftovers(Path profile) throws IOException {
        try (var entries = Files.list(profile)) {
            return entries.filter(entry -> entry.getFileName().toString().startsWith(".viridian-")).toList();
        }
    }

    private static void profileWithOriginals(Path profile) throws IOException {
        Files.createDirectories(profile.resolve("IndexedDB/a.leveldb"));
        Files.writeString(profile.resolve("IndexedDB/a.leveldb/CURRENT"), "old");
        Files.writeString(profile.resolve("LocalStorage"), "old file");
        Files.createDirectories(profile.resolve("IndexedDB/a"));
        Files.writeString(profile.resolve("IndexedDB/a/0"), "other backend");
    }

    @Test
    @DisplayName("a commit puts staged files and directories in place, deletes what it was told to, and leaves nothing behind")
    void commits(@TempDir Path profile) throws IOException {
        profileWithOriginals(profile);
        try (var replacement = StoreReplacement.begin(profile)) {
            var directory = replacement.stage(Path.of("IndexedDB/a.leveldb"));
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("CURRENT"), "new");
            Files.writeString(replacement.stage(Path.of("LocalStorage")), "new file");
            Files.writeString(replacement.stage(Path.of("Local Storage/created")), "made");
            replacement.delete(Path.of("IndexedDB/a"));
            replacement.delete(Path.of("IndexedDB/absent"));
            // Staged but never written: deleted
            replacement.stage(Path.of("IndexedDB/a/0"));
            replacement.commit();
        }
        assertEquals("new", Files.readString(profile.resolve("IndexedDB/a.leveldb/CURRENT")));
        assertEquals("new file", Files.readString(profile.resolve("LocalStorage")));
        assertEquals("made", Files.readString(profile.resolve("Local Storage/created")));
        assertFalse(Files.exists(profile.resolve("IndexedDB/a")));
        assertEquals(List.of(), leftovers(profile));
    }

    @Test
    @DisplayName("a replacement closed without a commit changes nothing")
    void abandons(@TempDir Path profile) throws IOException {
        profileWithOriginals(profile);
        try (var replacement = StoreReplacement.begin(profile)) {
            Files.writeString(replacement.stage(Path.of("LocalStorage")), "new file");
            replacement.delete(Path.of("IndexedDB/a"));
        }
        assertEquals("old file", Files.readString(profile.resolve("LocalStorage")));
        assertTrue(Files.exists(profile.resolve("IndexedDB/a/0")));
        assertEquals(List.of(), leftovers(profile));
    }

    @Test
    @DisplayName("a replacement cut short before its plan is written is dropped by the next one")
    void dropsUncommitted(@TempDir Path profile) throws IOException {
        profileWithOriginals(profile);
        var replacement = StoreReplacement.begin(profile);
        Files.writeString(replacement.stage(Path.of("LocalStorage")), "new file");
        // The process stops here, without closing
        StoreReplacement.recover(profile);
        assertEquals("old file", Files.readString(profile.resolve("LocalStorage")));
        assertEquals(List.of(), leftovers(profile));
    }

    @Test
    @DisplayName("a replacement cut short after its plan is written is finished, from every point of the swap")
    void finishesCommitted(@TempDir Path profile) throws IOException {
        profileWithOriginals(profile);
        var staging = profile.resolve(".viridian-crashed");
        // Step 0 already swapped, step 1 moved away but not replaced, step 2 not started, step 3 a deletion not started
        Files.createDirectories(staging.resolve("new"));
        Files.createDirectories(staging.resolve("old"));
        Files.writeString(staging.resolve("COMMITTED"), String.join("\n",
                "replace\t0\tIndexedDB/a.leveldb", "replace\t1\tLocalStorage", "replace\t2\tSessionStorage", "delete\t3\tIndexedDB/a", ""));
        Files.move(profile.resolve("IndexedDB/a.leveldb"), staging.resolve("old/0"));
        Files.createDirectories(profile.resolve("IndexedDB/a.leveldb"));
        Files.writeString(profile.resolve("IndexedDB/a.leveldb/CURRENT"), "new");
        Files.move(profile.resolve("LocalStorage"), staging.resolve("old/1"));
        Files.writeString(staging.resolve("new/1"), "new file");
        Files.writeString(profile.resolve("SessionStorage"), "old session");
        Files.writeString(staging.resolve("new/2"), "new session");

        StoreReplacement.recover(profile);
        StoreReplacement.recover(profile);
        assertEquals("new", Files.readString(profile.resolve("IndexedDB/a.leveldb/CURRENT")));
        assertEquals("new file", Files.readString(profile.resolve("LocalStorage")));
        assertEquals("new session", Files.readString(profile.resolve("SessionStorage")));
        assertFalse(Files.exists(profile.resolve("IndexedDB/a")));
        assertEquals(List.of(), leftovers(profile));
    }

    @Test
    @DisplayName("paths outside the profile, and a path named twice, are refused")
    void refusesBadPaths(@TempDir Path profile) throws IOException {
        try (var replacement = StoreReplacement.begin(profile)) {
            assertThrows(IllegalArgumentException.class, () -> replacement.stage(Path.of("../escape")));
            assertThrows(IllegalArgumentException.class, () -> replacement.delete(profile.toAbsolutePath()));
            replacement.stage(Path.of("LocalStorage"));
            assertThrows(IllegalArgumentException.class, () -> replacement.delete(Path.of("LocalStorage")));
        }
    }
}
