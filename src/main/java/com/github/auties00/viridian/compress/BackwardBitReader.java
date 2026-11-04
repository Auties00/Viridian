package com.github.auties00.viridian.compress;

import java.io.IOException;

/// Reads a zstd backward bit stream, the form of the FSE-coded Huffman weights and of the sequences of a block.
///
/// The encoder writes its bits forwards, least significant first, and then a single set bit, so the stream is read
/// backwards from its last set bit: the decoder skips that marker and the zeros above it, and takes each value from
/// the most significant bits that remain. Past the first byte the stream reads as zeros, which the format relies on:
/// a decoder learns that it has read everything only by overreading.
///
/// The bits are served from a 64-bit container holding the 8 bytes at [#position]; [#reload()] walks the container
/// back through the stream. After a reload at least 57 unread bits are in the container, so callers read up to that
/// many between reloads. [#read(int)] trusts its caller and never fails: once the stream is overread its values are
/// meaningless but stay within their bit width, and [#finished()] reports the overread.
final class BackwardBitReader {
    /// The bytes holding the stream.
    private final byte[] input;

    /// The index of the stream's first byte.
    private final int start;

    /// The index of the first of the 8 bytes in [#container]; it equals [#start] for a stream shorter than 8 bytes.
    private int position;

    /// The bytes at [#position], little-endian, so the latest unread bit is the most significant one.
    private long container;

    /// The bits of [#container] consumed from its top: 64 once the stream is exhausted, more once it is overread.
    private int consumed;

    /// Opens the stream in `input[start, end)`, skipping the end marker.
    ///
    /// @param name what the stream holds, for error messages
    /// @throws IOException if the stream is empty or its last byte is zero, so it has no end marker
    BackwardBitReader(byte[] input, int start, int end, String name) throws IOException {
        if (end <= start) {
            throw new IOException("The " + name + " bit stream is empty");
        }
        var last = input[end - 1] & 0xFF;
        if (last == 0) {
            throw new IOException("The " + name + " bit stream does not end with a set bit");
        }
        this.input = input;
        this.start = start;
        // The marker and the zeros above it: 8 minus the index of the highest set bit
        var skipped = Integer.numberOfLeadingZeros(last) - 23;
        var length = end - start;
        if (length >= 8) {
            this.position = end - 8;
            this.container = LittleEndian.readLong(input, position);
            this.consumed = skipped;
        } else {
            // The bytes sit at the bottom of the container; the missing ones above them count as consumed
            var value = 0L;
            for (var i = 0; i < length; i++) {
                value |= (input[start + i] & 0xFFL) << (8 * i);
            }
            this.position = start;
            this.container = value;
            this.consumed = skipped + (8 - length) * 8;
        }
    }

    /// {@return the next `count` bits of the stream, from 0 to 31, as an unsigned value}
    int read(int count) {
        // Shifting by 1 and then by 63 - count keeps a count of 0 from becoming a shift by 64, which Java masks to 0
        var value = (int) (container << consumed >>> 1 >>> (63 - count));
        consumed += count;
        return value;
    }

    /// Moves the container back over the bytes it has fully consumed, so that it holds at least 57 unread bits, or
    /// every bit left in the stream.
    ///
    /// @return whether the stream has been overread, which the container can no longer cover
    boolean reload() {
        if (consumed > 64) {
            return true;
        }
        var back = Math.min(consumed >>> 3, position - start);
        if (back > 0) {
            position -= back;
            consumed -= back << 3;
            container = LittleEndian.readLong(input, position);
        }
        return false;
    }

    /// Reports whether the stream has been read to exactly its first bit, neither short of it nor past it.
    ///
    /// @return `true` when every bit has been consumed and none overread
    boolean finished() {
        reload();
        return position == start && consumed == 64;
    }
}
