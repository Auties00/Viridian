package com.github.auties00.viridian.value;

import java.nio.ByteBuffer;
import java.util.OptionalLong;

/// A JavaScript `ArrayBuffer`: a resizable, immutable or plain one.
///
/// Typed arrays and `DataView`s over a buffer are [JsArrayBufferView]s that refer to it.
public final class JsArrayBuffer implements JsValue {
    private final byte[] bytes;
    // -1 for a buffer that cannot be resized
    private final long maxByteLength;
    private final boolean immutable;

    JsArrayBuffer(byte[] bytes, long maxByteLength, boolean immutable) {
        this.bytes = bytes;
        this.maxByteLength = maxByteLength;
        this.immutable = immutable;
    }

    /// {@return a plain buffer holding a copy of `bytes`}
    public static JsArrayBuffer of(byte[] bytes) {
        return new JsArrayBuffer(bytes.clone(), -1, false);
    }

    /// {@return how many bytes the buffer holds}
    public int byteLength() {
        return bytes.length;
    }

    /// {@return a copy of the bytes}
    public byte[] toByteArray() {
        return bytes.clone();
    }

    /// {@return a read-only view of the bytes, positioned at zero}
    public ByteBuffer asByteBuffer() {
        return ByteBuffer.wrap(bytes).asReadOnlyBuffer();
    }

    /// {@return the length the buffer may grow to, or an empty optional if it cannot be resized}
    public OptionalLong maxByteLength() {
        return maxByteLength < 0 ? OptionalLong.empty() : OptionalLong.of(maxByteLength);
    }

    /// {@return whether the buffer is immutable: neither its bytes nor its length can change}
    public boolean immutable() {
        return immutable;
    }

    /// The bytes themselves, for views of the same package.
    byte[] bytes() {
        return bytes;
    }

    @Override
    public String toString() {
        return "ArrayBuffer(" + bytes.length + ")";
    }
}
