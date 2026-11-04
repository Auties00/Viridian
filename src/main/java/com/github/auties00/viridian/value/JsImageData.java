package com.github.auties00.viridian.value;

import java.util.Objects;

/// An `ImageData`: its size, color space, pixel format and pixels.
public final class JsImageData implements JsHostObject {
    // Chromium refuses image data of more pixel bytes than a typed array holds, 2^32 elements
    private static final long MAX_BYTES = 1L << 32;

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

    /// {@return an sRGB `rgba-unorm8` image of `width` by `height` pixels, 4 bytes each, a copy of `pixels`}
    ///
    /// @throws IllegalArgumentException if a side is not positive, or `pixels` is not 4 bytes a pixel
    public static JsImageData of(int width, int height, byte[] pixels) {
        return of(width, height, JsColorSpace.SRGB, PixelFormat.RGBA_UNORM8, pixels);
    }

    /// {@return an image of `width` by `height` pixels in `colorSpace`, each stored in `pixelFormat`, a copy of
    /// `pixels`}
    ///
    /// @param pixels the pixels, row by row from the top; each component of a float format is little-endian
    /// @throws IllegalArgumentException if a side is not positive, or `pixels` is not the size the format calls for
    public static JsImageData of(int width, int height, JsColorSpace colorSpace, PixelFormat pixelFormat, byte[] pixels) {
        Objects.requireNonNull(colorSpace, "colorSpace");
        Objects.requireNonNull(pixelFormat, "pixelFormat");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("An ImageData of " + width + "x" + height + " pixels has no pixels");
        }
        var expected = (long) width * height * pixelFormat.bytesPerPixel();
        if (expected > MAX_BYTES || pixels.length != expected) {
            throw new IllegalArgumentException("An ImageData of " + width + "x" + height + " " + pixelFormat.webName()
                    + " pixels takes " + expected + " bytes, not " + pixels.length);
        }
        return new JsImageData(width, height, colorSpace, pixelFormat, pixels.clone());
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

    /// {@return the pixels themselves}
    byte[] rawPixels() {
        return pixels;
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

        /// {@return the code Blink serializes for the format}
        int code() {
            return code;
        }

        /// {@return how many bytes a pixel takes}
        int bytesPerPixel() {
            return 4 << code;
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
