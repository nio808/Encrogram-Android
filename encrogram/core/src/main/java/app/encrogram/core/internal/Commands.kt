package app.encrogram.core.internal

import app.encrogram.core.api.MemberRole
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * A chat in API commands (Commands.hs chatRefP / chatTypeP, :5927-5930): `@<contactId>` for a contact,
 * `*<noteFolderId>` for the local note folder (Saved Messages), `#<groupId>` for a group (never with a member-support
 * scope). Only built through [contact], [notes] and [group], so it is always one of the three.
 */
internal class ChatRef private constructor(private val text: String) {
    val isNotes: Boolean get() = text.startsWith('*')
    val isGroup: Boolean get() = text.startsWith('#')
    override fun toString(): String = text
    override fun equals(other: Any?): Boolean = other is ChatRef && other.text == text
    override fun hashCode(): Int = text.hashCode()

    companion object {
        fun contact(contactId: Long): ChatRef = ChatRef("@$contactId")
        fun notes(noteFolderId: Long): ChatRef = ChatRef("*$noteFolderId")
        fun group(groupId: Long): ChatRef {
            require(groupId > 0) { "group id" }
            return ChatRef("#$groupId")
        }
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
    fun sendText(contactId: Long, text: String, quotedItemId: Long? = null): String =
        sendText(ChatRef.contact(contactId), text, quotedItemId)

    /**
     * [sendText] to a contact or a group (sendRefP, Commands.hs:5931-5933: `#<groupId>` without scope or
     * `as_group`, so a group message goes to every member as ours). Not for notes: they use [createNote].
     */
    fun sendText(chat: ChatRef, text: String, quotedItemId: Long? = null): String {
        require(!chat.isNotes) { "notes use /_create" }
        val msgContent = JSONObject().put("type", "text").put("text", text)
        val composed = JSONObject().put("msgContent", msgContent).put("mentions", JSONObject())
        if (quotedItemId != null) composed.put("quotedItemId", quotedItemId)
        return "/_send $chat json " + asciiJson(JSONArray().put(composed).toString())
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
    fun reaction(contactId: Long, itemId: Long, add: Boolean, emoji: String): String =
        reaction(ChatRef.contact(contactId), itemId, add, emoji)

    /** [reaction] in a contact or group chat (`/_reaction <chatRef> <itemId> on|off <json>`, Commands.hs:5467). */
    fun reaction(chat: ChatRef, itemId: Long, add: Boolean, emoji: String): String {
        require(!chat.isNotes) { "no reactions in notes" }
        require(InputRules.reactionAllowed(emoji)) { "unknown reaction" }
        val reaction = JSONObject().put("type", "emoji").put("emoji", emoji)
        return "/_reaction $chat $itemId ${if (add) "on" else "off"} " + asciiJson(reaction.toString())
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
    fun chatSettings(contactId: Long, settings: JSONObject): String = chatSettings(ChatRef.contact(contactId), settings)

    /** [chatSettings] for a contact or a group (APISetChatSettings handles CTGroup, Commands.hs:1865-1873). */
    fun chatSettings(chat: ChatRef, settings: JSONObject): String {
        require(!chat.isNotes) { "the core refuses settings for notes" }
        return "/_settings $chat " + asciiJson(settings.toString())
    }

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
    fun markRead(contactId: Long): String = markRead(ChatRef.contact(contactId))

    /** [markRead] for a contact or a group (APIChatRead CTGroup without scope, Commands.hs:1225-1238). */
    fun markRead(chat: ChatRef): String = "/_read chat $chat"

    /** APIDeleteChat for a contact, without notifying anyone. Used only for preset contact cards. */
    fun deleteContactQuietly(contactId: Long): String = "/_delete @$contactId full notify=off"

    /** APIDeleteChat for a pending connection (e.g. an invite we refuse to hand out). */
    fun deletePendingConnection(connId: Long): String = "/_delete :$connId full notify=off"

    // ---- Groups (docs/groups-plan.md, first release). Sources: reference/simplex-chat at 7.0.0.12. -----------------

    /**
     * APINewGroup (`/_group <userId> <GroupProfile json>`, Commands.hs:5603; handler :2639-2644) -> `groupCreated
     * {groupInfo}`. Build [profile] with [CoreJson.newGroupProfile]. Creates the group on this phone only; nothing
     * is sent until members are added.
     */
    fun newGroup(userId: Long, profile: JSONObject): String = "/_group $userId " + asciiJson(profile.toString())

    /**
     * APIAddMember (`/_add #<groupId> <contactId> <role>`, Commands.hs:5508; handler :2742-2772) ->
     * `sentGroupInvitation`. Needs admin (`max GRAdmin memRole`, :2751). Only [ROLE_MEMBER] or [ROLE_OBSERVER] here:
     * the core refuses higher initial roles for links, and the first release adds people as members or observers.
     */
    fun addMember(groupId: Long, contactId: Long, role: String): String {
        require(role == ROLE_MEMBER || role == ROLE_OBSERVER) { "initial role" }
        return "/_add ${ChatRef.group(groupId)} $contactId $role"
    }

    /** APIJoinGroup (`/_join #<groupId>`, Commands.hs:5509) -> `userAcceptedGroupSent`. Accepts an invitation. */
    fun joinGroup(groupId: Long): String = "/_join ${ChatRef.group(groupId)}"

    /** APIListMembers (`/_members #<groupId>`, Commands.hs:5516; handler :3170) -> `groupMembers {group {groupInfo, members}}`. */
    fun listMembers(groupId: Long): String = "/_members ${ChatRef.group(groupId)}"

    /**
     * APILeaveGroup (`/_leave #<groupId>`, Commands.hs:5515; handler :3125-3145) -> `leftMemberUser`. Tells the
     * members, deletes the member connections and keeps the chat (status left).
     */
    fun leaveGroup(groupId: Long): String = "/_leave ${ChatRef.group(groupId)}"

    /**
     * APIDeleteChat for a group (Commands.hs:5478; handler :1336-1366) -> `groupDeletedUser`. The core ignores the mode
     * word here: it deletes for everyone exactly when we are an active owner (`doSendDel = memberActive membership &&
     * isOwner`, :1348), and refuses anyone else who is still a current member (CEGroupUserRole, :1339-1341). So a phone-only delete must
     * leave first. [forEveryone] only documents the caller's intent in the command line.
     */
    fun deleteGroup(groupId: Long, forEveryone: Boolean): String =
        "/_delete ${ChatRef.group(groupId)} full notify=${if (forEveryone) "on" else "off"}"

    /**
     * APIDeleteMemberChatItem (`/_delete member item #<groupId> <itemIds>`, Commands.hs:5464; handler :901-907,
     * :4069-4088) -> `chatItemsDeleted`. Moderator or higher, and never above our own role. With fullDelete on the
     * rows become "moderated" placeholders (Store/Messages.hs:2833-2850 updateGroupChatItemModerated).
     */
    fun deleteMemberItems(groupId: Long, itemIds: List<Long>): String {
        require(itemIds.isNotEmpty()) { "no items" }
        return "/_delete member item ${ChatRef.group(groupId)} ${itemIds.joinToString(",")}"
    }

    /**
     * APIGroupMemberInfo (`/_info #<groupId> <groupMemberId>`, Commands.hs:5557) -> `groupMemberInfo {groupInfo,
     * member, connectionStats_?}` (Controller.hs:790). For the restore repair of member connections.
     */
    fun groupMemberInfo(groupId: Long, groupMemberId: Long): String = "/_info ${ChatRef.group(groupId)} $groupMemberId"

    /**
     * APISyncGroupMemberRatchet (`/_sync #<groupId> <groupMemberId>[ force=on]`, Commands.hs:5572) ->
     * `groupMemberRatchetSyncStarted {groupInfo, member, connectionStats}` (Controller.hs:797). Same agent rules as
     * [syncRatchet]: [force] only for the restore repair.
     */
    fun syncGroupMember(groupId: Long, groupMemberId: Long, force: Boolean = false): String =
        "/_sync ${ChatRef.group(groupId)} $groupMemberId" + if (force) " force=on" else ""

    /**
     * APISwitchGroupMember (`/_switch #<groupId> <groupMemberId>`, Commands.hs:5568) -> `groupMemberSwitchStarted`
     * (Controller.hs:793); progress arrives as `groupMemberSwitch` events (Controller.hs:926).
     */
    fun switchGroupMember(groupId: Long, groupMemberId: Long): String = "/_switch ${ChatRef.group(groupId)} $groupMemberId"

    // ---- Roles, removal and group links (G3) ----

    /**
     * APIMembersRole (`/_member role #<groupId> <groupMemberIds> <role>`, Commands.hs:5512; handler :2877-2905) ->
     * `membersRoleUser` (Controller.hs:881). One member at a time; check [GroupRules.roleChangeRefusal] first.
     */
    fun setMemberRole(groupId: Long, groupMemberId: Long, role: MemberRole): String {
        require(groupMemberId > 0) { "member id" }
        return "/_member role ${ChatRef.group(groupId)} $groupMemberId ${CoreJson.roleWord(role)}"
    }

    /**
     * APIRemoveMembers (`/_remove #<groupId> <groupMemberIds>[ messages=on|off]`, Commands.hs:5514; handler
     * :3014-3060) -> `userDeletedMembers` (Controller.hs:831). messages=off (the default): what they sent stays, as
     * in Telegram without "delete all their messages". Check [GroupRules.removeRefusal] first.
     */
    fun removeMember(groupId: Long, groupMemberId: Long): String {
        require(groupMemberId > 0) { "member id" }
        return "/_remove ${ChatRef.group(groupId)} $groupMemberId messages=off"
    }

    /**
     * APICreateGroupLink (`/_create link #<groupId> <role>`, Commands.hs:5634; handler :3241-3254) ->
     * `groupLinkCreated {groupInfo, groupLink}` (Controller.hs:886). Admin only; the core refuses roles above member
     * (:3244). Creates a short link `https://<our relay>/g#<key>?p=..&c=..` (setShortLinkType CCTGroup, :3251) when the
     * relay supports short links. A second create fails (SEDuplicateGroupLink) after making an unused connection, so
     * the engine always tries [getGroupLink] first.
     */
    fun createGroupLink(groupId: Long, role: String): String {
        require(role == ROLE_MEMBER || role == ROLE_OBSERVER) { "link role" }
        return "/_create link ${ChatRef.group(groupId)} $role"
    }

    /** APIGroupLinkMemberRole (`/_set link role #<groupId> <role>`, Commands.hs:5635; :3255-3264) -> `groupLink`. */
    fun setGroupLinkRole(groupId: Long, role: String): String {
        require(role == ROLE_MEMBER || role == ROLE_OBSERVER) { "link role" }
        return "/_set link role ${ChatRef.group(groupId)} $role"
    }

    /** APIGetGroupLink (`/_get link #<groupId>`, Commands.hs:5637; :3269-3272) -> `groupLink`; none: errorStore groupLinkNotFound. */
    fun getGroupLink(groupId: Long): String = "/_get link ${ChatRef.group(groupId)}"

    /** APIDeleteGroupLink (`/_delete link #<groupId>`, Commands.hs:5636; :3265-3268) -> `groupLinkDeleted`. */
    fun deleteGroupLink(groupId: Long): String = "/_delete link ${ChatRef.group(groupId)}"

    /**
     * APIConnect (`/_connect <userId> <full link>[ <short link>]`, Commands.hs:5658, connLinkP :5781-5784; handler
     * :2337-2348) -> `sentInvitation` for a group link. Joins with exactly the request `/_connect plan` resolved and
     * [ConnectPlan] checked: the core doesn't read the link again and never joins through chat relays (unlike
     * `/connect`, which plans again, :2350-2357, :4489-4500).
     */
    fun connectResolved(userId: Long, fullLink: String, shortLink: String?): String {
        require(fullLink.isNotEmpty() && fullLink.all { it.code in 0x21..0x7E }) { "link must be checked first" }
        require(shortLink == null || (shortLink.isNotEmpty() && shortLink.all { it.code in 0x21..0x7E })) { "link must be checked first" }
        return "/_connect $userId $fullLink" + if (shortLink != null) " $shortLink" else ""
    }

    /** Role words of the core's memberRole parser (Commands.hs:5914-5921). */
    const val ROLE_MEMBER = "member"
    const val ROLE_OBSERVER = "observer"

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
