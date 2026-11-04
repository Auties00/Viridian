package com.github.auties00.viridian.io;

import java.io.IOException;
import java.util.Arrays;

/// A bounds-checked reader over a range of a byte array, for the binary formats of the module.
///
/// Every read past the end of the range throws an [IOException] naming what was being read, so that a truncated or
/// hostile input fails as malformed data, never with an unchecked exception.
public final class ByteCursor {
    private final byte[] data;
    private final int end;
    private int position;

    /// Creates a cursor over `data[start, end)`.
    ///
    /// @throws IllegalArgumentException if the range is not within `data`
    public ByteCursor(byte[] data, int start, int end) {
        if (start < 0 || end > data.length || start > end) {
            throw new IllegalArgumentException("Range [" + start + ", " + end + ") is outside an array of " + data.length);
        }
        this.data = data;
        this.position = start;
        this.end = end;
    }

    /// Creates a cursor over all of `data`.
    public ByteCursor(byte[] data) {
        this(data, 0, data.length);
    }

    /// {@return the underlying array}
    public byte[] data() {
        return data;
    }

    /// {@return the position of the next byte to read}
    public int position() {
        return position;
    }

    /// Moves to `position`, which must be within the range.
    ///
    /// @throws IOException if `position` is past the end
    public void position(int position) throws IOException {
        if (position < 0 || position > end) {
            throw new IOException("Position " + position + " is outside the data");
        }
        this.position = position;
    }

    /// {@return the exclusive end of the range}
    public int end() {
        return end;
    }

    /// {@return how many bytes are left}
    public int remaining() {
        return end - position;
    }

    /// {@return whether any byte is left}
    public boolean hasRemaining() {
        return position < end;
    }

    /// {@return the next unsigned byte}
    ///
    /// @throws IOException if no byte is left
    public int u8() throws IOException {
        require(1, "a byte");
        return data[position++] & 0xFF;
    }

    /// {@return the next unsigned byte, without consuming it}
    ///
    /// @throws IOException if no byte is left
    public int peek() throws IOException {
        require(1, "a byte");
        return data[position] & 0xFF;
    }

    /// {@return a copy of the next `length` bytes}
    ///
    /// @throws IOException if `length` is negative or fewer bytes are left
    public byte[] bytes(int length) throws IOException {
        require(length, length + " bytes");
        var result = Arrays.copyOfRange(data, position, position + length);
        position += length;
        return result;
    }

    /// Skips `length` bytes.
    ///
    /// @throws IOException if `length` is negative or fewer bytes are left
    public void skip(int length) throws IOException {
        require(length, length + " bytes");
        position += length;
    }

    /// {@return the next 2 bytes as a little-endian unsigned integer}
    ///
    /// @throws IOException if fewer bytes are left
    public int u16le() throws IOException {
        require(2, "2 bytes");
        var value = (data[position] & 0xFF) | (data[position + 1] & 0xFF) << 8;
        position += 2;
        return value;
    }

    /// {@return the next 4 bytes as a little-endian integer}
    ///
    /// @throws IOException if fewer bytes are left
    public int i32le() throws IOException {
        require(4, "4 bytes");
        var value = (data[position] & 0xFF) | (data[position + 1] & 0xFF) << 8
                | (data[position + 2] & 0xFF) << 16 | (data[position + 3] & 0xFF) << 24;
        position += 4;
        return value;
    }

    /// {@return the next 8 bytes as a little-endian integer}
    ///
    /// @throws IOException if fewer bytes are left
    public long i64le() throws IOException {
        var low = i32le() & 0xFFFF_FFFFL;
        var high = i32le() & 0xFFFF_FFFFL;
        return low | high << 32;
    }

    /// {@return the next 8 bytes as a big-endian integer}
    ///
    /// @throws IOException if fewer bytes are left
    public long i64be() throws IOException {
        require(8, "8 bytes");
        long value = 0;
        for (var i = 0; i < 8; i++) {
            value = value << 8 | (data[position + i] & 0xFF);
        }
        position += 8;
        return value;
    }

    /// {@return the next 8 bytes as a little-endian IEEE 754 double}
    ///
    /// @throws IOException if fewer bytes are left
    public double f64le() throws IOException {
        return Double.longBitsToDouble(i64le());
    }

    /// {@return the next unsigned base-128 varint, least significant group first}
    ///
    /// Bits beyond the 64th are ignored, as the encoders that write these varints would never set them.
    ///
    /// @throws IOException if the varint is longer than 10 bytes or the data ends inside it
    public long varint64() throws IOException {
        long result = 0;
        for (var shift = 0; shift < 70; shift += 7) {
            var current = u8();
            if (shift < 64) {
                result |= (long) (current & 0x7F) << shift;
            }
            if ((current & 0x80) == 0) {
                return result;
            }
        }
        throw new IOException("A varint runs longer than 10 bytes");
    }

    /// {@return the next unsigned varint, truncated to its low 32 bits as V8 and Blink read theirs}
    ///
    /// @throws IOException if the varint is longer than 10 bytes or the data ends inside it
    public int varint32() throws IOException {
        return (int) varint64();
    }

    /// {@return the next unsigned varint, which must fit a non-negative `int`}
    ///
    /// @throws IOException if the varint is malformed or does not fit
    public int varintLength() throws IOException {
        var value = varint64();
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new IOException("A length of " + Long.toUnsignedString(value) + " does not fit the data");
        }
        return (int) value;
    }

    /// {@return a copy of the bytes a varint length prefixes}
    ///
    /// @throws IOException if the length is malformed or longer than what is left
    public byte[] lengthPrefixed() throws IOException {
        return bytes(varintLength());
    }

    /// Fails unless `count` bytes are left.
    ///
    /// @param what what was being read, for the message
    /// @throws IOException if `count` is negative or fewer bytes are left
    public void require(int count, String what) throws IOException {
        if (count < 0 || count > end - position) {
            throw new IOException("The data ends before " + what + " at offset " + position);
        }
    }
}
