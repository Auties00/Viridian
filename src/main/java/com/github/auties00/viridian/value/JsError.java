package com.github.auties00.viridian.value;

import java.util.Objects;
import java.util.Optional;

/// A JavaScript error object: what structured cloning keeps of it, its constructor, message, stack and cause.
///
/// An error is immutable; [#builder(Type)] builds one, with a cause that may be the error itself.
public final class JsError implements JsValue {
    private final Type type;
    // Set while the error is built, since a cause may refer back to it
    private String message;
    private String stack;
    private JsValue cause;

    JsError(Type type, String message, String stack) {
        this.type = type;
        this.message = message;
        this.stack = stack;
    }

    /// {@return a builder of an error of `type`, with no message, stack or cause until it is given them}
    public static Builder builder(Type type) {
        return new Builder(Objects.requireNonNull(type, "type"));
    }

    /// {@return an error of `type` with `message`, or no message if it is `null`, and no stack or cause}
    public static JsError of(Type type, String message) {
        return new JsError(Objects.requireNonNull(type, "type"), message, null);
    }

    /// Sets the cause, as the decoder and the builder fill an error.
    void setCause(JsValue cause) {
        this.cause = cause;
    }

    /// {@return the error's constructor}
    public Type type() {
        return type;
    }

    /// {@return the message, or an empty optional if the error has none}
    ///
    /// A message is an own property of the error, as `new Error("")` makes even an empty one; an error made without
    /// a message has none.
    public Optional<String> message() {
        return Optional.ofNullable(message);
    }

    /// {@return the stack trace, or an empty optional if the error has none}
    public Optional<String> stack() {
        return Optional.ofNullable(stack);
    }

    /// {@return the cause, or an empty optional if the error has none}
    ///
    /// A cause that is `undefined` is a cause all the same: `new Error("", {cause: undefined})` has one.
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

    /// A builder of a [JsError], whose cause may be a builder of another value, itself included, as [JsBuilder]
    /// describes.
    public static final class Builder implements JsBuilder<JsError> {
        final JsError instance;
        final BuilderState state;

        private Builder(Type type) {
            this.instance = new JsError(type, null, null);
            this.state = new BuilderState();
        }

        /// Sets the message; `null` removes it.
        ///
        /// @return this builder
        /// @throws IllegalStateException if the builder already built its error
        public Builder message(String message) {
            state.checkOpen();
            instance.message = message;
            return this;
        }

        /// Sets the stack trace; `null` removes it.
        ///
        /// @return this builder
        /// @throws IllegalStateException if the builder already built its error
        public Builder stack(String stack) {
            state.checkOpen();
            instance.stack = stack;
            return this;
        }

        /// Sets the cause; `null` removes it.
        ///
        /// @return this builder
        /// @throws IllegalStateException if the builder already built its error
        public Builder cause(JsValue cause) {
            state.checkOpen();
            instance.cause = cause;
            return this;
        }

        /// Sets the cause to the value `cause` builds.
        ///
        /// @return this builder
        /// @throws IllegalStateException if the builder already built its error
        public Builder cause(JsBuilder<?> cause) {
            instance.cause = state.hold(cause);
            return this;
        }

        @Override
        public JsError build() {
            state.build();
            return instance;
        }
    }
}
