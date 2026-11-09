package app.encrogram.core.internal.media

import app.encrogram.core.api.VoiceRecording
import app.encrogram.core.internal.SecuredLog
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.RandomAccessFile

/**
 * The engine's [VoiceRecording]: one file in the vault's `view/` ([MediaVault.newRecording]) and its read-write
 * descriptor. The UI writes through [fileDescriptor] only; [finish] hands the file to the preparer, [close] discards it.
 * Any thread; never logs or prints the path.
 */
internal class VoiceFile(file: File, raf: RandomAccessFile) : VoiceRecording {

    private var file: File? = file
    private var raf: RandomAccessFile? = raf

    override val fileDescriptor: FileDescriptor = raf.fd

    /** Closes the descriptor and hands over the file, which the caller must delete; null once closed or finished. */
    @Synchronized
    fun finish(): File? {
        closeDescriptor()
        return file.also { file = null }
    }

    @Synchronized
    override fun close() {
        closeDescriptor()
        val f = file ?: return
        file = null
        try {
            if (!f.delete() && f.exists()) SecuredLog.w(SecuredLog.Ev.MEDIA_IO_FAILED, "delete")
        } catch (e: SecurityException) {
            SecuredLog.w(SecuredLog.Ev.MEDIA_IO_FAILED, "delete")
        }
    }

    private fun closeDescriptor() {
        val r = raf ?: return
        raf = null
        try {
            r.close()
        } catch (e: IOException) {
            SecuredLog.w(SecuredLog.Ev.MEDIA_IO_FAILED, "close")
        }
    }

    override fun toString() = "VoiceRecording"
}
