package com.github.auties00.viridian.compress;

/// Computes XXH64, the 64-bit xxHash: with seed 0, its low 32 bits are the content checksum a zstd frame may end
/// with.
///
/// @spec https://github.com/Cyan4973/xxHash/blob/dev/doc/xxhash_spec.md xxHash fast digest algorithm
final class Xxh64 {
    /// The first of the five 64-bit primes the algorithm multiplies by.
    private static final long PRIME_1 = 0x9E3779B185EBCA87L;

    /// The second 64-bit prime.
    private static final long PRIME_2 = 0xC2B2AE3D27D4EB4FL;

    /// The third 64-bit prime.
    private static final long PRIME_3 = 0x165667B19E3779F9L;

    /// The fourth 64-bit prime.
    private static final long PRIME_4 = 0x85EBCA77C2B2AE63L;

    /// The fifth 64-bit prime.
    private static final long PRIME_5 = 0x27D4EB2F165667C5L;

    private Xxh64() {
    }

    /// {@return the XXH64 digest of `input[offset, offset + length)` under `seed`}
    static long hash(byte[] input, int offset, int length, long seed) {
        var end = offset + length;
        var position = offset;
        long hash;
        if (length >= 32) {
            var accumulator1 = seed + PRIME_1 + PRIME_2;
            var accumulator2 = seed + PRIME_2;
            var accumulator3 = seed;
            var accumulator4 = seed - PRIME_1;
            do {
                accumulator1 = round(accumulator1, LittleEndian.readLong(input, position));
                accumulator2 = round(accumulator2, LittleEndian.readLong(input, position + 8));
                accumulator3 = round(accumulator3, LittleEndian.readLong(input, position + 16));
                accumulator4 = round(accumulator4, LittleEndian.readLong(input, position + 24));
                position += 32;
            } while (end - position >= 32);
            hash = Long.rotateLeft(accumulator1, 1) + Long.rotateLeft(accumulator2, 7)
                    + Long.rotateLeft(accumulator3, 12) + Long.rotateLeft(accumulator4, 18);
            hash = merge(hash, accumulator1);
            hash = merge(hash, accumulator2);
            hash = merge(hash, accumulator3);
            hash = merge(hash, accumulator4);
        } else {
            hash = seed + PRIME_5;
        }
        hash += length;
        while (end - position >= 8) {
            hash ^= round(0, LittleEndian.readLong(input, position));
            hash = Long.rotateLeft(hash, 27) * PRIME_1 + PRIME_4;
            position += 8;
        }
        if (end - position >= 4) {
            hash ^= (LittleEndian.readInt(input, position) & 0xFFFFFFFFL) * PRIME_1;
            hash = Long.rotateLeft(hash, 23) * PRIME_2 + PRIME_3;
            position += 4;
        }
        while (position < end) {
            hash ^= (input[position] & 0xFF) * PRIME_5;
            hash = Long.rotateLeft(hash, 11) * PRIME_1;
            position++;
        }
        hash ^= hash >>> 33;
        hash *= PRIME_2;
        hash ^= hash >>> 29;
        hash *= PRIME_3;
        hash ^= hash >>> 32;
        return hash;
    }

    /// {@return `accumulator` after absorbing one 8-byte lane}
    private static long round(long accumulator, long lane) {
        accumulator += lane * PRIME_2;
        accumulator = Long.rotateLeft(accumulator, 31);
        return accumulator * PRIME_1;
    }

    /// {@return `hash` after folding in one of the four stripe accumulators}
    private static long merge(long hash, long accumulator) {
        hash ^= round(0, accumulator);
        return hash * PRIME_1 + PRIME_4;
    }
}
