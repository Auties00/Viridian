package com.github.auties00.viridian.value;

import java.util.Objects;

/// A `DOMFileSystem` of the non-standard File System API.
///
/// @param type    Chromium's file system type: 0 temporary, 1 persistent, 2 isolated, 3 external
/// @param name    the file system's name
/// @param rootUrl the URL of its root
public record JsDomFileSystem(int type, String name, String rootUrl) implements JsHostObject {
    /// Creates a file system.
    public JsDomFileSystem {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(rootUrl, "rootUrl");
    }
}
