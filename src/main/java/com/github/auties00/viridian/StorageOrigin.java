package com.github.auties00.viridian;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/// The origin storage belongs to: a scheme, a host and a port.
///
/// Chromium names an origin's storage on disk by its identifier, such as `https_web.example.com_0`, where the port is
/// 0 when it is the scheme's default.
///
/// @param scheme the scheme, lowercase, such as `https`
/// @param host   the host, lowercase, such as `example.com`; empty for a `file` origin
/// @param port   the port, or -1 for the scheme's default
public record StorageOrigin(String scheme, String host, int port) {
    /// Creates an origin, lowercasing the scheme and host and making the scheme's default port -1.
    ///
    /// @throws IllegalArgumentException if the scheme is empty or the port is out of range
    public StorageOrigin {
        Objects.requireNonNull(scheme, "scheme");
        Objects.requireNonNull(host, "host");
        scheme = scheme.toLowerCase(Locale.ROOT);
        host = host.toLowerCase(Locale.ROOT);
        if (scheme.isEmpty()) {
            throw new IllegalArgumentException("An origin has a scheme");
        }
        if (port < -1 || port > 65535) {
            throw new IllegalArgumentException("A port is from 0 to 65535: " + port);
        }
        if (port == defaultPort(scheme)) {
            port = -1;
        }
    }

    /// {@return the origin of `url`, such as `https://web.example.com` or any URL of that origin}
    ///
    /// @throws IllegalArgumentException if `url` is not an absolute URL
    public static StorageOrigin parse(String url) {
        var uri = URI.create(url.strip());
        if (uri.getScheme() == null) {
            throw new IllegalArgumentException("Not an absolute URL: " + url);
        }
        return new StorageOrigin(uri.getScheme(), uri.getHost() == null ? "" : uri.getHost(), uri.getPort());
    }

    /// {@return the origin Chromium's storage identifier `identifier` names, such as `https_web.example.com_0`}
    ///
    /// @throws IllegalArgumentException if `identifier` is not one
    public static StorageOrigin ofIdentifier(String identifier) {
        var first = identifier.indexOf('_');
        var last = identifier.lastIndexOf('_');
        if (first <= 0 || last == first) {
            throw new IllegalArgumentException("Not a storage identifier: " + identifier);
        }
        int port;
        try {
            port = Integer.parseInt(identifier.substring(last + 1));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Not a storage identifier: " + identifier, exception);
        }
        return new StorageOrigin(identifier.substring(0, first), identifier.substring(first + 1, last), port == 0 ? -1 : port);
    }

    /// {@return the identifier Chromium names the origin's storage by: scheme, host and port joined by `_`, the
    /// default port as 0}
    public String identifier() {
        return scheme + "_" + host + "_" + (port == -1 ? 0 : port);
    }

    /// {@return the origin as a URL without a path, such as `https://web.example.com`}
    public String serialize() {
        return scheme + "://" + host + (port == -1 ? "" : ":" + port);
    }

    @Override
    public String toString() {
        return serialize();
    }

    private static int defaultPort(String scheme) {
        return switch (scheme) {
            case "http", "ws" -> 80;
            case "https", "wss" -> 443;
            case "ftp" -> 21;
            default -> -2;
        };
    }
}
