package com.tanuki276.cosc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Reads only the requested nearest-neighbour pixels from an RGBA image plane. */
final class RgbaSampler {
    private int[] offsets = new int[0];

    void sample(ByteBuffer source, int imageWidth, int imageHeight, int rowStride, int pixelStride,
                int left, int top, int width, int height, int outWidth, int outHeight, int[] out) {
        if (left < 0 || top < 0 || width <= 0 || height <= 0
                || left + width > imageWidth || top + height > imageHeight
                || outWidth <= 0 || outHeight <= 0 || (long) outWidth * outHeight > out.length
                || pixelStride < 4 || rowStride < (long) imageWidth * pixelStride)
            throw new IllegalArgumentException("Invalid RGBA plane or crop");
        ByteBuffer bytes = source.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int base = bytes.position();
        // The final row need not include its trailing padding bytes.
        long last = (long) base + (long) (top + height - 1) * rowStride
                + (long) (left + width - 1) * pixelStride + 4;
        if (last > bytes.limit()) throw new IllegalArgumentException("Truncated RGBA plane");
        if (offsets.length != outWidth) offsets = new int[outWidth];
        for (int x = 0; x < outWidth; x++) {
            int sx = left + (int) (((2L * x + 1) * width) / (2L * outWidth));
            offsets[x] = sx * pixelStride;
        }
        for (int y = 0; y < outHeight; y++) {
            int sy = top + (int) (((2L * y + 1) * height) / (2L * outHeight));
            int row = base + sy * rowStride;
            int dst = y * outWidth;
            for (int x = 0; x < outWidth; x++) {
                // RGBA bytes read little-endian are ABGR; Bitmap int colours are ARGB.
                int rgba = bytes.getInt(row + offsets[x]);
                out[dst + x] = (rgba & 0xff00ff00) | ((rgba & 0xff) << 16) | ((rgba >>> 16) & 0xff);
            }
        }
    }
}
