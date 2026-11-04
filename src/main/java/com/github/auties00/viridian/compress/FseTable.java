package com.github.auties00.viridian.compress;

import java.io.IOException;
import java.util.Arrays;

/// An FSE (tabled asymmetric numeral system) decoding table, indexed by state.
///
/// Each state decodes to one symbol, stored already resolved to what the symbol stands for: a base value in [#base]
/// and the count of raw extra bits added to it, which is how the sequence codes are defined (a literal length code is
/// a baseline plus extra bits). The state then moves to its next-state base plus as many fresh bits as its state
/// width. The extra bits, the state width and the next-state base share one `int` of [#packed]:
///
/// ```text
/// bits  0-15   next-state base
/// bits 16-23   state width, the bits read to move to the next state
/// bits 24-31   extra bits added to the base value
/// ```
///
/// A table is built from a normalized distribution, the counts of a total of `1 << log` states each symbol owns,
/// where `-1` marks a symbol of less than one state's probability; the zstd format spreads the symbols over the states
/// with a fixed step, so encoder and decoder agree on the layout without transmitting it. The arrays are allocated
/// once, at the largest size the table kind allows, and rebuilt in place.
final class FseTable {
    /// The smallest accuracy log a table description can state; its 4-bit field counts from it.
    private static final int MIN_LOG = 5;

    /// The base value each state decodes to.
    final int[] base;

    /// The next-state base, state width and extra bits of each state, laid out as the class describes.
    final int[] packed;

    /// The accuracy log: the table has `1 << log` states, and an initial state is read as `log` bits.
    int log;

    /// The largest accuracy log this table accepts.
    private final int maxLog;

    /// The largest symbol this table accepts.
    private final int maxSymbol;

    /// The normalized count of each symbol, read from a description and consumed by [#build(int, int, int\[\], int\[\])].
    private final short[] counts;

    /// The next state number each symbol hands out while the decoding table is built.
    private final int[] nextState;

    /// The symbol of each state, the spread the build lays down before resolving it.
    private final int[] symbols;

    /// Allocates an empty table with room for every table of its kind.
    ///
    /// @param maxLog    the largest accuracy log the table kind allows
    /// @param maxSymbol the largest symbol of the table kind's alphabet
    FseTable(int maxLog, int maxSymbol) {
        this.maxLog = maxLog;
        this.maxSymbol = maxSymbol;
        this.base = new int[1 << maxLog];
        this.packed = new int[1 << maxLog];
        this.counts = new short[maxSymbol + 1];
        this.nextState = new int[maxSymbol + 1];
        this.symbols = new int[1 << maxLog];
    }

    /// {@return a table built from a distribution the format predefines, which cannot fail to build}
    ///
    /// @param distribution    the normalized count of each symbol
    /// @param log             the accuracy log the distribution sums to
    /// @param symbolBases     the base value of each symbol
    /// @param symbolExtraBits the extra bits of each symbol
    static FseTable predefined(short[] distribution, int log, int[] symbolBases, int[] symbolExtraBits) {
        var table = new FseTable(log, distribution.length - 1);
        System.arraycopy(distribution, 0, table.counts, 0, distribution.length);
        try {
            table.build(distribution.length, log, symbolBases, symbolExtraBits);
        } catch (IOException exception) {
            throw new AssertionError("A predefined FSE distribution does not build", exception);
        }
        return table;
    }

    /// Rebuilds this table as the single state of an RLE-mode table: every value decodes to `symbol` and reading a
    /// state takes no bits.
    ///
    /// @param symbol          the only symbol, which the caller has checked against the alphabet
    /// @param symbolBases     the base value of each symbol
    /// @param symbolExtraBits the extra bits of each symbol
    void buildRle(int symbol, int[] symbolBases, int[] symbolExtraBits) {
        base[0] = symbolBases[symbol];
        packed[0] = symbolExtraBits[symbol] << 24;
        log = 0;
    }

    /// Reads the FSE table description at `input[start, end)` and rebuilds this table from it.
    ///
    /// The description is a forward little-endian bit stream: a 4-bit accuracy log, then each symbol's count in a
    /// variable width that shrinks as the probability left to distribute does, with runs of zero-probability symbols
    /// coded in 2-bit repeat flags after a zero. It ends when the probability is exhausted, at a byte boundary. Bits
    /// past `end` read as zeros, so a truncated description is caught by its length rather than by an exception.
    ///
    /// @param symbolBases     the base value of each symbol
    /// @param symbolExtraBits the extra bits of each symbol
    /// @param name            the table kind, for error messages
    /// @return the number of bytes the description occupies
    /// @throws IOException if the description is truncated, states too large an accuracy log, describes symbols past
    ///                     the alphabet, or does not distribute exactly its total probability
    int readDescription(byte[] input, int start, int end, int[] symbolBases, int[] symbolExtraBits, String name) throws IOException {
        var log = (peek(input, start, end, 0) & 0xF) + MIN_LOG;
        if (log > maxLog) {
            throw new IOException("The " + name + " FSE table states an accuracy log of " + log + ", above the " + maxLog + " allowed");
        }
        Arrays.fill(counts, (short) 0);
        var bitPosition = 4;
        var remaining = (1 << log) + 1;
        var threshold = 1 << log;
        var width = log + 1;
        var symbol = 0;
        var previousZero = false;
        while (remaining > 1) {
            if (previousZero) {
                // A zero count is followed by 2-bit flags, each the number of further zero-probability symbols, where 3
                // also announces another flag
                int repeat;
                do {
                    repeat = peek(input, start, end, bitPosition) & 3;
                    bitPosition += 2;
                    symbol += repeat;
                    if (symbol > maxSymbol) {
                        throw new IOException("The " + name + " FSE table describes symbols past the " + maxSymbol + " of its alphabet");
                    }
                } while (repeat == 3);
            }
            if (symbol > maxSymbol) {
                throw new IOException("The " + name + " FSE table describes symbols past the " + maxSymbol + " of its alphabet");
            }
            var bits = peek(input, start, end, bitPosition);
            // The values that cannot exceed what remains to distribute fit in one bit fewer
            var max = 2 * threshold - 1 - remaining;
            int count;
            if ((bits & (threshold - 1)) < max) {
                count = bits & (threshold - 1);
                bitPosition += width - 1;
            } else {
                count = bits & (2 * threshold - 1);
                if (count >= threshold) {
                    count -= max;
                }
                bitPosition += width;
            }
            // The coded value is the count plus one, so that -1 marks a less-than-one probability
            count--;
            remaining -= Math.abs(count);
            counts[symbol++] = (short) count;
            previousZero = count == 0;
            while (remaining < threshold) {
                width--;
                threshold >>>= 1;
            }
        }
        var size = (bitPosition + 7) >>> 3;
        if (size > end - start) {
            throw new IOException("The " + name + " FSE table description runs past the end of its block");
        }
        build(symbol, log, symbolBases, symbolExtraBits);
        return size;
    }

    /// Spreads the symbols of [#counts] over the states and resolves each state's transition.
    ///
    /// Symbols of less than one state's probability take the last states, one each, from the top down; the rest are
    /// laid down in symbol order at positions a fixed odd step apart, skipping those last states. A symbol's states
    /// then receive its next-state numbers in increasing order, each number giving the width and base of the next
    /// state.
    ///
    /// @param symbolCount     the number of symbols [#counts] describes
    /// @param symbolBases     the base value of each symbol
    /// @param symbolExtraBits the extra bits of each symbol
    /// @throws IOException if the counts do not add up to `1 << log`
    private void build(int symbolCount, int log, int[] symbolBases, int[] symbolExtraBits) throws IOException {
        var size = 1 << log;
        var total = 0;
        for (var symbol = 0; symbol < symbolCount; symbol++) {
            total += Math.abs(counts[symbol]);
        }
        if (total != size) {
            throw new IOException("An FSE distribution sums to " + total + " states instead of " + size);
        }
        var high = size - 1;
        for (var symbol = 0; symbol < symbolCount; symbol++) {
            int count = counts[symbol];
            if (count == -1) {
                symbols[high--] = symbol;
                nextState[symbol] = 1;
            } else {
                nextState[symbol] = count;
            }
        }
        var step = (size >>> 1) + (size >>> 3) + 3;
        var mask = size - 1;
        var position = 0;
        for (var symbol = 0; symbol < symbolCount; symbol++) {
            for (int i = 0, count = counts[symbol]; i < count; i++) {
                symbols[position] = symbol;
                do {
                    position = (position + step) & mask;
                } while (position > high);
            }
        }
        if (position != 0) {
            throw new IOException("An FSE distribution does not cover every state");
        }
        for (var state = 0; state < size; state++) {
            var symbol = symbols[state];
            var next = nextState[symbol]++;
            var width = log - (31 - Integer.numberOfLeadingZeros(next));
            base[state] = symbolBases[symbol];
            packed[state] = ((next << width) - size) | width << 16 | symbolExtraBits[symbol] << 24;
        }
        this.log = log;
    }

    /// {@return the 32 bits of the forward bit stream at `input[start, end)` that start `bitPosition` bits in, with
    /// bytes past `end` read as zeros}
    private static int peek(byte[] input, int start, int end, int bitPosition) {
        var index = start + (bitPosition >>> 3);
        var value = 0L;
        for (var i = 0; i < 5; i++) {
            if (index + i < end) {
                value |= (input[index + i] & 0xFFL) << (8 * i);
            }
        }
        return (int) (value >>> (bitPosition & 7));
    }
}
