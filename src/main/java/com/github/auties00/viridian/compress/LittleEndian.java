package com.github.auties00.viridian.compress;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/// Reads the little-endian integers every multi-byte field of the zstd format, and the bit containers of its
/// streams, are stored as.
///
/// The 16, 32 and 64-bit reads go through byte-array views, which the JIT compiles to single unaligned loads. Callers
/// check their bounds first; the views check them again and throw [IndexOutOfBoundsException], which a caller's
/// mistake, never hostile input, can reach.
final class LittleEndian {
    /// The view that reads a little-endian `short` at any byte offset.
    private static final VarHandle SHORT = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);

    /// The view that reads a little-endian `int` at any byte offset.
    private static final VarHandle INT = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    /// The view that reads a little-endian `long` at any byte offset.
    private static final VarHandle LONG = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    private LittleEndian() {
    }

    /// {@return the unsigned 16-bit value at `array[offset, offset + 2)`}
    static int readShort(byte[] array, int offset) {
        return (short) SHORT.get(array, offset) & 0xFFFF;
    }

    /// {@return the unsigned 24-bit value at `array[offset, offset + 3)`}
    static int readMedium(byte[] array, int offset) {
        return (array[offset] & 0xFF) | (array[offset + 1] & 0xFF) << 8 | (array[offset + 2] & 0xFF) << 16;
    }

    /// {@return the 32-bit value at `array[offset, offset + 4)`, negative when its top bit is set}
    static int readInt(byte[] array, int offset) {
        return (int) INT.get(array, offset);
    }

    /// {@return the 64-bit value at `array[offset, offset + 8)`, negative when its top bit is set}
    static long readLong(byte[] array, int offset) {
        return (long) LONG.get(array, offset);
    }
}
