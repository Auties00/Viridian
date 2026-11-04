package com.github.auties00.viridian;

import com.github.auties00.viridian.domstorage.LevelDbDomStorageWriter;
import com.github.auties00.viridian.domstorage.SqliteDomStorageWriter;
import com.github.auties00.viridian.indexeddb.DatabaseSource;
import com.github.auties00.viridian.indexeddb.IdbDataFormat;
import com.github.auties00.viridian.indexeddb.IndexedDbDirectory;
import com.github.auties00.viridian.indexeddb.LevelDbIndexedDbWriter;
import com.github.auties00.viridian.indexeddb.SqliteIndexedDbWriter;
import com.github.auties00.viridian.profile.BrowserVersion;
import com.github.auties00.viridian.profile.ProfileLock;
import com.github.auties00.viridian.profile.QuotaDatabase;
import com.github.auties00.viridian.profile.StoreReplacement;
import com.github.auties00.viridian.value.StructuredClone;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/// The writes of one [ChromiumProfile#write(ProfileEditor)]: what the facades of its edit stage, kept until the
/// editor returns and then written into staging and committed as one [StoreReplacement].
///
/// IndexedDB writes are kept per storage bucket as the databases it ends with, each one a database given in memory, a
/// database of another profile to copy, or one the bucket has now, which a LevelDB store, holding every database of
/// the bucket, writes again. A bucket the quota database does not record yet is recorded in a copy of it, staged with
/// the rest. `localStorage` writes are kept per backend as the items each storage key ends with.
final class ProfileTransaction {
    private final ChromiumProfile profile;
    private final LinkedHashMap<StorageBucket, IdbPlan> indexedDb = new LinkedHashMap<>();
    private final EnumMap<StorageBackend, LinkedHashMap<StorageKey, Map<String, String>>> localStorage = new EnumMap<>(StorageBackend.class);
    private boolean open = true;

    ProfileTransaction(ChromiumProfile profile) {
        this.profile = profile;
    }

    /// {@return the IndexedDB plan of `bucket`, made if it has none; `backend` is the backend a pinned facade writes,
    /// or `null` for the backend the bucket's data is on now, LevelDB for a bucket with none}
    ///
    /// @throws IllegalStateException if the edit is over, or wrote the bucket on the other backend already
    IdbPlan indexedDb(StorageBucket bucket, StorageBackend backend) throws IOException {
        checkOpen();
        var plan = indexedDb.get(bucket);
        if (plan == null) {
            var location = profile.indexedDbLocation(bucket);
            var current = location == null ? Optional.<StorageBackend>empty() : location.backend();
            plan = new IdbPlan(bucket, location, backend != null ? backend : current.orElse(StorageBackend.LEVELDB));
            indexedDb.put(bucket, plan);
        } else if (backend != null && plan.target != backend) {
            throw new IllegalStateException("The edit writes the IndexedDB of " + bucket + " to " + plan.target + " already, not " + backend);
        }
        return plan;
    }

    /// Makes the `localStorage` items of `key` on each of `backends` exactly `items`.
    ///
    /// @throws IllegalStateException if the edit is over
    void localStorage(StorageKey key, Set<StorageBackend> backends, Map<String, String> items) {
        checkOpen();
        for (var backend : backends) {
            localStorage.computeIfAbsent(backend, _ -> new LinkedHashMap<>()).put(key, Map.copyOf(items));
        }
    }

    /// Fails unless the edit is still going.
    void checkOpen() {
        if (!open) {
            throw new IllegalStateException("The edit is over: its facades are valid only inside write(...)");
        }
    }

    /// Writes every store the edit changed into staging and commits them in one step.
    ///
    /// @throws ViridianException if a store is in use or a value cannot be written
    /// @throws IOException       if the stores cannot be read or written
    void commit() throws IOException {
        checkOpen();
        open = false;
        if (indexedDb.isEmpty() && localStorage.isEmpty()) {
            return;
        }
        var directory = profile.directory();
        var browser = BrowserVersion.of(directory, profile.lockDirectory());
        try (var replacement = StoreReplacement.begin(directory)) {
            register(directory, replacement);
            for (var plan : indexedDb.values()) {
                plan.stage(directory, replacement, browser);
            }
            for (var entry : localStorage.entrySet()) {
                stageLocalStorage(directory, replacement, entry.getKey(), entry.getValue());
            }
            replacement.commit();
        }
    }

    /// Records in a staged copy of the quota database the buckets the edit writes that it does not record yet, and
    /// gives their plans the directories of `WebStorage` their new ids name.
    private void register(Path directory, StoreReplacement replacement) throws IOException {
        var unregistered = new ArrayList<IdbPlan>();
        var registrations = new ArrayList<QuotaDatabase.Registration>();
        for (var plan : indexedDb.values()) {
            if (plan.location == null && plan.databases != null && !plan.databases.isEmpty()) {
                unregistered.add(plan);
                var key = plan.bucket.key();
                registrations.add(new QuotaDatabase.Registration(key.serialize(), key.origin().host(), plan.bucket.name()));
            }
        }
        if (unregistered.isEmpty()) {
            return;
        }
        var ids = QuotaDatabase.register(directory.resolve(QuotaDatabase.FILE), replacement.stage(QuotaDatabase.FILE), registrations);
        deleteSidecars(replacement, QuotaDatabase.FILE);
        for (var i = 0; i < unregistered.size(); i++) {
            unregistered.get(i).location = profile.bucketLocation(ids.get(i));
        }
    }

    /// Ends the edit without committing what it staged.
    void abandon() {
        open = false;
    }

    private static void stageLocalStorage(Path directory, StoreReplacement replacement, StorageBackend backend,
                                          Map<StorageKey, Map<String, String>> items) throws IOException {
        switch (backend) {
            case LEVELDB -> {
                var existing = directory.resolve(ChromiumProfile.LOCAL_STORAGE_LEVELDB);
                ProfileLock.checkLevelDb(existing);
                LevelDbDomStorageWriter.replaceLocalStorage(existing, replacement.stage(ChromiumProfile.LOCAL_STORAGE_LEVELDB), items);
            }
            case SQLITE -> {
                var existing = directory.resolve(ChromiumProfile.LOCAL_STORAGE_SQLITE);
                SqliteDomStorageWriter.replaceLocalStorage(existing, replacement.stage(ChromiumProfile.LOCAL_STORAGE_SQLITE), items);
                // The copy holds what the journal and the log held; left in place, SQLite would apply them to it
                deleteSidecars(replacement, ChromiumProfile.LOCAL_STORAGE_SQLITE);
            }
        }
    }

    /// Makes the commit delete the journal, write-ahead log and shared memory of the SQLite database `file`.
    private static void deleteSidecars(StoreReplacement replacement, Path file) {
        for (var suffix : List.of("-journal", "-wal", "-shm")) {
            replacement.delete(file.resolveSibling(file.getFileName() + suffix));
        }
    }

    /// The databases one storage bucket ends with, on the backend it is written to.
    static final class IdbPlan {
        private final StorageBucket bucket;
        // Where the bucket's data is, or null for a bucket the quota database does not record until it is registered
        private IndexedDbDirectory.Location location;
        private final StorageBackend target;
        // The databases the bucket ends with, by name, in the order given; null until the edit changes them
        private LinkedHashMap<String, DatabaseRef> databases;
        // The names of the databases the bucket has now on the target backend
        private Set<String> existing;

        private IdbPlan(StorageBucket bucket, IndexedDbDirectory.Location location, StorageBackend target) {
            this.bucket = bucket;
            this.location = location;
            this.target = target;
        }

        /// {@return the databases the bucket ends with, starting from those it has now}
        private LinkedHashMap<String, DatabaseRef> databases() throws IOException {
            if (databases == null) {
                existing = location == null ? new LinkedHashSet<>() : new LinkedHashSet<>(IndexedDbDirectory.names(location, bucket, target));
                databases = new LinkedHashMap<>();
                for (var name : existing) {
                    databases.put(name, new DatabaseRef.Existing(name));
                }
            }
            return databases;
        }

        void put(IdbDatabase database) throws IOException {
            databases().put(database.name(), new DatabaseRef.Memory(database));
        }

        void delete(String name) throws IOException {
            databases().remove(name);
        }

        void clear() throws IOException {
            databases().clear();
        }

        void copy(IndexedDbStorage source, StorageBucket sourceBucket, String name) throws IOException {
            databases().put(name, new DatabaseRef.Copy(source, sourceBucket, name));
        }

        /// Writes the bucket's new store into staging, and makes the commit delete what it replaces.
        ///
        /// @param browser the major version of the browser that last ran the profile, if recorded
        /// @throws ViridianException with reason `UNSUPPORTED` if that browser cannot read the values written
        void stage(Path profile, StoreReplacement replacement, OptionalInt browser) throws IOException {
            if (databases == null || location == null) {
                return;
            }
            if (Files.isDirectory(location.levelDb())) {
                ProfileLock.checkLevelDb(location.levelDb());
            }
            var relative = location.relativeTo(profile);
            var opened = new ArrayList<AutoCloseable>();
            try {
                var format = new IdbDataFormat();
                switch (target) {
                    case LEVELDB -> {
                        var sources = new ArrayList<DatabaseSource>();
                        for (var reference : databases.values()) {
                            sources.add(reference.open(this, opened));
                        }
                        if (sources.isEmpty()) {
                            replacement.delete(relative.levelDb());
                            replacement.delete(relative.blobs());
                        } else {
                            format.include(LevelDbIndexedDbWriter.write(replacement.stage(relative.levelDb()), replacement.stage(relative.blobs()),
                                    bucket.key().origin(), sources, StructuredClone::serialize));
                        }
                        deleteSqlite(profile, replacement, relative, Set.of());
                    }
                    case SQLITE -> {
                        var kept = new LinkedHashSet<String>();
                        for (var reference : databases.values()) {
                            if (reference instanceof DatabaseRef.Existing(var name)) {
                                kept.add(name);
                                continue;
                            }
                            var source = reference.open(this, opened);
                            var file = relative.sqlite().resolve(SqliteIndexedDbWriter.fileName(source.name()));
                            format.include(SqliteIndexedDbWriter.write(replacement.stage(file), source, StructuredClone::serialize));
                            deleteSidecars(replacement, file);
                            replacement.delete(file.resolveSibling(file.getFileName() + "_"));
                            kept.add(source.name());
                        }
                        deleteSqlite(profile, replacement, relative, kept);
                        replacement.delete(relative.levelDb());
                        replacement.delete(relative.blobs());
                    }
                }
                if (browser.isPresent() && browser.getAsInt() < format.minimumChromeVersion()) {
                    throw new ViridianException(ViridianException.Reason.UNSUPPORTED, "The profile was last run by Chrome " + browser.getAsInt()
                            + ", which cannot read the IndexedDB values of " + bucket + ": they need Chrome " + format.minimumChromeVersion());
                }
            } finally {
                for (var resource : opened) {
                    try {
                        resource.close();
                    } catch (Exception _) {
                        // A reader of a store that is replaced anyway
                    }
                }
            }
        }

        /// Makes the commit delete the bucket's SQLite databases that are not in `kept`, with their sidecars and blob
        /// directories, and the bucket's SQLite directory itself when it is its own and nothing is left in it.
        private void deleteSqlite(Path profile, StoreReplacement replacement, IndexedDbDirectory.Location relative, Set<String> kept)
                throws IOException {
            var ownDirectory = !relative.levelDb().getParent().equals(relative.sqlite());
            if (ownDirectory && kept.isEmpty()) {
                replacement.delete(relative.sqlite());
                return;
            }
            var keptFiles = new LinkedHashSet<String>();
            for (var name : kept) {
                keptFiles.add(SqliteIndexedDbWriter.fileName(name));
            }
            for (var file : IndexedDbDirectory.sqliteFiles(profile.resolve(relative.sqlite()))) {
                var name = file.getFileName().toString();
                if (!keptFiles.contains(name)) {
                    var target = relative.sqlite().resolve(name);
                    replacement.delete(target);
                    deleteSidecars(replacement, target);
                    replacement.delete(target.resolveSibling(name + "_"));
                }
            }
        }
    }

    /// A database a bucket ends with.
    private sealed interface DatabaseRef {
        /// {@return the database as the writers read it, opening what it reads from into `opened`}
        DatabaseSource open(IdbPlan plan, List<AutoCloseable> opened) throws IOException;

        /// A database the bucket has now, written again.
        record Existing(String name) implements DatabaseRef {
            @Override
            public DatabaseSource open(IdbPlan plan, List<AutoCloseable> opened) throws IOException {
                var reader = IndexedDbDirectory.open(plan.location, plan.bucket, name, plan.target)
                        .orElseThrow(() -> new IOException("The database " + name + " of " + plan.bucket + " is gone"));
                opened.add(reader);
                return (DatabaseSource) reader;
            }
        }

        /// A database given in memory.
        record Memory(IdbDatabase database) implements DatabaseRef {
            @Override
            public DatabaseSource open(IdbPlan plan, List<AutoCloseable> opened) {
                return database.source();
            }
        }

        /// A database of another profile, copied as it is read.
        record Copy(IndexedDbStorage source, StorageBucket bucket, String name) implements DatabaseRef {
            @Override
            public DatabaseSource open(IdbPlan plan, List<AutoCloseable> opened) throws IOException {
                var reader = source.open(bucket, name)
                        .orElseThrow(() -> new IOException("The database " + name + " of " + bucket + " to copy is gone"));
                opened.add(reader);
                return (DatabaseSource) reader;
            }
        }
    }

    /// A write a facade stages in a transaction.
    @FunctionalInterface
    interface Step {
        /// Stages the write in `transaction`.
        ///
        /// @throws IOException if what it reads to stage cannot be read
        void apply(ProfileTransaction transaction) throws IOException;
    }

    @Override
    public String toString() {
        return "ProfileTransaction[" + profile + ", " + indexedDb.keySet() + "]";
    }
}
