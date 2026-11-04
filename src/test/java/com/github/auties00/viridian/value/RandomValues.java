package com.github.auties00.viridian.value;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/// Builds random value graphs from a seed, for tests to round-trip: every kind of primitive and object that needs no
/// external object, objects shared between parents, and cycles back to enclosing objects, which the builders of the
/// enclosing objects make before either is built.
public final class RandomValues {
    private static final double[] NUMBERS = {0, -0.0, 1, -1, 1.5, Double.NaN, Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY, (1 << 30) - 1, 1 << 30, -(1 << 30), -(1 << 30) - 1, 2147483647, 4294967295.0,
            9007199254740993.0, 5e-324, 1e21, 0.1};
    private static final String[] STRINGS = {"", "a", "café", "ÿ", "你好", "😀", "a\ud800b", "\udc00", "key", "0", "1",
            "01", "4294967294", "4294967295", "-1", "1.5", "length", "__proto__"};
    // Patterns JavaScript compiles with and without the u flag, as RegExp.prototype.source gives them
    private static final String[] PATTERNS = {"a+b", "café", "你", "x*", "(?:)", "a\\/b", "[a-z]"};

    private final Random random;
    // Values and builders made so far, which later values may refer back to
    private final List<Object> made;
    // Builders being filled, which their contents may refer back to
    private final List<JsBuilder<?>> enclosing;

    private RandomValues(long seed) {
        this.random = new Random(seed);
        this.made = new ArrayList<>();
        this.enclosing = new ArrayList<>();
    }

    /// {@return a random value graph of at most about `size` values}
    public static JsValue graph(long seed, int size) {
        return switch (new RandomValues(seed).value(size, 0)) {
            case JsBuilder<?> builder -> builder.build();
            case JsValue value -> value;
            default -> throw new IllegalStateException();
        };
    }

    /// {@return a random value or builder of one}
    private Object value(int budget, int depth) {
        if (budget <= 1 || depth > 12 || random.nextInt(4) == 0) {
            return leaf();
        }
        if (!made.isEmpty() && random.nextInt(6) == 0) {
            return made.get(random.nextInt(made.size()));
        }
        if (!enclosing.isEmpty() && random.nextInt(8) == 0) {
            return enclosing.get(random.nextInt(enclosing.size()));
        }
        var children = 1 + random.nextInt(Math.min(6, budget));
        var share = budget / children;
        return switch (random.nextInt(7)) {
            case 0 -> {
                var object = JsObject.builder();
                fill(object, () -> {
                    for (var i = 0; i < children; i++) {
                        switch (value(share, depth + 1)) {
                            case JsBuilder<?> builder -> object.put(key(), builder);
                            case JsValue value -> object.put(key(), value);
                            default -> throw new IllegalStateException();
                        }
                    }
                });
                yield object;
            }
            case 1 -> {
                var array = JsArray.builder();
                var length = new long[1];
                fill(array, () -> {
                    for (var i = 0; i < children; i++) {
                        var item = value(share, depth + 1);
                        switch (random.nextInt(4)) {
                            case 0 -> {
                                var index = length[0] + random.nextInt(3);
                                set(array, index, item);
                                length[0] = index + 1;
                            }
                            case 1 -> {
                                var key = arrayKey();
                                var index = JsNumbers.arrayIndex(key);
                                switch (item) {
                                    case JsBuilder<?> builder -> array.put(key, builder);
                                    case JsValue value -> array.put(key, value);
                                    default -> throw new IllegalStateException();
                                }
                                length[0] = Math.max(length[0], index + 1);
                            }
                            default -> set(array, length[0]++, item);
                        }
                    }
                    if (random.nextInt(8) == 0) {
                        set(array, 100_000 + random.nextInt(1_000_000), value(share, depth + 1));
                    }
                });
                yield array;
            }
            case 2 -> {
                var map = JsMap.builder();
                fill(map, () -> {
                    for (var i = 0; i < children; i++) {
                        var key = value(share / 2, depth + 1);
                        var entry = value(share / 2, depth + 1);
                        switch (key) {
                            case JsBuilder<?> keyBuilder when entry instanceof JsBuilder<?> entryBuilder -> map.put(keyBuilder, entryBuilder);
                            case JsBuilder<?> keyBuilder -> map.put(keyBuilder, (JsValue) entry);
                            case JsValue keyValue when entry instanceof JsBuilder<?> entryBuilder -> map.put(keyValue, entryBuilder);
                            case JsValue keyValue -> map.put(keyValue, (JsValue) entry);
                            default -> throw new IllegalStateException();
                        }
                    }
                });
                yield map;
            }
            case 3 -> {
                var set = JsSet.builder();
                fill(set, () -> {
                    for (var i = 0; i < children; i++) {
                        switch (value(share, depth + 1)) {
                            case JsBuilder<?> builder -> set.add(builder);
                            case JsValue value -> set.add(value);
                            default -> throw new IllegalStateException();
                        }
                    }
                });
                yield set;
            }
            case 4 -> {
                var error = JsError.builder(JsError.Type.values()[random.nextInt(JsError.Type.values().length)])
                        .message(random.nextBoolean() ? string() : null)
                        .stack(random.nextBoolean() ? "at " + string() : null);
                fill(error, () -> {
                    if (random.nextBoolean()) {
                        switch (value(share, depth + 1)) {
                            case JsBuilder<?> builder -> error.cause(builder);
                            case JsValue value -> error.cause(value);
                            default -> throw new IllegalStateException();
                        }
                    }
                });
                yield error;
            }
            case 5 -> {
                var numbers = JsArray.builder();
                for (var i = 0; i < children; i++) {
                    numbers.add(NUMBERS[random.nextInt(NUMBERS.length)]);
                }
                var built = numbers.build();
                made.add(built);
                yield built;
            }
            default -> views();
        };
    }

    private static void set(JsArray.Builder array, long index, Object item) {
        switch (item) {
            case JsBuilder<?> builder -> array.set(index, builder);
            case JsValue value -> array.set(index, value);
            default -> throw new IllegalStateException();
        }
    }

    /// Records `builder` as made and enclosing while `contents` fills it.
    private void fill(JsBuilder<?> builder, Runnable contents) {
        made.add(builder);
        enclosing.add(builder);
        contents.run();
        enclosing.removeLast();
    }

    private JsValue views() {
        var bytes = new byte[8 * (1 + random.nextInt(4))];
        random.nextBytes(bytes);
        var buffer = random.nextInt(3) == 0 ? JsArrayBuffer.ofResizable(bytes, bytes.length + random.nextInt(64)) : JsArrayBuffer.of(bytes);
        made.add(buffer);
        var views = JsArray.builder();
        for (var i = 0; i < 1 + random.nextInt(3); i++) {
            var kind = JsArrayBufferView.Kind.values()[random.nextInt(JsArrayBufferView.Kind.values().length)];
            var size = kind.elementSize();
            var offset = random.nextInt(bytes.length / size + 1) * size;
            JsArrayBufferView view;
            if (buffer.maxByteLength().isPresent() && random.nextBoolean()) {
                view = JsArrayBufferView.ofLengthTracking(kind, buffer, offset);
            } else {
                view = JsArrayBufferView.of(kind, buffer, offset, random.nextInt((bytes.length - offset) / size + 1) * size);
            }
            made.add(view);
            views.add(view);
        }
        if (random.nextBoolean()) {
            views.add(buffer);
        }
        var built = views.build();
        made.add(built);
        return built;
    }

    private JsValue leaf() {
        return switch (random.nextInt(12)) {
            case 0 -> JsValue.UNDEFINED;
            case 1 -> JsValue.NULL;
            case 2 -> JsValue.of(random.nextBoolean());
            case 3, 4 -> JsValue.of(random.nextBoolean() ? NUMBERS[random.nextInt(NUMBERS.length)] : random.nextInt());
            case 5 -> JsValue.of(new BigInteger(random.nextInt(200), random).multiply(BigInteger.valueOf(random.nextBoolean() ? 1 : -1)));
            case 6, 7 -> JsValue.of(string());
            case 8 -> remember(new JsDate(random.nextInt(3) == 0 ? Double.NaN : random.nextLong(-8_640_000_000_000_000L, 8_640_000_000_000_000L)));
            case 9 -> remember(new JsRegExp(PATTERNS[random.nextInt(PATTERNS.length)], random.nextBoolean() ? "gi" : "dmsuy"));
            case 10 -> remember(switch (random.nextInt(4)) {
                case 0 -> JsWrapper.of(JsValue.of(random.nextBoolean()));
                case 1 -> JsWrapper.of(JsValue.of(NUMBERS[random.nextInt(NUMBERS.length)]));
                case 2 -> JsWrapper.of(JsValue.of(BigInteger.valueOf(random.nextLong())));
                default -> JsWrapper.of(JsValue.of(string()));
            });
            default -> remember(new JsDomPoint(random.nextInt(9), random.nextDouble(), -0.0, 1, random.nextBoolean()));
        };
    }

    private JsValue remember(JsValue object) {
        made.add(object);
        return object;
    }

    /// {@return a key for a property of an array: never its length, nor the last index, past which it cannot grow}
    private String arrayKey() {
        var key = key();
        return key.equals("length") || key.equals("4294967294") ? "size" : key;
    }

    private String key() {
        return random.nextInt(3) == 0 ? Integer.toString(random.nextInt(2000)) : string();
    }

    private String string() {
        if (random.nextInt(5) == 0) {
            var characters = new char[random.nextInt(40)];
            for (var i = 0; i < characters.length; i++) {
                characters[i] = (char) (random.nextBoolean() ? random.nextInt(0x100) : random.nextInt(0x10000));
            }
            return new String(characters);
        }
        return STRINGS[random.nextInt(STRINGS.length)];
    }
}
