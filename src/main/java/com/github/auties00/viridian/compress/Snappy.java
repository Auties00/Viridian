package com.github.auties00.viridian.compress;

import java.io.IOException;

/// Decompresses the raw Snappy format: the block compression of LevelDB tables, Blink's compressed IndexedDB values,
/// and the values Chromium's SQLite IndexedDB backend compresses on Android.
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
    private Snappy() {
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
