package com.github.auties00.viridian.indexeddb;

/// The data format version of the values written, as `IndexedDBDataFormatVersion` encodes it: the newest V8
/// serializer version among them in the high 32 bits, and the newest Blink version in the low ones.
///
/// Chrome refuses a store recorded as newer than it is, so the version is no newer than the values need: without
/// values, it is V8 15 and Blink 21, the versions [com.github.auties00.viridian.value.StructuredClone] writes.
public final class IdbDataFormat {
    private static final int MINIMUM_V8_VERSION = 15;
    private static final int MINIMUM_BLINK_VERSION = 21;
    // A serialized value starts with this tag and its Blink version; from Blink 21 a trailer offset follows, then V8's
    private static final int VERSION_TAG = 0xFF;
    private static final int TRAILER_BLINK_VERSION = 21;
    private static final int TRAILER_LENGTH = 13;
    // The first Chrome that reads values of Blink 21 and V8 15, and the first that reads V8 16
    private static final int CHROME_BLINK_21 = 109;
    private static final int CHROME_V8_16 = 149;

    private int blinkVersion = MINIMUM_BLINK_VERSION;
    private int v8Version = MINIMUM_V8_VERSION;

    /// Raises the version to cover a serialized value: its Blink envelope's version, and the V8 version that
    /// follows the envelope, or its trailer from Blink 21 on.
    void note(byte[] value) {
        if (value.length < 2 || (value[0] & 0xFF) != VERSION_TAG) {
            return;
        }
        var blink = value[1] & 0xFF;
        var v8At = blink >= TRAILER_BLINK_VERSION ? 2 + TRAILER_LENGTH : 2;
        blinkVersion = Math.max(blinkVersion, blink);
        if (value.length > v8At + 1 && (value[v8At] & 0xFF) == VERSION_TAG) {
            v8Version = Math.max(v8Version, value[v8At + 1] & 0xFF);
        }
    }

    /// Raises the version to cover `other`'s.
    public void include(IdbDataFormat other) {
        blinkVersion = Math.max(blinkVersion, other.blinkVersion);
        v8Version = Math.max(v8Version, other.v8Version);
    }

    /// {@return the version as Chromium stores it}
    long encoded() {
        return (long) v8Version << 32 | blinkVersion;
    }

    /// {@return the major version of the first Chrome that reads every value written}
    public int minimumChromeVersion() {
        return v8Version >= 16 ? CHROME_V8_16 : CHROME_BLINK_21;
    }
}
