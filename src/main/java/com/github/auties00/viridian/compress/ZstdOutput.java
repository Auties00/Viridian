package com.github.auties00.viridian.compress;

import java.io.IOException;
import java.util.Arrays;

/// The growing output of a [Zstd#decompress(byte\[\], int, int, int)] call, and the window its matches copy from.
///
/// Every frame's output is kept until the call returns, so the window is the output itself and a match is a copy
/// within [#buffer]. Writers check each block against [#blockLimit()], the least of three bounds: the frame's maximum
/// block size, the content size the frame header declares, and the caller's `maxLength`; [#overflow(long)] names the
/// one a block crossed.
final class ZstdOutput {
    /// The largest byte array the JVM reliably allocates.
    private static final int MAX_ARRAY_LENGTH = Integer.MAX_VALUE - 8;

    /// The decoded bytes, in `buffer[0, size)`.
    byte[] buffer = new byte[0];

    /// The number of bytes decoded so far, across all frames.
    int size;

    /// The most bytes the caller lets the output reach.
    private final int maxLength;

    /// The least of `maxLength` and the largest array, the hard ceiling of [#buffer].
    private final int capacityLimit;

    /// The index in [#buffer] where the current frame's output starts.
    int frameStart;

    /// The content size the current frame header declares, or `-1` when it declares none.
    private long declaredSize = -1;

    /// The current frame's maximum block size.
    private int blockMax;

    /// Creates an empty output that may grow to `maxLength` bytes.
    ZstdOutput(int maxLength) {
        this.maxLength = maxLength;
        this.capacityLimit = Math.min(maxLength, MAX_ARRAY_LENGTH);
    }

    /// Starts a frame whose blocks are at most `blockMax` bytes, and reserves room for its output when its header
    /// declares a size.
    ///
    /// A declared size is reserved at once, up to what the rest of the input can produce: every block takes at least
    /// a 3-byte header and yields at most `blockMax` bytes, so a header claiming far more than its input holds cannot
    /// force a large allocation. The first output is reserved exactly, so a single frame needs no final copy; a later
    /// frame grows the buffer by at least doubling it, so that many small frames do not recopy the output each time.
    ///
    /// @param declaredSize   the content size from the frame header, or `-1` when it has none
    /// @param remainingInput the number of input bytes after the frame header
    /// @throws IOException if the declared size takes the output past `maxLength`
    void startFrame(long declaredSize, int blockMax, int remainingInput) throws IOException {
        this.frameStart = size;
        this.declaredSize = declaredSize;
        this.blockMax = blockMax;
        if (declaredSize >= 0) {
            if (declaredSize > (long) maxLength - size) {
                throw new IOException("A zstd frame declares " + declaredSize + " bytes, which take the output past the " + maxLength + " allowed");
            }
            var producible = ((long) remainingInput / 3 + 1) * blockMax;
            var capacity = (int) Math.min(size + Math.min(declaredSize, producible), capacityLimit);
            if (size == 0) {
                reserveExactly(capacity);
            } else {
                ensureCapacity(capacity);
            }
        }
    }

    /// {@return the index the current block's output may not pass}
    int blockLimit() {
        var limit = (long) size + blockMax;
        if (declaredSize >= 0) {
            limit = Math.min(limit, frameStart + declaredSize);
        }
        return (int) Math.min(limit, capacityLimit);
    }

    /// Makes [#buffer] hold at least `capacity` bytes, at least doubling it so that a frame without a declared size
    /// grows in few steps.
    ///
    /// @param capacity at most the ceiling of [#buffer], which every block limit respects
    void ensureCapacity(int capacity) {
        if (capacity > buffer.length) {
            buffer = Arrays.copyOf(buffer, (int) Math.min(Math.max((long) buffer.length * 2, capacity), capacityLimit));
        }
    }

    /// Appends `source[offset, offset + length)`, the content of a raw block.
    ///
    /// @throws IOException if the block takes the output past a limit
    void write(byte[] source, int offset, int length) throws IOException {
        if (length > blockLimit() - size) {
            throw overflow((long) size + length);
        }
        ensureCapacity(size + length);
        System.arraycopy(source, offset, buffer, size, length);
        size += length;
    }

    /// Appends `length` copies of `value`, the content of an RLE block.
    ///
    /// @throws IOException if the block takes the output past a limit
    void fill(byte value, int length) throws IOException {
        if (length > blockLimit() - size) {
            throw overflow((long) size + length);
        }
        ensureCapacity(size + length);
        Arrays.fill(buffer, size, size + length, value);
        size += length;
    }

    /// {@return the exception for a block whose output would reach `needed` bytes, naming the limit it crosses}
    IOException overflow(long needed) {
        if (needed > maxLength) {
            return new IOException("The zstd frames decompress to more than the " + maxLength + " bytes allowed");
        }
        if (needed > capacityLimit) {
            return new IOException("The zstd frames decompress to more bytes than an array can hold");
        }
        if (declaredSize >= 0 && needed > frameStart + declaredSize) {
            return new IOException("A zstd frame decompresses to more than the " + declaredSize + " bytes its header declares");
        }
        return new IOException("A zstd block decompresses to more than the frame's maximum block size of " + blockMax + " bytes");
    }

    /// {@return the decoded bytes, in an array of exactly their length}
    byte[] toByteArray() {
        return size == buffer.length ? buffer : Arrays.copyOf(buffer, size);
    }

    /// Grows [#buffer] to exactly `capacity` bytes when it is smaller, for a frame whose size is known.
    private void reserveExactly(int capacity) {
        if (capacity > buffer.length) {
            buffer = Arrays.copyOf(buffer, capacity);
        }
    }
}
