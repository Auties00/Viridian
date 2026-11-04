package com.github.auties00.viridian.value;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/// Renders values in a JavaScript-like notation for `toString`, safely for values that contain themselves.
final class JsRendering {
    // Deeper values are cut short: a description, not a serialization
    private static final int MAX_DEPTH = 32;
    // Longer collections are cut short after this many entries
    private static final int MAX_ENTRIES = 100;

    private JsRendering() {
    }

    /// {@return `value` rendered, with `[Circular]` where it contains an object that encloses it}
    static String render(JsValue value) {
        var output = new StringBuilder();
        render(value, output, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
        return output.toString();
    }

    private static void render(JsValue value, StringBuilder output, Set<JsValue> enclosing, int depth) {
        var container = value instanceof JsObject || value instanceof JsArray || value instanceof JsMap || value instanceof JsSet;
        if (!container) {
            output.append(value);
            return;
        }
        if (enclosing.contains(value)) {
            output.append("[Circular]");
            return;
        }
        if (depth >= MAX_DEPTH) {
            output.append("[...]");
            return;
        }
        enclosing.add(value);
        try {
            switch (value) {
                case JsObject object -> renderEntries(object.properties(), output, enclosing, depth, "{", "}");
                case JsArray array -> {
                    output.append('[');
                    var shown = 0;
                    for (var entry : array.elements().entrySet()) {
                        if (shown > 0) {
                            output.append(", ");
                        }
                        if (shown++ == MAX_ENTRIES) {
                            output.append("...");
                            break;
                        }
                        if (array.isSparse()) {
                            output.append(entry.getKey()).append(": ");
                        }
                        render(entry.getValue(), output, enclosing, depth + 1);
                    }
                    if (array.elements().size() < array.length() && !array.isSparse()) {
                        output.append(shown > 0 ? ", " : "").append("<holes>");
                    }
                    output.append(']');
                }
                case JsMap map -> {
                    output.append("Map{");
                    var shown = 0;
                    for (var entry : map.entries()) {
                        if (shown > 0) {
                            output.append(", ");
                        }
                        if (shown++ == MAX_ENTRIES) {
                            output.append("...");
                            break;
                        }
                        render(entry.getKey(), output, enclosing, depth + 1);
                        output.append(" => ");
                        render(entry.getValue(), output, enclosing, depth + 1);
                    }
                    output.append('}');
                }
                case JsSet set -> {
                    output.append("Set{");
                    var shown = 0;
                    for (var element : set.values()) {
                        if (shown > 0) {
                            output.append(", ");
                        }
                        if (shown++ == MAX_ENTRIES) {
                            output.append("...");
                            break;
                        }
                        render(element, output, enclosing, depth + 1);
                    }
                    output.append('}');
                }
                default -> output.append(value);
            }
        } finally {
            enclosing.remove(value);
        }
    }

    private static void renderEntries(Map<String, JsValue> entries, StringBuilder output, Set<JsValue> enclosing, int depth,
                                      String open, String close) {
        output.append(open);
        var shown = 0;
        for (var entry : entries.entrySet()) {
            if (shown > 0) {
                output.append(", ");
            }
            if (shown++ == MAX_ENTRIES) {
                output.append("...");
                break;
            }
            output.append(entry.getKey()).append(": ");
            render(entry.getValue(), output, enclosing, depth + 1);
        }
        output.append(close);
    }
}
