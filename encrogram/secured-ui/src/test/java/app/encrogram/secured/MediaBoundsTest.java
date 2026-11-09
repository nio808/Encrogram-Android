package app.encrogram.secured;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MediaBoundsTest {

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    @Test
    public void normalPreviewIsAccepted() {
        assertTrue(MediaBounds.acceptable(320, 240, true));
        assertTrue(MediaBounds.acceptable(2048, 2048, true));
        assertTrue(MediaBounds.acceptable(4000, 4000, true)); // exactly 16 million pixels
    }

    @Test
    public void largePreviewIsRefused() {
        assertFalse(MediaBounds.acceptable(4097, 10, true));
        assertFalse(MediaBounds.acceptable(10, 4097, true));
        assertFalse(MediaBounds.acceptable(30000, 30000, true));
        assertFalse(MediaBounds.acceptable(4096, 4096, true)); // 16.7 million pixels
    }

    @Test
    public void thinHugeIsRefused() {
        assertFalse(MediaBounds.acceptable(1, 100000, true));
        assertFalse(MediaBounds.acceptable(1, 100000, false));
        assertFalse(MediaBounds.acceptable(100000, 1, false));
    }

    @Test
    public void zeroAndNegativeAreRefused() {
        assertFalse(MediaBounds.acceptable(0, 100, true));
        assertFalse(MediaBounds.acceptable(100, 0, false));
        assertFalse(MediaBounds.acceptable(-1, 100, true));
        assertFalse(MediaBounds.acceptable(100, -5, false));
        assertFalse(MediaBounds.acceptable(Integer.MIN_VALUE, Integer.MIN_VALUE, false));
    }

    @Test
    public void fullImageLimits() {
        assertTrue(MediaBounds.acceptable(4000, 3000, false));
        assertTrue(MediaBounds.acceptable(10000, 10000, false)); // exactly 100 million
        assertFalse(MediaBounds.acceptable(10001, 10000, false));
        assertFalse(MediaBounds.acceptable(16385, 100, false));
        assertFalse(MediaBounds.acceptable(16384, 16384, false));
    }

    @Test
    public void noOverflowWithMaxInts() {
        assertFalse(MediaBounds.acceptable(Integer.MAX_VALUE, Integer.MAX_VALUE, true));
        assertFalse(MediaBounds.acceptable(Integer.MAX_VALUE, Integer.MAX_VALUE, false));
        int s = MediaBounds.sampleSize(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertTrue(s >= 1 && Integer.bitCount(s) == 1);
        long d = ((long) Integer.MAX_VALUE + s - 1) / s;
        assertTrue(d * d <= MediaBounds.DECODED_MAX_PIXELS);
    }

    @Test
    public void previewBytesLimit() {
        assertTrue(MediaBounds.previewBytesOk(1));
        assertTrue(MediaBounds.previewBytesOk(256 * 1024));
        assertFalse(MediaBounds.previewBytesOk(256 * 1024 + 1));
        assertFalse(MediaBounds.previewBytesOk(0));
        assertFalse(MediaBounds.previewBytesOk(-1));
    }

    @Test
    public void sampleSizeBoundsTheDecodedSize() {
        int s = MediaBounds.sampleSize(2048, 2048, 320, 320);
        assertEquals(4, s);
        assertTrue(2048 / s <= 640);
    }

    @Test
    public void sampleSizeIsOneForSmallImages() {
        assertEquals(1, MediaBounds.sampleSize(100, 100, 320, 320));
        assertEquals(1, MediaBounds.sampleSize(640, 640, 320, 320));
        assertEquals(2, MediaBounds.sampleSize(641, 100, 320, 320));
    }

    @Test
    public void sampleSizeIsPowerOfTwoAndBoundsBothSides() {
        int[][] cases = {{4000, 3000, 360, 270}, {16384, 100, 50, 50}, {100, 16384, 1, 1}, {9999, 7, 33, 5}};
        for (int[] c : cases) {
            int s = MediaBounds.sampleSize(c[0], c[1], c[2], c[3]);
            assertTrue(s >= 1);
            assertEquals(1, Integer.bitCount(s));
            assertTrue((c[0] + s - 1) / s <= 2 * c[2]);
            assertTrue((c[1] + s - 1) / s <= 2 * c[3]);
        }
    }

    @Test
    public void decodedPixelCapIsEnforcedForBigTargets() {
        int s = MediaBounds.sampleSize(10000, 10000, 100000, 100000);
        long d = (10000L + s - 1) / s;
        assertTrue(d * d <= MediaBounds.DECODED_MAX_PIXELS);
        assertTrue(s >= 2);
        // and the smallest such power of two was picked
        long dHalf = (10000L + s / 2 - 1) / (s / 2);
        assertTrue(dHalf * dHalf > MediaBounds.DECODED_MAX_PIXELS);
    }

    @Test
    public void zeroOrNegativeTargetIsSafe() {
        int s = MediaBounds.sampleSize(1000, 1000, 0, 0);
        assertTrue(s >= 1);
        assertTrue((1000 + s - 1) / s <= 2);
        int t = MediaBounds.sampleSize(1000, 1000, -50, -50);
        assertEquals(s, t);
    }

    @Test
    public void sniffRecognisesTypes() {
        assertEquals(MediaBounds.TYPE_JPEG, MediaBounds.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0)));
        assertEquals(MediaBounds.TYPE_PNG, MediaBounds.sniff(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A)));
        assertEquals(MediaBounds.TYPE_GIF, MediaBounds.sniff("GIF89a".getBytes()));
        assertEquals(MediaBounds.TYPE_GIF, MediaBounds.sniff("GIF87a".getBytes()));
        assertEquals(MediaBounds.TYPE_WEBP, MediaBounds.sniff("RIFF\0\0\0\0WEBPVP8 ".getBytes()));
    }

    @Test
    public void sniffRejectsGarbage() {
        assertEquals(MediaBounds.TYPE_UNKNOWN, MediaBounds.sniff(null));
        assertEquals(MediaBounds.TYPE_UNKNOWN, MediaBounds.sniff(new byte[0]));
        assertEquals(MediaBounds.TYPE_UNKNOWN, MediaBounds.sniff(bytes(0xFF, 0xD8)));
        assertEquals(MediaBounds.TYPE_UNKNOWN, MediaBounds.sniff("<html>".getBytes()));
        assertEquals(MediaBounds.TYPE_UNKNOWN, MediaBounds.sniff("RIFF\0\0\0\0WAVEfmt ".getBytes()));
        assertEquals(MediaBounds.TYPE_UNKNOWN, MediaBounds.sniff("RIFF\0\0\0\0WEB".getBytes()));
    }

    @Test
    public void previewsAllowOnlyJpegAndPng() {
        assertTrue(MediaBounds.previewType(MediaBounds.TYPE_JPEG));
        assertTrue(MediaBounds.previewType(MediaBounds.TYPE_PNG));
        assertFalse(MediaBounds.previewType(MediaBounds.TYPE_GIF));
        assertFalse(MediaBounds.previewType(MediaBounds.TYPE_WEBP));
        assertFalse(MediaBounds.previewType(MediaBounds.TYPE_UNKNOWN));
        assertTrue(MediaBounds.fullType(MediaBounds.TYPE_WEBP));
        assertFalse(MediaBounds.fullType(MediaBounds.TYPE_UNKNOWN));
    }
}
