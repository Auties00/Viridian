package com.github.auties00.viridian.value;

import java.math.BigInteger;
import java.time.Instant;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.SortedMap;
import java.util.TreeMap;

/// A JavaScript array: its length, its elements, and any other properties it has.
///
/// An array may have holes, indexes below its length that hold no element, which differ from elements that hold
/// `undefined`. A sparse array, one whose length far exceeds its elements, keeps only the elements it has.
///
/// An array is immutable; [#builder()] builds one, cycles included. [#get(long)] and the typed accessors read an
/// element by index as [JsObject]'s read a property, a hole reading as absent; [#values()] lists the elements with
/// holes as `undefined`, and [#elements()] the elements the array has.
///
/// {@snippet lang = java :
/// JsArray list = JsArray.builder()
///         .add(1)
///         .add("two")
///         .set(9, JsValue.of(true))              // length 10, holes at 2 to 8
///         .put("note", JsValue.of("an array may have named properties too"))
///         .build();
/// }
public final class JsArray implements JsValue {
    // The longest array JavaScript allows: indexes run from 0 to 2^32 - 2
    static final long MAX_LENGTH = 0xFFFF_FFFFL;
    // How many holes a dense array takes on at once before it keeps its elements by index instead
    private static final int MAX_DENSE_GROWTH = 1024;
    // The longest list of elements kept in sequence, a little below the longest Java array
    private static final long MAX_DENSE_LENGTH = Integer.MAX_VALUE - 8;

    private long length;
    // The elements of a dense array, one per index below the length, null for a hole; null for a sparse array
    private ArrayList<JsValue> dense;
    // The elements of a sparse array by index; null for a dense array
    private TreeMap<Long, JsValue> sparse;
    private final LinkedHashMap<String, JsValue> properties;

    private JsArray(long length, boolean sparse) {
        this.length = length;
        if (sparse) {
            this.sparse = new TreeMap<>();
        } else {
            this.dense = new ArrayList<>((int) Math.min(length, 1024));
            for (var i = 0L; i < length; i++) {
                this.dense.add(null);
            }
        }
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

    /// {@return a builder of an array, empty until it adds elements}
    public static Builder builder() {
        return new Builder();
    }

    /// {@return an empty array}
    public static JsArray of() {
        return new JsArray(0, false);
    }

    /// {@return an array of `elements`, in order}
    ///
    /// @throws NullPointerException if an element is `null`; [Builder#addHole()] makes a hole
    public static JsArray of(JsValue... elements) {
        return of(Arrays.asList(elements));
    }

    /// {@return an array of the strings `elements`, in order}
    public static JsArray of(String... elements) {
        var array = new JsArray(elements.length, false);
        for (var i = 0; i < elements.length; i++) {
            array.dense.set(i, JsValue.of(elements[i]));
        }
        return array;
    }

    /// {@return an array of `elements`, in order}
    ///
    /// @throws NullPointerException if an element is `null`; [Builder#addHole()] makes a hole
    public static JsArray of(List<? extends JsValue> elements) {
        var array = new JsArray(elements.size(), false);
        for (var i = 0; i < elements.size(); i++) {
            array.dense.set(i, Objects.requireNonNull(elements.get(i), "element"));
        }
        return array;
    }

    /// Stores `value` at `index`, extending the array if the index is at or past its length.
    void set(long index, JsValue value) {
        if (index < 0 || index >= MAX_LENGTH) {
            throw new IllegalArgumentException("Array indexes run from 0 to " + (MAX_LENGTH - 1) + ": " + index);
        }
        if (index >= length) {
            grow(index + 1);
        }
        if (dense != null) {
            dense.set((int) index, value);
        } else {
            sparse.put(index, value);
        }
    }

    /// Sets the length, removing the elements at and past it if it shortens the array, adding holes if it lengthens
    /// it.
    void setLength(long length) {
        if (length < 0 || length > MAX_LENGTH) {
            throw new IllegalArgumentException("Array lengths run from 0 to " + MAX_LENGTH + ": " + length);
        }
        if (length >= this.length) {
            grow(length);
        } else if (dense != null) {
            dense.subList((int) length, dense.size()).clear();
            this.length = length;
        } else {
            sparse.tailMap(length, true).clear();
            this.length = length;
        }
    }

    /// Stores a property that is not an element, as read, whatever its key.
    void putProperty(String key, JsValue value) {
        properties.put(key, value);
    }

    /// {@return the length: one more than the highest index, holes included}
    public long length() {
        return length;
    }

    /// {@return whether the array keeps its elements by index rather than in sequence}
    ///
    /// An array read from a value V8 wrote as sparse keeps them by index, as does one built with a long run of holes.
    /// Such an array, like any array with holes, is serialized by index, as V8 serializes holey arrays.
    public boolean isSparse() {
        return sparse != null;
    }

    /// {@return the elements from index 0 to the length, as an unmodifiable list where a hole reads as `undefined`, as
    /// `[...array]` gives them}
    ///
    /// [#has(long)] and [#elements()] tell a hole from an element that holds `undefined`.
    ///
    /// @throws IllegalStateException if the array is longer than a list can be, `Integer.MAX_VALUE` elements
    public List<JsValue> values() {
        if (length > Integer.MAX_VALUE) {
            throw new IllegalStateException("The array is " + length + " elements long, more than a list holds: use elements()");
        }
        var size = (int) length;
        return new AbstractList<>() {
            @Override
            public JsValue get(int index) {
                Objects.checkIndex(index, size);
                var element = element(index);
                return element == null ? JsValue.UNDEFINED : element;
            }

            @Override
            public int size() {
                return size;
            }
        };
    }

    /// {@return the elements the array has, by index, holes left out, as an unmodifiable view}
    public SortedMap<Long, JsValue> elements() {
        if (sparse != null) {
            return Collections.unmodifiableSortedMap(sparse);
        }
        var present = new TreeMap<Long, JsValue>();
        for (var i = 0; i < dense.size(); i++) {
            if (dense.get(i) != null) {
                present.put((long) i, dense.get(i));
            }
        }
        return Collections.unmodifiableSortedMap(present);
    }

    /// {@return whether the array has an element at `index`, whatever it holds: `false` for a hole and for an index
    /// outside the array}
    public boolean has(long index) {
        return element(index) != null;
    }

    /// {@return the properties that are not elements, in order, as an unmodifiable view}
    public Map<String, JsValue> properties() {
        return Collections.unmodifiableMap(properties);
    }

    /// {@return the element at `index` as stored, `undefined` and `null` included, or an empty optional for a hole or
    /// an index outside the array}
    public Optional<JsValue> get(long index) {
        return Optional.ofNullable(element(index));
    }

    /// {@return the element at `index` as a `type`, as [JsObject] describes its typed reading}
    ///
    /// @param <T> the type of value expected
    public <T extends JsValue> Optional<T> get(long index, Class<T> type) {
        return JsAccess.as(element(index), type, where(index));
    }

    /// {@return the string at `index`, as [JsObject] describes its typed reading}
    public Optional<String> getString(long index) {
        return JsAccess.string(element(index), where(index));
    }

    /// {@return the number at `index`, as [JsObject] describes its typed reading}
    public OptionalDouble getNumber(long index) {
        return JsAccess.number(element(index), where(index));
    }

    /// {@return the integer at `index`, as [JsObject] describes its typed reading}
    public OptionalLong getLong(long index) {
        return JsAccess.integer(element(index), where(index));
    }

    /// {@return the integer at `index`, as [JsObject] describes its typed reading}
    public OptionalInt getInt(long index) {
        return JsAccess.integer32(element(index), where(index));
    }

    /// {@return the boolean at `index`, as [JsObject] describes its typed reading}
    public Optional<Boolean> getBoolean(long index) {
        return JsAccess.bool(element(index), where(index));
    }

    /// {@return the BigInt at `index`, as [JsObject] describes its typed reading}
    public Optional<BigInteger> getBigInt(long index) {
        return JsAccess.bigInt(element(index), where(index));
    }

    /// {@return the object at `index`, as [JsObject] describes its typed reading}
    public Optional<JsObject> getObject(long index) {
        return JsAccess.as(element(index), JsObject.class, "an object", where(index));
    }

    /// {@return the array at `index`, as [JsObject] describes its typed reading}
    public Optional<JsArray> getArray(long index) {
        return JsAccess.as(element(index), JsArray.class, "an array", where(index));
    }

    /// {@return the `Map` at `index`, as [JsObject] describes its typed reading}
    public Optional<JsMap> getMap(long index) {
        return JsAccess.as(element(index), JsMap.class, "a Map", where(index));
    }

    /// {@return the `Set` at `index`, as [JsObject] describes its typed reading}
    public Optional<JsSet> getSet(long index) {
        return JsAccess.as(element(index), JsSet.class, "a Set", where(index));
    }

    /// {@return a copy of the bytes of the `ArrayBuffer` or view at `index`, as [JsObject] describes its typed
    /// reading}
    public Optional<byte[]> getBytes(long index) {
        return JsAccess.bytes(element(index), where(index));
    }

    /// {@return the instant of the `Date` at `index`, as [JsObject] describes its typed reading}
    public Optional<Instant> getDate(long index) {
        return JsAccess.date(element(index), where(index));
    }

    /// {@return the element at `index` as stored, or `null` for a hole or an index outside the array}
    private JsValue element(long index) {
        if (index < 0 || index >= length) {
            return null;
        }
        return dense != null ? dense.get((int) index) : sparse.get(index);
    }

    private static String where(long index) {
        return "Element " + index;
    }

    /// {@return the elements of a dense array, `null` for a hole, or `null` for a sparse array}
    List<JsValue> denseElements() {
        return dense;
    }

    /// {@return the elements of a sparse array by index, or `null` for a dense array}
    NavigableMap<Long, JsValue> sparseElements() {
        return sparse;
    }

    /// Lengthens the array to `length`, with holes, keeping its elements by index once that saves many holes.
    private void grow(long length) {
        if (dense != null && (length - dense.size() > MAX_DENSE_GROWTH || length > MAX_DENSE_LENGTH)) {
            sparse = new TreeMap<>();
            for (var i = 0; i < dense.size(); i++) {
                if (dense.get(i) != null) {
                    sparse.put((long) i, dense.get(i));
                }
            }
            dense = null;
        }
        if (dense != null) {
            while (dense.size() < length) {
                dense.add(null);
            }
        }
        this.length = length;
    }

    /// {@return the array in a JavaScript-like notation, `[Circular]` where it contains itself}
    @Override
    public String toString() {
        return JsRendering.render(this);
    }

    /// A builder of a [JsArray], which may hold builders of other values, itself included, as [JsBuilder] describes.
    ///
    /// As in JavaScript, setting an element at or past the length extends the array with holes, and shortening the
    /// length removes the elements past it.
    public static final class Builder implements JsBuilder<JsArray> {
        final JsArray instance;
        final BuilderState state;

        private Builder() {
            this.instance = new JsArray(0, false);
            this.state = new BuilderState();
        }

        /// Appends `value`, as `push` does.
        ///
        /// @return this builder
        /// @throws IllegalArgumentException if the array is already as long as JavaScript allows
        /// @throws IllegalStateException    if the builder already built its array
        public Builder add(JsValue value) {
            return set(instance.length(), value);
        }

        /// Appends the value `value` builds.
        ///
        /// @return this builder
        /// @throws IllegalArgumentException if the array is already as long as JavaScript allows
        /// @throws IllegalStateException    if the builder already built its array
        public Builder add(JsBuilder<?> value) {
            return set(instance.length(), value);
        }

        /// Appends a string.
        ///
        /// @return this builder
        /// @throws IllegalArgumentException if the array is already as long as JavaScript allows
        /// @throws IllegalStateException    if the builder already built its array
        public Builder add(String value) {
            return add(JsValue.of(value));
        }

        /// Appends a number.
        ///
        /// @return this builder
        /// @throws IllegalArgumentException if the array is already as long as JavaScript allows
        /// @throws IllegalStateException    if the builder already built its array
        public Builder add(double value) {
            return add(JsValue.of(value));
        }

        /// Appends a number.
        ///
        /// @return this builder
        /// @throws ArithmeticException      if `value` is more than 2^53 from zero, as [JsValue#of(long)] refuses
        /// @throws IllegalArgumentException if the array is already as long as JavaScript allows
        /// @throws IllegalStateException    if the builder already built its array
        public Builder add(long value) {
            return add(JsValue.of(value));
        }

        /// Appends a number.
        ///
        /// @return this builder
        /// @throws IllegalArgumentException if the array is already as long as JavaScript allows
        /// @throws IllegalStateException    if the builder already built its array
        public Builder add(int value) {
            return add(JsValue.of(value));
        }

        /// Appends a boolean.
        ///
        /// @return this builder
        /// @throws IllegalArgumentException if the array is already as long as JavaScript allows
        /// @throws IllegalStateException    if the builder already built its array
        public Builder add(boolean value) {
            return add(JsValue.of(value));
        }

        /// Appends a hole: an index that holds no element.
        ///
        /// @return this builder
        /// @throws IllegalArgumentException if the array is already as long as JavaScript allows
        /// @throws IllegalStateException    if the builder already built its array
        public Builder addHole() {
            return length(instance.length() + 1);
        }

        /// Stores `value` at `index`, extending the array if the index is at or past its length.
        ///
        /// @return this builder
        /// @throws IllegalArgumentException if the index is negative or past 2^32 - 2, the highest array index
        /// @throws IllegalStateException    if the builder already built its array
        public Builder set(long index, JsValue value) {
            state.checkOpen();
            instance.set(index, Objects.requireNonNull(value, "value"));
            return this;
        }

        /// Stores the value `value` builds at `index`, extending the array if the index is at or past its length.
        ///
        /// @return this builder
        /// @throws IllegalArgumentException if the index is negative or past 2^32 - 2, the highest array index
        /// @throws IllegalStateException    if the builder already built its array
        public Builder set(long index, JsBuilder<?> value) {
            state.checkOpen();
            if (index < 0 || index >= MAX_LENGTH) {
                throw new IllegalArgumentException("Array indexes run from 0 to " + (MAX_LENGTH - 1) + ": " + index);
            }
            instance.set(index, state.hold(value));
            return this;
        }

        /// Sets the length, removing the elements at and past it if it shortens the array, adding holes if it
        /// lengthens it.
        ///
        /// @return this builder
        /// @throws IllegalArgumentException if the length is negative or past 2^32 - 1
        /// @throws IllegalStateException    if the builder already built its array
        public Builder length(long length) {
            state.checkOpen();
            instance.setLength(length);
            return this;
        }

        /// Puts the property `key`: the element it names if `key` is an array index, as in JavaScript, and a property
        /// that is not an element otherwise.
        ///
        /// @return this builder
        /// @throws IllegalArgumentException if `key` is `length`, which is not an element nor a property structured
        ///                                  cloning keeps; set it with [#length(long)]
        /// @throws IllegalStateException    if the builder already built its array
        public Builder put(String key, JsValue value) {
            state.checkOpen();
            Objects.requireNonNull(value, "value");
            var index = propertyIndex(key);
            if (index >= 0) {
                instance.set(index, value);
            } else {
                instance.putProperty(key, value);
            }
            return this;
        }

        /// Puts the property `key`, holding the value `value` builds, as [#put(String, JsValue)] does.
        ///
        /// @return this builder
        /// @throws IllegalArgumentException if `key` is `length`
        /// @throws IllegalStateException    if the builder already built its array
        public Builder put(String key, JsBuilder<?> value) {
            state.checkOpen();
            var index = propertyIndex(key);
            var held = state.hold(value);
            if (index >= 0) {
                instance.set(index, held);
            } else {
                instance.putProperty(key, held);
            }
            return this;
        }

        /// {@return the index `key` names, or -1 for a property that is not an element}
        private static long propertyIndex(String key) {
            if (Objects.requireNonNull(key, "key").equals("length")) {
                throw new IllegalArgumentException("The length of an array is not a property: use length(long)");
            }
            return JsNumbers.arrayIndex(key);
        }

        @Override
        public JsArray build() {
            state.build();
            return instance;
        }
    }
}
