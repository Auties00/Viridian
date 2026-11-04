package com.github.auties00.viridian;

import java.util.Map;
import java.util.Objects;

/// The `sessionStorage` of an origin in one browsing session, such as a tab.
///
/// @param namespaceId the id Chromium gave the session
/// @param items       the items, by key
public record SessionStorageArea(String namespaceId, Map<String, String> items) {
    /// Creates an area, copying `items`.
    public SessionStorageArea {
        Objects.requireNonNull(namespaceId, "namespaceId");
        items = Map.copyOf(items);
    }
}
