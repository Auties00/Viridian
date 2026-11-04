package com.github.auties00.viridian.compress;

import java.io.IOException;
import java.util.Arrays;

/// Decodes the sequences section of a zstd compressed block and executes it, writing the block's output.
///
/// A sequence copies a run of literals and then a match: bytes copied from earlier output, an offset back, which may
/// overlap the bytes the match itself writes. The section is a sequence count, a byte of compression modes, the FSE
/// tables those modes describe, and a backward bit stream of three interleaved FSE states, one each for the literal
/// length, offset and match length codes. Each table is in one of four modes:
///
/// ```text
/// 0  predefined  the distribution the format fixes
/// 1  RLE         one symbol, stored in a byte
/// 2  compressed  an FSE table description
/// 3  repeat      the table of the previous block of the frame that had sequences
/// ```
///
/// Offset values 1 to 3 repeat one of the last three offsets rather than stating one. When the sequence copies no
/// literals they shift by one, so that 1 picks the second most recent offset and 3 the most recent minus one, since
/// repeating the most recent offset right after the match that used it would only have extended that match. The
/// three offsets and the tables carry over from block to block, and [#reset()] restores their initial state for a new
/// frame.
final class ZstdSequences {
    /// The index of the literal length tables in [#ALPHABETS], [#current] and [#owned].
    private static final int LITERAL_LENGTHS = 0;

    /// The index of the offset tables.
    private static final int OFFSETS = 1;

    /// The index of the match length tables.
    private static final int MATCH_LENGTHS = 2;

    /// The mode that uses the format's predefined distribution.
    private static final int PREDEFINED_MODE = 0;

    /// The mode whose table decodes a single symbol.
    private static final int RLE_MODE = 1;

    /// The mode that describes its table.
    private static final int COMPRESSED_MODE = 2;

    /// The literal length each code stands for, before its extra bits.
    private static final int[] LITERAL_LENGTH_BASES = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
            16, 18, 20, 22, 24, 28, 32, 40, 48, 64, 128, 256, 512, 1024, 2048, 4096,
            8192, 16384, 32768, 65536
    };

    /// The extra bits each literal length code adds to its base.
    private static final int[] LITERAL_LENGTH_EXTRA_BITS = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            1, 1, 1, 1, 2, 2, 3, 3, 4, 6, 7, 8, 9, 10, 11, 12,
            13, 14, 15, 16
    };

    /// The match length each code stands for, before its extra bits; a match is at least 3 bytes.
    private static final int[] MATCH_LENGTH_BASES = {
            3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18,
            19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34,
            35, 37, 39, 41, 43, 47, 51, 59, 67, 83, 99, 131, 259, 515, 1027, 2051,
            4099, 8195, 16387, 32771, 65539
    };

    /// The extra bits each match length code adds to its base.
    private static final int[] MATCH_LENGTH_EXTRA_BITS = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            1, 1, 1, 1, 2, 2, 3, 3, 4, 4, 5, 7, 8, 9, 10, 11,
            12, 13, 14, 15, 16
    };

    /// The base of each offset code: code `n` reads `n` extra bits for an offset value of `(1 << n) + bits`, and an
    /// offset value above 3 is an offset 3 smaller, so the base is `(1 << n) - 3`; codes 0 and 1 are repeat codes,
    /// resolved apart.
    private static final int[] OFFSET_BASES = new int[32];

    /// The extra bits of each offset code: the code itself.
    private static final int[] OFFSET_EXTRA_BITS = new int[32];

    static {
        for (var code = 0; code < 32; code++) {
            OFFSET_BASES[code] = code < 2 ? code : (int) ((1L << code) - 3);
            OFFSET_EXTRA_BITS[code] = code;
        }
    }

    /// The predefined literal length distribution, of accuracy log 6.
    private static final short[] LITERAL_LENGTH_DISTRIBUTION = {
            4, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1,
            2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 2, 1, 1, 1, 1, 1,
            -1, -1, -1, -1
    };

    /// The predefined match length distribution, of accuracy log 6.
    private static final short[] MATCH_LENGTH_DISTRIBUTION = {
            1, 4, 3, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, -1, -1,
            -1, -1, -1, -1, -1
    };

    /// The predefined offset distribution, of accuracy log 5, covering offset codes up to 28.
    private static final short[] OFFSET_DISTRIBUTION = {
            1, 1, 1, 1, 1, 1, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, -1, -1, -1, -1, -1
    };

    /// The three code alphabets, indexed by [#LITERAL_LENGTHS], [#OFFSETS] and [#MATCH_LENGTHS].
    private static final Alphabet[] ALPHABETS = {
            new Alphabet("literal length", 35, 9, LITERAL_LENGTH_BASES, LITERAL_LENGTH_EXTRA_BITS,
                    FseTable.predefined(LITERAL_LENGTH_DISTRIBUTION, 6, LITERAL_LENGTH_BASES, LITERAL_LENGTH_EXTRA_BITS)),
            new Alphabet("offset", 31, 8, OFFSET_BASES, OFFSET_EXTRA_BITS,
                    FseTable.predefined(OFFSET_DISTRIBUTION, 5, OFFSET_BASES, OFFSET_EXTRA_BITS)),
            new Alphabet("match length", 52, 9, MATCH_LENGTH_BASES, MATCH_LENGTH_EXTRA_BITS,
                    FseTable.predefined(MATCH_LENGTH_DISTRIBUTION, 6, MATCH_LENGTH_BASES, MATCH_LENGTH_EXTRA_BITS))
    };

    /// The table each code currently decodes with, a predefined one or one of [#owned]; `null` before the frame's
    /// first sequences, when a repeat mode has nothing to repeat.
    private final FseTable[] current = new FseTable[3];

    /// The tables this decoder builds RLE and compressed modes into, allocated on first use.
    private final FseTable[] owned = new FseTable[3];

    /// The most recent offset of the frame.
    private int repeat1 = 1;

    /// The second most recent offset.
    private int repeat2 = 4;

    /// The third most recent offset.
    private int repeat3 = 8;

    /// Restores the initial repeat offsets and forgets the tables, for the first block of a new frame.
    void reset() {
        Arrays.fill(current, null);
        repeat1 = 1;
        repeat2 = 4;
        repeat3 = 8;
    }

    /// Decodes the sequences section at `input[position, end)`, the rest of its block, and writes the block's output:
    /// each sequence's literals and match, then the literals no sequence copied.
    ///
    /// @param literals the block's decoded literals
    /// @param output   the output the block is appended to, and the window its matches copy from
    /// @param window   the frame's window size, the farthest a match may reach back
    /// @throws IOException if the section is truncated or malformed, a sequence copies more literals than remain or
    ///                     reaches before its frame or past its window, or the block takes the output past a limit
    void decode(byte[] input, int position, int end, ZstdLiterals literals, ZstdOutput output, int window) throws IOException {
        if (position >= end) {
            throw new IOException("A zstd compressed block ends before its sequences section");
        }
        var count = input[position++] & 0xFF;
        if (count >= 0x80) {
            if (count == 0xFF) {
                if (end - position < 2) {
                    throw new IOException("A zstd sequences section ends inside its sequence count");
                }
                count = LittleEndian.readShort(input, position) + 0x7F00;
                position += 2;
            } else {
                if (position >= end) {
                    throw new IOException("A zstd sequences section ends inside its sequence count");
                }
                count = (count - 0x80) << 8 | input[position++] & 0xFF;
            }
        }
        var literalArray = literals.array;
        var literalPosition = literals.start;
        var literalEnd = literalPosition + literals.length;
        var limit = output.blockLimit();
        output.ensureCapacity(limit);
        var out = output.buffer;
        var outputPosition = output.size;
        if (count == 0) {
            if (position != end) {
                throw new IOException("A zstd block without sequences has " + (end - position) + " bytes after its sequence count");
            }
        } else {
            if (position >= end) {
                throw new IOException("A zstd sequences section ends before its compression modes");
            }
            var modes = input[position++] & 0xFF;
            if ((modes & 3) != 0) {
                throw new IOException("A zstd sequences section sets the reserved bits of its compression modes");
            }
            position = selectTable(LITERAL_LENGTHS, modes >>> 6, input, position, end);
            position = selectTable(OFFSETS, modes >>> 4 & 3, input, position, end);
            position = selectTable(MATCH_LENGTHS, modes >>> 2 & 3, input, position, end);
            var literalLengthTable = current[LITERAL_LENGTHS];
            var offsetTable = current[OFFSETS];
            var matchLengthTable = current[MATCH_LENGTHS];
            var literalLengthBases = literalLengthTable.base;
            var literalLengthInfo = literalLengthTable.packed;
            var offsetBases = offsetTable.base;
            var offsetInfo = offsetTable.packed;
            var matchLengthBases = matchLengthTable.base;
            var matchLengthInfo = matchLengthTable.packed;
            var reader = new BackwardBitReader(input, position, end, "sequences");
            var literalLengthState = reader.read(literalLengthTable.log);
            var offsetState = reader.read(offsetTable.log);
            var matchLengthState = reader.read(matchLengthTable.log);
            var repeat1 = this.repeat1;
            var repeat2 = this.repeat2;
            var repeat3 = this.repeat3;
            var frameStart = output.frameStart;
            for (var remaining = count; remaining > 0; remaining--) {
                // A reload leaves at least 57 bits: enough for up to 31 offset bits and 16 match length bits, then,
                // after a second reload when the extra bits are many, 16 literal length bits and 26 state bits
                reader.reload();
                var literalLengthEntry = literalLengthInfo[literalLengthState];
                var offsetEntry = offsetInfo[offsetState];
                var matchLengthEntry = matchLengthInfo[matchLengthState];
                var literalLengthBits = literalLengthEntry >>> 24;
                var offsetBits = offsetEntry >>> 24;
                var matchLengthBits = matchLengthEntry >>> 24;
                var literalLength = literalLengthBases[literalLengthState];
                int offset;
                if (offsetBits > 1) {
                    // Clamping keeps an offset too large for any output invalid without overflowing
                    offset = (int) Math.min(offsetBases[offsetState] + (long) reader.read(offsetBits), Integer.MAX_VALUE);
                    repeat3 = repeat2;
                    repeat2 = repeat1;
                    repeat1 = offset;
                } else if (offsetBits == 0) {
                    // Offset value 1; only a literal length code of 0 has a base of 0
                    if (literalLength == 0) {
                        offset = repeat2;
                        repeat2 = repeat1;
                        repeat1 = offset;
                    } else {
                        offset = repeat1;
                    }
                } else {
                    // Offset value 2 or 3, shifted by one when the sequence copies no literals
                    var index = reader.read(1) + (literalLength == 0 ? 2 : 1);
                    offset = switch (index) {
                        case 1 -> repeat2;
                        case 2 -> repeat3;
                        default -> repeat1 - 1;
                    };
                    if (index != 1) {
                        repeat3 = repeat2;
                    }
                    repeat2 = repeat1;
                    repeat1 = offset;
                }
                var matchLength = matchLengthBases[matchLengthState] + reader.read(matchLengthBits);
                if (offsetBits + matchLengthBits + literalLengthBits >= 31) {
                    reader.reload();
                }
                literalLength += reader.read(literalLengthBits);
                // The last sequence's states are never updated
                if (remaining > 1) {
                    literalLengthState = (literalLengthEntry & 0xFFFF) + reader.read(literalLengthEntry >>> 16 & 0xFF);
                    matchLengthState = (matchLengthEntry & 0xFFFF) + reader.read(matchLengthEntry >>> 16 & 0xFF);
                    offsetState = (offsetEntry & 0xFFFF) + reader.read(offsetEntry >>> 16 & 0xFF);
                }
                if (literalLength > literalEnd - literalPosition) {
                    throw new IOException("A zstd sequence copies " + literalLength + " literals where " + (literalEnd - literalPosition) + " remain");
                }
                if (literalLength + matchLength > limit - outputPosition) {
                    throw output.overflow((long) outputPosition + literalLength + matchLength);
                }
                System.arraycopy(literalArray, literalPosition, out, outputPosition, literalLength);
                literalPosition += literalLength;
                outputPosition += literalLength;
                if (offset <= 0 || offset > outputPosition - frameStart || offset > window) {
                    throw invalidOffset(offset, outputPosition - frameStart, window);
                }
                var from = outputPosition - offset;
                if (offset >= matchLength) {
                    System.arraycopy(out, from, out, outputPosition, matchLength);
                } else if (offset == 1) {
                    Arrays.fill(out, outputPosition, outputPosition + matchLength, out[from]);
                } else {
                    // The match overlaps what it writes, repeating its first offset bytes; every copy so far is a
                    // whole number of periods, so the span from the source can double each time without overlapping
                    var copied = 0;
                    while (copied < matchLength) {
                        var chunk = Math.min(offset + copied, matchLength - copied);
                        System.arraycopy(out, from, out, outputPosition + copied, chunk);
                        copied += chunk;
                    }
                }
                outputPosition += matchLength;
            }
            if (!reader.finished()) {
                throw new IOException("The zstd sequences bit stream does not end with its last sequence");
            }
            this.repeat1 = repeat1;
            this.repeat2 = repeat2;
            this.repeat3 = repeat3;
        }
        var rest = literalEnd - literalPosition;
        if (rest > limit - outputPosition) {
            throw output.overflow((long) outputPosition + rest);
        }
        System.arraycopy(literalArray, literalPosition, out, outputPosition, rest);
        output.size = outputPosition + rest;
    }

    /// Sets up the table of one code from its compression mode, reading the RLE symbol or the table description at
    /// `input[position]` when the mode carries one.
    ///
    /// @param kind the code, [#LITERAL_LENGTHS], [#OFFSETS] or [#MATCH_LENGTHS]
    /// @return the index just past what the mode read
    /// @throws IOException if the mode's data is truncated or malformed, or a repeat mode has no table to repeat
    private int selectTable(int kind, int mode, byte[] input, int position, int end) throws IOException {
        var alphabet = ALPHABETS[kind];
        switch (mode) {
            case PREDEFINED_MODE -> current[kind] = alphabet.predefined();
            case RLE_MODE -> {
                if (position >= end) {
                    throw new IOException("A zstd sequences section ends before its " + alphabet.name() + " RLE symbol");
                }
                var symbol = input[position++] & 0xFF;
                if (symbol > alphabet.maxSymbol()) {
                    throw new IOException("A zstd " + alphabet.name() + " RLE symbol of " + symbol + " exceeds the " + alphabet.maxSymbol() + " of its alphabet");
                }
                var table = ownedTable(kind, alphabet);
                table.buildRle(symbol, alphabet.bases(), alphabet.extraBits());
                current[kind] = table;
            }
            case COMPRESSED_MODE -> {
                var table = ownedTable(kind, alphabet);
                current[kind] = null;
                position += table.readDescription(input, position, end, alphabet.bases(), alphabet.extraBits(), alphabet.name());
                current[kind] = table;
            }
            default -> {
                if (current[kind] == null) {
                    throw new IOException("A zstd block repeats a " + alphabet.name() + " table that no earlier block of its frame defined");
                }
            }
        }
        return position;
    }

    /// {@return the table of `kind` this decoder builds into, allocating it on first use}
    private FseTable ownedTable(int kind, Alphabet alphabet) {
        var table = owned[kind];
        if (table == null) {
            table = owned[kind] = new FseTable(alphabet.maxLog(), alphabet.maxSymbol());
        }
        return table;
    }

    /// {@return the exception for a match `offset` bytes back, when `produced` bytes of the frame precede it}
    private static IOException invalidOffset(int offset, int produced, int window) {
        if (offset <= 0) {
            return new IOException("A zstd sequence repeats an offset of 0");
        }
        if (offset > produced) {
            return new IOException("A zstd match reaches " + offset + " bytes back, before the start of its frame " + produced + " bytes back");
        }
        return new IOException("A zstd match reaches " + offset + " bytes back, beyond the frame's window of " + window + " bytes");
    }

    /// One of the three codes a sequence is made of.
    ///
    /// @param name       the code's name, for error messages
    /// @param maxSymbol  the largest code
    /// @param maxLog     the largest accuracy log of its FSE tables
    /// @param bases      the value each code stands for before its extra bits
    /// @param extraBits  the extra bits each code adds
    /// @param predefined the table of the predefined distribution
    private record Alphabet(String name, int maxSymbol, int maxLog, int[] bases, int[] extraBits, FseTable predefined) {
    }
}
