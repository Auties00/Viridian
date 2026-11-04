package com.github.auties00.viridian;

import java.io.IOException;
import java.util.Objects;

/// Thrown when stored data cannot be read or written: it is damaged, it uses a format Viridian does not know,
/// something it refers to is gone, or a browser is using it.
///
/// I/O failures of the files themselves are plain [IOException]s; this one says the files were reached but what they
/// hold could not be read, or may not be written. A failure with reason [Reason#IN_USE] is always a
/// [ProfileInUseException], which says who holds the profile.
public sealed class ViridianException extends IOException permits ProfileInUseException {
    private final Reason reason;

    /// Creates an exception.
    ///
    /// @param reason  why the data cannot be read or written
    /// @param message what failed, naming the data
    /// @param cause   the failure underneath, or `null`
    /// @throws IllegalArgumentException if `reason` is [Reason#IN_USE], which a [ProfileInUseException] reports
    public ViridianException(Reason reason, String message, Throwable cause) {
        if (reason == Reason.IN_USE) {
            throw new IllegalArgumentException("A profile in use is reported by a ProfileInUseException");
        }
        this(reason, message, cause, true);
    }

    /// Creates an exception with no cause.
    ///
    /// @param reason  why the data cannot be read or written
    /// @param message what failed, naming the data
    /// @throws IllegalArgumentException if `reason` is [Reason#IN_USE], which a [ProfileInUseException] reports
    public ViridianException(Reason reason, String message) {
        this(reason, message, null);
    }

    ViridianException(Reason reason, String message, Throwable cause, boolean ignored) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /// {@return why the data cannot be read or written}
    public Reason reason() {
        return reason;
    }

    /// Why stored data cannot be read or written.
    public enum Reason {
        /// The data does not decode: it is damaged, truncated, or not what it claims to be.
        CORRUPTED,
        /// The data is valid, but uses a format version or a feature Viridian does not support, or a value is newer
        /// than the browser that will read it.
        UNSUPPORTED,
        /// The data refers to something that is not there, such as the file of a stored blob.
        MISSING,
        /// A browser or another writer has the profile or the store open, so it may not be written.
        IN_USE
    }
}
