package app.encrogram.core.internal

import app.encrogram.core.api.ChatKind
import app.encrogram.core.api.GROUP_CHAT_ID_BASE
import app.encrogram.core.api.GroupEvent
import app.encrogram.core.api.MemberRole
import app.encrogram.core.api.MemberStatus
import app.encrogram.core.api.MessagePage
import app.encrogram.core.api.MessageStatus
import app.encrogram.core.api.SecuredError
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Groups in the engine (G1): chat-list rows, senders, service rows, members, commands, ticks and the channel marker. */
class GroupsTest {

    @Before
    fun quiet() {
        SecuredLog.enabled = false
    }

    // ---- JSON shaped after chat core 7.0.0.12 (Types.hs GroupInfo/GroupMember, Messages.hs ChatItem) ----

    private fun member(
        id: Long,
        name: String,
        role: String = "member",
        status: String = "connected",
        contactId: Long? = null,
        alias: String = "",
        category: String = "invitee",
    ) = """
        {"groupMemberId":$id,"groupId":3,"memberId":"bWVtYmVy","memberRole":"$role","memberCategory":"$category",
         "memberStatus":"$status","memberSettings":{"showMessages":true},"blockedByAdmin":false,
         "invitedBy":{"type":"user"},"localDisplayName":"${name.lowercase()}",
         "memberProfile":{"profileId":${id + 20},"displayName":"$name","fullName":"","localAlias":"$alias"},
         "memberContactId":${contactId ?: "null"},"memberContactProfileId":${id + 20},
         "createdAt":"2026-11-08T09:00:00Z","updatedAt":"2026-11-08T09:00:00Z"}"""

    private fun groupInfo(
        id: Long = 3,
        name: String = "Team",
        myRole: String = "owner",
        myStatus: String = "creator",
        members: Int = 3,
        channel: Boolean = false,
        useRelays: Boolean = false,
        favorite: Boolean = false,
        ntfs: String = "all",
        alias: String = "",
        fullPrefsComments: String = "off",
    ) = """
        {"groupId":$id,"useRelays":$useRelays,"localDisplayName":"${name.lowercase()}",
         "groupProfile":{"displayName":"$name","fullName":"","groupPreferences":{"history":{"enable":"on"},
           "fullDelete":{"enable":"on"}${if (channel) ""","comments":{"enable":"on"}""" else ""}}},
         "localAlias":"$alias",
         "fullGroupPreferences":{"history":{"enable":"on"},"fullDelete":{"enable":"on"},"comments":{"enable":"$fullPrefsComments"},
           "reactions":{"enable":"on"}},
         "membership":${member(1, "Alice", myRole, myStatus, contactId = 99)},
         "chatSettings":{"enableNtfs":"$ntfs","favorite":$favorite},
         "createdAt":"2026-11-08T09:00:00Z","updatedAt":"2026-11-08T09:10:00Z","chatTs":"2026-11-08T09:20:00Z",
         "groupSummary":{"currentMembers":$members},"chatTags":[],"futureField":1}"""

    private fun groupChatInfo(info: String, scope: Boolean = false) =
        """{"type":"group","groupInfo":$info${if (scope) ""","groupChatScope":{"type":"memberSupport","groupMemberId_":4}""" else ""}}"""

    private fun groupChat(info: String, items: List<String> = emptyList(), unread: Int = 0, scope: Boolean = false) =
        """{"chatInfo":${groupChatInfo(info, scope)},"chatItems":[${items.joinToString(",")}],
            "chatStats":{"unreadCount":$unread,"reportsCount":0,"minUnreadItemId":0,"unreadChat":false}}"""

    private val bob = member(5, "Bob", contactId = 5)
    private val carol = member(6, "Carol", role = "moderator")

    private fun status(type: String, progress: String? = null) =
        """{"type":"$type"${if (progress != null) ""","msgRcptStatus":"ok","sndProgress":"$progress"""" else ""}}"""

    private fun gItem(
        id: Long,
        dir: String,
        content: String,
        status: String = status("rcvRead"),
        from: String? = null,
        meta: String = "",
        text: String = "",
        shared: Boolean = true,
    ) = """
        {"chatDir":{"type":"$dir"${if (from != null) ""","groupMember":$from""" else ""}},
         "meta":{"itemId":$id,"itemTs":"2026-11-08T10:00:${(id % 60).toString().padStart(2, '0')}Z","itemText":"$text",
           "itemStatus":$status,${if (shared) "\"itemSharedMsgId\":\"c2hhcmVk\"," else ""}$meta
           "itemEdited":false,"userMention":false,"hasLink":false,"deletable":true,"editable":true,
           "showGroupAsSender":false,"createdAt":"2026-11-08T10:00:00Z","updatedAt":"2026-11-08T10:00:00Z"},
         "content":$content,"mentions":{},"reactions":[{"reaction":{"type":"emoji","emoji":"👍"},"userReacted":false,"totalReacted":1}]}"""

    private fun msg(text: String, dir: String = "rcv") =
        """{"type":"${dir}MsgContent","msgContent":{"type":"text","text":"$text"}}"""

    private fun rcvEvent(event: String) = """{"type":"rcvGroupEvent","rcvGroupEvent":$event}"""
    private fun sndEvent(event: String) = """{"type":"sndGroupEvent","sndGroupEvent":$event}"""
    private fun profile(name: String) = """"profile":{"displayName":"$name","fullName":"","localAlias":"ignored"}"""

    private val chat3 = GROUP_CHAT_ID_BASE + 3

    private fun parse(item: String, chatId: Long = chat3) = CoreJson.parseChatItem(chatId, JSONObject(item))!!

    private fun body(vararg chats: String) = JSONObject("""{"chats":[${chats.joinToString(",")}]}""")

    // ---- 1. groups in the chat list ----

    @Test
    fun groupRowsInTheChatList() {
        val b = body(
            groupChat(
                groupInfo(),
                listOf(gItem(10, "groupSnd", msg("mine", "snd"), status("sndSent")), gItem(11, "groupRcv", msg("hello"), from = bob, text = "hello")),
                unread = 2,
            ),
            groupChat(groupInfo(id = 4, name = "News", myRole = "observer", myStatus = "connected", members = 40, channel = true)),
            groupChat(groupInfo(id = 5, name = "Party", myRole = "member", myStatus = "invited", members = 2),
                listOf(gItem(1, "groupRcv", msg("before joining"), from = bob))),
            groupChat(groupInfo(id = 6, name = "Relay", useRelays = true)),
            groupChat(groupInfo(id = 7, name = "Support"), scope = true),
            """{"chatInfo":{"type":"direct","contact":${Samples.contact(8, "Dan", "ready")}},"chatItems":[],"chatStats":{"unreadCount":0}}""",
        )
        val chats = CoreJson.parseGroupChats(b)
        assertEquals(listOf(chat3, GROUP_CHAT_ID_BASE + 4, GROUP_CHAT_ID_BASE + 5), chats.map { it.group.chatId })
        // The contact parser never picks up a group.
        assertEquals(listOf(8L), CoreJson.parseChats(b).map { it.contact.contactId })

        val team = MessageMapper.groupSummary(chats[0], archived = true)
        assertEquals(chat3, team.chatId)
        assertEquals("Team", team.displayName)
        assertEquals(ChatKind.GROUP, team.kind)
        assertEquals(3, team.memberCount)
        assertEquals(MemberRole.OWNER, team.myRole)
        assertTrue(team.canPost)
        assertTrue(team.ready)
        assertFalse(team.invited)
        assertEquals("hello", team.lastMessageText)
        assertEquals("Bob", team.lastMessageSender)
        assertFalse(team.lastMessageOutgoing)
        assertEquals(2, team.unreadCount)
        assertTrue(team.archived)
        assertFalse(team.verified)

        val news = MessageMapper.groupSummary(chats[1], archived = false)
        assertEquals(ChatKind.CHANNEL, news.kind)
        assertEquals(MemberRole.OBSERVER, news.myRole)
        assertFalse("observers never post", news.canPost)
        assertEquals(40, news.memberCount)
        assertNull(news.lastMessageText)

        val party = MessageMapper.groupSummary(chats[2], archived = false)
        assertTrue(party.invited)
        assertFalse(party.canPost)
        assertNull("nothing from before joining is previewed", party.lastMessageText)
        assertEquals(MemberRole.MEMBER, party.myRole)
    }

    @Test
    fun rowDetails() {
        fun row(info: String, items: List<String> = emptyList()) =
            MessageMapper.groupSummary(CoreJson.parseGroupChats(body(groupChat(info, items)))[0], archived = false)
        // Still connecting after accepting: not ready, can't post yet.
        val joining = row(groupInfo(myRole = "member", myStatus = "accepted"))
        assertFalse(joining.ready)
        assertFalse(joining.canPost)
        // Gone: the chat stays, posting doesn't.
        assertFalse(row(groupInfo(myRole = "member", myStatus = "left")).canPost)
        assertFalse(row(groupInfo(myRole = "member", myStatus = "removed")).canPost)
        assertTrue(row(groupInfo(myRole = "member", myStatus = "complete")).canPost)
        // Pin, mute and our alias.
        val r = row(groupInfo(favorite = true, ntfs = "none", alias = "Work"))
        assertTrue(r.pinned)
        assertTrue(r.muted)
        assertEquals("Work", r.displayName)
        // Our own last message, or a deleted one, names no sender.
        assertNull(row(groupInfo(), listOf(gItem(10, "groupSnd", msg("mine", "snd"), status("sndSent")))).lastMessageSender)
        val gone = gItem(11, "groupRcv", msg("old"), from = bob,
            meta = """"itemDeleted":{"type":"deleted","deletedTs":"2026-11-08T11:00:00Z"},""")
        val deletedRow = row(groupInfo(), listOf(gone))
        assertNull(deletedRow.lastMessageSender)
        assertNull(deletedRow.lastMessageText)
        // A service row as the newest item is no message preview (as for contacts: the list holds one item per chat).
        val joined = row(groupInfo(), listOf(gItem(10, "groupRcv", msg("hi"), from = bob), gItem(12, "groupRcv", rcvEvent("""{"type":"memberConnected"}"""), from = carol)))
        assertNull(joined.lastMessageText)
        assertNull(joined.lastMessageSender)
        assertEquals(GroupEvent(GroupEvent.Kind.JOINED, "Carol"), joined.lastEvent)
        // A message as the newest item: no lastEvent.
        assertNull(row(groupInfo(), listOf(gItem(10, "groupRcv", msg("hi"), from = bob))).lastEvent)
    }

    @Test
    fun aNewItemUpdatesTheRowsSender() {
        val row = MessageMapper.groupSummary(CoreJson.parseGroupChats(body(groupChat(groupInfo())))[0], archived = false)
        val fromCarol = parse(gItem(20, "groupRcv", msg("yo"), status("rcvNew"), from = carol))
        val r1 = MessageMapper.withNewItem(row, fromCarol)
        assertEquals("Carol", r1.lastMessageSender)
        assertEquals("yo", r1.lastMessageText)
        assertEquals(1, r1.unreadCount)
        val r2 = MessageMapper.withNewItem(r1, parse(gItem(21, "groupSnd", msg("ok", "snd"), status("sndSent"))))
        assertNull(r2.lastMessageSender)
        // Invited: no preview of anything.
        val invited = row.copy(invited = true)
        assertNull(MessageMapper.withNewItem(invited, fromCarol).lastMessageText)
    }

    // ---- 2. items: sender and service rows ----

    @Test
    fun anIncomingGroupMessageCarriesItsSender() {
        val i = parse(gItem(30, "groupRcv", msg("hello"), from = member(6, "Carol", role = "admin", alias = "Caz")))
        assertTrue(i.group && i.isMessage && !i.outgoing)
        assertEquals("hello", i.text)
        assertEquals(CoreSender(6, "Caz", MemberRole.ADMIN), i.sender)
        assertNull(i.event)
        val m = MessageMapper.toMessage(i, MemberRole.MEMBER)
        assertEquals(6L, m.sender!!.memberId)
        assertEquals("Caz", m.sender!!.displayName)
        assertEquals(MemberRole.ADMIN, m.sender!!.role)
        assertEquals(1, m.reactions.size)
        // Ours: no sender.
        val mine = MessageMapper.toMessage(parse(gItem(31, "groupSnd", msg("me", "snd"), status("sndSent"))), MemberRole.OWNER)
        assertNull(mine.sender)
        assertTrue(mine.outgoing)
        // "author" maps to member; relay and unknown roles map down to observer.
        assertEquals(MemberRole.MEMBER, parse(gItem(32, "groupRcv", msg("a"), from = member(7, "Eve", role = "author"))).sender!!.role)
        assertEquals(MemberRole.OBSERVER, parse(gItem(33, "groupRcv", msg("a"), from = member(7, "Eve", role = "superstar"))).sender!!.role)
    }

    @Test
    fun eachServiceRowWeShow() {
        data class Case(val dir: String, val content: String, val from: String?, val expected: GroupEvent)
        val cases = listOf(
            Case("groupRcv", rcvEvent("""{"type":"memberConnected"}"""), carol, GroupEvent(GroupEvent.Kind.JOINED, "Carol")),
            // The host announcing someone who has already joined (xGrpMemNew): "Dan joined", never "Dan was invited".
            Case("groupRcv", rcvEvent("""{"type":"memberAdded","groupMemberId":9,${profile("Dan")}}"""), carol, GroupEvent(GroupEvent.Kind.JOINED, "Dan")),
            Case("groupRcv", rcvEvent("""{"type":"memberAccepted","groupMemberId":9,${profile("Dan")}}"""), carol, GroupEvent(GroupEvent.Kind.JOINED, "Dan")),
            Case("groupRcv", rcvEvent("""{"type":"userAccepted"}"""), carol, GroupEvent(GroupEvent.Kind.JOINED)),
            Case("groupRcv", rcvEvent("""{"type":"memberLeft"}"""), carol, GroupEvent(GroupEvent.Kind.LEFT, "Carol")),
            Case("groupRcv", rcvEvent("""{"type":"memberRole","groupMemberId":9,${profile("Dan")},"role":"admin"}"""), carol,
                GroupEvent(GroupEvent.Kind.ROLE_CHANGED, "Dan", MemberRole.ADMIN)),
            Case("groupRcv", rcvEvent("""{"type":"userRole","role":"observer"}"""), carol, GroupEvent(GroupEvent.Kind.ROLE_CHANGED, null, MemberRole.OBSERVER)),
            Case("groupRcv", rcvEvent("""{"type":"memberDeleted","groupMemberId":9,${profile("Dan")}}"""), carol, GroupEvent(GroupEvent.Kind.REMOVED, "Dan")),
            Case("groupRcv", rcvEvent("""{"type":"userDeleted"}"""), carol, GroupEvent(GroupEvent.Kind.YOU_REMOVED)),
            Case("groupRcv", rcvEvent("""{"type":"groupDeleted"}"""), carol, GroupEvent(GroupEvent.Kind.GROUP_DELETED)),
            Case("groupRcv", rcvEvent("""{"type":"groupUpdated","groupProfile":{"displayName":"New","fullName":""}}"""), carol, GroupEvent(GroupEvent.Kind.GROUP_UPDATED)),
            Case("groupSnd", sndEvent("""{"type":"memberRole","groupMemberId":9,${profile("Dan")},"role":"moderator"}"""), null,
                GroupEvent(GroupEvent.Kind.ROLE_CHANGED, "Dan", MemberRole.MODERATOR)),
            Case("groupSnd", sndEvent("""{"type":"userRole","role":"admin"}"""), null, GroupEvent(GroupEvent.Kind.ROLE_CHANGED, null, MemberRole.ADMIN)),
            Case("groupSnd", sndEvent("""{"type":"memberDeleted","groupMemberId":9,${profile("Dan")}}"""), null, GroupEvent(GroupEvent.Kind.REMOVED, "Dan")),
            Case("groupSnd", sndEvent("""{"type":"userLeft"}"""), null, GroupEvent(GroupEvent.Kind.LEFT)),
            Case("groupSnd", sndEvent("""{"type":"groupUpdated","groupProfile":{"displayName":"New","fullName":""}}"""), null, GroupEvent(GroupEvent.Kind.GROUP_UPDATED)),
            Case("groupSnd", sndEvent("""{"type":"memberAccepted","groupMemberId":9,${profile("Dan")}}"""), null, GroupEvent(GroupEvent.Kind.JOINED, "Dan")),
            Case("groupSnd", """{"type":"sndGroupE2EEInfo","e2eeInfo":{"pqEnabled":false}}""", null, GroupEvent(GroupEvent.Kind.CREATED)),
        )
        for ((n, c) in cases.withIndex()) {
            val i = parse(gItem(100L + n, c.dir, c.content, if (c.dir == "groupSnd") status("sndNew") else status("rcvRead"), from = c.from, text = "raw core text"))
            assertEquals("case $n", c.expected, i.event)
            assertFalse(i.isMessage)
            assertTrue(i.shown)
            assertEquals("", i.text)
            assertTrue(i.reactions.isEmpty())
            val m = MessageMapper.toMessage(i, MemberRole.OWNER)
            assertEquals(c.expected, m.event)
            assertEquals("", m.text)
            assertFalse(m.canEdit)
            assertFalse(m.canDeleteForEveryone)
            assertNull(m.replyTo)
        }
    }

    @Test
    fun theJoinerSeesTheirOwnJoinNotTheHosts() {
        // G7: joining, our connection to the member who let us in comes up as their memberConnected item
        // (Subscriber.hs:885-912, with userJoinedGroup). For us that is "You joined", not "<host> joined".
        val joined = rcvEvent("""{"type":"memberConnected"}""")
        val host = parse(gItem(120, "groupRcv", joined, from = member(2, "Alpha", role = "owner", category = "host")))
        assertEquals(GroupEvent(GroupEvent.Kind.JOINED), host.event)
        assertNull("worded as our own join, never naming the host", host.event!!.memberName)
        assertTrue(host.shown)
        assertEquals(GroupEvent(GroupEvent.Kind.JOINED), MessageMapper.toMessage(host, MemberRole.MEMBER).event)
        // Members who were there before us, connecting after our join, didn't just join: no row.
        val earlier = parse(gItem(121, "groupRcv", joined, from = member(6, "Carol", category = "pre")))
        assertNull(earlier.event)
        assertFalse(earlier.shown)
        // Someone joining after us through another member (post): their memberAdded row was the join row, so none here.
        val post = parse(gItem(122, "groupRcv", joined, from = member(7, "Dan", category = "post")))
        assertNull(post.event)
        assertFalse(post.shown)
        // Someone we let in (invitee): "X joined" as before.
        assertEquals(GroupEvent(GroupEvent.Kind.JOINED, "Eve"), parse(gItem(123, "groupRcv", joined, from = member(8, "Eve", category = "invitee"))).event)
        // The host's other rows still name them.
        assertEquals(GroupEvent(GroupEvent.Kind.LEFT, "Alpha"),
            parse(gItem(124, "groupRcv", rcvEvent("""{"type":"memberLeft"}"""), from = member(2, "Alpha", category = "host"))).event)
    }

    @Test
    fun restoredGroupsExpandFromTheMemberList() {
        // G7: the member list read once RUNNING becomes the group's repair keys: other members whose connection works.
        val b = JSONObject("""{"group":{"groupInfo":${groupInfo(myRole = "member", myStatus = "connected")},"members":[
            $bob,
            ${member(6, "Carol", status = "complete", category = "pre")},
            ${member(7, "Dan", status = "announced")},
            ${member(8, "Eve", status = "left")},
            ${member(9, "Fay", status = "invited")}
        ]}}""")
        val keys = app.encrogram.core.backup.RestoreRepair.memberKeys(3, CoreJson.parseMembers(b))
        assertEquals(listOf(5L, 6L), keys.map { it.memberId })
        assertTrue(keys.all { it.isMember && it.groupId == 3L })
    }

    @Test
    fun invitationRowsInTheOneToOneChat() {
        val bobChat = """{"type":"direct","contact":${Samples.contact(5, "Bob", "ready")}}"""
        val invitation = """{"groupId":4,"groupMemberId":2,"localDisplayName":"party","groupProfile":{"displayName":"Party","fullName":""},"status":"pending"}"""
        // We invited Bob: "Bob was invited".
        val sent = CoreJson.parseAChatItem(JSONObject("""{"chatInfo":$bobChat,"chatItem":${
            gItem(40, "directSnd", """{"type":"sndGroupInvitation","groupInvitation":$invitation,"memberRole":"member"}""", status("sndSent"))}}"""))!!
        assertEquals(5L, sent.chatId)
        assertEquals(GroupEvent(GroupEvent.Kind.INVITED, "Bob", groupName = "Party"), sent.event)
        assertEquals("", sent.text)
        // Bob invited us: "You were invited to Party" (G3 fills groupName; the text itself is still empty).
        val got = CoreJson.parseAChatItem(JSONObject("""{"chatInfo":$bobChat,"chatItem":${
            gItem(41, "directRcv", """{"type":"rcvGroupInvitation","groupInvitation":$invitation,"memberRole":"member"}""")}}"""))!!
        assertEquals(GroupEvent(GroupEvent.Kind.INVITED, groupName = "Party"), got.event)
        assertEquals("", got.text)
        assertNull(got.sender)
        assertTrue(got.shown)
    }

    @Test
    fun deletedByAModeratorIsAServiceRow() {
        val moderatedMeta = """"itemDeleted":{"type":"moderated","deletedTs":"2026-11-08T11:00:00Z","byGroupMember":$carol},"""
        // fullDelete on: the row becomes a "moderated" placeholder.
        val placeholder = parse(gItem(50, "groupRcv", """{"type":"rcvModerated"}""", from = bob, meta = moderatedMeta))
        // fullDelete off: the message stays, marked moderated; its text must never show.
        val marked = parse(gItem(51, "groupRcv", msg("secret words"), from = bob, meta = moderatedMeta, text = "secret words"))
        for (i in listOf(placeholder, marked)) {
            assertEquals(GroupEvent(GroupEvent.Kind.DELETED_BY_ADMIN, "Bob"), i.event)
            assertTrue(i.deleted)
            assertFalse(i.isMessage)
            assertEquals("", i.text)
            assertTrue(i.reactions.isEmpty())
            assertFalse(MessageMapper.toMessage(i, MemberRole.OWNER).canDeleteForEveryone)
        }
        // Our own message deleted by an admin.
        val ours = parse(gItem(52, "groupSnd", """{"type":"sndModerated"}""", status("sndSent")))
        assertEquals(GroupEvent(GroupEvent.Kind.DELETED_BY_ADMIN), ours.event)
    }

    @Test
    fun unknownServiceItemsAreSkipped() {
        val skipped = listOf(
            gItem(60, "groupRcv", """{"type":"rcvGroupFeature","groupFeature":"reactions","preference":{"enable":"on"}}""", from = bob),
            gItem(61, "groupRcv", """{"type":"rcvGroupE2EEInfo","e2eeInfo":{"pqEnabled":false}}""", from = bob),
            gItem(62, "groupRcv", rcvEvent("""{"type":"memberProfileUpdated","fromProfile":{},"toProfile":{}}"""), from = bob),
            gItem(63, "groupRcv", rcvEvent("""{"type":"memberBlocked","groupMemberId":9,${profile("Dan")},"blocked":true}"""), from = bob),
            gItem(64, "groupRcv", rcvEvent("""{"type":"somethingNew"}"""), from = bob),
            // About a member, but no name to show: skipped, never worded as if it were about us.
            gItem(65, "groupRcv", rcvEvent("""{"type":"memberRole","groupMemberId":9,"role":"admin"}"""), from = bob),
            gItem(66, "groupRcv", rcvEvent("""{"type":"memberConnected"}""")),
            gItem(67, "groupRcv", """{"type":"chatBanner"}""", from = bob),
        )
        for (raw in skipped) {
            val i = parse(raw)
            assertNull(i.event)
            assertFalse(i.isMessage)
            assertFalse(i.shown)
        }
        // The page reader keeps service rows we know and drops the rest; a search finds messages only.
        val items = listOf(
            parse(gItem(70, "groupRcv", rcvEvent("""{"type":"memberConnected"}"""), from = bob)),
            parse(skipped[0]),
            parse(gItem(71, "groupRcv", msg("hello"), from = bob)),
            parse(skipped[4]),
            parse(gItem(72, "groupRcv", rcvEvent("""{"type":"memberLeft"}"""), from = bob)),
        )
        assertEquals(listOf(70L, 71L, 72L), InputRules.pageWindow(items, MessagePage(10), 10).map { it.itemId })
        assertEquals(listOf(71L), InputRules.pageWindow(items, MessagePage(10, search = "hel"), 10).map { it.itemId })
    }

    // ---- 3. delete for everyone and honest ticks ----

    @Test
    fun moderatorsMayDeleteOthersMessagesUpToTheirOwnRole() {
        fun canDelete(myRole: MemberRole?, senderRole: String, shared: Boolean = true): Boolean =
            MessageMapper.toMessage(parse(gItem(80, "groupRcv", msg("x"), from = member(9, "Dan", role = senderRole), shared = shared)), myRole)
                .canDeleteForEveryone
        assertFalse(canDelete(MemberRole.MEMBER, "member"))
        assertFalse(canDelete(MemberRole.OBSERVER, "observer"))
        assertTrue(canDelete(MemberRole.MODERATOR, "member"))
        assertTrue(canDelete(MemberRole.MODERATOR, "moderator"))
        assertFalse(canDelete(MemberRole.MODERATOR, "admin"))
        assertTrue(canDelete(MemberRole.ADMIN, "admin"))
        assertFalse(canDelete(MemberRole.ADMIN, "owner"))
        assertTrue(canDelete(MemberRole.OWNER, "owner"))
        assertFalse("no shared id, nothing to delete remotely", canDelete(MemberRole.OWNER, "member", shared = false))
        assertFalse("unknown role", canDelete(null, "member"))
    }

    @Test
    fun groupTicksNeedEveryMember() {
        // StatusMapper: in a group DELIVERED needs sndProgress complete.
        assertEquals(MessageStatus.SENT_TO_SERVER, StatusMapper.map("sndRcvd", true, "partial", group = true))
        assertEquals(MessageStatus.SENT_TO_SERVER, StatusMapper.map("sndRcvd", true, null, group = true))
        assertEquals(MessageStatus.DELIVERED, StatusMapper.map("sndRcvd", true, "complete", group = true))
        assertEquals(MessageStatus.SENT_TO_SERVER, StatusMapper.map("sndSent", true, "complete", group = true))
        // 1:1 unchanged: absent or complete is delivered; partial (never sent by the core for 1:1) is not.
        assertEquals(MessageStatus.DELIVERED, StatusMapper.map("sndRcvd", true))
        assertEquals(MessageStatus.DELIVERED, StatusMapper.map("sndRcvd", true, "complete"))
        assertEquals(MessageStatus.SENT_TO_SERVER, StatusMapper.map("sndRcvd", true, "partial"))
        // Through the parser.
        assertEquals(MessageStatus.SENT_TO_SERVER, parse(gItem(90, "groupSnd", msg("a", "snd"), status("sndRcvd", "partial"))).status)
        assertEquals(MessageStatus.DELIVERED, parse(gItem(91, "groupSnd", msg("a", "snd"), status("sndRcvd", "complete"))).status)
        assertEquals(MessageStatus.DELIVERED, parse(gItem(92, "directSnd", msg("a", "snd"), status("sndRcvd", "complete")), chatId = 5).status)
    }

    // ---- 4 and 9. create, and the channel marker ----

    @Test
    fun newGroupProfileTurnsOnHistoryAndFullDelete() {
        val g = CoreJson.newGroupProfile("Team", channel = false)
        assertEquals("Team", g.getString("displayName"))
        assertEquals("", g.getString("fullName"))
        val prefs = g.getJSONObject("groupPreferences")
        assertEquals("on", prefs.getJSONObject("history").getString("enable"))
        assertEquals("on", prefs.getJSONObject("fullDelete").getString("enable"))
        assertFalse(prefs.has(CoreJson.CHANNEL_MARKER_PREF))
        val c = CoreJson.newGroupProfile("News", channel = true).getJSONObject("groupPreferences")
        assertEquals("on", c.getJSONObject("comments").getString("enable"))
        assertEquals("comments", CoreJson.CHANNEL_MARKER_PREF)
        assertEquals(
            """/_group 1 {"displayName":"News","fullName":"","groupPreferences":{"history":{"enable":"on"},"fullDelete":{"enable":"on"},"comments":{"enable":"on"}}}""",
            normalize(Commands.newGroup(1, CoreJson.newGroupProfile("News", channel = true))),
        )
    }

    /** JSON key order is not part of the contract: compare the parsed objects, then print in a fixed order. */
    private fun normalize(cmd: String): String {
        val json = JSONObject(cmd.substringAfter(' ').substringAfter(' '))
        val prefs = json.getJSONObject("groupPreferences")
        val order = listOf("history", "fullDelete", "comments").filter { prefs.has(it) }
        return cmd.substringBefore(" {") + " " + """{"displayName":"${json.getString("displayName")}","fullName":"${json.getString("fullName")}","groupPreferences":{${
            order.joinToString(",") { """"$it":{"enable":"${prefs.getJSONObject(it).getString("enable")}"}""" }}}}"""
    }

    @Test
    fun theChannelMarkerSurvivesInTheProfile() {
        assertTrue(CoreJson.isChannel(JSONObject(groupInfo(channel = true))))
        assertFalse(CoreJson.isChannel(JSONObject(groupInfo(channel = false))))
        // Another client's profile edit that dropped our own copy keeps the merged preference.
        assertTrue(CoreJson.isChannel(JSONObject(groupInfo(channel = false, fullPrefsComments = "on")).also {
            it.getJSONObject("groupProfile").getJSONObject("groupPreferences").remove("comments")
        }))
        // Our profile says off: off, whatever the merged copy says.
        val off = JSONObject(groupInfo(channel = false, fullPrefsComments = "on"))
        off.getJSONObject("groupProfile").getJSONObject("groupPreferences").put("comments", JSONObject().put("enable", "off"))
        assertFalse(CoreJson.isChannel(off))
        assertTrue(CoreJson.parseGroup(JSONObject(groupInfo(channel = true)))!!.channel)
        assertEquals(4L, CoreJson.createdGroupId(JSONObject("""{"groupInfo":${groupInfo(id = 4)}}""")))
        assertNull(CoreJson.createdGroupId(JSONObject("{}")))
    }

    // ---- 7. members ----

    @Test
    fun membersListWithUsFirst() {
        val b = JSONObject("""{"group":{"groupInfo":${groupInfo(myRole = "admin", myStatus = "complete")},"members":[
            $bob,
            ${member(6, "Carol", role = "owner", status = "invited")},
            ${member(7, "Dan", role = "author", status = "announced", alias = "Danny")},
            ${member(8, "Relay", role = "relay")},
            ${member(9, "Ghost", status = "unknown")},
            ${member(10, "Eve", status = "left")},
            ${member(11, "Fay", status = "removed")},
            ${member(12, "Gus", status = "somethingNew")}
        ]}}""")
        val ms = CoreJson.parseMembers(b)
        assertEquals(listOf(1L, 5L, 6L, 7L, 10L, 11L), ms.map { it.groupMemberId })
        val me = ms[0]
        assertTrue(me.isMe)
        assertEquals(MemberRole.ADMIN, me.role)
        assertEquals(MemberStatus.CONNECTED, me.status)
        assertNull("our own membership is never linked to a contact", me.memberContactId)
        assertEquals(CoreMember(5, "Bob", MemberRole.MEMBER, MemberStatus.CONNECTED, false, 5L, active = true), ms[1])
        assertEquals(MemberStatus.INVITED, ms[2].status)
        assertEquals(MemberRole.OWNER, ms[2].role)
        assertFalse(ms[2].active)
        assertNull(ms[2].memberContactId)
        assertEquals("Danny", ms[3].displayName)
        assertEquals(MemberRole.MEMBER, ms[3].role)
        assertEquals(MemberStatus.CONNECTING, ms[3].status)
        assertEquals(MemberStatus.LEFT, ms[4].status)
        assertEquals(MemberStatus.REMOVED, ms[5].status)
        assertTrue(CoreJson.parseMembers(JSONObject("{}")).isEmpty())
    }

    @Test
    fun memberStatusWords() {
        val map = mapOf(
            "invited" to MemberStatus.INVITED, "pending_approval" to MemberStatus.CONNECTING, "pending_review" to MemberStatus.CONNECTING,
            "introduced" to MemberStatus.CONNECTING, "intro-inv" to MemberStatus.CONNECTING, "accepted" to MemberStatus.CONNECTING,
            "announced" to MemberStatus.CONNECTING, "connected" to MemberStatus.CONNECTED, "complete" to MemberStatus.CONNECTED,
            "creator" to MemberStatus.CONNECTED, "left" to MemberStatus.LEFT, "removed" to MemberStatus.REMOVED,
            "rejected" to MemberStatus.REMOVED, "deleted" to MemberStatus.REMOVED,
        )
        for ((w, s) in map) assertEquals(w, s, CoreJson.memberStatus(w))
        assertNull(CoreJson.memberStatus("unknown"))
        assertNull(CoreJson.memberStatus(null))
        for (r in MemberRole.values()) assertEquals(r, CoreJson.memberRole(CoreJson.roleWord(r)))
    }

    // ---- 11. member connections for the restore repair ----

    @Test
    fun memberConnectionEvents() {
        val stats = """{"connAgentVersion":7,"rcvQueuesInfo":[{"rcvServer":"smp://x","rcvSwitchStatus":"switch_started","canAbortSwitch":true}],
            "sndQueuesInfo":[],"ratchetSyncState":"agreed","ratchetSyncSupported":true}"""
        val info = JSONObject("""{"groupInfo":${groupInfo()},"member":$bob,"connectionStats_":$stats}""")
        val (connected, s) = CoreJson.memberInfoStats(info)
        assertTrue(connected)
        assertEquals(app.encrogram.core.backup.RestoreRepair.ConnStats("agreed", true, true), s)
        val noConn = CoreJson.memberInfoStats(JSONObject("""{"groupInfo":${groupInfo()},"member":${member(6, "Carol", status = "invited")}}"""))
        assertFalse(noConn.first)
        assertNull(noConn.second)

        val sync = JSONObject("""{"groupInfo":${groupInfo()},"member":$bob,"ratchetSyncProgress":{"ratchetSyncStatus":"ok","connectionStats":$stats}}""")
        assertEquals(Triple(3L, 5L, "ok"), CoreJson.memberRatchetSyncEvent(sync))
        val sw = JSONObject("""{"groupInfo":${groupInfo()},"member":$bob,"switchProgress":{"queueDirection":"rcv","switchPhase":"completed","connectionStats":$stats}}""")
        assertEquals(Triple(3L, 5L, "rcv.completed"), CoreJson.memberSwitchEvent(sw))
        assertNull(CoreJson.memberRatchetSyncEvent(JSONObject("""{"member":$bob,"ratchetSyncProgress":{"ratchetSyncStatus":"ok"}}""")))
        assertNull(CoreJson.memberSwitchEvent(JSONObject("""{"groupInfo":${groupInfo()},"member":$bob}""")))
    }

    // ---- chat ids ----

    @Test
    fun groupChatIds() {
        assertEquals(chat3, CoreJson.chatIdOf(JSONObject(groupChatInfo(groupInfo()))))
        assertNull(CoreJson.chatIdOf(JSONObject(groupChatInfo(groupInfo(), scope = true))))
        assertNull(CoreJson.chatIdOf(JSONObject(groupChatInfo(groupInfo(useRelays = true)))))
        assertNull(CoreJson.chatIdOf(JSONObject("""{"type":"group","groupInfo":{"groupId":0}}""")))
        assertEquals(3L, CoreJson.groupIdFromChatId(chat3))
        assertNull(CoreJson.groupIdFromChatId(3))
        assertNull(CoreJson.groupIdFromChatId(GROUP_CHAT_ID_BASE))
        assertEquals(chat3, CoreJson.groupChatId(3))
        assertNotNull(CoreJson.groupInfoOf(JSONObject(groupChatInfo(groupInfo()))))
    }

    // ---- commands ----

    @Test
    fun groupCommandStrings() {
        val g = ChatRef.group(3)
        assertEquals("#3", g.toString())
        assertTrue(g.isGroup)
        assertFalse(ChatRef.contact(3).isGroup)
        for (bad in listOf(0L, -1L)) {
            try {
                ChatRef.group(bad)
                throw AssertionError("group $bad accepted")
            } catch (expected: IllegalArgumentException) {
            }
        }
        // Every chat command takes the group ref exactly where it took the contact ref.
        val c = ChatRef.contact(3)
        assertEquals(Commands.sendText(c, "hi ❤", 40).replace("@3", "#3"), Commands.sendText(g, "hi ❤", 40))
        assertTrue(Commands.sendText(g, "hi").startsWith("/_send #3 json ["))
        assertEquals(Commands.updateItem(c, 7, "x").replace("@3", "#3"), Commands.updateItem(g, 7, "x"))
        assertEquals("/_delete item #3 7,8 broadcast", Commands.deleteItems(g, listOf(7, 8), Commands.DeleteMode.BROADCAST))
        assertEquals(Commands.reaction(c, 7, true, "👍").replace("@3", "#3"), Commands.reaction(g, 7, true, "👍"))
        val settings = JSONObject().put("enableNtfs", "all").put("favorite", true)
        assertEquals(Commands.chatSettings(c, settings).replace("@3", "#3"), Commands.chatSettings(g, settings))
        assertEquals("/_read chat #3", Commands.markRead(g))
        assertEquals("/_clear chat #3", Commands.clearChat(g))
        assertEquals(Commands.forward(c, ChatRef.contact(5), listOf(1)).replace("@3", "#3"), Commands.forward(g, ChatRef.contact(5), listOf(1)))

        assertEquals("/_add #3 5 member", Commands.addMember(3, 5, Commands.ROLE_MEMBER))
        assertEquals("/_add #3 5 observer", Commands.addMember(3, 5, Commands.ROLE_OBSERVER))
        try {
            Commands.addMember(3, 5, "admin")
            throw AssertionError("admin as an initial role")
        } catch (expected: IllegalArgumentException) {
        }
        assertEquals("/_join #3", Commands.joinGroup(3))
        assertEquals("/_members #3", Commands.listMembers(3))
        assertEquals("/_leave #3", Commands.leaveGroup(3))
        assertEquals("/_delete #3 full notify=on", Commands.deleteGroup(3, forEveryone = true))
        assertEquals("/_delete #3 full notify=off", Commands.deleteGroup(3, forEveryone = false))
        assertEquals("/_delete member item #3 7,8", Commands.deleteMemberItems(3, listOf(7, 8)))
        try {
            Commands.deleteMemberItems(3, emptyList())
            throw AssertionError("empty list")
        } catch (expected: IllegalArgumentException) {
        }
        assertEquals("/_info #3 12", Commands.groupMemberInfo(3, 12))
        assertEquals("/_sync #3 12", Commands.syncGroupMember(3, 12))
        assertEquals("/_sync #3 12 force=on", Commands.syncGroupMember(3, 12, force = true))
        assertEquals("/_switch #3 12", Commands.switchGroupMember(3, 12))
    }

    // ---- G3: the contract fields G1 left empty, and being removed ----

    @Test
    fun invitationRowNamesTheGroupEvenWithoutAProfileName() {
        val bobChat = """{"type":"direct","contact":${Samples.contact(5, "Bob", "ready")}}"""
        fun got(invitation: String) = CoreJson.parseAChatItem(JSONObject("""{"chatInfo":$bobChat,"chatItem":${
            gItem(41, "directRcv", """{"type":"rcvGroupInvitation","groupInvitation":$invitation,"memberRole":"member"}""")}}"""))!!.event
        // The core's local name when the profile has none; nothing when neither is there (the row still shows).
        assertEquals("party_1", got("""{"groupId":4,"groupMemberId":2,"localDisplayName":"party_1","groupProfile":{"displayName":"","fullName":""},"status":"pending"}""")!!.groupName)
        assertEquals(GroupEvent(GroupEvent.Kind.INVITED), got("""{"groupId":4,"groupMemberId":2,"status":"pending"}"""))
    }

    @Test
    fun theNewestServiceRowIsTheRowsLastEvent() {
        // A 1:1 chat whose newest item is an invitation: lastEvent, no text, no ticks.
        val invitation = """{"groupId":4,"groupMemberId":2,"localDisplayName":"party","groupProfile":{"displayName":"Party","fullName":""},"status":"pending"}"""
        val bobChat = """{"chatInfo":{"type":"direct","contact":${Samples.contact(5, "Bob", "ready")}},"chatItems":[${
            gItem(41, "directRcv", """{"type":"rcvGroupInvitation","groupInvitation":$invitation,"memberRole":"member"}""", status("rcvNew"))}],
            "chatStats":{"unreadCount":1}}"""
        val bobRow = MessageMapper.summaryOf(CoreJson.parseChats(body(bobChat))[0], archived = false)
        assertEquals(GroupEvent(GroupEvent.Kind.INVITED, groupName = "Party"), bobRow.lastEvent)
        assertNull(bobRow.lastMessageText)
        assertFalse(bobRow.lastMessageOutgoing)
        assertNull(bobRow.lastMessageStatus)
        // We invited Bob: "Bob was invited to Party", named from the contact in the list too.
        val sentChat = bobChat.replace("""{"type":"rcvGroupInvitation""", """{"type":"sndGroupInvitation""").replace(""""type":"directRcv""", """"type":"directSnd""")
        val sentRow = MessageMapper.summaryOf(CoreJson.parseChats(body(sentChat))[0], archived = false)
        assertEquals(GroupEvent(GroupEvent.Kind.INVITED, "Bob", groupName = "Party"), sentRow.lastEvent)
        assertFalse(sentRow.lastMessageOutgoing)

        // Live: a new service row sets it, the next message clears it; our own service row shows no ticks.
        val row = MessageMapper.groupSummary(CoreJson.parseGroupChats(body(groupChat(groupInfo())))[0], archived = false)
        val left = MessageMapper.withNewItem(row, parse(gItem(60, "groupRcv", rcvEvent("""{"type":"memberLeft"}"""), status("rcvNew"), from = carol)))
        assertEquals(GroupEvent(GroupEvent.Kind.LEFT, "Carol"), left.lastEvent)
        assertNull(left.lastMessageText)
        assertNull(left.lastMessageSender)
        assertEquals(1, left.unreadCount)
        val roleRow = MessageMapper.withNewItem(left, parse(gItem(61, "groupSnd",
            sndEvent("""{"type":"memberRole","groupMemberId":5,${profile("Bob")},"role":"admin"}"""), status("sndSent"))))
        assertEquals(GroupEvent(GroupEvent.Kind.ROLE_CHANGED, "Bob", MemberRole.ADMIN), roleRow.lastEvent)
        assertFalse(roleRow.lastMessageOutgoing)
        assertNull(roleRow.lastMessageStatus)
        val next = MessageMapper.withNewItem(roleRow, parse(gItem(62, "groupRcv", msg("hi"), from = bob)))
        assertNull(next.lastEvent)
        assertEquals("hi", next.lastMessageText)
        assertEquals("Bob", next.lastMessageSender)
        // Invited: no preview at all, not even a service row.
        assertNull(MessageMapper.withNewItem(row.copy(invited = true), parse(gItem(63, "groupRcv", rcvEvent("""{"type":"memberLeft"}"""), from = carol))).lastEvent)
        val invitedRow = MessageMapper.groupSummary(CoreJson.parseGroupChats(body(groupChat(groupInfo(myRole = "member", myStatus = "invited"),
            listOf(gItem(12, "groupRcv", rcvEvent("""{"type":"memberConnected"}"""), from = carol)))))[0], archived = false)
        assertNull(invitedRow.lastEvent)
    }

    @Test
    fun anAdminRemovingYouLeavesAReadableChatYouCantPostIn() {
        // xGrpMemDel for our own member id (Subscriber.hs:3625-3636): status removed, a userDeleted row from the admin.
        val removedBy = member(6, "Carol", role = "admin")
        val row = MessageMapper.groupSummary(CoreJson.parseGroupChats(body(groupChat(groupInfo(myRole = "member", myStatus = "removed"),
            listOf(gItem(70, "groupRcv", rcvEvent("""{"type":"userDeleted"}"""), status("rcvNew"), from = removedBy)), unread = 1)))[0], archived = false)
        assertEquals(chat3, row.chatId)
        assertEquals(GroupEvent(GroupEvent.Kind.YOU_REMOVED), row.lastEvent)
        assertFalse(row.canPost)
        assertFalse(row.invited)
        assertEquals(MemberRole.MEMBER, row.myRole)
        // The row in the chat itself.
        val m = MessageMapper.toMessage(parse(gItem(70, "groupRcv", rcvEvent("""{"type":"userDeleted"}"""), from = removedBy)), MemberRole.MEMBER)
        assertEquals(GroupEvent(GroupEvent.Kind.YOU_REMOVED), m.event)
        assertEquals("", m.text)
        // And every link or role action is refused readably from now on.
        val g = CoreJson.parseGroup(JSONObject(groupInfo(myRole = "admin", myStatus = "removed")))!!
        assertEquals(ErrorMapper.NOT_A_MEMBER, GroupRules.linkRefusal(g))
    }

    // ---- errors ----

    @Test
    fun groupErrorsAreReadable() {
        fun err(detail: String, type: String = "error") = ErrorMapper.fromCore(CoreError(type, detail, null), ErrorMapper.Op.CHAT)
        assertEquals(SecuredError.Kind.NOT_ALLOWED, err("groupUserRole").kind)
        assertEquals(ErrorMapper.GROUP_ROLE, err("groupUserRole").message)
        assertEquals(ErrorMapper.NOT_JOINED, err("groupNotJoined").message)
        assertEquals(ErrorMapper.ALREADY_MEMBER, err("groupDuplicateMember").message)
        assertEquals(ErrorMapper.ALREADY_JOINED, err("groupAlreadyJoined", "errorStore").message)
        assertEquals(ErrorMapper.INVITATION_GONE, err("groupInvitationNotFound", "errorStore").message)
        assertEquals("No one could be added. Try again.", ErrorMapper.addFailed(2, 2).message)
        assertEquals("1 of 3 people couldn't be added. Try again for them.", ErrorMapper.addFailed(1, 3).message)
    }

    // ---- link joins: one row, "X joined the group via invite link" ----

    /** [gItem] at an explicit item time (the core orders a chat by item time, then id). */
    private fun gItemAt(ts: String, id: Long, dir: String, content: String, from: String? = null) =
        gItem(id, dir, content, from = from).replace(Regex("\"itemTs\":\"[^\"]+\""), "\"itemTs\":\"$ts\"")

    /** GroupInfo of a group we joined through its link (Types.hs:477-503: viaGroupLinkUri, preparedGroup). */
    private fun joinedByLinkInfo(prepared: Boolean = false): String {
        val g = JSONObject(groupInfo(myRole = "member", myStatus = "connected"))
        if (prepared) {
            g.put("preparedGroup", JSONObject("""{"connLinkToConnect":{"connFullLink":"simplex:/contact#/?v=2-7&smp=x",
                "connShortLink":"https://encrogram.nytmanager.com/g#x"},"connLinkPreparedConnection":true,"connLinkStartedConnection":true}"""))
        } else {
            g.put("viaGroupLinkUri", "simplex:/contact#/?v=2-7&smp=x")
        }
        return g.toString()
    }

    private fun apiChat(info: String, items: List<String>) =
        JSONObject("""{"chat":{"chatInfo":${groupChatInfo(info)},"chatItems":[${items.joinToString(",")}],"chatStats":{"unreadCount":0}}}""")

    @Test
    fun theLinkOwnerSeesOneRowForALinkJoin() {
        // Bravo as the link owner's core writes him: invitee, no contact (createJoiningMember, Store/Groups.hs:2068-2128).
        val bravoAccepted = member(7, "Bravo", status = "accepted")
        val bravo = member(7, "Bravo")
        // Accepting the request (Subscriber.hs:1626-1629): never shown, only marks the member.
        val accepted = parse(gItemAt("2026-11-08T10:00:01Z", 130, "groupRcv", rcvEvent("""{"type":"invitedViaGroupLink"}"""), from = bravoAccepted))
        assertNull(accepted.event)
        assertFalse(accepted.shown)
        assertEquals(7L, accepted.linkJoinOf)
        // His connection coming up (:950): on its own, a plain join row; the member record never decides it.
        val joined = parse(gItemAt("2026-11-08T10:00:04Z", 131, "groupRcv", rcvEvent("""{"type":"memberConnected"}"""), from = bravo))
        assertEquals(GroupEvent(GroupEvent.Kind.JOINED, "Bravo"), joined.event)
        assertTrue(joined.shown)
        assertNull(joined.linkJoinOf)
        // Live, the engine has seen his invitedViaGroupLink row seconds earlier (remembered per group and member):
        val seen = setOf(chat3 to 7L)
        val live = CoreJson.markLinkJoins(CoreJson.parseChatItems(JSONObject("""{"chatItems":[{"chatInfo":${groupChatInfo(groupInfo())},"chatItem":${
            gItem(131, "groupRcv", rcvEvent("""{"type":"memberConnected"}"""), from = bravo)}}]}"""))) { c, m -> (c to m) in seen }
        assertEquals(listOf(GroupEvent(GroupEvent.Kind.JOINED_BY_LINK, "Bravo")), live.map { it.event })
        assertEquals(GroupEvent(GroupEvent.Kind.JOINED_BY_LINK, "Bravo"), MessageMapper.toMessage(live[0], MemberRole.OWNER).event)
        // The same member id in another group, our own join and other rows are never marked.
        val others = CoreJson.markLinkJoins(listOf(
            CoreJson.parseChatItem(chat3 + 1, JSONObject(gItem(133, "groupRcv", rcvEvent("""{"type":"memberConnected"}"""), from = bravo)))!!,
            parse(gItem(134, "groupRcv", rcvEvent("""{"type":"memberLeft"}"""), from = bravo)),
            parse(gItem(135, "groupRcv", msg("hello"), from = bravo)),
        )) { c, m -> (c to m) in seen }
        assertEquals(listOf(GroupEvent(GroupEvent.Kind.JOINED, "Bravo"), GroupEvent(GroupEvent.Kind.LEFT, "Bravo"), null), others.map { it.event })

        // Reopened later: the page's invitedViaGroupLink row marks him, whether or not a contact is linked to him now
        // (a contact merge links one, Subscriber.hs:3083-3086). An admin-invited member whose contact we deleted since
        // (that clears memberContactId, Store/Direct.hs:491) has no such row and stays "joined".
        for (bravoNow in listOf(bravo, member(7, "Bravo", contactId = 12))) {
            val page = CoreJson.parseChatItemsOfChat(apiChat(groupInfo(), listOf(
                gItemAt("2026-11-08T10:00:00Z", 129, "groupRcv", msg("hi"), from = bob),
                gItemAt("2026-11-08T10:00:01Z", 130, "groupRcv", rcvEvent("""{"type":"invitedViaGroupLink"}"""), from = bravoNow),
                gItemAt("2026-11-08T10:00:04Z", 131, "groupRcv", rcvEvent("""{"type":"memberConnected"}"""), from = bravoNow),
                gItemAt("2026-11-08T10:00:05Z", 132, "groupRcv", rcvEvent("""{"type":"memberConnected"}"""), from = member(8, "Dan")),
            )))
            val shown = InputRules.pageWindow(page, MessagePage(10), 10)
            assertEquals(listOf(129L, 131L, 132L), shown.map { it.itemId })
            assertEquals(GroupEvent(GroupEvent.Kind.JOINED_BY_LINK, "Bravo"), shown[1].event)
            assertEquals("no link row for Dan", GroupEvent(GroupEvent.Kind.JOINED, "Dan"), shown[2].event)
        }

        // The stored key: group and member in one Long; nothing outside groups or for ids that don't fit.
        val key = CoreJson.linkJoinKey(chat3, 7)!!
        assertEquals(3L, CoreJson.linkJoinGroupId(key))
        assertEquals(7L, key and 0xFFFFFFFFL)
        assertNotEquals(key, CoreJson.linkJoinKey(chat3 + 1, 7))
        assertNull(CoreJson.linkJoinKey(5, 7))
        assertNull(CoreJson.linkJoinKey(chat3, 0))
        assertNull(CoreJson.linkJoinKey(chat3, 1L shl 31))
        assertNull(CoreJson.linkJoinKey(GROUP_CHAT_ID_BASE + (1L shl 31), 7))
    }

    @Test
    fun goneWhenNoLongerAMember() {
        fun row(status: String) =
            MessageMapper.groupSummary(CoreJson.parseGroupChats(body(groupChat(groupInfo(myRole = "member", myStatus = status))))[0], archived = false)
        for (s in listOf("left", "removed", "rejected", "deleted")) assertTrue(s, row(s).gone)
        for (s in listOf("connected", "complete", "invited", "accepted", "creator")) assertFalse(s, row(s).gone)
        // A new item keeps it.
        assertTrue(MessageMapper.withNewItem(row("removed"), parse(gItem(70, "groupRcv", rcvEvent("""{"type":"userDeleted"}"""), from = carol))).gone)
        // 1:1 chats never are.
        assertFalse(MessageMapper.summaryOf(CoreJson.parseChats(body("""{"chatInfo":{"type":"direct","contact":${Samples.contact(8, "Dan", "ready")}},
            "chatItems":[],"chatStats":{"unreadCount":0}}"""))[0], archived = false).gone)
    }

    @Test
    fun otherMembersSeeOneJoinRow() {
        // Members other than the one who let Bravo in get XGrpMemNew from them (memberAdded, after Bravo is already
        // connected to them) and later Bravo's own connection (memberConnected, category post). Nothing in either says
        // "link" (MemberInfo, Types.hs:1043-1050), so a link join and an admin's invitation read the same: one row,
        // "Bravo joined" (never "Bravo was invited" then "Bravo joined").
        val alpha = member(2, "Alpha", role = "owner")
        val bravo = member(7, "Bravo", category = "post")
        val items = listOf(
            gItemAt("2026-11-08T10:00:00Z", 139, "groupRcv", msg("hi"), from = bob),
            gItemAt("2026-11-08T10:00:01Z", 140, "groupRcv", rcvEvent("""{"type":"memberAdded","groupMemberId":7,${profile("Bravo")}}"""), from = alpha),
            gItemAt("2026-11-08T10:00:04Z", 141, "groupRcv", rcvEvent("""{"type":"memberConnected"}"""), from = bravo),
            gItemAt("2026-11-08T10:00:05Z", 142, "groupRcv", rcvEvent("""{"type":"memberProfileUpdated","fromProfile":{},"toProfile":{}}"""), from = bravo),
        )
        // A page of the chat: one row about Bravo.
        val page = CoreJson.parseChatItemsOfChat(apiChat(groupInfo(myRole = "member", myStatus = "connected"), items))
        val shown = InputRules.pageWindow(page, MessagePage(10), 10)
        assertEquals(listOf(139L, 140L), shown.map { it.itemId })
        assertEquals(GroupEvent(GroupEvent.Kind.JOINED, "Bravo"), shown[1].event)
        assertEquals(1, shown.count { it.event != null })
        // Live: the announcement is the one event the screens get; Bravo's connection coming up later is not shown.
        val live = items.drop(1).map { CoreJson.parseAChatItem(JSONObject("""{"chatInfo":${groupChatInfo(groupInfo())},"chatItem":$it}"""))!! }
        assertEquals(listOf(GroupEvent(GroupEvent.Kind.JOINED, "Bravo"), null, null), live.map { it.event })
        assertEquals(listOf(true, false, false), live.map { it.shown })
        // Alpha once came in through our own link (we remember Alpha, id 2, as a link joiner): Alpha announcing Bravo
        // is still a plain "Bravo joined", never "joined via invite link" (only a member's own connection row is marked).
        assertEquals(listOf(GroupEvent(GroupEvent.Kind.JOINED, "Bravo"), null, null),
            CoreJson.markLinkJoins(live) { _, m -> m == 2L || m == 7L }.map { it.event })
        assertNull(live[0].connectedOf)
        assertEquals(7L, live[1].connectedOf)
        // The chat list previews the same single row.
        val row = MessageMapper.groupSummary(CoreJson.parseGroupChats(body(groupChat(groupInfo(), listOf(items[1]))))[0], archived = false)
        assertEquals(GroupEvent(GroupEvent.Kind.JOINED, "Bravo"), row.lastEvent)
        assertNull(CoreJson.newestShown(listOf(live[1], live[2])))
    }

    @Test
    fun eachSideOfALinkJoinSeesItOnce() {
        // Bravo joins "Team" by Alpha's link; Carol was already a member.
        // Bravo (joiner): Alpha's connection is his own join, via the link; Carol (pre) adds no row.
        val joiner = CoreJson.parseChatItemsOfChat(apiChat(joinedByLinkInfo(), listOf(
            gItemAt("2026-11-08T10:00:03Z", 160, "groupRcv", rcvEvent("""{"type":"memberConnected"}"""), from = member(2, "Alpha", role = "owner", category = "host")),
            gItemAt("2026-11-08T10:00:05Z", 161, "groupRcv", rcvEvent("""{"type":"memberConnected"}"""), from = member(6, "Carol", category = "pre")),
        ))).filter { it.shown }
        assertEquals(listOf(GroupEvent(GroupEvent.Kind.JOINED_BY_LINK)), joiner.map { it.event })
        // Alpha (link owner): invitedViaGroupLink (hidden) then Bravo's connection, "Bravo joined the group via invite link".
        val owner = CoreJson.parseChatItemsOfChat(apiChat(groupInfo(), listOf(
            gItemAt("2026-11-08T10:00:01Z", 170, "groupRcv", rcvEvent("""{"type":"invitedViaGroupLink"}"""), from = member(7, "Bravo", status = "accepted")),
            gItemAt("2026-11-08T10:00:04Z", 171, "groupRcv", rcvEvent("""{"type":"memberConnected"}"""), from = member(7, "Bravo")),
        ))).filter { it.shown }
        assertEquals(listOf(GroupEvent(GroupEvent.Kind.JOINED_BY_LINK, "Bravo")), owner.map { it.event })
        // Carol: Alpha's announcement, then Bravo connecting to her: "Bravo joined", once.
        val other = CoreJson.parseChatItemsOfChat(apiChat(groupInfo(myRole = "member", myStatus = "connected"), listOf(
            gItemAt("2026-11-08T10:00:02Z", 180, "groupRcv", rcvEvent("""{"type":"memberAdded","groupMemberId":9,${profile("Bravo")}}"""), from = member(2, "Alpha", role = "owner")),
            gItemAt("2026-11-08T10:00:06Z", 181, "groupRcv", rcvEvent("""{"type":"memberConnected"}"""), from = member(9, "Bravo", category = "post")),
        ))).filter { it.shown }
        assertEquals(listOf(GroupEvent(GroupEvent.Kind.JOINED, "Bravo")), other.map { it.event })
    }

    @Test
    fun theJoinerSeesYouJoinedViaInviteLink() {
        val connected = rcvEvent("""{"type":"memberConnected"}""")
        // The host as a link joiner's core writes them: no contact behind them.
        val host = member(2, "Alpha", role = "owner", category = "host")
        for (prepared in listOf(false, true)) {
            val info = joinedByLinkInfo(prepared)
            assertTrue(CoreJson.joinedByLink(JSONObject(info)))
            // A page of the chat.
            val page = CoreJson.parseChatItemsOfChat(apiChat(info, listOf(gItem(150, "groupRcv", connected, from = host))))
            assertEquals(GroupEvent(GroupEvent.Kind.JOINED_BY_LINK), page.single().event)
            assertNull("our own join never names the host", page.single().event!!.memberName)
            // The live event.
            val live = CoreJson.parseAChatItem(JSONObject("""{"chatInfo":${groupChatInfo(info)},"chatItem":${gItem(150, "groupRcv", connected, from = host)}}"""))!!
            assertEquals(GroupEvent(GroupEvent.Kind.JOINED_BY_LINK), live.event)
            // The chat list's newest item.
            val row = CoreJson.parseGroupChats(body(groupChat(info, listOf(gItem(150, "groupRcv", connected, from = host)))))[0]
            assertEquals(GroupEvent(GroupEvent.Kind.JOINED_BY_LINK), MessageMapper.groupSummary(row, archived = false).lastEvent)
        }
        // Invited by a contact: the GroupInfo has no link, so plain "You joined", even when the host's contact is gone.
        assertFalse(CoreJson.joinedByLink(JSONObject(groupInfo(myRole = "member", myStatus = "connected"))))
        val invited = CoreJson.parseChatItemsOfChat(apiChat(groupInfo(myRole = "member", myStatus = "connected"),
            listOf(gItem(151, "groupRcv", connected, from = host))))
        assertEquals(GroupEvent(GroupEvent.Kind.JOINED), invited.single().event)
        assertFalse(CoreJson.joinedByLink(null))
    }

    // ---- the chat list's preview after a join ----

    @Test
    fun aJustJoinedGroupPreviewsItsNewestServiceRow() {
        val info = joinedByLinkInfo()
        val host = member(2, "Alpha", role = "owner", category = "host")
        // What the joiner's core holds right after a link join (Subscriber.hs:885-912, 1835-1845): history the host
        // sent (old item times, new ids), E2EE info, feature rows, "You joined", then a row for each member who was
        // already there connecting to us, and the host's profile update (XGrpLinkMem).
        val items = listOf(
            gItemAt("2026-11-08T09:00:00Z", 210, "groupRcv", msg("earlier post"), from = host),
            gItemAt("2026-11-08T10:00:01Z", 200, "groupRcv", """{"type":"rcvGroupE2EEInfo","e2eeInfo":{"pqEnabled":false}}""", from = host),
            gItemAt("2026-11-08T10:00:02Z", 201, "groupRcv", """{"type":"rcvGroupFeature","groupFeature":"history","preference":{"enable":"on"}}""", from = host),
            gItemAt("2026-11-08T10:00:03Z", 202, "groupRcv", rcvEvent("""{"type":"memberConnected"}"""), from = host),
            gItemAt("2026-11-08T10:00:05Z", 211, "groupRcv", rcvEvent("""{"type":"memberConnected"}"""), from = member(6, "Carol", category = "pre")),
            gItemAt("2026-11-08T10:00:06Z", 212, "groupRcv", rcvEvent("""{"type":"memberConnected"}"""), from = member(8, "Dan", category = "pre")),
            gItemAt("2026-11-08T10:00:07Z", 213, "groupRcv", rcvEvent("""{"type":"memberProfileUpdated","fromProfile":{},"toProfile":{}}"""), from = host),
        )
        // The bug: apiChats lists only the newest item (by item time, Store/Messages.hs:895-913), a skipped one, so the
        // row had neither text nor lastEvent ("No messages yet").
        val listed = CoreJson.parseGroupChats(body(groupChat(info, listOf(items.last()))))[0]
        assertFalse(listed.lastItem!!.shown)
        assertNull(MessageMapper.groupSummary(listed, archived = false).lastEvent)
        assertNull(MessageMapper.groupSummary(listed, archived = false).lastMessageText)

        // The look-back page: the newest shown item is "You joined via invite link", newer than the history message
        // even though that has a higher id.
        val page = CoreJson.parseChatItemsOfChat(apiChat(info, items))
        val newest = CoreJson.newestShown(page)!!
        assertEquals(202L, newest.itemId)
        val row = MessageMapper.groupSummary(listed.copy(lastItem = newest), archived = false)
        assertEquals(GroupEvent(GroupEvent.Kind.JOINED_BY_LINK), row.lastEvent)
        assertNull(row.lastMessageText)
        assertNull(row.lastMessageSender)
        assertFalse(row.lastMessageOutgoing)

        // A message newer than every service row is the preview instead, with its sender.
        val later = page + parse(gItemAt("2026-11-08T10:00:09Z", 214, "groupRcv", msg("welcome!"), from = member(6, "Carol", category = "pre")))
        val msgRow = MessageMapper.groupSummary(listed.copy(lastItem = CoreJson.newestShown(later)), archived = false)
        assertEquals("welcome!", msgRow.lastMessageText)
        assertEquals("Carol", msgRow.lastMessageSender)
        assertNull(msgRow.lastEvent)

        // On the link owner's side: "Bravo joined via invite link" beats the later skipped rows.
        val ownerPage = CoreJson.parseChatItemsOfChat(apiChat(groupInfo(), listOf(
            gItemAt("2026-11-08T10:00:01Z", 300, "groupRcv", rcvEvent("""{"type":"invitedViaGroupLink"}"""), from = member(7, "Bravo", status = "accepted")),
            gItemAt("2026-11-08T10:00:04Z", 301, "groupRcv", rcvEvent("""{"type":"memberConnected"}"""), from = member(7, "Bravo")),
            gItemAt("2026-11-08T10:00:08Z", 302, "groupRcv", rcvEvent("""{"type":"memberProfileUpdated","fromProfile":{},"toProfile":{}}"""), from = member(7, "Bravo")),
        )))
        assertEquals(GroupEvent(GroupEvent.Kind.JOINED_BY_LINK, "Bravo"), CoreJson.newestShown(ownerPage)!!.event)
        // Nothing shown at all: no preview (the row keeps "No messages yet").
        assertNull(CoreJson.newestShown(page.filter { !it.shown }))
    }

    // ---- a deleted group has no members ----

    @Test
    fun aDeletedGroupShowsNoMembers() {
        // xGrpDel (Subscriber.hs:3717-3727) sets only our membership to "deleted": the core's count drops by one for us
        // (M20250919_group_summary triggers) and still counts the others' rows, so the header said "2 members".
        val info = groupInfo(myRole = "member", myStatus = "deleted", members = 2)
        val listed = CoreJson.parseGroupChats(body(groupChat(info, listOf(
            gItem(90, "groupRcv", rcvEvent("""{"type":"groupDeleted"}"""), from = member(2, "Alpha", role = "owner")),
        ))))[0]
        assertTrue(listed.group.deleted)
        assertEquals("the core's own count is still read as it is", 2, listed.group.memberCount)
        val row = MessageMapper.groupSummary(listed, archived = false)
        assertEquals(0, row.memberCount)
        assertTrue(row.gone)
        assertFalse(row.canPost)
        assertEquals(GroupEvent(GroupEvent.Kind.GROUP_DELETED), row.lastEvent)
        // A later item keeps it at 0.
        assertEquals(0, MessageMapper.withNewItem(row, parse(gItem(91, "groupRcv", rcvEvent("""{"type":"groupDeleted"}"""), from = carol))).memberCount)
        // Left, removed and live groups keep the core's count; only a deleted group is empty.
        for (st in listOf("left", "removed", "rejected", "connected", "creator")) {
            val g = CoreJson.parseGroupChats(body(groupChat(groupInfo(myRole = "member", myStatus = st, members = 2))))[0]
            assertFalse(st, g.group.deleted)
            assertEquals(st, 2, MessageMapper.groupSummary(g, archived = false).memberCount)
        }
    }

    // ---- rejoins: one row per group ----

    private fun memberWithId(id: Long, name: String, memberId: String, status: String = "connected", role: String = "member", category: String = "invitee") =
        member(id, name, role = role, status = status, category = category).replace("\"memberId\":\"bWVtYmVy\"", "\"memberId\":\"$memberId\"")

    /** A group row as the chat list reads it, optionally joined by [link] (viaGroupLinkUri) or prepared from it. */
    private fun listedGroup(id: Long, name: String, status: String, link: String? = null, prepared: Boolean = false, members: Int = 3): CoreGroup {
        val g = JSONObject(groupInfo(id = id, name = name, myRole = "member", myStatus = status, members = members))
        if (link != null && prepared) {
            g.put("preparedGroup", JSONObject().put("connLinkToConnect", JSONObject().put("connFullLink", link).put("connShortLink", "https://encrogram.nytmanager.com/g#short"))
                .put("connLinkPreparedConnection", true).put("connLinkStartedConnection", true))
        } else if (link != null) {
            g.put("viaGroupLinkUri", link)
        }
        return CoreJson.parseGroupChats(body(groupChat(g.toString())))[0].group
    }

    @Test
    fun theLinkWeJoinedByIsKeptOnlyAsAHash() {
        val link = "simplex:/contact#/?v=2-7&smp=smp%3A%2F%2Ffp%40encrogram.nytmanager.com%2Fq1&data=%7B%22groupLinkId%22%3A%22abc%22%7D"
        val g = listedGroup(3, "Team", "connected", link)
        assertEquals("Team", g.profileName)
        val key = g.linkKey!!
        assertEquals(64, key.length)
        assertFalse("the link itself is never kept", key.contains("smp") || key.contains("encrogram"))
        // The same link under the other scheme (SimpleX matches both, Commands.hs:4602-4606), or from a prepared join.
        assertEquals(key, listedGroup(4, "Team", "connected", link.replace("simplex:/contact#", "https://simplex.chat/contact#")).linkKey)
        assertEquals(key, listedGroup(5, "Team", "connected", link, prepared = true).linkKey)
        // Another link, no link, or a link without a # part.
        assertNotEquals(key, listedGroup(6, "Team", "connected", link.replace("q1", "q2")).linkKey)
        assertNull(listedGroup(7, "Team", "connected").linkKey)
        assertNull(listedGroup(8, "Team", "connected", "simplex:/contact").linkKey)
        // The profile name, not our local alias.
        val aliased = JSONObject(groupInfo(name = "Team", alias = "My team"))
        assertEquals("Team", CoreJson.parseGroup(aliased)!!.profileName)
        assertEquals("My team", CoreJson.parseGroup(aliased)!!.displayName)
    }

    @Test
    fun otherMembersIdsFromTheMemberList() {
        val b = JSONObject("""{"group":{"groupInfo":${groupInfo(myStatus = "removed")},"members":[
            ${memberWithId(2, "Alpha", "QUxQSEE=", role = "owner", category = "host")},
            ${memberWithId(6, "Carol", "Q0FST0w=", status = "left")},
            ${memberWithId(8, "Relay", "UkVMQVk=", role = "relay")},
            ${memberWithId(9, "Ghost", "R0hPU1Q=", status = "unknown")}
        ]}}""")
        assertEquals(setOf("QUxQSEE=", "Q0FST0w="), CoreJson.otherMemberIds(b))
        assertTrue(CoreJson.otherMemberIds(JSONObject("{}")).isEmpty())
    }

    @Test
    fun aRejoinByTheSameLinkReplacesTheOldRow() {
        val link = "simplex:/contact#/?v=2-7&smp=smp%3A%2F%2Ffp%40encrogram.nytmanager.com%2Fq1"
        for (oldStatus in listOf("removed", "left", "rejected")) {
            val old = listedGroup(3, "Tab Group", oldStatus, link)
            val rejoined = listedGroup(9, "Tab Group", "connected", link, prepared = true)
            // Same link: no member list needed.
            val result = GroupRules.successors(listOf(rejoined, old)) { error("not needed: $it") }
            assertEquals(oldStatus, mapOf(3L to 9L), result)
        }
        // Still connecting to the group counts too (the new row is what the user just joined).
        assertEquals(mapOf(3L to 9L), GroupRules.successors(listOf(listedGroup(3, "T", "removed", link), listedGroup(9, "T", "accepted", link))) { error("member lists not needed") })
    }

    @Test
    fun aRejoinAfterAnInvitationIsMatchedByItsMembers() {
        // First invited by a contact (no link), removed, then joined again by link: the new row knows the host first.
        val old = listedGroup(3, "Team", "removed")
        val rejoined = listedGroup(9, "Team", "connected", "simplex:/contact#/?v=2-7&smp=x")
        val ids = mapOf(3L to setOf("QUxQSEE=", "Q0FST0w="), 9L to setOf("QUxQSEE="))
        val asked = ArrayList<Long>()
        assertEquals(mapOf(3L to 9L), GroupRules.successors(listOf(old, rejoined)) { asked.add(it.groupId); ids[it.groupId] })
        assertEquals(listOf(3L, 9L), asked)
        // Another group with the same name and other people: kept.
        assertTrue(GroupRules.successors(listOf(old, rejoined)) { if (it.groupId == 3L) setOf("QUxQSEE=") else setOf("WkFL") }.isEmpty())
        // Member lists that couldn't be read: kept (fail open to two rows, never hide on a guess).
        assertTrue(GroupRules.successors(listOf(old, rejoined)) { null }.isEmpty())
        assertTrue(GroupRules.successors(listOf(old, rejoined)) { if (it.groupId == 3L) emptySet() else setOf("QUxQSEE=") }.isEmpty())
        // A different name: member lists are never asked for.
        assertTrue(GroupRules.successors(listOf(old, listedGroup(9, "Other", "connected"))) { error("not asked") }.isEmpty())
    }

    @Test
    fun onlyANewerJoinedRowReplacesAnEndedOne() {
        val link = "simplex:/contact#/?v=2-7&smp=x"
        val ids: (CoreGroup) -> Set<String>? = { setOf("QUxQSEE=") }
        // A pending invitation doesn't replace the old row (declining it must leave the old one).
        assertTrue(GroupRules.successors(listOf(listedGroup(3, "T", "removed", link), listedGroup(9, "T", "invited", link)), ids).isEmpty())
        // A deleted group can't be joined again, so it is never replaced.
        assertTrue(GroupRules.successors(listOf(listedGroup(3, "T", "deleted", link), listedGroup(9, "T", "connected", link)), ids).isEmpty())
        // Only a newer row (higher groupId) replaces; live rows are never replaced.
        assertTrue(GroupRules.successors(listOf(listedGroup(9, "T", "removed", link), listedGroup(3, "T", "connected", link)), ids).isEmpty())
        assertTrue(GroupRules.successors(listOf(listedGroup(3, "T", "connected", link), listedGroup(9, "T", "connected", link)), ids).isEmpty())
        // No ended rows: nothing to do and nothing asked.
        assertTrue(GroupRules.successors(listOf(listedGroup(3, "T", "connected"), listedGroup(9, "T", "creator"))) { error("member lists not needed") }.isEmpty())
        // Removed twice, now back: both old rows go, the newest stays. Removed twice, not back: only the newest ended row stays.
        val r1 = listedGroup(3, "T", "removed", link)
        val r2 = listedGroup(5, "T", "left", link)
        assertEquals(mapOf(3L to 5L, 5L to 9L), GroupRules.successors(listOf(r1, r2, listedGroup(9, "T", "connected", link)), ids))
        assertEquals(mapOf(3L to 5L), GroupRules.successors(listOf(r1, r2), ids))
        // The owner deleted it after the rejoin: the old removed row still gives way to the "deleted" one.
        assertEquals(mapOf(3L to 9L), GroupRules.successors(listOf(r1, listedGroup(9, "T", "deleted", link)), ids))
        // A newer group that only shares the name is passed over for the row that shares members.
        val invitedOnce = listedGroup(3, "T", "removed")
        assertEquals(mapOf(3L to 8L), GroupRules.successors(listOf(invitedOnce, listedGroup(7, "T", "connected"), listedGroup(8, "T", "connected"))) { g ->
            if (g.groupId == 7L) setOf("WkFL") else setOf("QUxQSEE=")
        })
    }
}
