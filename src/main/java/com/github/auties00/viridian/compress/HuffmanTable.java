package com.github.auties00.viridian.compress;

import java.io.IOException;
import java.util.Arrays;

/// The Huffman decoding table of a zstd frame's literals, and the decoder of the streams it codes.
///
/// A tree description gives each byte value a weight: 0 for an absent symbol, otherwise `maxBits + 1 - weight` is
/// the length of its code. The last symbol's weight is implied, as the one that completes the prefix code. The table
/// has `1 << maxBits` entries, indexed by the next `maxBits` bits of a stream, each holding a symbol and the length of
/// its code; codes are canonical, assigned from the lowest weight up and in symbol order within a weight, so a
/// symbol of weight `w` owns `1 << (w - 1)` consecutive entries.
///
/// A block that reuses the table of an earlier block (treeless literals) decodes against the table as last built.
final class HuffmanTable {
    /// The longest code the decoder accepts: 11 for the format, 12 for the reference decoder, which this one follows.
    private static final int MAX_BITS = 12;

    /// The largest accuracy log of the FSE table that compresses weights.
    private static final int MAX_WEIGHT_LOG = 6;

    /// The most explicit weights a description can carry: one per byte value but the last, implied one.
    private static final int MAX_WEIGHTS = 255;

    /// The base value of each FSE weight symbol: the weight itself.
    private static final int[] WEIGHT_BASES = new int[256];

    /// The extra bits of each FSE weight symbol: none.
    private static final int[] WEIGHT_EXTRA_BITS = new int[256];

    static {
        for (var i = 0; i < WEIGHT_BASES.length; i++) {
            WEIGHT_BASES[i] = i;
        }
    }

    /// The table entries, `(codeLength << 8) | symbol`, of which the first `1 << maxBits` are in use.
    private final short[] entries = new short[1 << MAX_BITS];

    /// The weight of each symbol of the description being read, the implied last one included.
    private final byte[] weights = new byte[MAX_WEIGHTS + 1];

    /// The number of symbols of each weight, and then the next free entry of each weight, while the table is built.
    private final int[] ranks = new int[MAX_BITS + 1];

    /// The length of the longest code, and the number of bits each lookup peeks.
    private int maxBits;

    /// The FSE table of compressed weights, allocated on the first description that needs it.
    private FseTable weightTable;

    /// Reads the Huffman tree description at `input[start, end)` and rebuilds this table from it.
    ///
    /// A header byte of 128 or more is followed by `header - 127` weights of 4 bits each, two per byte with the first
    /// in the high nibble; a smaller one is the size of an FSE-compressed weight stream that follows.
    ///
    /// @return the number of bytes the description occupies
    /// @throws IOException if the description is truncated or malformed, or its weights do not form a prefix code
    int readDescription(byte[] input, int start, int end) throws IOException {
        if (start >= end) {
            throw new IOException("A zstd literals section ends before its Huffman tree description");
        }
        var header = input[start] & 0xFF;
        int count;
        int size;
        if (header >= 128) {
            count = header - 127;
            size = 1 + (count + 1) / 2;
            if (size > end - start) {
                throw new IOException("A Huffman tree description of " + count + " weights runs past its literals section");
            }
            for (var i = 0; i < count; i++) {
                var packed = input[start + 1 + i / 2] & 0xFF;
                weights[i] = (byte) ((i & 1) == 0 ? packed >>> 4 : packed & 0xF);
            }
        } else {
            size = 1 + header;
            if (size > end - start) {
                throw new IOException("A Huffman tree description of " + header + " compressed bytes runs past its literals section");
            }
            count = readCompressedWeights(input, start + 1, start + size);
        }
        build(count);
        return size;
    }

    /// Decodes `length` literals from the Huffman-coded streams at `input[start, end)` into `output[0, length)`.
    ///
    /// Four streams start with a jump table of three 16-bit stream sizes, the fourth taking the rest; each of the
    /// first three decodes `(length + 3) / 4` literals and the fourth what remains.
    ///
    /// @param fourStreams whether the literals are split over four streams rather than coded in one
    /// @throws IOException if a stream is malformed or does not decode exactly its share of the literals
    void decode(byte[] input, int start, int end, boolean fourStreams, byte[] output, int length) throws IOException {
        if (!fourStreams) {
            decodeStream(input, start, end, output, 0, length);
            return;
        }
        if (end - start < 6) {
            throw new IOException("Four Huffman-coded literal streams end inside their jump table");
        }
        var size1 = LittleEndian.readShort(input, start);
        var size2 = LittleEndian.readShort(input, start + 2);
        var size3 = LittleEndian.readShort(input, start + 4);
        var start1 = start + 6;
        var start2 = start1 + size1;
        var start3 = start2 + size2;
        var start4 = start3 + size3;
        if (start4 > end) {
            throw new IOException("The jump table of four Huffman-coded literal streams points past them");
        }
        var segment = (length + 3) >>> 2;
        if (length - 3 * segment < 0) {
            throw new IOException(length + " literals are too few to split over four Huffman-coded streams");
        }
        decodeStream(input, start1, start2, output, 0, segment);
        decodeStream(input, start2, start3, output, segment, 2 * segment);
        decodeStream(input, start3, start4, output, 2 * segment, 3 * segment);
        decodeStream(input, start4, end, output, 3 * segment, length);
    }

    /// Decodes the FSE-compressed weights at `input[start, end)` into [#weights].
    ///
    /// The weights are interleaved over two FSE states sharing one backward bit stream. Decoding alternates between
    /// them until a state update overreads the stream, which marks the end: the other state's symbol is the last
    /// weight.
    ///
    /// @return the number of weights decoded
    /// @throws IOException if the FSE table or the stream is malformed, or it decodes more weights than byte values
    private int readCompressedWeights(byte[] input, int start, int end) throws IOException {
        var table = weightTable;
        if (table == null) {
            table = weightTable = new FseTable(MAX_WEIGHT_LOG, 255);
        }
        var descriptionSize = table.readDescription(input, start, end, WEIGHT_BASES, WEIGHT_EXTRA_BITS, "Huffman weights");
        var reader = new BackwardBitReader(input, start + descriptionSize, end, "Huffman weights");
        var bases = table.base;
        var packed = table.packed;
        var state1 = reader.read(table.log);
        var state2 = reader.read(table.log);
        if (reader.reload()) {
            throw new IOException("The Huffman weights bit stream ends inside its initial states");
        }
        var count = 0;
        while (true) {
            // The reference decoder stops two weights short of 256, which bounds a valid description
            if (count > MAX_WEIGHTS - 2) {
                throw new IOException("The Huffman weights bit stream decodes more than " + MAX_WEIGHTS + " weights");
            }
            weights[count++] = (byte) bases[state1];
            var info1 = packed[state1];
            state1 = (info1 & 0xFFFF) + reader.read((info1 >>> 16) & 0xFF);
            if (reader.reload()) {
                weights[count++] = (byte) bases[state2];
                return count;
            }
            if (count > MAX_WEIGHTS - 2) {
                throw new IOException("The Huffman weights bit stream decodes more than " + MAX_WEIGHTS + " weights");
            }
            weights[count++] = (byte) bases[state2];
            var info2 = packed[state2];
            state2 = (info2 & 0xFFFF) + reader.read((info2 >>> 16) & 0xFF);
            if (reader.reload()) {
                weights[count++] = (byte) bases[state1];
                return count;
            }
        }
    }

    /// Completes the `count` explicit weights of [#weights] with the implied last one and lays down the table.
    ///
    /// @throws IOException if a weight is out of range, or the weights cannot be completed into a prefix code
    private void build(int count) throws IOException {
        var total = 0;
        for (var i = 0; i < count; i++) {
            var weight = weights[i] & 0xFF;
            if (weight > MAX_BITS) {
                throw new IOException("A Huffman weight of " + weight + " exceeds the " + MAX_BITS + " allowed");
            }
            if (weight > 0) {
                total += 1 << (weight - 1);
            }
        }
        if (total == 0) {
            throw new IOException("Every explicit Huffman weight is zero");
        }
        // The explicit weights fill less than the table, which rounds up to the next power of two
        var maxBits = 32 - Integer.numberOfLeadingZeros(total);
        if (maxBits > MAX_BITS) {
            throw new IOException("The Huffman weights need codes of " + maxBits + " bits, above the " + MAX_BITS + " allowed");
        }
        var rest = (1 << maxBits) - total;
        if (Integer.bitCount(rest) != 1) {
            throw new IOException("The Huffman weights leave " + rest + " codes, which no single weight completes");
        }
        weights[count] = (byte) (Integer.numberOfTrailingZeros(rest) + 1);
        var symbols = count + 1;
        Arrays.fill(ranks, 0);
        for (var symbol = 0; symbol < symbols; symbol++) {
            ranks[weights[symbol]]++;
        }
        var next = 0;
        for (var weight = 1; weight <= maxBits; weight++) {
            var symbolsOfWeight = ranks[weight];
            ranks[weight] = next;
            next += symbolsOfWeight << (weight - 1);
        }
        for (var symbol = 0; symbol < symbols; symbol++) {
            var weight = weights[symbol];
            if (weight == 0) {
                continue;
            }
            var first = ranks[weight];
            var span = 1 << (weight - 1);
            Arrays.fill(entries, first, first + span, (short) ((maxBits + 1 - weight) << 8 | symbol));
            ranks[weight] = first + span;
        }
        this.maxBits = maxBits;
    }

    /// Decodes the single Huffman-coded stream at `input[start, end)` into `output[outputStart, outputEnd)`.
    ///
    /// The stream is a backward bit stream; it must end exactly at its first bit once the last literal is decoded.
    /// While the container can be refilled with 8 whole bytes, four literals are decoded per refill: a refill leaves
    /// at least 57 unread bits and four codes take at most 48. The tail then decodes one literal per refill, reading
    /// zeros past the start of the stream; an overread stays inside the table and fails the final check.
    ///
    /// @throws IOException if the stream is empty, lacks its end marker, or does not end with its last literal
    private void decodeStream(byte[] input, int start, int end, byte[] output, int outputStart, int outputEnd) throws IOException {
        if (end <= start) {
            throw new IOException("A Huffman-coded literals stream is empty");
        }
        var last = input[end - 1] & 0xFF;
        if (last == 0) {
            throw new IOException("A Huffman-coded literals stream does not end with a set bit");
        }
        var entries = this.entries;
        var shift = 64 - maxBits;
        var consumed = Integer.numberOfLeadingZeros(last) - 23;
        int position;
        long bits;
        if (end - start >= 8) {
            position = end - 8;
            bits = LittleEndian.readLong(input, position);
        } else {
            position = start;
            bits = 0;
            for (var i = start; i < end; i++) {
                bits |= (input[i] & 0xFFL) << (8 * (i - start));
            }
            consumed += (8 - (end - start)) * 8;
        }
        var outputPosition = outputStart;
        if (end - start >= 8) {
            while (outputEnd - outputPosition >= 4) {
                var back = consumed >>> 3;
                if (position - back < start) {
                    break;
                }
                position -= back;
                consumed &= 7;
                bits = LittleEndian.readLong(input, position);
                int entry = entries[(int) (bits << consumed >>> shift)];
                output[outputPosition] = (byte) entry;
                consumed += entry >>> 8;
                entry = entries[(int) (bits << consumed >>> shift)];
                output[outputPosition + 1] = (byte) entry;
                consumed += entry >>> 8;
                entry = entries[(int) (bits << consumed >>> shift)];
                output[outputPosition + 2] = (byte) entry;
                consumed += entry >>> 8;
                entry = entries[(int) (bits << consumed >>> shift)];
                output[outputPosition + 3] = (byte) entry;
                consumed += entry >>> 8;
                outputPosition += 4;
            }
        }
        while (outputPosition < outputEnd) {
            if (consumed <= 64) {
                var back = Math.min(consumed >>> 3, position - start);
                if (back > 0) {
                    position -= back;
                    consumed -= back << 3;
                    bits = LittleEndian.readLong(input, position);
                }
            }
            int entry = entries[(int) (bits << consumed >>> shift)];
            output[outputPosition++] = (byte) entry;
            consumed += entry >>> 8;
        }
        if (consumed <= 64) {
            var back = Math.min(consumed >>> 3, position - start);
            position -= back;
            consumed -= back << 3;
        }
        if (position != start || consumed != 64) {
            throw new IOException("A Huffman-coded literals stream does not end where its literals do");
        }
    }
}
