package com.github.auties00.viridian.value;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;

/// A view of a [JsArrayBuffer]: a typed array, such as a `Uint8Array`, or a `DataView`.
///
/// The view refers to its buffer, which other views may share; [#toByteArray()] copies the range it covers. Typed
/// arrays read their elements little-endian, the byte order of every platform Chromium serializes them on.
public final class JsArrayBufferView implements JsValue {
    private final Kind kind;
    private final JsArrayBuffer buffer;
    private final int byteOffset;
    private final int byteLength;
    private final boolean lengthTracking;

    JsArrayBufferView(Kind kind, JsArrayBuffer buffer, int byteOffset, int byteLength, boolean lengthTracking) {
        this.kind = kind;
        this.buffer = buffer;
        this.byteOffset = byteOffset;
        this.byteLength = byteLength;
        this.lengthTracking = lengthTracking;
    }

    /// {@return a `Uint8Array` over a new buffer holding a copy of `bytes`, as `new Uint8Array(bytes)` makes}
    public static JsArrayBufferView uint8(byte[] bytes) {
        return of(Kind.UINT8, JsArrayBuffer.of(bytes));
    }

    /// {@return a view of `kind` over all of `buffer`, of a fixed length even if the buffer is resizable}
    ///
    /// @throws IllegalArgumentException if the buffer's length is not a multiple of the kind's element size
    public static JsArrayBufferView of(Kind kind, JsArrayBuffer buffer) {
        return of(kind, buffer, 0, buffer.byteLength());
    }

    /// {@return a view of `kind` over the `byteLength` bytes of `buffer` from `byteOffset`, of a fixed length even if
    /// the buffer is resizable}
    ///
    /// The range is in bytes for every kind, so a `Uint32Array` of 2 elements at the start of a buffer is
    /// `of(Kind.UINT32, buffer, 0, 8)`.
    ///
    /// @throws IllegalArgumentException if the range is not within the buffer, or the offset or the length is not a
    ///                                  multiple of the kind's element size
    public static JsArrayBufferView of(Kind kind, JsArrayBuffer buffer, int byteOffset, int byteLength) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(buffer, "buffer");
        if (byteOffset < 0 || byteLength < 0 || byteOffset > buffer.byteLength() || byteLength > buffer.byteLength() - byteOffset) {
            throw new IllegalArgumentException("A view of " + byteLength + " bytes at " + byteOffset + " is not within a buffer of " + buffer.byteLength());
        }
        checkAlignment(kind, byteOffset, byteLength);
        return new JsArrayBufferView(kind, buffer, byteOffset, byteLength, false);
    }

    /// {@return a view of `kind` over a resizable `buffer` from `byteOffset`, which follows the buffer's length, as
    /// `new Uint8Array(buffer, byteOffset)` makes over a resizable buffer}
    ///
    /// @throws IllegalArgumentException if the buffer is not resizable, the offset is past its end, or the offset is
    ///                                  not a multiple of the kind's element size
    public static JsArrayBufferView ofLengthTracking(Kind kind, JsArrayBuffer buffer, int byteOffset) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(buffer, "buffer");
        if (buffer.maxByteLength().isEmpty()) {
            throw new IllegalArgumentException("Only a view of a resizable buffer can follow its length");
        }
        if (byteOffset < 0 || byteOffset > buffer.byteLength()) {
            throw new IllegalArgumentException("A view at " + byteOffset + " is not within a buffer of " + buffer.byteLength());
        }
        checkAlignment(kind, byteOffset, 0);
        return new JsArrayBufferView(kind, buffer, byteOffset, 0, true);
    }

    private static void checkAlignment(Kind kind, int byteOffset, int byteLength) {
        if (byteOffset % kind.elementSize() != 0 || byteLength % kind.elementSize() != 0) {
            throw new IllegalArgumentException("A " + kind.typeName() + " needs an offset and a length that are multiples of "
                    + kind.elementSize() + " bytes: " + byteOffset + " and " + byteLength);
        }
    }

    /// {@return the kind of view}
    public Kind kind() {
        return kind;
    }

    /// {@return the buffer the view covers}
    public JsArrayBuffer buffer() {
        return buffer;
    }

    /// {@return the offset in the buffer of the first byte the view covers}
    public int byteOffset() {
        return byteOffset;
    }

    /// {@return how many bytes the view covers: for a length-tracking view, the whole elements its buffer holds past
    /// its offset}
    public int byteLength() {
        // V8 records 0 for a length-tracking view, whose length is its buffer's
        return lengthTracking ? Math.max(0, buffer.byteLength() - byteOffset) / kind.elementSize() * kind.elementSize() : byteLength;
    }

    /// {@return how many elements the view has: its bytes for a `DataView`}
    public int length() {
        return byteLength() / kind.elementSize();
    }

    /// {@return whether the view follows the length of its resizable buffer}
    public boolean lengthTracking() {
        return lengthTracking;
    }

    /// {@return a copy of the bytes the view covers}
    public byte[] toByteArray() {
        return Arrays.copyOfRange(buffer.bytes(), byteOffset, byteOffset + byteLength());
    }

    /// {@return a read-only, little-endian view of the bytes the view covers}
    public ByteBuffer asByteBuffer() {
        return ByteBuffer.wrap(buffer.bytes(), byteOffset, byteLength()).slice().asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }

    @Override
    public String toString() {
        return kind.typeName() + "(" + length() + ")";
    }

    /// The kinds of view, with their JavaScript constructor and element size.
    public enum Kind {
        /// `Int8Array`.
        INT8("Int8Array", 1),
        /// `Uint8Array`.
        UINT8("Uint8Array", 1),
        /// `Uint8ClampedArray`.
        UINT8_CLAMPED("Uint8ClampedArray", 1),
        /// `Int16Array`.
        INT16("Int16Array", 2),
        /// `Uint16Array`.
        UINT16("Uint16Array", 2),
        /// `Int32Array`.
        INT32("Int32Array", 4),
        /// `Uint32Array`.
        UINT32("Uint32Array", 4),
        /// `Float16Array`.
        FLOAT16("Float16Array", 2),
        /// `Float32Array`.
        FLOAT32("Float32Array", 4),
        /// `Float64Array`.
        FLOAT64("Float64Array", 8),
        /// `BigInt64Array`.
        BIGINT64("BigInt64Array", 8),
        /// `BigUint64Array`.
        BIGUINT64("BigUint64Array", 8),
        /// `DataView`.
        DATA_VIEW("DataView", 1);

        private final String typeName;
        private final int elementSize;

        Kind(String typeName, int elementSize) {
            this.typeName = typeName;
            this.elementSize = elementSize;
        }

        /// {@return the name of the JavaScript constructor}
        public String typeName() {
            return typeName;
        }

        /// {@return how many bytes an element takes: 1 for a `DataView`}
        public int elementSize() {
            return elementSize;
        }
    }
}
