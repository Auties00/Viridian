package com.github.auties00.viridian.io;

import com.github.auties00.viridian.ViridianException;
import com.github.auties00.viridian.value.BlobSource;
import com.github.auties00.viridian.value.JsBlob;
import com.github.auties00.viridian.value.JsFile;
import com.github.auties00.viridian.value.JsValue;

import java.time.Instant;
import java.util.List;

/// What the stores of this module may do with values beyond the public API of the value package: make the blobs and
/// files a store describes as it recorded them, decode a stored value with the File System Access tokens stored beside
/// it, and inject a record's key into a value as Blink does when it reads one back.
///
/// The value package registers the one implementation when it is first used; this package is not exported, so no
/// caller outside the module reaches it.
public abstract class ValueAccess {
    private static volatile ValueAccess instance;

    /// Creates the implementation, which only the value package does.
    protected ValueAccess() {
    }

    /// Makes `access` the implementation, as the value package does once.
    ///
    /// @throws IllegalStateException if there is one already
    public static void register(ValueAccess access) {
        synchronized (ValueAccess.class) {
            if (instance != null) {
                throw new IllegalStateException("The value access is registered already");
            }
            instance = access;
        }
    }

    /// {@return the implementation, loading the value package if it has not registered it yet}
    public static ValueAccess get() {
        var access = instance;
        if (access == null) {
            try {
                Class.forName("com.github.auties00.viridian.value.StructuredClone", true, ValueAccess.class.getClassLoader());
            } catch (ClassNotFoundException exception) {
                throw new IllegalStateException("The value package is missing", exception);
            }
            access = instance;
        }
        return access;
    }

    /// {@return a blob as a store recorded it: its type as stored, and its size}
    ///
    /// @param source where its bytes are
    public abstract JsBlob blob(String type, long size, BlobSource source);

    /// {@return a file as a store recorded it: its name and type as stored, its size and its modification time, or
    /// `null` if none was recorded}
    ///
    /// @param source where its bytes are
    public abstract JsFile file(String name, String type, long size, Instant lastModified, BlobSource source);

    /// {@return the value `data[offset, offset + length)` holds}
    ///
    /// @param blobs  the blobs and files stored beside the value, in index order
    /// @param tokens the File System Access tokens stored beside it, in index order
    /// @throws ViridianException if the value does not decode
    public abstract JsValue decode(byte[] data, int offset, int length, List<? extends JsBlob> blobs, List<byte[]> tokens)
            throws ViridianException;

    /// Sets the property `path` names in `value`, which nothing but the caller has seen yet, to `key`, creating the
    /// objects on the way that `value` lacks, as Blink injects a generated key into a value it reads.
    ///
    /// @param path the identifiers of a key path, at least one
    /// @return whether the key is in place: `false` if a step of the path reached a value that holds no properties
    public abstract boolean inject(JsValue value, List<String> path, JsValue key);
}
