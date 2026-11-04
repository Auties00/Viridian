package com.github.auties00.viridian.value;

import com.github.auties00.viridian.ViridianException;
import com.github.auties00.viridian.io.ValueAccess;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/// The value package's side of [ValueAccess].
final class ValueAccessImpl extends ValueAccess {
    @Override
    public JsBlob blob(String type, long size, BlobSource source) {
        return new JsBlob(type, size, Objects.requireNonNull(source, "source"));
    }

    @Override
    public JsFile file(String name, String type, long size, Instant lastModified, BlobSource source) {
        return new JsFile(name, type, size, lastModified, Objects.requireNonNull(source, "source"));
    }

    @Override
    public JsValue decode(byte[] data, int offset, int length, List<? extends JsBlob> blobs, List<byte[]> tokens)
            throws ViridianException {
        Objects.checkFromIndexSize(offset, length, data.length);
        return V8Decoder.decode(data, offset, offset + length, List.copyOf(blobs), List.copyOf(tokens));
    }

    @Override
    public boolean inject(JsValue value, List<String> path, JsValue key) {
        var current = value;
        for (var i = 0; i < path.size() - 1; i++) {
            var identifier = path.get(i);
            var next = ownProperty(current, identifier);
            if (next == null) {
                var created = new JsObject();
                if (!define(current, identifier, created)) {
                    return false;
                }
                next = created;
            }
            current = next;
        }
        var last = path.getLast();
        return isImplicit(current, last) || define(current, last, key);
    }

    /// {@return the own property `identifier` of `value`, or `null` if it has none or holds no properties}
    private static JsValue ownProperty(JsValue value, String identifier) {
        return switch (value) {
            case JsObject object -> object.propertyMap().get(identifier);
            case JsArray array -> {
                if (identifier.equals("length")) {
                    yield new JsNumber(array.length());
                }
                var index = JsNumbers.arrayIndex(identifier);
                yield index >= 0 ? array.get(index).orElse(null) : array.properties().get(identifier);
            }
            default -> null;
        };
    }

    /// Defines the property `identifier` of `value` as `property`, as `CreateDataProperty` does.
    ///
    /// @return whether `value` holds properties a stored value keeps: an object or an array
    private static boolean define(JsValue value, String identifier, JsValue property) {
        switch (value) {
            case JsObject object -> object.put(identifier, property);
            case JsArray array -> {
                var index = JsNumbers.arrayIndex(identifier);
                if (index >= 0) {
                    array.set(index, property);
                } else {
                    array.putProperty(identifier, property);
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /// {@return whether `identifier` names a property `value` has by its nature rather than as an own property, which
    /// an injection leaves alone}
    private static boolean isImplicit(JsValue value, String identifier) {
        return switch (value) {
            case JsString _, JsArray _ -> identifier.equals("length");
            case JsFile _ -> switch (identifier) {
                case "size", "type", "name", "lastModified", "lastModifiedDate" -> true;
                default -> false;
            };
            case JsBlob _ -> identifier.equals("size") || identifier.equals("type");
            default -> false;
        };
    }
}
