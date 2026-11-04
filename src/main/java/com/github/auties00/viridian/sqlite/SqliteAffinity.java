package com.github.auties00.viridian.sqlite;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.regex.Pattern;

/// The type affinity of a column: the storage class SQLite prefers for the values it stores there.
///
/// Reading a file needs affinity twice. A REAL column stores a value with no fractional part as an INTEGER to save
/// space, and SQLite converts it back as it reads it, so a reader must too. A record written before
/// `ALTER TABLE ADD COLUMN` lacks the new column, which reads as its `DEFAULT` converted by the column's affinity.
/// Writing a file converts every value it stores, as [#store(Object)] does.
///
/// @spec https://www.sqlite.org/datatype3.html Datatypes In SQLite
enum SqliteAffinity {
    /// No conversion; the affinity of a column with no type or a type naming `BLOB`.
    BLOB,

    /// Numbers become text.
    TEXT,

    /// Text that is a well-formed number becomes an INTEGER when the number has an integer value, else a REAL.
    NUMERIC,

    /// The same conversions as [#NUMERIC], for types naming `INT`.
    INTEGER,

    /// The conversions of [#NUMERIC], after which an INTEGER is read as a REAL.
    REAL;

    /// The four-character windows that decide a type name's affinity, as SQLite packs them into an `int`.
    private static final int CHAR = window("char"), CLOB = window("clob"), TEXT_ = window("text"),
            BLOB_ = window("blob"), REAL_ = window("real"), FLOA = window("floa"), DOUB = window("doub"),
            INT = window("int");

    /// A decimal integer literal with an optional sign.
    private static final Pattern INTEGER_TEXT = Pattern.compile("[+-]?[0-9]+");

    /// A decimal real literal with an optional sign, fraction and exponent.
    private static final Pattern REAL_TEXT = Pattern.compile("[+-]?([0-9]+\\.?[0-9]*|\\.[0-9]+)([eE][+-]?[0-9]+)?");

    /// {@return the characters of `text`, at most four, packed big-endian into an `int`}
    private static int window(String text) {
        var value = 0;
        for (var i = 0; i < text.length(); i++) {
            value = value << 8 | text.charAt(i);
        }
        return value;
    }

    /// {@return the affinity of a type name that is not one of SQLite's standard type names}
    ///
    /// SQLite slides a four-character window over the name: `INT` anywhere gives INTEGER; otherwise `CHAR`, `CLOB` or
    /// `TEXT` gives TEXT; otherwise `BLOB` gives BLOB; otherwise `REAL`, `FLOA` or `DOUB` gives REAL; anything else is
    /// NUMERIC.
    static SqliteAffinity ofTypeName(String type) {
        var affinity = NUMERIC;
        var window = 0;
        for (var i = 0; i < type.length(); i++) {
            var character = type.charAt(i);
            window = window << 8 | (character < 0x80 ? SqliteBytes.lowercase(character) : 0x80);
            if (window == CHAR || window == CLOB || window == TEXT_) {
                affinity = TEXT;
            } else if (window == BLOB_ && (affinity == NUMERIC || affinity == REAL)) {
                affinity = BLOB;
            } else if ((window == REAL_ || window == FLOA || window == DOUB) && affinity == NUMERIC) {
                affinity = REAL;
            } else if ((window & 0x00FFFFFF) == INT) {
                return INTEGER;
            }
        }
        return affinity;
    }

    /// {@return the value a `DEFAULT` literal gives a column of this affinity: `null`, a [Long], a [Double], a
    /// [String] or a `byte[]`}
    ///
    /// A numeric literal is converted as SQLite converts the text it is written as, so `DEFAULT 1.0` gives an INTEGER
    /// or NUMERIC column the integer 1, a TEXT column the string `"1.0"`, and a column with no affinity the REAL 1.0,
    /// since there a literal keeps its own type. An integer literal that fits in 31 bits is converted from its value
    /// instead, so `DEFAULT 007` gives a TEXT column `"7"`.
    Object defaultValue(SqliteSchemaParser.Literal literal) {
        var negations = literal.negations();
        Object value;
        switch (literal.kind()) {
            case NONE, NULL -> {
                return null;
            }
            case BLOB -> {
                return literal.blob();
            }
            case TRUE, FALSE -> {
                return fromInteger(literal.kind() == SqliteSchemaParser.LiteralKind.TRUE ? 1 : 0);
            }
            case INTEGER, REAL -> {
                var small = literal.kind() == SqliteSchemaParser.LiteralKind.INTEGER ? smallInteger(literal.text()) : -1;
                var negative = negations > 0;
                if (negative) {
                    negations--;
                }
                if (small >= 0) {
                    value = fromInteger(negative ? -small : small);
                } else {
                    var text = negative ? "-" + literal.text() : literal.text();
                    value = this == BLOB ? numeric(text, false) : fromText(text);
                }
            }
            default -> value = fromText(literal.text());
        }
        for (var i = 0; i < negations; i++) {
            value = fromNumber(negate(numerify(value)));
        }
        return value;
    }

    /// {@return `value` as SQLite stores it in a column of this affinity: `null`, a [Long], a [Double], a [String] or a
    /// `byte[]`}
    ///
    /// `value` is one of those types, and never a NaN, which SQLite stores as `NULL`. A BLOB is never converted. A TEXT
    /// column stores an integer as its decimal text. The numeric affinities store text that is a well-formed number
    /// as that number, and a REAL with an integer value strictly inside the `long` range as an INTEGER. A REAL column
    /// then keeps an INTEGER of at most six bytes, from -2^47 to 2^47 - 1, as it is, the INTEGER that reads back as a
    /// REAL, and stores any larger INTEGER as the nearest REAL.
    ///
    /// @throws IllegalArgumentException if `value` is a REAL and this is the TEXT affinity: the text SQLite renders a
    ///                                  REAL as depends on its version, so no conversion matches every SQLite
    Object store(Object value) {
        return switch (this) {
            case BLOB -> value;
            case TEXT -> switch (value) {
                case Long integer -> Long.toString(integer);
                case Double _ -> throw new IllegalArgumentException("A REAL stored in a TEXT column becomes text whose form "
                        + "depends on the SQLite version; store the text instead");
                case null, default -> value;
            };
            case NUMERIC, INTEGER -> numeric(value);
            case REAL -> {
                var stored = numeric(value);
                yield stored instanceof Long integer && (integer < -(1L << 47) || integer >= 1L << 47) ? (Object) (double) integer : stored;
            }
        };
    }

    /// {@return `value` converted by the numeric affinities: text that is a well-formed number becomes that number,
    /// and a REAL with an integer value strictly inside the `long` range an INTEGER}
    private static Object numeric(Object value) {
        return switch (value) {
            case Double real -> integral(real);
            case String text -> numeric(text, true);
            case null, default -> value;
        };
    }

    /// {@return the integer `value` converted by this affinity}
    private Object fromInteger(long value) {
        return this == TEXT ? Long.toString(value) : value;
    }

    /// {@return the text `value` converted by this affinity}
    private Object fromText(String value) {
        return this == TEXT || this == BLOB ? value : numeric(value, true);
    }

    /// {@return the number `value` converted by this affinity}
    private Object fromNumber(Object value) {
        return switch (value) {
            case Long integer when this == TEXT -> Long.toString(integer);
            case Double real when this == TEXT -> render(real);
            case Double real when this != BLOB -> integral(real);
            default -> value;
        };
    }

    /// {@return `text` as a number if it is a well-formed one, else `text`}
    ///
    /// SQLite ignores leading and trailing whitespace, and leaves hexadecimal text alone. Text in integer syntax that
    /// fits in a `long` is an INTEGER; other numbers are REALs, converted to an INTEGER when `tryForInteger` is set
    /// and their value is one. A numeric literal in the `DEFAULT` of a column with no affinity keeps its own type,
    /// so for it `tryForInteger` is not set and `DEFAULT 1.0` stays a REAL.
    static Object numeric(String text, boolean tryForInteger) {
        var start = 0;
        var end = text.length();
        while (start < end && isSpace(text.charAt(start))) {
            start++;
        }
        while (end > start && isSpace(text.charAt(end - 1))) {
            end--;
        }
        var trimmed = text.substring(start, end);
        if (INTEGER_TEXT.matcher(trimmed).matches()) {
            try {
                return Long.parseLong(trimmed);
            } catch (NumberFormatException tooLarge) {
                var real = Double.parseDouble(trimmed);
                return tryForInteger ? integral(real) : real;
            }
        }
        if (REAL_TEXT.matcher(trimmed).matches()) {
            var real = Double.parseDouble(trimmed);
            return tryForInteger ? integral(real) : real;
        }
        return text;
    }

    /// {@return whether `character` is whitespace to SQLite's number parser: space, tab, line feed, vertical tab, form
    /// feed or carriage return}
    private static boolean isSpace(char character) {
        return character == ' ' || character >= '\t' && character <= '\r';
    }

    /// {@return `real` as a [Long] if it has an integer value strictly inside the `long` range, else as a [Double]}
    private static Object integral(double real) {
        var integer = (long) real;
        return real == integer && integer > Long.MIN_VALUE && integer < Long.MAX_VALUE ? (Object) integer : (Object) real;
    }

    /// {@return `value` as a number, with text that is not one, a BLOB or anything else reading as 0}
    private static Object numerify(Object value) {
        return switch (value) {
            case null -> null;
            case Long integer -> integer;
            case Double real -> real;
            case String text when numeric(text, true) instanceof Number number -> number;
            default -> 0L;
        };
    }

    /// {@return the negation of `value`, a number or `null`; the negation of the smallest `long` is a REAL}
    private static Object negate(Object value) {
        return switch (value) {
            case Long integer when integer == Long.MIN_VALUE -> -(double) Long.MIN_VALUE;
            case Long integer -> -integer;
            case Double real -> -real;
            case null, default -> value;
        };
    }

    /// {@return `real` in SQLite's text form, 15 significant digits with a `.0` kept on integer values}
    private static String render(double real) {
        if (Double.isInfinite(real)) {
            return real > 0 ? "Inf" : "-Inf";
        }
        if (real == 0) {
            return "0.0";
        }
        var decimal = new BigDecimal(real).round(new MathContext(15)).stripTrailingZeros();
        var exponent = decimal.precision() - decimal.scale() - 1;
        if (exponent < -4 || exponent >= 15) {
            var digits = decimal.unscaledValue().abs().toString();
            var mantissa = digits.length() > 1 ? digits.charAt(0) + "." + digits.substring(1) : digits + ".0";
            return (real < 0 ? "-" : "") + mantissa + "e" + (exponent < 0 ? "-" : "+") + String.format("%02d", Math.abs(exponent));
        }
        var plain = decimal.toPlainString();
        return plain.indexOf('.') < 0 ? plain + ".0" : plain;
    }

    /// {@return the value of an integer literal's text if it fits in 31 bits, else -1}
    ///
    /// SQLite stores such literals as integers rather than text, which matters when a TEXT affinity converts them.
    private static long smallInteger(String text) {
        var position = 0;
        var value = 0L;
        if (text.length() > 2 && text.charAt(0) == '0' && (text.charAt(1) == 'x' || text.charAt(1) == 'X')) {
            position = 2;
            while (position < text.length() && text.charAt(position) == '0') {
                position++;
            }
            var digits = text.length() - position;
            if (digits > 8) {
                return -1;
            }
            value = digits == 0 ? 0 : Long.parseLong(text, position, text.length(), 16);
            return value < 0x80000000L ? value : -1;
        }
        while (position < text.length() && text.charAt(position) == '0') {
            position++;
        }
        var digits = text.length() - position;
        if (digits > 10) {
            return -1;
        }
        value = digits == 0 ? 0 : Long.parseLong(text, position, text.length(), 10);
        return value <= Integer.MAX_VALUE ? value : -1;
    }
}
