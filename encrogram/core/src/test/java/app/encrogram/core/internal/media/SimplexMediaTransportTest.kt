package app.encrogram.core.internal.media

import app.encrogram.core.api.MediaFailure
import app.encrogram.core.api.SecuredError
import app.encrogram.core.internal.ChatRef
import app.encrogram.core.internal.CoreJson
import app.encrogram.core.internal.CoreReply
import app.encrogram.core.internal.Samples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimplexMediaTransportTest {

    private val key = FileKey("a2V5", "bm9uY2U")
    private val checked = CheckedFile.of(42, Verdict.PASSED)!!
    private val out = OutgoingContent(app.encrogram.core.api.MediaKind.FILE, "", null, null)

    private fun transport(raw: String, seen: MutableList<String> = mutableListOf()) =
        SimplexMediaTransport { cmd -> seen.add(cmd); CoreJson.parseReply(raw) }

    private fun item(id: Long, dir: String = "directSnd", status: String = "sndNew") =
        Samples.aChatItem(5, Samples.chatItem(id, dir, "sndMsgContent", "x", status))

    private fun err(inner: String) = """{"error":{"type":"error","errorType":$inner}}"""

    private inline fun <reified T : TransportReply> TransportReply.expect(): T {
        assertTrue("was ${this::class.simpleName}", this is T)
        return this as T
    }

    @Test
    fun sendReturnsTheFirstItem() {
        val seen = mutableListOf<String>()
        val raw = """{"result":{"type":"newChatItems","chatItems":[${item(9)},${item(10)}]}}"""
        val r = transport(raw, seen).send(ChatRef.contact(5), "doc.pdf", key, out, null).expect<TransportReply.Item>()
        assertEquals(9L, r.item!!.itemId)
        assertTrue(seen.single().startsWith("/_send @5 json "))
    }

    @Test
    fun sendWithNoItemsFails() {
        val raw = """{"result":{"type":"newChatItems","chatItems":[]}}"""
        transport(raw).send(ChatRef.contact(5), "a.pdf", key, out, null).expect<TransportReply.Failed>()
    }

    @Test
    fun sendSizeErrorsAreRefusals() {
        for (tag in listOf("fileSize", "fileImageSize")) {
            val raw = err("""{"type":"$tag","filePath":"secret-name.jpg"}""")
            val r = transport(raw).send(ChatRef.contact(5), "a.pdf", key, out, null).expect<TransportReply.Refused>()
            assertEquals(MediaFailure.TOO_LARGE, r.failure)
        }
    }

    @Test
    fun sendOtherErrorsFailWithTagsOnly() {
        val raw = err("""{"type":"fileWrite","filePath":"/secret/path/name.jpg","message":"disk detail"}""")
        val f = transport(raw).send(ChatRef.contact(5), "a.pdf", key, out, null).expect<TransportReply.Failed>()
        assertTrue(f.error.message, !f.error.message.contains("secret") && !f.error.message.contains("disk detail"))
        val net = """{"error":{"type":"errorAgent","agentError":{"type":"BROKER","brokerAddress":"smp://x@h","brokerErr":{"type":"TIMEOUT"}}}}"""
        val n = transport(net).send(ChatRef.contact(5), "a.pdf", key, out, null).expect<TransportReply.Failed>()
        assertEquals(SecuredError.Kind.NETWORK, n.error.kind)
    }

    @Test
    fun receiveAcceptedGivesTheItem() {
        val seen = mutableListOf<String>()
        val raw = """{"result":{"type":"rcvFileAccepted","chatItem":${item(7, "directRcv", "rcvNew")}}}"""
        val r = transport(raw, seen).receive(checked).expect<TransportReply.Item>()
        assertNotNull(r.item)
        assertEquals("/freceive 42 approved_relays=off encrypt=on inline=off", seen.single())
    }

    @Test
    fun receiveSenderCancelledIsRefused() {
        val raw = """{"result":{"type":"rcvFileAcceptedSndCancelled","rcvFileTransfer":{"fileId":42}}}"""
        assertEquals(MediaFailure.CANCELLED, transport(raw).receive(checked).expect<TransportReply.Refused>().failure)
    }

    @Test
    fun receiveErrorTags() {
        val already = err("""{"type":"fileAlreadyReceiving","message":"x"}""")
        assertNull(transport(already).receive(checked).expect<TransportReply.Item>().item)

        val notApproved = err("""{"type":"fileNotApproved","fileId":42,"unknownServers":["xftp://AAAA@evil.example:443"]}""")
        assertEquals(MediaFailure.FOREIGN_SERVER, transport(notApproved).receive(checked).expect<TransportReply.Refused>().failure)

        val cancelled = err("""{"type":"fileCancelled","message":"x"}""")
        assertEquals(MediaFailure.CANCELLED, transport(cancelled).receive(checked).expect<TransportReply.Refused>().failure)

        val chunk = err("""{"type":"fileRcvChunk","message":"x"}""")
        assertEquals(MediaFailure.TOO_LARGE, transport(chunk).receive(checked).expect<TransportReply.Refused>().failure)

        val other = err("""{"type":"fileNotFound","message":"x"}""")
        transport(other).receive(checked).expect<TransportReply.Failed>()
        transport("not json").receive(checked).expect<TransportReply.Failed>()
        transport("""{"result":{"type":"somethingElse"}}""").receive(checked).expect<TransportReply.Failed>()
    }

    @Test
    fun cancelReplies() {
        val seen = mutableListOf<String>()
        val snd = """{"result":{"type":"sndFileCancelled","chatItem_":${item(3)},"fileTransferMeta":{},"sndFileTransfers":[]}}"""
        assertNotNull(transport(snd, seen).cancel(42).expect<TransportReply.Item>().item)
        assertEquals("/fcancel 42", seen.single())

        val rcv = """{"result":{"type":"rcvFileCancelled","rcvFileTransfer":{}}}"""
        assertNull(transport(rcv).cancel(42).expect<TransportReply.Item>().item)

        val done = err("""{"type":"fileCancel","fileId":42,"message":"x"}""")
        assertNull(transport(done).cancel(42).expect<TransportReply.Item>().item)

        transport(err("""{"type":"fileNotFound","message":"x"}""")).cancel(42).expect<TransportReply.Failed>()
    }

    @Test
    fun invalidReplyIsAFailure() {
        assertTrue(CoreJson.parseReply("") === CoreReply.Invalid)
        transport("").cancel(1).expect<TransportReply.Failed>()
    }
}
