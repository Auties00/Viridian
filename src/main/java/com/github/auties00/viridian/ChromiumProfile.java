package com.github.auties00.viridian;

import com.github.auties00.viridian.indexeddb.IndexedDbDirectory;
import com.github.auties00.viridian.profile.ProfileLock;
import com.github.auties00.viridian.profile.QuotaDatabase;
import com.github.auties00.viridian.profile.StoreReplacement;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/// A profile of a Chromium-based browser or app: the directory that holds its sites' storage.
///
/// {@snippet lang = java :
/// var profile = ChromiumProfile.open(Path.of(System.getenv("LOCALAPPDATA"), "Google", "Chrome", "User Data", "Default"));
/// var origin = StorageOrigin.parse("https://example.com");
/// try (var database = profile.indexedDb().open(origin, "app").orElseThrow();
///      var records = database.records("settings")) {
///     records.forEach(record -> System.out.println(record.key() + " = " + record.value()));
/// }
/// }
///
/// A browser's profile is a directory such as `User Data/Default` in the browser's user data directory, which
/// [ChromiumUserData] lists; an Electron app's is its `userData` directory, and a persistent partition of one is
/// `Partitions/<name>` inside it; a WebView2 app's is its `EBWebView/Default` directory.
///
/// # Reading
/// [#indexedDb()], [#localStorage()] and [#sessionStorage()] read the profile's storage on the backend Chromium reads,
/// and their overloads on the backend given. Reading never changes the profile and takes no lock: a profile the
/// browser has closed reads as the browser left it, and one the browser is writing gives a best-effort view, which may
/// lack its latest writes, or fail to read when a file changes under the reader.
///
/// # Writing
/// Writes replace stores in the formats Chrome writes, so that the browser opens them as its own.
/// [#write(ProfileEditor)] makes several writes in one step; a write on a facade of the profile itself is a step of
/// its own.
///
/// {@snippet lang = java :
/// profile.write(edit -> {
///     edit.indexedDb().put(origin, database);
///     edit.localStorage().replaceAll(origin, Map.of("onboarded", "true"));
/// });
/// }
///
/// A write holds the browser's own lock on the profile, in [#lockDirectory()], for as long as it runs: it fails with
/// [ProfileInUseException] when a browser, an app or another writer, in this process or another, has the profile
/// open, and a browser that starts meanwhile does not open it. The new stores are written beside the old ones and
/// put in their place in one step, which a crash cannot leave half done: the next write, or [#recover()], finishes it
/// if it had begun and drops it otherwise.
///
/// A profile holds no resource of its own; the readers it opens may, until they are closed. It is safe for concurrent
/// use.
public final class ChromiumProfile {
    /// The directory of a profile that holds the IndexedDB of its first-party default buckets.
    static final Path INDEXED_DB = Path.of("IndexedDB");
    /// The LevelDB database of a profile's `localStorage`.
    static final Path LOCAL_STORAGE_LEVELDB = Path.of("Local Storage", "leveldb");
    /// The directory of a profile's `localStorage` on SQLite, one database per storage key.
    static final Path LOCAL_STORAGE_SQLITE = Path.of("LocalStorage");
    /// The LevelDB database of a profile's `sessionStorage`.
    static final Path SESSION_STORAGE_LEVELDB = Path.of("Session Storage");
    /// The directory of a profile's `sessionStorage` on SQLite.
    static final Path SESSION_STORAGE_SQLITE = Path.of("SessionStorage");

    private final Path directory;

    private ChromiumProfile(Path directory) {
        this.directory = directory;
    }

    /// {@return the existing profile in `directory`}
    ///
    /// @throws NoSuchFileException      if `directory` is not a directory
    /// @throws IllegalArgumentException if `directory` is a browser's user data directory, which holds profiles
    ///                                  rather than being one; [ChromiumUserData#profile(String)] opens those
    /// @throws IOException              if the directory cannot be read
    public static ChromiumProfile open(Path directory) throws IOException {
        Objects.requireNonNull(directory, "directory");
        if (!Files.isDirectory(directory)) {
            throw new NoSuchFileException(directory.toString(), null, "Not a directory");
        }
        if (ChromiumUserData.isUserDataDirectory(directory)) {
            throw new IllegalArgumentException(directory + " is a user data directory, not a profile; its profiles are "
                    + ChromiumUserData.open(directory).profileNames());
        }
        return new ChromiumProfile(directory.toAbsolutePath().normalize());
    }

    /// {@return a new, empty profile in `directory`, which is created with its parents}
    ///
    /// The profile holds nothing until written to, and a browser started on it, on its user data directory for a
    /// browser, makes the rest of a profile when it first opens it.
    ///
    /// @throws FileAlreadyExistsException if `directory` exists and is not an empty directory
    /// @throws IOException                if the directory cannot be created
    public static ChromiumProfile create(Path directory) throws IOException {
        Objects.requireNonNull(directory, "directory");
        if (Files.exists(directory)) {
            var empty = false;
            if (Files.isDirectory(directory)) {
                try (var entries = Files.list(directory)) {
                    empty = entries.findAny().isEmpty();
                }
            }
            if (!empty) {
                throw new FileAlreadyExistsException(directory.toString(), null, "Not an empty directory");
            }
        }
        Files.createDirectories(directory);
        return new ChromiumProfile(directory.toAbsolutePath().normalize());
    }

    /// {@return the profile's directory}
    public Path directory() {
        return directory;
    }

    /// {@return the directory that holds the lock a browser running the profile has: its user data directory, the
    /// profile's parent for a browser and the profile itself for an Electron app, or the app's directory for a
    /// partition of one}
    public Path lockDirectory() {
        return ProfileLock.find(directory);
    }

    /// {@return whether a browser, an app or a writer has the profile open: it holds the browser's lock, or the lock of
    /// one of the profile's DOM Storage databases}
    ///
    /// An app that takes no browser lock, as an Electron app that does not ask for a single instance, is seen only
    /// while it has a DOM Storage database open.
    ///
    /// @throws IOException if a lock cannot be inspected
    public boolean isInUse() throws IOException {
        try {
            ProfileLock.check(lockDirectory());
            for (var store : new Path[]{LOCAL_STORAGE_LEVELDB, SESSION_STORAGE_LEVELDB}) {
                ProfileLock.checkLevelDb(directory.resolve(store));
            }
            return false;
        } catch (ProfileInUseException _) {
            return true;
        }
    }

    /// {@return the profile's IndexedDB, each origin on the backend Chromium reads it on}
    public IndexedDbStorage indexedDb() {
        return new IndexedDbStorage(this, null, null);
    }

    /// {@return the profile's IndexedDB on `backend` only}
    public IndexedDbStorage indexedDb(StorageBackend backend) {
        return new IndexedDbStorage(this, Objects.requireNonNull(backend, "backend"), null);
    }

    /// {@return the profile's `localStorage`, on the backend Chromium reads, written to every backend the profile has}
    public LocalStorage localStorage() {
        return new LocalStorage(this, null, null);
    }

    /// {@return the profile's `localStorage` on `backend` only}
    public LocalStorage localStorage(StorageBackend backend) {
        return new LocalStorage(this, Objects.requireNonNull(backend, "backend"), null);
    }

    /// {@return the profile's `sessionStorage`, on the backend Chromium reads}
    public SessionStorage sessionStorage() {
        return new SessionStorage(this, null);
    }

    /// {@return the profile's `sessionStorage` on `backend` only}
    public SessionStorage sessionStorage(StorageBackend backend) {
        return new SessionStorage(this, Objects.requireNonNull(backend, "backend"));
    }

    /// Makes the writes `editor` stages through its edit, in one step: every store they change is replaced, or none.
    ///
    /// The profile's lock is held from before the editor runs until the stores are in place, so the editor reads the
    /// profile as no one else changes it. A replacement a crash left behind is finished or dropped first. If the
    /// editor throws, or a store cannot be written, nothing changes.
    ///
    /// @throws ProfileInUseException if a browser, an app or another writer has the profile, or a store it writes,
    ///                               open; a thread already writing the profile is refused too
    /// @throws ViridianException     if a value cannot be written, such as a blob whose bytes are gone
    /// @throws IOException           if the editor throws it, or the files cannot be read or written
    public void write(ProfileEditor editor) throws IOException {
        Objects.requireNonNull(editor, "editor");
        try (var _ = ProfileLock.acquire(lockDirectory())) {
            StoreReplacement.recover(directory);
            var transaction = new ProfileTransaction(this);
            try {
                editor.edit(new ProfileEdit(this, transaction));
                transaction.commit();
            } finally {
                transaction.abandon();
            }
        }
    }

    /// {@return whether a write a crash cut short left a replacement behind, which [#recover()] finishes or drops}
    ///
    /// It is `false` while a browser, an app or a writer holds the profile's lock, since a writer's replacement then
    /// looks like a crash's, and nothing may change the profile meanwhile.
    ///
    /// @throws IOException if the profile or its lock cannot be inspected
    public boolean needsRecovery() throws IOException {
        if (!StoreReplacement.hasLeftovers(directory)) {
            return false;
        }
        try {
            ProfileLock.check(lockDirectory());
            return true;
        } catch (ProfileInUseException _) {
            return false;
        }
    }

    /// Finishes the replacement a crash left behind if it had begun, or drops it, holding the profile's lock meanwhile.
    ///
    /// A browser must not run on a profile that needs recovery; every write recovers first.
    ///
    /// @throws ProfileInUseException if a browser, an app or another writer has the profile open
    /// @throws IOException           if the replacement cannot be finished or dropped
    public void recover() throws IOException {
        try (var _ = ProfileLock.acquire(lockDirectory())) {
            StoreReplacement.recover(directory);
        }
    }

    /// {@return where the IndexedDB data of `bucket` is, or `null` for a bucket the quota database does not record,
    /// which has none}
    ///
    /// An origin's first-party default bucket is in the profile's `IndexedDB` directory, as Chromium keeps it, unless
    /// that has no data for it and the quota database names a directory of `WebStorage` that has; every other bucket is
    /// in the directory of `WebStorage` its id names.
    ///
    /// @throws IOException if the quota database or the directories cannot be read
    IndexedDbDirectory.Location indexedDbLocation(StorageBucket bucket) throws IOException {
        var legacy = bucket.isDefault() && bucket.key().isFirstParty()
                ? IndexedDbDirectory.Location.legacy(directory.resolve(INDEXED_DB), bucket.key().origin()) : null;
        if (legacy != null && legacy.backend().isPresent()) {
            return legacy;
        }
        for (var recorded : QuotaDatabase.read(directory.resolve(QuotaDatabase.FILE))) {
            if (recorded.name().equals(bucket.name()) && recorded.storageKey().equals(bucket.key().serialize())) {
                var location = bucketLocation(recorded.id());
                if (legacy == null || location.backend().isPresent()) {
                    return location;
                }
            }
        }
        return legacy;
    }

    /// {@return the location of the IndexedDB data of the bucket of id `id`}
    IndexedDbDirectory.Location bucketLocation(long id) {
        return IndexedDbDirectory.Location.bucket(directory.resolve(QuotaDatabase.bucketDirectory(id)).resolve(INDEXED_DB));
    }

    /// {@return every bucket with IndexedDB data, and where it is: the first-party default buckets of the profile's
    /// `IndexedDB` directory, then those the quota database records, by id}
    ///
    /// @throws IOException if the quota database or the directories cannot be read
    Map<StorageBucket, IndexedDbDirectory.Location> indexedDbBuckets() throws IOException {
        var buckets = new LinkedHashMap<StorageBucket, IndexedDbDirectory.Location>();
        var root = directory.resolve(INDEXED_DB);
        for (var origin : IndexedDbDirectory.origins(root)) {
            buckets.put(StorageBucket.defaultOf(origin), IndexedDbDirectory.Location.legacy(root, origin));
        }
        for (var recorded : QuotaDatabase.read(directory.resolve(QuotaDatabase.FILE))) {
            StorageBucket bucket;
            try {
                bucket = new StorageBucket(StorageKey.parse(recorded.storageKey()), recorded.name());
            } catch (IllegalArgumentException _) {
                // A storage key or name this reader does not know
                continue;
            }
            var location = bucketLocation(recorded.id());
            if (!buckets.containsKey(bucket) && location.backend().isPresent()) {
                buckets.put(bucket, location);
            }
        }
        return buckets;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ChromiumProfile profile && directory.equals(profile.directory);
    }

    @Override
    public int hashCode() {
        return directory.hashCode();
    }

    @Override
    public String toString() {
        return "ChromiumProfile[" + directory + "]";
    }
}
