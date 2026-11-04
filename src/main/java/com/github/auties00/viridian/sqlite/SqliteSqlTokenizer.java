package com.github.auties00.viridian.sqlite;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/// Splits SQL text into tokens the way SQLite's own tokenizer does, as far as `CREATE` statements need.
///
/// Whitespace and both comment forms, `-- ...` to the end of the line and `/* ... */` (possibly unterminated), are
/// skipped. Identifiers may be bare or quoted three ways, `"x"`, `` `x` `` (both with the quote doubled to escape it)
/// and `[x]`; a bare identifier is any run of ASCII letters, digits, `_`, `$` and characters above U+007F, so keywords
/// come out as identifiers too and the parser tells them apart. Numbers keep their text with any `_` digit
/// separators removed.
///
/// @spec https://www.sqlite.org/lang_keywords.html SQLite Keywords
final class SqliteSqlTokenizer {
    /// The kind of a token.
    enum Kind {
        /// A bare identifier or keyword.
        WORD,
        /// An identifier in `"..."`, `` `...` `` or `[...]`.
        QUOTED,
        /// A string literal in `'...'`.
        STRING,
        /// A numeric literal.
        NUMBER,
        /// A BLOB literal, `x'...'`.
        BLOB,
        /// A bound-parameter placeholder, such as `?1` or `:name`.
        VARIABLE,
        /// Punctuation or an operator.
        SYMBOL,
        /// The end of the text.
        END
    }

    /// A token.
    ///
    /// @param kind  the kind of token
    /// @param text  the identifier without its quotes, the string without its quotes and escapes, the BLOB's hex
    ///              digits, or the token as written for the other kinds
    /// @param start the offset of the token's first character in the SQL text
    /// @param end   the offset just past the token's last character
    record Token(Kind kind, String text, int start, int end) {
    }

    /// The symbols longer than one character, longest first so that `->>` is not read as `->`.
    private static final String[] LONG_SYMBOLS = {"->>", "||", "<=", ">=", "<>", "!=", "==", "<<", ">>", "->"};

    /// Never instantiated.
    private SqliteSqlTokenizer() {
    }

    /// {@return the tokens of `sql`, ending with one of kind [Kind#END]}
    ///
    /// @throws IOException if a quoted identifier, string or BLOB literal is unterminated or malformed
    static List<Token> tokenize(String sql) throws IOException {
        var tokens = new ArrayList<Token>();
        var position = 0;
        var length = sql.length();
        while (true) {
            while (position < length && isSpace(sql.charAt(position))) {
                position++;
            }
            if (position >= length) {
                tokens.add(new Token(Kind.END, "", length, length));
                return tokens;
            }
            var start = position;
            var current = sql.charAt(position);
            var following = position + 1 < length ? sql.charAt(position + 1) : '\0';
            if (current == '-' && following == '-') {
                while (position < length && sql.charAt(position) != '\n') {
                    position++;
                }
            } else if (current == '/' && following == '*') {
                var close = sql.indexOf("*/", position + 2);
                position = close < 0 ? length : close + 2;
            } else if (current == '\'' || current == '"' || current == '`') {
                var builder = new StringBuilder();
                position = quoted(sql, position, current, builder);
                tokens.add(new Token(current == '\'' ? Kind.STRING : Kind.QUOTED, builder.toString(), start, position));
            } else if (current == '[') {
                var close = sql.indexOf(']', position + 1);
                if (close < 0) {
                    throw new IOException("The [identifier] at offset " + start + " is never closed");
                }
                position = close + 1;
                tokens.add(new Token(Kind.QUOTED, sql.substring(start + 1, close), start, position));
            } else if ((current == 'x' || current == 'X') && following == '\'') {
                var close = sql.indexOf('\'', position + 2);
                if (close < 0) {
                    throw new IOException("The BLOB literal at offset " + start + " is never closed");
                }
                var digits = sql.substring(position + 2, close);
                if ((digits.length() & 1) != 0 || !digits.chars().allMatch(SqliteSqlTokenizer::isHexDigit)) {
                    throw new IOException("The BLOB literal at offset " + start + " is not an even number of hex digits");
                }
                position = close + 1;
                tokens.add(new Token(Kind.BLOB, digits, start, position));
            } else if (isDigit(current) || current == '.' && isDigit(following)) {
                position = number(sql, position);
                tokens.add(new Token(Kind.NUMBER, sql.substring(start, position).replace("_", ""), start, position));
            } else if (current == '?' || current == ':' || current == '@' || current == '$') {
                position++;
                while (position < length && isIdentifierCharacter(sql.charAt(position))) {
                    position++;
                }
                tokens.add(new Token(Kind.VARIABLE, sql.substring(start, position), start, position));
            } else if (isIdentifierCharacter(current)) {
                while (position < length && isIdentifierCharacter(sql.charAt(position))) {
                    position++;
                }
                tokens.add(new Token(Kind.WORD, sql.substring(start, position), start, position));
            } else {
                var symbol = String.valueOf(current);
                for (var candidate : LONG_SYMBOLS) {
                    if (sql.startsWith(candidate, position)) {
                        symbol = candidate;
                        break;
                    }
                }
                position += symbol.length();
                tokens.add(new Token(Kind.SYMBOL, symbol, start, position));
            }
        }
    }

    /// Reads the quoted token that starts with `quote` at `start` into `builder`, undoing doubled quotes.
    ///
    /// @return the offset just past the closing quote
    private static int quoted(String sql, int start, char quote, StringBuilder builder) throws IOException {
        var position = start + 1;
        while (true) {
            var close = sql.indexOf(quote, position);
            if (close < 0) {
                throw new IOException("The quoted token at offset " + start + " is never closed");
            }
            builder.append(sql, position, close);
            if (close + 1 < sql.length() && sql.charAt(close + 1) == quote) {
                builder.append(quote);
                position = close + 2;
            } else {
                return close + 1;
            }
        }
    }

    /// {@return the offset just past the numeric literal at `start`: hexadecimal, or decimal with an optional
    /// fraction and exponent}
    private static int number(String sql, int start) {
        var length = sql.length();
        var position = start;
        if (sql.charAt(position) == '0' && position + 2 < length
                && (sql.charAt(position + 1) == 'x' || sql.charAt(position + 1) == 'X')
                && isHexDigit(sql.charAt(position + 2))) {
            position += 2;
            while (position < length && (isHexDigit(sql.charAt(position)) || sql.charAt(position) == '_')) {
                position++;
            }
            return position;
        }
        while (position < length && (isDigit(sql.charAt(position)) || sql.charAt(position) == '_')) {
            position++;
        }
        if (position < length && sql.charAt(position) == '.') {
            position++;
            while (position < length && (isDigit(sql.charAt(position)) || sql.charAt(position) == '_')) {
                position++;
            }
        }
        if (position < length && (sql.charAt(position) == 'e' || sql.charAt(position) == 'E')) {
            var exponent = position + 1;
            if (exponent < length && (sql.charAt(exponent) == '+' || sql.charAt(exponent) == '-')) {
                exponent++;
            }
            if (exponent < length && isDigit(sql.charAt(exponent))) {
                position = exponent;
                while (position < length && isDigit(sql.charAt(position))) {
                    position++;
                }
            }
        }
        return position;
    }

    /// {@return whether `character` is whitespace to SQLite: space, tab, line feed, form feed or carriage return}
    private static boolean isSpace(char character) {
        return character == ' ' || character == '\t' || character == '\n' || character == '\f' || character == '\r';
    }

    /// {@return whether `character` is an ASCII digit}
    private static boolean isDigit(char character) {
        return character >= '0' && character <= '9';
    }

    /// {@return whether `character` is an ASCII hexadecimal digit}
    private static boolean isHexDigit(int character) {
        return character >= '0' && character <= '9' || character >= 'a' && character <= 'f' || character >= 'A' && character <= 'F';
    }

    /// {@return whether `character` can be part of a bare identifier}
    private static boolean isIdentifierCharacter(char character) {
        return character >= 'a' && character <= 'z'
                || character >= 'A' && character <= 'Z'
                || isDigit(character)
                || character == '_'
                || character == '$'
                || character >= 0x80;
    }
}
