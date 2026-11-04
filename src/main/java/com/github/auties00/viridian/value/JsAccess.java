package com.github.auties00.viridian.value;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

/// The typed reading of a property or element shared by [JsObject] and [JsArray]: empty when the value is absent,
/// `undefined` or `null`, the value when it has the type asked for, and an exception naming both types otherwise.
final class JsAccess {
    // The largest integer every smaller one of which a double holds exactly, past which getLong refuses a number
    private static final double MAX_EXACT = 0x1p53;

    // What each type is called in error messages, for get(key, type)
    private static final Map<Class<?>, String> NAMES = Map.ofEntries(
            Map.entry(JsValue.class, "a value"), Map.entry(JsPrimitive.class, "a primitive"),
            Map.entry(JsUndefined.class, "undefined"), Map.entry(JsNull.class, "null"), Map.entry(JsBoolean.class, "a boolean"),
            Map.entry(JsNumber.class, "a number"), Map.entry(JsBigInt.class, "a BigInt"), Map.entry(JsString.class, "a string"),
            Map.entry(JsObject.class, "an object"), Map.entry(JsArray.class, "an array"), Map.entry(JsDate.class, "a Date"),
            Map.entry(JsRegExp.class, "a RegExp"), Map.entry(JsMap.class, "a Map"), Map.entry(JsSet.class, "a Set"),
            Map.entry(JsArrayBuffer.class, "an ArrayBuffer"), Map.entry(JsArrayBufferView.class, "a typed array or DataView"),
            Map.entry(JsWrapper.class, "a wrapper of a primitive"), Map.entry(JsError.class, "an Error"),
            Map.entry(JsHostObject.class, "a platform object"), Map.entry(JsCryptoKey.class, "a CryptoKey"),
            Map.entry(JsBlob.class, "a Blob"), Map.entry(JsFile.class, "a File"), Map.entry(JsFileList.class, "a FileList"),
            Map.entry(JsImageData.class, "an ImageData"), Map.entry(JsImageBitmap.class, "an ImageBitmap"),
            Map.entry(JsDomPoint.class, "a DOMPoint"), Map.entry(JsDomRect.class, "a DOMRect"), Map.entry(JsDomQuad.class, "a DOMQuad"),
            Map.entry(JsDomMatrix.class, "a DOMMatrix"), Map.entry(JsDomException.class, "a DOMException"),
            Map.entry(JsFileSystemHandle.class, "a FileSystemHandle"), Map.entry(JsRtcCertificate.class, "an RTCCertificate"),
            Map.entry(JsDomFileSystem.class, "a DOMFileSystem"));

    private JsAccess() {
    }

    /// {@return `value`, or an empty optional if it is absent (`null`), `undefined` or `null`}
    static Optional<JsValue> present(JsValue value) {
        return value == null || value instanceof JsUndefined || value instanceof JsNull ? Optional.empty() : Optional.of(value);
    }

    static Optional<String> string(JsValue value, String where) {
        return as(value, JsString.class, "a string", where).map(JsString::value);
    }

    static OptionalDouble number(JsValue value, String where) {
        var number = as(value, JsNumber.class, "a number", where);
        return number.isEmpty() ? OptionalDouble.empty() : OptionalDouble.of(number.get().value());
    }

    static OptionalLong integer(JsValue value, String where) {
        var number = as(value, JsNumber.class, "a number", where);
        if (number.isEmpty()) {
            return OptionalLong.empty();
        }
        var raw = number.get().value();
        if (raw != Math.rint(raw)) {
            throw new ArithmeticException(where + " holds " + number.get() + ", which is not an integer");
        }
        if (Math.abs(raw) > MAX_EXACT) {
            throw new ArithmeticException(where + " holds " + number.get() + ", past 2^53, where a number may have lost the"
                    + " integer it was given: use getNumber");
        }
        return OptionalLong.of((long) raw);
    }

    static OptionalInt integer32(JsValue value, String where) {
        var number = as(value, JsNumber.class, "a number", where);
        if (number.isEmpty()) {
            return OptionalInt.empty();
        }
        var raw = number.get().value();
        if (raw != Math.rint(raw) || raw < Integer.MIN_VALUE || raw > Integer.MAX_VALUE) {
            throw new ArithmeticException(where + " holds " + number.get() + ", which is not an integer an int holds");
        }
        return OptionalInt.of((int) raw);
    }

    static Optional<Boolean> bool(JsValue value, String where) {
        return as(value, JsBoolean.class, "a boolean", where).map(JsBoolean::value);
    }

    static Optional<BigInteger> bigInt(JsValue value, String where) {
        return as(value, JsBigInt.class, "a BigInt", where).map(JsBigInt::value);
    }

    static Optional<byte[]> bytes(JsValue value, String where) {
        return switch (present(value).orElse(null)) {
            case null -> Optional.empty();
            case JsArrayBuffer buffer -> Optional.of(buffer.toByteArray());
            case JsArrayBufferView view -> Optional.of(view.toByteArray());
            case JsValue other -> throw mismatch(other, "an ArrayBuffer or a view of one", where);
        };
    }

    static Optional<Instant> date(JsValue value, String where) {
        return as(value, JsDate.class, "a Date", where).flatMap(JsDate::instant);
    }

    /// {@return `value` as a `type`, or an empty optional if it is absent, `undefined` or `null`}
    ///
    /// @throws ClassCastException if it is of another type
    static <T extends JsValue> Optional<T> as(JsValue value, Class<T> type, String where) {
        return as(value, Objects.requireNonNull(type, "type"), describe(type), where);
    }

    /// {@return `value` as a `type`, or an empty optional if it is absent, `undefined` or `null`}
    ///
    /// @throws ClassCastException if it is of another type
    static <T extends JsValue> Optional<T> as(JsValue value, Class<T> type, String expected, String where) {
        var present = present(value);
        if (present.isEmpty()) {
            return Optional.empty();
        }
        if (!type.isInstance(present.get())) {
            throw mismatch(present.get(), expected, where);
        }
        return Optional.of(type.cast(present.get()));
    }

    private static ClassCastException mismatch(JsValue actual, String expected, String where) {
        return new ClassCastException(where + " holds " + describe(actual) + ", not " + expected);
    }

    /// {@return what a value of `type` is, as an error message names it: "a string", "a CryptoKey"}
    private static String describe(Class<? extends JsValue> type) {
        var name = NAMES.get(type);
        return name != null ? name : "a " + type.getSimpleName();
    }

    /// {@return what `value` is, as an error message names it: "a string", "an array", "a Uint8Array"}
    static String describe(JsValue value) {
        return switch (value) {
            case JsUndefined _ -> "undefined";
            case JsNull _ -> "null";
            case JsBoolean _ -> "a boolean";
            case JsNumber _ -> "a number";
            case JsBigInt _ -> "a BigInt";
            case JsString _ -> "a string";
            case JsObject _ -> "an object";
            case JsArray _ -> "an array";
            case JsDate _ -> "a Date";
            case JsRegExp _ -> "a RegExp";
            case JsMap _ -> "a Map";
            case JsSet _ -> "a Set";
            case JsArrayBuffer _ -> "an ArrayBuffer";
            case JsArrayBufferView view -> "a " + view.kind().typeName();
            case JsWrapper wrapper -> "a wrapper of " + describe(wrapper.primitive());
            case JsError error -> "a " + error.type().constructorName();
            case JsCryptoKey _ -> "a CryptoKey";
            case JsFile _ -> "a File";
            case JsBlob _ -> "a Blob";
            case JsFileList _ -> "a FileList";
            case JsImageData _ -> "an ImageData";
            case JsImageBitmap _ -> "an ImageBitmap";
            case JsDomPoint _ -> "a DOMPoint";
            case JsDomRect _ -> "a DOMRect";
            case JsDomQuad _ -> "a DOMQuad";
            case JsDomMatrix _ -> "a DOMMatrix";
            case JsDomException _ -> "a DOMException";
            case JsFileSystemHandle _ -> "a FileSystemHandle";
            case JsRtcCertificate _ -> "an RTCCertificate";
            case JsDomFileSystem _ -> "a DOMFileSystem";
        };
    }
}
