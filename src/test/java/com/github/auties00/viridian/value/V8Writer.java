package com.github.auties00.viridian.value;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/// Writes V8's wire format by hand, with Blink's envelope, for tests to build serialized values tag by tag.
public final class V8Writer {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private V8Writer() {
    }

    /// {@return a writer that starts with Blink's envelope and V8's header, as Blink writes values today}
    public static V8Writer blink() {
        return blink(21, 15, true);
    }

    /// {@return a writer that starts with Blink's envelope of `blinkVersion`, then V8's header of `v8Version`}
    public static V8Writer blink(int blinkVersion, int v8Version, boolean trailer) {
        var writer = new V8Writer();
        writer.raw(0xFF).varint(blinkVersion);
        if (trailer) {
            writer.raw(0xFE).raw(new byte[12]);
        }
        return writer.raw(0xFF).varint(v8Version);
    }

    /// {@return a writer that starts with V8's header of `version` only}
    public static V8Writer v8(int version) {
        return new V8Writer().raw(0xFF).varint(version);
    }

    /// Writes one byte.
    public V8Writer raw(int value) {
        out.write(value);
        return this;
    }

    /// Writes bytes.
    public V8Writer raw(byte[] bytes) {
        out.writeBytes(bytes);
        return this;
    }

    /// Writes a tag.
    public V8Writer tag(char tag) {
        return raw(tag);
    }

    /// Writes an unsigned varint.
    public V8Writer varint(long value) {
        while ((value & ~0x7FL) != 0) {
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
        return this;
    }

    /// Writes a little-endian double.
    public V8Writer f64(double value) {
        var bits = Double.doubleToRawLongBits(value);
        for (var shift = 0; shift < 64; shift += 8) {
            out.write((int) (bits >>> shift));
        }
        return this;
    }

    /// Writes an int32 value.
    public V8Writer int32(int value) {
        return tag('I').varint(Integer.toUnsignedLong((value << 1) ^ (value >> 31)));
    }

    /// Writes a double value.
    public V8Writer number(double value) {
        return tag('N').f64(value);
    }

    /// Writes a one-byte string value.
    public V8Writer latin1(String value) {
        var bytes = value.getBytes(StandardCharsets.ISO_8859_1);
        return tag('"').varint(bytes.length).raw(bytes);
    }

    /// Writes a two-byte string value, with the padding V8 writes to align it.
    public V8Writer twoByte(String value) {
        var bytes = new byte[value.length() * 2];
        for (var i = 0; i < value.length(); i++) {
            bytes[2 * i] = (byte) value.charAt(i);
            bytes[2 * i + 1] = (byte) (value.charAt(i) >>> 8);
        }
        if ((out.size() + 1 + varintSize(bytes.length)) % 2 != 0) {
            out.write(0);
        }
        return tag('c').varint(bytes.length).raw(bytes);
    }

    /// Writes a UTF-8 string value.
    public V8Writer utf8(String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        return tag('S').varint(bytes.length).raw(bytes);
    }

    /// Writes a string as Blink writes its own: a varint length and UTF-8.
    public V8Writer blinkString(String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        return varint(bytes.length).raw(bytes);
    }

    /// {@return the bytes written}
    public byte[] toByteArray() {
        return out.toByteArray();
    }

    private static int varintSize(long value) {
        var size = 1;
        while ((value & ~0x7FL) != 0) {
            value >>>= 7;
            size++;
        }
        return size;
    }
}
