package com.github.auties00.viridian.value;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// JavaScript's number-to-string conversion, which property keys depend on, and array index detection.
class JsNumbersTest {
    @ParameterizedTest
    @CsvSource({
            "0, 0",
            "-0.0, 0",
            "1, 1",
            "-1.5, -1.5",
            "123.456, 123.456",
            "0.000001, 0.000001",
            "0.0000001, 1e-7",
            "1.5e-7, 1.5e-7",
            "1e21, 1e+21",
            "123456789012345680000, 123456789012345680000",
            "1.7976931348623157e308, 1.7976931348623157e+308",
            "5e-324, 5e-324",
            "9007199254740992, 9007199254740992",
            "0.1, 0.1"
    })
    @DisplayName("numbers become the strings JavaScript makes of them")
    void toJsString(double value, String expected) {
        assertEquals(expected, JsNumbers.toJsString(value));
    }

    @Test
    @DisplayName("every number's string parses back to it, and has no more digits than Java's own")
    void roundTrips() {
        var random = new java.util.Random(13);
        for (var i = 0; i < 200_000; i++) {
            var value = Double.longBitsToDouble(random.nextLong());
            if (!Double.isFinite(value)) {
                continue;
            }
            var string = JsNumbers.toJsString(value);
            assertEquals(value == 0 ? 0 : value, Double.parseDouble(string), string);
            var digits = string.replaceFirst("e.*", "").replace("-", "").replace(".", "").replaceFirst("^0+", "").replaceFirst("0+$", "");
            var java = new java.math.BigDecimal(Double.toString(Math.abs(value))).stripTrailingZeros().precision();
            org.junit.jupiter.api.Assertions.assertTrue(digits.length() <= java, () -> string + " has more digits than " + value);
        }
    }

    @Test
    @DisplayName("NaN and the infinities")
    void specials() {
        assertEquals("NaN", JsNumbers.toJsString(Double.NaN));
        assertEquals("Infinity", JsNumbers.toJsString(Double.POSITIVE_INFINITY));
        assertEquals("-Infinity", JsNumbers.toJsString(Double.NEGATIVE_INFINITY));
    }

    @Test
    @DisplayName("only canonical integers below 2^32 - 1 are array indexes")
    void arrayIndexes() {
        assertEquals(0, JsNumbers.arrayIndex("0"));
        assertEquals(4294967294L, JsNumbers.arrayIndex("4294967294"));
        assertEquals(-1, JsNumbers.arrayIndex("4294967295"));
        assertEquals(-1, JsNumbers.arrayIndex("01"));
        assertEquals(-1, JsNumbers.arrayIndex("1.5"));
        assertEquals(-1, JsNumbers.arrayIndex(""));
    }

    @Test
    @DisplayName("regular expression flags come out in the order JavaScript lists them")
    void flags() {
        assertEquals("dgimsuvy", JsNumbers.regExpFlags(0b1_1011_1111));
        assertEquals("", JsNumbers.regExpFlags(0));
    }
}
