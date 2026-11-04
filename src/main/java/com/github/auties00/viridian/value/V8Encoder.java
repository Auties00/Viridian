package com.github.auties00.viridian.value;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/// Encodes one value as Chrome serializes it for IndexedDB: Blink's version envelope, V8's header, V8's value with
/// Blink's host objects, then Blink's trailer.
///
/// Object ids follow V8's order, since back-references name objects by them: an object takes the next id when it is
/// first met, before its contents are written, except that a view's buffer is written, and takes its id, before the
/// view. The files of a file list are written inside it and take no id, as Blink writes them.
///
/// @spec https://github.com/v8/v8/blob/main/src/objects/value-serializer.cc V8 value serializer
/// @spec https://chromium.googlesource.com/chromium/src/+/main/third_party/blink/renderer/bindings/core/v8/serialization/ Blink script value serialization
final class V8Encoder {
    private static final int VERSION_TAG = 0xFF;
    // Blink's wire format version (SerializedScriptValue::kWireFormatVersion), the one with a trailer
    private static final int BLINK_VERSION = 21;
    private static final int TRAILER_OFFSET_TAG = 0xFE;
    private static final int TRAILER_REQUIRES_INTERFACES_TAG = 0xA0;
    // V8's format version 15, which every Chrome since 100 reads; 16, of Chrome since 149, only lifts lengths past 32 bits
    private static final int V8_VERSION = 15;
    private static final int V8_VERSION_WIDE_LENGTHS = 16;
    // Where the trailer's offset follows its tag, and where V8's version is: past Blink's 15-byte envelope and a tag
    private static final int TRAILER_OFFSET_POSITION = 3;
    private static final int V8_VERSION_POSITION = 16;
    // V8's small integers on 64-bit Chrome, which compresses pointers to 31 bits; other numbers are heap doubles
    private static final int MIN_SMI = -(1 << 30);
    private static final int MAX_SMI = (1 << 30) - 1;
    // The longest BigInt V8 holds, in bits (BigInt::kMaxLengthBits)
    private static final long MAX_BIGINT_BITS = 1L << 30;
    // The deepest nesting written. V8 reads a value recursively, bounded by its thread's stack: Chrome 157 on its main
    // thread reads arrays and maps nested 1800 levels deep but fails on objects nested 1400 deep, and a value it
    // fails on reads back as null, so a deeper value is refused rather than written unreadable
    static final int MAX_DEPTH = 1000;

    private final Output output;
    private final IdentityHashMap<JsValue, Integer> ids;
    private final List<JsBlob> externals;
    // The Blink tags of the host objects written, in the order first written, which the trailer lists
    private final LinkedHashSet<Integer> requiredInterfaces;
    // Whether a length past 32 bits was written, which only version 16 allows
    private boolean wideLengths;
    private int depth;

    private V8Encoder() {
        this.output = new Output();
        this.ids = new IdentityHashMap<>();
        this.externals = new ArrayList<>();
        this.requiredInterfaces = new LinkedHashSet<>();
    }

    /// {@return `value` serialized, with the blobs and files it refers to}
    ///
    /// @throws IllegalArgumentException if the value holds a File System Access handle, or is nested more than
    ///                                  [#MAX_DEPTH] levels deep
    static SerializedValue encode(JsValue value) {
        var encoder = new V8Encoder();
        encoder.run(value);
        return new SerializedValue(encoder.output.toByteArray(), encoder.externals);
    }

    private void run(JsValue value) {
        output.u8(VERSION_TAG);
        output.varint(BLINK_VERSION);
        output.u8(TRAILER_OFFSET_TAG);
        // The trailer's offset and size, filled in once the trailer is written; zero if there is none
        output.bytes(new byte[Long.BYTES + Integer.BYTES]);
        output.u8(VERSION_TAG);
        output.varint(V8_VERSION);
        writeValue(value);
        if (!requiredInterfaces.isEmpty()) {
            var offset = output.size();
            output.u8(TRAILER_REQUIRES_INTERFACES_TAG);
            output.u32be(requiredInterfaces.size());
            for (var tag : requiredInterfaces) {
                output.u8(tag);
            }
            output.putU64be(TRAILER_OFFSET_POSITION, offset);
            output.putU32be(TRAILER_OFFSET_POSITION + Long.BYTES, output.size() - offset);
        }
        if (wideLengths) {
            output.put(V8_VERSION_POSITION, V8_VERSION_WIDE_LENGTHS);
        }
    }

    private void writeValue(JsValue value) {
        enter();
        switch (value) {
            case JsUndefined _ -> output.u8('_');
            case JsNull _ -> output.u8('0');
            case JsBoolean bool -> output.u8(bool.value() ? 'T' : 'F');
            case JsNumber number -> writeNumber(number.value());
            case JsBigInt bigInt -> {
                output.u8('Z');
                writeBigIntContents(bigInt.value());
            }
            case JsString string -> writeString(string.value());
            default -> writeReceiver(value);
        }
        depth--;
    }

    /// Counts a level of nesting, as the reader counts the values it reads.
    private void enter() {
        if (++depth > MAX_DEPTH) {
            throw new IllegalArgumentException("The value is nested more than " + MAX_DEPTH + " levels deep, more than Chrome reads back");
        }
    }

    private void writeNumber(double value) {
        if (value >= MIN_SMI && value <= MAX_SMI && value == Math.rint(value) && !(value == 0 && 1 / value < 0)) {
            var integer = (int) value;
            output.u8('I');
            output.varint(Integer.toUnsignedLong(integer << 1 ^ integer >> 31));
        } else {
            output.u8('N');
            output.f64(value);
        }
    }

    private void writeBigIntContents(BigInteger value) {
        if (value.bitLength() > MAX_BIGINT_BITS) {
            throw new IllegalArgumentException("A BigInt of " + value.bitLength() + " bits is longer than JavaScript allows");
        }
        var magnitude = value.abs().toByteArray();
        var start = magnitude[0] == 0 ? 1 : 0;
        // V8 stores 64-bit digits, little-endian
        var digitBytes = (magnitude.length - start + 7) / 8 * 8;
        output.varint(Integer.toUnsignedLong(digitBytes << 1 | (value.signum() < 0 ? 1 : 0)));
        for (var i = 0; i < digitBytes; i++) {
            var index = magnitude.length - 1 - i;
            output.u8(index >= start ? magnitude[index] : 0);
        }
    }

    /// Writes a string as V8 holds strings a page builds: one byte a character when every character fits, two bytes
    /// otherwise, aligned to two bytes from the start of the data as V8's reader expects.
    private void writeString(String value) {
        var oneByte = true;
        for (var i = 0; i < value.length() && oneByte; i++) {
            oneByte = value.charAt(i) <= 0xFF;
        }
        if (oneByte) {
            output.u8('"');
            output.varint(value.length());
            for (var i = 0; i < value.length(); i++) {
                output.u8(value.charAt(i));
            }
            return;
        }
        var byteLength = 2L * value.length();
        if ((output.size() + 1 + varintSize(byteLength)) % 2 != 0) {
            output.u8(0);
        }
        output.u8('c');
        output.varint(byteLength);
        for (var i = 0; i < value.length(); i++) {
            var character = value.charAt(i);
            output.u8(character);
            output.u8(character >>> 8);
        }
    }

    /// Writes a string that the reader reads as a value of its own: the source of a regular expression, the string
    /// of a wrapper, the message or stack of an error.
    private void writeNestedString(String value) {
        enter();
        writeString(value);
        depth--;
    }

    /// Writes a property key: an array index as a number, as V8 lists index keys, any other key as a string.
    private void writeKey(String key) {
        enter();
        var index = JsNumbers.arrayIndex(key);
        if (index >= 0) {
            writeNumber(index);
        } else {
            writeString(key);
        }
        depth--;
    }

    private void writeIndexKey(long index) {
        enter();
        writeNumber(index);
        depth--;
    }

    private void writeReceiver(JsValue value) {
        if (value instanceof JsArrayBufferView view && !ids.containsKey(view)) {
            writeReceiver(view.buffer());
        }
        var id = ids.get(value);
        if (id != null) {
            output.u8('^');
            output.varint(id);
            return;
        }
        ids.put(value, ids.size());
        switch (value) {
            case JsObject object -> writeObject(object);
            case JsArray array -> writeArray(array);
            case JsDate date -> {
                output.u8('D');
                output.f64(date.time());
            }
            case JsRegExp regExp -> {
                output.u8('R');
                writeNestedString(regExp.source());
                output.varint(JsNumbers.regExpBits(regExp.flags()));
            }
            case JsMap map -> writeMap(map);
            case JsSet set -> writeSet(set);
            case JsArrayBuffer buffer -> writeArrayBuffer(buffer);
            case JsArrayBufferView view -> writeView(view);
            case JsWrapper wrapper -> writeWrapper(wrapper);
            case JsError error -> writeError(error);
            case JsHostObject host -> {
                output.u8('\\');
                writeHostObject(host);
            }
            default -> throw new IllegalStateException("Not an object: " + value);
        }
    }

    private void writeObject(JsObject object) {
        output.u8('o');
        var properties = object.propertyMap();
        for (var entry : properties.indexed().entrySet()) {
            writeIndexKey(entry.getKey());
            writeValue(entry.getValue());
        }
        for (var entry : properties.named().entrySet()) {
            writeKey(entry.getKey());
            writeValue(entry.getValue());
        }
        output.u8('{');
        output.varint(properties.size());
    }

    /// Writes an array as V8 writes one by its elements kind: in sequence if it has no holes, by index if it has, or
    /// if it was read by index.
    ///
    /// V8 writes the elements of an array of numbers that are not all small integers as doubles, since it keeps them
    /// so; an array whose numbers are all small integers, or that holds anything else, has each element written as
    /// the value it is.
    private void writeArray(JsArray array) {
        var dense = array.denseElements();
        if (dense != null && !dense.contains(null)) {
            output.u8('A');
            output.varint(array.length());
            var doubles = holdsDoubles(dense);
            for (var element : dense) {
                if (doubles) {
                    enter();
                    output.u8('N');
                    output.f64(((JsNumber) element).value());
                    depth--;
                } else {
                    writeValue(element);
                }
            }
            var count = writeProperties(array.properties());
            output.u8('$');
            output.varint(count);
            output.varint(array.length());
            return;
        }
        output.u8('a');
        output.varint(array.length());
        var count = 0;
        if (dense != null) {
            for (var i = 0; i < dense.size(); i++) {
                if (dense.get(i) != null) {
                    writeIndexKey(i);
                    writeValue(dense.get(i));
                    count++;
                }
            }
        } else {
            for (var entry : array.sparseElements().entrySet()) {
                writeIndexKey(entry.getKey());
                writeValue(entry.getValue());
                count++;
            }
        }
        count += writeProperties(array.properties());
        output.u8('@');
        output.varint(count);
        output.varint(array.length());
    }

    /// {@return whether V8 would keep `elements` as doubles: all numbers, not all small integers}
    private static boolean holdsDoubles(List<JsValue> elements) {
        var allSmall = true;
        for (var element : elements) {
            if (!(element instanceof JsNumber number)) {
                return false;
            }
            var value = number.value();
            allSmall &= value >= MIN_SMI && value <= MAX_SMI && value == Math.rint(value) && !(value == 0 && 1 / value < 0);
        }
        return !allSmall;
    }

    private int writeProperties(Map<String, JsValue> properties) {
        for (var entry : properties.entrySet()) {
            writeKey(entry.getKey());
            writeValue(entry.getValue());
        }
        return properties.size();
    }

    private void writeMap(JsMap map) {
        output.u8(';');
        for (var entry : map.entries()) {
            writeValue(entry.getKey());
            writeValue(entry.getValue());
        }
        output.u8(':');
        output.varint(2L * map.size());
    }

    private void writeSet(JsSet set) {
        output.u8('\'');
        for (var value : set.values()) {
            writeValue(value);
        }
        output.u8(',');
        output.varint(set.size());
    }

    private void writeArrayBuffer(JsArrayBuffer buffer) {
        var bytes = buffer.bytes();
        var maxByteLength = buffer.maxByteLength();
        if (maxByteLength.isPresent()) {
            output.u8('~');
            output.varint(bytes.length);
            output.varint(maxByteLength.getAsLong());
            wideLengths |= maxByteLength.getAsLong() > 0xFFFF_FFFFL;
        } else {
            output.u8(buffer.immutable() ? 'C' : 'B');
            output.varint(bytes.length);
        }
        output.bytes(bytes);
    }

    private void writeView(JsArrayBufferView view) {
        output.u8('V');
        output.varint(switch (view.kind()) {
            case INT8 -> 'b';
            case UINT8 -> 'B';
            case UINT8_CLAMPED -> 'C';
            case INT16 -> 'w';
            case UINT16 -> 'W';
            case INT32 -> 'd';
            case UINT32 -> 'D';
            case FLOAT16 -> 'h';
            case FLOAT32 -> 'f';
            case FLOAT64 -> 'F';
            case BIGINT64 -> 'q';
            case BIGUINT64 -> 'Q';
            case DATA_VIEW -> '?';
        });
        output.varint(view.byteOffset());
        // V8 records no length for a view that follows its buffer's
        output.varint(view.lengthTracking() ? 0 : view.byteLength());
        output.varint((view.lengthTracking() ? 1 : 0) | (view.buffer().maxByteLength().isPresent() ? 2 : 0));
    }

    private void writeWrapper(JsWrapper wrapper) {
        switch (wrapper.primitive()) {
            case JsBoolean bool -> output.u8(bool.value() ? 'y' : 'x');
            case JsNumber number -> {
                output.u8('n');
                output.f64(number.value());
            }
            case JsBigInt bigInt -> {
                output.u8('z');
                writeBigIntContents(bigInt.value());
            }
            case JsString string -> {
                output.u8('s');
                writeNestedString(string.value());
            }
            case JsUndefined _, JsNull _ -> throw new IllegalStateException("A wrapper of " + wrapper.primitive());
        }
    }

    private void writeError(JsError error) {
        output.u8('r');
        switch (error.type()) {
            case EVAL -> output.varint('E');
            case RANGE -> output.varint('R');
            case REFERENCE -> output.varint('F');
            case SYNTAX -> output.varint('S');
            case TYPE -> output.varint('T');
            case URI -> output.varint('U');
            case ERROR -> {
                // The reader's default prototype
            }
        }
        if (error.message().isPresent()) {
            output.varint('m');
            writeNestedString(error.message().get());
        }
        if (error.stack().isPresent()) {
            output.varint('s');
            writeNestedString(error.stack().get());
        }
        if (error.cause().isPresent()) {
            output.varint('c');
            writeValue(error.cause().get());
        }
        output.varint('.');
    }

    private void writeHostObject(JsHostObject object) {
        switch (object) {
            case JsFile file -> {
                writeHostTag('e');
                writeFileIndex(file);
            }
            case JsBlob blob -> {
                writeHostTag('i');
                output.varint(externals.size());
                externals.add(blob);
            }
            case JsFileList list -> {
                writeHostTag('L');
                output.varint(list.files().size());
                for (var file : list.files()) {
                    writeFileIndex(file);
                }
            }
            case JsImageData image -> writeImageData(image);
            case JsImageBitmap bitmap -> writeImageBitmap(bitmap);
            case JsDomPoint point -> {
                writeHostTag(point.readOnly() ? 'W' : 'Q');
                writePoint(point);
            }
            case JsDomRect rect -> {
                writeHostTag(rect.readOnly() ? 'R' : 'E');
                output.f64(rect.x());
                output.f64(rect.y());
                output.f64(rect.width());
                output.f64(rect.height());
            }
            case JsDomQuad quad -> {
                writeHostTag('T');
                writePoint(quad.p1());
                writePoint(quad.p2());
                writePoint(quad.p3());
                writePoint(quad.p4());
            }
            case JsDomMatrix matrix -> {
                writeHostTag(matrix.is2D() ? (matrix.readOnly() ? 'O' : 'I') : (matrix.readOnly() ? 'U' : 'Y'));
                for (var value : matrix.rawValues()) {
                    output.f64(value);
                }
            }
            case JsCryptoKey key -> writeCryptoKey(key);
            case JsRtcCertificate certificate -> {
                writeHostTag('k');
                writeUtf8(certificate.privateKey());
                writeUtf8(certificate.certificate());
            }
            case JsDomFileSystem fileSystem -> {
                writeHostTag('d');
                output.varint(Integer.toUnsignedLong(fileSystem.type()));
                writeUtf8(fileSystem.name());
                writeUtf8(fileSystem.rootUrl());
            }
            case JsDomException exception -> {
                writeHostTag('x');
                writeUtf8(exception.name());
                writeUtf8(exception.message());
                // The stack, which Blink writes empty
                writeUtf8("");
            }
            case JsFileSystemHandle handle -> throw new IllegalArgumentException("The File System Access handle "
                    + handle.name() + " cannot be stored: its grant is a token only the browser that issued it holds");
        }
    }

    /// Writes a Blink host object's tag, which the trailer then lists among the interfaces the value needs.
    private void writeHostTag(int tag) {
        requiredInterfaces.add(tag);
        output.u8(tag);
    }

    private void writeFileIndex(JsFile file) {
        output.varint(externals.size());
        externals.add(file);
    }

    private void writePoint(JsDomPoint point) {
        output.f64(point.x());
        output.f64(point.y());
        output.f64(point.z());
        output.f64(point.w());
    }

    private void writeImageData(JsImageData image) {
        writeHostTag('#');
        output.varint(1);
        output.varint(image.colorSpace().code());
        output.varint(3);
        output.varint(image.pixelFormat().code());
        output.varint(0);
        output.varint(Integer.toUnsignedLong(image.width()));
        output.varint(Integer.toUnsignedLong(image.height()));
        var pixels = image.rawPixels();
        output.varint(pixels.length);
        output.bytes(pixels);
    }

    /// Writes an image bitmap's settings in the order Blink writes them, with its predefined color space where it has
    /// no parametric one, which Blink reads although it now always writes the parametric one.
    private void writeImageBitmap(JsImageBitmap bitmap) {
        writeHostTag('g');
        var parametric = bitmap.rawParametricColorSpace();
        if (parametric != null) {
            output.varint(7);
            for (var value : parametric) {
                output.f64(value);
            }
        } else {
            output.varint(1);
            output.varint(bitmap.colorSpace().code());
        }
        output.varint(2);
        output.varint(bitmap.pixelFormat().code());
        output.varint(6);
        output.varint(bitmap.opaque() ? 1 : 0);
        output.varint(4);
        output.varint(1);
        output.varint(5);
        output.varint(bitmap.premultiplied() ? 1 : 0);
        output.varint(8);
        output.varint(bitmap.orientation().ordinal());
        output.varint(0);
        output.varint(Integer.toUnsignedLong(bitmap.width()));
        output.varint(Integer.toUnsignedLong(bitmap.height()));
        var pixels = bitmap.rawPixels();
        output.varint(pixels.length);
        output.bytes(pixels);
    }

    /// Writes a key as Blink does, by the subtag of its algorithm's parameters.
    private void writeCryptoKey(JsCryptoKey key) {
        writeHostTag('K');
        switch (key.algorithm()) {
            case KeyAlgorithm.Aes(var name, var length) -> {
                output.u8(1);
                output.varint(name.blinkId);
                output.varint(length / 8);
            }
            case KeyAlgorithm.Hmac(var hash, var length) -> {
                output.u8(2);
                output.varint(length / 8);
                output.varint(hash.blinkId);
            }
            case KeyAlgorithm.RsaHashed(var name, var modulusLength, var publicExponent, var hash) -> {
                output.u8(4);
                output.varint(name.blinkId);
                output.varint(keyType(key.type()));
                output.varint(Integer.toUnsignedLong(modulusLength));
                var exponent = publicExponent.toByteArray();
                // Blink holds the exponent big-endian, without the sign byte BigInteger adds
                var start = exponent[0] == 0 ? 1 : 0;
                output.varint(exponent.length - start);
                for (var i = start; i < exponent.length; i++) {
                    output.u8(exponent[i]);
                }
                output.varint(hash.blinkId);
            }
            case KeyAlgorithm.Ec(var name, var namedCurve) -> {
                output.u8(5);
                output.varint(name.blinkId);
                output.varint(keyType(key.type()));
                output.varint(namedCurve.blinkId);
            }
            case KeyAlgorithm.Basic(var name) -> {
                switch (name) {
                    // Blink gives these their own subtags, older than the general one
                    case HKDF, PBKDF2 -> {
                        output.u8(6);
                        output.varint(name.blinkId);
                    }
                    case ED25519, X25519 -> {
                        output.u8(name == KeyAlgorithm.Basic.Name.ED25519 ? 7 : 8);
                        output.varint(name.blinkId);
                        output.varint(keyType(key.type()));
                    }
                    default -> {
                        output.u8(9);
                        output.varint(name.blinkId);
                        output.varint(keyType(key.type()));
                    }
                }
            }
        }
        output.varint(Integer.toUnsignedLong(key.usageMask()));
        var data = key.rawKeyData();
        output.varint(data.length);
        output.bytes(data);
    }

    private static int keyType(JsCryptoKey.Type type) {
        return switch (type) {
            case PUBLIC -> 1;
            case PRIVATE -> 2;
            case SECRET -> 3;
        };
    }

    /// Writes a string as Blink writes its own: a varint byte length, then UTF-8.
    ///
    /// A lone surrogate becomes U+FFFD. Blink writes one as if it were a character, but reads its strings back as
    /// strict UTF-8, so a value it wrote with one does not read back at all.
    private void writeUtf8(String value) {
        var bytes = blinkUtf8(value);
        output.varint(bytes.length);
        output.bytes(bytes);
    }

    private static byte[] blinkUtf8(String value) {
        var surrogates = false;
        for (var i = 0; i < value.length() && !surrogates; i++) {
            surrogates = Character.isSurrogate(value.charAt(i));
        }
        if (!surrogates) {
            return value.getBytes(StandardCharsets.UTF_8);
        }
        var result = new Output();
        for (var i = 0; i < value.length(); i++) {
            int character = value.charAt(i);
            if (Character.isHighSurrogate((char) character) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                character = Character.toCodePoint((char) character, value.charAt(++i));
            } else if (Character.isSurrogate((char) character)) {
                character = 0xFFFD;
            }
            if (character < 0x80) {
                result.u8(character);
            } else if (character < 0x800) {
                result.u8(0xC0 | character >> 6);
                result.u8(0x80 | character & 0x3F);
            } else if (character < 0x10000) {
                result.u8(0xE0 | character >> 12);
                result.u8(0x80 | character >> 6 & 0x3F);
                result.u8(0x80 | character & 0x3F);
            } else {
                result.u8(0xF0 | character >> 18);
                result.u8(0x80 | character >> 12 & 0x3F);
                result.u8(0x80 | character >> 6 & 0x3F);
                result.u8(0x80 | character & 0x3F);
            }
        }
        return result.toByteArray();
    }

    private static int varintSize(long value) {
        var size = 1;
        while ((value & ~0x7FL) != 0) {
            value >>>= 7;
            size++;
        }
        return size;
    }

    /// A growable byte buffer, which can patch what it already holds.
    private static final class Output {
        private byte[] data;
        private int size;

        Output() {
            this.data = new byte[256];
        }

        int size() {
            return size;
        }

        void u8(int value) {
            if (size == data.length) {
                grow(1);
            }
            data[size++] = (byte) value;
        }

        void bytes(byte[] bytes) {
            if (bytes.length > data.length - size) {
                grow(bytes.length);
            }
            System.arraycopy(bytes, 0, data, size, bytes.length);
            size += bytes.length;
        }

        void varint(long value) {
            while ((value & ~0x7FL) != 0) {
                u8((int) (value & 0x7F | 0x80));
                value >>>= 7;
            }
            u8((int) value);
        }

        void f64(double value) {
            var bits = Double.doubleToRawLongBits(value);
            for (var shift = 0; shift < Long.SIZE; shift += Byte.SIZE) {
                u8((int) (bits >>> shift));
            }
        }

        void u32be(int value) {
            for (var shift = Integer.SIZE - Byte.SIZE; shift >= 0; shift -= Byte.SIZE) {
                u8(value >>> shift);
            }
        }

        void put(int position, int value) {
            data[position] = (byte) value;
        }

        void putU64be(int position, long value) {
            for (var i = 0; i < Long.BYTES; i++) {
                data[position + i] = (byte) (value >>> (Long.SIZE - Byte.SIZE * (i + 1)));
            }
        }

        void putU32be(int position, int value) {
            for (var i = 0; i < Integer.BYTES; i++) {
                data[position + i] = (byte) (value >>> (Integer.SIZE - Byte.SIZE * (i + 1)));
            }
        }

        byte[] toByteArray() {
            return Arrays.copyOf(data, size);
        }

        private void grow(int needed) {
            var capacity = Math.max(data.length * 2L, (long) size + needed);
            if (capacity > Integer.MAX_VALUE - 8) {
                if ((long) size + needed > Integer.MAX_VALUE - 8) {
                    throw new IllegalArgumentException("The serialized value would be larger than 2 GiB");
                }
                capacity = Integer.MAX_VALUE - 8;
            }
            data = Arrays.copyOf(data, (int) capacity);
        }
    }
}
