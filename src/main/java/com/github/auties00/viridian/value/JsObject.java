package com.github.auties00.viridian.value;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/// A plain JavaScript object: its own enumerable properties, which are what structured cloning keeps.
///
/// Property keys are strings, as JavaScript makes them: a numeric key such as `1` is the string `"1"`. Properties
/// keep the order they were serialized in, which is JavaScript's enumeration order: integer keys ascending, then the
/// other keys in insertion order.
public final class JsObject implements JsValue {
    private final LinkedHashMap<String, JsValue> properties;

    JsObject() {
        this.properties = new LinkedHashMap<>();
    }

    /// {@return an object with a copy of `properties`, in their iteration order}
    public static JsObject of(Map<String, ? extends JsValue> properties) {
        var object = new JsObject();
        for (var entry : properties.entrySet()) {
            object.put(Objects.requireNonNull(entry.getKey(), "key"), Objects.requireNonNull(entry.getValue(), "value"));
        }
        return object;
    }

    void put(String key, JsValue value) {
        properties.put(key, value);
    }

    /// {@return the properties, in order, as an unmodifiable view}
    public Map<String, JsValue> properties() {
        return Collections.unmodifiableMap(properties);
    }

    /// {@return the value of the property `key`, or an empty optional if the object has none}
    public Optional<JsValue> get(String key) {
        return Optional.ofNullable(properties.get(key));
    }

    /// {@return how many properties the object has}
    public int size() {
        return properties.size();
    }

    /// {@return the object in a JavaScript-like notation, `[Circular]` where it contains itself}
    @Override
    public String toString() {
        return JsRendering.render(this);
    }
}
