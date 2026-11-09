package app.encrogram.core.internal

import app.encrogram.core.api.AVATAR_MAX_BYTES
import app.encrogram.core.api.MediaPreview
import app.encrogram.core.api.SecuredError
import app.encrogram.core.internal.media.DataUrl
import app.encrogram.core.internal.media.ImageHeaders
import app.encrogram.core.internal.media.MediaMapper

/**
 * Profile photos (simplex-chat-api-for-ui.md §1.10): the `image` of a Profile, LocalProfile or GroupProfile, a data URL
 * (Types.hs:890-904 ImageData). Pure logic, any thread.
 *
 * In: contacts', members', groups' and our own photos are untrusted. Only a jpg, jpeg or png data URL with valid base64,
 * at most [MAX_DATA_URL_CHARS] characters in all, whose bytes really are that format with a small size in the header
 * ([MediaMapper.previewOf], the same check as a message preview), becomes a [MediaPreview]; anything else is no photo.
 * The engine never decodes the image itself: the UI does, with bounds. Equal photos share one [MediaPreview] ([intern]),
 * so a page of one member's messages holds their photo once, not once per message.
 *
 * Out: [outgoingRefusal] checks a JPEG the UI re-encoded before `/_profile` or `/_group_profile` carries it.
 * Never log a data URL or its bytes.
 */
internal object Avatars {

    /** The core's ceiling on a profile image data URL (Commands.hs:158-164 maxProfileImageSize, checkProfileImageSize). */
    const val MAX_DATA_URL_CHARS = 12_500

    /** Distinct photos kept for sharing (about 9 KB each at most). */
    private const val INTERN_MAX = 256

    private val interned = object : LinkedHashMap<MediaPreview, MediaPreview>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MediaPreview, MediaPreview>?) = size > INTERN_MAX
    }

    /** The photo in [dataUrl] (a profile's `image`), or null for none or anything not accepted. */
    fun of(dataUrl: String?): MediaPreview? {
        if (dataUrl == null || dataUrl.length > MAX_DATA_URL_CHARS) return null
        return MediaMapper.previewOf(dataUrl)?.let { intern(it) }
    }

    /** One shared instance per distinct photo (content equality). */
    fun intern(p: MediaPreview): MediaPreview = synchronized(interned) {
        interned[p] ?: p.also { interned[it] = it }
    }

    /**
     * Why [jpeg] can't be our profile or group photo, or null. Larger than [AVATAR_MAX_BYTES]: TOO_LARGE (its data URL
     * would pass the core's 12,500 characters). Not a well-formed JPEG with a small size in its header, or carrying
     * metadata (Exif, XMP, comments, bytes after the end): UNSUPPORTED. The UI re-encodes, so neither should happen.
     */
    fun outgoingRefusal(jpeg: ByteArray): SecuredError? {
        if (jpeg.size > AVATAR_MAX_BYTES) return SecuredError(SecuredError.Kind.TOO_LARGE, PHOTO_TOO_LARGE)
        if (ImageHeaders.format(jpeg) != ImageHeaders.Format.JPEG) return SecuredError(SecuredError.Kind.UNSUPPORTED, PHOTO_UNSUPPORTED)
        val size = ImageHeaders.size(jpeg)
        val fits = size != null && size.width <= MediaMapper.MAX_PREVIEW_SIDE && size.height <= MediaMapper.MAX_PREVIEW_SIDE
        if (!fits || ImageHeaders.hasMetadata(jpeg)) return SecuredError(SecuredError.Kind.UNSUPPORTED, PHOTO_UNSUPPORTED)
        return null
    }

    /** `data:image/jpg;base64,...`, as the official clients write a JPEG photo (the core's imagePrefix, Commands.hs:5794). */
    fun dataUrl(jpeg: ByteArray): String = DataUrl.encodeJpeg(jpeg)

    const val PHOTO_TOO_LARGE = "This photo is too large. Choose a smaller one."
    const val PHOTO_UNSUPPORTED = "This photo can't be used. Choose another one."
}
