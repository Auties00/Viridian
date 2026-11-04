package com.github.auties00.viridian.profile;

import com.github.auties00.viridian.ProfileInUseException;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/// The lock a running Chromium holds on its user data directory, taken by Viridian for the length of a write, so that
/// a browser that starts meanwhile does not open the profile, and two writers, in one process or two, exclude each
/// other.
///
/// The lock is the browser's own, in the directory [#find(Path)] names: the user data directory of a browser, the
/// `userData` directory of an Electron app, which an app takes only when it asks for a single instance.
///
/// - On Windows it is `lockfile`, which a running Chromium keeps open for writing, sharing it for reading only, and
///   deletes when it exits. Viridian opens it for writing too, which makes a starting Chromium's own open fail, so
///   that it exits with its profile-in-use result rather than open the profile; and it locks the file, which keeps
///   other writers out. It is deleted when the write ends.
/// - Elsewhere it is `SingletonLock`, a symbolic link to `<host name>-<process id>`, beside `SingletonSocket` and
///   `SingletonCookie`, which name a socket the running browser listens on and a cookie that proves the socket is its
///   own. A starting Chromium that cannot reach the socket deletes a lock whose process is not a Chromium of this
///   host and starts, so the link alone does not keep it out: Viridian makes all three, listens on the socket, and
///   answers a Chromium that connects once the write is done and the lock deleted, with `SHUTDOWN`, after which it
///   starts as usual. It answers within 15 seconds, before the 20 a starting Chromium waits after which it kills the
///   process the lock names; a write still going then gets `ACK`, after which the starting Chromium exits as if
///   another of its windows had opened. A lock whose process no longer runs on this host is a crash's leftover, which
///   Viridian deletes and takes, as Chromium does.
///
/// A lock is not reentrant: a thread that holds it and asks again is refused, as is any other.
///
/// @spec https://chromium.googlesource.com/chromium/src/+/main/chrome/browser/process_singleton_posix.cc Chromium process singleton, POSIX
/// @spec https://chromium.googlesource.com/chromium/src/+/main/chrome/browser/process_singleton_win.cc Chromium process singleton, Windows
public final class ProfileLock implements Closeable {
    /// The name of the lock on Windows.
    public static final String LOCKFILE = "lockfile";
    /// The name of the lock elsewhere.
    public static final String SINGLETON_LOCK = "SingletonLock";
    private static final String SINGLETON_SOCKET = "SingletonSocket";
    private static final String SINGLETON_COOKIE = "SingletonCookie";
    private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");
    // How long a starting Chromium is left waiting for an answer: it kills the process the lock names after 20 seconds
    private static final long ANSWER_WITHIN_MILLIS = 15_000;
    // The lock directories this process holds, which a second writer of the same process is refused
    private static final Set<Path> HELD = ConcurrentHashMap.newKeySet();

    private final Path directory;
    private final Path key;
    // Windows: the open lockfile and the lock on it
    private final FileChannel channel;
    private final FileLock fileLock;
    // POSIX: what the lock is made of, and the listener that answers a starting Chromium
    private final String lockTarget;
    private final Path socketDirectory;
    private final String cookie;
    private final ServerSocketChannel server;
    private final CountDownLatch released;
    private final List<Thread> threads;
    private boolean closed;

    private ProfileLock(Path directory, Path key, FileChannel channel, FileLock fileLock, String lockTarget, Path socketDirectory,
                        String cookie, ServerSocketChannel server) {
        this.directory = directory;
        this.key = key;
        this.channel = channel;
        this.fileLock = fileLock;
        this.lockTarget = lockTarget;
        this.socketDirectory = socketDirectory;
        this.cookie = cookie;
        this.server = server;
        this.released = new CountDownLatch(1);
        this.threads = new ArrayList<>();
    }

    /// {@return the directory that holds the lock of the profile in `profile`: the profile itself when it is an
    /// Electron app's `userData` directory, the app's directory for a partition of one, and the user data directory,
    /// its parent, otherwise}
    ///
    /// A directory holding `Local State` is a user data directory, an Electron app's being its profile too.
    public static Path find(Path profile) {
        if (Files.isRegularFile(profile.resolve("Local State"))) {
            return profile;
        }
        var parent = profile.getParent();
        if (parent == null) {
            return profile;
        }
        if (parent.getFileName() != null && parent.getFileName().toString().equals("Partitions") && parent.getParent() != null) {
            return parent.getParent();
        }
        return parent;
    }

    /// Takes the lock in `directory`, creating the directory if it does not exist.
    ///
    /// @return the lock, which the caller closes to release it
    /// @throws ProfileInUseException if a browser, an app or another writer holds it
    /// @throws IOException           if the lock cannot be made
    public static ProfileLock acquire(Path directory) throws IOException {
        Files.createDirectories(directory);
        var key = directory.toRealPath();
        if (!HELD.add(key)) {
            throw new ProfileInUseException(directory.resolve(WINDOWS ? LOCKFILE : SINGLETON_LOCK), ProcessHandle.current(),
                    "This process is writing the profile in " + directory + " already");
        }
        try {
            return WINDOWS ? acquireWindows(directory, key) : acquirePosix(directory, key);
        } catch (IOException | RuntimeException | Error failure) {
            HELD.remove(key);
            throw failure;
        }
    }

    /// Fails if a browser, an app or a writer holds the lock in `directory`, without taking it.
    ///
    /// @throws ProfileInUseException if one does
    /// @throws IOException           if the lock cannot be inspected
    public static void check(Path directory) throws IOException {
        if (Files.isDirectory(directory) && HELD.contains(directory.toRealPath())) {
            throw new ProfileInUseException(directory.resolve(WINDOWS ? LOCKFILE : SINGLETON_LOCK), ProcessHandle.current(),
                    "This process is writing the profile in " + directory);
        }
        var lockfile = directory.resolve(LOCKFILE);
        if (Files.isRegularFile(lockfile) && isLockfileHeld(lockfile)) {
            throw new ProfileInUseException(lockfile, null, "A browser has the profile in " + directory + " open");
        }
        var singleton = directory.resolve(SINGLETON_LOCK);
        var holder = singletonHolder(singleton);
        if (holder != null) {
            throw new ProfileInUseException(singleton, holder.process(), "A browser has the profile in " + directory + " open"
                    + (holder.host().isEmpty() ? "" : ", on " + holder.host()));
        }
    }

    /// Fails if a process has the LevelDB database in `directory` open, as an app that takes no browser lock holds its
    /// stores.
    ///
    /// @throws ProfileInUseException if one does
    /// @throws IOException           if the lock file cannot be inspected
    public static void checkLevelDb(Path directory) throws IOException {
        var lock = directory.resolve("LOCK");
        if (!Files.isRegularFile(lock)) {
            return;
        }
        try (var channel = FileChannel.open(lock, StandardOpenOption.WRITE)) {
            var held = channel.tryLock();
            if (held == null) {
                throw new ProfileInUseException(lock, null, "A process has the database " + directory + " open");
            }
            held.release();
        } catch (OverlappingFileLockException exception) {
            throw new ProfileInUseException(lock, ProcessHandle.current(), "This process has the database " + directory + " open");
        } catch (FileSystemException exception) {
            // Windows refuses to open a file another process has opened exclusively
            if (Files.exists(lock)) {
                throw new ProfileInUseException(lock, null, "A process has the database " + directory + " open");
            }
        }
    }

    /// {@return the directory whose lock this is}
    public Path directory() {
        return directory;
    }

    private static ProfileLock acquireWindows(Path directory, Path key) throws IOException {
        var lockfile = directory.resolve(LOCKFILE);
        FileChannel channel;
        try {
            channel = FileChannel.open(lockfile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.DELETE_ON_CLOSE);
        } catch (FileSystemException exception) {
            throw new ProfileInUseException(lockfile, null, "A browser has the profile in " + directory + " open; close it first");
        }
        try {
            var fileLock = channel.tryLock();
            if (fileLock == null) {
                throw new ProfileInUseException(lockfile, null, "Another writer has the profile in " + directory + " open");
            }
            return new ProfileLock(directory, key, channel, fileLock, null, null, null, null);
        } catch (IOException | RuntimeException failure) {
            channel.close();
            throw failure;
        }
    }

    private static ProfileLock acquirePosix(Path directory, Path key) throws IOException {
        var lock = directory.resolve(SINGLETON_LOCK);
        var target = hostName() + "-" + ProcessHandle.current().pid();
        for (var attempt = 0; ; attempt++) {
            try {
                Files.createSymbolicLink(lock, Path.of(target));
                break;
            } catch (FileAlreadyExistsException exception) {
                var holder = singletonHolder(lock);
                if (holder != null || attempt >= 3) {
                    throw new ProfileInUseException(lock, holder == null ? null : holder.process(), "A browser has the profile in "
                            + directory + " open; close it first");
                }
                // A crash's leftover, which a starting Chromium deletes too
                Files.deleteIfExists(lock);
            }
        }
        Path socketDirectory = null;
        ServerSocketChannel server = null;
        try {
            var random = new SecureRandom();
            socketDirectory = socketDirectory(random);
            var cookie = Long.toUnsignedString(random.nextLong());
            var socket = socketDirectory.resolve(SINGLETON_SOCKET);
            Files.deleteIfExists(directory.resolve(SINGLETON_SOCKET));
            Files.deleteIfExists(directory.resolve(SINGLETON_COOKIE));
            Files.createSymbolicLink(directory.resolve(SINGLETON_SOCKET), socket);
            Files.createSymbolicLink(directory.resolve(SINGLETON_COOKIE), Path.of(cookie));
            Files.createSymbolicLink(socketDirectory.resolve(SINGLETON_COOKIE), Path.of(cookie));
            server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            server.bind(UnixDomainSocketAddress.of(socket));
            var profileLock = new ProfileLock(directory, key, null, null, target, socketDirectory, cookie, server);
            profileLock.listen();
            return profileLock;
        } catch (IOException | RuntimeException failure) {
            if (server != null) {
                server.close();
            }
            deleteIfLinkedTo(directory.resolve(SINGLETON_LOCK), target);
            if (socketDirectory != null) {
                deleteTree(socketDirectory);
            }
            throw failure;
        }
    }

    /// {@return a new directory for the socket, readable by this user only, named as `mkdtemp` names Chromium's}
    ///
    /// The name is kept as short as Chromium's: a Unix socket's path is at most 104 bytes on macOS, whose temporary
    /// directory is long already.
    private static Path socketDirectory(SecureRandom random) throws IOException {
        var temporary = Path.of(System.getProperty("java.io.tmpdir"));
        var alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        for (var attempt = 0; ; attempt++) {
            var name = new StringBuilder("org.chromium.Chromium.");
            for (var i = 0; i < 6; i++) {
                name.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            try {
                return Files.createDirectory(temporary.resolve(name.toString()),
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            } catch (FileAlreadyExistsException exception) {
                if (attempt >= 16) {
                    throw exception;
                }
            }
        }
    }

    /// Answers every Chromium that connects to the socket while the lock is held, on threads of its own.
    private void listen() {
        var acceptor = Thread.ofPlatform().daemon().name("viridian-profile-lock").unstarted(() -> {
            while (true) {
                SocketChannel connection;
                try {
                    connection = server.accept();
                } catch (IOException exception) {
                    return;
                }
                var answerer = Thread.ofVirtual().unstarted(() -> answer(connection));
                synchronized (threads) {
                    threads.add(answerer);
                }
                answerer.start();
            }
        });
        acceptor.start();
    }

    /// Reads what a starting Chromium sends, waits for the write to end, and answers.
    private void answer(SocketChannel connection) {
        try (connection) {
            // A Chromium sends its command line, then closes its side for writing
            var drain = Thread.ofVirtual().start(() -> {
                var buffer = ByteBuffer.allocate(4096);
                try {
                    while (connection.read(buffer.clear()) >= 0) {
                        // The command line is not acted on
                    }
                } catch (IOException _) {
                    // The connection closed under the reader
                }
            });
            var done = released.await(ANSWER_WITHIN_MILLIS, TimeUnit.MILLISECONDS);
            connection.write(ByteBuffer.wrap((done ? "SHUTDOWN" : "ACK").getBytes(StandardCharsets.US_ASCII)));
            drain.join(1_000);
        } catch (IOException | InterruptedException _) {
            // The Chromium went away; nothing is left to answer
        }
    }

    /// Releases the lock: on Windows closes and deletes `lockfile`; elsewhere deletes the links it made, answers the
    /// Chromium waiting for it, and deletes its socket. Closing a released lock does nothing.
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (channel != null) {
                try {
                    fileLock.release();
                } finally {
                    channel.close();
                }
                return;
            }
            server.close();
            deleteIfLinkedTo(directory.resolve(SINGLETON_SOCKET), socketDirectory.resolve(SINGLETON_SOCKET).toString());
            deleteIfLinkedTo(directory.resolve(SINGLETON_COOKIE), cookie);
            deleteIfLinkedTo(directory.resolve(SINGLETON_LOCK), lockTarget);
            released.countDown();
            List<Thread> answering;
            synchronized (threads) {
                answering = List.copyOf(threads);
            }
            for (var thread : answering) {
                try {
                    thread.join(2_000);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            deleteTree(socketDirectory);
        } finally {
            HELD.remove(key);
        }
    }

    /// {@return whether a process holds the Windows lockfile: it cannot be opened for writing, or is locked}
    private static boolean isLockfileHeld(Path lockfile) throws IOException {
        try (var channel = FileChannel.open(lockfile, StandardOpenOption.WRITE)) {
            var held = channel.tryLock();
            if (held == null) {
                return true;
            }
            held.release();
            return false;
        } catch (OverlappingFileLockException _) {
            return true;
        } catch (NoSuchFileException _) {
            return false;
        } catch (FileSystemException _) {
            return Files.exists(lockfile);
        }
    }

    /// Who holds a `SingletonLock`.
    ///
    /// @param host    the host the holder runs on, empty for this one
    /// @param process the holder, if it runs on this host
    private record Holder(String host, ProcessHandle process) {
    }

    /// {@return who holds the `SingletonLock` `link`, or `null` if it is not held: there is none, it names no host, or
    /// its process no longer runs on this host}
    private static Holder singletonHolder(Path link) throws IOException {
        if (!Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        if (!Files.isSymbolicLink(link)) {
            // A lock file of an older macOS Chromium, held while a process locks it
            try (var channel = FileChannel.open(link, StandardOpenOption.WRITE)) {
                var held = channel.tryLock();
                if (held == null) {
                    return new Holder("", null);
                }
                held.release();
                return null;
            } catch (OverlappingFileLockException _) {
                return new Holder("", ProcessHandle.current());
            }
        }
        String target;
        try {
            target = Files.readSymbolicLink(link).toString();
        } catch (NoSuchFileException _) {
            return null;
        }
        var separator = target.lastIndexOf('-');
        if (separator <= 0) {
            return null;
        }
        long pid;
        try {
            pid = Long.parseLong(target.substring(separator + 1));
        } catch (NumberFormatException _) {
            return null;
        }
        var host = target.substring(0, separator);
        var local = hostName();
        if (!host.equals(local)) {
            return new Holder(host, null);
        }
        var process = ProcessHandle.of(pid).filter(ProcessHandle::isAlive).orElse(null);
        return process == null ? null : new Holder("", process);
    }

    /// Deletes the link `link` if it still points at `target`, leaving one another process made since.
    private static void deleteIfLinkedTo(Path link, String target) {
        try {
            if (Files.isSymbolicLink(link) && Files.readSymbolicLink(link).toString().equals(target)) {
                Files.delete(link);
            }
        } catch (IOException _) {
            // Gone already, or not ours to delete
        }
    }

    private static void deleteTree(Path directory) {
        try (var entries = Files.list(directory)) {
            for (var entry : (Iterable<Path>) entries::iterator) {
                Files.deleteIfExists(entry);
            }
            Files.deleteIfExists(directory);
        } catch (IOException _) {
            // A temporary directory the system cleans up
        }
    }

    /// {@return the name this host gives itself, as Chromium writes it into the link}
    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException _) {
            return "localhost";
        }
    }

    @Override
    public String toString() {
        return "ProfileLock[" + directory + "]";
    }
}
