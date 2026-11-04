package com.github.auties00.viridian.indexeddb;

import com.github.auties00.viridian.IdbKey;
import com.github.auties00.viridian.IdbObjectStore;
import com.github.auties00.viridian.ViridianException;
import com.github.auties00.viridian.value.JsValue;
import com.github.auties00.viridian.value.SerializedValue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.TreeSet;

/// What the IndexedDB writers of both backends derive from the databases they are given, as Chromium derives it when a
/// page stores the same: the order of a store's records, its key generator, the keys its indexes hold, and the checks
/// Chromium makes on them.
final class IdbWriterSupport {
    /// The id of the first index of an object store: one past `kMinimumIndexId`, the largest index id of a store
    /// without indexes, as Blink numbers the indexes a page creates.
    static final int FIRST_INDEX_ID = 31;

    // The first number of a key generator, kKeyGeneratorInitialNumber
    private static final long INITIAL_KEY_GENERATOR = 1;
    // The largest integer a JavaScript number holds exactly, past which a key generator does not count
    private static final double MAX_GENERATED_KEY = 0x1p53;

    private IdbWriterSupport() {
    }

    /// Fails if two of `databases` share a name.
    ///
    /// @throws IllegalArgumentException if they do
    static void requireDistinctNames(List<? extends DatabaseSource> databases) {
        var names = new HashSet<String>();
        for (var database : databases) {
            if (!names.add(database.name())) {
                throw new IllegalArgumentException("Two databases are named " + database.name());
            }
        }
    }

    /// Fails unless every blob and file `value` refers to has bytes to store, or is empty.
    ///
    /// @throws ViridianException with reason `MISSING` if one has none, naming the record and its store
    static void requireBytes(SerializedValue value, IdbKey key, IdbObjectStore store) throws ViridianException {
        for (var blob : value.blobs()) {
            if (!blob.hasBytes() && blob.size() != 0) {
                throw new ViridianException(ViridianException.Reason.MISSING, "A blob or file in the value of record " + key
                        + " of " + store.name() + " has no bytes to store");
            }
        }
    }

    /// The order and key generator of a store's records, checked and derived as they are written.
    static final class RecordOrder {
        private final IdbObjectStore store;
        private IdbKey previous;
        private long derivedGenerator = INITIAL_KEY_GENERATOR;
        private long count;

        RecordOrder(IdbObjectStore store) {
            this.store = store;
        }

        /// Notes the next record's key.
        ///
        /// @throws IllegalArgumentException if it is not past the previous one, as IndexedDB orders keys
        void note(IdbKey key) {
            if (previous != null && previous.compareTo(key) >= 0) {
                throw new IllegalArgumentException("The records of " + store.name() + " are not in key order, or two have the key "
                        + key + ", as IndexedDB orders keys");
            }
            previous = key;
            count++;
            if (store.autoIncrement() && key instanceof IdbKey.NumberKey number) {
                derivedGenerator = Math.max(derivedGenerator, 1 + (long) Math.floor(Math.min(number.value(), MAX_GENERATED_KEY)));
            }
        }

        /// {@return how many records were noted}
        long count() {
            return count;
        }

        /// {@return the number the store's key generator gives next: the one `database` records, or one past the
        /// integer part of its largest number key, at most 2^53 + 1, or 1 for a store without number keys or without
        /// a key generator}
        long keyGenerator(DatabaseSource database) {
            if (!store.autoIncrement()) {
                return INITIAL_KEY_GENERATOR;
            }
            return database.keyGenerator(store).orElse(derivedGenerator);
        }
    }

    /// The keys under which the indexes of an object store hold its records, checked as Chromium checks a unique
    /// index.
    static final class IndexKeys {
        private final IdbObjectStore store;
        // The keys each unique index holds so far, null for an index that is not unique
        private final List<TreeSet<IdbKey>> unique = new ArrayList<>();

        /// Starts collecting the index keys of `store`'s records.
        IndexKeys(IdbObjectStore store) {
            this.store = store;
            for (var index : store.indexes()) {
                unique.add(index.unique() ? new TreeSet<>() : null);
            }
        }

        /// {@return the keys under which the `position`th index of the store holds the record of `key` and `value`,
        /// sorted and distinct}
        ///
        /// @throws IllegalArgumentException if the index is unique and holds a record already under one of them
        List<IdbKey> of(int position, IdbKey key, JsValue value) {
            var index = store.indexes().get(position);
            var keys = IdbKeyPaths.indexKeys(key, value, index, store);
            var seen = unique.get(position);
            if (seen != null) {
                for (var indexKey : keys) {
                    if (!seen.add(indexKey)) {
                        throw new IllegalArgumentException("Two records of " + store.name() + " have the key " + indexKey
                                + " in the unique index " + index.name());
                    }
                }
            }
            return keys;
        }
    }
}
