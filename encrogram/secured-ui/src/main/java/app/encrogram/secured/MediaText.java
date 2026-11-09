package app.encrogram.secured;

import android.content.Context;

import org.telegram.messenger.AndroidUtilities;

import app.encrogram.core.api.MediaFailure;
import app.encrogram.core.api.MediaKind;

/**
 * Words for Secured media (task MU0). Fixed sentences only, from res/values/secured_media_strings.xml; nothing here
 * ever includes a file name, caption or engine text. Telegram's size and duration formatters are pure functions
 * (reuse level 1).
 */
final class MediaText {

    private MediaText() {
    }

    /** "Photo", "GIF", "Video", "Voice message", "File". */
    static String kind(Context c, MediaKind kind) {
        if (kind == null) return c.getString(R.string.secmedia_file);
        switch (kind) {
            case IMAGE:
                return c.getString(R.string.secmedia_photo);
            case GIF:
                return c.getString(R.string.secmedia_gif);
            case VIDEO:
                return c.getString(R.string.secmedia_video);
            case VOICE:
                return c.getString(R.string.secmedia_voice);
            default:
                return c.getString(R.string.secmedia_file);
        }
    }

    /**
     * The chat list preview. {@code text} is the caption, or a file's name for FILE (may be empty or null):
     * "🖼 Photo" without a caption, "🖼 caption" with one, "📎 report.pdf" for a file.
     */
    static String listPreview(Context c, MediaKind kind, String text) {
        final boolean has = text != null && !text.trim().isEmpty();
        if (kind == null) kind = MediaKind.FILE;
        switch (kind) {
            case IMAGE:
                return has ? c.getString(R.string.secmedia_list_photo_caption, text) : c.getString(R.string.secmedia_list_photo);
            case GIF:
                return has ? c.getString(R.string.secmedia_list_gif_caption, text) : c.getString(R.string.secmedia_list_gif);
            case VIDEO:
                return has ? c.getString(R.string.secmedia_list_video_caption, text) : c.getString(R.string.secmedia_list_video);
            case VOICE:
                return has ? c.getString(R.string.secmedia_list_voice_caption, text) : c.getString(R.string.secmedia_list_voice);
            default:
                return has ? c.getString(R.string.secmedia_list_file_caption, text) : c.getString(R.string.secmedia_list_file);
        }
    }

    /** The short line a bubble shows for a stopped transfer. */
    static String failureShort(Context c, MediaFailure f) {
        if (f == null) return c.getString(R.string.secmedia_fail_receive);
        switch (f) {
            case EXPIRED:
                return c.getString(R.string.secmedia_fail_expired);
            case SERVER_FULL:
                return c.getString(R.string.secmedia_fail_server_full);
            case FOREIGN_SERVER:
                return c.getString(R.string.secmedia_fail_foreign);
            case TOO_LARGE:
                return c.getString(R.string.secmedia_fail_too_large);
            case CHECK_FAILED:
                return c.getString(R.string.secmedia_fail_check);
            case CANCELLED:
                return c.getString(R.string.secmedia_fail_cancelled);
            case SEND_FAILED:
                return c.getString(R.string.secmedia_fail_send);
            case DAMAGED:
                return c.getString(R.string.secmedia_fail_damaged);
            case UNSUPPORTED:
                return c.getString(R.string.secmedia_fail_unsupported);
            case NOT_ON_DEVICE:
                return c.getString(R.string.secmedia_fail_not_on_device);
            case RECEIVE_FAILED:
            default:
                return c.getString(R.string.secmedia_fail_receive);
        }
    }

    /** The full sentence shown when a stopped bubble is tapped (the engine's ErrorMapper wording). */
    static String failureSentence(Context c, MediaFailure f) {
        if (f == null) return c.getString(R.string.secmedia_why_receive);
        switch (f) {
            case EXPIRED:
                return c.getString(R.string.secmedia_why_expired);
            case SERVER_FULL:
                return c.getString(R.string.secmedia_why_server_full);
            case FOREIGN_SERVER:
                return c.getString(R.string.secmedia_why_foreign);
            case TOO_LARGE:
                return c.getString(R.string.secmedia_why_too_large);
            case CHECK_FAILED:
                return c.getString(R.string.secmedia_why_check);
            case CANCELLED:
                return c.getString(R.string.secmedia_why_cancelled);
            case SEND_FAILED:
                return c.getString(R.string.secmedia_why_send);
            case DAMAGED:
                return c.getString(R.string.secmedia_why_damaged);
            case UNSUPPORTED:
                return c.getString(R.string.secmedia_why_unsupported);
            case NOT_ON_DEVICE:
                return c.getString(R.string.secmedia_why_not_on_device);
            case RECEIVE_FAILED:
            default:
                return c.getString(R.string.secmedia_why_receive);
        }
    }

    /** "1.2 MB". */
    static String size(long bytes) {
        return AndroidUtilities.formatFileSize(Math.max(0L, bytes));
    }

    /** "0.4 MB of 1.2 MB" while a file moves. */
    static String progress(Context c, long done, long total) {
        return c.getString(R.string.secmedia_progress, size(Math.min(Math.max(0L, done), Math.max(0L, total))), size(total));
    }

    /** "0:12", "1:02:03". */
    static String duration(int seconds) {
        return AndroidUtilities.formatShortDuration(Math.max(0, seconds));
    }
}
