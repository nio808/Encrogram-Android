package app.encrogram.core.internal

import app.encrogram.core.api.MessageStatus
import app.encrogram.core.api.MessageVersion
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Round 2, task 3: `/_get item info` (APIGetChatItemInfo, Commands.hs:687-696) as message details. Shapes from
 * Controller.hs:778 (chatItemInfo {chatItem, chatItemInfo}), Messages.hs:1345-1380 (ChatItemInfo, ChatItemVersion,
 * MemberDeliveryStatus) and :1118-1126, :1433 (GroupSndStatus JSON).
 */
class MessageInfoTest {

    @Before
    fun quiet() {
        SecuredLog.enabled = false
    }

    private fun version(id: Long, text: String, ts: String) =
        """{"chatItemVersionId":$id,"msgContent":{"type":"text","text":"$text"},
            "formattedText":[{"text":"$text"}],"itemVersionTs":"$ts","createdAt":"$ts"}"""

    /**
     * Our item 70 in a direct chat with Bob, or (group = true) in group 3. [versions] newest first, as the core orders
     * them (ORDER BY chat_item_version_id DESC, Store/Messages.hs:3347).
     */
    private fun info(
        versions: List<String>,
        statuses: String? = null,
        group: Boolean = false,
        dir: String = if (group) "groupSnd" else "directSnd",
        status: String = "sndSent",
        deleted: Boolean = false,
        text: String = "third",
    ): JSONObject {
        val chatInfo = if (group) """{"type":"group","groupInfo":{"groupId":3}}"""
        else """{"type":"direct","contact":${Samples.contact(5, "Bob", "ready")}}"""
        val content = if (dir.endsWith("Snd")) "sndMsgContent" else "rcvMsgContent"
        val item = """{"chatDir":{"type":"$dir"},
            "meta":{"itemId":70,"itemTs":"2026-11-09T10:00:00Z","itemText":"$text","itemStatus":{"type":"$status"},
              "itemSharedMsgId":"c2hhcmVk",${if (deleted) "\"itemDeleted\":{\"type\":\"deleted\",\"deletedTs\":\"2026-11-09T12:00:00Z\"}," else ""}
              "itemEdited":true,"userMention":false,"hasLink":false,"deletable":true,"editable":true,
              "showGroupAsSender":false,"createdAt":"2026-11-09T10:00:02Z","updatedAt":"2026-11-09T11:00:00Z"},
            "content":{"type":"$content","msgContent":{"type":"text","text":"$text"}},"mentions":{},"reactions":[]}"""
        val body = """{"type":"chatItemInfo","user":{"userId":1},
            "chatItem":{"chatInfo":$chatInfo,"chatItem":$item},
            "chatItemInfo":{"itemVersions":[${versions.joinToString(",")}],${
                if (statuses != null) "\"memberDeliveryStatuses\":$statuses," else ""}"fileXftpServers":[]}}"""
        return JSONObject(body)
    }

    private val edited = listOf(
        version(12, "third", "2026-11-09T11:00:00Z"),
        version(11, "second", "2026-11-09T10:30:00Z"),
        version(10, "first", "2026-11-09T10:00:00Z"),
    )

    @Test
    fun earlierVersionsNewestFirstWithoutTheCurrentText() {
        val i = CoreJson.parseMessageInfo(info(edited))!!
        assertEquals(70L, i.item.itemId)
        assertEquals(5L, i.item.chatId)
        assertEquals(CoreJson.timeMs("2026-11-09T10:00:02Z"), i.createdAtMs)
        assertEquals(CoreJson.timeMs("2026-11-09T10:00:00Z"), i.item.timeMs)
        assertEquals(
            listOf(MessageVersion("second", CoreJson.timeMs("2026-11-09T10:30:00Z")), MessageVersion("first", CoreJson.timeMs("2026-11-09T10:00:00Z"))),
            i.versions,
        )
        // Whatever order the array came in.
        assertEquals(i.versions, CoreJson.parseMessageInfo(info(edited.reversed()))!!.versions)
        assertNull("no member rows outside groups", i.deliveries)
    }

    @Test
    fun aMessageNeverEditedHasNoEarlierVersions() {
        // The core makes one version from the current content (mkItemVersion, Commands.hs:690).
        val i = CoreJson.parseMessageInfo(info(listOf(version(70, "third", "2026-11-09T10:00:00Z"))))!!
        assertTrue(i.versions.isEmpty())
        assertTrue(CoreJson.parseMessageInfo(info(emptyList()))!!.versions.isEmpty())
    }

    @Test
    fun aDeletedMessageNeverShowsItsOldTexts() {
        val i = CoreJson.parseMessageInfo(info(edited, deleted = true))!!
        assertTrue(i.item.deleted)
        assertTrue(i.versions.isEmpty())
        assertEquals("", i.item.text)
    }

    @Test
    fun versionTextsNeverReachAString() {
        val i = CoreJson.parseMessageInfo(info(edited))!!
        assertFalse(i.toString().contains("second") || i.toString().contains("first"))
        assertFalse(i.versions.toString().contains("second"))
        assertEquals("MessageVersion(5)", MessageVersion("secret", 5).toString())
    }

    @Test
    fun memberDeliveriesOfOurGroupMessage() {
        val statuses = """[
            {"groupMemberId":5,"memberDeliveryStatus":{"type":"rcvd","msgRcptStatus":"ok"},"sentViaProxy":false},
            {"groupMemberId":6,"memberDeliveryStatus":{"type":"rcvd","msgRcptStatus":"badMsgHash"}},
            {"groupMemberId":7,"memberDeliveryStatus":{"type":"sent"},"sentViaProxy":true},
            {"groupMemberId":8,"memberDeliveryStatus":{"type":"forwarded"}},
            {"groupMemberId":9,"memberDeliveryStatus":{"type":"new"}},
            {"groupMemberId":10,"memberDeliveryStatus":{"type":"inactive"}},
            {"groupMemberId":11,"memberDeliveryStatus":{"type":"warning","agentError":{"type":"proxy","proxyServer":"x","srvError":{"type":"host"}}}},
            {"groupMemberId":12,"memberDeliveryStatus":{"type":"error","agentError":{"type":"auth"}}},
            {"groupMemberId":13,"memberDeliveryStatus":{"type":"invalid","text":"snd_x"}},
            {"groupMemberId":14,"memberDeliveryStatus":{"type":"someday"}},
            {"memberDeliveryStatus":{"type":"rcvd","msgRcptStatus":"ok"}}
        ]"""
        val i = CoreJson.parseMessageInfo(info(edited, statuses, group = true))!!
        assertTrue(i.item.group && i.item.outgoing)
        assertEquals(
            listOf(
                5L to MessageStatus.DELIVERED, 6L to MessageStatus.DELIVERED,
                7L to MessageStatus.SENT_TO_SERVER, 8L to MessageStatus.SENT_TO_SERVER,
                9L to MessageStatus.SENDING, 10L to MessageStatus.SENDING, 11L to MessageStatus.SENDING,
                12L to MessageStatus.FAILED, 13L to MessageStatus.FAILED, 14L to MessageStatus.SENDING,
            ),
            i.deliveries,
        )
        // No "read" anywhere: a receipt is a delivery.
        assertTrue(i.deliveries!!.none { it.second == MessageStatus.RECEIVED })
        // memberDeliveryStatuses is left out when Nothing (no member rows): null, not an empty delivery list.
        assertNull(CoreJson.parseMessageInfo(info(edited, group = true))!!.deliveries)
    }

    @Test
    fun receivedGroupMessageTimes() {
        val i = CoreJson.parseMessageInfo(info(edited, group = true, dir = "groupRcv", status = "rcvRead"))!!
        assertFalse(i.item.outgoing)
        assertEquals(MessageStatus.RECEIVED, i.item.status)
        assertEquals(CoreJson.timeMs("2026-11-09T10:00:02Z"), i.createdAtMs)
    }

    @Test
    fun brokenRepliesAreNull() {
        assertNull(CoreJson.parseMessageInfo(JSONObject("""{"type":"chatItemInfo"}""")))
        val noInfo = info(edited).apply { remove("chatItemInfo") }
        assertNull(CoreJson.parseMessageInfo(noInfo))
        assertNotNull(CoreJson.parseMessageInfo(info(edited)))
    }

    @Test
    fun statusMapperForMemberDeliveries() {
        assertEquals(MessageStatus.DELIVERED, StatusMapper.memberDelivery("rcvd"))
        assertEquals(MessageStatus.SENT_TO_SERVER, StatusMapper.memberDelivery("sent"))
        assertEquals(MessageStatus.SENDING, StatusMapper.memberDelivery(null))
    }
}
