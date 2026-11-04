package com.github.auties00.viridian.value;

import java.util.Objects;

/// An `ImageData`: its size, color space, pixel format and pixels.
public final class JsImageData implements JsHostObject {
    private final int width;
    private final int height;
    private final JsColorSpace colorSpace;
    private final PixelFormat pixelFormat;
    private final byte[] pixels;

    JsImageData(int width, int height, JsColorSpace colorSpace, PixelFormat pixelFormat, byte[] pixels) {
        this.width = width;
        this.height = height;
        this.colorSpace = Objects.requireNonNull(colorSpace, "colorSpace");
        this.pixelFormat = Objects.requireNonNull(pixelFormat, "pixelFormat");
        this.pixels = pixels;
    }

    /// {@return the width in pixels}
    public int width() {
        return width;
    }

    /// {@return the height in pixels}
    public int height() {
        return height;
    }

    /// {@return the color space of the pixels}
    public JsColorSpace colorSpace() {
        return colorSpace;
    }

    /// {@return how each pixel is stored}
    public PixelFormat pixelFormat() {
        return pixelFormat;
    }

    /// {@return a copy of the pixels, row by row from the top, each in [#pixelFormat()]}
    public byte[] pixels() {
        return pixels.clone();
    }

    @Override
    public String toString() {
        return "ImageData(" + width + "x" + height + ")";
    }

    /// How the pixels of an `ImageData` are stored, with the code Blink serializes for each.
    public enum PixelFormat {
        /// `rgba-unorm8`: 4 bytes a pixel.
        RGBA_UNORM8(0, "rgba-unorm8"),
        /// `rgba-float16`: 4 half-precision floats a pixel.
        RGBA_FLOAT16(1, "rgba-float16"),
        /// `rgba-float32`: 4 single-precision floats a pixel.
        RGBA_FLOAT32(2, "rgba-float32");

        private final int code;
        private final String webName;

        PixelFormat(int code, String webName) {
            this.code = code;
            this.webName = webName;
        }

        /// {@return the name the canvas APIs use for the format}
        public String webName() {
            return webName;
        }

        /// {@return the format of Blink code `code`, or `null` for a code it does not know}
        static PixelFormat ofCode(int code) {
            for (var format : values()) {
                if (format.code == code) {
                    return format;
                }
            }
            return null;
        }
    }
}
