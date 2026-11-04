package com.github.auties00.viridian.value;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/// A JavaScript `Map`: its entries, in insertion order.
///
/// Keys may be any value, objects included, so the entries are a list rather than a Java map. Keys are compared as
/// JavaScript's `Map` compares them: primitives by value, `NaN` equal to itself and `-0` to `0`, and objects, the
/// records among them included, by identity. A map is immutable; [#builder()] builds one, cycles included.
public final class JsMap implements JsValue {
    private final ArrayList<Map.Entry<JsValue, JsValue>> entries;
    // The position in entries of each key, by its SameValueZero key
    private final HashMap<Object, Integer> positions;

    JsMap() {
        this.entries = new ArrayList<>();
        this.positions = new HashMap<>();
    }

    /// {@return a builder of a map}
    public static Builder builder() {
        return new Builder();
    }

    /// Sets the value of `key`, in place if the map has the key, at the end if it does not; a `-0` key is stored as
    /// `0`, as JavaScript stores it.
    void put(JsValue key, JsValue value) {
        var stored = SameValueZero.normalize(key);
        var position = positions.putIfAbsent(SameValueZero.keyOf(stored), entries.size());
        if (position == null) {
            entries.add(Map.entry(stored, value));
        } else {
            entries.set(position, Map.entry(entries.get(position).getKey(), value));
        }
    }

    /// {@return the value of `key`, or an empty optional if the map has no such key}
    public Optional<JsValue> get(JsValue key) {
        var position = positions.get(SameValueZero.keyOf(Objects.requireNonNull(key, "key")));
        return position == null ? Optional.empty() : Optional.of(entries.get(position).getValue());
    }

    /// {@return the value of the string key `key`, or an empty optional if the map has no such key}
    public Optional<JsValue> get(String key) {
        return get(JsValue.of(key));
    }

    /// {@return whether the map has the key `key`}
    public boolean has(JsValue key) {
        return positions.containsKey(SameValueZero.keyOf(Objects.requireNonNull(key, "key")));
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

    /// A builder of a [JsMap], whose keys and values may be builders of other values, itself included, as
    /// [JsBuilder] describes.
    ///
    /// Putting a key the map already has replaces its value in place, as `Map.prototype.set` does; a `-0` key is
    /// stored as `0`.
    public static final class Builder implements JsBuilder<JsMap> {
        final JsMap instance;
        final BuilderState state;

        private Builder() {
            this.instance = new JsMap();
            this.state = new BuilderState();
        }

        /// Puts the entry of `key`.
        ///
        /// @return this builder
        /// @throws IllegalStateException if the builder already built its map
        public Builder put(JsValue key, JsValue value) {
            state.checkOpen();
            instance.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
            return this;
        }

        /// Puts the entry of `key`, holding the value `value` builds.
        ///
        /// @return this builder
        /// @throws IllegalStateException if the builder already built its map
        public Builder put(JsValue key, JsBuilder<?> value) {
            Objects.requireNonNull(key, "key");
            instance.put(key, state.hold(value));
            return this;
        }

        /// Puts the entry of the key `key` builds.
        ///
        /// @return this builder
        /// @throws IllegalStateException if the builder already built its map
        public Builder put(JsBuilder<?> key, JsValue value) {
            Objects.requireNonNull(value, "value");
            instance.put(state.hold(key), value);
            return this;
        }

        /// Puts the entry of the key `key` builds, holding the value `value` builds.
        ///
        /// @return this builder
        /// @throws IllegalStateException if the builder already built its map
        public Builder put(JsBuilder<?> key, JsBuilder<?> value) {
            instance.put(state.hold(key), state.hold(value));
            return this;
        }

        /// Puts the entry of the string key `key`.
        ///
        /// @return this builder
        /// @throws IllegalStateException if the builder already built its map
        public Builder put(String key, JsValue value) {
            return put(JsValue.of(key), value);
        }

        @Override
        public JsMap build() {
            state.build();
            return instance;
        }
    }
}
