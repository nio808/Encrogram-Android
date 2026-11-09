package app.encrogram.core.internal

import app.encrogram.core.api.SAVED_MESSAGES_CHAT_ID
import app.encrogram.core.api.isGroupChatId
import app.encrogram.core.backup.RepairKey
import org.json.JSONObject

/**
 * b1: contacts and group members whose inbox (or any other queue of our connection with them) is on an SMP relay we
 * don't run. Sending to them is paused, fail closed (research/foreign-relays.md). Pure rules; the engine keeps the
 * marks in one [ArchiveStore] file and runs the checks.
 *
 * A mark is a Long: a contact's id, or [CoreJson.linkJoinKey] for a member (groupId in the high 32 bits, so the two
 * kinds never meet: contact ids stay below 2^31).
 */
internal object ForeignRelays {

    /** The stored mark for a contact or member connection; null for a group placeholder or an id that doesn't fit. */
    fun mark(key: RepairKey): Long? = when {
        key.isMember -> CoreJson.linkJoinKey(CoreJson.groupChatId(key.groupId), key.memberId)
        key.isGroupPlaceholder -> null
        key.contactId in 1..Int.MAX_VALUE -> key.contactId
        else -> null
    }

    fun isMemberMark(mark: Long): Boolean = mark ushr 32 != 0L

    /** Some member of [groupId] is marked. */
    fun groupMarked(marks: Set<Long>, groupId: Long): Boolean = marks.any { isMemberMark(it) && CoreJson.linkJoinGroupId(it) == groupId }

    fun memberMarked(marks: Set<Long>, groupId: Long, memberId: Long): Boolean =
        CoreJson.linkJoinKey(CoreJson.groupChatId(groupId), memberId)?.let { it in marks } == true

    /** [chatId]'s row is affected: the contact, or any member of the group. Saved Messages never. */
    fun chatMarked(marks: Set<Long>, chatId: Long): Boolean = when {
        marks.isEmpty() || chatId == SAVED_MESSAGES_CHAT_ID -> false
        isGroupChatId(chatId) -> CoreJson.groupIdFromChatId(chatId)?.let { groupMarked(marks, it) } == true
        else -> chatId in marks
    }

    /** The sentence a refused send gets, or null when [chatId] isn't affected. */
    fun refusal(marks: Set<Long>, chatId: Long): String? = when {
        !chatMarked(marks, chatId) -> null
        isGroupChatId(chatId) -> ErrorMapper.FOREIGN_RELAY_GROUP
        else -> ErrorMapper.FOREIGN_RELAY_CONTACT
    }

    /**
     * What an `/_info @id` / `/_info #g <memberId>` reply says: true some queue is on a relay we don't run; false none
     * is (every queue ours, no active connection, or the connection is gone for good, [gone]); null when the reply
     * can't tell (another error, a garbled reply): the mark stays as it was.
     */
    fun verdict(reply: CoreReply, ours: List<ServerAddress>, gone: Set<String>): Boolean? = when (reply) {
        is CoreReply.Result ->
            if (reply.type == "contactInfo" || reply.type == "groupMemberInfo") CoreJson.statsOf(reply.body)?.let { CoreJson.foreignQueues(it, ours) } ?: false
            else null
        is CoreReply.Error -> if (reply.error.detail in gone) false else null
        CoreReply.Invalid -> null
    }

    /** A `contactSwitch` / `groupMemberSwitch` event's own stats (the new queue is in them from the first step); null without stats. */
    fun switchVerdict(body: JSONObject, ours: List<ServerAddress>): Boolean? =
        body.optJSONObject("switchProgress")?.optJSONObject("connectionStats")?.let { CoreJson.foreignQueues(it, ours) }

    /** Marks worth keeping after a chat list load: listed contacts, and members of groups we're still in. */
    fun keep(mark: Long, contacts: Set<Long>, groupIds: Set<Long>): Boolean =
        if (isMemberMark(mark)) CoreJson.linkJoinGroupId(mark) in groupIds else mark in contacts

    /**
     * The connection a connection event names, to check: a contact that connected or can now send, a member we
     * connected to (or who joined through us), the host of a group we joined. Null for anything else.
     */
    fun connectionOf(type: String, body: JSONObject): RepairKey? {
        fun member(field: String): RepairKey? {
            val groupId = body.optJSONObject("groupInfo")?.optLong("groupId", 0L) ?: 0L
            val memberId = body.optJSONObject(field)?.optLong("groupMemberId", 0L) ?: 0L
            return if (groupId > 0 && memberId > 0) RepairKey.member(groupId, memberId) else null
        }
        return when (type) {
            "contactConnected", "contactSndReady" ->
                body.optJSONObject("contact")?.optLong("contactId", 0L)?.takeIf { it > 0 }?.let { RepairKey.contact(it) }
            "connectedToGroupMember", "joinedGroupMember" -> member("member")
            "userJoinedGroup" -> member("hostMember")
            else -> null
        }
    }

    /** A member who left or was removed (by someone else): their mark goes. */
    fun departedOf(type: String, body: JSONObject): RepairKey? {
        val field = when (type) {
            "deletedMember" -> "deletedMember"
            "leftMember" -> "member"
            else -> return null
        }
        val groupId = body.optJSONObject("groupInfo")?.optLong("groupId", 0L) ?: 0L
        val memberId = body.optJSONObject(field)?.optLong("groupMemberId", 0L) ?: 0L
        return if (groupId > 0 && memberId > 0) RepairKey.member(groupId, memberId) else null
    }
}
