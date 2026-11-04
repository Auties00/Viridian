package com.github.auties00.viridian.value;

/// A JavaScript number primitive: an IEEE 754 double, `NaN`, the infinities and `-0` included.
///
/// Two numbers are equal when their bits are, so `NaN` equals itself and `-0` differs from `0`, as
/// [Double#equals(Object)] has it.
///
/// @param value the number
public record JsNumber(double value) implements JsValue {
    /// {@return whether the number is an integer that a `long` holds exactly}
    public boolean isLong() {
        return value == Math.rint(value) && value >= -0x1p63 && value < 0x1p63 && !(value == 0 && 1 / value < 0);
    }

    /// {@return the number as a `long`}
    ///
    /// @throws ArithmeticException if the number is not an integer a `long` holds exactly
    public long longValue() {
        if (!isLong()) {
            throw new ArithmeticException(this + " is not an integer a long holds exactly");
        }
        return (long) value;
    }

    @Override
    public String toString() {
        return isLong() ? Long.toString((long) value) : Double.toString(value);
    }
}
