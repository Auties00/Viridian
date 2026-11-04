package com.github.auties00.viridian;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/// An object store of an IndexedDB database: its name, how its keys are made, and its indexes.
///
/// @param id            the store's id within its database
/// @param name          the name
/// @param keyPath       where in a value its key is, or `null` if keys are given beside the values
/// @param autoIncrement whether keys are generated
/// @param indexes       the indexes, by id
public record IdbObjectStore(long id, String name, IdbKeyPath keyPath, boolean autoIncrement, List<IdbIndex> indexes) {
    /// Creates an object store, copying `indexes`.
    public IdbObjectStore {
        Objects.requireNonNull(name, "name");
        indexes = List.copyOf(indexes);
    }

    /// {@return where in a value its key is, or an empty optional if keys are given beside the values}
    public Optional<IdbKeyPath> inlineKeyPath() {
        return Optional.ofNullable(keyPath);
    }

    /// {@return the index named `name`, or an empty optional if there is none}
    public Optional<IdbIndex> index(String name) {
        for (var index : indexes) {
            if (index.name().equals(name)) {
                return Optional.of(index);
            }
        }
        return Optional.empty();
    }
}
