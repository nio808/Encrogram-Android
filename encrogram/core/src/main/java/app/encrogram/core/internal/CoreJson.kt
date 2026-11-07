package app.encrogram.core.internal

import app.encrogram.core.api.MessageStatus
import app.encrogram.core.api.ReactionCount
import app.encrogram.core.api.SECURED_REACTIONS
import org.json.JSONException
import org.json.JSONObject
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
)

/** chatItem.quotedItem: [itemId] is null when the quoted message is not stored on this device. */
internal data class CoreQuote(val itemId: Long?, val text: String, val outgoing: Boolean)

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
            "error" -> CoreError(type, tagOf(e.opt("errorType")), null)
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

    /** An AChatItem `{chatInfo, chatItem}` in a direct chat, else null. */
    fun parseAChatItem(aChatItem: JSONObject?): CoreItem? {
        if (aChatItem == null) return null
        val contact = directContact(aChatItem.optJSONObject("chatInfo")) ?: return null
        return parseChatItem(contact.contactId, aChatItem.optJSONObject("chatItem"))
    }

    fun parseChatItem(chatId: Long, item: JSONObject?): CoreItem? {
        if (item == null) return null
        val meta = item.optJSONObject("meta") ?: return null
        if (!meta.has("itemId")) return null
        val dir = tagOf(item.opt("chatDir"))
        val content = item.optJSONObject("content")
        val contentType = tagOf(content)
        val outgoing = when (dir) {
            "directSnd", "groupSnd", "localSnd" -> true
            "directRcv", "groupRcv", "channelRcv", "localRcv" -> false
            else -> contentType?.startsWith("snd") == true
        }
        // sndDeleted/rcvDeleted: legacy deleted items (before meta.itemDeleted existed). Shown as deleted messages.
        val legacyDeleted = contentType == "sndDeleted" || contentType == "rcvDeleted"
        val isMessage = contentType == "sndMsgContent" || contentType == "rcvMsgContent" || legacyDeleted
        val deleted = legacyDeleted || present(meta, "itemDeleted")
        val msgContent = content?.optJSONObject("msgContent")
        val msgText = msgContent?.str("text").orEmpty()
        // A deleted message never carries its old text: the core keeps it in the JSON when it only marks it.
        val text = if (deleted) "" else msgText.ifEmpty { meta.str("itemText").orEmpty() }
        val statusTag = tagOf(meta.opt("itemStatus"))
        val timed = meta.optJSONObject("itemTimed")
        val expiresAt = timeMs(timed?.str("deleteAt")).takeIf { it > 0 }
        return CoreItem(
            chatId = chatId,
            itemId = meta.optLong("itemId"),
            outgoing = outgoing,
            isMessage = isMessage,
            text = text,
            timeMs = timeMs(meta.str("itemTs")),
            statusTag = statusTag,
            status = StatusMapper.map(statusTag, outgoing),
            unread = !outgoing && statusTag == "rcvNew",
            msgContentType = tagOf(msgContent),
            quote = if (deleted) null else parseQuote(item.optJSONObject("quotedItem")),
            edited = meta.optBoolean("itemEdited", false),
            deleted = deleted,
            editable = !deleted && meta.optBoolean("editable", false),
            deletable = !deleted && meta.optBoolean("deletable", false),
            shared = meta.str("itemSharedMsgId") != null,
            forwarded = present(meta, "itemForwarded"),
            reactions = if (deleted) emptyList() else parseReactions(item),
            expiresAtMs = expiresAt,
        )
    }

    /** CIQuote {chatDir, itemId?, sharedMsgId?, sentAt, content: MsgContent}. */
    private fun parseQuote(q: JSONObject?): CoreQuote? {
        if (q == null) return null
        val itemId = if (present(q, "itemId")) q.optLong("itemId") else null
        val outgoing = when (tagOf(q.opt("chatDir"))) {
            "directSnd", "groupSnd", "localSnd" -> true
            else -> false
        }
        return CoreQuote(itemId, q.optJSONObject("content")?.str("text").orEmpty(), outgoing)
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

    /** `chatItemReaction` body {added, reaction: {chatInfo, chatReaction: {chatDir, chatItem, sentAt, reaction}}}. */
    fun parseReactionItem(body: JSONObject): CoreItem? {
        val reaction = body.optJSONObject("reaction") ?: return null
        val contact = directContact(reaction.optJSONObject("chatInfo")) ?: return null
        return parseChatItem(contact.contactId, reaction.optJSONObject("chatReaction")?.optJSONObject("chatItem"))
    }

    /** `chatItemsDeleted` body {chatItemDeletions: [{deletedChatItem, toChatItem?}], byUser, timed}: messages only. */
    fun parseDeletions(body: JSONObject): List<CoreDeletion> {
        val out = ArrayList<CoreDeletion>()
        body.optJSONArray("chatItemDeletions").forEachObject { d ->
            val gone = parseAChatItem(d.optJSONObject("deletedChatItem")) ?: return@forEachObject
            if (!gone.isMessage) return@forEachObject
            val now = parseAChatItem(d.optJSONObject("toChatItem"))
            out.add(CoreDeletion(gone.chatId, gone.itemId, now))
        }
        return out
    }

    /** All AChatItems of a `newChatItems` / `chatItemsStatusesUpdated` body that belong to direct chats. */
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
                    val it = parseChatItem(contact.contactId, items.optJSONObject(j)) ?: continue
                    if (last == null || it.itemId > last.itemId) last = it
                }
            }
            val unread = chat.optJSONObject("chatStats")?.optInt("unreadCount", 0) ?: 0
            out.add(CoreChat(contact, last, unread))
        }
        return out
    }

    /** `apiChat` body -> items of that direct chat, oldest first. */
    fun parseChatItemsOfChat(body: JSONObject): List<CoreItem> {
        val chat = body.optJSONObject("chat") ?: return emptyList()
        val contact = directContact(chat.optJSONObject("chatInfo")) ?: return emptyList()
        val items = chat.optJSONArray("chatItems") ?: return emptyList()
        val out = ArrayList<CoreItem>(items.length())
        for (j in 0 until items.length()) parseChatItem(contact.contactId, items.optJSONObject(j))?.let { out.add(it) }
        out.sortBy { it.itemId }
        return out
    }

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
