package com.github.auties00.viridian.value;

import java.math.BigDecimal;

/// The number conversions JavaScript applies to property keys and flags.
final class JsNumbers {
    // The regular expression flags in the order V8 lists them, with their bits
    private static final char[] FLAG_CHARACTERS = {'d', 'g', 'i', 'l', 'm', 's', 'u', 'v', 'y'};
    private static final int[] FLAG_BITS = {7, 0, 1, 6, 2, 5, 4, 8, 3};

    private JsNumbers() {
    }

    /// {@return the string JavaScript makes of property key `key`, a string or a number}
    static String propertyKey(JsValue key) {
        return switch (key) {
            case JsString string -> string.value();
            case JsNumber number -> toJsString(number.value());
            default -> throw new IllegalArgumentException("Not a property key: " + key);
        };
    }

    /// {@return the index `key` names, if it is the canonical string of an array index, else -1}
    static long arrayIndex(String key) {
        var length = key.length();
        if (length == 0 || length > 10 || (length > 1 && key.charAt(0) == '0')) {
            return -1;
        }
        long value = 0;
        for (var i = 0; i < length; i++) {
            var digit = key.charAt(i) - '0';
            if (digit < 0 || digit > 9) {
                return -1;
            }
            value = value * 10 + digit;
        }
        // Array indexes stop one below the longest length
        return value < JsArray.MAX_LENGTH ? value : -1;
    }

    /// {@return the flags string of a regular expression, from V8's flag bits}
    static String regExpFlags(int bits) {
        var flags = new StringBuilder();
        for (var i = 0; i < FLAG_CHARACTERS.length; i++) {
            if ((bits & (1 << FLAG_BITS[i])) != 0) {
                flags.append(FLAG_CHARACTERS[i]);
            }
        }
        return flags.toString();
    }

    /// {@return the shortest decimal that rounds to `value`, positive and finite, the closest of them if several}
    ///
    /// [Double#toString(double)] finds the shortest digits but writes at least two in scientific notation, where one
    /// may suffice: `Number.MIN_VALUE` is `4.9E-324` to Java and `5e-324` to JavaScript. One digit is tried then.
    private static BigDecimal shortest(double value) {
        var decimal = new BigDecimal(Double.toString(value)).stripTrailingZeros();
        if (decimal.precision() != 2) {
            return decimal;
        }
        var exact = new BigDecimal(value);
        BigDecimal best = null;
        for (var candidate : new BigDecimal[]{decimal.round(new java.math.MathContext(1, java.math.RoundingMode.FLOOR)),
                decimal.round(new java.math.MathContext(1, java.math.RoundingMode.CEILING))}) {
            if (candidate.doubleValue() == value
                    && (best == null || candidate.subtract(exact).abs().compareTo(best.subtract(exact).abs()) < 0)) {
                best = candidate;
            }
        }
        return best == null ? decimal : best.stripTrailingZeros();
    }

    /// {@return `value` as JavaScript's `Number.prototype.toString` writes it}
    ///
    /// The digits are the shortest that round back to the double; the layout, plain up to 21 digits and exponential
    /// beyond, is JavaScript's.
    ///
    /// @spec https://tc39.es/ecma262/#sec-numeric-types-number-tostring Number::toString
    static String toJsString(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (value == 0) {
            return "0";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "Infinity" : "-Infinity";
        }
        if (value < 0) {
            return "-" + toJsString(-value);
        }
        var decimal = shortest(value);
        var digits = decimal.unscaledValue().toString();
        var k = digits.length();
        // value = digits * 10^(n - k)
        var n = k - decimal.scale();
        var result = new StringBuilder();
        if (k <= n && n <= 21) {
            result.append(digits).repeat('0', n - k);
        } else if (0 < n && n <= 21) {
            result.append(digits, 0, n).append('.').append(digits, n, k);
        } else if (-6 < n && n <= 0) {
            result.append("0.").repeat('0', -n).append(digits);
        } else {
            var exponent = n - 1;
            result.append(digits.charAt(0));
            if (k > 1) {
                result.append('.').append(digits, 1, k);
            }
            result.append('e').append(exponent < 0 ? '-' : '+').append(Math.abs(exponent));
        }
        return result.toString();
    }
}
