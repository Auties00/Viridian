package com.github.auties00.viridian.value;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

/// A JavaScript `Set`: its values, in insertion order.
///
/// Values are compared as JavaScript's `Set` compares them: primitives by value, `NaN` equal to itself and `-0` to
/// `0`, and objects, the records among them included, by identity. A set is immutable; [#builder()] builds one,
/// cycles included.
public final class JsSet implements JsValue {
    private final ArrayList<JsValue> values;
    // The position in values of each value, by its SameValueZero key
    private final HashMap<Object, Integer> positions;

    JsSet() {
        this.values = new ArrayList<>();
        this.positions = new HashMap<>();
    }

    /// {@return a builder of a set}
    public static Builder builder() {
        return new Builder();
    }

    /// {@return a set of `values`, in order, each once}
    public static JsSet of(JsValue... values) {
        var set = new JsSet();
        for (var value : values) {
            set.add(Objects.requireNonNull(value, "value"));
        }
        return set;
    }

    /// Adds `value` at the end, unless the set has it; `-0` is stored as `0`, as JavaScript stores it.
    void add(JsValue value) {
        var stored = SameValueZero.normalize(value);
        if (positions.putIfAbsent(SameValueZero.keyOf(stored), values.size()) == null) {
            values.add(stored);
        }
    }

    /// {@return whether the set has `value`}
    public boolean has(JsValue value) {
        return positions.containsKey(SameValueZero.keyOf(Objects.requireNonNull(value, "value")));
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

    /// A builder of a [JsSet], whose values may be builders of other values, itself included, as [JsBuilder]
    /// describes.
    ///
    /// Adding a value the set already has leaves it where it is, as `Set.prototype.add` does; `-0` is stored as `0`.
    public static final class Builder implements JsBuilder<JsSet> {
        final JsSet instance;
        final BuilderState state;

        private Builder() {
            this.instance = new JsSet();
            this.state = new BuilderState();
        }

        /// Adds `value`.
        ///
        /// @return this builder
        /// @throws IllegalStateException if the builder already built its set
        public Builder add(JsValue value) {
            state.checkOpen();
            instance.add(Objects.requireNonNull(value, "value"));
            return this;
        }

        /// Adds the value `value` builds.
        ///
        /// @return this builder
        /// @throws IllegalStateException if the builder already built its set
        public Builder add(JsBuilder<?> value) {
            instance.add(state.hold(value));
            return this;
        }

        @Override
        public JsSet build() {
            state.build();
            return instance;
        }
    }
}
