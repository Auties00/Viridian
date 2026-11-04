package com.github.auties00.viridian.value;

import java.math.BigInteger;
import java.util.Objects;

/// A JavaScript BigInt primitive.
///
/// @param value the integer
public record JsBigInt(BigInteger value) implements JsPrimitive {
    /// Creates a BigInt.
    public JsBigInt {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value + "n";
    }
}
