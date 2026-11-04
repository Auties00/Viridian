package com.github.auties00.viridian.compress;

import java.io.IOException;

/// Decodes zstd frames, header, blocks and checksum, appending their output to one [ZstdOutput].
///
/// A frame header is a descriptor byte, then an optional window descriptor, dictionary id and content size:
///
/// ```text
/// descriptor bits 7-6  content size field: 0, 2, 4 or 8 bytes (1, not 0, in a single-segment frame)
///            bit  5    single segment: no window descriptor, the window is the content size
///            bit  4    unused
///            bit  3    reserved, must be 0
///            bit  2    content checksum: the frame ends with the low 32 bits of the XXH64 of its output
///            bits 1-0  dictionary id field: 0, 1, 2 or 4 bytes
/// window     bits 7-3  exponent, the window is 1 << (10 + exponent)
///            bits 2-0  mantissa, adding that many eighths of it
/// ```
///
/// The blocks follow, each a 3-byte little-endian header (bit 0 last block, bits 1-2 type, bits 3-23 size) and its
/// content. The decoder lives for one decompression call; the literals and sequences decoders it owns keep their
/// tables and buffers across frames, and are reset at the start of each.
final class ZstdFrameDecoder {
    /// The largest window the decoder accepts: what one Java array can address.
    private static final long MAX_WINDOW_SIZE = 1L << 31;

    /// The largest block the format allows, whatever the window.
    private static final int MAX_BLOCK_SIZE = 128 * 1024;

    /// The block type whose content is stored as it is.
    private static final int RAW_BLOCK = 0;

    /// The block type whose content is one byte, repeated.
    private static final int RLE_BLOCK = 1;

    /// The block type whose content is a literals section and a sequences section.
    private static final int COMPRESSED_BLOCK = 2;

    /// The bytes holding the frames.
    private final byte[] input;

    /// The output every frame is appended to.
    private final ZstdOutput output;

    /// The literals decoder, whose Huffman table compressed blocks of a frame share.
    private final ZstdLiterals literals = new ZstdLiterals();

    /// The sequences decoder, whose FSE tables and repeat offsets compressed blocks of a frame share.
    private final ZstdSequences sequences = new ZstdSequences();

    /// Creates a decoder of the frames in `input` that appends to `output`.
    ZstdFrameDecoder(byte[] input, ZstdOutput output) {
        this.input = input;
        this.output = output;
    }

    /// Decodes the frame whose header starts at `input[position]`, right after its magic number, in input that ends at
    /// `end`.
    ///
    /// @return the index just past the frame
    /// @throws IOException if the frame is truncated, malformed, needs a dictionary, has a window above 2 GiB, does not
    ///                     decompress to the content size it declares, fails its checksum, or takes the output past
    ///                     `maxLength`
    int decode(int position, int end) throws IOException {
        if (position >= end) {
            throw new IOException("A zstd frame ends inside its header");
        }
        var descriptor = input[position++] & 0xFF;
        var singleSegment = (descriptor & 0x20) != 0;
        if ((descriptor & 0x08) != 0) {
            throw new IOException("A zstd frame header sets its reserved bit");
        }
        var checksummed = (descriptor & 0x04) != 0;
        var dictionaryIdSize = switch (descriptor & 3) {
            case 0 -> 0;
            case 1 -> 1;
            case 2 -> 2;
            default -> 4;
        };
        var contentSizeSize = switch (descriptor >>> 6) {
            case 0 -> singleSegment ? 1 : 0;
            case 1 -> 2;
            case 2 -> 4;
            default -> 8;
        };
        if (end - position < (singleSegment ? 0 : 1) + dictionaryIdSize + contentSizeSize) {
            throw new IOException("A zstd frame ends inside its header");
        }
        var windowSize = 0L;
        if (!singleSegment) {
            var windowDescriptor = input[position++] & 0xFF;
            var windowBase = 1L << (10 + (windowDescriptor >>> 3));
            windowSize = windowBase + (windowBase >>> 3) * (windowDescriptor & 7);
            if (windowSize > MAX_WINDOW_SIZE) {
                throw new IOException("A zstd frame needs a window of " + windowSize + " bytes, above the " + MAX_WINDOW_SIZE + " supported");
            }
        }
        long dictionaryId = switch (dictionaryIdSize) {
            case 0 -> 0;
            case 1 -> input[position] & 0xFF;
            case 2 -> LittleEndian.readShort(input, position);
            default -> LittleEndian.readInt(input, position) & 0xFFFFFFFFL;
        };
        position += dictionaryIdSize;
        if (dictionaryId != 0) {
            throw new IOException("A zstd frame needs dictionary " + dictionaryId + ", and dictionaries are not supported");
        }
        long contentSize = switch (contentSizeSize) {
            case 0 -> -1;
            case 1 -> input[position] & 0xFF;
            // The 2-byte field skips the sizes the 1-byte field of a single-segment frame covers
            case 2 -> LittleEndian.readShort(input, position) + 256;
            case 4 -> LittleEndian.readInt(input, position) & 0xFFFFFFFFL;
            default -> LittleEndian.readLong(input, position);
        };
        position += contentSizeSize;
        if (contentSizeSize == 8 && contentSize < 0) {
            throw new IOException("A zstd frame declares a content size above 2^63 bytes");
        }
        if (singleSegment) {
            windowSize = contentSize;
        }
        var blockMax = (int) Math.min(windowSize, MAX_BLOCK_SIZE);
        var window = (int) Math.min(windowSize, Integer.MAX_VALUE);
        output.startFrame(contentSize, blockMax, end - position);
        var frameStart = output.frameStart;
        literals.reset();
        sequences.reset();
        boolean last;
        do {
            if (end - position < 3) {
                throw new IOException("A zstd frame ends inside a block header");
            }
            var header = LittleEndian.readMedium(input, position);
            position += 3;
            last = (header & 1) != 0;
            var type = header >>> 1 & 3;
            var size = header >>> 3;
            if (size > blockMax) {
                throw new IOException("A zstd block of " + size + " bytes exceeds its frame's maximum block size of " + blockMax + " bytes");
            }
            switch (type) {
                case RAW_BLOCK -> {
                    if (size > end - position) {
                        throw new IOException("A zstd raw block of " + size + " bytes runs past the end of the data");
                    }
                    output.write(input, position, size);
                    position += size;
                }
                case RLE_BLOCK -> {
                    if (position >= end) {
                        throw new IOException("A zstd RLE block ends before its byte");
                    }
                    output.fill(input[position], size);
                    position++;
                }
                case COMPRESSED_BLOCK -> {
                    if (size > end - position) {
                        throw new IOException("A zstd compressed block of " + size + " bytes runs past the end of the data");
                    }
                    var blockEnd = position + size;
                    var sequencesStart = literals.decode(input, position, blockEnd, blockMax);
                    sequences.decode(input, sequencesStart, blockEnd, literals, output, window);
                    position = blockEnd;
                }
                default -> throw new IOException("A zstd block has the reserved block type");
            }
        } while (!last);
        var produced = output.size - frameStart;
        if (contentSize >= 0 && produced != contentSize) {
            throw new IOException("A zstd frame decompresses to " + produced + " bytes instead of the " + contentSize + " its header declares");
        }
        if (checksummed) {
            if (end - position < 4) {
                throw new IOException("A zstd frame ends inside its content checksum");
            }
            var expected = LittleEndian.readInt(input, position);
            var actual = (int) Xxh64.hash(output.buffer, frameStart, produced, 0);
            if (expected != actual) {
                throw new IOException("A zstd frame fails its content checksum: it stores " + hex(expected) + ", its output hashes to " + hex(actual));
            }
            position += 4;
        }
        return position;
    }

    /// {@return `value` as eight hexadecimal digits}
    private static String hex(int value) {
        return String.format("%08x", value);
    }
}
