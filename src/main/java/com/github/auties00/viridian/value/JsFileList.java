package com.github.auties00.viridian.value;

import java.util.Arrays;
import java.util.List;

/// A `FileList`, the files an `<input type="file">` held.
///
/// The files of a list are part of it: a value that holds a list and also one of its files holds that file twice,
/// as Chromium stores it.
public final class JsFileList implements JsHostObject {
    private final List<JsFile> files;

    JsFileList(List<JsFile> files) {
        this.files = List.copyOf(files);
    }

    /// {@return a list of `files`, in order}
    public static JsFileList of(List<JsFile> files) {
        return new JsFileList(files);
    }

    /// {@return a list of `files`, in order}
    public static JsFileList of(JsFile... files) {
        return new JsFileList(Arrays.asList(files));
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
