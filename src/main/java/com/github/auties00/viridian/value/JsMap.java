package com.github.auties00.viridian.value;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/// A JavaScript `Map`: its entries, in insertion order.
///
/// Keys may be any value, objects included, so the entries are a list rather than a Java map: JavaScript compares
/// object keys by identity and primitive keys by value.
public final class JsMap implements JsValue {
    private final ArrayList<Map.Entry<JsValue, JsValue>> entries;

    JsMap() {
        this.entries = new ArrayList<>();
    }

    void put(JsValue key, JsValue value) {
        entries.add(Map.entry(key, value));
    }

    /// {@return the entries, in insertion order, as an unmodifiable view}
    public List<Map.Entry<JsValue, JsValue>> entries() {
        return Collections.unmodifiableList(entries);
    }

    /// {@return how many entries the map has}
    public int size() {
        return entries.size();
    }

    /// {@return the map in a JavaScript-like notation, `[Circular]` where it contains itself}
    @Override
    public String toString() {
        return JsRendering.render(this);
    }
}
