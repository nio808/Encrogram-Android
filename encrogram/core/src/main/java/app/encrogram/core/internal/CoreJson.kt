package app.encrogram.core.internal

import app.encrogram.core.api.MessageStatus
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
)

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
        return CoreContact(c.optLong("contactId"), name, ready, verified, isCard, removed, ts)
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
        val isMessage = contentType == "sndMsgContent" || contentType == "rcvMsgContent"
        val msgText = content?.optJSONObject("msgContent")?.str("text").orEmpty()
        val text = msgText.ifEmpty { meta.str("itemText").orEmpty() }
        val statusTag = tagOf(meta.opt("itemStatus"))
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
        )
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
}
