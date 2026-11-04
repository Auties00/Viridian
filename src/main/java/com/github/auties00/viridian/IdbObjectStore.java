package com.github.auties00.viridian;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/// The definition of an object store: its name, how its keys are made, and its indexes.
///
/// A store has in-line keys when it has a key path, its records' keys then being in their values; out-of-line keys
/// otherwise, each record's key given beside its value. A store with a key generator, `autoIncrement`, numbers the
/// records it is given without a key. Two definitions are equal when their names, key paths, generators and indexes
/// are, the indexes in the same order.
///
/// {@snippet lang = java :
/// IdbObjectStore settings = IdbObjectStore.of("settings");                                   // out-of-line keys
/// IdbObjectStore people = IdbObjectStore.builder("people").keyPath("id").build();             // in-line keys
/// IdbObjectStore notes = IdbObjectStore.builder("notes").keyPath("id").autoIncrement().build();   // generated, in-line
/// IdbObjectStore contacts = IdbObjectStore.builder("contacts").keyPath("id")
///         .index("byEmail", "email", IdbIndex.Option.UNIQUE)
///         .index("byTag", "tags", IdbIndex.Option.MULTI_ENTRY)
///         .build();
/// }
public final class IdbObjectStore {
    private final String name;
    private final IdbKeyPath keyPath;
    private final boolean autoIncrement;
    private final List<IdbIndex> indexes;

    private IdbObjectStore(String name, IdbKeyPath keyPath, boolean autoIncrement, List<IdbIndex> indexes) {
        this.name = name;
        this.keyPath = keyPath;
        this.autoIncrement = autoIncrement;
        this.indexes = List.copyOf(indexes);
    }

    /// {@return a store named `name` with out-of-line keys, no key generator and no index}
    public static IdbObjectStore of(String name) {
        return builder(name).build();
    }

    /// {@return a builder of a store named `name`, with out-of-line keys, no key generator and no index until told}
    public static Builder builder(String name) {
        return new Builder(Objects.requireNonNull(name, "name"));
    }

    /// {@return the name}
    public String name() {
        return name;
    }

    /// {@return where in a value its key is, or an empty optional if keys are given beside the values}
    public Optional<IdbKeyPath> keyPath() {
        return Optional.ofNullable(keyPath);
    }

    /// {@return whether the store has a key generator, which numbers the records it is given without a key}
    public boolean autoIncrement() {
        return autoIncrement;
    }

    /// {@return the indexes, in the order they were created}
    public List<IdbIndex> indexes() {
        return indexes;
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

    /// {@return a builder holding this definition, to change}
    public Builder toBuilder() {
        var builder = new Builder(name);
        builder.keyPath = keyPath;
        builder.autoIncrement = autoIncrement;
        builder.indexes.addAll(indexes);
        return builder;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof IdbObjectStore store && name.equals(store.name) && Objects.equals(keyPath, store.keyPath)
                && autoIncrement == store.autoIncrement && indexes.equals(store.indexes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, keyPath, autoIncrement, indexes);
    }

    @Override
    public String toString() {
        return "IdbObjectStore[" + name + (keyPath == null ? "" : ", keyPath " + keyPath) + (autoIncrement ? ", autoIncrement" : "")
                + (indexes.isEmpty() ? "" : ", " + indexes) + "]";
    }

    /// A builder of an [IdbObjectStore]. It may build any number of definitions, each from what it holds then.
    public static final class Builder {
        private final String name;
        private final List<IdbIndex> indexes = new ArrayList<>();
        private IdbKeyPath keyPath;
        private boolean autoIncrement;

        private Builder(String name) {
            this.name = name;
        }

        /// Makes the store's keys in-line, at `path`.
        ///
        /// @return this builder
        /// @throws IllegalArgumentException if `path` is not a valid key path
        public Builder keyPath(String path) {
            return keyPath(IdbKeyPath.of(path));
        }

        /// Makes the store's keys in-line, at `path`.
        ///
        /// @return this builder
        public Builder keyPath(IdbKeyPath path) {
            this.keyPath = Objects.requireNonNull(path, "path");
            return this;
        }

        /// Gives the store a key generator.
        ///
        /// @return this builder
        public Builder autoIncrement() {
            this.autoIncrement = true;
            return this;
        }

        /// Adds an index.
        ///
        /// @return this builder
        /// @throws IllegalArgumentException if `keyPath` is not a valid key path
        public Builder index(String name, String keyPath, IdbIndex.Option... options) {
            return index(name, IdbKeyPath.of(keyPath), options);
        }

        /// Adds an index.
        ///
        /// @return this builder
        /// @throws IllegalArgumentException if the index is multi-entry with a compound key path
        public Builder index(String name, IdbKeyPath keyPath, IdbIndex.Option... options) {
            var set = new HashSet<>(Arrays.asList(options));
            return index(new IdbIndex(name, keyPath, set.contains(IdbIndex.Option.UNIQUE), set.contains(IdbIndex.Option.MULTI_ENTRY)));
        }

        /// Adds `index`.
        ///
        /// @return this builder
        public Builder index(IdbIndex index) {
            indexes.add(Objects.requireNonNull(index, "index"));
            return this;
        }

        /// {@return the definition}
        ///
        /// @throws IllegalArgumentException if the store has a key generator and a compound or empty key path, or two
        ///                                  indexes share a name, as `createObjectStore` and `createIndex` refuse them
        public IdbObjectStore build() {
            if (autoIncrement && (keyPath instanceof IdbKeyPath.Compound || keyPath instanceof IdbKeyPath.Single(var path) && path.isEmpty())) {
                throw new IllegalArgumentException("The store " + name + " cannot have a key generator and the key path " + keyPath);
            }
            var names = new HashSet<String>();
            for (var index : indexes) {
                if (!names.add(index.name())) {
                    throw new IllegalArgumentException("Two indexes of " + name + " are named " + index.name());
                }
            }
            return new IdbObjectStore(name, keyPath, autoIncrement, indexes);
        }
    }
}
