package com.github.auties00.viridian.value;

import java.util.Arrays;

/// A `DOMMatrix` or `DOMMatrixReadOnly`.
public final class JsDomMatrix implements JsHostObject {
    private final double[] values;
    private final boolean readOnly;

    JsDomMatrix(double[] values, boolean readOnly) {
        this.values = values;
        this.readOnly = readOnly;
    }

    /// {@return whether the matrix is 2D}
    public boolean is2D() {
        return values.length == 6;
    }

    /// {@return a copy of the values: `a` to `f` for a 2D matrix, `m11` to `m44` row by row for a 3D one}
    public double[] values() {
        return values.clone();
    }

    /// {@return whether it is a `DOMMatrixReadOnly`}
    public boolean readOnly() {
        return readOnly;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof JsDomMatrix matrix && readOnly == matrix.readOnly && Arrays.equals(values, matrix.values);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(values) + Boolean.hashCode(readOnly);
    }

    @Override
    public String toString() {
        return (readOnly ? "DOMMatrixReadOnly" : "DOMMatrix") + Arrays.toString(values);
    }
}
