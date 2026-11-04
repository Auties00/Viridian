package com.github.auties00.viridian.compress;

import java.io.IOException;
import java.util.Arrays;

/// Decodes the literals section of a zstd compressed block: the bytes its sequences copy into the output between
/// matches.
///
/// The section header's low two bits pick the representation:
///
/// ```text
/// 0  raw         the literals as they are
/// 1  RLE         one byte, repeated
/// 2  compressed  a Huffman tree description, then one or four Huffman-coded streams
/// 3  treeless    one or four streams coded with the Huffman table of an earlier block of the frame
/// ```
///
/// After [#decode(byte\[\], int, int, int)], the literals lie at `array[start, start + length)`: raw literals are
/// read in place from the input, the others from a buffer this object reuses from block to block. The Huffman table
/// lives as long as the frame, for the treeless blocks that reuse it.
final class ZstdLiterals {
    /// The section type that stores the literals as they are.
    private static final int RAW = 0;

    /// The section type that repeats a single byte.
    private static final int RLE = 1;

    /// The section type that carries its own Huffman tree description.
    private static final int COMPRESSED = 2;

    /// The array holding the decoded literals: the input for raw literals, [#buffer] otherwise.
    byte[] array;

    /// The index of the first literal in [#array].
    int start;

    /// The number of literals.
    int length;

    /// The buffer RLE and Huffman-coded literals are decoded into, grown as blocks need.
    private byte[] buffer = new byte[0];

    /// The Huffman table of the frame, allocated on the first Huffman-coded section.
    private HuffmanTable huffman;

    /// Whether a block of the current frame has described [#huffman], so a treeless section may reuse it.
    private boolean huffmanReady;

    /// Forgets the Huffman table of the previous frame, which a new frame cannot reuse.
    void reset() {
        huffmanReady = false;
    }

    /// Decodes the literals section that starts at `input[position]`, inside a block that ends at `end`.
    ///
    /// @param blockMax the frame's maximum block size, which bounds the literals as it bounds the whole block
    /// @return the index just past the section, where the sequences section starts
    /// @throws IOException if the section is truncated, malformed, or holds more literals than a block can
    int decode(byte[] input, int position, int end, int blockMax) throws IOException {
        if (position >= end) {
            throw new IOException("A zstd compressed block ends before its literals section");
        }
        var header = input[position] & 0xFF;
        var type = header & 3;
        var sizeFormat = (header >>> 2) & 3;
        if (type == RAW || type == RLE) {
            // Size formats 0 and 2 differ only in the bit that becomes the size's low bit
            int headerSize;
            int size;
            switch (sizeFormat) {
                case 0, 2 -> {
                    headerSize = 1;
                    size = header >>> 3;
                }
                case 1 -> {
                    headerSize = 2;
                    if (end - position < headerSize) {
                        throw new IOException("A zstd compressed block ends inside its literals section header");
                    }
                    size = header >>> 4 | (input[position + 1] & 0xFF) << 4;
                }
                default -> {
                    headerSize = 3;
                    if (end - position < headerSize) {
                        throw new IOException("A zstd compressed block ends inside its literals section header");
                    }
                    size = header >>> 4 | (input[position + 1] & 0xFF) << 4 | (input[position + 2] & 0xFF) << 12;
                }
            }
            if (size > blockMax) {
                throw new IOException("A zstd literals section holds " + size + " literals, more than the block maximum of " + blockMax);
            }
            var contentStart = position + headerSize;
            if (type == RAW) {
                if (size > end - contentStart) {
                    throw new IOException("A zstd raw literals section of " + size + " bytes runs past its block");
                }
                array = input;
                start = contentStart;
                length = size;
                return contentStart + size;
            }
            if (contentStart >= end) {
                throw new IOException("A zstd RLE literals section ends before its byte");
            }
            ensureBuffer(size, blockMax);
            Arrays.fill(buffer, 0, size, input[contentStart]);
            array = buffer;
            start = 0;
            length = size;
            return contentStart + 1;
        }
        // Compressed and treeless sections: two sizes of 10, 10, 14 or 18 bits after the 4 bits of type and format
        var headerSize = switch (sizeFormat) {
            case 0, 1 -> 3;
            case 2 -> 4;
            default -> 5;
        };
        var sizeBits = switch (sizeFormat) {
            case 0, 1 -> 10;
            case 2 -> 14;
            default -> 18;
        };
        if (end - position < headerSize) {
            throw new IOException("A zstd compressed block ends inside its literals section header");
        }
        var fields = 0L;
        for (var i = 0; i < headerSize; i++) {
            fields |= (input[position + i] & 0xFFL) << (8 * i);
        }
        var mask = (1 << sizeBits) - 1;
        var regenerated = (int) (fields >>> 4) & mask;
        var compressed = (int) (fields >>> (4 + sizeBits)) & mask;
        if (regenerated > blockMax) {
            throw new IOException("A zstd literals section holds " + regenerated + " literals, more than the block maximum of " + blockMax);
        }
        var contentStart = position + headerSize;
        if (compressed > end - contentStart) {
            throw new IOException("A zstd Huffman-coded literals section of " + compressed + " bytes runs past its block");
        }
        var contentEnd = contentStart + compressed;
        var streamsStart = contentStart;
        if (type == COMPRESSED) {
            if (huffman == null) {
                huffman = new HuffmanTable();
            }
            huffmanReady = false;
            streamsStart += huffman.readDescription(input, contentStart, contentEnd);
            huffmanReady = true;
        } else if (!huffmanReady) {
            throw new IOException("A zstd treeless literals section reuses a Huffman table that no earlier block of its frame described");
        }
        ensureBuffer(regenerated, blockMax);
        huffman.decode(input, streamsStart, contentEnd, sizeFormat != 0, buffer, regenerated);
        array = buffer;
        start = 0;
        length = regenerated;
        return contentEnd;
    }

    /// Grows [#buffer] to hold at least `size` literals, doubling up to the block maximum so that a run of growing
    /// blocks reallocates only a few times.
    private void ensureBuffer(int size, int blockMax) {
        if (buffer.length < size) {
            buffer = new byte[Math.max(size, Math.min(blockMax, buffer.length * 2))];
        }
    }
}
