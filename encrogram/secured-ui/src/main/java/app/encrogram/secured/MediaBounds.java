package app.encrogram.secured;

/**
 * Limits and math for decoding untrusted images (task MU1/MD11). Pure Java so it can be unit tested. Everything uses
 * long arithmetic: a hostile header can claim any int size.
 */
final class MediaBounds {

    /** The small JPEG/PNG that travels inside a message. */
    static final int PREVIEW_MAX_BYTES = 256 * 1024;
    static final int PREVIEW_MAX_SIDE = 4096;
    static final long PREVIEW_MAX_PIXELS = 16_000_000L;
    static final int FULL_MAX_SIDE = 16384;
    static final long FULL_MAX_PIXELS = 100_000_000L;
    /** No decoded bitmap may exceed this, whatever the source. */
    static final long DECODED_MAX_PIXELS = 16_000_000L;

    static final int TYPE_UNKNOWN = 0;
    static final int TYPE_JPEG = 1;
    static final int TYPE_PNG = 2;
    static final int TYPE_GIF = 3;
    static final int TYPE_WEBP = 4;

    private MediaBounds() {
    }

    /** Whether an image of this header size may be decoded at all. Zero and negative sizes are refused. */
    static boolean acceptable(int w, int h, boolean preview) {
        if (w <= 0 || h <= 0) return false;
        int maxSide = preview ? PREVIEW_MAX_SIDE : FULL_MAX_SIDE;
        long maxPixels = preview ? PREVIEW_MAX_PIXELS : FULL_MAX_PIXELS;
        if (w > maxSide || h > maxSide) return false;
        return (long) w * (long) h <= maxPixels;
    }

    static boolean previewBytesOk(int length) {
        return length > 0 && length <= PREVIEW_MAX_BYTES;
    }

    /**
     * A power of two, at least 1. The decoded size (rounded up, as the decoders do) is at most 2x the target on each
     * side and at most {@link #DECODED_MAX_PIXELS} pixels. A target of 0 or less counts as 1.
     */
    static int sampleSize(int w, int h, int targetW, int targetH) {
        long lw = Math.max(1, w);
        long lh = Math.max(1, h);
        long limitW = 2L * Math.max(1, targetW);
        long limitH = 2L * Math.max(1, targetH);
        long s = 1;
        while (s < (1L << 30)) {
            long dw = (lw + s - 1) / s;
            long dh = (lh + s - 1) / s;
            if (dw <= limitW && dh <= limitH && dw * dh <= DECODED_MAX_PIXELS) break;
            s <<= 1;
        }
        return (int) s;
    }

    /** The image type by magic bytes, or {@link #TYPE_UNKNOWN}. Never trusts a name or a mime string. */
    static int sniff(byte[] b) {
        if (b == null) return TYPE_UNKNOWN;
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return TYPE_JPEG;
        }
        if (b.length >= 4 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return TYPE_PNG;
        }
        if (b.length >= 4 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') {
            return TYPE_GIF;
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return TYPE_WEBP;
        }
        return TYPE_UNKNOWN;
    }

    static boolean previewType(int type) {
        return type == TYPE_JPEG || type == TYPE_PNG;
    }

    static boolean fullType(int type) {
        return type != TYPE_UNKNOWN;
    }
}
