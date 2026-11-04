package com.github.auties00.viridian.value;

import java.util.List;

/// A `FileList`, the files an `<input type="file">` held.
public final class JsFileList implements JsHostObject {
    private final List<JsFile> files;

    JsFileList(List<JsFile> files) {
        this.files = List.copyOf(files);
    }

    /// {@return the files, in order}
    public List<JsFile> files() {
        return files;
    }

    @Override
    public String toString() {
        return "FileList(" + files.size() + ")";
    }
}
