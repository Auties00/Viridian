package com.github.auties00.viridian;

/// The two databases Chromium keeps storage in: LevelDB, and SQLite, which it is moving IndexedDB and DOM Storage to.
public enum StorageBackend {
    /// LevelDB. IndexedDB keeps one database per storage bucket, holding all of the bucket's IndexedDB databases:
    /// `IndexedDB/<origin>.indexeddb.leveldb`, with large blobs in `<origin>.indexeddb.blob`, for an origin's
    /// first-party default bucket, and `WebStorage/<bucket id>/IndexedDB/indexeddb.leveldb` and `indexeddb.blob` for
    /// any other. DOM Storage keeps one per profile: `Local Storage/leveldb` and `Session Storage`.
    LEVELDB,
    /// SQLite. IndexedDB keeps one file per IndexedDB database, blobs included, in a directory per storage bucket:
    /// `IndexedDB/<origin>/` for an origin's first-party default bucket, `WebStorage/<bucket id>/IndexedDB/` for any
    /// other. DOM Storage keeps one file per profile: `LocalStorage` and `SessionStorage`. Chromium rolls it out for new
    /// IndexedDB databases from Chrome 156, and for DOM Storage through field trials.
    SQLITE
}
