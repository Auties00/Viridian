package com.github.auties00.viridian.value;

import java.util.Objects;
import java.util.Optional;

/// An `ImageBitmap`: its size, how its pixels are stored and oriented, and the pixels.
public final class JsImageBitmap implements JsHostObject {
    private final int width;
    private final int height;
    private final JsColorSpace colorSpace;
    private final double[] parametricColorSpace;
    private final PixelFormat pixelFormat;
    private final boolean opaque;
    private final boolean premultiplied;
    private final Orientation orientation;
    private final byte[] pixels;

    JsImageBitmap(int width, int height, JsColorSpace colorSpace, double[] parametricColorSpace, PixelFormat pixelFormat,
                  boolean opaque, boolean premultiplied, Orientation orientation, byte[] pixels) {
        this.width = width;
        this.height = height;
        this.colorSpace = colorSpace;
        this.parametricColorSpace = parametricColorSpace;
        this.pixelFormat = pixelFormat;
        this.opaque = opaque;
        this.premultiplied = premultiplied;
        this.orientation = orientation;
        this.pixels = pixels;
    }

    /// {@return a bitmap of `width` by `height` pixels in `colorSpace`, stored in `pixelFormat` and oriented by
    /// `orientation`, a copy of `pixels`}
    ///
    /// @param pixels the pixels as stored, row by row from the top, before the orientation applies
    /// @throws IllegalArgumentException if a side is not positive, the format is the obsolete
    ///                                  [PixelFormat#NATIVE8], or `pixels` is not the size the format calls for
    public static JsImageBitmap of(int width, int height, JsColorSpace colorSpace, PixelFormat pixelFormat, boolean opaque,
                                   boolean premultiplied, Orientation orientation, byte[] pixels) {
        Objects.requireNonNull(colorSpace, "colorSpace");
        Objects.requireNonNull(pixelFormat, "pixelFormat");
        Objects.requireNonNull(orientation, "orientation");
        if (pixelFormat == PixelFormat.NATIVE8) {
            throw new IllegalArgumentException("The native pixel format is obsolete: name the byte order");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("An ImageBitmap of " + width + "x" + height + " pixels has no pixels");
        }
        var expected = (long) width * height * pixelFormat.bytesPerPixel();
        if (pixels.length != expected) {
            throw new IllegalArgumentException("An ImageBitmap of " + width + "x" + height + " pixels in " + pixelFormat
                    + " takes " + expected + " bytes, not " + pixels.length);
        }
        return new JsImageBitmap(width, height, colorSpace, null, pixelFormat, opaque, premultiplied, orientation, pixels.clone());
    }

    /// {@return the width in pixels}
    public int width() {
        return width;
    }

    /// {@return the height in pixels}
    public int height() {
        return height;
    }

    /// {@return the predefined color space of the pixels}
    public JsColorSpace colorSpace() {
        return colorSpace;
    }

    /// {@return a copy of the 16 values of a parametric color space that replaces [#colorSpace()], or an empty
    /// optional if there is none}
    ///
    /// The values are the transfer function's 7 parameters, then the 3x3 matrix to XYZ D50 row by row, as Skia
    /// describes a color space.
    public Optional<double[]> parametricColorSpace() {
        return parametricColorSpace == null ? Optional.empty() : Optional.of(parametricColorSpace.clone());
    }

    /// {@return how each pixel is stored}
    public PixelFormat pixelFormat() {
        return pixelFormat;
    }

    /// {@return whether the bitmap is fully opaque}
    public boolean opaque() {
        return opaque;
    }

    /// {@return whether the color channels are premultiplied by alpha}
    public boolean premultiplied() {
        return premultiplied;
    }

    /// {@return how the pixels are to be oriented when shown}
    public Orientation orientation() {
        return orientation;
    }

    /// {@return a copy of the pixels, row by row from the top, each in [#pixelFormat()]}
    public byte[] pixels() {
        return pixels.clone();
    }

    /// {@return the parametric color space itself, or `null`}
    double[] rawParametricColorSpace() {
        return parametricColorSpace;
    }

    /// {@return the pixels themselves}
    byte[] rawPixels() {
        return pixels;
    }

    @Override
    public String toString() {
        return "ImageBitmap(" + width + "x" + height + ")";
    }

    /// How the pixels of an `ImageBitmap` are stored, with the code Blink serializes for each.
    public enum PixelFormat {
        /// The platform's native 8-bit order, which older versions recorded.
        NATIVE8(0),
        /// 4 half-precision floats a pixel.
        F16(1),
        /// 4 bytes a pixel, red first.
        RGBA8(2),
        /// 4 bytes a pixel, blue first.
        BGRA8(3),
        /// 4 bytes a pixel, red first, the fourth unused.
        RGBX8(4);

        private final int code;

        PixelFormat(int code) {
            this.code = code;
        }

        /// {@return the code Blink serializes for the format}
        int code() {
            return code;
        }

        /// {@return how many bytes a pixel takes}
        int bytesPerPixel() {
            return this == F16 ? 8 : 4;
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

    /// The orientations of a bitmap: where row 0 and column 0 are, as the EXIF orientations name them.
    public enum Orientation {
        /// Row 0 at the top, column 0 at the left: as stored.
        TOP_LEFT,
        /// Row 0 at the top, column 0 at the right.
        TOP_RIGHT,
        /// Row 0 at the bottom, column 0 at the right.
        BOTTOM_RIGHT,
        /// Row 0 at the bottom, column 0 at the left.
        BOTTOM_LEFT,
        /// Row 0 at the left, column 0 at the top.
        LEFT_TOP,
        /// Row 0 at the right, column 0 at the top.
        RIGHT_TOP,
        /// Row 0 at the right, column 0 at the bottom.
        RIGHT_BOTTOM,
        /// Row 0 at the left, column 0 at the bottom.
        LEFT_BOTTOM
    }
}
