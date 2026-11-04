package com.github.auties00.viridian;

import com.github.auties00.viridian.indexeddb.DatabaseSource;
import com.github.auties00.viridian.indexeddb.IdbKeyPaths;
import com.github.auties00.viridian.io.ValueAccess;
import com.github.auties00.viridian.value.JsValue;
import com.github.auties00.viridian.value.SerializedValue;
import com.github.auties00.viridian.value.StructuredClone;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.SortedMap;
import java.util.TreeMap;

/// A whole IndexedDB database as a value: its name, version, object stores, key generators and records.
///
/// {@snippet lang = java :
/// IdbObjectStore people = IdbObjectStore.builder("people").keyPath("id").build();
/// IdbObjectStore settings = IdbObjectStore.of("settings");
/// IdbDatabase app = IdbDatabase.builder("app", 1)
///         .put(people, JsObject.builder().put("id", 1).put("name", "Alice").build())   // key 1, from the value
///         .put(settings, IdbKey.of("theme"), JsValue.of("dark"))
///         .build();
/// profile.indexedDb().put(origin, app);
/// }
///
/// A database is immutable; [#builder(String, long)] builds one, and [#toBuilder()] starts from one. The builder
/// applies IndexedDB's rules at each call, as a page's `put` meets them: it derives an in-line key from the value,
/// generates a key with the store's key generator where the value has none, refuses a record a unique index already
/// holds a record under, and refuses a value that cannot be stored. Its failures name the store and the key.
///
/// Values are those a page reads: in a store with a key generator and a key path, a record's generated key is in its
/// value, as Blink injects it on reading. The value stored lacks it, as Chromium stores it, so a database read from a
/// profile and written to another is stored as it was.
///
/// A blob or file in a value read from a profile reads its bytes from that profile when the database is written,
/// so the profile must stay in place until then.
public final class IdbDatabase {
    // The largest version and key generator number IndexedDB allows: 2^53 - 1 and 2^53 + 1, past the last key
    private static final long MAX_VERSION = (1L << 53) - 1;
    private static final long MAX_GENERATOR = (1L << 53) + 1;

    private final String name;
    private final long version;
    private final List<IdbObjectStore> objectStores;
    private final Map<String, StoreRecords> stores;

    private IdbDatabase(String name, long version, List<IdbObjectStore> objectStores, Map<String, StoreRecords> stores) {
        this.name = name;
        this.version = version;
        this.objectStores = List.copyOf(objectStores);
        this.stores = stores;
    }

    /// {@return a builder of the database `name` of version `version`, with no object store until given one}
    ///
    /// @throws IllegalArgumentException if `version` is not from 1 to 2^53 - 1
    public static Builder builder(String name, long version) {
        return new Builder(Objects.requireNonNull(name, "name"), checkVersion(version));
    }

    /// {@return the database `source` holds, read whole}
    ///
    /// @throws IOException if the source cannot be read
    static IdbDatabase of(DatabaseSource source) throws IOException {
        var stores = new LinkedHashMap<String, StoreRecords>();
        for (var store : source.objectStores()) {
            var records = new StoreRecords(store);
            try (var cursor = source.records(store)) {
                while (cursor.next()) {
                    var stored = cursor.value();
                    records.values.put(cursor.key(), stored);
                }
            }
            records.injectAll();
            if (store.autoIncrement()) {
                records.generator = source.keyGenerator(store).orElse(records.derivedGenerator());
            }
            stores.put(store.name(), records);
        }
        return new IdbDatabase(source.name(), source.version(), source.objectStores(), stores);
    }

    /// {@return the database's name}
    public String name() {
        return name;
    }

    /// {@return the database's version}
    public long version() {
        return version;
    }

    /// {@return the object stores, in the order they were first given}
    public List<IdbObjectStore> objectStores() {
        return objectStores;
    }

    /// {@return the object store named `name`, or an empty optional if there is none}
    public Optional<IdbObjectStore> objectStore(String name) {
        var records = stores.get(Objects.requireNonNull(name, "name"));
        return records == null ? Optional.empty() : Optional.of(records.definition);
    }

    /// {@return the records of the object store `store`, by key, their values as a page reads them}
    ///
    /// @throws IllegalArgumentException if the database has no object store named `store`
    public SortedMap<IdbKey, JsValue> records(String store) {
        return Collections.unmodifiableSortedMap(requireStore(store).values);
    }

    /// {@return the records of `store`, by key, their values as a page reads them}
    ///
    /// @throws IllegalArgumentException if the database has no such object store, or defines it differently
    public SortedMap<IdbKey, JsValue> records(IdbObjectStore store) {
        var records = requireStore(store.name());
        if (!records.definition.equals(store)) {
            throw new IllegalArgumentException("The object store " + store.name() + " of " + name + " is " + records.definition + ", not " + store);
        }
        return Collections.unmodifiableSortedMap(records.values);
    }

    /// {@return the number the key generator of the object store `store` gives the next record added without a key,
    /// or an empty optional if the store has no key generator}
    ///
    /// @throws IllegalArgumentException if the database has no object store named `store`
    public OptionalLong keyGenerator(String store) {
        var records = requireStore(store);
        return records.definition.autoIncrement() ? OptionalLong.of(records.generator) : OptionalLong.empty();
    }

    /// {@return a builder holding this database, to change; the database itself stays as it is}
    public Builder toBuilder() {
        var builder = new Builder(name, version);
        for (var store : objectStores) {
            builder.stores.put(store.name(), stores.get(store.name()).copy());
        }
        return builder;
    }

    /// {@return the database as the writers read it: its records' values as stored}
    DatabaseSource source() {
        return new DatabaseSource() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public long version() {
                return version;
            }

            @Override
            public List<IdbObjectStore> objectStores() {
                return objectStores;
            }

            @Override
            public OptionalLong keyGenerator(IdbObjectStore store) {
                return IdbDatabase.this.keyGenerator(store.name());
            }

            @Override
            public RecordCursor records(IdbObjectStore store) {
                var records = stores.get(store.name());
                Iterator<Map.Entry<IdbKey, JsValue>> iterator = records.values.entrySet().iterator();
                return new RecordCursor() {
                    private Map.Entry<IdbKey, JsValue> current;

                    @Override
                    public boolean next() {
                        current = iterator.hasNext() ? iterator.next() : null;
                        return current != null;
                    }

                    @Override
                    public IdbKey key() {
                        return current.getKey();
                    }

                    @Override
                    public JsValue value() {
                        return records.stored(current.getKey(), current.getValue());
                    }

                    @Override
                    public void close() {
                    }
                };
            }
        };
    }

    private StoreRecords requireStore(String store) {
        var records = stores.get(Objects.requireNonNull(store, "store"));
        if (records == null) {
            throw new IllegalArgumentException("The database " + name + " has no object store " + store);
        }
        return records;
    }

    private static long checkVersion(long version) {
        if (version < 1 || version > MAX_VERSION) {
            throw new IllegalArgumentException("A database's version is from 1 to 2^53 - 1, not " + version);
        }
        return version;
    }

    @Override
    public String toString() {
        var counts = new ArrayList<String>();
        for (var store : objectStores) {
            counts.add(store.name() + "=" + stores.get(store.name()).values.size());
        }
        return "IdbDatabase[" + name + " v" + version + ", " + counts + "]";
    }

    /// The records of one object store: their values as a page reads them, by key, those stored otherwise, and the
    /// number its key generator gives next.
    private static final class StoreRecords {
        private final IdbObjectStore definition;
        private final TreeMap<IdbKey, JsValue> values;
        // The values stored without the generated key the page reads in them, by key
        private final HashMap<IdbKey, JsValue> storedWithoutKey;
        private long generator;

        StoreRecords(IdbObjectStore definition) {
            this(definition, new TreeMap<>(), new HashMap<>(), 1);
        }

        private StoreRecords(IdbObjectStore definition, TreeMap<IdbKey, JsValue> values, HashMap<IdbKey, JsValue> storedWithoutKey,
                             long generator) {
            this.definition = definition;
            this.values = values;
            this.storedWithoutKey = storedWithoutKey;
            this.generator = generator;
        }

        StoreRecords copy() {
            return new StoreRecords(definition, new TreeMap<>(values), new HashMap<>(storedWithoutKey), generator);
        }

        /// {@return the value of the record of `key` as stored}
        JsValue stored(IdbKey key, JsValue value) {
            var stored = storedWithoutKey.get(key);
            return stored == null ? value : stored;
        }

        /// {@return one past the integer part of the largest number key, at most 2^53 + 1, or 1}
        long derivedGenerator() {
            var largest = 1L;
            // Every number key orders before the earliest date
            var numbers = values.headMap(new IdbKey.DateKey(-8.64e15), false);
            if (!numbers.isEmpty() && numbers.lastKey() instanceof IdbKey.NumberKey number) {
                largest = Math.max(largest, 1 + (long) Math.floor(Math.min(number.value(), 0x1p53)));
            }
            return largest;
        }

        /// Replaces every value read as stored with the value a page reads, its key injected, where the store
        /// injects keys.
        void injectAll() throws IOException {
            if (!(definition.autoIncrement() && definition.keyPath().orElse(null) instanceof IdbKeyPath.Single path)) {
                return;
            }
            for (var entry : values.entrySet()) {
                storedWithoutKey.put(entry.getKey(), entry.getValue());
                entry.setValue(injected(entry.getValue(), path, entry.getKey()));
            }
        }

        /// {@return a copy of `value` with `key` injected at `path`, as Blink injects it into a value it reads}
        static JsValue injected(JsValue value, IdbKeyPath.Single path, IdbKey key) throws IOException {
            var copy = StructuredClone.deserialize(StructuredClone.serialize(value));
            ValueAccess.get().inject(copy, path.identifiers(), IdbKeyPaths.toValue(key));
            return copy;
        }
    }

    /// A builder of an [IdbDatabase], which applies IndexedDB's rules at each call.
    ///
    /// A store is declared by the first call that names it; a later call that names a store of the same name defined
    /// differently fails. [#build()] builds the database, the same instance on every call, after which the builder
    /// refuses every change. A builder is not thread-safe.
    public static final class Builder {
        private final String name;
        private long version;
        private final LinkedHashMap<String, StoreRecords> stores = new LinkedHashMap<>();
        // The keys each unique index holds, with the record each belongs to, by store and index; made when first needed
        private final HashMap<String, Map<String, TreeMap<IdbKey, IdbKey>>> uniqueKeys = new HashMap<>();
        private IdbDatabase built;

        private Builder(String name, long version) {
            this.name = name;
            this.version = version;
        }

        /// Sets the version.
        ///
        /// @return this builder
        /// @throws IllegalArgumentException if `version` is not from 1 to 2^53 - 1
        /// @throws IllegalStateException    if the builder already built its database
        public Builder version(long version) {
            checkOpen();
            this.version = checkVersion(version);
            return this;
        }

        /// Declares `store`, empty until records are put in it.
        ///
        /// @return this builder
        /// @throws IllegalArgumentException if the database has a store of that name defined differently
        /// @throws IllegalStateException    if the builder already built its database
        public Builder objectStore(IdbObjectStore store) {
            checkOpen();
            declare(store);
            return this;
        }

        /// Puts `value` in `store`, under the key its key path gives it, or the next key of the store's key generator.
        ///
        /// In a store with in-line keys, the key is the one the key path finds in the value, or, if the value has none
        /// and the store has a key generator, a generated key, which is then injected into the value at the key path,
        /// as a page reads it back. In a store with out-of-line keys, the store must have a key generator, which gives
        /// the key. A record already under that key is replaced, as `put` replaces it. A number key past the key
        /// generator moves the generator past it.
        ///
        /// @return this builder
        /// @throws IllegalArgumentException if the database has a store of that name defined differently, the value's
        ///                                  key is not a valid key, the store has no key generator to give a missing
        ///                                  key, or its generator is past 2^53, a key cannot be injected into the
        ///                                  value, a unique index holds another record under one of the value's index
        ///                                  keys, or the value cannot be stored: it holds a File System Access handle,
        ///                                  a blob without bytes, or is nested more than 1000 levels deep
        /// @throws IllegalStateException    if the builder already built its database
        public Builder put(IdbObjectStore store, JsValue value) {
            checkOpen();
            Objects.requireNonNull(value, "value");
            var records = declare(store);
            var keyPath = store.keyPath().orElse(null);
            if (keyPath == null) {
                if (!store.autoIncrement()) {
                    throw new IllegalArgumentException("The store " + store.name() + " has out-of-line keys and no key generator: give the key");
                }
                checkStorable(store, null, value);
                return insert(records, generate(records), value, value);
            }
            var evaluation = IdbKeyPaths.evaluate(value, keyPath);
            if (evaluation.present()) {
                if (evaluation.key() == null) {
                    throw new IllegalArgumentException("The value put in " + store.name() + " holds no valid key at " + keyPath);
                }
                checkStorable(store, evaluation.key(), value);
                advance(records, evaluation.key());
                return insert(records, evaluation.key(), value, value);
            }
            if (!store.autoIncrement() || !(keyPath instanceof IdbKeyPath.Single single)) {
                throw new IllegalArgumentException("The value put in " + store.name() + " has no key at " + keyPath + ", and the store has no key generator");
            }
            if (!IdbKeyPaths.canInject(value, single)) {
                throw new IllegalArgumentException("A key cannot be injected at " + keyPath + " into the value put in " + store.name());
            }
            checkStorable(store, null, value);
            var key = generate(records);
            JsValue exposed;
            try {
                exposed = StoreRecords.injected(value, single, key);
            } catch (IOException exception) {
                throw new IllegalArgumentException("The value put in " + store.name() + " does not read back", exception);
            }
            return insert(records, key, exposed, value);
        }

        /// Puts `value` in `store` under `key`, replacing a record already under it, as `put` replaces it.
        ///
        /// @return this builder
        /// @throws IllegalArgumentException if the database has a store of that name defined differently, the store
        ///                                  has in-line keys, which come from the value, a unique index holds another
        ///                                  record under one of the value's index keys, or the value cannot be stored
        /// @throws IllegalStateException    if the builder already built its database
        public Builder put(IdbObjectStore store, IdbKey key, JsValue value) {
            checkOpen();
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
            var records = declare(store);
            if (store.keyPath().isPresent()) {
                throw new IllegalArgumentException("The store " + store.name() + " has in-line keys, which come from the value: put(store, value)");
            }
            checkStorable(store, key, value);
            advance(records, key);
            return insert(records, key, value, value);
        }

        /// Puts every value of `values` in `store`, as [#put(IdbObjectStore, JsValue)] puts each, in order.
        ///
        /// @return this builder
        /// @throws IllegalArgumentException as [#put(IdbObjectStore, JsValue)] does; the values before the one it names
        ///                                  are put
        /// @throws IllegalStateException    if the builder already built its database
        public Builder putAll(IdbObjectStore store, Iterable<? extends JsValue> values) {
            for (var value : values) {
                put(store, value);
            }
            return this;
        }

        /// Puts every record of `records` in `store`, as [#put(IdbObjectStore, IdbKey, JsValue)] puts each.
        ///
        /// @return this builder
        /// @throws IllegalArgumentException as [#put(IdbObjectStore, IdbKey, JsValue)] does
        /// @throws IllegalStateException    if the builder already built its database
        public Builder putAll(IdbObjectStore store, Map<IdbKey, ? extends JsValue> records) {
            for (var record : records.entrySet()) {
                put(store, record.getKey(), record.getValue());
            }
            return this;
        }

        /// Deletes the record of `store` under `key`, if there is one.
        ///
        /// @return this builder
        /// @throws IllegalArgumentException if the database has a store of that name defined differently
        /// @throws IllegalStateException    if the builder already built its database
        public Builder delete(IdbObjectStore store, IdbKey key) {
            checkOpen();
            Objects.requireNonNull(key, "key");
            var records = declare(store);
            var old = records.values.remove(key);
            if (old != null) {
                forgetIndexKeys(records, key, records.stored(key, old));
                records.storedWithoutKey.remove(key);
            }
            return this;
        }

        /// Sets the number the key generator of `store` gives the next record added without a key.
        ///
        /// The generator otherwise starts at 1 and moves past every number key put in the store, as a page's puts
        /// move it; a database read from a profile keeps the number its store had, which may be past every key, such
        /// as when the page deleted its last records.
        ///
        /// @return this builder
        /// @throws IllegalArgumentException if the store has no key generator, or `next` is not past the integer part
        ///                                  of the store's largest number key or is more than 2^53 + 1
        /// @throws IllegalStateException    if the builder already built its database
        public Builder keyGenerator(IdbObjectStore store, long next) {
            checkOpen();
            var records = declare(store);
            if (!store.autoIncrement()) {
                throw new IllegalArgumentException("The store " + store.name() + " has no key generator");
            }
            if (next < records.derivedGenerator() || next > MAX_GENERATOR) {
                throw new IllegalArgumentException("The key generator of " + store.name() + " cannot give " + next + " next: its keys need "
                        + records.derivedGenerator() + " to " + MAX_GENERATOR);
            }
            records.generator = next;
            return this;
        }

        /// Builds the database.
        ///
        /// @return the database, the same instance on every call
        public IdbDatabase build() {
            if (built == null) {
                var definitions = new ArrayList<IdbObjectStore>();
                for (var records : stores.values()) {
                    definitions.add(records.definition);
                }
                built = new IdbDatabase(name, version, definitions, stores);
            }
            return built;
        }

        private void checkOpen() {
            if (built != null) {
                throw new IllegalStateException("The builder already built its database, which cannot change");
            }
        }

        /// {@return the records of `store`, declaring it if the database has no store of its name}
        private StoreRecords declare(IdbObjectStore store) {
            Objects.requireNonNull(store, "store");
            var records = stores.get(store.name());
            if (records == null) {
                records = new StoreRecords(store);
                stores.put(store.name(), records);
            } else if (!records.definition.equals(store)) {
                throw new IllegalArgumentException("The database " + name + " has the object store " + records.definition + ", not " + store);
            }
            return records;
        }

        /// {@return the next key of the store's key generator, which then moves on}
        private static IdbKey generate(StoreRecords records) {
            if (records.generator > 1L << 53) {
                throw new IllegalArgumentException("The key generator of " + records.definition.name() + " is past 2^53 and gives no more keys");
            }
            return new IdbKey.NumberKey(records.generator++);
        }

        /// Moves the store's key generator past `key`, if it is a number and the store has a generator.
        private static void advance(StoreRecords records, IdbKey key) {
            if (records.definition.autoIncrement() && key instanceof IdbKey.NumberKey number) {
                var next = 1 + (long) Math.floor(Math.min(number.value(), 0x1p53));
                if (next > records.generator) {
                    records.generator = next;
                }
            }
        }

        /// Fails unless `value` can be stored: it serializes, and its blobs have bytes.
        private static void checkStorable(IdbObjectStore store, IdbKey key, JsValue value) {
            var where = key == null ? "a value put in " + store.name() : "the value of " + key + " in " + store.name();
            SerializedValue serialized;
            try {
                serialized = StructuredClone.serialize(value);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Cannot store " + where + ": " + exception.getMessage(), exception);
            }
            for (var blob : serialized.blobs()) {
                if (!blob.hasBytes() && blob.size() != 0) {
                    throw new IllegalArgumentException("Cannot store " + where + ": " + blob + " has no bytes to store");
                }
            }
        }

        /// Puts the record of `key`, whose value is `value` as a page reads it and `stored` as stored, after checking
        /// its unique index keys.
        private Builder insert(StoreRecords records, IdbKey key, JsValue value, JsValue stored) {
            var old = records.values.get(key);
            if (old != null) {
                forgetIndexKeys(records, key, records.stored(key, old));
            }
            var claimed = new ArrayList<Map.Entry<TreeMap<IdbKey, IdbKey>, IdbKey>>();
            for (var index : records.definition.indexes()) {
                if (!index.unique()) {
                    continue;
                }
                var held = uniqueKeys(records, index);
                for (var indexKey : IdbKeyPaths.indexKeys(key, stored, index, records.definition)) {
                    var owner = held.get(indexKey);
                    if (owner != null && !owner.equals(key)) {
                        for (var entry : claimed) {
                            entry.getKey().remove(entry.getValue());
                        }
                        if (old != null) {
                            rememberIndexKeys(records, key, records.stored(key, old));
                        }
                        throw new IllegalArgumentException("The unique index " + index.name() + " of " + records.definition.name()
                                + " holds the record " + owner + " under " + indexKey + ", which the record " + key + " has too");
                    }
                    if (owner == null) {
                        held.put(indexKey, key);
                        claimed.add(Map.entry(held, indexKey));
                    }
                }
            }
            records.values.put(key, value);
            if (stored != value) {
                records.storedWithoutKey.put(key, stored);
            } else {
                records.storedWithoutKey.remove(key);
            }
            return this;
        }

        /// {@return the keys `index` of the store holds, made from its records when first needed}
        private TreeMap<IdbKey, IdbKey> uniqueKeys(StoreRecords records, IdbIndex index) {
            var byIndex = uniqueKeys.computeIfAbsent(records.definition.name(), _ -> new HashMap<>());
            var held = byIndex.get(index.name());
            if (held == null) {
                held = new TreeMap<>();
                for (var record : records.values.entrySet()) {
                    var stored = records.stored(record.getKey(), record.getValue());
                    for (var indexKey : IdbKeyPaths.indexKeys(record.getKey(), stored, index, records.definition)) {
                        held.put(indexKey, record.getKey());
                    }
                }
                byIndex.put(index.name(), held);
            }
            return held;
        }

        private void forgetIndexKeys(StoreRecords records, IdbKey key, JsValue stored) {
            var byIndex = uniqueKeys.get(records.definition.name());
            if (byIndex == null) {
                return;
            }
            for (var index : records.definition.indexes()) {
                var held = byIndex.get(index.name());
                if (held != null) {
                    for (var indexKey : IdbKeyPaths.indexKeys(key, stored, index, records.definition)) {
                        held.remove(indexKey, key);
                    }
                }
            }
        }

        private void rememberIndexKeys(StoreRecords records, IdbKey key, JsValue stored) {
            var byIndex = uniqueKeys.get(records.definition.name());
            if (byIndex == null) {
                return;
            }
            for (var index : records.definition.indexes()) {
                var held = byIndex.get(index.name());
                if (held != null) {
                    for (var indexKey : IdbKeyPaths.indexKeys(key, stored, index, records.definition)) {
                        held.put(indexKey, key);
                    }
                }
            }
        }
    }
}
