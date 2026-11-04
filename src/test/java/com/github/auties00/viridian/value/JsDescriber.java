package com.github.auties00.viridian.value;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/// Describes decoded values in the canonical form `ci/chrome/fixture.js` describes the values Chrome reads back,
/// so that the two compare as strings. Both give an object an id when they first meet it.
public final class JsDescriber {
    private final Map<JsValue, Integer> seen = new IdentityHashMap<>();

    private JsDescriber() {
    }

    /// {@return the canonical description of `value`}
    public static String describe(JsValue value) {
        return new JsDescriber().visit(value);
    }

    /// {@return the UTF-16 code units of `value`, each as 4 lowercase hex digits}
    public static String hex16(String value) {
        var result = new StringBuilder(value.length() * 4);
        for (var i = 0; i < value.length(); i++) {
            result.append(String.format("%04x", (int) value.charAt(i)));
        }
        return result.toString();
    }

    /// {@return `value` as JavaScript writes it, `-0` included}
    public static String number(double value) {
        return value == 0 && 1 / value < 0 ? "-0" : JsNumbers.toJsString(value);
    }

    private String visit(JsValue value) {
        switch (value) {
            case JsUndefined _ -> {
                return "u";
            }
            case JsNull _ -> {
                return "n";
            }
            case JsBoolean bool -> {
                return bool.value() ? "b1" : "b0";
            }
            case JsNumber number -> {
                return "N" + number(number.value());
            }
            case JsBigInt bigInt -> {
                return "Z" + bigInt.value();
            }
            case JsString string -> {
                return "S" + hex16(string.value());
            }
            default -> {
            }
        }
        var id = seen.get(value);
        if (id != null) {
            return "@" + id;
        }
        seen.put(value, seen.size());
        return switch (value) {
            case JsArray array -> {
                var result = new StringBuilder("A").append(array.length()).append('[');
                for (var entry : array.presentElements().entrySet()) {
                    result.append(entry.getKey()).append('=').append(visit(entry.getValue())).append(';');
                }
                result.append('|');
                for (var entry : array.properties().entrySet()) {
                    result.append(hex16(entry.getKey())).append('=').append(visit(entry.getValue())).append(';');
                }
                yield result.append(']').toString();
            }
            case JsObject object -> {
                var result = new StringBuilder("O{");
                for (var entry : object.properties().entrySet()) {
                    result.append(hex16(entry.getKey())).append('=').append(visit(entry.getValue())).append(';');
                }
                yield result.append('}').toString();
            }
            case JsDate date -> "D" + number(date.time());
            case JsRegExp regExp -> "R" + hex16(regExp.source()) + "/" + regExp.flags();
            case JsMap map -> {
                var result = new StringBuilder("M{");
                for (var entry : map.entries()) {
                    result.append(visit(entry.getKey())).append("=>").append(visit(entry.getValue())).append(';');
                }
                yield result.append('}').toString();
            }
            case JsSet set -> {
                var result = new StringBuilder("T{");
                for (var entry : set.values()) {
                    result.append(visit(entry)).append(';');
                }
                yield result.append('}').toString();
            }
            case JsArrayBuffer buffer -> "B" + HexFormat.of().formatHex(buffer.toByteArray())
                    + (buffer.maxByteLength().isPresent() ? "~" + buffer.maxByteLength().getAsLong() : "");
            case JsArrayBufferView view -> "V" + view.kind().typeName() + "(" + view.byteOffset() + "," + view.byteLength() + "," + visit(view.buffer()) + ")";
            case JsWrapper wrapper -> "W" + visit(wrapper.primitive());
            case JsDomException exception -> "X" + hex16(exception.name()) + "," + hex16(exception.message());
            case JsError error -> "E" + error.type().constructorName() + "(" + hex16(error.message().orElse("")) + ")"
                    + error.cause().map(cause -> "c" + visit(cause)).orElse("");
            case JsCryptoKey key -> "K" + key.type().name().toLowerCase() + "," + key.algorithm().webCryptoName() + ","
                    + key.usages().stream().map(JsCryptoKey.Usage::webCryptoName).sorted().collect(Collectors.joining("+")) + ","
                    + (key.extractable() ? 1 : 0) + "," + (key.extractable() ? HexFormat.of().formatHex(key.keyData()) : "-");
            case JsFile file -> "F" + hex16(file.name()) + "," + hex16(file.type()) + "," + file.size() + ","
                    + file.lastModified().map(instant -> Long.toString(instant.toEpochMilli())).orElse("?") + "," + content(file.source().orElse(null));
            case JsBlob blob -> "L" + hex16(blob.type()) + "," + blob.size() + "," + content(blob.source().orElse(null));
            case JsImageData image -> "I" + image.width() + "x" + image.height() + "," + image.colorSpace().cssName() + "," + HexFormat.of().formatHex(image.pixels());
            case JsDomMatrix matrix -> {
                var values = new StringBuilder();
                for (var number : matrix.values()) {
                    values.append(values.isEmpty() ? "" : ",").append(number(number));
                }
                yield "G" + (matrix.readOnly() ? "M" : "m") + values;
            }
            case JsDomQuad quad -> "Q" + point(quad.p1()) + "|" + point(quad.p2()) + "|" + point(quad.p3()) + "|" + point(quad.p4());
            case JsDomPoint point -> "P" + (point.readOnly() ? "M" : "m") + point(point);
            case JsDomRect rect -> "C" + (rect.readOnly() ? "M" : "m") + number(rect.x()) + "," + number(rect.y()) + "," + number(rect.width()) + "," + number(rect.height());
            default -> "?" + value;
        };
    }

    private static String point(JsDomPoint point) {
        return number(point.x()) + "," + number(point.y()) + "," + number(point.z()) + "," + number(point.w());
    }

    private static String content(BlobSource source) {
        if (source == null) {
            return "-";
        }
        try (var stream = source.open()) {
            return HexFormat.of().formatHex(stream.readAllBytes());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
