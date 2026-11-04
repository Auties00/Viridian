package com.github.auties00.viridian.value;

import java.util.Optional;

/// A JavaScript error object: what structured cloning keeps of it, its constructor, message, stack and cause.
public final class JsError implements JsValue {
    private final Type type;
    private final String message;
    private final String stack;
    // Set after construction, since a cause may refer back to the error
    private JsValue cause;

    JsError(Type type, String message, String stack) {
        this.type = type;
        this.message = message;
        this.stack = stack;
    }

    void setCause(JsValue cause) {
        this.cause = cause;
    }

    /// {@return the error's constructor}
    public Type type() {
        return type;
    }

    /// {@return the message, or an empty optional if the error had none}
    public Optional<String> message() {
        return Optional.ofNullable(message);
    }

    /// {@return the stack trace, or an empty optional if the error had none}
    public Optional<String> stack() {
        return Optional.ofNullable(stack);
    }

    /// {@return the cause, or an empty optional if the error had none}
    public Optional<JsValue> cause() {
        return Optional.ofNullable(cause);
    }

    @Override
    public String toString() {
        return type.constructorName() + (message == null ? "" : ": " + message);
    }

    /// The constructors an error may have been created with.
    public enum Type {
        /// `Error`, and any constructor structured cloning does not distinguish.
        ERROR("Error"),
        /// `EvalError`.
        EVAL("EvalError"),
        /// `RangeError`.
        RANGE("RangeError"),
        /// `ReferenceError`.
        REFERENCE("ReferenceError"),
        /// `SyntaxError`.
        SYNTAX("SyntaxError"),
        /// `TypeError`.
        TYPE("TypeError"),
        /// `URIError`.
        URI("URIError");

        private final String constructorName;

        Type(String constructorName) {
            this.constructorName = constructorName;
        }

        /// {@return the name of the JavaScript constructor}
        public String constructorName() {
            return constructorName;
        }
    }
}
