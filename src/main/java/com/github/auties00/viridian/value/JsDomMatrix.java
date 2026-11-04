package com.github.auties00.viridian.value;

import java.util.Arrays;

/// A `DOMMatrix` or `DOMMatrixReadOnly`.
///
/// Matrices compare by their values, but are objects all the same: a value that holds one matrix twice holds it by
/// identity, as [JsValue] describes.
public final class JsDomMatrix implements JsHostObject {
    private final double[] values;
    private final boolean readOnly;

    JsDomMatrix(double[] values, boolean readOnly) {
        this.values = values;
        this.readOnly = readOnly;
    }

    /// {@return a matrix of `values`, copied: 6 for a 2D matrix, `a` to `f`, or 16 for a 3D one, `m11` to `m44` row
    /// by row}
    ///
    /// @param readOnly whether it is a `DOMMatrixReadOnly`
    /// @throws IllegalArgumentException if there are not 6 or 16 values
    public static JsDomMatrix of(double[] values, boolean readOnly) {
        if (values.length != 6 && values.length != 16) {
            throw new IllegalArgumentException("A matrix has 6 or 16 values, not " + values.length);
        }
        return new JsDomMatrix(values.clone(), readOnly);
    }

    /// {@return whether the matrix is 2D}
    public boolean is2D() {
        return values.length == 6;
    }

    /// {@return a copy of the values: `a` to `f` for a 2D matrix, `m11` to `m44` row by row for a 3D one}
    public double[] values() {
        return values.clone();
    }

    /// {@return the values themselves}
    double[] rawValues() {
        return values;
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
