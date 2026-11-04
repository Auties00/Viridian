package com.github.auties00.viridian.value;

import com.github.auties00.viridian.ViridianException;
import com.github.auties00.viridian.io.ByteCursor;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.ToIntFunction;

/// Decodes one serialized script value: Blink's version envelope, V8's header, then V8's value with Blink's host
/// objects.
///
/// Object ids follow V8's order exactly, since back-references name objects by them: an id is taken when V8's
/// deserializer takes it, which for containers is before their contents are read and for dates, views and
/// array-buffer views after their own fields are.
///
/// @spec https://github.com/v8/v8/blob/main/src/objects/value-serializer.cc V8 value serializer
/// @spec https://chromium.googlesource.com/chromium/src/+/main/third_party/blink/renderer/bindings/core/v8/serialization/ Blink script value serialization
final class V8Decoder {
    private static final int VERSION_TAG = 0xFF;
    // V8's latest wire format version (kLatestVersion)
    private static final int LATEST_V8_VERSION = 16;
    // Blink's latest wire format version (SerializedScriptValue::kWireFormatVersion)
    private static final int LATEST_BLINK_VERSION = 21;
    // Below this Blink version, Blink and V8 share one version header
    private static final int SEPARATE_ENVELOPE_VERSION = 16;
    private static final int TRAILER_OFFSET_TAG = 0xFE;
    // The trailer offset tag, an 8-byte offset and a 4-byte size
    private static final int TRAILER_OFFSET_SIZE = 1 + 8 + 4;
    // Deeper values are rejected rather than risk the stack; V8 itself bounds them by its stack
    private static final int MAX_DEPTH = 2048;

    private final ByteCursor input;
    // The blobs and files stored beside the value, which Blink's blob and file indexes count
    private final List<? extends JsBlob> blobInfos;
    // The File System Access tokens stored beside the value, which a handle's index counts, or null if not given
    private final List<byte[]> tokens;
    private final List<JsValue> objects;
    // Version 13 data was written with view flags V8 13 did not announce; V8 retries such data reading them
    private final boolean version13Views;
    private int v8Version;
    private int blinkVersion;
    private int depth;

    private V8Decoder(byte[] data, int start, int end, List<? extends JsBlob> blobs, List<byte[]> tokens, boolean version13Views) {
        this.input = new ByteCursor(data, start, end);
        this.blobInfos = blobs;
        this.tokens = tokens;
        this.objects = new ArrayList<>();
        this.version13Views = version13Views;
    }

    /// {@return the value `data[start, end)` holds}
    ///
    /// @param blobs  the blobs and files stored beside the value
    /// @param tokens the File System Access tokens stored beside it, or `null` if they are not known
    static JsValue decode(byte[] data, int start, int end, List<? extends JsBlob> blobs, List<byte[]> tokens) throws ViridianException {
        var decoder = new V8Decoder(data, start, end, blobs, tokens, false);
        try {
            return decoder.run();
        } catch (IOException failure) {
            if (decoder.v8Version == 13) {
                try {
                    return new V8Decoder(data, start, end, blobs, tokens, true).run();
                } catch (IOException ignored) {
                    // The data is damaged either way: report the failure of the regular reading
                }
            }
            throw failure instanceof ViridianException viridian ? viridian
                    : new ViridianException(ViridianException.Reason.CORRUPTED, "The serialized value does not decode: " + failure.getMessage(), failure);
        }
    }

    private JsValue run() throws IOException {
        readEnvelope();
        return readObject();
    }

    /// Reads Blink's envelope, if the value has one of its own, then V8's header.
    private void readEnvelope() throws IOException {
        if (input.hasRemaining() && input.peek() == VERSION_TAG) {
            var start = input.position();
            input.u8();
            var version = input.varint32();
            if (Integer.compareUnsigned(version, SEPARATE_ENVELOPE_VERSION) >= 0) {
                if (Integer.compareUnsigned(version, LATEST_BLINK_VERSION) > 0) {
                    throw unsupported("Blink serialization version " + Integer.toUnsignedString(version) + " is newer than this reader knows");
                }
                blinkVersion = version;
                if (input.hasRemaining() && input.peek() == TRAILER_OFFSET_TAG) {
                    input.skip(TRAILER_OFFSET_SIZE);
                }
            } else {
                // A single envelope that V8's header reading takes as its own
                input.position(start);
            }
        }
        if (input.hasRemaining() && input.peek() == VERSION_TAG) {
            input.u8();
            v8Version = input.varint32();
            if (Integer.compareUnsigned(v8Version, LATEST_V8_VERSION) > 0) {
                throw unsupported("V8 serialization version " + Integer.toUnsignedString(v8Version) + " is newer than this reader knows");
            }
        }
        if (blinkVersion == 0) {
            blinkVersion = v8Version;
        }
    }

    /// {@return the next tag, past any padding}
    private int readTag() throws IOException {
        int tag;
        do {
            tag = input.u8();
        } while (tag == 0);
        return tag;
    }

    /// {@return the next tag past any padding without consuming the tag, or -1 at the end of the data}
    private int peekTag() throws IOException {
        while (input.hasRemaining() && input.peek() == 0) {
            input.u8();
        }
        return input.hasRemaining() ? input.peek() : -1;
    }

    /// {@return the next value, with the view that follows it if it is an array buffer}
    private JsValue readObject() throws IOException {
        if (++depth > MAX_DEPTH) {
            throw corrupted("The value is nested more than " + MAX_DEPTH + " levels deep");
        }
        try {
            var value = readValue();
            if (value instanceof JsArrayBuffer buffer && peekTag() == 'V') {
                readTag();
                value = readView(buffer);
            }
            return value;
        } finally {
            depth--;
        }
    }

    private JsValue readValue() throws IOException {
        var tag = readTag();
        return switch (tag) {
            case '?' -> {
                // An object count V8 writes for verification, which a reader may skip
                input.varint32();
                yield readObject();
            }
            case '_' -> JsValue.UNDEFINED;
            case '0' -> JsValue.NULL;
            case 'T' -> JsBoolean.TRUE;
            case 'F' -> JsBoolean.FALSE;
            case 'I' -> {
                var raw = input.varint32();
                yield new JsNumber((raw >>> 1) ^ -(raw & 1));
            }
            case 'U' -> new JsNumber(Integer.toUnsignedLong(input.varint32()));
            case 'N' -> new JsNumber(input.f64le());
            case 'Z' -> new JsBigInt(readBigInt());
            case 'S', '"', 'c' -> new JsString(readStringBody(tag));
            case '^' -> readReference();
            case 'o' -> readJsObject();
            case 'a' -> readSparseArray();
            case 'A' -> readDenseArray();
            case 'D' -> {
                var date = new JsDate(input.f64le());
                objects.add(date);
                yield date;
            }
            case 'y', 'x', 'n', 'z', 's' -> readWrapper(tag);
            case 'R' -> readRegExp();
            case ';' -> readMap();
            case '\'' -> readSet();
            case 'B' -> readArrayBuffer(false, false);
            case '~' -> readArrayBuffer(true, false);
            case 'C' -> readArrayBuffer(false, true);
            case 'E', 't', 'u', 'w', 'm' -> throw unsupported("The value holds a shared or transferred object (tag '"
                    + (char) tag + "'), which exists only while a value passes between contexts");
            case 'r' -> readError();
            case '\\' -> readHostObject();
            default -> {
                if (tag == 'p' && Integer.compareUnsigned(v8Version, 15) >= 0) {
                    throw unsupported("The value holds a shared object, which exists only while a value passes between contexts");
                }
                if (Integer.compareUnsigned(v8Version, 13) < 0) {
                    // Before version 13, host objects had no tag of their own
                    input.position(input.position() - 1);
                    yield readHostObject();
                }
                throw corrupted("Unknown value tag 0x" + Integer.toHexString(tag) + " at offset " + (input.position() - 1));
            }
        };
    }

    private JsValue readReference() throws IOException {
        var id = input.varint32();
        if (id < 0 || id >= objects.size() || objects.get(id) == null) {
            throw corrupted("A back-reference names object " + Integer.toUnsignedString(id) + ", which is not decoded yet");
        }
        return objects.get(id);
    }

    /// {@return the characters of a string of `tag`, whose tag is already read}
    private String readStringBody(int tag) throws IOException {
        var length = input.varintLength();
        return switch (tag) {
            case '"' -> new String(input.bytes(length), StandardCharsets.ISO_8859_1);
            case 'S' -> new String(input.bytes(length), StandardCharsets.UTF_8);
            case 'c' -> {
                if (length % 2 != 0) {
                    throw corrupted("A two-byte string has an odd length of " + length + " bytes");
                }
                input.require(length, "a string");
                var data = input.data();
                var start = input.position();
                // Decoded by hand, since a charset decoder would replace lone surrogates that JavaScript keeps
                var characters = new char[length / 2];
                for (var i = 0; i < characters.length; i++) {
                    characters[i] = (char) ((data[start + 2 * i] & 0xFF) | (data[start + 2 * i + 1] & 0xFF) << 8);
                }
                input.skip(length);
                yield new String(characters);
            }
            default -> throw corrupted("Expected a string, found tag 0x" + Integer.toHexString(tag));
        };
    }

    /// {@return a string written as a value of its own, as versions from 12 on write them}
    private String readString() throws IOException {
        if (Integer.compareUnsigned(v8Version, 12) < 0) {
            return readStringBody('S');
        }
        if (readObject() instanceof JsString string) {
            return string.value();
        }
        throw corrupted("Expected a string");
    }

    private BigInteger readBigInt() throws IOException {
        var bitfield = input.varint32();
        // Bit 0 is the sign, bits 1 to 30 the length of the digits in bytes
        var length = (bitfield >>> 1) & 0x3FFF_FFFF;
        input.require(length, "a BigInt's digits");
        var littleEndian = input.bytes(length);
        var bigEndian = new byte[length];
        for (var i = 0; i < length; i++) {
            bigEndian[i] = littleEndian[length - 1 - i];
        }
        var magnitude = new BigInteger(1, bigEndian);
        return (bitfield & 1) != 0 ? magnitude.negate() : magnitude;
    }

    private JsObject readJsObject() throws IOException {
        var object = new JsObject();
        objects.add(object);
        var count = readProperties('{', (key, value) -> object.put(JsNumbers.propertyKey(key), value));
        expectCount("object", count, input.varint32());
        return object;
    }

    private JsArray readSparseArray() throws IOException {
        var length = Integer.toUnsignedLong(input.varint32());
        var array = JsArray.sparse(length);
        objects.add(array);
        var count = readProperties('@', (key, value) -> putArrayKey(array, key, value));
        expectCount("sparse array", count, input.varint32());
        expectLength(length, input.varint32());
        return array;
    }

    private JsArray readDenseArray() throws IOException {
        var length = Integer.toUnsignedLong(input.varint32());
        // Every element takes at least a byte, so a longer array cannot be in the data
        if (length > input.remaining()) {
            throw corrupted("A dense array of " + length + " elements is longer than the data left");
        }
        var array = JsArray.dense((int) length);
        objects.add(array);
        for (var i = 0; i < length; i++) {
            if (peekTag() == '-') {
                readTag();
                continue;
            }
            var element = readObject();
            if (Integer.compareUnsigned(v8Version, 11) < 0 && element instanceof JsUndefined) {
                // Before version 11, holes were written as undefined
                continue;
            }
            array.set(i, element);
        }
        var count = readProperties('$', (key, value) -> putArrayKey(array, key, value));
        expectCount("dense array", count, input.varint32());
        expectLength(length, input.varint32());
        return array;
    }

    /// Reads key and value pairs up to `endTag`, which it consumes.
    ///
    /// @return how many pairs it read
    private int readProperties(int endTag, BiConsumer<JsValue, JsValue> sink) throws IOException {
        var count = 0;
        while (true) {
            var tag = peekTag();
            if (tag == -1) {
                throw corrupted("The data ends inside an object's properties");
            }
            if (tag == endTag) {
                readTag();
                return count;
            }
            var key = readObject();
            if (!(key instanceof JsString) && !(key instanceof JsNumber)) {
                throw corrupted("A property key is neither a string nor a number: " + key);
            }
            sink.accept(key, readObject());
            count++;
        }
    }

    /// Stores a property of an array: an element if its key is an index below the length, a property otherwise.
    private static void putArrayKey(JsArray array, JsValue key, JsValue value) {
        var name = JsNumbers.propertyKey(key);
        var index = JsNumbers.arrayIndex(name);
        if (index >= 0 && index < array.length()) {
            array.set(index, value);
        } else {
            array.putProperty(name, value);
        }
    }

    private JsWrapper readWrapper(int tag) throws IOException {
        var id = reserve();
        var wrapper = switch (tag) {
            case 'y' -> JsWrapper.of(JsBoolean.TRUE);
            case 'x' -> JsWrapper.of(JsBoolean.FALSE);
            case 'n' -> JsWrapper.of(new JsNumber(input.f64le()));
            case 'z' -> JsWrapper.of(new JsBigInt(readBigInt()));
            default -> JsWrapper.of(new JsString(readString()));
        };
        objects.set(id, wrapper);
        return wrapper;
    }

    private JsRegExp readRegExp() throws IOException {
        var id = reserve();
        var source = readString();
        var flags = input.varint32();
        // Bits past the nine flags V8 defines are invalid, as is the u flag with the v flag
        if ((flags & ~0x1FF) != 0 || (flags & JsNumbers.UNICODE_FLAGS) == JsNumbers.UNICODE_FLAGS) {
            throw corrupted("A regular expression has invalid flags 0x" + Integer.toHexString(flags));
        }
        var regexp = new JsRegExp(source, JsNumbers.regExpFlags(flags));
        objects.set(id, regexp);
        return regexp;
    }

    private JsMap readMap() throws IOException {
        var map = new JsMap();
        objects.add(map);
        var length = 0;
        while (true) {
            var tag = peekTag();
            if (tag == -1) {
                throw corrupted("The data ends inside a Map");
            }
            if (tag == ':') {
                readTag();
                break;
            }
            var key = readObject();
            map.put(key, readObject());
            length += 2;
        }
        expectCount("Map", length, input.varint32());
        return map;
    }

    private JsSet readSet() throws IOException {
        var set = new JsSet();
        objects.add(set);
        var length = 0;
        while (true) {
            var tag = peekTag();
            if (tag == -1) {
                throw corrupted("The data ends inside a Set");
            }
            if (tag == ',') {
                readTag();
                break;
            }
            set.add(readObject());
            length++;
        }
        expectCount("Set", length, input.varint32());
        return set;
    }

    private JsArrayBuffer readArrayBuffer(boolean resizable, boolean immutable) throws IOException {
        var id = reserve();
        var byteLength = input.varintLength();
        var maxByteLength = -1L;
        if (resizable) {
            maxByteLength = input.varint64();
            if (maxByteLength < byteLength) {
                throw corrupted("A resizable ArrayBuffer of " + byteLength + " bytes has a smaller maximum, " + maxByteLength);
            }
        }
        var buffer = new JsArrayBuffer(input.bytes(byteLength), maxByteLength, immutable);
        objects.set(id, buffer);
        return buffer;
    }

    private JsArrayBufferView readView(JsArrayBuffer buffer) throws IOException {
        var subTag = input.varint32() & 0xFF;
        var offset = input.varint64();
        var length = input.varint64();
        if (offset < 0 || length < 0 || offset > buffer.byteLength() || length > buffer.byteLength() - offset) {
            throw corrupted("A view of " + length + " bytes at " + offset + " falls outside its buffer of " + buffer.byteLength());
        }
        var flags = Integer.compareUnsigned(v8Version, 14) >= 0 || version13Views ? input.varint32() : 0;
        var id = reserve();
        var kind = switch (subTag) {
            case 'b' -> JsArrayBufferView.Kind.INT8;
            case 'B' -> JsArrayBufferView.Kind.UINT8;
            case 'C' -> JsArrayBufferView.Kind.UINT8_CLAMPED;
            case 'w' -> JsArrayBufferView.Kind.INT16;
            case 'W' -> JsArrayBufferView.Kind.UINT16;
            case 'd' -> JsArrayBufferView.Kind.INT32;
            case 'D' -> JsArrayBufferView.Kind.UINT32;
            case 'h' -> JsArrayBufferView.Kind.FLOAT16;
            case 'f' -> JsArrayBufferView.Kind.FLOAT32;
            case 'F' -> JsArrayBufferView.Kind.FLOAT64;
            case 'q' -> JsArrayBufferView.Kind.BIGINT64;
            case 'Q' -> JsArrayBufferView.Kind.BIGUINT64;
            case '?' -> JsArrayBufferView.Kind.DATA_VIEW;
            default -> throw corrupted("Unknown view type 0x" + Integer.toHexString(subTag));
        };
        if (offset % kind.elementSize() != 0 || length % kind.elementSize() != 0) {
            throw corrupted("A " + kind.typeName() + " is not aligned to its " + kind.elementSize() + "-byte elements");
        }
        var lengthTracking = (flags & 1) != 0;
        var backedByResizable = (flags & 2) != 0;
        var resizable = buffer.maxByteLength().isPresent();
        if ((lengthTracking || backedByResizable) && !resizable || resizable && !backedByResizable) {
            throw corrupted("A view's flags do not match its buffer");
        }
        var view = new JsArrayBufferView(kind, buffer, (int) offset, (int) length, lengthTracking);
        objects.set(id, view);
        return view;
    }

    private JsError readError() throws IOException {
        var id = reserve();
        var tag = input.varint32() & 0xFF;
        var type = switch (tag) {
            case 'E' -> JsError.Type.EVAL;
            case 'R' -> JsError.Type.RANGE;
            case 'F' -> JsError.Type.REFERENCE;
            case 'S' -> JsError.Type.SYNTAX;
            case 'T' -> JsError.Type.TYPE;
            case 'U' -> JsError.Type.URI;
            default -> JsError.Type.ERROR;
        };
        if (type != JsError.Type.ERROR) {
            tag = input.varint32() & 0xFF;
        }
        String message = null;
        if (tag == 'm') {
            message = readString();
            tag = input.varint32() & 0xFF;
        }
        String stack = null;
        if (tag == 's') {
            stack = readString();
            tag = input.varint32() & 0xFF;
        }
        var error = new JsError(type, message, stack);
        objects.set(id, error);
        if (tag == 'c') {
            error.setCause(readObject());
            tag = input.varint32() & 0xFF;
        }
        if (tag != '.') {
            throw corrupted("An error ends with tag 0x" + Integer.toHexString(tag) + " instead of '.'");
        }
        return error;
    }

    private JsHostObject readHostObject() throws IOException {
        var id = reserve();
        var tag = input.u8();
        JsHostObject object = switch (tag) {
            case 'b' -> readBlob();
            case 'i' -> blobAt(readIndex());
            case 'f' -> readFile();
            case 'e' -> fileAt(readIndex());
            case 'l', 'L' -> {
                var length = input.varintLength();
                var files = new ArrayList<JsFile>(Math.min(length, 64));
                for (var i = 0; i < length; i++) {
                    files.add(tag == 'l' ? readFile() : fileAt(readIndex()));
                }
                yield new JsFileList(files);
            }
            case '#' -> readImageData();
            case 'g' -> readImageBitmap();
            case 'Q', 'W' -> new JsDomPoint(input.f64le(), input.f64le(), input.f64le(), input.f64le(), tag == 'W');
            case 'E', 'R' -> new JsDomRect(input.f64le(), input.f64le(), input.f64le(), input.f64le(), tag == 'R');
            case 'T' -> new JsDomQuad(readQuadPoint(), readQuadPoint(), readQuadPoint(), readQuadPoint());
            case 'I', 'O' -> new JsDomMatrix(readDoubles(6), tag == 'O');
            case 'Y', 'U' -> new JsDomMatrix(readDoubles(16), tag == 'U');
            case 'K' -> readCryptoKey();
            case 'k' -> new JsRtcCertificate(readUtf8(), readUtf8());
            case 'n', 'N' -> {
                var name = readUtf8();
                var index = input.varint32();
                byte[] token = null;
                if (tokens != null) {
                    if (index < 0 || index >= tokens.size()) {
                        throw new ViridianException(ViridianException.Reason.MISSING, "The value refers to File System Access token "
                                + Integer.toUnsignedString(index) + ", but " + tokens.size() + " are stored with it");
                    }
                    token = tokens.get(index);
                }
                yield new JsFileSystemHandle(tag == 'n' ? JsFileSystemHandle.Kind.FILE : JsFileSystemHandle.Kind.DIRECTORY, name, token);
            }
            case 'd' -> new JsDomFileSystem(input.varint32(), readUtf8(), readUtf8());
            case 'x' -> {
                var name = readUtf8();
                var message = readUtf8();
                // The stack, which Blink does not restore
                readUtf8();
                yield new JsDomException(name, message);
            }
            case 'q' -> {
                var message = readUtf8();
                readUtf8();
                // Whether a quota and a requested size follow, and their values, which the exception does not keep
                input.varint32();
                input.f64le();
                input.varint32();
                input.f64le();
                yield new JsDomException("QuotaExceededError", message);
            }
            default -> throw unsupported("The value holds a platform object (tag '" + (char) tag
                    + "') that cannot be stored, or that this reader does not support");
        };
        objects.set(id, object);
        return object;
    }

    private int readIndex() throws IOException {
        var index = input.varint32();
        if (index < 0 || index >= blobInfos.size()) {
            throw new ViridianException(ViridianException.Reason.MISSING, "The value refers to blob "
                    + Integer.toUnsignedString(index) + ", but " + blobInfos.size() + " are stored with it");
        }
        return index;
    }

    private JsBlob readBlob() throws IOException {
        // The blob's id in the page that serialized it, which has no meaning in storage
        readUtf8();
        var type = readUtf8();
        var size = input.varint64();
        return new JsBlob(type, size, null);
    }

    /// {@return the blob at `index` among those stored beside the value: the instance itself, or, for a file read
    /// as a blob, a blob of its bytes}
    private JsBlob blobAt(int index) {
        var stored = blobInfos.get(index);
        return stored instanceof JsFile ? new JsBlob(stored.type(), stored.size(), stored.source()) : stored;
    }

    /// {@return the file at `index` among those stored beside the value: the instance itself, or, for a blob read as
    /// a file, a file of its bytes without a name}
    private JsFile fileAt(int index) {
        return switch (blobInfos.get(index)) {
            case JsFile file -> file;
            case JsBlob blob -> new JsFile("", blob.type(), blob.size(), null, blob.source());
        };
    }

    private JsFile readFile() throws IOException {
        var path = readUtf8();
        var name = Integer.compareUnsigned(blinkVersion, 4) >= 0 ? readUtf8() : null;
        if (Integer.compareUnsigned(blinkVersion, 4) >= 0) {
            // The relative path, of files a directory upload picked
            readUtf8();
        }
        readUtf8();
        var type = readUtf8();
        var hasSnapshot = Integer.compareUnsigned(blinkVersion, 4) >= 0 && input.varint32() != 0;
        var size = -1L;
        Instant lastModified = null;
        if (hasSnapshot) {
            size = input.varint64();
            var modified = input.f64le();
            if (Integer.compareUnsigned(blinkVersion, 8) < 0) {
                // Older versions recorded seconds
                modified *= 1000;
            }
            if (Double.isFinite(modified)) {
                lastModified = Instant.ofEpochMilli((long) modified);
            }
        }
        if (Integer.compareUnsigned(blinkVersion, 7) >= 0) {
            // Whether the user picked the file, which only the browser acts on
            input.varint32();
        }
        if (name == null || name.isEmpty()) {
            var separator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            name = path.substring(separator + 1);
        }
        return new JsFile(name, type, size, lastModified, null);
    }

    private JsImageData readImageData() throws IOException {
        var colorSpace = JsColorSpace.SRGB;
        var pixelFormat = JsImageData.PixelFormat.RGBA_UNORM8;
        if (Integer.compareUnsigned(blinkVersion, 18) >= 0) {
            while (true) {
                var tag = input.varint32();
                if (tag == 0) {
                    break;
                }
                switch (tag) {
                    case 1 -> colorSpace = JsColorSpace.ofCode(input.varint32())
                            .orElseThrow(() -> corrupted("An ImageData has an unknown color space"));
                    case 3 -> {
                        pixelFormat = JsImageData.PixelFormat.ofCode(input.varint32());
                        if (pixelFormat == null) {
                            throw corrupted("An ImageData has an unknown pixel format");
                        }
                    }
                    default -> throw corrupted("An ImageData has the image tag " + tag + ", which only image bitmaps have");
                }
            }
        }
        var width = input.varint32();
        var height = input.varint32();
        var byteLength = input.varint64();
        if (byteLength < 0 || byteLength > input.remaining()) {
            throw corrupted("An ImageData's " + Long.toUnsignedString(byteLength) + " bytes of pixels run past the data");
        }
        return new JsImageData(width, height, colorSpace, pixelFormat, input.bytes((int) byteLength));
    }

    private JsImageBitmap readImageBitmap() throws IOException {
        var colorSpace = JsColorSpace.SRGB;
        double[] parametric = null;
        var pixelFormat = JsImageBitmap.PixelFormat.NATIVE8;
        var opaque = true;
        var originClean = 0;
        var premultiplied = 0;
        var orientation = JsImageBitmap.Orientation.TOP_LEFT;
        if (Integer.compareUnsigned(blinkVersion, 18) >= 0) {
            while (true) {
                var tag = input.varint32();
                if (tag == 0) {
                    break;
                }
                switch (tag) {
                    case 1 -> colorSpace = JsColorSpace.ofCode(input.varint32())
                            .orElseThrow(() -> corrupted("An ImageBitmap has an unknown color space"));
                    case 2 -> {
                        pixelFormat = JsImageBitmap.PixelFormat.ofCode(input.varint32());
                        if (pixelFormat == null) {
                            throw corrupted("An ImageBitmap has an unknown pixel format");
                        }
                    }
                    case 4 -> originClean = input.varint32();
                    case 5 -> premultiplied = input.varint32();
                    case 6 -> opaque = switch (input.varint32()) {
                        case 0 -> false;
                        case 1 -> true;
                        default -> throw corrupted("An ImageBitmap has an unknown opacity mode");
                    };
                    case 7 -> parametric = readDoubles(16);
                    case 8 -> {
                        var code = input.varint32();
                        if (code < 0 || code >= JsImageBitmap.Orientation.values().length) {
                            throw corrupted("An ImageBitmap has an unknown orientation");
                        }
                        orientation = JsImageBitmap.Orientation.values()[code];
                    }
                    default -> throw corrupted("An ImageBitmap has the image tag " + tag + ", which it cannot have");
                }
            }
        } else {
            originClean = input.varint32();
            premultiplied = input.varint32();
        }
        if (originClean != 1 || (premultiplied & ~1) != 0) {
            throw corrupted("An ImageBitmap's flags are invalid, or it is not origin-clean, which Blink no longer reads");
        }
        var width = input.varint32();
        var height = input.varint32();
        var byteLength = input.varintLength();
        return new JsImageBitmap(width, height, colorSpace, parametric, pixelFormat, opaque, premultiplied == 1,
                orientation, input.bytes(byteLength));
    }

    private JsDomPoint readQuadPoint() throws IOException {
        return new JsDomPoint(input.f64le(), input.f64le(), input.f64le(), input.f64le(), false);
    }

    private double[] readDoubles(int count) throws IOException {
        var values = new double[count];
        for (var i = 0; i < count; i++) {
            values[i] = input.f64le();
        }
        return values;
    }

    private JsCryptoKey readCryptoKey() throws IOException {
        var subTag = input.u8();
        var type = JsCryptoKey.Type.SECRET;
        KeyAlgorithm algorithm;
        switch (subTag) {
            case 1 -> {
                var name = find(KeyAlgorithm.Aes.Name.values(), input.varint32(), each -> each.blinkId, "AES");
                algorithm = new KeyAlgorithm.Aes(name, keyBits(input.varint32()));
            }
            case 2 -> {
                var length = keyBits(input.varint32());
                algorithm = new KeyAlgorithm.Hmac(find(KeyAlgorithm.Hash.values(), input.varint32(), hash -> hash.blinkId, "hash"), length);
            }
            case 4 -> {
                var name = find(KeyAlgorithm.RsaHashed.Name.values(), input.varint32(), each -> each.blinkId, "RSA");
                type = keyType(input.varint32());
                var modulusLength = input.varint32();
                var exponent = new BigInteger(1, input.bytes(input.varintLength()));
                var hash = find(KeyAlgorithm.Hash.values(), input.varint32(), each -> each.blinkId, "hash");
                if (modulusLength <= 0 || exponent.signum() == 0) {
                    throw corrupted("An RSA CryptoKey has a modulus of " + Integer.toUnsignedString(modulusLength) + " bits or a zero exponent");
                }
                algorithm = new KeyAlgorithm.RsaHashed(name, modulusLength, exponent, hash);
            }
            case 5 -> {
                var name = find(KeyAlgorithm.Ec.Name.values(), input.varint32(), each -> each.blinkId, "elliptic-curve");
                type = keyType(input.varint32());
                algorithm = new KeyAlgorithm.Ec(name, find(KeyAlgorithm.NamedCurve.values(), input.varint32(), curve -> curve.blinkId, "curve"));
            }
            case 6 -> algorithm = new KeyAlgorithm.Basic(find(KeyAlgorithm.Basic.Name.values(), input.varint32(), each -> each.blinkId, "parameterless"));
            case 7, 8, 9 -> {
                algorithm = new KeyAlgorithm.Basic(find(KeyAlgorithm.Basic.Name.values(), input.varint32(), each -> each.blinkId, "parameterless"));
                type = keyType(input.varint32());
            }
            default -> throw unsupported("A CryptoKey has key type " + subTag + ", which this reader does not know");
        }
        var mask = input.varint32();
        if ((mask & ~JsCryptoKey.Usage.KNOWN_BITS) != 0) {
            throw unsupported("A CryptoKey has usages 0x" + Integer.toHexString(mask) + ", which this reader does not know");
        }
        var keyData = input.bytes(input.varintLength());
        return new JsCryptoKey(type, algorithm, JsCryptoKey.usagesOf(mask), (mask & JsCryptoKey.Usage.EXTRACTABLE_BIT) != 0, keyData);
    }

    /// {@return the bits of a key `bytes` long}
    private static int keyBits(int bytes) throws ViridianException {
        // Blink refuses AES keys over 65535 bits; HMAC keys of more than 2^28 bytes cannot be in any stored value
        if (bytes <= 0 || bytes > (1 << 28)) {
            throw corrupted("A CryptoKey is " + Integer.toUnsignedString(bytes) + " bytes long");
        }
        return bytes * 8;
    }

    /// {@return the constant among `values` whose Blink id is `id`}
    ///
    /// @throws ViridianException if none is: an id Blink does not define, or one of another family of algorithms
    private static <T> T find(T[] values, int id, ToIntFunction<T> idOf, String family) throws ViridianException {
        for (var value : values) {
            if (idOf.applyAsInt(value) == id) {
                return value;
            }
        }
        if (knownAlgorithmId(id)) {
            throw corrupted("A CryptoKey of the " + family + " kind has algorithm " + id + ", which is of another kind");
        }
        throw unsupported("A CryptoKey has " + family + " algorithm " + Integer.toUnsignedString(id) + ", which this reader does not know");
    }

    /// {@return whether Blink defines `id` for an algorithm or hash}
    private static boolean knownAlgorithmId(int id) {
        for (var name : KeyAlgorithm.Aes.Name.values()) {
            if (name.blinkId == id) {
                return true;
            }
        }
        for (var name : KeyAlgorithm.RsaHashed.Name.values()) {
            if (name.blinkId == id) {
                return true;
            }
        }
        for (var name : KeyAlgorithm.Ec.Name.values()) {
            if (name.blinkId == id) {
                return true;
            }
        }
        for (var name : KeyAlgorithm.Basic.Name.values()) {
            if (name.blinkId == id) {
                return true;
            }
        }
        for (var hash : KeyAlgorithm.Hash.values()) {
            if (hash.blinkId == id) {
                return true;
            }
        }
        // HMAC, which no family but its own subtag takes
        return id == 2;
    }

    private static JsCryptoKey.Type keyType(int code) throws ViridianException {
        return switch (code) {
            case 1 -> JsCryptoKey.Type.PUBLIC;
            case 2 -> JsCryptoKey.Type.PRIVATE;
            case 3 -> JsCryptoKey.Type.SECRET;
            default -> throw corrupted("A CryptoKey has key type " + code);
        };
    }

    /// {@return a string as Blink writes its own: a varint byte length, then UTF-8}
    private String readUtf8() throws IOException {
        return new String(input.lengthPrefixed(), StandardCharsets.UTF_8);
    }

    /// {@return the id of an object decoded in several steps, which it fills in once decoded}
    private int reserve() {
        objects.add(null);
        return objects.size() - 1;
    }

    private void expectCount(String what, int count, int expected) throws ViridianException {
        if (count != expected) {
            throw corrupted("A " + what + " has " + count + " entries but records " + Integer.toUnsignedString(expected));
        }
    }

    private void expectLength(long length, int expected) throws ViridianException {
        if (length != Integer.toUnsignedLong(expected)) {
            throw corrupted("An array of length " + length + " records length " + Integer.toUnsignedString(expected));
        }
    }

    private static ViridianException corrupted(String message) {
        return new ViridianException(ViridianException.Reason.CORRUPTED, message);
    }

    private static ViridianException unsupported(String message) {
        return new ViridianException(ViridianException.Reason.UNSUPPORTED, message);
    }
}
