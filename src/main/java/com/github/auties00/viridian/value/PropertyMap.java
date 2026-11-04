package com.github.auties00.viridian.value;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeMap;

/// The own properties of an object in JavaScript's enumeration order: array-index keys ascending, then the other keys
/// in the order they were added.
///
/// @spec https://tc39.es/ecma262/#sec-ordinaryownpropertykeys OrdinaryOwnPropertyKeys
final class PropertyMap extends AbstractMap<String, JsValue> {
    private final TreeMap<Long, JsValue> indexed;
    private final LinkedHashMap<String, JsValue> named;

    PropertyMap() {
        this.indexed = new TreeMap<>();
        this.named = new LinkedHashMap<>();
    }

    /// {@return the properties whose keys are array indexes, by index, as a live view}
    NavigableMap<Long, JsValue> indexed() {
        return indexed;
    }

    /// {@return the other properties, in the order they were added, as a live view}
    Map<String, JsValue> named() {
        return named;
    }

    @Override
    public JsValue put(String key, JsValue value) {
        var index = JsNumbers.arrayIndex(key);
        return index >= 0 ? indexed.put(index, value) : named.put(key, value);
    }

    @Override
    public JsValue get(Object key) {
        if (!(key instanceof String name)) {
            return null;
        }
        var index = JsNumbers.arrayIndex(name);
        return index >= 0 ? indexed.get(index) : named.get(name);
    }

    @Override
    public boolean containsKey(Object key) {
        if (!(key instanceof String name)) {
            return false;
        }
        var index = JsNumbers.arrayIndex(name);
        return index >= 0 ? indexed.containsKey(index) : named.containsKey(name);
    }

    @Override
    public JsValue remove(Object key) {
        if (!(key instanceof String name)) {
            return null;
        }
        var index = JsNumbers.arrayIndex(name);
        return index >= 0 ? indexed.remove(index) : named.remove(name);
    }

    @Override
    public int size() {
        return indexed.size() + named.size();
    }

    @Override
    public Set<Entry<String, JsValue>> entrySet() {
        return new AbstractSet<>() {
            @Override
            public Iterator<Entry<String, JsValue>> iterator() {
                var indexes = indexed.entrySet().iterator();
                var names = named.entrySet().iterator();
                return new Iterator<>() {
                    @Override
                    public boolean hasNext() {
                        return indexes.hasNext() || names.hasNext();
                    }

                    @Override
                    public Entry<String, JsValue> next() {
                        if (indexes.hasNext()) {
                            var entry = indexes.next();
                            return Map.entry(Long.toString(entry.getKey()), entry.getValue());
                        }
                        if (names.hasNext()) {
                            return names.next();
                        }
                        throw new NoSuchElementException();
                    }
                };
            }

            @Override
            public int size() {
                return PropertyMap.this.size();
            }
        };
    }
}
