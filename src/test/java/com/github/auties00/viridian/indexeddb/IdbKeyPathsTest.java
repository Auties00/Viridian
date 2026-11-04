package com.github.auties00.viridian.indexeddb;

import com.github.auties00.viridian.IdbIndex;
import com.github.auties00.viridian.IdbKey;
import com.github.auties00.viridian.IdbKeyPath;
import com.github.auties00.viridian.value.JsArray;
import com.github.auties00.viridian.value.JsArrayBuffer;
import com.github.auties00.viridian.value.JsArrayBufferView;
import com.github.auties00.viridian.value.JsBigInt;
import com.github.auties00.viridian.value.JsBlob;
import com.github.auties00.viridian.value.JsBoolean;
import com.github.auties00.viridian.value.JsDate;
import com.github.auties00.viridian.value.JsError;
import com.github.auties00.viridian.value.JsFile;
import com.github.auties00.viridian.value.JsNumber;
import com.github.auties00.viridian.value.JsObject;
import com.github.auties00.viridian.value.JsRegExp;
import com.github.auties00.viridian.value.JsString;
import com.github.auties00.viridian.value.JsValue;
import com.github.auties00.viridian.value.JsWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Key path evaluation and key conversion against the cases of the IndexedDB specification, and Chromium's
/// departures from it.
class IdbKeyPathsTest {
    private static JsObject object(Object... properties) {
        var map = new LinkedHashMap<String, JsValue>();
        for (var i = 0; i < properties.length; i += 2) {
            map.put((String) properties[i], (JsValue) properties[i + 1]);
        }
        return JsObject.of(map);
    }

    /// {@return an array of `elements`, where `null` is a hole}
    private static JsArray array(JsValue... elements) {
        var array = JsArray.builder();
        for (var element : elements) {
            if (element == null) {
                array.addHole();
            } else {
                array.add(element);
            }
        }
        return array.build();
    }

    private static JsArray selfContaining() {
        var array = JsArray.builder().add(number(1));
        return array.add(array).build();
    }

    private static JsString string(String value) {
        return new JsString(value);
    }

    private static JsNumber number(double value) {
        return new JsNumber(value);
    }

    private static IdbKey.NumberKey numberKey(double value) {
        return new IdbKey.NumberKey(value);
    }

    private static IdbKey.StringKey stringKey(String value) {
        return new IdbKey.StringKey(value);
    }

    private static Optional<IdbKey> extract(JsValue value, String path) {
        return IdbKeyPaths.extract(value, new IdbKeyPath.Single(path));
    }

    private static List<IdbKey> multiEntryKeys(JsValue value, String path) {
        return IdbKeyPaths.indexKeys(value, new IdbIndex("index", new IdbKeyPath.Single(path), false, true));
    }

    @Nested
    @DisplayName("evaluating a key path")
    class Evaluation {
        @Test
        @DisplayName("the empty path is the value itself")
        void empty() {
            assertEquals(Optional.of(stringKey("value")), extract(string("value"), ""));
        }

        @Test
        @DisplayName("identifiers name own properties, nested through dots")
        void nested() {
            var value = object("a", object("b", object("c", number(7))), "id", string("x"));
            assertEquals(Optional.of(numberKey(7)), extract(value, "a.b.c"));
            assertEquals(Optional.of(stringKey("x")), extract(value, "id"));
            assertEquals(Optional.empty(), extract(value, "a.missing"));
            assertEquals(Optional.empty(), extract(value, "id.c"));
        }

        @Test
        @DisplayName("a string's and an array's length, and an array's named properties")
        void lengths() {
            var items = JsArray.builder().add(string("first")).add(string("second")).put("label", string("named")).build();
            var value = object("name", string("abcd"), "items", items);
            assertEquals(Optional.of(numberKey(4)), extract(value, "name.length"));
            assertEquals(Optional.of(numberKey(2)), extract(value, "items.length"));
            assertEquals(Optional.of(stringKey("named")), extract(value, "items.label"));
            // A string primitive has no properties but its length
            assertEquals(Optional.empty(), extract(value, "name.first"));
        }

        @Test
        @DisplayName("a key path is identifier names joined by dots, as createIndex checks it")
        void validity() {
            for (var invalid : List.of("1", "items.1", "a..b", ".a", "a.", "a b", "a-b")) {
                assertThrows(IllegalArgumentException.class, () -> new IdbKeyPath.Single(invalid), invalid);
            }
            for (var valid : List.of("", "a", "$a._b", "a.b.c", "\u00e9t\u00e9", "a1")) {
                new IdbKeyPath.Single(valid);
            }
            assertThrows(IllegalArgumentException.class, () -> new IdbKeyPath.Compound(List.of()));
            assertThrows(IllegalArgumentException.class, () -> IdbKeyPath.compound("a", "1"));
        }

        @Test
        @DisplayName("a blob's size and type, and a file's name, last modification and size")
        void blobs() {
            var value = object(
                    "blob", JsBlob.of(new byte[12], "text/plain"),
                    "file", JsFile.of("a.txt", new byte[3], "text/plain", Instant.ofEpochMilli(1600000000000L)));
            assertEquals(Optional.of(numberKey(12)), extract(value, "blob.size"));
            assertEquals(Optional.of(stringKey("text/plain")), extract(value, "blob.type"));
            assertEquals(Optional.empty(), extract(value, "blob.name"));
            assertEquals(Optional.of(stringKey("a.txt")), extract(value, "file.name"));
            assertEquals(Optional.of(numberKey(1600000000000L)), extract(value, "file.lastModified"));
            assertEquals(Optional.of(new IdbKey.DateKey(1600000000000L)), extract(value, "file.lastModifiedDate"));
            assertEquals(Optional.of(numberKey(3)), extract(value, "file.size"));
        }

        @Test
        @DisplayName("the own properties of String objects, typed arrays, errors and regular expressions")
        void exotic() {
            assertEquals(Optional.of(numberKey(3)), extract(JsWrapper.of(string("abc")), "length"));
            assertEquals(Optional.empty(), extract(JsWrapper.of(number(1)), "length"));
            var buffer = JsArrayBuffer.of(new byte[]{1, 0, (byte) 0xFF, (byte) 0xFF, 5, 6, 7, 8});
            // A typed array's length is its prototype's, not an own property
            assertEquals(Optional.empty(), extract(JsArrayBufferView.of(JsArrayBufferView.Kind.UINT16, buffer, 0, 8), "length"));
            var error = JsError.builder(JsError.Type.TYPE).message("bad").cause(string("cause")).build();
            assertEquals(Optional.of(stringKey("bad")), extract(error, "message"));
            assertEquals(Optional.of(stringKey("cause")), extract(error, "cause"));
            assertEquals(Optional.empty(), extract(error, "stack"));
            assertEquals(Optional.of(numberKey(0)), extract(new JsRegExp("a", "g"), "lastIndex"));
        }

        @Test
        @DisplayName("a compound key path gives an array key, and fails if any path fails or gives no key")
        void compound() {
            var value = object("a", number(1), "b", string("two"), "c", number(Double.NaN));
            assertEquals(Optional.of(new IdbKey.ArrayKey(List.of(numberKey(1), stringKey("two")))),
                    IdbKeyPaths.extract(value, new IdbKeyPath.Compound(List.of("a", "b"))));
            assertEquals(Optional.empty(), IdbKeyPaths.extract(value, new IdbKeyPath.Compound(List.of("a", "missing"))));
            assertEquals(Optional.empty(), IdbKeyPaths.extract(value, new IdbKeyPath.Compound(List.of("a", "c"))));
        }
    }

    @Nested
    @DisplayName("converting a value to a key")
    class Conversion {
        static Stream<Arguments> keys() {
            var buffer = JsArrayBuffer.of(new byte[]{1, 2, 3, 4});
            return Stream.of(
                    Arguments.of("a number", number(-1.5), numberKey(-1.5)),
                    Arguments.of("infinity", number(Double.POSITIVE_INFINITY), numberKey(Double.POSITIVE_INFINITY)),
                    Arguments.of("a date", new JsDate(5), new IdbKey.DateKey(5)),
                    Arguments.of("a string", string("a\ud800"), stringKey("a\ud800")),
                    Arguments.of("an ArrayBuffer", buffer, new IdbKey.BinaryKey(new byte[]{1, 2, 3, 4})),
                    Arguments.of("the range a view covers", JsArrayBufferView.of(JsArrayBufferView.Kind.UINT8, buffer, 1, 2), new IdbKey.BinaryKey(new byte[]{2, 3})),
                    Arguments.of("an array of keys", array(number(1), array(string("a")), new JsDate(0)),
                            new IdbKey.ArrayKey(List.of(numberKey(1), new IdbKey.ArrayKey(List.of(stringKey("a"))), new IdbKey.DateKey(0)))),
                    Arguments.of("the same array twice", sharedTwice(), new IdbKey.ArrayKey(List.of(new IdbKey.ArrayKey(List.of()), new IdbKey.ArrayKey(List.of())))));
        }

        static Stream<Arguments> invalid() {
            return Stream.of(
                    Arguments.of("NaN", number(Double.NaN)),
                    Arguments.of("an invalid date", new JsDate(Double.NaN)),
                    Arguments.of("a boolean", JsBoolean.of(true)),
                    Arguments.of("null", JsValue.NULL),
                    Arguments.of("a BigInt", new JsBigInt(BigInteger.ONE)),
                    Arguments.of("an object", object("a", number(1))),
                    Arguments.of("a Number object", JsWrapper.of(number(1))),
                    Arguments.of("an array with an invalid element", array(number(1), object())),
                    Arguments.of("an array with a hole", array(number(1), null, number(3))),
                    Arguments.of("an array that contains itself", selfContaining()),
                    Arguments.of("an array of more than 1,000,000 elements", JsArray.builder().length(1_000_001).set(0, number(1)).build()));
        }

        private static JsArray sharedTwice() {
            var shared = array();
            return array(shared, shared);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("keys")
        @DisplayName("converts to a key")
        void converts(String name, JsValue value, IdbKey key) {
            assertEquals(Optional.of(key), extract(value, ""));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("invalid")
        @DisplayName("is not a valid key")
        void invalid(String name, JsValue value) {
            assertEquals(Optional.empty(), extract(value, ""));
        }

        @Test
        @DisplayName("arrays nest up to 2000 deep")
        void depth() {
            JsValue nested = number(1);
            for (var i = 0; i < 2000; i++) {
                nested = array(nested);
            }
            assertEquals(true, extract(nested, "").isPresent());
            assertEquals(Optional.empty(), extract(array(nested), ""));
        }
    }

    @Nested
    @DisplayName("the keys of a record in an index")
    class Indexes {
        @Test
        @DisplayName("a multi-entry index has each valid element of an array once, sorted, nested arrays included")
        void multiEntry() {
            var value = object("tags", array(string("b"), string("a"), string("b"), number(Double.NaN), object(), number(-0.0), number(0),
                    array(number(1)), array(number(Double.NaN))));
            assertEquals(List.of(numberKey(-0.0), stringKey("a"), stringKey("b"), new IdbKey.ArrayKey(List.of(numberKey(1)))), multiEntryKeys(value, "tags"));
        }

        @Test
        @DisplayName("a multi-entry index has a non-array's key, and nothing for an array with a hole, as Chromium indexes them")
        void multiEntryEdges() {
            assertEquals(List.of(stringKey("one")), multiEntryKeys(object("tags", string("one")), "tags"));
            assertEquals(List.of(), multiEntryKeys(object("tags", object()), "tags"));
            assertEquals(List.of(), multiEntryKeys(object("tags", array(string("a"), null)), "tags"));
            assertEquals(List.of(), multiEntryKeys(object(), "tags"));
        }

        @Test
        @DisplayName("an index that is not multi-entry has an array as one key")
        void single() {
            var index = new IdbIndex("index", new IdbKeyPath.Single("tags"), false, false);
            assertEquals(List.of(new IdbKey.ArrayKey(List.of(stringKey("a"), stringKey("b")))),
                    IdbKeyPaths.indexKeys(object("tags", array(string("a"), string("b"))), index));
            assertEquals(List.of(), IdbKeyPaths.indexKeys(object("tags", array(string("a"), object())), index));
        }

        @Test
        @DisplayName("in a store of generated in-line keys, the record's key stands in for the key path a value lacks")
        void generated() {
            var byId = new IdbIndex("byId", new IdbKeyPath.Single("id"), false, false);
            var pair = new IdbIndex("pair", new IdbKeyPath.Compound(List.of("id", "name")), false, false);
            var record = new Content.Record(numberKey(5), object("name", string("x")));
            var generated = Content.store("store", new IdbKeyPath.Single("id"), true, List.of(byId, pair), List.of(record));
            assertEquals(List.of(numberKey(5)), IdbKeyPaths.indexKeys(record.key(), record.value(), byId, generated.definition()));
            assertEquals(List.of(new IdbKey.ArrayKey(List.of(numberKey(5), stringKey("x")))), IdbKeyPaths.indexKeys(record.key(), record.value(), pair, generated.definition()));
            var given = Content.store("store", new IdbKeyPath.Single("id"), false, List.of(byId, pair), List.of(record));
            assertEquals(List.of(), IdbKeyPaths.indexKeys(record.key(), record.value(), byId, given.definition()));
            assertEquals(List.of(), IdbKeyPaths.indexKeys(record.key(), record.value(), pair, given.definition()));
            var withId = new Content.Record(numberKey(5), object("id", number(9), "name", string("x")));
            assertEquals(List.of(numberKey(9)), IdbKeyPaths.indexKeys(withId.key(), withId.value(), byId, generated.definition()));
            assertEquals(new ArrayList<>(List.of(new IdbKey.ArrayKey(List.of(numberKey(9), stringKey("x"))))), IdbKeyPaths.indexKeys(withId.key(), withId.value(), pair, generated.definition()));
        }
    }
}
