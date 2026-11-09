package app.encrogram.core.internal

import app.encrogram.core.api.GROUP_CHAT_ID_BASE
import app.encrogram.core.api.GroupEvent
import app.encrogram.core.api.MemberRole
import app.encrogram.core.api.MemberStatus
import app.encrogram.core.api.MessageStatus
import app.encrogram.core.api.ReactionCount
import app.encrogram.core.api.SAVED_MESSAGES_CHAT_ID
import app.encrogram.core.api.SECURED_REACTIONS
import app.encrogram.core.api.isGroupChatId
import app.encrogram.core.backup.RestoreRepair
import app.encrogram.core.internal.media.CoreFile
import app.encrogram.core.internal.media.FileStatusMapper
import app.encrogram.core.internal.media.MediaJson
import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * The small JSON layer over the SimpleX core API (chat core 7.0.0.12; shapes from bots/api/TYPES.md).
 *
 * It reads only the fields Encrogram needs and ignores everything else, so new fields in the engine's JSON do
 * not break it. Sum types are tagged objects with a "type" field (Android encoding); a few readers also accept
 * a bare string tag for robustness.
 */

/** One reply from `chat_send_cmd_retry` or one event from `chat_recv_msg_wait`. */
internal sealed class CoreReply {
    /** `{"result": {"type": ..., ...}}` */
    class Result(val type: String, val body: JSONObject) : CoreReply()

    /** `{"error": {...}}` (or a `chatCmdError`/`chatError` result) */
    class Error(val error: CoreError) : CoreReply()

    /** Not JSON, or a shape we do not know. */
    object Invalid : CoreReply()
}

/**
 * A ChatError reduced to its type tags, e.g. type="errorAgent", detail="SMP", subDetail="AUTH".
 * Only tags are kept: never the engine's free-text fields.
 */
internal data class CoreError(val type: String, val detail: String?, val subDetail: String?) {
    /** "errorAgent.SMP.AUTH", safe for logs and error messages. */
    val tag: String get() = listOfNotNull(type, detail, subDetail).joinToString(".")
}

internal data class CoreUser(val userId: Long, val displayName: String)

internal data class CoreContact(
    val contactId: Long,
    val displayName: String,
    val ready: Boolean,
    val verified: Boolean,
    /** A preset contact card (e.g. the upstream team card): no connection, only a link. Hidden from the list. */
    val isContactCard: Boolean,
    val removed: Boolean,
    val activityTimeMs: Long,
    /** chatSettings.favorite: backs "pinned". */
    val favorite: Boolean = false,
    /** chatSettings.enableNtfs is not "all": backs "muted". */
    val muted: Boolean = false,
    /** mergedPreferences.timedMessages enabled for both sides. */
    val timedEnabled: Boolean = false,
    /** Our own disappearing-message timer for this contact, when [timedEnabled]; else null. */
    val timedTtlSeconds: Int? = null,
)

internal data class CoreItem(
    val chatId: Long,
    val itemId: Long,
    val outgoing: Boolean,
    /** True for real messages (sndMsgContent/rcvMsgContent); false for service items. */
    val isMessage: Boolean,
    val text: String,
    val timeMs: Long,
    val statusTag: String?,
    val status: MessageStatus,
    val unread: Boolean,
    /** msgContent.type, e.g. "text"; null for service items. */
    val msgContentType: String? = null,
    val quote: CoreQuote? = null,
    val edited: Boolean = false,
    /** meta.itemDeleted is set (or a legacy deleted item). [text] is then always empty. */
    val deleted: Boolean = false,
    /** meta.editable: our own, under 24 h, not forwarded (Messages.hs mkCIMeta). */
    val editable: Boolean = false,
    /** meta.deletable: our own, under 24 h. */
    val deletable: Boolean = false,
    /** meta.itemSharedMsgId is set: without it an item can't be edited, deleted for everyone or reacted to. */
    val shared: Boolean = false,
    val forwarded: Boolean = false,
    val reactions: List<ReactionCount> = emptyList(),
    val expiresAtMs: Long? = null,
    /**
     * A note in the local note folder (chatDir localSnd/localRcv, or read from Saved Messages). Always [outgoing],
     * status DELIVERED, never unread: it was stored on this phone and never sent.
     */
    val local: Boolean = false,
    /** Groups: the member who sent an incoming item (chatDir groupRcv); null for our own items, 1:1 and notes. */
    val sender: CoreSender? = null,
    /** A service row we show ("X joined", "deleted by admin"); null for messages and for service items we skip. */
    val event: GroupEvent? = null,
    /**
     * An `invitedViaGroupLink` row (never shown): the groupMemberId of who came in through our group link. It marks
     * their later join row as a link join ([CoreJson.parseChatItemsOfChat]).
     */
    val linkJoinOf: Long? = null,
    /**
     * A `memberConnected` row: the groupMemberId of its sender, whose connection came up. Only such a row can become
     * "joined via invite link" ([CoreJson.markLinkJoins]); rows about someone else (memberAdded, memberAccepted name
     * the new member but come from the member who let them in) never can.
     */
    val connectedOf: Long? = null,
    /**
     * Our own message from an earlier membership of this group, in the history the group sent us when we joined again
     * ([GroupRules.markOwnHistory]). The core holds it as a received item from a member it doesn't know, so it is
     * shown as ours ([outgoing], one tick, never unread) but can't be edited or deleted for everyone. [statusTag] stays
     * the core's own (rcvNew until it is marked read there).
     */
    val earlierOwn: Boolean = false,
    /**
     * meta.itemSharedMsgId: the message's id shared by every copy of it (the sender's item, each member's, and a
     * history copy, Library/Internal.hs:1428). Only compared, never logged.
     */
    val sharedMsgId: String? = null,
    /** The item's file (CIFile), for image, video, voice and file content; null otherwise and when deleted (media phase 1). */
    val file: CoreFile? = null,
    /** msgContent.image: the preview data URL that travelled in the message (image and video), untrusted. Never logged. */
    val preview: String? = null,
    /** msgContent.duration in whole seconds (video and voice). */
    val durationSeconds: Int? = null,
    /**
     * meta.forwardedByMember is set: another member passed this group message on to us (x.grp.msg.forward). That is
     * how a join's history arrives, and also how live messages from members not yet connected to us arrive.
     */
    val relayed: Boolean = false,
    /**
     * Part of the history the group sent when we joined (G11, [GroupRules.beforeJoin]): relayed and sent before our
     * membership began. Never [unread], never notifies; shown like any other message. [statusTag] stays the core's
     * (rcvNew until the engine marks it read there).
     */
    val history: Boolean = false,
    /**
     * ChatItem.mentions {name: CIMention {memberId, memberRef?}} (Messages.hs:272-285): each name in the text -> the
     * mentioned member's groupMemberId on this phone, when the core knows them. Names are user content: never logged.
     */
    val mentions: Map<String, Long> = emptyMap(),
    /** meta.userMention: someone else's group message that mentions us or replies to one of our messages (Messages.hs:521). */
    val mentionsMe: Boolean = false,
) {
    /** In a group chat ([GROUP_CHAT_ID_BASE] + groupId). */
    val group: Boolean get() = isGroupChatId(chatId)

    /** Shown in a chat: a message or a service row we know. Every other service item is skipped, never shown raw. */
    val shown: Boolean get() = isMessage || event != null

    /** Ids and status only: text, quote and preview are user content (MD9). */
    override fun toString() = "CoreItem($chatId/$itemId, $statusTag)"
}

/**
 * chatDir groupRcv's member (Messages.hs:308-313): the core's groupMemberId, the name we show and the current role.
 * [groupWideId]: the member's group-wide memberId (GroupMember.memberId, Types.hs:1118), the same in every member's
 * copy of the group; for a history item it is the author's id as the sender of the history forwarded it.
 */
internal data class CoreSender(val memberId: Long, val displayName: String, val role: MemberRole, val groupWideId: String? = null)

/**
 * A group as the chat list needs it (GroupInfo, Types.hs:477-503). [myStatus] is membership.memberStatus as the core
 * writes it (Types.hs:1460-1493: "invited", "accepted", "connected", "complete", "creator", "left", ...).
 */
internal data class CoreGroup(
    val groupId: Long,
    val displayName: String,
    val myRole: MemberRole,
    val myStatus: String,
    /** groupSummary.currentMembers: current members including us (Store/Groups.hs:450 starts it at 1). */
    val memberCount: Int,
    val favorite: Boolean = false,
    val muted: Boolean = false,
    /** Our channel marker ([CoreJson.isChannel]). */
    val channel: Boolean = false,
    val activityTimeMs: Long = 0,
    /** groupProfile.displayName as the group's owner set it (never our local alias): one hint that two rows are one group. */
    val profileName: String = "",
    /**
     * SHA-256 of the link we joined by (viaGroupLinkUri, else preparedGroup's full link), from its `#` part so the
     * scheme and host don't matter; null when we didn't join by link. The link itself is never kept.
     */
    val linkKey: String? = null,
    /**
     * membership.memberId: our own group-wide memberId in this row (random per membership, Types.hs:1118). A rejoin
     * gets a new one, so the history it receives carries this row's id on our earlier messages ([GroupRules.markOwnHistory]).
     */
    val myMemberId: String? = null,
    /** membership.createdAt: when this membership began on this phone (invitation received or link join accepted), 0 if unknown. */
    val joinedAtMs: Long = 0,
    /** membership.invitedBy `{"type":"contact","byContactId":N}` (Types.hs:1268): the contact whose invitation this is, else null. */
    val invitedByContactId: Long? = null,
) {
    val chatId: Long get() = GROUP_CHAT_ID_BASE + groupId
    val invited: Boolean get() = myStatus == "invited"

    /** Our membership is over (Types.hs:1442-1447 memberRemoved): we can't leave, post or be repaired any more. */
    val gone: Boolean get() = myStatus in GONE_STATUSES

    /**
     * The owner deleted the group (membership GSMemGroupDeleted, written by xGrpDel, Subscriber.hs:3717-3727): nobody
     * is a member any more. The core's member count still counts the others (their rows keep their status).
     */
    val deleted: Boolean get() = myStatus == "deleted"

    companion object {
        private val GONE_STATUSES = setOf("left", "removed", "rejected", "deleted")
    }
}

internal data class CoreGroupChat(val group: CoreGroup, val lastItem: CoreItem?, val unreadCount: Int)

/** One entry of `/_members` (GroupMember, Types.hs:1114-1148), or our own membership ([isMe]). */
internal data class CoreMember(
    val groupMemberId: Long,
    val displayName: String,
    val role: MemberRole,
    val status: MemberStatus,
    val isMe: Boolean,
    /** memberContactId: the contact this member is linked to on our side, if any (never set for [isMe]). */
    val memberContactId: Long?,
    /** memberStatus connected or complete: the member's own connection works (the restore repair needs it). */
    val active: Boolean = false,
    /** memberStatus pending_approval or pending_review (knocking): the core refuses role changes for them (Commands.hs:2886). */
    val pending: Boolean = false,
)

/**
 * A group's link (GroupLink, Types.hs:569-576): `connLinkContact` {connFullLink, connShortLink?} and the role people
 * join with (acceptMemberRole).
 */
internal data class CoreGroupLink(val fullLink: String?, val shortLink: String?, val role: MemberRole)

/** SimpleX's local note folder (ChatInfo `local`, NoteFolder in Types.hs): Saved Messages. */
internal data class CoreNoteFolder(val noteFolderId: Long, val favorite: Boolean, val chatTsMs: Long)

/** The note folder's row in `apiChats`, with its newest item. */
internal data class CoreNoteChat(val folder: CoreNoteFolder, val lastItem: CoreItem?)

/**
 * chatItem.quotedItem: [itemId] is null when the quoted message is not stored on this device. [authorId]: the quoted
 * group member's group-wide memberId (chatDir groupRcv), else null. [sharedMsgId]: the quoted message's shared id
 * (CIQuote.sharedMsgId, Messages.hs:611), when known.
 */
internal data class CoreQuote(
    val itemId: Long?,
    val text: String,
    val outgoing: Boolean,
    val authorId: String? = null,
    val sharedMsgId: String? = null,
    /** The quoted message's content type tag (text, image, video, voice, file, link...), when known. */
    val msgContentType: String? = null,
)

/** One entry of chatItemsDeleted: [replacement] is the item as it is now when it was only marked deleted. */
internal data class CoreDeletion(val chatId: Long, val itemId: Long, val replacement: CoreItem?)

internal data class CoreChat(val contact: CoreContact, val lastItem: CoreItem?, val unreadCount: Int)

internal data class CreatedLink(val fullLink: String?, val shortLink: String?, val pendingConnId: Long?)

internal object CoreJson {

    fun parseReply(raw: String?): CoreReply {
        if (raw.isNullOrEmpty()) return CoreReply.Invalid
        val obj = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            return CoreReply.Invalid
        }
        val result = obj.optJSONObject("result") ?: obj.optJSONObject("resp")
        if (result != null) {
            val type = result.str("type").orEmpty()
            if (type.isEmpty()) return CoreReply.Invalid
            if (type == "chatCmdError" || type == "chatError") {
                return CoreReply.Error(parseError(result.optJSONObject("chatError")))
            }
            return CoreReply.Result(type, result)
        }
        val error = obj.optJSONObject("error")
        if (error != null) return CoreReply.Error(parseError(error))
        return CoreReply.Invalid
    }

    fun parseError(e: JSONObject?): CoreError {
        if (e == null) return CoreError("unknown", null, null)
        val type = e.str("type") ?: "unknown"
        return when (type) {
            "error" -> {
                val errorType = e.optJSONObject("errorType")
                CoreError(type, tagOf(errorType), commandErrorTag(errorType))
            }
            "errorAgent" -> {
                val agentError = e.optJSONObject("agentError")
                CoreError(type, tagOf(agentError), agentSubTag(agentError))
            }
            "errorStore" -> CoreError(type, tagOf(e.opt("storeError")), null)
            "errorDatabase" -> CoreError(type, tagOf(e.opt("databaseError")), null)
            "errorRemoteHost", "errorRemoteCtrl" -> CoreError(type, null, null)
            else -> CoreError(type, null, null)
        }
    }

    /**
     * `commandError` carries free text; only these fixed core strings (Library/Commands.hs, checkReactionAllowed) become
     * tags, so nothing else from the message can reach logs or the UI.
     */
    private val COMMAND_ERROR_TAGS = mapOf(
        "reaction already added" to "reactionAlreadyAdded",
        "reaction already removed" to "reactionAlreadyRemoved",
        "too many reactions" to "tooManyReactions",
    )

    private fun commandErrorTag(errorType: JSONObject?): String? =
        if (errorType?.str("type") == "commandError") COMMAND_ERROR_TAGS[errorType.str("message")] else null

    private val AGENT_SUB_KEYS = listOf(
        "smpErr", "brokerErr", "proxyErr", "connErr", "cmdErr", "agentErr", "xftpErr", "ntfErr", "fileErr", "rcpErr",
    )

    private fun agentSubTag(agentError: JSONObject?): String? {
        if (agentError == null) return null
        for (k in AGENT_SUB_KEYS) {
            val v = agentError.opt(k) ?: continue
            return tagOf(v)
        }
        return null
    }

    /** The "type" of a tagged object, or the string itself for enum-like values. */
    fun tagOf(v: Any?): String? = when (v) {
        is JSONObject -> v.str("type")?.ifEmpty { null }
        is String -> v.ifEmpty { null }
        else -> null
    }

    /** DBMigrationResult: `{"type":"ok"}`, `{"type":"errorNotADatabase","dbFile":..}`, ... */
    fun migrationResultTag(raw: String?): String {
        if (raw.isNullOrEmpty()) return "empty"
        return try {
            JSONObject(raw).str("type") ?: "unknown"
        } catch (e: JSONException) {
            "invalid"
        }
    }

    fun parseUser(user: JSONObject?): CoreUser? {
        if (user == null || !user.has("userId")) return null
        val name = user.optJSONObject("profile")?.str("displayName")?.ifEmpty { null }
            ?: user.str("localDisplayName").orEmpty()
        return CoreUser(user.optLong("userId"), name)
    }

    fun parseContact(c: JSONObject?): CoreContact? {
        if (c == null || !c.has("contactId")) return null
        val profile = c.optJSONObject("profile")
        val alias = profile?.str("localAlias").orEmpty()
        val name = alias.ifEmpty { profile?.str("displayName").orEmpty() }
            .ifEmpty { c.str("localDisplayName").orEmpty() }
        val activeConn = c.optJSONObject("activeConn")
        val connStatus = tagOf(activeConn?.opt("connStatus"))
        val ready = connStatus == "ready" || connStatus == "sndReady"
        val verified = activeConn != null && activeConn.has("connectionCode") && !activeConn.isNull("connectionCode")
        val hasContactLink = profile != null && profile.has("contactLink") && !profile.isNull("contactLink")
        val isCard = (activeConn == null || connStatus == "prepared") && hasContactLink &&
            isNullOrAbsent(c, "preparedContact") && isNullOrAbsent(c, "contactRequestId")
        val status = tagOf(c.opt("contactStatus")) ?: "active"
        val removed = status != "active" || c.optBoolean("chatDeleted", false)
        val ts = timeMs(c.str("chatTs")).takeIf { it > 0 }
            ?: timeMs(c.str("updatedAt")).takeIf { it > 0 }
            ?: timeMs(c.str("createdAt"))
        val settings = c.optJSONObject("chatSettings")
        val ntfs = tagOf(settings?.opt("enableNtfs"))
        // ContactUserPreference {enabled {forUser, forContact}, userPreference {type, preference {allow, ttl?}}}
        val timed = c.optJSONObject("mergedPreferences")?.optJSONObject("timedMessages")
        val enabled = timed?.optJSONObject("enabled")
        val timedEnabled = enabled != null && enabled.optBoolean("forUser", false) && enabled.optBoolean("forContact", false)
        val ourTimed = timed?.optJSONObject("userPreference")?.optJSONObject("preference")
        val ttl = if (timedEnabled && ourTimed != null && ourTimed.has("ttl") && !ourTimed.isNull("ttl")) {
            ourTimed.optInt("ttl", 0).takeIf { it > 0 }
        } else {
            null
        }
        return CoreContact(
            contactId = c.optLong("contactId"),
            displayName = name,
            ready = ready,
            verified = verified,
            isContactCard = isCard,
            removed = removed,
            activityTimeMs = ts,
            favorite = settings?.optBoolean("favorite", false) ?: false,
            muted = ntfs != null && ntfs != "all",
            timedEnabled = timedEnabled,
            timedTtlSeconds = ttl,
        )
    }

    /** The raw contact of an `apiChat` body (for read-modify-write of its settings and preferences). */
    fun contactOfChat(body: JSONObject): JSONObject? {
        val info = body.optJSONObject("chat")?.optJSONObject("chatInfo") ?: return null
        if (tagOf(info) != "direct") return null
        return info.optJSONObject("contact")
    }

    /** The raw GroupInfo of a group's `apiChat` body (for read-modify-write of its ChatSettings). */
    fun groupInfoOfChat(body: JSONObject): JSONObject? = groupInfoOf(body.optJSONObject("chat")?.optJSONObject("chatInfo"))

    /**
     * The contact's whole ChatSettings {enableNtfs, sendRcpts?, favorite} with [edit] applied, for `/_settings`.
     * The core replaces all of it, so every field we don't change is copied from the current one.
     */
    fun chatSettingsUpdate(contact: JSONObject, edit: (JSONObject) -> Unit): JSONObject {
        val s = contact.optJSONObject("chatSettings")?.deepCopy() ?: JSONObject()
        if (!s.has("enableNtfs") || s.isNull("enableNtfs")) s.put("enableNtfs", "all")
        if (!s.has("favorite") || s.isNull("favorite")) s.put("favorite", false)
        edit(s)
        return s
    }

    /**
     * The contact's own Preferences (`userPreferences`) with the disappearing-message timer set, for
     * `/_set prefs`. On: `{"allow":"yes","ttl":n}`; off: `{"allow":"yes"}` (the contact may still use its own).
     * Every other feature is copied, since the core replaces the whole object.
     */
    fun contactPrefsUpdate(contact: JSONObject, ttlSeconds: Int?): JSONObject {
        val prefs = contact.optJSONObject("userPreferences")?.deepCopy() ?: JSONObject()
        val timed = JSONObject().put("allow", "yes")
        if (ttlSeconds != null) timed.put("ttl", ttlSeconds)
        prefs.put("timedMessages", timed)
        return prefs
    }

    /** The preferences every Secured profile has (simplex-chat-api-for-ui.md §5.2). */
    val SECURED_PROFILE_PREFS: Map<String, String> = linkedMapOf("fullDelete" to "yes", "calls" to "no")

    /** LocalProfile fields that are not part of Profile (Types.hs): never sent back in `/_profile`. */
    private val LOCAL_ONLY_PROFILE_KEYS = listOf("profileId", "localAlias", "localBadge", "contactDomainVerified")

    /**
     * The Profile to send with `/_profile`, from the user's current LocalProfile (`/u` -> user.profile), with
     * [displayName] (when given) and [SECURED_PROFILE_PREFS] applied. The core replaces the whole profile
     * (gotcha G4), so every other field is kept as it is. Null when nothing would change.
     */
    fun profileUpdate(localProfile: JSONObject, displayName: String? = null): JSONObject? {
        val p = localProfile.deepCopy()
        for (k in LOCAL_ONLY_PROFILE_KEYS) p.remove(k)
        var changed = false
        if (displayName != null && p.str("displayName") != displayName) {
            p.put("displayName", displayName)
            changed = true
        }
        if (!p.has("fullName") || p.isNull("fullName")) p.put("fullName", "")
        val prefs = p.optJSONObject("preferences") ?: JSONObject()
        for ((feature, allow) in SECURED_PROFILE_PREFS) {
            val current = prefs.optJSONObject(feature)
            if (current?.str("allow") != allow) {
                prefs.put(feature, (current ?: JSONObject()).put("allow", allow))
                changed = true
            }
        }
        p.put("preferences", prefs)
        return if (changed) p else null
    }

    /** The contact of a direct ChatInfo, or null for groups, notes, requests and pending connections. */
    fun directContact(chatInfo: JSONObject?): CoreContact? {
        if (chatInfo == null || tagOf(chatInfo) != "direct") return null
        return parseContact(chatInfo.optJSONObject("contact"))
    }

    /** The note folder of a `local` ChatInfo `{type: "local", noteFolder: {noteFolderId, favorite, chatTs, ...}}`, else null. */
    fun noteFolder(chatInfo: JSONObject?): CoreNoteFolder? {
        if (chatInfo == null || tagOf(chatInfo) != "local") return null
        val nf = chatInfo.optJSONObject("noteFolder") ?: return null
        if (!present(nf, "noteFolderId")) return null
        val id = nf.optLong("noteFolderId", -1L)
        if (id <= 0) return null
        val ts = timeMs(nf.str("chatTs")).takeIf { it > 0 } ?: timeMs(nf.str("updatedAt")).takeIf { it > 0 } ?: timeMs(nf.str("createdAt"))
        return CoreNoteFolder(id, nf.optBoolean("favorite", false), ts)
    }

    /**
     * Our chat id for a ChatInfo: the contact id for a direct chat, [SAVED_MESSAGES_CHAT_ID] for the note folder,
     * [GROUP_CHAT_ID_BASE] + groupId for a group ([groupIdOf]), null for everything else (requests, pending
     * connections, member-support scopes, relay channels).
     */
    fun chatIdOf(chatInfo: JSONObject?): Long? =
        directContact(chatInfo)?.contactId ?: noteFolder(chatInfo)?.let { SAVED_MESSAGES_CHAT_ID }
            ?: groupIdOf(chatInfo)?.let { groupChatId(it) }

    fun groupChatId(groupId: Long): Long = GROUP_CHAT_ID_BASE + groupId

    /** The core's groupId behind one of our group chat ids, or null for any other chat id. */
    fun groupIdFromChatId(chatId: Long): Long? = if (isGroupChatId(chatId)) (chatId - GROUP_CHAT_ID_BASE).takeIf { it > 0 } else null

    /**
     * The groupId of a group ChatInfo `{type: "group", groupInfo, groupChatScope?}` (Messages.hs:204-206), else null.
     * A member-support scope (knocking, `groupChatScope` set) is not the group's main chat. A relay channel
     * (`useRelays`, Types.hs:479) is not something Encrogram creates or joins: never shown (fail closed).
     */
    fun groupIdOf(chatInfo: JSONObject?): Long? {
        if (chatInfo == null || tagOf(chatInfo) != "group") return null
        if (present(chatInfo, "groupChatScope")) return null
        val g = chatInfo.optJSONObject("groupInfo") ?: return null
        if (g.optBoolean("useRelays", false)) return null
        return g.optLong("groupId", 0L).takeIf { it > 0 }
    }

    /** The GroupInfo of a group ChatInfo that [groupIdOf] accepts. */
    fun groupInfoOf(chatInfo: JSONObject?): JSONObject? =
        if (groupIdOf(chatInfo) != null) chatInfo?.optJSONObject("groupInfo") else null

    /** The display name of a direct ChatInfo's contact (for "Bob was invited" in our chat with Bob), else null. */
    private fun peerNameOf(chatInfo: JSONObject?): String? = directContact(chatInfo)?.displayName?.ifEmpty { null }

    /** An AChatItem `{chatInfo, chatItem}` in a direct chat, a group or the note folder (chat id -1), else null. */
    fun parseAChatItem(aChatItem: JSONObject?): CoreItem? {
        if (aChatItem == null) return null
        val info = aChatItem.optJSONObject("chatInfo")
        val chatId = chatIdOf(info) ?: return null
        val group = groupInfoOf(info)
        return parseChatItem(chatId, aChatItem.optJSONObject("chatItem"), peerNameOf(info), joinedByLink(group), joinedAtMs(group))
    }

    /**
     * A `chatItemInfo` reply ([Commands.itemInfo]): the item as the core holds it now, and whether nobody was ever sent
     * it. ChatItemInfo.memberDeliveryStatuses (Messages.hs:1345-1350) is `L.nonEmpty` of a group item's member rows
     * (Commands.hs:691-693) and is left out when Nothing (defaultJSON omitNothingFields), so it is missing exactly
     * when the item has no member row. Any other shape counts as recipients. Null when the reply holds no item or info.
     */
    fun parseItemInfo(body: JSONObject): Pair<CoreItem, Boolean>? {
        val item = parseAChatItem(body.optJSONObject("chatItem")) ?: return null
        val info = body.optJSONObject("chatItemInfo") ?: return null
        val key = "memberDeliveryStatuses"
        return item to (info.isNull(key) || info.optJSONArray(key)?.length() == 0)
    }

    /** membership.createdAt of a GroupInfo (when our membership began on this phone), 0 when absent. */
    fun joinedAtMs(groupInfo: JSONObject?): Long = timeMs(groupInfo?.optJSONObject("membership")?.str("createdAt"))

    /**
     * We joined this group through its link: the GroupInfo keeps the link we used (viaGroupLinkUri, Types.hs:500, set by
     * Store/Shared.hs:909-937) or the group was prepared from it (preparedGroup, Types.hs:551-557). An invitation from a
     * contact sets neither. Only presence is read: the link itself is never kept.
     */
    fun joinedByLink(groupInfo: JSONObject?): Boolean =
        groupInfo != null && (present(groupInfo, "viaGroupLinkUri") || present(groupInfo, "preparedGroup"))

    /**
     * [peerName]: the contact's name in a direct chat, used only for our own sent invitation row. [joinedByLink]: we
     * joined this group by its link ([joinedByLink]), so our own join row reads "You joined via invite link".
     * [joinedAtMs]: the group's membership.createdAt ([joinedAtMs]), for [CoreItem.history]; 0 outside groups.
     */
    fun parseChatItem(chatId: Long, item: JSONObject?, peerName: String? = null, joinedByLink: Boolean = false, joinedAtMs: Long = 0L): CoreItem? {
        if (item == null) return null
        val meta = item.optJSONObject("meta") ?: return null
        if (!meta.has("itemId")) return null
        val dir = tagOf(item.opt("chatDir"))
        val content = item.optJSONObject("content")
        val contentType = tagOf(content)
        // Notes: stored here, never sent, so always ours and never "unread" (the core may tag them sndNew).
        val local = chatId == SAVED_MESSAGES_CHAT_ID || dir == "localSnd" || dir == "localRcv"
        val outgoing = when (dir) {
            "directSnd", "groupSnd", "localSnd" -> true
            "directRcv", "groupRcv", "channelRcv", "localRcv" -> local
            else -> local || contentType?.startsWith("snd") == true
        }
        val group = isGroupChatId(chatId)
        val senderJson = if (group && dir == "groupRcv") item.optJSONObject("chatDir")?.optJSONObject("groupMember") else null
        val sender = parseSender(senderJson)
        // Deleted by a moderator: a "moderated" placeholder (fullDelete on, Store/Messages.hs:2833-2850) or a marked
        // item (fullDelete off, itemDeleted {type: moderated}, Messages.hs:1291-1295). Shown as a service row only.
        val moderated = contentType == "rcvModerated" || contentType == "sndModerated" ||
            tagOf(meta.opt("itemDeleted")) == "moderated"
        // sndDeleted/rcvDeleted: legacy deleted items (before meta.itemDeleted existed). Shown as deleted messages.
        val legacyDeleted = contentType == "sndDeleted" || contentType == "rcvDeleted"
        val isMessage = !moderated && (contentType == "sndMsgContent" || contentType == "rcvMsgContent" || legacyDeleted)
        val deleted = moderated || legacyDeleted || present(meta, "itemDeleted")
        val event = if (moderated) GroupEvent(GroupEvent.Kind.DELETED_BY_ADMIN, sender?.displayName) else serviceEvent(content, contentType, sender, senderJson, joinedByLink, peerName)
        val rcvEventTag = if (contentType == "rcvGroupEvent") tagOf(content?.opt("rcvGroupEvent")) else null
        val linkJoinOf = if (rcvEventTag == "invitedViaGroupLink") sender?.memberId else null
        val connectedOf = if (rcvEventTag == "memberConnected") sender?.memberId else null
        val msgContent = content?.optJSONObject("msgContent")
        val msgText = msgContent?.str("text").orEmpty()
        // A deleted message never carries its old text: the core keeps it in the JSON when it only marks it.
        // A service row carries no text at all: the UI words it from [event].
        // Media content takes its caption from msgContent.text alone: meta.itemText holds names such as
        // "voice message 0:12" that would otherwise show up as a caption (Protocol.hs:787-790).
        val msgType = tagOf(msgContent)
        val mediaMsg = !deleted && isMessage && MediaJson.isMediaType(msgType)
        val text = if (deleted || !isMessage) "" else if (mediaMsg) msgText else msgText.ifEmpty { meta.str("itemText").orEmpty() }
        val file = if (mediaMsg) MediaJson.parseFile(item.optJSONObject("file"), msgContent) else null
        val status = meta.optJSONObject("itemStatus")
        val statusTag = tagOf(status)
        val timed = meta.optJSONObject("itemTimed")
        val expiresAt = timeMs(timed?.str("deleteAt")).takeIf { it > 0 }
        val itemTs = timeMs(meta.str("itemTs"))
        // Relayed by another member (Subscriber.hs:3895-3898 saveGroupFwdRcvMsg -> chat_items.forwarded_by_group_member_id).
        val relayed = group && dir == "groupRcv" && present(meta, "forwardedByMember")
        val history = relayed && isMessage && GroupRules.beforeJoin(itemTs, joinedAtMs)
        return CoreItem(
            chatId = chatId,
            itemId = meta.optLong("itemId"),
            outgoing = outgoing,
            isMessage = isMessage,
            text = text,
            timeMs = itemTs,
            statusTag = statusTag,
            status = if (local) MessageStatus.DELIVERED else
                FileStatusMapper.cappedStatus(StatusMapper.map(statusTag, outgoing, status?.str("sndProgress"), group), file, outgoing),
            unread = !local && !outgoing && statusTag == "rcvNew" && !history,
            msgContentType = tagOf(msgContent),
            quote = if (deleted) null else parseQuote(item.optJSONObject("quotedItem")),
            edited = meta.optBoolean("itemEdited", false),
            deleted = deleted,
            editable = !deleted && meta.optBoolean("editable", false),
            deletable = !deleted && meta.optBoolean("deletable", false),
            shared = meta.str("itemSharedMsgId") != null,
            forwarded = present(meta, "itemForwarded"),
            reactions = if (deleted || local || !isMessage) emptyList() else parseReactions(item),
            expiresAtMs = expiresAt,
            local = local,
            sender = sender,
            event = event,
            linkJoinOf = linkJoinOf,
            connectedOf = connectedOf,
            sharedMsgId = meta.str("itemSharedMsgId")?.ifEmpty { null },
            file = file,
            preview = if (mediaMsg && (msgType == "image" || msgType == "video")) MediaJson.preview(msgContent) else null,
            durationSeconds = if (mediaMsg && (msgType == "video" || msgType == "voice")) MediaJson.duration(msgContent) else null,
            relayed = relayed,
            history = history,
            mentions = if (group && isMessage && !deleted) parseMentions(item.optJSONObject("mentions")) else emptyMap(),
            mentionsMe = group && isMessage && !outgoing && !deleted && meta.optBoolean("userMention", false),
        )
    }

    /** ChatItem.mentions -> name -> memberRef.groupMemberId, for members known on this phone. */
    private fun parseMentions(m: JSONObject?): Map<String, Long> {
        if (m == null || m.length() == 0) return emptyMap()
        val out = LinkedHashMap<String, Long>()
        for (name in m.keys()) {
            val id = m.optJSONObject(name)?.optJSONObject("memberRef")?.optLong("groupMemberId", 0L) ?: 0L
            if (name.isNotEmpty() && id > 0) out[name] = id
        }
        return out
    }

    /** The core's order of a chat's items (Store/Messages.hs: item_ts, then chat_item_id). */
    val ITEM_ORDER: Comparator<CoreItem> = compareBy<CoreItem>({ it.timeMs }, { it.itemId })

    /** The newest item a chat-list row can preview: a message or a service row we show; null when there is none. */
    fun newestShown(items: List<CoreItem>): CoreItem? = items.filter { it.shown }.maxWithOrNull(ITEM_ORDER)

    // ---- groups ----------------------------------------------------------------------------------------------------

    /**
     * The service rows we show, from CIContent's API JSON (Messages/CIContent.hs:479-511; events in
     * Messages/CIContent/Events.hs:15-47, tagged by constructor minus RGE/SGE). Anything else (feature items, the
     * chat banner, E2EE info of a member, profile updates, blocks, signature warnings) is null and the row is skipped.
     * A row about a member always names them; without a name it is skipped rather than worded as if it were about us.
     */
    private fun serviceEvent(
        content: JSONObject?,
        contentType: String?,
        sender: CoreSender?,
        senderJson: JSONObject?,
        joinedByLink: Boolean,
        peerName: String?,
    ): GroupEvent? {
        if (content == null) return null
        return when (contentType) {
            "rcvGroupEvent" -> rcvGroupEvent(content.optJSONObject("rcvGroupEvent"), sender, senderJson, joinedByLink)
            "sndGroupEvent" -> sndGroupEvent(content.optJSONObject("sndGroupEvent"))
            // In the inviting contact's 1:1 chat: "You were invited to <group>" (CIRcvGroupInvitation, CIContent.hs:152).
            "rcvGroupInvitation" -> GroupEvent(GroupEvent.Kind.INVITED, groupName = invitedGroupName(content))
            // In our 1:1 chat with the person we invited: "Bob was invited to <group>".
            "sndGroupInvitation" -> peerName?.let { GroupEvent(GroupEvent.Kind.INVITED, it, groupName = invitedGroupName(content)) }
            // Created with every new group (Commands.hs:4182-4187 createNewGroupItems; CIContent.hs:172).
            "sndGroupE2EEInfo" -> GroupEvent(GroupEvent.Kind.CREATED)
            else -> null
        }
    }

    /**
     * [senderJson]: the sending GroupMember (its memberCategory is GroupMemberCategory, Types.hs:1321-1326).
     * [joinedByLink]: we joined this group through its link.
     *
     * Every join is one row, like Telegram's:
     * - The joiner: the host's `memberConnected` (category host) is "You joined", or "You joined the group via invite
     *   link" when we joined by link.
     * - The member who let them in: by link, the core makes `invitedViaGroupLink` when it accepts the request
     *   (Subscriber.hs:1626-1629) and `memberConnected` when the joiner's connection comes up (:950); only the second
     *   is shown, marked "joined the group via invite link" from the first ([markLinkJoins]).
     * - Everyone else: the host announces the new member (XGrpMemNew -> `memberAdded`, Subscriber.hs:3185-3189) once the
     *   joiner is already connected to the host (Internal.hs:1138-1159, called from the host's CON, Subscriber.hs:964-967),
     *   then the joiner's own connection to us comes up (`memberConnected`, category post, :979, :1841-1845). That pair is the
     *   same for a link join and for an admin's invitation (MemberInfo carries no marker, Types.hs:1043-1050; nothing is
     *   sent to the group when an admin invites, Commands.hs:2744-2764), so it is one plain "X joined": `memberAdded`
     *   as JOINED (by then X has joined; "was invited" was wrong for link joins), the later `memberConnected` hidden.
     */
    private fun rcvGroupEvent(e: JSONObject?, sender: CoreSender?, senderJson: JSONObject?, joinedByLink: Boolean): GroupEvent? {
        if (e == null) return null
        val about = profileName(e)
        return when (tagOf(e)) {
            // The host announcing someone who has just joined (only made by xGrpMemNew, Subscriber.hs:3187).
            "memberAdded" -> about?.let { GroupEvent(GroupEvent.Kind.JOINED, it) }
            // The member the item is from finished connecting ("X joined"), except when we are the one joining.
            "memberConnected" -> when (senderJson?.str("memberCategory")) {
                // The member who let us in (by invitation or link): their connection coming up is our own join
                // (Subscriber.hs:885-912 makes this item with userJoinedGroup), so "You joined", not "<host> joined".
                "host" -> GroupEvent(if (joinedByLink) GroupEvent.Kind.JOINED_BY_LINK else GroupEvent.Kind.JOINED)
                // Members who were there before us, connecting after our join (:1835-1845): they didn't just join.
                "pre" -> null
                // Someone a host introduced to us: their memberAdded row already said "X joined" (xGrpMemNew creates
                // post members with that row, Subscriber.hs:3167-3189), so no second row. Only if the host's
                // introduction overtook its announcement (xGrpMemFwd's fallback, :3259-3266, an error case per the
                // core) is there no memberAdded row; then this join has no row rather than two.
                "post" -> null
                // Someone we let in (invitee). Whether that was through our link is not in the member
                // (memberContactId changes with contact deletes and merges), only in their invitedViaGroupLink row:
                // [markLinkJoins] turns this into JOINED_BY_LINK from those rows.
                else -> sender?.let { GroupEvent(GroupEvent.Kind.JOINED, it.displayName) }
            }
            "memberAccepted" -> about?.let { GroupEvent(GroupEvent.Kind.JOINED, it) }
            "userAccepted" -> GroupEvent(GroupEvent.Kind.JOINED)
            "memberLeft" -> sender?.let { GroupEvent(GroupEvent.Kind.LEFT, it.displayName) }
            "memberRole" -> about?.let { GroupEvent(GroupEvent.Kind.ROLE_CHANGED, it, memberRole(e.str("role"))) }
            "userRole" -> GroupEvent(GroupEvent.Kind.ROLE_CHANGED, null, memberRole(e.str("role")))
            "memberDeleted" -> GroupEvent(GroupEvent.Kind.REMOVED, about)
            "userDeleted" -> GroupEvent(GroupEvent.Kind.YOU_REMOVED)
            "groupDeleted" -> GroupEvent(GroupEvent.Kind.GROUP_DELETED)
            "groupUpdated" -> GroupEvent(GroupEvent.Kind.GROUP_UPDATED)
            // Made on our side when we accept a join through our link (Events.hs:28-31): not shown, their memberConnected
            // row is the one "joined via invite link" row ([CoreItem.linkJoinOf] marks it).
            "invitedViaGroupLink" -> null
            else -> null
        }
    }

    private fun sndGroupEvent(e: JSONObject?): GroupEvent? {
        if (e == null) return null
        val about = profileName(e)
        return when (tagOf(e)) {
            "memberRole" -> about?.let { GroupEvent(GroupEvent.Kind.ROLE_CHANGED, it, memberRole(e.str("role"))) }
            "userRole" -> GroupEvent(GroupEvent.Kind.ROLE_CHANGED, null, memberRole(e.str("role")))
            "memberDeleted" -> GroupEvent(GroupEvent.Kind.REMOVED, about)
            "userLeft" -> GroupEvent(GroupEvent.Kind.LEFT)
            "groupUpdated" -> GroupEvent(GroupEvent.Kind.GROUP_UPDATED)
            "memberAccepted" -> about?.let { GroupEvent(GroupEvent.Kind.JOINED, it) }
            else -> null
        }
    }

    /**
     * The group an invitation row is about: `groupInvitation` (CIGroupInvitation, CIContent.hs:269-276; JSON
     * JCIRcvGroupInvitation/JCISndGroupInvitation {groupInvitation, memberRole}, :489-490) -> its profile's displayName,
     * else the core's local name for it. Null when neither is there.
     */
    private fun invitedGroupName(content: JSONObject): String? {
        val inv = content.optJSONObject("groupInvitation") ?: return null
        return inv.optJSONObject("groupProfile")?.str("displayName")?.ifEmpty { null } ?: inv.str("localDisplayName")?.ifEmpty { null }
    }

    /** `profile.displayName` of an event about a member (Profile, not LocalProfile: no alias). */
    private fun profileName(e: JSONObject): String? = e.optJSONObject("profile")?.str("displayName")?.ifEmpty { null }

    /** GroupMemberRole (Types/Shared.hs:19-28, text values :34-52). relay, author and newer roles map down, never up. */
    fun memberRole(s: String?): MemberRole = when (s) {
        "owner" -> MemberRole.OWNER
        "admin" -> MemberRole.ADMIN
        "moderator" -> MemberRole.MODERATOR
        "member", "author" -> MemberRole.MEMBER
        else -> MemberRole.OBSERVER
    }

    /** The core's role word for [role] (Commands.hs:5914-5921 memberRole parser). */
    fun roleWord(role: MemberRole): String = when (role) {
        MemberRole.OWNER -> "owner"
        MemberRole.ADMIN -> "admin"
        MemberRole.MODERATOR -> "moderator"
        MemberRole.MEMBER -> "member"
        MemberRole.OBSERVER -> "observer"
    }

    /**
     * GroupMemberStatus text (Types.hs:1460-1493) -> what we show. Null for `unknown` (a member known only from
     * forwarded history, not really in the group) and for statuses newer than this reader.
     */
    fun memberStatus(s: String?): MemberStatus? = when (s) {
        "invited" -> MemberStatus.INVITED
        "pending_approval", "pending_review", "introduced", "intro-inv", "accepted", "announced" -> MemberStatus.CONNECTING
        "connected", "complete", "creator" -> MemberStatus.CONNECTED
        "left" -> MemberStatus.LEFT
        "removed", "rejected", "deleted" -> MemberStatus.REMOVED
        else -> null
    }

    /** We can post: member or higher and an active member (Commands.hs:4063-4068 assertUserGroupRole, :783 GRAuthor). */
    fun canPost(g: CoreGroup): Boolean = g.myRole >= MemberRole.MEMBER && g.myStatus in POSTING_STATUSES

    /** Still connecting to the group: accepted an invitation or joined, not yet connected (memberActive, Types.hs:1391-1407). */
    fun groupConnecting(g: CoreGroup): Boolean = g.myStatus in CONNECTING_STATUSES

    private val POSTING_STATUSES = setOf("connected", "complete", "creator")
    private val CONNECTING_STATUSES = setOf("accepted", "announced", "introduced", "intro-inv", "pending_approval", "pending_review")

    /**
     * The group preference that marks an Encrogram channel: `comments` on. Upstream reserves `comments` for relay
     * channels; in core 7.0.0.12 nothing reads it (only Types/Preferences.hs defines it), no client shows it
     * (allGroupFeatures leaves it out, Preferences.hs:240-255, so the core makes no feature item for it), and it travels
     * with the group profile to every member (GroupInvitation.groupProfile, Commands.hs:4189-4200) and survives profile
     * edits by any client (FullGroupPreferences keeps it). Re-check at every core upgrade.
     */
    const val CHANNEL_MARKER_PREF = "comments"

    /** True when [groupInfo] carries our channel marker (the profile's own preferences first, then the merged ones). */
    fun isChannel(groupInfo: JSONObject): Boolean {
        val own = groupInfo.optJSONObject("groupProfile")?.optJSONObject("groupPreferences")?.optJSONObject(CHANNEL_MARKER_PREF)
        val pref = own ?: groupInfo.optJSONObject("fullGroupPreferences")?.optJSONObject(CHANNEL_MARKER_PREF)
        return pref?.str("enable") == "on"
    }

    /**
     * The GroupProfile for `/_group` (Types.hs:865-875): name, empty fullName (required), and GroupPreferences
     * (Preferences.hs:360-376, GroupFeatureEnabled "on"/"off", Preferences.hs:987-995): `history` on (new members get
     * up to 100 earlier messages, Library/Internal.hs:1316-1329), `fullDelete` on, and for a channel our marker
     * [CHANNEL_MARKER_PREF]. "Only admins post" itself is the role gate: channel members join as observers.
     */
    fun newGroupProfile(name: String, channel: Boolean): JSONObject {
        val prefs = JSONObject()
            .put("history", JSONObject().put("enable", "on"))
            .put("fullDelete", JSONObject().put("enable", "on"))
        if (channel) prefs.put(CHANNEL_MARKER_PREF, JSONObject().put("enable", "on"))
        return JSONObject().put("displayName", name).put("fullName", "").put("groupPreferences", prefs)
    }

    /** GroupInfo -> [CoreGroup]; null without a groupId or for a relay channel. */
    fun parseGroup(g: JSONObject?): CoreGroup? {
        if (g == null || g.optBoolean("useRelays", false)) return null
        val groupId = g.optLong("groupId", 0L).takeIf { it > 0 } ?: return null
        val profile = g.optJSONObject("groupProfile")
        val name = g.str("localAlias").orEmpty().ifEmpty { profile?.str("displayName").orEmpty() }
            .ifEmpty { g.str("localDisplayName").orEmpty() }
        val membership = g.optJSONObject("membership")
        val settings = g.optJSONObject("chatSettings")
        val ntfs = tagOf(settings?.opt("enableNtfs"))
        val ts = timeMs(g.str("chatTs")).takeIf { it > 0 }
            ?: timeMs(g.str("updatedAt")).takeIf { it > 0 }
            ?: timeMs(g.str("createdAt"))
        return CoreGroup(
            groupId = groupId,
            displayName = name,
            myRole = memberRole(membership?.str("memberRole")),
            myStatus = membership?.str("memberStatus").orEmpty(),
            memberCount = g.optJSONObject("groupSummary")?.optLong("currentMembers", 0L)?.coerceIn(0L, Int.MAX_VALUE.toLong())?.toInt() ?: 0,
            favorite = settings?.optBoolean("favorite", false) ?: false,
            muted = ntfs != null && ntfs != "all",
            channel = isChannel(g),
            activityTimeMs = ts,
            profileName = profile?.str("displayName").orEmpty(),
            linkKey = joinLinkKey(g),
            myMemberId = membership?.str("memberId")?.ifEmpty { null },
            joinedAtMs = timeMs(membership?.str("createdAt")),
            invitedByContactId = membership?.optJSONObject("invitedBy")?.takeIf { tagOf(it) == "contact" }
                ?.optLong("byContactId", 0L)?.takeIf { it > 0 },
        )
    }

    /**
     * [CoreGroup.linkKey]: the link we joined this group by, as SimpleX itself matches it (via_group_link_uri_hash over
     * both URI schemes, Store/Groups.hs:2888-2903, Commands.hs:4602-4606), so only the part after `#` counts.
     */
    fun joinLinkKey(groupInfo: JSONObject): String? {
        val uri = groupInfo.str("viaGroupLinkUri")?.ifEmpty { null }
            ?: groupInfo.optJSONObject("preparedGroup")?.optJSONObject("connLinkToConnect")?.str("connFullLink")?.ifEmpty { null }
            ?: return null
        val fragment = uri.substringAfter('#', "").ifEmpty { return null }
        val digest = MessageDigest.getInstance("SHA-256").digest(fragment.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** chatDir groupRcv's GroupMember -> the sender we show (with its group-wide memberId). */
    fun parseSender(m: JSONObject?): CoreSender? {
        if (m == null || !present(m, "groupMemberId")) return null
        return CoreSender(m.optLong("groupMemberId"), memberName(m), memberRole(m.str("memberRole")), m.str("memberId")?.ifEmpty { null })
    }

    /** A member's name as we show it: our local alias, else their profile name, else the core's local name. */
    private fun memberName(m: JSONObject): String {
        val p = m.optJSONObject("memberProfile")
        return p?.str("localAlias").orEmpty().ifEmpty { p?.str("displayName").orEmpty() }
            .ifEmpty { m.str("localDisplayName").orEmpty() }
    }

    /** One GroupMember; null for relays, `unknown` members and statuses newer than this reader. */
    fun parseMember(m: JSONObject?, isMe: Boolean): CoreMember? {
        if (m == null || !present(m, "groupMemberId")) return null
        val roleWord = m.str("memberRole")
        if (roleWord == "relay") return null
        val statusWord = m.str("memberStatus")
        val status = memberStatus(statusWord) ?: return null
        val contactId = if (!isMe && present(m, "memberContactId")) m.optLong("memberContactId").takeIf { it > 0 } else null
        return CoreMember(
            groupMemberId = m.optLong("groupMemberId"),
            displayName = memberName(m),
            role = memberRole(roleWord),
            status = status,
            isMe = isMe,
            memberContactId = contactId,
            active = statusWord == "connected" || statusWord == "complete",
            pending = statusWord == "pending_approval" || statusWord == "pending_review",
        )
    }

    /** `groupMembers` body {group: {groupInfo, members}} (Controller.hs:822, Types.hs:456): us first, then the others. */
    fun parseMembers(body: JSONObject): List<CoreMember> {
        val group = body.optJSONObject("group") ?: return emptyList()
        val out = ArrayList<CoreMember>()
        // The core's list leaves us out (Store/Groups.hs:1230-1237): our membership comes from groupInfo.
        parseMember(group.optJSONObject("groupInfo")?.optJSONObject("membership"), isMe = true)?.let { out.add(it) }
        group.optJSONArray("members").forEachObject { m -> parseMember(m, isMe = false)?.let { out.add(it) } }
        return out
    }

    /**
     * The group-wide memberIds (GroupMember.memberId, Types.hs:1118: random, made once per member per group and the
     * same in every member's copy) of the other members in a `groupMembers` body, whatever their status now; relays
     * and `unknown` members are left out like in [parseMembers]. Our own membership is never in the core's list, and
     * a rejoin gets us a new memberId anyway. Two group rows sharing one of these are the same group.
     */
    fun otherMemberIds(body: JSONObject): Set<String> {
        val out = HashSet<String>()
        body.optJSONObject("group")?.optJSONArray("members").forEachObject { m ->
            if (parseMember(m, isMe = false) == null) return@forEachObject
            m.str("memberId")?.ifEmpty { null }?.let { out.add(it) }
        }
        return out
    }

    /**
     * `groupLink` / `groupLinkCreated` body {groupInfo, groupLink} (Controller.hs:886-887) -> the link, or null when the
     * body has no groupLink. Links are kept exactly as the core wrote them; the engine checks them before use.
     */
    fun parseGroupLink(body: JSONObject): CoreGroupLink? {
        val gl = body.optJSONObject("groupLink") ?: return null
        val links = gl.optJSONObject("connLinkContact")
        return CoreGroupLink(
            fullLink = links?.str("connFullLink")?.ifEmpty { null },
            shortLink = links?.str("connShortLink")?.ifEmpty { null },
            role = memberRole(gl.str("acceptMemberRole") ?: "member"),
        )
    }

    /** `groupCreated` body {groupInfo} (Controller.hs:815) -> the new groupId. */
    fun createdGroupId(body: JSONObject): Long? = body.optJSONObject("groupInfo")?.optLong("groupId", 0L)?.takeIf { it > 0 }

    /** `apiChats` body -> group chats (not relay channels, not support scopes), each with its newest item. */
    fun parseGroupChats(body: JSONObject): List<CoreGroupChat> {
        val arr = body.optJSONArray("chats") ?: return emptyList()
        val out = ArrayList<CoreGroupChat>()
        for (i in 0 until arr.length()) {
            val chat = arr.optJSONObject(i) ?: continue
            val info = groupInfoOf(chat.optJSONObject("chatInfo")) ?: continue
            val group = parseGroup(info) ?: continue
            val viaLink = joinedByLink(info)
            val items = chat.optJSONArray("chatItems")
            var last: CoreItem? = null
            if (items != null) {
                for (j in 0 until items.length()) {
                    val it = parseChatItem(group.chatId, items.optJSONObject(j), joinedByLink = viaLink, joinedAtMs = group.joinedAtMs) ?: continue
                    if (last == null || it.itemId > last.itemId) last = it
                }
            }
            val unread = chat.optJSONObject("chatStats")?.optInt("unreadCount", 0) ?: 0
            out.add(CoreGroupChat(group, last, unread))
        }
        return out
    }

    /**
     * `groupMemberInfo` body {groupInfo, member, connectionStats_?} (Controller.hs:790) -> (member connected, stats).
     * Stats are absent for a member without an active connection.
     */
    fun memberInfoStats(body: JSONObject): Pair<Boolean, RestoreRepair.ConnStats?> {
        val stats = body.optJSONObject("connectionStats_") ?: body.optJSONObject("connectionStats")
        val status = body.optJSONObject("member")?.str("memberStatus")
        return (status == "connected" || status == "complete") to connectionStats(stats)
    }

    /** (groupId, groupMemberId) of a `{groupInfo, member, ...}` body, or null. */
    private fun memberKeyOf(body: JSONObject): Pair<Long, Long>? {
        val groupId = body.optJSONObject("groupInfo")?.optLong("groupId", 0L)?.takeIf { it > 0 } ?: return null
        val memberId = body.optJSONObject("member")?.optLong("groupMemberId", 0L)?.takeIf { it > 0 } ?: return null
        return groupId to memberId
    }

    /** `groupMemberRatchetSync` event {groupInfo, member, ratchetSyncProgress} (Controller.hs:928) -> (groupId, memberId, status). */
    fun memberRatchetSyncEvent(body: JSONObject): Triple<Long, Long, String>? {
        val (g, m) = memberKeyOf(body) ?: return null
        val status = body.optJSONObject("ratchetSyncProgress")?.str("ratchetSyncStatus")?.ifEmpty { null } ?: return null
        return Triple(g, m, status)
    }

    /** `groupMemberSwitch` event {groupInfo, member, switchProgress} (Controller.hs:926) -> (groupId, memberId, "rcv.completed"). */
    fun memberSwitchEvent(body: JSONObject): Triple<Long, Long, String>? {
        val (g, m) = memberKeyOf(body) ?: return null
        val p = body.optJSONObject("switchProgress") ?: return null
        val dir = p.str("queueDirection") ?: return null
        val phase = p.str("switchPhase") ?: return null
        return Triple(g, m, "$dir.$phase")
    }

    /**
     * CIQuote {chatDir, itemId?, sharedMsgId?, sentAt, content: MsgContent}. A group quote's chatDir is groupRcv with the
     * quoted member (JCIGroupRcv, Messages.hs:664), the same shape as an item's.
     */
    private fun parseQuote(q: JSONObject?): CoreQuote? {
        if (q == null) return null
        val itemId = if (present(q, "itemId")) q.optLong("itemId") else null
        val dir = tagOf(q.opt("chatDir"))
        val outgoing = when (dir) {
            "directSnd", "groupSnd", "localSnd" -> true
            else -> false
        }
        // Group quotes only: what [GroupRules.markOwnHistory] needs to prove a quoted message is ours.
        val group = dir == "groupRcv"
        val author = if (group) q.optJSONObject("chatDir")?.optJSONObject("groupMember")?.str("memberId")?.ifEmpty { null } else null
        val shared = if (group) q.str("sharedMsgId")?.ifEmpty { null } else null
        val content = q.optJSONObject("content")
        return CoreQuote(itemId, content?.str("text").orEmpty(), outgoing, author, shared, tagOf(content))
    }

    /** reactions: [{reaction: {type: "emoji", emoji}, userReacted, totalReacted}]; only the known eight. */
    private fun parseReactions(item: JSONObject): List<ReactionCount> {
        val out = ArrayList<ReactionCount>()
        item.optJSONArray("reactions").forEachObject { r ->
            val reaction = r.optJSONObject("reaction") ?: return@forEachObject
            if (reaction.str("type") != "emoji") return@forEachObject
            val emoji = reaction.str("emoji") ?: return@forEachObject
            if (emoji !in SECURED_REACTIONS) return@forEachObject
            val total = r.optInt("totalReacted", 0)
            if (total > 0) out.add(ReactionCount(emoji, total, r.optBoolean("userReacted", false)))
        }
        return out
    }

    /**
     * `chatItemReaction` body {added, reaction: {chatInfo, chatReaction: {chatDir, chatItem, sentAt, reaction}}}, in a
     * contact's chat or a group.
     */
    fun parseReactionItem(body: JSONObject): CoreItem? {
        val reaction = body.optJSONObject("reaction") ?: return null
        val info = reaction.optJSONObject("chatInfo")
        val chatId = chatIdOf(info)?.takeIf { it != SAVED_MESSAGES_CHAT_ID } ?: return null
        return parseChatItem(chatId, reaction.optJSONObject("chatReaction")?.optJSONObject("chatItem"), peerNameOf(info))
    }

    /**
     * `chatItemsDeleted` body {chatItemDeletions: [{deletedChatItem, toChatItem?}], byUser, timed}: items we show
     * only (messages and known service rows, e.g. a "deleted by admin" row deleted for me).
     */
    fun parseDeletions(body: JSONObject): List<CoreDeletion> {
        val out = ArrayList<CoreDeletion>()
        body.optJSONArray("chatItemDeletions").forEachObject { d ->
            val gone = parseAChatItem(d.optJSONObject("deletedChatItem")) ?: return@forEachObject
            if (!gone.shown) return@forEachObject
            val now = parseAChatItem(d.optJSONObject("toChatItem"))
            out.add(CoreDeletion(gone.chatId, gone.itemId, now))
        }
        return out
    }

    /** All AChatItems of a `newChatItems` / `chatItemsStatusesUpdated` body in direct chats, groups or the note folder. */
    fun parseChatItems(body: JSONObject): List<CoreItem> {
        val arr = body.optJSONArray("chatItems") ?: return emptyList()
        val out = ArrayList<CoreItem>(arr.length())
        for (i in 0 until arr.length()) parseAChatItem(arr.optJSONObject(i))?.let { out.add(it) }
        return out
    }

    /** `apiChats` body -> direct chats only. */
    fun parseChats(body: JSONObject): List<CoreChat> {
        val arr = body.optJSONArray("chats") ?: return emptyList()
        val out = ArrayList<CoreChat>(arr.length())
        for (i in 0 until arr.length()) {
            val chat = arr.optJSONObject(i) ?: continue
            val contact = directContact(chat.optJSONObject("chatInfo")) ?: continue
            val items = chat.optJSONArray("chatItems")
            var last: CoreItem? = null
            if (items != null) {
                for (j in 0 until items.length()) {
                    // The contact's name too, so "Bob was invited" can be the row's lastEvent.
                    val it = parseChatItem(contact.contactId, items.optJSONObject(j), contact.displayName.ifEmpty { null }) ?: continue
                    if (last == null || it.itemId > last.itemId) last = it
                }
            }
            val unread = chat.optJSONObject("chatStats")?.optInt("unreadCount", 0) ?: 0
            out.add(CoreChat(contact, last, unread))
        }
        return out
    }

    /**
     * `apiChats` body -> the note folder (Saved Messages) with its newest item, or null when the list has none.
     * Unread counts are ignored: notes are never unread.
     */
    fun parseNoteChat(body: JSONObject): CoreNoteChat? {
        val arr = body.optJSONArray("chats") ?: return null
        for (i in 0 until arr.length()) {
            val chat = arr.optJSONObject(i) ?: continue
            val folder = noteFolder(chat.optJSONObject("chatInfo")) ?: continue
            val items = chat.optJSONArray("chatItems")
            var last: CoreItem? = null
            if (items != null) {
                for (j in 0 until items.length()) {
                    val it = parseChatItem(SAVED_MESSAGES_CHAT_ID, items.optJSONObject(j)) ?: continue
                    if (last == null || it.itemId > last.itemId) last = it
                }
            }
            return CoreNoteChat(folder, last)
        }
        return null
    }

    /**
     * `apiChat` body -> items of that direct chat, group or note folder (chat id -1), oldest first. A join row whose
     * member has an `invitedViaGroupLink` row on the same page is a link join ([markLinkJoins]); the engine also
     * remembers those rows across pages.
     */
    fun parseChatItemsOfChat(body: JSONObject): List<CoreItem> {
        val chat = body.optJSONObject("chat") ?: return emptyList()
        val info = chat.optJSONObject("chatInfo")
        val chatId = chatIdOf(info) ?: return emptyList()
        val peer = peerNameOf(info)
        val group = groupInfoOf(info)
        val viaLink = joinedByLink(group)
        val joinedAt = joinedAtMs(group)
        val items = chat.optJSONArray("chatItems") ?: return emptyList()
        val out = ArrayList<CoreItem>(items.length())
        for (j in 0 until items.length()) parseChatItem(chatId, items.optJSONObject(j), peer, viaLink, joinedAt)?.let { out.add(it) }
        out.sortBy { it.itemId }
        val linkJoiners = out.mapNotNullTo(HashSet()) { it.linkJoinOf }
        return if (linkJoiners.isEmpty()) out else markLinkJoins(out) { _, memberId -> memberId in linkJoiners }
    }

    /**
     * Someone else's join row ("X joined", their own memberConnected row, [CoreItem.connectedOf]) becomes "X joined via
     * invite link" when [viaLink] says that member of that chat came in through our group link (an invitedViaGroupLink
     * row was seen for them). Only the link owner has such rows (Subscriber.hs:1626-1629), and a member id is never
     * reused within a group, so nothing else can match. A row a member sent about someone else ("Y joined" announced
     * by X) is never marked, even when X once came in through our link.
     */
    fun markLinkJoins(items: List<CoreItem>, viaLink: (chatId: Long, memberId: Long) -> Boolean): List<CoreItem> = items.map { i ->
        val e = i.event
        val member = i.connectedOf
        if (e != null && member != null && e.kind == GroupEvent.Kind.JOINED && e.memberName != null && viaLink(i.chatId, member)) {
            i.copy(event = e.copy(kind = GroupEvent.Kind.JOINED_BY_LINK))
        } else {
            i
        }
    }

    /**
     * One stored key for (group, member): groupId in the high 32 bits, groupMemberId in the low ones; null outside
     * groups or for ids that don't fit (then the row just reads "joined").
     */
    fun linkJoinKey(chatId: Long, memberId: Long): Long? {
        val groupId = groupIdFromChatId(chatId) ?: return null
        if (groupId > Int.MAX_VALUE || memberId <= 0 || memberId > Int.MAX_VALUE) return null
        return (groupId shl 32) or memberId
    }

    /** The groupId of a [linkJoinKey]. */
    fun linkJoinGroupId(key: Long): Long = key ushr 32

    /** `contactCode` body {contact, connectionCode}: the code to compare in person (digits in groups of five). */
    fun contactCode(body: JSONObject): String? = body.str("connectionCode")?.ifEmpty { null }

    /** `connectionVerified` body {verified, expectedCode}. */
    fun connectionVerified(body: JSONObject): Boolean = body.optBoolean("verified", false)

    /** `invitation` body: `connLinkInvitation {connFullLink, connShortLink?}` and the pending connection id. */
    fun parseInvitation(body: JSONObject): CreatedLink {
        val links = body.optJSONObject("connLinkInvitation")
        val full = links?.str("connFullLink")?.ifEmpty { null }
        val short = links?.str("connShortLink")?.ifEmpty { null }
        val conn = body.optJSONObject("connection")
        val connId = if (conn != null && conn.has("pccConnId")) conn.optLong("pccConnId") else null
        return CreatedLink(full, short, connId)
    }

    /** `connectionPlan` body -> e.g. "invitationLink.ownLink", "contactAddress.known". */
    fun connectionPlanTag(body: JSONObject): String {
        val plan = body.optJSONObject("connectionPlan") ?: return "unknown"
        val type = tagOf(plan) ?: return "unknown"
        val inner = when (type) {
            "invitationLink" -> tagOf(plan.opt("invitationLinkPlan"))
            "contactAddress" -> tagOf(plan.opt("contactAddressPlan"))
            "groupLink" -> tagOf(plan.opt("groupLinkPlan"))
            "error" -> "error"
            else -> null
        }
        return if (inner != null) "$type.$inner" else type
    }

    // ---- 12-word backup ------------------------------------------------------------------------------------------

    /** `archiveExported` / `archiveImported` body {archiveErrors: [ArchiveError]}: how many files failed (never their names). */
    fun archiveErrorCount(body: JSONObject): Int = body.optJSONArray("archiveErrors")?.length() ?: 0

    /**
     * ConnectionStats (simplexmq Agent/Protocol.hs:709-716) {connAgentVersion, rcvQueuesInfo: [{rcvServer, status,
     * rcvSwitchStatus?, canAbortSwitch, subStatus}], sndQueuesInfo, ratchetSyncState: ok|allowed|required|started|
     * agreed, ratchetSyncSupported}. A receive queue with a rcvSwitchStatus (switch_started, sending_qadd, ...) means
     * a queue switch is running. Null when absent or without a sync state.
     */
    fun connectionStats(o: JSONObject?): RestoreRepair.ConnStats? {
        if (o == null) return null
        val state = o.str("ratchetSyncState")?.ifEmpty { null } ?: return null
        var switching = false
        o.optJSONArray("rcvQueuesInfo").forEachObject { q ->
            if (q.has("rcvSwitchStatus") && !q.isNull("rcvSwitchStatus")) switching = true
        }
        return RestoreRepair.ConnStats(state, o.optBoolean("ratchetSyncSupported", false), switching)
    }

    /**
     * The SMP servers of every queue in a ConnectionStats (simplexmq Agent/Protocol.hs:686-717): `rcvQueuesInfo[].rcvServer`
     * (our inbox) and `sndQueuesInfo[].sndServer` (the other side's inbox), each `smp://<keyHash>@<hosts>[:<port>]`
     * (strEncode, Protocol.hs:1317-1322), including queues of a switch under way. Never logged: they name servers.
     */
    fun queueServers(stats: JSONObject): List<String> {
        val out = ArrayList<String>()
        stats.optJSONArray("rcvQueuesInfo").forEachObject { q -> q.str("rcvServer")?.let { out.add(it) } }
        stats.optJSONArray("sndQueuesInfo").forEachObject { q -> q.str("sndServer")?.let { out.add(it) } }
        return out
    }

    /**
     * b1: some queue of this connection is on a server none of [ours] serves ([ServerAddress.servedBy]); an address we
     * can't read counts as foreign (fail closed). Stats without any queue: nothing to judge, false.
     */
    fun foreignQueues(stats: JSONObject, ours: List<ServerAddress>): Boolean =
        queueServers(stats).any { s -> ServerAddress.parse(s)?.takeIf { it.scheme == "smp" }?.servedBy(ours) != true }

    /** The ConnectionStats of a `contactInfo` / `groupMemberInfo` reply; absent without an active connection. */
    fun statsOf(body: JSONObject): JSONObject? = body.optJSONObject("connectionStats_") ?: body.optJSONObject("connectionStats")

    /**
     * `contactInfo` body {contact, connectionStats_?, customUserProfile?} (Controller.hs:788; the JSON field keeps the
     * trailing underscore, as upstream's ContactInfo shows). Stats are absent for a contact without an active connection.
     */
    fun contactInfoStats(body: JSONObject): Pair<CoreContact?, RestoreRepair.ConnStats?> {
        val stats = body.optJSONObject("connectionStats_") ?: body.optJSONObject("connectionStats")
        return parseContact(body.optJSONObject("contact")) to connectionStats(stats)
    }

    /** `contactRatchetSync` event {contact, ratchetSyncProgress: {ratchetSyncStatus, connectionStats}} -> (contactId, status). */
    fun ratchetSyncEvent(body: JSONObject): Pair<Long, String>? {
        val contactId = parseContact(body.optJSONObject("contact"))?.contactId ?: return null
        val status = body.optJSONObject("ratchetSyncProgress")?.str("ratchetSyncStatus")?.ifEmpty { null } ?: return null
        return contactId to status
    }

    /**
     * `contactSwitch` event {contact, switchProgress: {queueDirection: rcv|snd, switchPhase: started|confirmed|secured|
     * completed, connectionStats}} (Controller.hs:925, 1348-1353) -> (contactId, "rcv.completed").
     */
    fun switchEvent(body: JSONObject): Pair<Long, String>? {
        val contactId = parseContact(body.optJSONObject("contact"))?.contactId ?: return null
        val p = body.optJSONObject("switchProgress") ?: return null
        val dir = p.str("queueDirection") ?: return null
        val phase = p.str("switchPhase") ?: return null
        return contactId to "$dir.$phase"
    }

    /** UTCTime ("2026-11-07T10:20:30.123456Z") -> epoch millis; 0 if absent or unparseable. */
    fun timeMs(s: String?): Long {
        if (s.isNullOrEmpty()) return 0
        return try {
            Instant.parse(s).toEpochMilli()
        } catch (e: DateTimeParseException) {
            0
        }
    }

    private fun isNullOrAbsent(o: JSONObject, key: String) = !o.has(key) || o.isNull(key)

    private fun present(o: JSONObject, key: String) = o.has(key) && !o.isNull(key)
}
