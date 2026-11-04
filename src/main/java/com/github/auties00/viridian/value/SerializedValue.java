package com.github.auties00.viridian.value;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/// A value serialized as Chromium stores it: the bytes, and the blobs and files they refer to by index.
///
/// The bytes are copied in and out, so a serialized value cannot change; two are equal when their bytes are, and
/// their blobs are the same instances in the same order.
///
/// @param bytes the serialized value, Blink's envelope included
/// @param blobs the blobs and files the value refers to, in the order of their indices, which is the order a store
///              keeps them beside the value in
public record SerializedValue(byte[] bytes, List<JsBlob> blobs) {
    /// Creates a serialized value, copying `bytes` and `blobs`.
    public SerializedValue {
        bytes = bytes.clone();
        blobs = List.copyOf(blobs);
    }

    /// {@return a copy of the serialized value}
    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    /// {@return the serialized value itself, for the serializer and the stores of this module}
    byte[] rawBytes() {
        return bytes;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SerializedValue value && Arrays.equals(bytes, value.bytes) && blobs.equals(value.blobs);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(bytes), blobs);
    }

    @Override
    public String toString() {
        return "SerializedValue[" + bytes.length + " bytes, " + blobs.size() + " blobs]";
    }
}
