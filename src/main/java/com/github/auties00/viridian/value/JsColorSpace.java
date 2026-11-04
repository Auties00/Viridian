package com.github.auties00.viridian.value;

import java.util.Optional;

/// The predefined color spaces of stored image data, with the code Blink serializes for each.
public enum JsColorSpace {
    /// `srgb`, which images serialized before color spaces were recorded also have.
    SRGB(1, "srgb"),
    /// `rec2020`.
    REC2020(2, "rec2020"),
    /// `display-p3`.
    DISPLAY_P3(3, "display-p3"),
    /// `rec2100-hlg`.
    REC2100_HLG(4, "rec2100-hlg"),
    /// `rec2100-pq`.
    REC2100_PQ(5, "rec2100-pq"),
    /// `srgb-linear`.
    SRGB_LINEAR(6, "srgb-linear"),
    /// `rec2100-linear`.
    REC2100_LINEAR(7, "rec2100-linear"),
    /// `display-p3-linear`.
    DISPLAY_P3_LINEAR(8, "display-p3-linear");

    private final int code;
    private final String cssName;

    JsColorSpace(int code, String cssName) {
        this.code = code;
        this.cssName = cssName;
    }

    /// {@return the name the canvas APIs use for the color space}
    public String cssName() {
        return cssName;
    }

    /// {@return the color space of Blink code `code`, the obsolete legacy code 0 as sRGB, or an empty optional}
    static Optional<JsColorSpace> ofCode(int code) {
        if (code == 0) {
            return Optional.of(SRGB);
        }
        for (var space : values()) {
            if (space.code == code) {
                return Optional.of(space);
            }
        }
        return Optional.empty();
    }
}
