package app.encrogram.core.internal

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * A chat in API commands (Commands.hs chatRefP / chatTypeP): `@<contactId>` for a contact, `*<noteFolderId>` for
 * the local note folder (Saved Messages). Only built through [contact] and [notes], so it is always one of the two.
 */
internal class ChatRef private constructor(private val text: String) {
    val isNotes: Boolean get() = text.startsWith('*')
    override fun toString(): String = text
    override fun equals(other: Any?): Boolean = other is ChatRef && other.text == text
    override fun hashCode(): Int = text.hashCode()

    companion object {
        fun contact(contactId: Long): ChatRef = ChatRef("@$contactId")
        fun notes(noteFolderId: Long): ChatRef = ChatRef("*$noteFolderId")
    }
}

/**
 * Engine command strings (CLI syntax with embedded JSON) for chat core 7.0.0.12.
 * Syntax verified against src/Simplex/Chat/Library/Commands.hs (chatCommandP) and bots/api/COMMANDS.md.
 *
 * Every JSON fragment goes through [asciiJson]: the JNI glue passes commands with GetStringUTFChars, which
 * produces Java's *modified* UTF-8 (emoji become surrogate pairs the engine would mangle). Escaping all
 * non-ASCII characters as \uXXXX keeps the command pure ASCII, and the engine's JSON parser rebuilds the text.
 */
internal object Commands {

    /** ShowActiveUser -> `activeUser`, or error `noActiveUser`. */
    const val SHOW_ACTIVE_USER = "/u"

    /** APISetEncryptLocalFiles -> `cmdOk`. */
    const val ENCRYPT_LOCAL_FILES = "/_files_encrypt on"

    /**
     * SetNetworkConfig (merges into the current config) -> `networkConfig`.
     * smp-proxy=unknown: commands to any SMP relay that is not in our server list go through our relay as a proxy;
     * smp-proxy-fallback=no: never fall back to a direct connection to that unknown relay.
     */
    const val NETWORK_CONFIG = "/network socks=off smp-proxy=unknown smp-proxy-fallback=no"

    /** APIGetServerOperators -> `serverOperatorConditions`. */
    const val GET_OPERATORS = "/_operators"

    /** StartChat -> `chatStarted` or `chatRunning`. */
    const val START_CHAT = "/_start main=on"

    /** APIStopChat -> `chatStopped`. */
    const val STOP_CHAT = "/_stop"

    /** APISetAppFilePaths -> `cmdOk`. */
    fun setFilePaths(files: String, temp: String, assets: String, remoteHosts: String): String {
        val o = JSONObject()
            .put("appFilesFolder", files)
            .put("appTempFolder", temp)
            .put("appAssetsFolder", assets)
            .put("appRemoteHostsFolder", remoteHosts)
        return "/set file paths " + asciiJson(o.toString())
    }

    /**
     * CreateActiveUser -> `activeUser`. NewUser {profile, pastTimestamp}; userChatRelay/clientService default off.
     * The profile carries [CoreJson.SECURED_PROFILE_PREFS] (fullDelete yes, calls no) from the start, so every
     * contact receives them at connection time.
     */
    fun createUser(displayName: String): String {
        val prefs = JSONObject()
        for ((feature, allow) in CoreJson.SECURED_PROFILE_PREFS) prefs.put(feature, JSONObject().put("allow", allow))
        val profile = JSONObject().put("displayName", displayName).put("fullName", "").put("preferences", prefs)
        val newUser = JSONObject().put("profile", profile).put("pastTimestamp", false)
        return "/_create user " + asciiJson(newUser.toString())
    }

    /**
     * APIUpdateProfile -> `userProfileUpdated` | `userProfileNoChange`. [profile] is the WHOLE Profile (the core
     * replaces it): build it with [CoreJson.profileUpdate]. Sent to every contact.
     */
    fun updateProfile(userId: Long, profile: JSONObject): String = "/_profile $userId " + asciiJson(profile.toString())

    /** APISetServerOperators -> `serverOperatorConditions`. */
    fun setOperators(operators: JSONArray): String = "/_operators " + asciiJson(operators.toString())

    /** APIGetUserServers -> `userServers`. Requires the chat to be started. */
    fun getUserServers(userId: Long): String = "/_servers $userId"

    /** APISetUserServers -> `cmdOk`. Requires the chat to be started. */
    fun setUserServers(userId: Long, groups: JSONArray): String = "/_servers $userId " + asciiJson(groups.toString())

    /** APIGetChats -> `apiChats` (each chat with its last item). */
    fun getChats(userId: Long): String = "/_get chats $userId"

    /**
     * APIGetChat -> `apiChat`. ChatPagination: `count=N` (CPLast), `before=<id> count=N`, `after=<id> count=N`,
     * `around=<id> count=N` (N before, the item, N after). `search=` must be last: it takes the rest of the line
     * as plain text (no JSON escaping), so it must come from [InputRules.searchQuery].
     */
    fun getChat(
        contactId: Long,
        count: Int,
        beforeId: Long? = null,
        afterId: Long? = null,
        aroundId: Long? = null,
        search: String? = null,
    ): String = getChat(ChatRef.contact(contactId), count, beforeId, afterId, aroundId, search)

    /** [getChat] for any chat, including the note folder (`/_get chat *<id> ...`, getLocalChat: same pagination and search). */
    fun getChat(
        chat: ChatRef,
        count: Int,
        beforeId: Long? = null,
        afterId: Long? = null,
        aroundId: Long? = null,
        search: String? = null,
    ): String {
        require(listOfNotNull(beforeId, afterId, aroundId).size <= 1) { "one cursor at most" }
        val pagination = when {
            beforeId != null -> "before=$beforeId count=$count"
            afterId != null -> "after=$afterId count=$count"
            aroundId != null -> "around=$aroundId count=$count"
            else -> "count=$count"
        }
        val cmd = "/_get chat $chat $pagination"
        if (search == null) return cmd
        require(search.isNotEmpty() && search == InputRules.searchQuery(search)) { "search must be sanitised first" }
        return "$cmd search=$search"
    }

    /** APIAddContact: one-time invitation -> `invitation` {connLinkInvitation {connFullLink, connShortLink}}. */
    fun addContact(userId: Long): String = "/_connect $userId"

    /**
     * Connect (plan + connect in one step) -> `sentConfirmation` | `sentInvitation` | `contactAlreadyExists` |
     * `connectionPlan` (when the engine will not proceed, e.g. own link). [link] must be an approved, normalized
     * link from [LinkChecker] (printable ASCII, no spaces).
     */
    fun connect(link: String): String {
        require(link.isNotEmpty() && link.all { it.code in 0x21..0x7E }) { "link must be checked first" }
        return "/connect $link"
    }

    /**
     * APIConnectPlan (`/_connect plan <userId> <link>`, resolve mode default "unknown") -> `connectionPlan`
     * {connLink, connectionPlan: {type: invitationLink, invitationLinkPlan: {type: ok | ownLink | connecting | known}}
     * | {type: contactAddress, contactAddressPlan: {...}} | ...}. Never connects. A short link not known locally is
     * read from its relay (our relay: [link] must be checked first). Unparseable link: error `invalidConnReq`.
     */
    fun connectPlan(userId: Long, link: String): String {
        require(link.isNotEmpty() && link.all { it.code in 0x21..0x7E }) { "link must be checked first" }
        return "/_connect plan $userId $link"
    }

    /**
     * APISendMessages to a contact -> `newChatItems`. With [quotedItemId] the message is a reply; the core copies
     * the quoted item's content into the message itself. Per-message ttl stays default: the contact's timer applies.
     */
    fun sendText(contactId: Long, text: String, quotedItemId: Long? = null): String {
        val msgContent = JSONObject().put("type", "text").put("text", text)
        val composed = JSONObject().put("msgContent", msgContent).put("mentions", JSONObject())
        if (quotedItemId != null) composed.put("quotedItemId", quotedItemId)
        return "/_send @$contactId json " + asciiJson(JSONArray().put(composed).toString())
    }

    /**
     * APICreateChatItems: a text note in the local note folder (Saved Messages) -> `newChatItems` (chatInfo
     * `local`, chatDir `localSnd`). Stored on this device only; nothing is sent. The core refuses quotes and
     * mentions here, so neither is ever included.
     */
    fun createNote(noteFolderId: Long, text: String): String {
        val msgContent = JSONObject().put("type", "text").put("text", text)
        val composed = JSONObject().put("msgContent", msgContent).put("mentions", JSONObject())
        return "/_create *$noteFolderId json " + asciiJson(JSONArray().put(composed).toString())
    }

    /** APIUpdateChatItem -> `chatItemUpdated` | `chatItemNotChanged`; error `invalidChatItemUpdate` when not editable. */
    fun updateItem(contactId: Long, itemId: Long, text: String): String = updateItem(ChatRef.contact(contactId), itemId, text)

    /** [updateItem] for any chat. For a note (`*<id>`) the core edits it locally, with no time limit. */
    fun updateItem(chat: ChatRef, itemId: Long, text: String): String {
        val msgContent = JSONObject().put("type", "text").put("text", text)
        val updated = JSONObject().put("msgContent", msgContent).put("mentions", JSONObject())
        return "/_update item $chat $itemId live=off json " + asciiJson(updated.toString())
    }

    /** CIDeleteMode for [deleteItems]: `internal` (for me), `broadcast` (for everyone). */
    enum class DeleteMode(val word: String) { INTERNAL("internal"), BROADCAST("broadcast") }

    /** APIDeleteChatItem -> `chatItemsDeleted`. `broadcast` errors with `invalidChatItemDelete` when not allowed. */
    fun deleteItems(contactId: Long, itemIds: List<Long>, mode: DeleteMode): String =
        deleteItems(ChatRef.contact(contactId), itemIds, mode)

    /** [deleteItems] for any chat. Notes are always removed locally (deleteLocalCIs ignores the mode). */
    fun deleteItems(chat: ChatRef, itemIds: List<Long>, mode: DeleteMode): String {
        require(itemIds.isNotEmpty()) { "no items" }
        return "/_delete item $chat ${itemIds.joinToString(",")} ${mode.word}"
    }

    /** APIChatItemReaction -> `chatItemReaction`. [emoji] must pass [InputRules.reactionAllowed]. */
    fun reaction(contactId: Long, itemId: Long, add: Boolean, emoji: String): String {
        require(InputRules.reactionAllowed(emoji)) { "unknown reaction" }
        val reaction = JSONObject().put("type", "emoji").put("emoji", emoji)
        return "/_reaction @$contactId $itemId ${if (add) "on" else "off"} " + asciiJson(reaction.toString())
    }

    /** APIForwardChatItems -> `newChatItems` for the destination (empty when nothing could be forwarded). */
    fun forward(toContactId: Long, fromContactId: Long, itemIds: List<Long>): String =
        forward(ChatRef.contact(toContactId), ChatRef.contact(fromContactId), itemIds)

    /**
     * [forward] between any two chats: `/_forward <to> <from> <ids> ttl=default` (both chatRefP). Into the note
     * folder the core stores local copies (createNoteFolderContentItems, ttl ignored); out of it, it sends them.
     */
    fun forward(to: ChatRef, from: ChatRef, itemIds: List<Long>): String {
        require(itemIds.isNotEmpty()) { "no items" }
        return "/_forward $to $from ${itemIds.joinToString(",")} ttl=default"
    }

    /** APISetChatSettings -> `cmdOk`. [settings] is the WHOLE ChatSettings: build it with [CoreJson.chatSettingsUpdate]. */
    fun chatSettings(contactId: Long, settings: JSONObject): String = "/_settings @$contactId " + asciiJson(settings.toString())

    /** APIClearChat -> `chatCleared`. Local only: nothing is sent to the contact. */
    fun clearChat(contactId: Long): String = clearChat(ChatRef.contact(contactId))

    /** [clearChat] for any chat; for the note folder it deletes every note (and its files) on this device. */
    fun clearChat(chat: ChatRef): String = "/_clear chat $chat"

    /** APIDeleteChat, full -> `contactDeleted`. notify=on first tells the contact (x.directDel). */
    fun deleteContact(contactId: Long, notify: Boolean): String =
        "/_delete @$contactId full notify=${if (notify) "on" else "off"}"

    /** APISetContactPrefs -> `contactPrefsUpdated`. [prefs] is the WHOLE Preferences: see [CoreJson.contactPrefsUpdate]. */
    fun setContactPrefs(contactId: Long, prefs: JSONObject): String = "/_set prefs @$contactId " + asciiJson(prefs.toString())

    /** APIGetContactCode -> `contactCode` {contact, connectionCode}. */
    fun getCode(contactId: Long): String = "/_get code @$contactId"

    /** APIVerifyContact -> `connectionVerified` {verified, expectedCode}. Null [code] clears the verification. */
    fun verifyCode(contactId: Long, code: String?): String {
        if (code == null) return "/_verify code @$contactId"
        require(code == InputRules.securityCode(code)) { "code must be checked first" }
        return "/_verify code @$contactId $code"
    }

    /** APIChatRead -> `cmdOk`. */
    fun markRead(contactId: Long): String = "/_read chat @$contactId"

    /** APIDeleteChat for a contact, without notifying anyone. Used only for preset contact cards. */
    fun deleteContactQuietly(contactId: Long): String = "/_delete @$contactId full notify=off"

    /** APIDeleteChat for a pending connection (e.g. an invite we refuse to hand out). */
    fun deletePendingConnection(connId: Long): String = "/_delete :$connId full notify=off"

    // ---- 12-word backup (docs/recovery-phrase-plan.md). Sources: reference/simplex-chat at 7.0.0.12. ----------------

    /**
     * APIExportArchive (`/_db export <ArchiveConfig json>`, Commands.hs:5426; handler :610 `checkChatStopped`, so the
     * chat must be stopped) -> `archiveExported {archiveErrors}`. ArchiveConfig {archivePath, disableCompression?,
     * parentTempDirectory?} (Controller.hs:1216): no disableCompression means Deflate (Archive.hs:66). The zip holds
     * both databases, the files folder and assets/wallpapers (Archive.hs:55-70).
     */
    fun exportArchive(archivePath: String, tempDir: String): String =
        "/_db export " + asciiJson(JSONObject().put("archivePath", archivePath).put("parentTempDirectory", tempDir).toString())

    /**
     * APIImportArchive (`/_db import <ArchiveConfig json>`, Commands.hs:5428; handler :615-618, chat stopped) ->
     * `archiveImported {archiveErrors}`. It closes both stores, keeps the old databases as `.bak` and copies the
     * archive's in (Archive.hs:72-85), and marks the store changed: the controller can't be started again, so the
     * engine opens a new one with chatMigrateInit.
     */
    fun importArchive(archivePath: String, tempDir: String): String =
        "/_db import " + asciiJson(JSONObject().put("archivePath", archivePath).put("parentTempDirectory", tempDir).toString())

    /**
     * APIStorageEncryption (`/_db encryption <DBEncryptionConfig json>`, Commands.hs:5430; handler :620
     * `withStoreChanged` = chat stopped, then `cmdOk`, :3671-3672). Re-keys both SQLCipher databases from
     * [currentKey] to [newKey] (Archive.hs:149-175 `sqlCipherExport`: `.bak` copies, export, close the stores).
     * Keys are DBEncryptionKey strings: printable ASCII without spaces (Controller.hs:1222-1232).
     */
    fun storageEncryption(currentKey: String, newKey: String): String {
        require(dbKeyOk(currentKey) && dbKeyOk(newKey)) { "database keys must be printable ASCII" }
        return "/_db encryption " + asciiJson(JSONObject().put("currentKey", currentKey).put("newKey", newKey).toString())
    }

    /** APIContactInfo (`/_info @<id>`, Commands.hs:5560; handler :1886-1894) -> `contactInfo {contact, connectionStats_?}`. */
    fun contactInfo(contactId: Long): String = "/_info @$contactId"

    /**
     * APISyncContactRatchet (`/_sync @<id>[ force=on]`, Commands.hs:5573; handler :1953-1960) ->
     * `contactRatchetSyncStarted {contact, connectionStats}`. Without force the agent accepts it only in the
     * `allowed` and `required` states (simplexmq Agent.hs:2472, Agent/Store.hs:473-475). [force] only for the
     * restore repair (RestoreRepair.Action.FORCE_SYNC): once per restored contact whose state is `ok`.
     */
    fun syncRatchet(contactId: Long, force: Boolean = false): String =
        if (force) "/_sync @$contactId force=on" else "/_sync @$contactId"

    /**
     * APISwitchContact (`/_switch @<id>`, Commands.hs:5569; handler :1925-1931) -> `contactSwitchStarted
     * {contact, connectionStats}`. Moves our receive queue; progress arrives as `contactSwitch` events.
     */
    fun switchContact(contactId: Long): String = "/_switch @$contactId"

    private fun dbKeyOk(k: String) = k.isNotEmpty() && k.all { it.code in 0x21..0x7E }

    /** Escapes every non-ASCII char of a JSON text as \uXXXX (surrogate pairs stay as two escapes). */
    fun asciiJson(json: String): String {
        var firstNonAscii = -1
        for (i in json.indices) if (json[i].code > 0x7E) { firstNonAscii = i; break }
        if (firstNonAscii < 0) return json
        val sb = StringBuilder(json.length + 32)
        sb.append(json, 0, firstNonAscii)
        for (i in firstNonAscii until json.length) {
            val c = json[i]
            if (c.code > 0x7E) sb.append(String.format(Locale.ROOT, "\\u%04x", c.code)) else sb.append(c)
        }
        return sb.toString()
    }
}
