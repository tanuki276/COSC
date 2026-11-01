package com.tanuki276.cosc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Pixel-layout checks only: no Android runtime, game or device emulation. */
public final class RgbaSamplerCheck {
    private static final int[] COLOURS = {
        0xffff0000, 0xff00ff00, 0xff0000ff, 0xffffffff,
        0xff29272e, 0xff4c4b50, 0xffc07cff, 0xff67e8a5,
        0xff010203, 0xff112233, 0xffabcdef, 0xffd8d2dc
    };

    private static ByteBuffer plane(int rowStride, int pixelStride) {
        int base = 7;
        int end = base + 2 * rowStride + 3 * pixelStride + 4;
        ByteBuffer b = ByteBuffer.allocateDirect(end).order(ByteOrder.BIG_ENDIAN);
        for (int y = 0; y < 3; y++) for (int x = 0; x < 4; x++) {
            int c = COLOURS[y * 4 + x], at = base + y * rowStride + x * pixelStride;
            b.put(at, (byte) (c >>> 16)); b.put(at + 1, (byte) (c >>> 8));
            b.put(at + 2, (byte) c); b.put(at + 3, (byte) (c >>> 24));
        }
        b.position(base);
        return b.asReadOnlyBuffer();
    }

    private static void equal(int[] expected, int[] actual) {
        if (!Arrays.equals(expected, actual))
            throw new AssertionError("Pixel colours or crop differ: " + Arrays.toString(actual));
    }

    public static void main(String[] args) {
        RgbaSampler sampler = new RgbaSampler();
        for (int pixelStride : new int[]{4, 8}) {
            int rowStride = pixelStride * 4 + 8;
            ByteBuffer source = plane(rowStride, pixelStride);
            int[] full = new int[12];
            sampler.sample(source, 4, 3, rowStride, pixelStride, 0, 0, 4, 3, 4, 3, full);
            equal(COLOURS, full);
            int[] roi = new int[4];
            sampler.sample(source, 4, 3, rowStride, pixelStride, 1, 1, 2, 2, 2, 2, roi);
            equal(new int[]{0xff4c4b50, 0xffc07cff, 0xff112233, 0xffabcdef}, roi);
            int[] reduced = new int[4];
            sampler.sample(source, 4, 3, rowStride, pixelStride, 0, 0, 4, 3, 2, 2, reduced);
            equal(new int[]{0xff00ff00, 0xffffffff, 0xff112233, 0xffd8d2dc}, reduced);
            if (source.position() != 7 || source.order() != ByteOrder.BIG_ENDIAN)
                throw new AssertionError("Sampler changed its caller's buffer state");
            source.limit(source.limit() - 1);
            boolean rejected = false;
            try { sampler.sample(source, 4, 3, rowStride, pixelStride, 0, 0, 4, 3, 4, 3, full); }
            catch (IllegalArgumentException expected) { rejected = true; }
            if (!rejected) throw new AssertionError("Truncated final row was accepted");
        }
        System.out.println("RGBA colours, crop, sampling, pixel/row stride and buffer bounds: OK");
    }
}
