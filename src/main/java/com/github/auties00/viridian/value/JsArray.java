package com.github.auties00.viridian.value;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/// A JavaScript array: its length, its elements, and any other properties it has.
///
/// An array may have holes, indexes below its length that hold no element, which differ from elements that hold
/// `undefined`. A sparse array, one whose length far exceeds its elements, keeps only the elements it has.
public final class JsArray implements JsValue {
    // The longest array JavaScript allows: indexes run from 0 to 2^32 - 2
    static final long MAX_LENGTH = 0xFFFF_FFFFL;

    private final long length;
    // The elements of a dense array, null for a hole; null for a sparse array
    private final ArrayList<JsValue> dense;
    // The elements of a sparse array by index; null for a dense array
    private final TreeMap<Long, JsValue> sparse;
    private final LinkedHashMap<String, JsValue> properties;

    private JsArray(long length, boolean sparse) {
        this.length = length;
        this.dense = sparse ? null : new ArrayList<>((int) Math.min(length, 1024));
        if (this.dense != null) {
            for (var i = 0L; i < length; i++) {
                this.dense.add(null);
            }
        }
        this.sparse = sparse ? new TreeMap<>() : null;
        this.properties = new LinkedHashMap<>();
    }

    /// {@return an empty dense array of `length` holes}
    static JsArray dense(int length) {
        return new JsArray(length, false);
    }

    /// {@return an empty sparse array of `length`, all holes}
    static JsArray sparse(long length) {
        return new JsArray(length, true);
    }

    /// {@return an array of `elements`, in order, where `null` is a hole}
    public static JsArray of(List<? extends JsValue> elements) {
        var array = new JsArray(elements.size(), false);
        for (var i = 0; i < elements.size(); i++) {
            array.dense.set(i, elements.get(i));
        }
        return array;
    }

    /// Stores `value` at `index`, which is below the length.
    void set(long index, JsValue value) {
        if (dense != null) {
            dense.set((int) index, value);
        } else {
            sparse.put(index, value);
        }
    }

    /// Stores a property that is not an element.
    void putProperty(String key, JsValue value) {
        properties.put(key, value);
    }

    /// {@return the length: one more than the highest index, holes included}
    public long length() {
        return length;
    }

    /// {@return whether the array keeps its elements by index rather than in sequence}
    public boolean isSparse() {
        return sparse != null;
    }

    /// {@return the element at `index`, or an empty optional for a hole or an index outside the array}
    public Optional<JsValue> get(long index) {
        if (index < 0 || index >= length) {
            return Optional.empty();
        }
        return Optional.ofNullable(dense != null ? dense.get((int) index) : sparse.get(index));
    }

    /// {@return the elements from index 0, as an unmodifiable list where `null` is a hole}
    ///
    /// @throws IllegalStateException if the array is longer than a list can be, `Integer.MAX_VALUE` elements
    public List<JsValue> elements() {
        if (dense != null) {
            return Collections.unmodifiableList(dense);
        }
        if (length > Integer.MAX_VALUE) {
            throw new IllegalStateException("The array is " + length + " elements long, more than a list holds: use get(long)");
        }
        var size = (int) length;
        return new AbstractList<>() {
            @Override
            public JsValue get(int index) {
                Objects.checkIndex(index, size);
                return sparse.get((long) index);
            }

            @Override
            public int size() {
                return size;
            }
        };
    }

    /// {@return the elements the array has, by index, holes left out, as an unmodifiable view}
    public Map<Long, JsValue> presentElements() {
        if (sparse != null) {
            return Collections.unmodifiableMap(sparse);
        }
        var present = new TreeMap<Long, JsValue>();
        for (var i = 0; i < dense.size(); i++) {
            if (dense.get(i) != null) {
                present.put((long) i, dense.get(i));
            }
        }
        return Collections.unmodifiableMap(present);
    }

    /// {@return the properties that are not elements, in order, as an unmodifiable view}
    public Map<String, JsValue> properties() {
        return Collections.unmodifiableMap(properties);
    }

    /// {@return the array in a JavaScript-like notation, `[Circular]` where it contains itself}
    @Override
    public String toString() {
        return JsRendering.render(this);
    }
}
