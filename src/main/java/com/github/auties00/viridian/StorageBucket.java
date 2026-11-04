package com.github.auties00.viridian;

import java.util.Objects;
import java.util.regex.Pattern;

/// A storage bucket: a storage key's default bucket, or one a page opened by name with `navigator.storageBuckets`.
///
/// IndexedDB keeps each bucket's databases apart. Chromium keeps an origin's first-party default bucket in the
/// profile's `IndexedDB` directory, and every other bucket in a directory of `WebStorage` named by the bucket's id,
/// which its quota database maps to the bucket's key and name.
///
/// {@snippet lang = java :
/// var origin = StorageOrigin.parse("https://app.example.com");
/// StorageBucket drafts = new StorageBucket(StorageKey.of(origin), "drafts");
/// StorageBucket embedded = StorageBucket.defaultOf(StorageKey.partitioned(origin, "https://news.example"));
/// }
///
/// @param key  the storage key the bucket belongs to
/// @param name the name: [#DEFAULT_NAME] for the default bucket, else 1 to 63 lowercase letters, digits, `_` and `-`,
///             not starting with `_` or `-`
/// @spec https://wicg.github.io/storage-buckets/ Storage Buckets API
public record StorageBucket(StorageKey key, String name) {
    /// The name Chromium gives every storage key's default bucket.
    public static final String DEFAULT_NAME = "_default";
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,62}");

    /// Creates a bucket.
    ///
    /// @throws IllegalArgumentException if the name is not one `navigator.storageBuckets.open` accepts, nor the default
    ///                                  bucket's
    public StorageBucket {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(name, "name");
        if (!name.equals(DEFAULT_NAME) && !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Not a bucket name: \"" + name + "\"");
        }
    }

    /// {@return the default bucket of `key`}
    public static StorageBucket defaultOf(StorageKey key) {
        return new StorageBucket(key, DEFAULT_NAME);
    }

    /// {@return the default bucket of `origin`'s first-party key, the bucket [StorageOrigin] names everywhere a method
    /// takes one}
    public static StorageBucket defaultOf(StorageOrigin origin) {
        return defaultOf(StorageKey.of(origin));
    }

    /// {@return whether this is its key's default bucket}
    public boolean isDefault() {
        return name.equals(DEFAULT_NAME);
    }

    @Override
    public String toString() {
        return key + (isDefault() ? "" : " bucket " + name);
    }
}
