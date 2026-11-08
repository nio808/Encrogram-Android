package app.encrogram.core.internal

import app.encrogram.core.api.MediaFailure
import app.encrogram.core.api.SecuredError.Kind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The media sentences and core file-error tags in ErrorMapper (docs/media-plan.md §4.6). */
class MediaErrorsTest {

    private fun core(detail: String, type: String = "error") = ErrorMapper.fromCore(CoreError(type, detail, null), ErrorMapper.Op.OTHER)

    @Test
    fun everyFailureHasAFixedSentence() {
        val sentences = HashSet<String>()
        for (f in MediaFailure.values()) {
            val e = ErrorMapper.mediaFailure(f)
            assertTrue(f.name, e.message.endsWith("."))
            sentences += e.message
        }
        assertEquals(MediaFailure.values().size, sentences.size)
        assertEquals(Kind.UNAPPROVED_RELAY, ErrorMapper.mediaFailure(MediaFailure.FOREIGN_SERVER).kind)
        assertEquals(ErrorMapper.MEDIA_FOREIGN, ErrorMapper.mediaFailure(MediaFailure.FOREIGN_SERVER).message)
        assertEquals(Kind.TOO_LARGE, ErrorMapper.mediaFailure(MediaFailure.TOO_LARGE).kind)
        assertEquals(Kind.UNSUPPORTED, ErrorMapper.mediaFailure(MediaFailure.UNSUPPORTED).kind)
        assertEquals(Kind.NETWORK, ErrorMapper.mediaFailure(MediaFailure.SERVER_FULL).kind)
        assertEquals(ErrorMapper.MEDIA_EXPIRED, ErrorMapper.mediaFailure(MediaFailure.EXPIRED).message)
    }

    @Test
    fun coreFileErrorsMapByTag() {
        assertEquals(Kind.UNAPPROVED_RELAY, core("fileNotApproved").kind)
        assertEquals(ErrorMapper.MEDIA_FOREIGN, core("fileNotApproved").message)
        assertEquals(ErrorMapper.MESSAGE_GONE, core("fileNotFound").message)
        for (d in listOf("fileSize", "fileImageSize")) assertEquals(d, Kind.TOO_LARGE, core(d).kind)
        for (d in listOf("fileWrite", "fileAlreadyExists")) assertEquals(d, Kind.STORAGE, core(d).kind)
        for (d in listOf("fallbackToSMPProhibited", "inlineFileProhibited")) assertEquals(d, Kind.UNSUPPORTED, core(d).kind)
        assertEquals(ErrorMapper.MEDIA_UNSUPPORTED, core("fileImageType").message)
        assertEquals(ErrorMapper.MEDIA_NOT_READY, core("fileNotReceived").message)
        for (d in listOf("fileNotFound", "rcvFileNotFound", "sndFileNotFound", "rcvFileDescrNotFound", "chatItemNotFoundByFileId")) {
            assertEquals(d, ErrorMapper.MESSAGE_GONE, core(d, "errorStore").message)
        }
        // Not ours to map: still a tagged engine error.
        assertEquals(ErrorMapper.engine("error.fileInternal"), core("fileInternal"))
    }

    @Test
    fun theUnknownServersOfARefusalNeverShow() {
        val raw = """{"error":{"type":"error","errorType":{"type":"fileNotApproved","fileId":3,
            "unknownServers":["xftp://CANARYKEY=@canary-evil.example:443"]}}}"""
        val reply = CoreJson.parseReply(raw) as CoreReply.Error
        val e = ErrorMapper.fromCore(reply.error, ErrorMapper.Op.OTHER)
        assertEquals(Kind.UNAPPROVED_RELAY, e.kind)
        assertFalse(e.toString().contains("canary", ignoreCase = true))
        assertFalse(JSONObject.quote(e.message).contains("evil"))
    }

    @Test
    fun theWordingStaysHonest() {
        val all = MediaFailure.values().map { ErrorMapper.mediaFailure(it).message } + listOf(
            ErrorMapper.MEDIA_TOO_LARGE, ErrorMapper.MEDIA_UNSUPPORTED, ErrorMapper.MEDIA_CHECK_FAILED, ErrorMapper.MEDIA_SERVER_FULL,
            ErrorMapper.MEDIA_NOT_READY, ErrorMapper.MEDIA_TOO_LARGE_TO_OPEN, ErrorMapper.MEDIA_STORAGE_FULL,
            ErrorMapper.MEDIA_CAPTION_TOO_LONG, ErrorMapper.MEDIA_PREPARED_GONE, ErrorMapper.MEDIA_NOT_IN_SAVED, ErrorMapper.MEDIA_NONE,
            ErrorMapper.MEDIA_OPEN_FAILED, ErrorMapper.MEDIA_UNREADABLE, ErrorMapper.MEDIA_TOO_LARGE_TO_PREPARE, ErrorMapper.MEDIA_REPLY_TOO_LONG,
        )
        for (s in all) {
            val lower = s.lowercase()
            for (banned in listOf("unhackable", "military", "100%", "zero metadata", "anonymous", "telegram")) assertFalse(s, lower.contains(banned))
        }
    }
}
