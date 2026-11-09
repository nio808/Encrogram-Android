package app.encrogram.core.internal

import app.encrogram.core.api.GROUP_CHAT_ID_BASE
import app.encrogram.core.background.SecuredService
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * G11: a join's history (x.grp.msg.forward with the original item time, Library/Internal.hs:1316-1419) never counts as
 * unread and never notifies; live messages, relayed or not, always do. JSON shaped after chat core 7.0.0.12
 * (Messages.hs CIMeta.forwardedByMember, Types.hs GroupMember.createdAt).
 */
class GroupHistoryTest {

    @Before
    fun quiet() {
        SecuredLog.enabled = false
    }

    private val chat = GROUP_CHAT_ID_BASE + 3
    private val joined = "2026-11-08T12:00:00Z"
    private val joinedMs = CoreJson.timeMs(joined)

    private fun membership(createdAt: String?) = """
        {"groupMemberId":1,"groupId":3,"memberId":"bWU=","memberRole":"member","memberCategory":"user",
         "memberStatus":"connected","localDisplayName":"alice",
         "memberProfile":{"profileId":21,"displayName":"Alice","fullName":"","localAlias":""},
         ${if (createdAt != null) "\"createdAt\":\"$createdAt\"," else ""}"updatedAt":"2026-11-08T12:30:00Z"}"""

    private fun groupInfo(createdAt: String? = joined) = """
        {"groupId":3,"useRelays":false,"localDisplayName":"team",
         "groupProfile":{"displayName":"Team","fullName":"","groupPreferences":{"history":{"enable":"on"}}},
         "localAlias":"","fullGroupPreferences":{"history":{"enable":"on"}},
         "membership":${membership(createdAt)},"chatSettings":{"enableNtfs":"all","favorite":false},
         "createdAt":"2026-11-08T12:00:00Z","updatedAt":"2026-11-08T12:30:00Z","chatTs":null,
         "groupSummary":{"currentMembers":3},"chatTags":[]}"""

    private val bob = """
        {"groupMemberId":5,"groupId":3,"memberId":"Ym9i","memberRole":"member","memberCategory":"pre",
         "memberStatus":"connected","localDisplayName":"bob",
         "memberProfile":{"profileId":25,"displayName":"Bob","fullName":"","localAlias":""}}"""

    private fun item(
        id: Long,
        ts: String,
        relayedBy: Long? = 2,
        status: String = "rcvNew",
        content: String = """{"type":"rcvMsgContent","msgContent":{"type":"text","text":"old news"}}""",
    ) = """
        {"chatDir":{"type":"groupRcv","groupMember":$bob},
         "meta":{"itemId":$id,"itemTs":"$ts","itemText":"old news","itemStatus":{"type":"$status"},
           "itemSharedMsgId":"c2hhcmVk",${if (relayedBy != null) "\"forwardedByMember\":$relayedBy," else ""}
           "itemEdited":false,"userMention":false,"hasLink":false,"deletable":false,"editable":false,
           "showGroupAsSender":false,"createdAt":"2026-11-08T12:31:00Z","updatedAt":"2026-11-08T12:31:00Z"},
         "content":$content,"mentions":{},"reactions":[]}"""

    private fun event(vararg items: String, info: String = groupInfo()) = JSONObject(
        """{"type":"newChatItems","chatItems":[${items.joinToString(",") { """{"chatInfo":{"type":"group","groupInfo":$info},"chatItem":$it}""" }}]}""",
    )

    private fun one(item: String, info: String = groupInfo()) = CoreJson.parseChatItems(event(item, info = info)).single()

    @Test
    fun relayedMessageFromBeforeTheJoinIsHistory() {
        val i = one(item(10, "2026-11-08T09:00:00Z"))
        assertTrue(i.relayed)
        assertTrue(i.history)
        assertFalse("history never counts as unread", i.unread)
        assertEquals("the core still holds it as new until the engine reads it", "rcvNew", i.statusTag)
        val m = MessageMapper.toMessage(i)
        assertTrue(m.history)
        assertEquals("old news", m.text)
        assertFalse(SecuredService.notifies(m))
    }

    @Test
    fun liveMessagesAlwaysNotify() {
        // Relayed for a member not yet connected to us, sent after we joined.
        val relayedLive = one(item(11, "2026-11-08T12:05:00Z"))
        // Sent by a connected member, delivered hours late (phone offline): never relayed, so never history.
        val lateDirect = one(item(12, "2026-11-08T09:00:00Z", relayedBy = null))
        for (i in listOf(relayedLive, lateDirect)) {
            assertFalse(i.history)
            assertTrue(i.unread)
            assertTrue(SecuredService.notifies(MessageMapper.toMessage(i)))
        }
        assertTrue(relayedLive.relayed)
        assertFalse(lateDirect.relayed)
    }

    @Test
    fun theClockSkewMarginKeepsBorderlineMessagesLive() {
        val justBefore = one(item(13, "2026-11-08T11:59:00Z"))
        assertFalse("within the 2-minute margin: notify", justBefore.history)
        val beyond = one(item(14, "2026-11-08T11:57:59Z"))
        assertTrue(beyond.history)
        assertTrue(GroupRules.beforeJoin(joinedMs - GroupRules.HISTORY_MARGIN_MS - 1, joinedMs))
        assertFalse(GroupRules.beforeJoin(joinedMs - GroupRules.HISTORY_MARGIN_MS, joinedMs))
    }

    @Test
    fun withoutAJoinTimeNothingIsHistory() {
        val i = one(item(15, "2026-11-01T09:00:00Z"), info = groupInfo(createdAt = null))
        assertFalse(i.history)
        assertTrue(i.unread)
        assertFalse(GroupRules.beforeJoin(0, joinedMs))
        assertFalse(GroupRules.beforeJoin(joinedMs - 3_600_000, 0))
    }

    @Test
    fun serviceRowsAndOurOwnItemsAreNeverHistory() {
        val joinedRow = one(item(16, "2026-11-08T09:00:00Z", content = """{"type":"rcvGroupEvent","rcvGroupEvent":{"type":"memberConnected"}}"""))
        assertFalse(joinedRow.history)
        val sent = CoreJson.parseChatItem(
            chat,
            JSONObject(
                """{"chatDir":{"type":"groupSnd"},"meta":{"itemId":17,"itemTs":"2026-11-08T09:00:00Z","itemText":"mine",
                  "itemStatus":{"type":"sndSent"},"forwardedByMember":2,"itemEdited":false,"deletable":true,"editable":true},
                  "content":{"type":"sndMsgContent","msgContent":{"type":"text","text":"mine"}},"mentions":{},"reactions":[]}""",
            ),
            joinedAtMs = joinedMs,
        )!!
        assertFalse(sent.relayed)
        assertFalse(sent.history)
    }

    @Test
    fun acceptingAnInvitationLaterMovesTheJoinTime() {
        // membership.createdAt is when the invitation arrived; we accepted at 14:00 and the history covers up to then.
        val betweenInviteAndJoin = one(item(18, "2026-11-08T13:00:00Z"))
        assertFalse(betweenInviteAndJoin.history)
        val acceptedAt = CoreJson.timeMs("2026-11-08T14:00:00Z")
        val marked = GroupRules.markJoinHistory(listOf(betweenInviteAndJoin)) { if (it == chat) acceptedAt else null }.single()
        assertTrue(marked.history)
        assertFalse(marked.unread)
        // A live message after accepting, and anything not relayed, stay as they are.
        val after = one(item(19, "2026-11-08T14:01:00Z"))
        val direct = one(item(20, "2026-11-08T13:00:00Z", relayedBy = null))
        val again = GroupRules.markJoinHistory(listOf(after, direct)) { acceptedAt }
        assertFalse(again[0].history)
        assertFalse(again[1].history)
        assertTrue(again[1].unread)
        // No acceptance known: unchanged.
        assertFalse(GroupRules.markJoinHistory(listOf(betweenInviteAndJoin)) { null }.single().history)
    }

    @Test
    fun pagesAndChatListRowsCarryTheFlagToo() {
        val page = JSONObject(
            """{"type":"apiChat","chat":{"chatInfo":{"type":"group","groupInfo":${groupInfo()}},
                "chatItems":[${item(21, "2026-11-08T09:00:00Z", status = "rcvRead")},${item(22, "2026-11-08T12:10:00Z")}]}}""",
        )
        val items = CoreJson.parseChatItemsOfChat(page)
        assertEquals(listOf(true, false), items.map { it.history })
        val list = JSONObject(
            """{"chats":[{"chatInfo":{"type":"group","groupInfo":${groupInfo()}},"chatItems":[${item(23, "2026-11-08T09:00:00Z")}],
                "chatStats":{"unreadCount":1}}]}""",
        )
        val row = CoreJson.parseGroupChats(list).single()
        assertEquals(joinedMs, row.group.joinedAtMs)
        assertTrue(row.lastItem!!.history)
    }

    @Test
    fun theSweepOnlyLooksAtRecentJoins() {
        val now = joinedMs + 60_000
        assertTrue(GroupRules.joinHistorySweepDue(joinedMs, now))
        assertTrue("a phone clock moved back still counts", GroupRules.joinHistorySweepDue(joinedMs + 3_600_000, now))
        assertFalse(GroupRules.joinHistorySweepDue(joinedMs, joinedMs + GroupRules.HISTORY_SWEEP_WINDOW_MS))
        assertFalse(GroupRules.joinHistorySweepDue(0, now))
    }

    @Test
    fun historyNeverPrintsItsText() {
        val i = one(item(24, "2026-11-08T09:00:00Z"))
        assertFalse(i.toString().contains("old news"))
        assertFalse(MessageMapper.toMessage(i).toString().contains("old news"))
    }
}
