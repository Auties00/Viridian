package com.github.auties00.viridian.value;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/// The structural equality of [JsValue#deepEquals(JsValue, JsValue)]: objects are paired one to one as they are met,
/// and two values are equal when every pair holds equal contents.
///
/// The comparison walks a work list rather than recursing, since values may nest deeper than a thread's stack.
final class JsEquality {
    private final IdentityHashMap<JsValue, JsValue> forward = new IdentityHashMap<>();
    private final IdentityHashMap<JsValue, JsValue> backward = new IdentityHashMap<>();
    private final ArrayDeque<JsValue[]> pending = new ArrayDeque<>();

    private JsEquality() {
    }

    static boolean deepEquals(JsValue first, JsValue second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        var equality = new JsEquality();
        if (!equality.pair(first, second)) {
            return false;
        }
        while (!equality.pending.isEmpty()) {
            var pair = equality.pending.pop();
            if (!equality.contents(pair[0], pair[1])) {
                return false;
            }
        }
        return true;
    }

    /// {@return whether `first` and `second` may be equal: equal primitives, or objects of one class paired with each
    /// other only, whose contents are queued to compare}
    private boolean pair(JsValue first, JsValue second) {
        if (first instanceof JsPrimitive || second instanceof JsPrimitive) {
            return first.equals(second);
        }
        var known = forward.get(first);
        var knownBack = backward.get(second);
        if (known != null || knownBack != null) {
            return known == second && knownBack == first;
        }
        if (first.getClass() != second.getClass()) {
            return false;
        }
        forward.put(first, second);
        backward.put(second, first);
        pending.push(new JsValue[]{first, second});
        return true;
    }

    private boolean contents(JsValue first, JsValue second) {
        return switch (first) {
            case JsObject object -> entries(object.properties(), ((JsObject) second).properties());
            case JsArray array -> {
                var other = (JsArray) second;
                yield array.length() == other.length() && entries(array.elements(), other.elements())
                        && entries(array.properties(), other.properties());
            }
            case JsMap map -> {
                var other = ((JsMap) second).entries();
                if (map.entries().size() != other.size()) {
                    yield false;
                }
                for (var i = 0; i < other.size(); i++) {
                    if (!pair(map.entries().get(i).getKey(), other.get(i).getKey())
                            || !pair(map.entries().get(i).getValue(), other.get(i).getValue())) {
                        yield false;
                    }
                }
                yield true;
            }
            case JsSet set -> {
                var other = ((JsSet) second).values();
                if (set.values().size() != other.size()) {
                    yield false;
                }
                for (var i = 0; i < other.size(); i++) {
                    if (!pair(set.values().get(i), other.get(i))) {
                        yield false;
                    }
                }
                yield true;
            }
            case JsArrayBuffer buffer -> {
                var other = (JsArrayBuffer) second;
                yield buffer.immutable() == other.immutable() && buffer.maxByteLength().equals(other.maxByteLength())
                        && Arrays.equals(buffer.bytes(), other.bytes());
            }
            case JsArrayBufferView view -> {
                var other = (JsArrayBufferView) second;
                yield view.kind() == other.kind() && view.byteOffset() == other.byteOffset() && view.byteLength() == other.byteLength()
                        && view.lengthTracking() == other.lengthTracking() && pair(view.buffer(), other.buffer());
            }
            case JsWrapper wrapper -> wrapper.primitive().equals(((JsWrapper) second).primitive());
            case JsError error -> {
                var other = (JsError) second;
                if (error.type() != other.type() || !error.message().equals(other.message()) || !error.stack().equals(other.stack())
                        || error.cause().isPresent() != other.cause().isPresent()) {
                    yield false;
                }
                yield error.cause().isEmpty() || pair(error.cause().get(), other.cause().get());
            }
            case JsFile file -> {
                var other = (JsFile) second;
                yield file.name().equals(other.name()) && file.type().equals(other.type()) && file.size() == other.size()
                        && file.lastModified().equals(other.lastModified());
            }
            case JsBlob blob -> blob.type().equals(((JsBlob) second).type()) && blob.size() == ((JsBlob) second).size();
            case JsFileList list -> {
                var other = ((JsFileList) second).files();
                if (list.files().size() != other.size()) {
                    yield false;
                }
                for (var i = 0; i < other.size(); i++) {
                    if (!pair(list.files().get(i), other.get(i))) {
                        yield false;
                    }
                }
                yield true;
            }
            case JsCryptoKey key -> {
                var other = (JsCryptoKey) second;
                yield key.type() == other.type() && key.algorithm().equals(other.algorithm()) && key.usages().equals(other.usages())
                        && key.extractable() == other.extractable() && Arrays.equals(key.rawKeyData(), other.rawKeyData());
            }
            case JsImageData image -> {
                var other = (JsImageData) second;
                yield image.width() == other.width() && image.height() == other.height() && image.colorSpace() == other.colorSpace()
                        && image.pixelFormat() == other.pixelFormat() && Arrays.equals(image.rawPixels(), other.rawPixels());
            }
            case JsImageBitmap bitmap -> {
                var other = (JsImageBitmap) second;
                yield bitmap.width() == other.width() && bitmap.height() == other.height() && bitmap.colorSpace() == other.colorSpace()
                        && Arrays.equals(bitmap.rawParametricColorSpace(), other.rawParametricColorSpace())
                        && bitmap.pixelFormat() == other.pixelFormat() && bitmap.opaque() == other.opaque()
                        && bitmap.premultiplied() == other.premultiplied() && bitmap.orientation() == other.orientation()
                        && Arrays.equals(bitmap.rawPixels(), other.rawPixels());
            }
            case JsFileSystemHandle handle -> {
                var other = (JsFileSystemHandle) second;
                yield handle.kind() == other.kind() && handle.name().equals(other.name())
                        && Arrays.equals(handle.rawToken(), other.rawToken());
            }
            // The records and the matrix, which compare by value
            default -> first.equals(second);
        };
    }

    /// {@return whether two maps hold the same keys in the same order, with values that pair}
    private <K> boolean entries(Map<K, JsValue> first, Map<K, JsValue> second) {
        if (first.size() != second.size()) {
            return false;
        }
        Iterator<Map.Entry<K, JsValue>> others = second.entrySet().iterator();
        for (var entry : first.entrySet()) {
            var other = others.next();
            if (!entry.getKey().equals(other.getKey()) || !pair(entry.getValue(), other.getValue())) {
                return false;
            }
        }
        return true;
    }
}
