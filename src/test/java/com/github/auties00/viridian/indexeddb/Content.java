package com.github.auties00.viridian.indexeddb;

import com.github.auties00.viridian.IdbIndex;
import com.github.auties00.viridian.IdbKey;
import com.github.auties00.viridian.IdbKeyPath;
import com.github.auties00.viridian.IdbObjectStore;
import com.github.auties00.viridian.value.JsValue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalLong;

/// Databases for the writers' tests, given as plain records rather than built with the rules
/// [com.github.auties00.viridian.IdbDatabase] applies, so that a test can give a writer what a builder would refuse.
final class Content {
    private Content() {
    }

    /// {@return a store of `records`, which a database gives the writers in key order}
    static Store store(String name, IdbKeyPath keyPath, boolean autoIncrement, List<IdbIndex> indexes, List<Record> records) {
        var builder = IdbObjectStore.builder(name);
        if (keyPath != null) {
            builder.keyPath(keyPath);
        }
        if (autoIncrement) {
            builder.autoIncrement();
        }
        indexes.forEach(builder::index);
        return new Store(builder.build(), records, null);
    }

    /// Writes each of `databases` to its SQLite file in `directory`.
    static void writeSqlite(Path directory, List<Database> databases, ValueSerializer serializer) throws IOException {
        if (!databases.isEmpty()) {
            java.nio.file.Files.createDirectories(directory);
        }
        for (var database : databases) {
            SqliteIndexedDbWriter.write(directory.resolve(SqliteIndexedDbWriter.fileName(database.name())), database, serializer);
        }
    }

    /// A record.
    record Record(IdbKey key, JsValue value) {
    }

    /// A store and its records, in any order, and the key generator it records, or `null` to derive it.
    record Store(IdbObjectStore definition, List<Record> records, Long keyGenerator) {
        Store withKeyGenerator(long next) {
            return new Store(definition, records, next);
        }
    }

    /// A database.
    record Database(String name, long version, List<Store> stores) implements DatabaseSource {
        @Override
        public List<IdbObjectStore> objectStores() {
            return stores.stream().map(Store::definition).toList();
        }

        @Override
        public OptionalLong keyGenerator(IdbObjectStore store) {
            var generator = find(store).keyGenerator();
            return generator == null ? OptionalLong.empty() : OptionalLong.of(generator);
        }

        @Override
        public RecordCursor records(IdbObjectStore store) {
            var sorted = new ArrayList<>(find(store).records());
            sorted.sort(Comparator.comparing(Record::key));
            var iterator = sorted.iterator();
            return new RecordCursor() {
                private Record current;

                @Override
                public boolean next() {
                    current = iterator.hasNext() ? iterator.next() : null;
                    return current != null;
                }

                @Override
                public IdbKey key() {
                    return current.key();
                }

                @Override
                public JsValue value() {
                    return current.value();
                }

                @Override
                public void close() {
                }
            };
        }

        private Store find(IdbObjectStore store) {
            for (var candidate : stores) {
                if (candidate.definition().equals(store)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("No store " + store.name());
        }
    }
}
