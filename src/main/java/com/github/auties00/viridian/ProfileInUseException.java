package com.github.auties00.viridian;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/// Thrown when a profile may not be written because a browser, an app or another writer has it open.
///
/// The reason is always [ViridianException.Reason#IN_USE]. A write that fails with it changed nothing; the caller may
/// wait for the holder to close the profile, as [ChromiumProfile#isInUse()] tells, and try again.
///
/// {@snippet lang = java :
/// try {
///     profile.write(edit -> edit.localStorage().replaceAll(origin, items));
/// } catch (ProfileInUseException inUse) {
///     System.err.println("Close the browser using " + inUse.lock()
///             + inUse.process().map(process -> " (process " + process.pid() + ")").orElse(""));
/// }
/// }
public final class ProfileInUseException extends ViridianException {
    private final Path lock;
    private final transient ProcessHandle process;

    /// Creates an exception.
    ///
    /// @param lock    the lock the holder has: a browser's `lockfile` or `SingletonLock`, or a store's `LOCK` file
    /// @param process the process that holds it, or `null` if it is not known
    /// @param message what is in use, naming the profile
    public ProfileInUseException(Path lock, ProcessHandle process, String message) {
        super(Reason.IN_USE, message, null, true);
        this.lock = Objects.requireNonNull(lock, "lock");
        this.process = process;
    }

    /// {@return the lock the holder has: a browser's `lockfile` or `SingletonLock`, or a store's `LOCK` file}
    public Path lock() {
        return lock;
    }

    /// {@return the process that holds the lock, or an empty optional if it is not known}
    ///
    /// A `SingletonLock` names the process that holds it when that process runs on this host; a Windows `lockfile`
    /// and a store's `LOCK` file do not name theirs.
    public Optional<ProcessHandle> process() {
        return Optional.ofNullable(process);
    }
}
