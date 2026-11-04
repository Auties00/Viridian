package com.github.auties00.viridian;

import com.github.auties00.viridian.indexeddb.IdbKeyPaths;
import com.github.auties00.viridian.value.JsValue;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/// The key path of an object store or index: where in a value its key is.
///
/// A key path is the empty string, which names the value itself, or identifiers joined by dots, such as `user.id`,
/// each an ECMAScript identifier name; a compound key path is a list of such paths, whose key is an array. Both are
/// checked when made, as `createObjectStore` and `createIndex` check them.
///
/// {@snippet lang = java :
/// IdbKeyPath id = IdbKeyPath.of("id");
/// IdbKeyPath name = IdbKeyPath.compound("last", "first");
/// Optional<IdbKey> key = id.evaluate(value);
/// }
///
/// @spec https://w3c.github.io/IndexedDB/#key-path-construct Indexed Database API 3.0: key paths
public sealed interface IdbKeyPath {
    /// {@return the key path `path`: the empty string, or identifiers joined by dots}
    ///
    /// @throws IllegalArgumentException if `path` is not a valid key path
    static Single of(String path) {
        return new Single(path);
    }

    /// {@return the compound key path of `paths`, whose key is an array of one key for each}
    ///
    /// A compound key path of one path is not the same as that path: its key is an array of one key.
    ///
    /// @throws IllegalArgumentException if there is no path, or one is not a valid key path
    static Compound compound(String... paths) {
        return new Compound(Arrays.asList(paths));
    }

    /// {@return the key this path gives `value`, or an empty optional if `value` has nothing there, or what it has is
    /// not a valid key}
    ///
    /// The path is evaluated as IndexedDB evaluates it on a value it stores: it reads own properties, and the
    /// properties values have by their nature, such as the `length` of a string or the `size` of a blob.
    default Optional<IdbKey> evaluate(JsValue value) {
        return IdbKeyPaths.extract(Objects.requireNonNull(value, "value"), this);
    }

    /// A key path of one identifier chain, such as `"user.id"`, or the empty string, the value itself.
    ///
    /// @param path the path
    record Single(String path) implements IdbKeyPath {
        /// Creates a key path.
        ///
        /// @throws IllegalArgumentException if `path` is not a valid key path
        public Single {
            requireValid(Objects.requireNonNull(path, "path"));
        }

        /// {@return the identifiers of the path, none for the empty path}
        public List<String> identifiers() {
            return path.isEmpty() ? List.of() : List.of(path.split("\\.", -1));
        }

        @Override
        public String toString() {
            return path;
        }
    }

    /// A key path of several paths, whose key is an array.
    ///
    /// @param paths the paths, at least one
    record Compound(List<String> paths) implements IdbKeyPath {
        /// Creates a key path, copying `paths`.
        ///
        /// @throws IllegalArgumentException if there is no path, or one is not a valid key path
        public Compound {
            paths = List.copyOf(paths);
            if (paths.isEmpty()) {
                throw new IllegalArgumentException("A compound key path has at least one path");
            }
            paths.forEach(IdbKeyPath::requireValid);
        }

        @Override
        public String toString() {
            return paths.toString();
        }
    }

    /// Fails unless `path` is the empty string or identifier names joined by dots.
    ///
    /// @throws IllegalArgumentException if it is not
    /// @spec https://tc39.es/ecma262/#prod-IdentifierName ECMAScript identifier names
    private static void requireValid(String path) {
        if (path.isEmpty()) {
            return;
        }
        for (var identifier : path.split("\\.", -1)) {
            if (identifier.isEmpty()) {
                throw new IllegalArgumentException("Not a valid key path: \"" + path + "\"");
            }
            for (var i = 0; i < identifier.length(); ) {
                var codePoint = identifier.codePointAt(i);
                var valid = codePoint == '$' || codePoint == '_'
                        || (i == 0 ? Character.isUnicodeIdentifierStart(codePoint)
                        : Character.isUnicodeIdentifierPart(codePoint) && !Character.isIdentifierIgnorable(codePoint)
                        || codePoint == 0x200C || codePoint == 0x200D);
                if (!valid) {
                    throw new IllegalArgumentException("Not a valid key path: \"" + path + "\"");
                }
                i += Character.charCount(codePoint);
            }
        }
    }
}
