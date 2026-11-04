package com.github.auties00.viridian;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/// The write half of the CI's Chrome check: copies everything a profile Chrome wrote holds, through the public API and
/// in one write, into a new profile, which the workflow then has Chrome read back (`ci/chrome/read.mjs`). It runs only
/// where the workflow names the original, its manifest and the copy's user data directory.
@EnabledIfEnvironmentVariable(named = "VIRIDIAN_COPY_USER_DATA", matches = ".+")
class ChromeCopyTest {
    @Test
    @DisplayName("every IndexedDB bucket and localStorage key of a profile Chrome wrote is copied into a new profile")
    void copiesChromeProfile() throws IOException {
        var manifest = Files.readAllLines(Path.of(System.getenv("VIRIDIAN_CHROME_MANIFEST")), StandardCharsets.UTF_8);
        var backend = StorageBackend.valueOf(manifest.get(1).split("\t")[1].toUpperCase());
        var original = ChromiumProfile.open(Path.of(System.getenv("VIRIDIAN_CHROME_PROFILE")));
        var copy = ChromiumProfile.create(Path.of(System.getenv("VIRIDIAN_COPY_USER_DATA")).resolve("Default"));
        copy.write(edit -> {
            for (var bucket : original.indexedDb().buckets()) {
                edit.indexedDb(backend).copyFrom(original.indexedDb(), bucket);
            }
            for (var key : original.localStorage().storageKeys()) {
                edit.localStorage(backend).copyFrom(original.localStorage(), key);
            }
        });
    }
}
