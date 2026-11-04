package com.github.auties00.viridian;

import java.util.Objects;

/// The writes of one [ChromiumProfile#write(ProfileEditor)], made through the facades it gives.
///
/// The facades' writes are staged and committed together when the editor returns; their reads see the profile as it
/// was before the edit. An edit is valid only while its editor runs: a facade used after it throws
/// [IllegalStateException]. An edit is not safe for concurrent use.
public final class ProfileEdit {
    private final ChromiumProfile profile;
    private final ProfileTransaction transaction;

    ProfileEdit(ChromiumProfile profile, ProfileTransaction transaction) {
        this.profile = profile;
        this.transaction = transaction;
    }

    /// {@return the profile's IndexedDB, as [ChromiumProfile#indexedDb()] reads it, its writes part of this edit}
    public IndexedDbStorage indexedDb() {
        transaction.checkOpen();
        return new IndexedDbStorage(profile, null, transaction);
    }

    /// {@return the profile's IndexedDB on `backend`, as [ChromiumProfile#indexedDb(StorageBackend)] reads it, its
    /// writes part of this edit}
    public IndexedDbStorage indexedDb(StorageBackend backend) {
        transaction.checkOpen();
        return new IndexedDbStorage(profile, Objects.requireNonNull(backend, "backend"), transaction);
    }

    /// {@return the profile's `localStorage`, as [ChromiumProfile#localStorage()] reads it, its writes part of this
    /// edit}
    public LocalStorage localStorage() {
        transaction.checkOpen();
        return new LocalStorage(profile, null, transaction);
    }

    /// {@return the profile's `localStorage` on `backend`, as [ChromiumProfile#localStorage(StorageBackend)] reads
    /// it, its writes part of this edit}
    public LocalStorage localStorage(StorageBackend backend) {
        transaction.checkOpen();
        return new LocalStorage(profile, Objects.requireNonNull(backend, "backend"), transaction);
    }

    /// {@return the transaction the facades stage their writes in}
    ProfileTransaction transaction() {
        return transaction;
    }

    @Override
    public String toString() {
        return "ProfileEdit[" + profile.directory() + "]";
    }
}
