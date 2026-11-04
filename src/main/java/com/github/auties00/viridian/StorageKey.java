package com.github.auties00.viridian;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/// The key Chromium partitions storage by: an origin, and for storage of a frame embedded in another site, that site.
///
/// A page that is the top-level frame, or embedded only by pages of its own site, has its origin's first-party key, as
/// [#of(StorageOrigin)] makes it; that is the storage [StorageOrigin] names everywhere a method takes one. A frame
/// embedded by another site has a partitioned key: the origin and the site of the top-level frame, as
/// [#partitioned(StorageOrigin, String)] makes it, so that the same origin under two sites has two storages. A frame
/// whose top-level frame is of its own site but some frame between is not has a key with the cross-site ancestor
/// flag, [#crossSiteAncestor(StorageOrigin)]. A key may also hold a nonce, which a fenced frame or a credentialless
/// iframe has, and a top-level site that is opaque, as a sandboxed top-level frame has: such keys can be read, never
/// made.
///
/// {@snippet lang = java :
/// var origin = StorageOrigin.parse("https://widget.example");
/// StorageKey firstParty = StorageKey.of(origin);                                   // https://widget.example/
/// StorageKey embedded = StorageKey.partitioned(origin, "https://news.example");   // https://widget.example/^0https://news.example
/// }
///
/// Two keys are equal when their serializations are.
///
/// @spec https://chromium.googlesource.com/chromium/src/+/main/third_party/blink/public/common/storage_key/storage_key.h Chromium storage keys
public final class StorageKey {
    // A site: a scheme and a host, without a port or a path
    private static final Pattern SITE = Pattern.compile("[a-z][a-z0-9+.-]*://[^/:?#\\s]+");

    private final StorageOrigin origin;
    private final String topLevelSite;
    private final boolean crossSiteAncestor;
    // What follows the origin for a key with a nonce or an opaque top-level site, kept to serialize it as read
    private final String opaqueSuffix;
    private final String nonce;

    private StorageKey(StorageOrigin origin, String topLevelSite, boolean crossSiteAncestor, String nonce, String opaqueSuffix) {
        this.origin = origin;
        this.topLevelSite = topLevelSite;
        this.crossSiteAncestor = crossSiteAncestor;
        this.nonce = nonce;
        this.opaqueSuffix = opaqueSuffix;
    }

    /// {@return the first-party key of `origin`}
    public static StorageKey of(StorageOrigin origin) {
        return new StorageKey(Objects.requireNonNull(origin, "origin"), null, false, null, null);
    }

    /// {@return the key of `origin` embedded in a page of `topLevelSite`, another site}
    ///
    /// A site is a scheme and a registrable domain, such as `https://example.com` for every host under it, or a host
    /// that has none, such as `http://localhost` or `http://127.0.0.1`; it has no port. Telling a registrable domain
    /// takes the Public Suffix List, which Viridian does not carry: the caller names the site.
    ///
    /// @throws IllegalArgumentException if `topLevelSite` is not a scheme and a host, without a port or a path
    public static StorageKey partitioned(StorageOrigin origin, String topLevelSite) {
        Objects.requireNonNull(origin, "origin");
        var site = Objects.requireNonNull(topLevelSite, "topLevelSite").toLowerCase(Locale.ROOT);
        if (!SITE.matcher(site).matches()) {
            throw new IllegalArgumentException("Not a site, a scheme and a host without a port: " + topLevelSite);
        }
        return new StorageKey(origin, site, true, null, null);
    }

    /// {@return the key of `origin` in a frame whose top-level frame is of its own site, but which a frame of another
    /// site embeds}
    public static StorageKey crossSiteAncestor(StorageOrigin origin) {
        return new StorageKey(Objects.requireNonNull(origin, "origin"), null, true, null, null);
    }

    /// {@return the key Chromium serialized as `serialized`, as storage and its quota database record it}
    ///
    /// A first-party key may lack the trailing slash, as Local Storage writes it.
    ///
    /// @throws IllegalArgumentException if `serialized` is not a storage key's serialization
    public static StorageKey parse(String serialized) {
        Objects.requireNonNull(serialized, "serialized");
        var caret = serialized.indexOf('^');
        var originPart = caret < 0 ? serialized : serialized.substring(0, caret);
        if (caret >= 0 && !originPart.endsWith("/")) {
            throw new IllegalArgumentException("Not a storage key: " + serialized);
        }
        var trimmed = originPart.endsWith("/") ? originPart.substring(0, originPart.length() - 1) : originPart;
        StorageOrigin origin;
        try {
            origin = StorageOrigin.parse(trimmed);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Not a storage key: " + serialized, exception);
        }
        if (!origin.serialize().equals(trimmed)) {
            throw new IllegalArgumentException("Not a storage key: " + serialized);
        }
        if (caret < 0) {
            return of(origin);
        }
        var rest = serialized.substring(caret);
        if (rest.startsWith("^0")) {
            var site = rest.substring(2);
            if (!SITE.matcher(site).matches()) {
                throw new IllegalArgumentException("Not a storage key: " + serialized);
            }
            return new StorageKey(origin, site, true, null, null);
        }
        if (rest.equals("^31")) {
            return crossSiteAncestor(origin);
        }
        if (rest.matches("\\^1\\d+\\^2\\d+")) {
            return new StorageKey(origin, null, false, rest.substring(2).replace("^2", ":"), rest);
        }
        if (rest.matches("\\^4\\d+\\^5\\d+\\^6.*")) {
            return new StorageKey(origin, null, true, null, rest);
        }
        throw new IllegalArgumentException("Not a storage key: " + serialized);
    }

    /// {@return the origin whose storage this is}
    public StorageOrigin origin() {
        return origin;
    }

    /// {@return the site of the top-level frame, when it is not the origin's own site and not opaque}
    public Optional<String> topLevelSite() {
        return Optional.ofNullable(topLevelSite);
    }

    /// {@return whether a frame between the top-level frame and this one is of another site, as it is when the
    /// top-level frame itself is}
    public boolean crossSiteAncestor() {
        return crossSiteAncestor;
    }

    /// {@return the nonce of a fenced frame or a credentialless iframe, as its two halves joined by a colon, or an empty
    /// optional if the key has none}
    public Optional<String> nonce() {
        return Optional.ofNullable(nonce);
    }

    /// {@return whether this is an origin's first-party key, which [StorageOrigin] names}
    public boolean isFirstParty() {
        return topLevelSite == null && !crossSiteAncestor && opaqueSuffix == null;
    }

    /// {@return the key as Chromium serializes it: the origin with a trailing slash, then the top-level site after
    /// `^0`, the cross-site ancestor flag as `^31`, or the nonce or opaque site as read}
    public String serialize() {
        var serialized = origin.serialize() + "/";
        if (opaqueSuffix != null) {
            return serialized + opaqueSuffix;
        }
        if (topLevelSite != null) {
            return serialized + "^0" + topLevelSite;
        }
        return crossSiteAncestor ? serialized + "^31" : serialized;
    }

    /// {@return the key as Local Storage on LevelDB names it: the origin without a trailing slash for a first-party
    /// key, the serialization otherwise}
    public String serializeForLocalStorage() {
        return isFirstParty() ? origin.serialize() : serialize();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StorageKey key && serialize().equals(key.serialize());
    }

    @Override
    public int hashCode() {
        return serialize().hashCode();
    }

    @Override
    public String toString() {
        return serialize();
    }
}
