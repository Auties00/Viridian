package com.github.auties00.viridian;

/// The two ways Chromium stores IndexedDB on disk.
public enum IndexedDbBackend {
    /// One LevelDB database per origin, holding all of the origin's IndexedDB databases: `<origin>.indexeddb.leveldb`,
    /// with large blobs in `<origin>.indexeddb.blob`.
    LEVELDB,
    /// One SQLite file per IndexedDB database, in a directory per origin, blobs included. Chromium uses it for
    /// databases created since Chrome 156, and earlier where a field trial enabled it.
    SQLITE
}
