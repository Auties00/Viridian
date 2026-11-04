package com.github.auties00.viridian.indexeddb;

import com.github.auties00.viridian.IdbIndex;
import com.github.auties00.viridian.IdbKey;
import com.github.auties00.viridian.IdbKeyPath;
import com.github.auties00.viridian.IdbObjectStore;
import com.github.auties00.viridian.value.JsArray;
import com.github.auties00.viridian.value.JsArrayBuffer;
import com.github.auties00.viridian.value.JsArrayBufferView;
import com.github.auties00.viridian.value.JsBlob;
import com.github.auties00.viridian.value.JsDate;
import com.github.auties00.viridian.value.JsError;
import com.github.auties00.viridian.value.JsFile;
import com.github.auties00.viridian.value.JsNumber;
import com.github.auties00.viridian.value.JsObject;
import com.github.auties00.viridian.value.JsRegExp;
import com.github.auties00.viridian.value.JsString;
import com.github.auties00.viridian.value.JsUndefined;
import com.github.auties00.viridian.value.JsValue;
import com.github.auties00.viridian.value.JsWrapper;

import java.io.Serial;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/// Evaluates IndexedDB key paths on values and converts the results to keys, as Chromium does when a page stores a
/// record: the record's key in an object store with in-line keys, and its keys in each index.
///
/// A key path is evaluated one identifier at a time. The `length` of a string or an array, the `size` and `type` of a
/// blob or file, and the `name`, `lastModified` and `lastModifiedDate` of a file are read directly; any other
/// identifier must name an own property of an object, as the value has them once deserialized: an object's
/// properties, an array's properties that are not elements, the `length` of a `String` object, an error's `message`,
/// `stack` and `cause`, and a regular expression's `lastIndex`. No identifier names an element, since a valid key path
/// holds no array index. Anything else fails the evaluation.
///
/// A value converts to a key if it is a number other than `NaN`, a valid date, a string, an `ArrayBuffer` or a view
/// of one, or an array whose every element up to its length is present and converts. An array that contains itself,
/// nests deeper than 2000 arrays or holds more than 1,000,000 elements does not.
///
/// @implNote This implementation follows Chromium where it departs from the specification: an array that holds the
/// same array twice, without containing itself, converts, and a multi-entry index skips a record whose array has a
/// hole, rather than only the hole.
/// @spec https://w3c.github.io/IndexedDB/#key-construct Indexed Database API 3.0: keys
public final class IdbKeyPaths {
    // IndexedDBKey::kMaximumDepth and kMaximumArraySize
    private static final int MAX_DEPTH = 2000;
    private static final long MAX_ARRAY_SIZE = 1_000_000;

    private IdbKeyPaths() {
    }

    /// {@return the key `keyPath` gives `value`, or an empty optional if evaluating the path fails or its result is
    /// not a valid key}
    ///
    /// A compound key path gives an array key of its paths' keys, and fails if any of them does.
    public static Optional<IdbKey> extract(JsValue value, IdbKeyPath keyPath) {
        Objects.requireNonNull(value, "value");
        return Optional.ofNullable(switch (Objects.requireNonNull(keyPath, "keyPath")) {
            case IdbKeyPath.Single single -> {
                var evaluated = evaluate(value, single.path());
                yield evaluated == null ? null : toKey(evaluated);
            }
            case IdbKeyPath.Compound compound -> {
                var keys = new ArrayList<IdbKey>(compound.paths().size());
                for (var path : compound.paths()) {
                    var evaluated = evaluate(value, path);
                    var key = evaluated == null ? null : toKey(evaluated);
                    if (key == null) {
                        yield null;
                    }
                    keys.add(key);
                }
                yield new IdbKey.ArrayKey(keys);
            }
        });
    }

    /// What evaluating a key path on a value gives: a key, a value that is not a valid key, or nothing.
    ///
    /// @param present whether the path names a value other than `undefined`
    /// @param key     the key that value converts to, or `null` if it does not convert
    public record Evaluation(boolean present, IdbKey key) {
    }

    /// {@return what `keyPath` names in `value`: a key, a value that is not one, or nothing when evaluating the path
    /// fails or names `undefined`, where a store with a key generator generates the key}
    public static Evaluation evaluate(JsValue value, IdbKeyPath keyPath) {
        if (keyPath instanceof IdbKeyPath.Single single) {
            var evaluated = evaluate(value, single.path());
            if (evaluated == null || evaluated instanceof JsUndefined) {
                return new Evaluation(false, null);
            }
            return new Evaluation(true, toKey(evaluated));
        }
        var key = extract(value, keyPath);
        return new Evaluation(true, key.orElse(null));
    }

    /// {@return whether a key could be injected into `value` at `keyPath`: every value on the way that it has is an
    /// object or an array, which a stored value can hold properties of}
    ///
    /// @spec https://w3c.github.io/IndexedDB/#check-that-a-key-could-be-injected-into-a-value Indexed Database API 3.0: key injection
    public static boolean canInject(JsValue value, IdbKeyPath.Single keyPath) {
        var identifiers = keyPath.identifiers();
        var current = value;
        for (var i = 0; i < identifiers.size(); i++) {
            if (!(current instanceof JsObject) && !(current instanceof JsArray)) {
                return false;
            }
            if (i == identifiers.size() - 1) {
                return true;
            }
            var next = step(current, identifiers.get(i));
            if (next == null) {
                return true;
            }
            current = next;
        }
        return false;
    }

    /// {@return `key` as the value Blink injects for it: a number, a `Date`, a string, an `ArrayBuffer` for a binary
    /// key, or an array of those}
    public static JsValue toValue(IdbKey key) {
        return switch (key) {
            case IdbKey.NumberKey number -> new JsNumber(number.value());
            case IdbKey.DateKey date -> new JsDate(date.time());
            case IdbKey.StringKey string -> new JsString(string.value());
            case IdbKey.BinaryKey binary -> JsArrayBuffer.of(binary.bytes());
            case IdbKey.ArrayKey array -> JsArray.of(array.elements().stream().map(IdbKeyPaths::toValue).toList());
        };
    }

    /// {@return the keys under which `index` holds a record whose value is `value`, sorted and distinct}
    ///
    /// A record has no key in the index if evaluating the index's key path fails or gives no valid key. In a
    /// multi-entry index, a path that gives an array gives each of its elements that converts to a key; one that gives
    /// anything else gives its key, if it has one.
    public static List<IdbKey> indexKeys(JsValue value, IdbIndex index) {
        Objects.requireNonNull(value, "value");
        return indexKeys(value, index, null, null);
    }

    /// {@return the keys under which `index` of `store` holds the record of `primaryKey` whose stored value is `value`,
    /// sorted and distinct}
    ///
    /// As [#indexKeys(JsValue, IdbIndex)], except that in a store whose keys are generated and in-line, a value may
    /// lack its key, which Chromium then keeps beside the value rather than in it: where the index's key path is the
    /// store's, or one of the paths of a compound one is, and the value does not have it, the record's key stands in
    /// for it.
    public static List<IdbKey> indexKeys(IdbKey primaryKey, JsValue value, IdbIndex index, IdbObjectStore store) {
        Objects.requireNonNull(primaryKey, "primaryKey");
        Objects.requireNonNull(value, "value");
        var generatedPath = store.autoIncrement() && store.keyPath().orElse(null) instanceof IdbKeyPath.Single single ? single.path() : null;
        return indexKeys(value, index, generatedPath, primaryKey);
    }

    /// {@return the index keys of `value`, the primary key standing in for a value at `generatedPath` it lacks}
    ///
    /// @param generatedPath the store's key path if its keys are generated and in-line, or `null`
    private static List<IdbKey> indexKeys(JsValue value, IdbIndex index, String generatedPath, IdbKey primaryKey) {
        Objects.requireNonNull(index, "index");
        switch (index.keyPath()) {
            case IdbKeyPath.Single single -> {
                var evaluated = evaluate(value, single.path());
                if (evaluated == null) {
                    return single.path().equals(generatedPath) ? List.of(primaryKey) : List.of();
                }
                if (index.multiEntry()) {
                    return multiEntryKeys(evaluated);
                }
                var key = toKey(evaluated);
                return key == null ? List.of() : List.of(key);
            }
            case IdbKeyPath.Compound compound -> {
                var keys = new ArrayList<IdbKey>(compound.paths().size());
                for (var path : compound.paths()) {
                    var evaluated = evaluate(value, path);
                    if (evaluated == null) {
                        if (!path.equals(generatedPath)) {
                            return List.of();
                        }
                        keys.add(primaryKey);
                        continue;
                    }
                    var key = toKey(evaluated);
                    if (key == null) {
                        return List.of();
                    }
                    keys.add(key);
                }
                // Chromium refuses a multi-entry index with a compound key path, but would index each of the keys
                return index.multiEntry() ? List.copyOf(new TreeSet<>(keys)) : List.of(new IdbKey.ArrayKey(keys));
            }
        }
    }

    /// {@return the value `path` gives `value`, or `null` if the evaluation fails}
    private static JsValue evaluate(JsValue value, String path) {
        if (path.isEmpty()) {
            return value;
        }
        var current = value;
        for (var identifier : path.split("\\.", -1)) {
            current = step(current, identifier);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    /// {@return the value `identifier` names on `value`, or `null` if it names none}
    ///
    /// An identifier is never an array index, which a valid key path cannot name.
    private static JsValue step(JsValue value, String identifier) {
        return switch (value) {
            case JsString string -> identifier.equals("length") ? new JsNumber(string.value().length()) : null;
            case JsArray array -> identifier.equals("length") ? new JsNumber(array.length()) : array.properties().get(identifier);
            case JsObject object -> object.properties().get(identifier);
            case JsFile file -> switch (identifier) {
                case "size" -> file.size() < 0 ? null : new JsNumber(file.size());
                case "type" -> new JsString(file.type());
                case "name" -> new JsString(file.name());
                case "lastModified" -> file.lastModified().map(time -> (JsValue) new JsNumber(time.toEpochMilli())).orElse(null);
                case "lastModifiedDate" -> file.lastModified().map(time -> (JsValue) new JsDate(time.toEpochMilli())).orElse(null);
                default -> null;
            };
            case JsBlob blob -> switch (identifier) {
                case "size" -> new JsNumber(blob.size());
                case "type" -> new JsString(blob.type());
                default -> null;
            };
            case JsWrapper wrapper when wrapper.primitive() instanceof JsString string ->
                    identifier.equals("length") ? new JsNumber(string.value().length()) : null;
            case JsError error -> switch (identifier) {
                case "message" -> error.message().map(message -> (JsValue) new JsString(message)).orElse(null);
                case "stack" -> error.stack().map(stack -> (JsValue) new JsString(stack)).orElse(null);
                case "cause" -> error.cause().orElse(null);
                default -> null;
            };
            // A deserialized regular expression starts matching from the start
            case JsRegExp _ -> identifier.equals("lastIndex") ? new JsNumber(0) : null;
            default -> null;
        };
    }

    /// {@return the keys of a multi-entry index for an evaluated key path: each element of an array that converts, or
    /// the key of anything else}
    private static List<IdbKey> multiEntryKeys(JsValue value) {
        if (!(value instanceof JsArray array)) {
            var key = toKey(value);
            return key == null ? List.of() : List.of(key);
        }
        try {
            var open = new ArrayList<JsArray>();
            enter(array, open);
            var keys = new TreeSet<IdbKey>();
            for (var i = 0L; i < array.length(); i++) {
                var element = array.get(i).orElseThrow(Unconvertible::new);
                var key = toKey(element, open);
                if (key != null) {
                    keys.add(key);
                }
            }
            return List.copyOf(keys);
        } catch (Unconvertible _) {
            return List.of();
        }
    }

    /// {@return the key `value` converts to, or `null` if it is not a valid key}
    private static IdbKey toKey(JsValue value) {
        try {
            return toKey(value, new ArrayList<>());
        } catch (Unconvertible _) {
            return null;
        }
    }

    /// {@return the key `value` converts to, or `null` if it is not a valid key}
    ///
    /// @param open the arrays being converted, outermost first
    /// @throws Unconvertible if `value` is an array that makes the whole value unconvertible
    private static IdbKey toKey(JsValue value, List<JsArray> open) throws Unconvertible {
        return switch (value) {
            case JsNumber number -> Double.isNaN(number.value()) ? null : new IdbKey.NumberKey(number.value());
            case JsString string -> new IdbKey.StringKey(string.value());
            case JsDate date -> Double.isNaN(date.time()) ? null : new IdbKey.DateKey(date.time());
            case JsArrayBuffer buffer -> new IdbKey.BinaryKey(buffer.toByteArray());
            case JsArrayBufferView view -> new IdbKey.BinaryKey(view.toByteArray());
            case JsArray array -> {
                enter(array, open);
                var elements = new ArrayList<IdbKey>((int) array.length());
                var valid = true;
                for (var i = 0L; i < array.length(); i++) {
                    var key = toKey(array.get(i).orElseThrow(Unconvertible::new), open);
                    if (key == null) {
                        valid = false;
                    } else {
                        elements.add(key);
                    }
                }
                open.removeLast();
                yield valid ? new IdbKey.ArrayKey(elements) : null;
            }
            default -> null;
        };
    }

    /// Adds `array` to the arrays being converted.
    ///
    /// @throws Unconvertible if it is one of them, they are as deep as an array may nest, or it is too long
    private static void enter(JsArray array, List<JsArray> open) throws Unconvertible {
        if (array.length() > MAX_ARRAY_SIZE || open.size() >= MAX_DEPTH) {
            throw new Unconvertible();
        }
        for (var other : open) {
            if (other == array) {
                throw new Unconvertible();
            }
        }
        open.add(array);
    }

    /// Thrown for an array that makes the whole value it is in unconvertible, even an element of a multi-entry array:
    /// one that contains itself, nests too deep, is too long or has a hole.
    private static final class Unconvertible extends Exception {
        @Serial
        private static final long serialVersionUID = 1L;

        Unconvertible() {
            super(null, null, false, false);
        }
    }
}
