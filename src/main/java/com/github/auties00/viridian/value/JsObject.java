package com.github.auties00.viridian.value;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

/// A plain JavaScript object: its own enumerable properties, which are what structured cloning keeps.
///
/// Property keys are strings, as JavaScript makes them: a numeric key such as `1` is the string `"1"`. Properties are
/// in JavaScript's enumeration order, whatever order they were put in: keys that are array indexes ascending, then the
/// other keys in the order they were first put. An object is immutable; [#builder()] builds one, cycles included.
///
/// # Typed reading
///
/// [#get(String)] returns a property as stored, `undefined` and `null` included, and is empty only when the object
/// has no such property, so it tells `{a: null}` from `{}`. The typed accessors read a property as the type a schema
/// expects, here and by index on [JsArray]:
///
/// - they return an empty optional when the property is absent, or holds `undefined` or `null`;
/// - they return its value when it holds that type: [#get(String, Class)] any type of value, such as a
///   [JsCryptoKey]; [#getBytes(String)] an `ArrayBuffer` or any view of one, whose bytes it copies;
///   [#getDate(String)] a date, empty when it is invalid;
/// - they throw [ClassCastException], naming the property, the type expected and the type found, when it holds
///   another type, so that a value of an unexpected shape does not pass unnoticed;
/// - [#getLong(String)] and [#getInt(String)] throw [ArithmeticException] when the number is not an integer, or is
///   one past the range of the Java type; [#getLong(String)] also refuses one more than 2^53 from zero, which a
///   number may hold only rounded.
///
/// {@snippet lang = java :
/// if (record.value() instanceof JsObject chat) {
///     String id = chat.getString("id").orElseThrow();
///     long timestamp = chat.getLong("t").orElse(0);
///     boolean archived = chat.getBoolean("archive").orElse(false);
///     String firstLabel = chat.getArray("labels").flatMap(labels -> labels.getString(0)).orElse("none");
/// }
/// }
public final class JsObject implements JsValue {
    private final PropertyMap properties;

    JsObject() {
        this.properties = new PropertyMap();
    }

    /// {@return a builder of an object}
    public static Builder builder() {
        return new Builder();
    }

    /// {@return an empty object}
    public static JsObject of() {
        return new JsObject();
    }

    /// {@return an object with `properties`, in JavaScript's order}
    public static JsObject of(Map<String, ? extends JsValue> properties) {
        var object = new JsObject();
        for (var entry : properties.entrySet()) {
            object.put(Objects.requireNonNull(entry.getKey(), "key"), Objects.requireNonNull(entry.getValue(), "value"));
        }
        return object;
    }

    /// Stores a property, as the decoder and the builder fill an object.
    void put(String key, JsValue value) {
        properties.put(key, value);
    }

    /// {@return the properties, in order, as an unmodifiable view}
    public Map<String, JsValue> properties() {
        return Collections.unmodifiableMap(properties);
    }

    /// {@return whether the object has the property `key`, whatever it holds}
    public boolean has(String key) {
        return properties.containsKey(Objects.requireNonNull(key, "key"));
    }

    /// {@return how many properties the object has}
    public int size() {
        return properties.size();
    }

    /// {@return the value of the property `key` as stored, `undefined` and `null` included, or an empty optional if
    /// the object has no such property}
    public Optional<JsValue> get(String key) {
        return Optional.ofNullable(properties.get(Objects.requireNonNull(key, "key")));
    }

    /// {@return the value of the property `key` as a `type`, as the class documentation describes}
    ///
    /// @param <T> the type of value expected
    public <T extends JsValue> Optional<T> get(String key, Class<T> type) {
        return JsAccess.as(properties.get(key), type, where(key));
    }

    /// {@return the string of the property `key`, as the class documentation describes}
    public Optional<String> getString(String key) {
        return JsAccess.string(properties.get(key), where(key));
    }

    /// {@return the number of the property `key`, as the class documentation describes}
    public OptionalDouble getNumber(String key) {
        return JsAccess.number(properties.get(key), where(key));
    }

    /// {@return the integer of the property `key`, as the class documentation describes}
    public OptionalLong getLong(String key) {
        return JsAccess.integer(properties.get(key), where(key));
    }

    /// {@return the integer of the property `key`, as the class documentation describes}
    public OptionalInt getInt(String key) {
        return JsAccess.integer32(properties.get(key), where(key));
    }

    /// {@return the boolean of the property `key`, as the class documentation describes}
    public Optional<Boolean> getBoolean(String key) {
        return JsAccess.bool(properties.get(key), where(key));
    }

    /// {@return the BigInt of the property `key`, as the class documentation describes}
    public Optional<BigInteger> getBigInt(String key) {
        return JsAccess.bigInt(properties.get(key), where(key));
    }

    /// {@return the object of the property `key`, as the class documentation describes}
    public Optional<JsObject> getObject(String key) {
        return JsAccess.as(properties.get(key), JsObject.class, "an object", where(key));
    }

    /// {@return the array of the property `key`, as the class documentation describes}
    public Optional<JsArray> getArray(String key) {
        return JsAccess.as(properties.get(key), JsArray.class, "an array", where(key));
    }

    /// {@return the `Map` of the property `key`, as the class documentation describes}
    public Optional<JsMap> getMap(String key) {
        return JsAccess.as(properties.get(key), JsMap.class, "a Map", where(key));
    }

    /// {@return the `Set` of the property `key`, as the class documentation describes}
    public Optional<JsSet> getSet(String key) {
        return JsAccess.as(properties.get(key), JsSet.class, "a Set", where(key));
    }

    /// {@return a copy of the bytes of the `ArrayBuffer` or view of the property `key`, as the class documentation
    /// describes}
    public Optional<byte[]> getBytes(String key) {
        return JsAccess.bytes(properties.get(key), where(key));
    }

    /// {@return the instant of the `Date` of the property `key`, as the class documentation describes}
    public Optional<Instant> getDate(String key) {
        return JsAccess.date(properties.get(key), where(key));
    }

    /// The properties themselves, for the serializer of the same package.
    PropertyMap propertyMap() {
        return properties;
    }

    private static String where(String key) {
        return "Property \"" + Objects.requireNonNull(key, "key") + "\"";
    }

    /// {@return the object in a JavaScript-like notation, `[Circular]` where it contains itself}
    @Override
    public String toString() {
        return JsRendering.render(this);
    }

    /// A builder of a [JsObject], which may hold builders of other values, itself included, as [JsBuilder] describes.
    ///
    /// Putting a key the object already has replaces its value in place, as JavaScript does.
    public static final class Builder implements JsBuilder<JsObject> {
        final JsObject instance;
        final BuilderState state;

        private Builder() {
            this.instance = new JsObject();
            this.state = new BuilderState();
        }

        /// Puts the property `key`.
        ///
        /// @return this builder
        /// @throws IllegalStateException if the builder already built its object
        public Builder put(String key, JsValue value) {
            state.checkOpen();
            instance.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
            return this;
        }

        /// Puts the property `key`, holding the value `value` builds.
        ///
        /// @return this builder
        /// @throws IllegalStateException if the builder already built its object
        public Builder put(String key, JsBuilder<?> value) {
            Objects.requireNonNull(key, "key");
            instance.put(key, state.hold(value));
            return this;
        }

        /// Puts the property `key`, holding a string.
        ///
        /// @return this builder
        /// @throws IllegalStateException if the builder already built its object
        public Builder put(String key, String value) {
            return put(key, JsValue.of(value));
        }

        /// Puts the property `key`, holding a number.
        ///
        /// @return this builder
        /// @throws IllegalStateException if the builder already built its object
        public Builder put(String key, double value) {
            return put(key, JsValue.of(value));
        }

        /// Puts the property `key`, holding a number.
        ///
        /// @return this builder
        /// @throws ArithmeticException   if `value` is more than 2^53 from zero, as [JsValue#of(long)] refuses
        /// @throws IllegalStateException if the builder already built its object
        public Builder put(String key, long value) {
            return put(key, JsValue.of(value));
        }

        /// Puts the property `key`, holding a number.
        ///
        /// @return this builder
        /// @throws IllegalStateException if the builder already built its object
        public Builder put(String key, int value) {
            return put(key, JsValue.of(value));
        }

        /// Puts the property `key`, holding a boolean.
        ///
        /// @return this builder
        /// @throws IllegalStateException if the builder already built its object
        public Builder put(String key, boolean value) {
            return put(key, JsValue.of(value));
        }

        @Override
        public JsObject build() {
            state.build();
            return instance;
        }
    }
}
