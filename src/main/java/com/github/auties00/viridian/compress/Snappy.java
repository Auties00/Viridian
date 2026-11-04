package com.github.auties00.viridian.compress;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/// Compresses and decompresses the raw Snappy format: the block compression of LevelDB tables, Blink's compressed
/// IndexedDB values, and the values Chromium's SQLite IndexedDB backend compresses on Android.
///
/// A raw stream is the uncompressed length as an unsigned varint, then a sequence of elements, each a literal run or
/// a copy of earlier output:
///
/// ```text
/// tag & 3 == 0   literal: length (tag >>> 2) + 1, or, for 60 to 63, 1 to 4 little-endian length bytes, plus 1
/// tag & 3 == 1   copy: length ((tag >>> 2) & 7) + 4, offset ((tag >>> 5) << 8) | next byte
/// tag & 3 == 2   copy: length (tag >>> 2) + 1, offset in the next 2 bytes, little-endian
/// tag & 3 == 3   copy: length (tag >>> 2) + 1, offset in the next 4 bytes, little-endian
/// ```
///
/// @spec https://github.com/google/snappy/blob/main/format_description.txt Snappy compressed format description
public final class Snappy {
    // The compressor works on fragments of this size, so that every copy offset fits in two bytes
    private static final int FRAGMENT = 1 << 16;
    private static final int HASH_BITS = 14;
    // How far the match finder keeps from a fragment's end, so that its 4-byte loads stay inside the fragment
    private static final int INPUT_MARGIN = 15;
    // The element tags of a literal run and of the copies with 1 and 2 offset bytes
    private static final int LITERAL = 0;
    private static final int COPY_1 = 1;
    private static final int COPY_2 = 2;

    private Snappy() {
    }

    /// {@return `input[offset, offset + length)` compressed to a raw Snappy stream}
    ///
    /// The stream is the one Google's reference compressor writes: each 64 KiB fragment is matched greedily through
    /// a hash of the 4 bytes at each position, which skips ahead faster the longer it finds no match.
    ///
    /// @throws IndexOutOfBoundsException if the range is not within `input`
    public static byte[] compress(byte[] input, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, input.length);
        var output = new byte[32 + length + length / 6];
        var written = writeVarint(output, 0, length);
        var table = new int[1 << HASH_BITS];
        for (var start = offset; start < offset + length; start += FRAGMENT) {
            Arrays.fill(table, 0);
            written = compressFragment(input, start, Math.min(FRAGMENT, offset + length - start), output, written, table);
        }
        return Arrays.copyOf(output, written);
    }

    /// Compresses one fragment into `output` from `written`, returning where its output ends.
    ///
    /// `table` maps a hash of 4 bytes to the offset in the fragment where they were last seen.
    private static int compressFragment(byte[] input, int start, int length, byte[] output, int written, int[] table) {
        var end = start + length;
        var position = start;
        var nextEmit = start;
        if (length >= INPUT_MARGIN) {
            var limit = end - INPUT_MARGIN;
            position++;
            var nextHash = hash(input, position);
            search:
            while (true) {
                var skip = 32;
                var next = position;
                int candidate;
                do {
                    position = next;
                    var currentHash = nextHash;
                    next = position + (skip++ >>> 5);
                    if (next > limit) {
                        break search;
                    }
                    nextHash = hash(input, next);
                    candidate = start + table[currentHash];
                    table[currentHash] = position - start;
                } while (load32(input, position) != load32(input, candidate));
                written = emitLiteral(input, nextEmit, position - nextEmit, output, written);
                do {
                    var base = position;
                    var matched = 4 + matchLength(input, candidate + 4, position + 4, end);
                    position += matched;
                    written = emitCopy(base - candidate, matched, output, written);
                    nextEmit = position;
                    if (position >= limit) {
                        break search;
                    }
                    table[hash(input, position - 1)] = position - 1 - start;
                    var currentHash = hash(input, position);
                    candidate = start + table[currentHash];
                    table[currentHash] = position - start;
                } while (load32(input, position) == load32(input, candidate));
                nextHash = hash(input, ++position);
            }
        }
        if (nextEmit < end) {
            written = emitLiteral(input, nextEmit, end - nextEmit, output, written);
        }
        return written;
    }

    /// {@return how many bytes from `second` equal those from `first`, stopping at `end`}
    private static int matchLength(byte[] input, int first, int second, int end) {
        var matched = 0;
        while (second + matched < end && input[first + matched] == input[second + matched]) {
            matched++;
        }
        return matched;
    }

    private static int emitLiteral(byte[] input, int from, int length, byte[] output, int written) {
        var stored = length - 1;
        if (stored < 60) {
            output[written++] = (byte) (LITERAL | stored << 2);
        } else {
            var lengthBytes = stored < 1 << 8 ? 1 : stored < 1 << 16 ? 2 : stored < 1 << 24 ? 3 : 4;
            output[written++] = (byte) (LITERAL | (59 + lengthBytes) << 2);
            for (var i = 0; i < lengthBytes; i++) {
                output[written++] = (byte) (stored >>> (8 * i));
            }
        }
        System.arraycopy(input, from, output, written, length);
        return written + length;
    }

    /// Emits a copy of `length` bytes from `distance` back, in pieces of at most 64 bytes.
    private static int emitCopy(int distance, int length, byte[] output, int written) {
        while (length >= 68) {
            written = emitShortCopy(distance, 64, output, written);
            length -= 64;
        }
        if (length > 64) {
            // Leaves at least 4 bytes, the shortest copy
            written = emitShortCopy(distance, 60, output, written);
            length -= 60;
        }
        return emitShortCopy(distance, length, output, written);
    }

    /// Emits a copy of 4 to 64 bytes, in the 2-byte form when it fits.
    private static int emitShortCopy(int distance, int length, byte[] output, int written) {
        if (length < 12 && distance < 2048) {
            output[written++] = (byte) (COPY_1 | (length - 4) << 2 | (distance >>> 8) << 5);
            output[written++] = (byte) distance;
        } else {
            output[written++] = (byte) (COPY_2 | (length - 1) << 2);
            output[written++] = (byte) distance;
            output[written++] = (byte) (distance >>> 8);
        }
        return written;
    }

    private static int hash(byte[] input, int position) {
        return (load32(input, position) * 0x1e35a7bd) >>> (32 - HASH_BITS);
    }

    private static int load32(byte[] input, int position) {
        return (input[position] & 0xFF) | (input[position + 1] & 0xFF) << 8 | (input[position + 2] & 0xFF) << 16
                | (input[position + 3] & 0xFF) << 24;
    }

    private static int writeVarint(byte[] output, int written, int value) {
        while ((value & ~0x7F) != 0) {
            output[written++] = (byte) (value & 0x7F | 0x80);
            value >>>= 7;
        }
        output[written++] = (byte) value;
        return written;
    }

    /// {@return the bytes the raw Snappy stream in `input[offset, offset + length)` decompresses to}
    ///
    /// @param maxLength the most bytes the stream may declare, a guard against a stream that claims an enormous length
    /// @throws IOException if the stream is truncated, declares a length it does not produce or one above
    ///                     `maxLength`, or copies from before the start of its output
    public static byte[] decompress(byte[] input, int offset, int length, int maxLength) throws IOException {
        var end = offset + length;
        var position = offset;
        long declared = 0;
        var shift = 0;
        while (true) {
            if (position >= end) {
                throw new IOException("The Snappy stream ends inside its length");
            }
            var current = input[position++] & 0xFF;
            declared |= (long) (current & 0x7F) << shift;
            if ((current & 0x80) == 0) {
                break;
            }
            shift += 7;
            if (shift > 28) {
                throw new IOException("The Snappy stream's length is too long a varint");
            }
        }
        if (declared > maxLength) {
            throw new IOException("The Snappy stream declares " + declared + " bytes, more than the " + maxLength + " allowed");
        }
        var output = new byte[(int) declared];
        var written = 0;
        while (position < end) {
            var tag = input[position++] & 0xFF;
            int copyLength;
            int copyOffset;
            switch (tag & 3) {
                case 0 -> {
                    var literalLength = tag >>> 2;
                    if (literalLength >= 60) {
                        var lengthBytes = literalLength - 59;
                        if (position + lengthBytes > end) {
                            throw new IOException("The Snappy stream ends inside a literal's length");
                        }
                        literalLength = 0;
                        for (var i = 0; i < lengthBytes; i++) {
                            literalLength |= (input[position++] & 0xFF) << (8 * i);
                        }
                    }
                    literalLength++;
                    if (literalLength <= 0 || literalLength > end - position || literalLength > output.length - written) {
                        throw new IOException("A Snappy literal runs past the stream or its output");
                    }
                    System.arraycopy(input, position, output, written, literalLength);
                    position += literalLength;
                    written += literalLength;
                    continue;
                }
                case 1 -> {
                    if (position >= end) {
                        throw new IOException("The Snappy stream ends inside a copy");
                    }
                    copyLength = ((tag >>> 2) & 7) + 4;
                    copyOffset = ((tag >>> 5) << 8) | (input[position++] & 0xFF);
                }
                case 2 -> {
                    if (end - position < 2) {
                        throw new IOException("The Snappy stream ends inside a copy");
                    }
                    copyLength = (tag >>> 2) + 1;
                    copyOffset = (input[position] & 0xFF) | (input[position + 1] & 0xFF) << 8;
                    position += 2;
                }
                default -> {
                    if (end - position < 4) {
                        throw new IOException("The Snappy stream ends inside a copy");
                    }
                    copyLength = (tag >>> 2) + 1;
                    copyOffset = (input[position] & 0xFF) | (input[position + 1] & 0xFF) << 8
                            | (input[position + 2] & 0xFF) << 16 | (input[position + 3] & 0xFF) << 24;
                    position += 4;
                }
            }
            if (copyOffset <= 0 || copyOffset > written || copyLength > output.length - written) {
                throw new IOException("A Snappy copy reaches before its output, or past its end");
            }
            if (copyOffset >= copyLength) {
                System.arraycopy(output, written - copyOffset, output, written, copyLength);
                written += copyLength;
            } else {
                // The copy overlaps what it writes, repeating the last copyOffset bytes
                for (var i = 0; i < copyLength; i++) {
                    output[written] = output[written - copyOffset];
                    written++;
                }
            }
        }
        if (written != output.length) {
            throw new IOException("The Snappy stream produced " + written + " bytes instead of the " + output.length + " it declares");
        }
        return output;
    }
}
