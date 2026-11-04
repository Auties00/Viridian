package com.github.auties00.viridian;

import java.io.IOException;
import java.util.Objects;

/// Thrown when stored data cannot be read: it is damaged, it uses a format this reader does not know, or something it
/// refers to is gone.
///
/// I/O failures of the files themselves are plain [IOException]s; this one says the files were read but what they
/// hold could not be.
public final class ViridianException extends IOException {
    private final Reason reason;

    /// Creates an exception.
    ///
    /// @param reason  why the data cannot be read
    /// @param message what failed, naming the data
    /// @param cause   the failure underneath, or `null`
    public ViridianException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /// Creates an exception with no cause.
    ///
    /// @param reason  why the data cannot be read
    /// @param message what failed, naming the data
    public ViridianException(Reason reason, String message) {
        this(reason, message, null);
    }

    /// {@return why the data cannot be read}
    public Reason reason() {
        return reason;
    }

    /// Why stored data cannot be read.
    public enum Reason {
        /// The data does not decode: it is damaged, truncated, or not what it claims to be.
        CORRUPTED,
        /// The data is valid, but uses a format version or a feature this reader does not support.
        UNSUPPORTED,
        /// The data refers to something that is not there, such as the file of a stored blob.
        MISSING
    }
}
