package com.github.auties00.viridian;

import com.github.auties00.viridian.indexeddb.IndexedDbDirectory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/// The IndexedDB databases of a profile, storage bucket by storage bucket.
///
/// {@snippet lang = java :
/// var indexedDb = profile.indexedDb();
/// for (var name : indexedDb.names(origin)) {
///     try (var database = indexedDb.open(origin, name).orElseThrow()) {
///         System.out.println(name + " v" + database.version() + " on " + database.backend());
///     }
/// }
/// IdbDatabase app = indexedDb.read(origin, "app").orElseThrow();
/// indexedDb.put(origin, app.toBuilder().put(settings, IdbKey.of("theme"), JsValue.of("light")).build());
/// }
///
/// Each method takes a [StorageBucket], or a [StorageOrigin] for the default bucket of the origin's first-party key,
/// which is where a page that is not embedded by another site keeps its databases.
///
/// # Backends
/// Chromium keeps each bucket's IndexedDB on one backend: LevelDB, whenever the bucket has a LevelDB store, else
/// SQLite. The storage of [ChromiumProfile#indexedDb()] reads each bucket on that backend, and writes it there, or on
/// LevelDB for a bucket without data; that of [ChromiumProfile#indexedDb(StorageBackend)] reads and writes the given
/// backend only. Either way, a write deletes the bucket's data on the other backend, which would otherwise hide what
/// was written from Chromium, or be what Chromium reads.
///
/// # Writing
/// [#put(StorageBucket, IdbDatabase)] adds or replaces one database and keeps the bucket's others;
/// [#replaceAll(StorageBucket, Collection)] leaves the bucket with exactly the databases given;
/// [#delete(StorageBucket, String)] deletes one; [#copyFrom(IndexedDbStorage, StorageBucket)] copies every database of
/// a bucket from another profile, reading its records one at a time. The databases are written in the format Chrome
/// writes, so that the browser opens them as its own. A bucket other than an origin's first-party default one that the
/// profile's quota database does not record is recorded there, as Chromium records a bucket a page opens.
///
/// On the storage of a profile, each write is one step of its own, made as [ChromiumProfile#write(ProfileEditor)]
/// makes one, which holds the profile's lock and refuses a profile in use. On the storage of a [ProfileEdit], writes
/// are part of the edit, committed together when its editor returns; reads still see the profile as it was before
/// the edit.
///
/// A LevelDB store holds every database of its bucket, so writing one database there writes the others again, decoded
/// and serialized anew: a value of another database that this reader cannot decode, or cannot write, such as a File
/// System Access handle, fails the write.
public final class IndexedDbStorage {
    private final ChromiumProfile profile;
    private final StorageBackend backend;
    private final ProfileTransaction transaction;

    IndexedDbStorage(ChromiumProfile profile, StorageBackend backend, ProfileTransaction transaction) {
        this.profile = profile;
        this.backend = backend;
        this.transaction = transaction;
    }

    /// {@return the origins whose first-party default bucket has IndexedDB data, in the backend this storage reads}
    ///
    /// @throws IOException if the profile's directories or quota database cannot be read
    public List<StorageOrigin> origins() throws IOException {
        var origins = new ArrayList<StorageOrigin>();
        for (var bucket : buckets()) {
            if (bucket.isDefault() && bucket.key().isFirstParty()) {
                origins.add(bucket.key().origin());
            }
        }
        return List.copyOf(origins);
    }

    /// {@return every storage bucket with IndexedDB data, in the backend this storage reads: the origins' first-party
    /// default buckets, then the buckets the profile's quota database records, partitioned ones and named ones included}
    ///
    /// @throws IOException if the profile's directories or quota database cannot be read
    public List<StorageBucket> buckets() throws IOException {
        var buckets = new ArrayList<StorageBucket>();
        for (var entry : profile.indexedDbBuckets().entrySet()) {
            if (backend == null || entry.getValue().has(backend)) {
                buckets.add(entry.getKey());
            }
        }
        return List.copyOf(buckets);
    }

    /// {@return the backend that holds `origin`'s first-party default bucket, as [#backend(StorageBucket)] tells}
    ///
    /// @throws IOException if the profile's directories or quota database cannot be read
    public Optional<StorageBackend> backend(StorageOrigin origin) throws IOException {
        return backend(StorageBucket.defaultOf(origin));
    }

    /// {@return the backend that holds `bucket`'s IndexedDB data, as this storage reads it, or an empty optional if it
    /// has none there}
    ///
    /// @throws IOException if the profile's directories or quota database cannot be read
    public Optional<StorageBackend> backend(StorageBucket bucket) throws IOException {
        var location = profile.indexedDbLocation(Objects.requireNonNull(bucket, "bucket"));
        if (location == null) {
            return Optional.empty();
        }
        if (backend == null) {
            return location.backend();
        }
        return location.has(backend) ? Optional.of(backend) : Optional.empty();
    }

    /// {@return the names of the databases of `origin`'s first-party default bucket, sorted}
    ///
    /// @throws ViridianException if the metadata does not decode
    /// @throws IOException       if the files cannot be read
    public List<String> names(StorageOrigin origin) throws IOException {
        return names(StorageBucket.defaultOf(origin));
    }

    /// {@return the names of `bucket`'s databases, sorted}
    ///
    /// A database a page created but failed to open is left out, as Chromium leaves it out.
    ///
    /// @throws ViridianException if the metadata does not decode
    /// @throws IOException       if the files cannot be read
    public List<String> names(StorageBucket bucket) throws IOException {
        var backend = backend(bucket);
        return backend.isEmpty() ? List.of() : IndexedDbDirectory.names(profile.indexedDbLocation(bucket), bucket, backend.get());
    }

    /// {@return the database `name` of `origin`'s first-party default bucket, as [#open(StorageBucket, String)] opens
    /// it}
    ///
    /// @throws ViridianException if the database's metadata does not decode, or its schema is newer than this reader
    ///                           knows
    /// @throws IOException       if the files cannot be read
    public Optional<IdbDatabaseReader> open(StorageOrigin origin, String name) throws IOException {
        return open(StorageBucket.defaultOf(origin), name);
    }

    /// {@return `bucket`'s database `name`, open, or an empty optional if there is none; the caller closes it}
    ///
    /// @throws ViridianException if the database's metadata does not decode, or its schema is newer than this reader
    ///                           knows
    /// @throws IOException       if the files cannot be read
    public Optional<IdbDatabaseReader> open(StorageBucket bucket, String name) throws IOException {
        Objects.requireNonNull(name, "name");
        var backend = backend(bucket);
        return backend.isEmpty() ? Optional.empty() : IndexedDbDirectory.open(profile.indexedDbLocation(bucket), bucket, name, backend.get());
    }

    /// {@return the database `name` of `origin`'s first-party default bucket, as [#read(StorageBucket, String)] reads
    /// it}
    ///
    /// @throws ViridianException if the metadata or a value does not decode
    /// @throws IOException       if the files cannot be read
    public Optional<IdbDatabase> read(StorageOrigin origin, String name) throws IOException {
        return read(StorageBucket.defaultOf(origin), name);
    }

    /// {@return `bucket`'s database `name`, read whole, or an empty optional if there is none}
    ///
    /// A blob or file in a value keeps reading its bytes from the profile, which must stay in place for as long as
    /// they may be read.
    ///
    /// @throws ViridianException if the metadata or a value does not decode
    /// @throws IOException       if the files cannot be read
    public Optional<IdbDatabase> read(StorageBucket bucket, String name) throws IOException {
        var reader = open(bucket, name);
        if (reader.isEmpty()) {
            return Optional.empty();
        }
        try (var database = reader.get()) {
            return Optional.of(database.readAll());
        }
    }

    /// {@return every database of `origin`'s first-party default bucket, read whole, by name}
    ///
    /// @throws ViridianException if the metadata or a value does not decode
    /// @throws IOException       if the files cannot be read
    public List<IdbDatabase> readAll(StorageOrigin origin) throws IOException {
        return readAll(StorageBucket.defaultOf(origin));
    }

    /// {@return every database of `bucket`, read whole, by name}
    ///
    /// @throws ViridianException if the metadata or a value does not decode
    /// @throws IOException       if the files cannot be read
    public List<IdbDatabase> readAll(StorageBucket bucket) throws IOException {
        var databases = new ArrayList<IdbDatabase>();
        for (var name : names(bucket)) {
            read(bucket, name).ifPresent(databases::add);
        }
        return List.copyOf(databases);
    }

    /// Adds `database` to `origin`'s first-party default bucket, as [#put(StorageBucket, IdbDatabase)] adds it.
    ///
    /// @throws ProfileInUseException    if a browser, an app or another writer has the profile or the bucket's store
    ///                                  open
    /// @throws ViridianException        if a value cannot be written, such as a blob whose bytes are gone, or the
    ///                                  profile's browser is too old to read the values
    /// @throws IllegalArgumentException if a value of another database of the bucket cannot be written again
    /// @throws IOException              if the files cannot be read or written
    public void put(StorageOrigin origin, IdbDatabase database) throws IOException {
        put(StorageBucket.defaultOf(origin), database);
    }

    /// Adds `database` to `bucket`'s databases, replacing the one of its name if there is one, and keeping the others.
    ///
    /// @throws ProfileInUseException    if a browser, an app or another writer has the profile or the bucket's store
    ///                                  open
    /// @throws ViridianException        if a value cannot be written, such as a blob whose bytes are gone, or the
    ///                                  profile's browser is too old to read the values
    /// @throws IllegalArgumentException if a value of another database of the bucket cannot be written again
    /// @throws IOException              if the files cannot be read or written
    public void put(StorageBucket bucket, IdbDatabase database) throws IOException {
        Objects.requireNonNull(bucket, "bucket");
        Objects.requireNonNull(database, "database");
        write(transaction -> transaction.indexedDb(bucket, backend).put(database));
    }

    /// Leaves `origin`'s first-party default bucket with exactly `databases`, as
    /// [#replaceAll(StorageBucket, Collection)] does.
    ///
    /// @throws IllegalArgumentException if two databases share a name
    /// @throws ProfileInUseException    if a browser, an app or another writer has the profile or the bucket's store
    ///                                  open
    /// @throws ViridianException        if a value cannot be written, or the profile's browser is too old to read the
    ///                                  values
    /// @throws IOException              if the files cannot be read or written
    public void replaceAll(StorageOrigin origin, Collection<IdbDatabase> databases) throws IOException {
        replaceAll(StorageBucket.defaultOf(origin), databases);
    }

    /// Leaves `bucket` with exactly `databases`, deleting its others.
    ///
    /// @throws IllegalArgumentException if two databases share a name
    /// @throws ProfileInUseException    if a browser, an app or another writer has the profile or the bucket's store
    ///                                  open
    /// @throws ViridianException        if a value cannot be written, or the profile's browser is too old to read the
    ///                                  values
    /// @throws IOException              if the files cannot be read or written
    public void replaceAll(StorageBucket bucket, Collection<IdbDatabase> databases) throws IOException {
        Objects.requireNonNull(bucket, "bucket");
        var copy = List.copyOf(databases);
        var names = new HashSet<String>();
        for (var database : copy) {
            if (!names.add(database.name())) {
                throw new IllegalArgumentException("Two databases are named " + database.name());
            }
        }
        write(transaction -> {
            var plan = transaction.indexedDb(bucket, backend);
            plan.clear();
            for (var database : copy) {
                plan.put(database);
            }
        });
    }

    /// Deletes the database `name` of `origin`'s first-party default bucket, as [#delete(StorageBucket, String)] does.
    ///
    /// @throws ProfileInUseException if a browser, an app or another writer has the profile or the bucket's store open
    /// @throws IOException           if the files cannot be read or written
    public void delete(StorageOrigin origin, String name) throws IOException {
        delete(StorageBucket.defaultOf(origin), name);
    }

    /// Deletes `bucket`'s database `name`, if it has one, keeping the others.
    ///
    /// @throws ProfileInUseException if a browser, an app or another writer has the profile or the bucket's store open
    /// @throws IOException           if the files cannot be read or written
    public void delete(StorageBucket bucket, String name) throws IOException {
        Objects.requireNonNull(bucket, "bucket");
        Objects.requireNonNull(name, "name");
        write(transaction -> transaction.indexedDb(bucket, backend).delete(name));
    }

    /// Leaves `origin`'s first-party default bucket with exactly the databases it has in `source`, as
    /// [#copyFrom(IndexedDbStorage, StorageBucket)] copies them.
    ///
    /// @throws ProfileInUseException    if a browser, an app or another writer has this profile or the bucket's store
    ///                                  open
    /// @throws ViridianException        if a value of the source does not decode, or this profile's browser is too old
    ///                                  to read the values
    /// @throws IllegalArgumentException if a value of the source cannot be written, such as a File System Access
    ///                                  handle
    /// @throws IOException              if the files cannot be read or written
    public void copyFrom(IndexedDbStorage source, StorageOrigin origin) throws IOException {
        copyFrom(source, StorageBucket.defaultOf(origin));
    }

    /// Leaves `bucket` with exactly the databases it has in `source`, copied record by record as they are read, so that
    /// a bucket need not fit in memory.
    ///
    /// The source is read when the write is made: at once on the storage of a profile, and when the edit commits on
    /// that of a [ProfileEdit]. Blobs and files are read from the source profile then.
    ///
    /// @throws ProfileInUseException    if a browser, an app or another writer has this profile or the bucket's store
    ///                                  open
    /// @throws ViridianException        if a value of the source does not decode, or this profile's browser is too old
    ///                                  to read the values
    /// @throws IllegalArgumentException if a value of the source cannot be written, such as a File System Access
    ///                                  handle
    /// @throws IOException              if the files cannot be read or written
    public void copyFrom(IndexedDbStorage source, StorageBucket bucket) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(bucket, "bucket");
        var names = source.names(bucket);
        write(transaction -> {
            var plan = transaction.indexedDb(bucket, backend);
            plan.clear();
            for (var name : names) {
                plan.copy(source, bucket, name);
            }
        });
    }

    /// Stages a write in the edit this storage belongs to, or makes it as a write of its own.
    private void write(ProfileTransaction.Step step) throws IOException {
        if (transaction != null) {
            step.apply(transaction);
        } else {
            profile.write(edit -> step.apply(edit.transaction()));
        }
    }

    @Override
    public String toString() {
        return "IndexedDbStorage[" + profile.directory() + (backend == null ? "" : ", " + backend) + "]";
    }
}
