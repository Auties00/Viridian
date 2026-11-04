package com.github.auties00.viridian.compress;

import java.io.IOException;
import java.util.Objects;

/// Decompresses the Zstandard format: the compression of every record value of 450 bytes or more in Chromium's SQLite
/// IndexedDB backend, and of LevelDB blocks written by producers that choose it.
///
/// The decoder is complete for frames without a dictionary: every frame of a range is decoded and their outputs
/// concatenated, skippable frames are skipped, every block type, literals mode and sequence table mode is supported,
/// whatever level produced it, and content checksums are verified. Malformed input fails with an [IOException], and
/// the output never grows past the bound the caller sets.
///
/// ```text
/// 0xFD2FB528                 zstd frame: header, blocks, optional checksum
/// 0x184D2A50 to 0x184D2A5F   skippable frame: a 4-byte little-endian size, then that many ignored bytes
/// ```
///
/// @spec https://www.rfc-editor.org/rfc/rfc8878 Zstandard Compression and the 'application/zstd' Media Type
public final class Zstd {
    /// The magic number a zstd frame starts with, read little-endian.
    private static final int FRAME_MAGIC = 0xFD2FB528;

    /// The magic number of the first skippable frame; the low 4 bits are free, giving 16 skippable magics.
    private static final int SKIPPABLE_MAGIC = 0x184D2A50;

    /// The bits of a magic number that identify a skippable frame.
    private static final int SKIPPABLE_MASK = 0xFFFFFFF0;

    /// The magic number of the v0.1 legacy format, which predates the standard and differs from the later ones.
    private static final int LEGACY_V01_MAGIC = 0xFD2FB51E;

    /// The magic number of the v0.2 legacy format; v0.3 to v0.7 count up from it.
    private static final int LEGACY_V02_MAGIC = 0xFD2FB522;

    /// The magic number of the v0.7 legacy format, the last before the standard.
    private static final int LEGACY_V07_MAGIC = 0xFD2FB527;

    private Zstd() {
    }

    /// {@return the bytes the zstd frames in `input[offset, offset + length)` decompress to}
    ///
    /// An empty range decompresses to an empty array, as do skippable frames alone.
    ///
    /// @param maxLength the most bytes the output may reach; more fails rather than allocate without bound
    /// @throws IOException              if the range holds anything but whole frames, a frame is truncated or
    ///                                  malformed, needs a dictionary, has a window above 2 GiB, fails its checksum,
    ///                                  or the output would pass `maxLength`
    /// @throws IndexOutOfBoundsException if the range lies outside `input`
    /// @throws IllegalArgumentException if `maxLength` is negative
    public static byte[] decompress(byte[] input, int offset, int length, int maxLength) throws IOException {
        Objects.checkFromIndexSize(offset, length, input.length);
        if (maxLength < 0) {
            throw new IllegalArgumentException("maxLength is negative: " + maxLength);
        }
        var end = offset + length;
        var output = new ZstdOutput(maxLength);
        ZstdFrameDecoder decoder = null;
        var position = offset;
        while (position < end) {
            if (end - position < 4) {
                throw new IOException(position == offset
                        ? "The zstd data is " + length + " bytes long, too short for a frame"
                        : "The " + (end - position) + " bytes after the last zstd frame are too few for another frame");
            }
            var magic = LittleEndian.readInt(input, position);
            if (magic == FRAME_MAGIC) {
                if (decoder == null) {
                    decoder = new ZstdFrameDecoder(input, output);
                }
                position = decoder.decode(position + 4, end);
            } else if ((magic & SKIPPABLE_MASK) == SKIPPABLE_MAGIC) {
                if (end - position < 8) {
                    throw new IOException("A skippable zstd frame ends inside its header");
                }
                var size = LittleEndian.readInt(input, position + 4) & 0xFFFFFFFFL;
                if (size > end - position - 8) {
                    throw new IOException("A skippable zstd frame of " + size + " bytes runs past the end of the data");
                }
                position += 8 + (int) size;
            } else {
                throw new IOException(describeUnknownMagic(magic, position == offset));
            }
        }
        return output.toByteArray();
    }

    /// {@return why the bytes `magic` was read from cannot start a frame}
    ///
    /// @param first whether they start the range, rather than follow a frame
    private static String describeUnknownMagic(int magic, boolean first) {
        var where = first ? "The zstd data starts with " : "The bytes after a zstd frame start with ";
        var hex = String.format("0x%08X", magic);
        if (magic == LEGACY_V01_MAGIC || (magic >= LEGACY_V02_MAGIC && magic <= LEGACY_V07_MAGIC)) {
            var version = magic == LEGACY_V01_MAGIC ? 1 : magic - LEGACY_V02_MAGIC + 2;
            return where + hex + ", the magic number of the legacy zstd v0." + version + " format, which is not supported";
        }
        return where + hex + ", which is not the magic number of a zstd or skippable frame";
    }
}
