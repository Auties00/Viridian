package com.github.auties00.viridian;

import com.github.auties00.viridian.profile.ProfileLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The profile lock against a real Chrome, which `VIRIDIAN_CHROME_EXECUTABLE` names: a Chrome started while
/// Viridian writes does not open the profile, and Viridian does not write a profile a running Chrome has open.
@EnabledIfEnvironmentVariable(named = "VIRIDIAN_CHROME_EXECUTABLE", matches = ".+")
class ChromeLockTest {
    private static Process chrome(Path userData, String... extra) throws IOException {
        var command = new ArrayList<>(List.of(System.getenv("VIRIDIAN_CHROME_EXECUTABLE"), "--headless", "--user-data-dir=" + userData,
                "--no-first-run", "--no-default-browser-check", "--disable-gpu"));
        if (System.getProperty("os.name").startsWith("Linux")) {
            command.add("--no-sandbox");
        }
        command.addAll(List.of(extra));
        command.add("about:blank");
        return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    }

    @Test
    @DisplayName("a Chrome started while Viridian holds the lock exits without opening the profile, and starts once it is released")
    void chromeWaitsForViridian(@TempDir Path root) throws Exception {
        var userData = Files.createDirectories(root.resolve("User Data"));
        var profile = ChromiumProfile.create(userData.resolve("Default"));
        Process browser;
        try (var _ = ProfileLock.acquire(profile.lockDirectory())) {
            browser = chrome(userData, "--remote-debugging-port=0");
            // On Windows Chrome gives up after 5 seconds; elsewhere it waits for the lock's answer
            var exited = browser.waitFor(8, TimeUnit.SECONDS);
            if (System.getProperty("os.name").startsWith("Windows")) {
                assertTrue(exited, "Chrome kept running while Viridian held the lock");
                // chrome::RESULT_CODE_PROFILE_IN_USE
                assertEquals(21, browser.exitValue());
            }
            assertFalse(Files.exists(profile.directory().resolve("Preferences")), "Chrome opened the profile while Viridian held it");
        }
        if (browser.isAlive()) {
            // A POSIX Chrome is told to start once the lock is released, and opens the profile then
            waitFor(() -> Files.exists(profile.directory().resolve("Preferences")), Duration.ofSeconds(30));
            browser.destroy();
            browser.waitFor(10, TimeUnit.SECONDS);
        }
        browser = chrome(userData, "--remote-debugging-port=0");
        var second = browser;
        try {
            // The first Chrome's lock may outlive it, naming a process that is gone, so wait for this one's
            waitFor(() -> isLockedBy(userData, second), Duration.ofSeconds(30));
            assertThrows(ProfileInUseException.class, () -> ProfileLock.acquire(profile.lockDirectory()));
            assertThrows(ProfileInUseException.class, () -> profile.localStorage().replaceAll(StorageOrigin.parse("https://example.com"), java.util.Map.of("a", "b")));
            assertTrue(profile.isInUse());
        } finally {
            browser.destroy();
            browser.waitFor(15, TimeUnit.SECONDS);
            browser.destroyForcibly();
        }
    }

    /// {@return whether `browser` holds the lock: elsewhere than Windows a `SingletonLock` naming its process, which is
    /// the browser process itself; on Windows a `lockfile` no one else may write}
    private static boolean isLockedBy(Path userData, Process browser) throws IOException {
        var link = userData.resolve("SingletonLock");
        if (Files.isSymbolicLink(link)) {
            try {
                return Files.readSymbolicLink(link).toString().endsWith("-" + browser.pid());
            } catch (NoSuchFileException _) {
                return false;
            }
        }
        var lockfile = userData.resolve("lockfile");
        if (!Files.exists(lockfile)) {
            return false;
        }
        try (var _ = FileChannel.open(lockfile, StandardOpenOption.WRITE)) {
            return false;
        } catch (FileSystemException _) {
            return true;
        }
    }

    private interface Condition {
        boolean holds() throws Exception;
    }

    private static void waitFor(Condition condition, Duration timeout) throws Exception {
        var deadline = Instant.now().plus(timeout);
        while (!condition.holds()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Timed out after " + timeout);
            }
            Thread.sleep(200);
        }
    }
}
