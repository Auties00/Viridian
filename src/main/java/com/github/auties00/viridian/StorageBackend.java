package com.github.auties00.viridian;

/// The two databases Chromium keeps storage in: LevelDB, and SQLite, which it is moving IndexedDB and DOM Storage to.
public enum StorageBackend {
    /// LevelDB. IndexedDB keeps one database per origin, holding all of the origin's IndexedDB databases:
    /// `IndexedDB/<origin>.indexeddb.leveldb`, with large blobs in `<origin>.indexeddb.blob`. DOM Storage keeps one
    /// per profile: `Local Storage/leveldb` and `Session Storage`.
    LEVELDB,
    /// SQLite. IndexedDB keeps one file per IndexedDB database, blobs included, in a directory per origin:
    /// `IndexedDB/<origin>/`. DOM Storage keeps one file per profile: `LocalStorage` and `SessionStorage`. Chromium
    /// rolls it out for new IndexedDB databases from Chrome 156, and for DOM Storage through field trials.
    SQLITE
}
