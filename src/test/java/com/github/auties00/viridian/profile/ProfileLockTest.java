package com.github.auties00.viridian.profile;

import com.github.auties00.viridian.ProfileInUseException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Taking, holding and inspecting the lock a browser holds on its user data directory. A real browser meeting the lock
/// is the CI's job; these cover what a test can hold or fake, the socket a starting Chromium connects to included.
class ProfileLockTest {
    @Test
    @DisplayName("the lock directory is the parent of a browser profile, the profile of an Electron app, the app of a partition")
    void lockDirectory(@TempDir Path root) throws IOException {
        var browser = Files.createDirectories(root.resolve("User Data").resolve("Default"));
        assertEquals(root.resolve("User Data"), ProfileLock.find(browser));
        var app = Files.createDirectories(root.resolve("MyApp"));
        Files.writeString(app.resolve("Local State"), "{}");
        assertEquals(app, ProfileLock.find(app));
        var partition = Files.createDirectories(app.resolve("Partitions").resolve("work"));
        assertEquals(app, ProfileLock.find(partition));
    }

    @Test
    @DisplayName("a lock excludes a second writer of the same process, and is free once released")
    void exclusive(@TempDir Path userData) throws IOException {
        try (var _ = ProfileLock.acquire(userData)) {
            var failure = assertThrows(ProfileInUseException.class, () -> ProfileLock.acquire(userData));
            assertEquals(com.github.auties00.viridian.ViridianException.Reason.IN_USE, failure.reason());
            assertThrows(ProfileInUseException.class, () -> ProfileLock.check(userData));
        }
        assertDoesNotThrow(() -> ProfileLock.check(userData));
        try (var _ = ProfileLock.acquire(userData)) {
            // Free again
        }
        try (var entries = Files.list(userData)) {
            assertEquals(0, entries.count(), "the lock leaves nothing behind");
        }
    }

    @Test
    @DisplayName("a LevelDB database is open while its LOCK file is locked, and closed once it is released")
    void levelDbLock(@TempDir Path database) throws IOException {
        var lock = database.resolve("LOCK");
        Files.createFile(lock);
        try (var channel = FileChannel.open(lock, StandardOpenOption.WRITE); var _ = channel.lock()) {
            var failure = assertThrows(ProfileInUseException.class, () -> ProfileLock.checkLevelDb(database));
            assertEquals(lock, failure.lock());
        }
        assertDoesNotThrow(() -> ProfileLock.checkLevelDb(database));
    }

    @Nested
    @DisplayName("on Windows")
    @EnabledOnOs(OS.WINDOWS)
    class Windows {
        @Test
        @DisplayName("a lockfile a browser holds open for writing keeps the lock, and a stale one does not")
        void lockfile(@TempDir Path userData) throws IOException {
            var lockfile = userData.resolve("lockfile");
            Files.writeString(lockfile, "");
            assertDoesNotThrow(() -> ProfileLock.check(userData));
            try (var _ = ProfileLock.acquire(userData)) {
                assertTrue(Files.exists(lockfile));
            }
            assertFalse(Files.exists(lockfile), "the lock deletes the lockfile it held");
        }
    }

    @Nested
    @DisplayName("elsewhere")
    @EnabledOnOs({OS.LINUX, OS.MAC, OS.FREEBSD, OS.OPENBSD})
    class Posix {
        @Test
        @DisplayName("a singleton lock counts while its process runs on this host, and not after")
        void singletonLock(@TempDir Path userData) throws IOException {
            var link = userData.resolve("SingletonLock");
            var host = InetAddress.getLocalHost().getHostName();
            Files.createSymbolicLink(link, Path.of(host + "-" + ProcessHandle.current().pid()));
            var failure = assertThrows(ProfileInUseException.class, () -> ProfileLock.check(userData));
            assertEquals(ProcessHandle.current().pid(), failure.process().orElseThrow().pid());

            Files.delete(link);
            // A process id no process has on this host: a crash's leftover, which the lock takes over
            Files.createSymbolicLink(link, Path.of(host + "-" + Integer.MAX_VALUE));
            assertDoesNotThrow(() -> ProfileLock.check(userData));
            try (var _ = ProfileLock.acquire(userData)) {
                assertEquals(host + "-" + ProcessHandle.current().pid(), Files.readSymbolicLink(link).toString());
            }
            assertFalse(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS));

            Files.createSymbolicLink(link, Path.of("another-host-1"));
            assertThrows(ProfileInUseException.class, () -> ProfileLock.check(userData));
            assertThrows(ProfileInUseException.class, () -> ProfileLock.acquire(userData));
        }

        @Test
        @DisplayName("a Chromium that connects while the write runs is told to start once the lock is gone")
        void answersChromium(@TempDir Path userData) throws Exception {
            var lock = ProfileLock.acquire(userData);
            var socket = Files.readSymbolicLink(userData.resolve("SingletonSocket"));
            var cookie = Files.readSymbolicLink(userData.resolve("SingletonCookie"));
            assertEquals(cookie, Files.readSymbolicLink(socket.resolveSibling("SingletonCookie")));
            var answer = CompletableFuture.supplyAsync(() -> {
                try (var channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                    channel.connect(UnixDomainSocketAddress.of(socket));
                    channel.write(ByteBuffer.wrap("START\0/\0chrome".getBytes(StandardCharsets.US_ASCII)));
                    channel.shutdownOutput();
                    var buffer = ByteBuffer.allocate(16);
                    while (channel.read(buffer) >= 0) {
                        // Until the lock answers and closes
                    }
                    return new String(buffer.array(), 0, buffer.position(), StandardCharsets.US_ASCII);
                } catch (IOException exception) {
                    throw new RuntimeException(exception);
                }
            });
            Thread.sleep(300);
            assertFalse(answer.isDone(), "the lock answers only once the write is done");
            lock.close();
            assertEquals("SHUTDOWN", answer.get(10, TimeUnit.SECONDS));
            assertFalse(Files.exists(userData.resolve("SingletonLock"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
            assertFalse(Files.exists(userData.resolve("SingletonSocket"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
            assertTrue(Files.notExists(socket.getParent()));
        }
    }

    @Test
    @DisplayName("a lock on an unwritable directory fails without leaving a holder behind")
    void unwritable(@TempDir Path root) throws IOException {
        var file = Files.writeString(root.resolve("file"), "");
        assertThrows(IOException.class, () -> ProfileLock.acquire(file.resolve("child")));
        Assumptions.assumeTrue(Files.isDirectory(root));
        assertDoesNotThrow(() -> ProfileLock.acquire(root).close());
    }
}
