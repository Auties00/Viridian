package com.github.auties00.viridian.value;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/// A JavaScript `Set`: its values, in insertion order.
public final class JsSet implements JsValue {
    private final ArrayList<JsValue> values;

    JsSet() {
        this.values = new ArrayList<>();
    }

    void add(JsValue value) {
        values.add(value);
    }

    /// {@return the values, in insertion order, as an unmodifiable view}
    public List<JsValue> values() {
        return Collections.unmodifiableList(values);
    }

    /// {@return how many values the set has}
    public int size() {
        return values.size();
    }

    /// {@return the set in a JavaScript-like notation, `[Circular]` where it contains itself}
    @Override
    public String toString() {
        return JsRendering.render(this);
    }
}
