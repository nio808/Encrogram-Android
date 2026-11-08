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
    ) = """
        {"groupMemberId":$id,"groupId":3,"memberId":"bWVtYmVy","memberRole":"$role","memberCategory":"invitee",
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
            Case("groupRcv", rcvEvent("""{"type":"memberAdded","groupMemberId":9,${profile("Dan")}}"""), carol, GroupEvent(GroupEvent.Kind.INVITED, "Dan")),
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
            Case("groupRcv", rcvEvent("""{"type":"invitedViaGroupLink"}"""), carol, GroupEvent(GroupEvent.Kind.INVITED, "Carol")),
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
}
