package app.encrogram.core.internal.media.prep.android

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.encrogram.core.api.MediaInput
import app.encrogram.core.internal.media.prep.InputOpener
import app.encrogram.core.internal.media.prep.PickedSource
import app.encrogram.core.internal.media.prep.PrepException
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.Locale

/**
 * Opens a picked content:// Uri through the platform ContentResolver. Never our own app's authority (our FileProvider
 * would expose Telegram's files). Names and Uris are user content: never logged.
 */
internal class ContentInputOpener(
    context: Context,
    private val ownPackage: String = context.packageName,
    private val cap: Long = 100L * 1024 * 1024,
) : InputOpener {

    private val resolver: ContentResolver = context.applicationContext.contentResolver

    override fun open(input: MediaInput.Picked): PickedSource {
        val uri = try {
            Uri.parse(input.uri)
        } catch (e: Exception) {
            throw PrepException(PrepException.Reason.UNSUPPORTED)
        }
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) throw PrepException(PrepException.Reason.UNSUPPORTED)
        if (isOwn(uri)) throw PrepException(PrepException.Reason.UNSUPPORTED)

        var name: String? = null
        var size: Long? = null
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s).takeIf { it >= 0 }
                }
            }
        } catch (e: Exception) {
            // Some providers refuse the query but still open; the probe below decides.
        }
        if ((size ?: 0L) > cap) throw PrepException(PrepException.Reason.TOO_LARGE)

        val mime = try { resolver.getType(uri) } catch (e: Exception) { null }
        // Probe once so an unreadable Uri fails here, not halfway through preparation.
        openRaw(uri).close()
        return Source(uri, name, mime, size)
    }

    /** Our own package, or any authority that starts with it (our FileProvider is "<package>.fileprovider"). */
    private fun isOwn(uri: Uri): Boolean {
        val own = ownPackage.lowercase(Locale.ROOT)
        // "0@authority" forms carry a user id; check the host as well as the whole authority.
        return listOfNotNull(uri.authority, uri.host).any {
            val a = it.lowercase(Locale.ROOT)
            a == own || a.startsWith(own)
        }
    }

    private fun openRaw(uri: Uri): InputStream = try {
        resolver.openInputStream(uri) ?: throw PrepException(PrepException.Reason.UNREADABLE)
    } catch (e: PrepException) {
        throw e
    } catch (e: Exception) {
        throw PrepException(PrepException.Reason.UNREADABLE)
    }

    private inner class Source(
        private val uri: Uri,
        override val displayName: String?,
        override val mimeType: String?,
        override val declaredSize: Long?,
    ) : PickedSource {
        private val open = ArrayList<InputStream>()

        override fun head(n: Int): ByteArray {
            val out = ByteArrayOutputStream(n.coerceAtLeast(0))
            stream().use { s ->
                val buf = ByteArray(minOf(8192, n.coerceAtLeast(1)))
                while (out.size() < n) {
                    val r = s.read(buf, 0, minOf(buf.size, n - out.size()))
                    if (r < 0) break
                    out.write(buf, 0, r)
                }
            }
            return out.toByteArray()
        }

        @Synchronized
        override fun stream(): InputStream {
            val s = CountingStream(openRaw(uri), cap)
            open.add(s)
            return s
        }

        @Synchronized
        override fun close() {
            for (s in open) try { s.close() } catch (e: Exception) { }
            open.clear()
        }
    }

    /** Throws TOO_LARGE once more than [cap] bytes were read or skipped, whatever SIZE claimed. */
    private class CountingStream(inner: InputStream, private val cap: Long) : FilterInputStream(inner) {
        private var count = 0L

        private fun add(n: Long) {
            if (n > 0) {
                count += n
                if (count > cap) throw PrepException(PrepException.Reason.TOO_LARGE)
            }
        }

        override fun read(): Int = super.read().also { if (it >= 0) add(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { add(it.toLong()) }
        override fun skip(n: Long): Long = super.skip(n).also { add(it) }
        override fun markSupported(): Boolean = false
    }
}
