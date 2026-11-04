package com.github.auties00.viridian;

import com.github.auties00.viridian.profile.ProfileLock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/// The user data directory of a Chromium-based browser, such as Chrome's `User Data`: the directory that holds its
/// profiles, its `Local State`, and the lock a running browser holds.
///
/// {@snippet lang = java :
/// var chrome = ChromiumUserData.open(Path.of(System.getenv("LOCALAPPDATA"), "Google", "Chrome", "User Data"));
/// System.out.println(chrome.profileNames());                     // [Default, Profile 1]
/// ChromiumProfile profile = chrome.profile("Default");
/// }
///
/// An Electron app's `userData` directory is both a user data directory and its default profile;
/// [ChromiumProfile#open(Path)] opens it as the profile.
public final class ChromiumUserData {
    /// What a directory of a profile holds, of which one is enough to tell it from any other directory.
    private static final List<String> PROFILE_ENTRIES = List.of("Preferences", "IndexedDB", "Local Storage", "LocalStorage",
            "Session Storage", "SessionStorage", "WebStorage");

    private final Path directory;

    private ChromiumUserData(Path directory) {
        this.directory = directory;
    }

    /// {@return the user data directory `directory`}
    ///
    /// @throws NoSuchFileException if `directory` is not a directory
    public static ChromiumUserData open(Path directory) throws IOException {
        Objects.requireNonNull(directory, "directory");
        if (!Files.isDirectory(directory)) {
            throw new NoSuchFileException(directory.toString(), null, "Not a directory");
        }
        return new ChromiumUserData(directory.toAbsolutePath().normalize());
    }

    /// {@return the directory}
    public Path directory() {
        return directory;
    }

    /// {@return the names of the directory's subdirectories that hold a profile, such as `Default` and `Profile 1`,
    /// `Default` first and the others by name, numbers compared as numbers}
    ///
    /// @throws IOException if the directory cannot be listed
    public List<String> profileNames() throws IOException {
        var names = new ArrayList<String>();
        try (var entries = Files.list(directory)) {
            for (var entry : (Iterable<Path>) entries::iterator) {
                if (Files.isDirectory(entry) && isProfile(entry)) {
                    names.add(entry.getFileName().toString());
                }
            }
        }
        names.sort(Comparator.comparing((String name) -> !name.equals("Default")).thenComparing(ChromiumUserData::compareNaturally));
        return List.copyOf(names);
    }

    /// {@return the profile named `name`, a subdirectory of the directory}
    ///
    /// @throws NoSuchFileException if there is no such subdirectory
    /// @throws IOException         if it cannot be read
    public ChromiumProfile profile(String name) throws IOException {
        return ChromiumProfile.open(directory.resolve(Objects.requireNonNull(name, "name")));
    }

    /// {@return whether a browser or a writer holds the directory's lock}
    ///
    /// @throws IOException if the lock cannot be inspected
    public boolean isInUse() throws IOException {
        try {
            ProfileLock.check(directory);
            return false;
        } catch (ProfileInUseException _) {
            return true;
        }
    }

    /// {@return whether `directory` is a browser's user data directory rather than a profile: it holds `Local State`
    /// and a profile, but no `Preferences` of its own}
    static boolean isUserDataDirectory(Path directory) throws IOException {
        if (!Files.isRegularFile(directory.resolve("Local State")) || Files.exists(directory.resolve("Preferences"))) {
            return false;
        }
        try (var entries = Files.list(directory)) {
            for (var entry : (Iterable<Path>) entries::iterator) {
                if (Files.isDirectory(entry) && isProfile(entry)) {
                    return true;
                }
            }
        }
        return false;
    }

    /// {@return whether `directory` holds what a profile holds}
    private static boolean isProfile(Path directory) {
        for (var entry : PROFILE_ENTRIES) {
            if (Files.exists(directory.resolve(entry))) {
                return true;
            }
        }
        return false;
    }

    /// Compares two names as text, the runs of digits in them as numbers.
    private static int compareNaturally(String first, String second) {
        var i = 0;
        var j = 0;
        while (i < first.length() && j < second.length()) {
            var a = first.charAt(i);
            var b = second.charAt(j);
            if (Character.isDigit(a) && Character.isDigit(b)) {
                var startA = i;
                var startB = j;
                while (i < first.length() && Character.isDigit(first.charAt(i))) {
                    i++;
                }
                while (j < second.length() && Character.isDigit(second.charAt(j))) {
                    j++;
                }
                var numberA = first.substring(startA, i).replaceFirst("^0+(?=.)", "");
                var numberB = second.substring(startB, j).replaceFirst("^0+(?=.)", "");
                var order = numberA.length() != numberB.length() ? Integer.compare(numberA.length(), numberB.length()) : numberA.compareTo(numberB);
                if (order != 0) {
                    return order;
                }
            } else {
                if (a != b) {
                    return Character.compare(a, b);
                }
                i++;
                j++;
            }
        }
        return Integer.compare(first.length() - i, second.length() - j);
    }

    @Override
    public String toString() {
        return "ChromiumUserData[" + directory + "]";
    }
}
