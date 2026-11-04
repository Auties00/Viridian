package com.github.auties00.viridian.profile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalInt;
import java.util.regex.Pattern;

/// The version of the browser that last ran a profile, as the browser records it, so that a writer does not write
/// values that browser cannot read.
///
/// Chrome records it in three places, read in this order: `Last Version` in its user data directory, the bare version
/// it writes at every launch on Windows, macOS and Linux; `extensions.last_chrome_version` in the profile's
/// `Preferences`, which it updates each time it loads the profile; and `user_experience_metrics.stability.stats_version`
/// in the user data directory's `Local State`. Electron and the other embedders of Chromium record none of them, so no
/// version is known for their profiles.
public final class BrowserVersion {
    /// A full version, as `Last Version` holds it, its major version the first group.
    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.\\d+\\.\\d+\\.\\d+");
    /// The major version of `extensions.last_chrome_version`, found without parsing the JSON it is in.
    private static final Pattern LAST_CHROME_VERSION = Pattern.compile("\"last_chrome_version\"\\s*:\\s*\"(\\d+)\\.");
    /// The major version of `user_experience_metrics.stability.stats_version`, found the same way.
    private static final Pattern STATS_VERSION = Pattern.compile("\"stats_version\"\\s*:\\s*\"(\\d+)\\.");
    /// The largest file read for a version, a guard against a damaged one.
    private static final long MAX_FILE = 64L << 20;

    private BrowserVersion() {
    }

    /// {@return the major version of the browser that last ran the profile in `profile`, whose user data directory is
    /// `userData`, or an empty optional if none is recorded}
    ///
    /// @throws IOException if a record exists but cannot be read
    public static OptionalInt of(Path profile, Path userData) throws IOException {
        var lastVersion = read(userData.resolve("Last Version"));
        if (lastVersion != null) {
            var matcher = VERSION.matcher(lastVersion.strip());
            if (matcher.matches()) {
                return OptionalInt.of(Integer.parseInt(matcher.group(1)));
            }
        }
        var preferences = read(profile.resolve("Preferences"));
        if (preferences != null) {
            var matcher = LAST_CHROME_VERSION.matcher(preferences);
            if (matcher.find()) {
                return OptionalInt.of(Integer.parseInt(matcher.group(1)));
            }
        }
        var localState = read(userData.resolve("Local State"));
        if (localState != null) {
            var matcher = STATS_VERSION.matcher(localState);
            if (matcher.find()) {
                return OptionalInt.of(Integer.parseInt(matcher.group(1)));
            }
        }
        return OptionalInt.empty();
    }

    /// {@return the text of `file`, or `null` if there is none, or it is too large to be a record}
    private static String read(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) > MAX_FILE) {
            return null;
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
