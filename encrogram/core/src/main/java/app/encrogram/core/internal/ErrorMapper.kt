package app.encrogram.core.internal

import app.encrogram.core.api.SecuredError
import app.encrogram.core.api.SecuredError.Kind

/**
 * Turns engine errors into readable [SecuredError]s. Messages are fixed English sentences plus, at most, the
 * engine's error type tags (e.g. "errorStore.duplicateName"): never message text, names, links or keys.
 */
internal object ErrorMapper {

    const val NOT_RUNNING = "Secured isn't running yet."
    const val NO_PROFILE = "Create your Secured profile first."
    const val NETWORK = "Can't reach the Secured server. Check your internet connection and try again."
    const val LINK_USED = "This invite link was already used or has expired. Ask for a new one."
    const val LINK_INVALID = "This isn't a valid Secured invite link."
    const val CHAT_GONE = "This chat is no longer available."
    const val MESSAGE_GONE = "This message is no longer available."
    const val TOO_LONG = "The message is too long."
    const val CANT_EDIT = "This message can't be edited. Only your own messages can be edited, within 24 hours of sending."
    const val CANT_DELETE_FOR_EVERYONE = "This message can't be deleted for everyone. Only your own messages can be, within a day of sending."
    const val CANT_REACT = "This reaction can't be used here."
    const val CANT_REPLY = "This message can't be replied to."
    const val STORAGE = "Secured storage couldn't be written. Try again."
    const val OWN_LINK = "This is your own invite link. Notes to yourself go in Saved Messages."
    const val NOT_IN_SAVED = "This isn't available in Saved Messages."

    // ---- groups ----
    const val GROUP_ROLE = "Your role in this group doesn't allow this."
    const val NOT_JOINED = "Join the group first."
    const val NOT_A_MEMBER = "You're no longer a member of this group."
    const val GROUP_CONNECTING = "This group is still connecting. Try again in a moment."
    const val ONLY_ADMINS_POST = "Only admins can post here."
    const val ONLY_ADMINS_ADD = "Only admins can add people."
    const val CANT_MODERATE = "Only admins and moderators can delete other members' messages, and never those of someone with a higher role."
    const val CONTACTS_ONLY = "This is only available in a chat with a contact."
    const val NOT_A_CONTACT = "Only your connected Secured contacts can be added."
    const val ALREADY_MEMBER = "This person is already in the group."
    const val INVITATION_GONE = "This invitation is no longer available."
    const val MEMBER_GONE = "This member is no longer in the group."
    const val ALREADY_LEFT = "You already left this group."
    const val ALREADY_JOINED = "You already joined this group."
    const val DECLINE_INSTEAD = "You haven't joined this group. Decline the invitation instead."

    // ---- roles, removal and group links (G3) ----
    const val ONLY_ADMINS_ROLES = "Only admins can change roles."
    const val ONLY_OWNERS_OWNERS = "Only owners can make someone an owner or change an owner's role."
    const val OWN_ROLE = "You can't change your own role."
    const val ONLY_ADMINS_REMOVE = "Only admins can remove people."
    const val ONLY_OWNERS_REMOVE_OWNERS = "Only owners can remove an owner."
    const val REMOVE_SELF = "You can't remove yourself. Leave the group instead."
    const val MEMBER_PENDING = "This person is still waiting to be approved."
    const val ONLY_ADMINS_LINK = "Only admins can manage the group's link."
    const val GROUP_LINK_ELSEWHERE = "The group link wasn't made on Encrogram's server, so it was removed. Try again."
    const val OWN_GROUP_LINK = "This is your group's link."
    const val ALREADY_IN_GROUP = "You're already in this group."
    const val ALREADY_JOINING = "You're already joining this group. It appears in your chats once it connects."
    const val UNSUPPORTED_GROUP_LINK = "This link is for a kind of channel Encrogram doesn't support."

    /** Some of the people could not be added; the others were. Counts only, never names. */
    fun addFailed(failed: Int, total: Int): SecuredError =
        SecuredError(Kind.ENGINE, if (failed == total) "No one could be added. Try again." else "$failed of $total people couldn't be added. Try again for them.")

    enum class Op { START, PROFILE, INVITE, CONNECT, SEND, LOAD, READ, EDIT, DELETE, REACT, FORWARD, CHAT, VERIFY, OTHER }

    /** A refusal by our own rules or the protocol's (time limits, the reaction set). [message] is a fixed sentence. */
    fun notAllowed(message: String): SecuredError = SecuredError(Kind.NOT_ALLOWED, message)

    /** Malformed input from the UI (empty, too long, bad page). */
    fun invalid(message: String): SecuredError = SecuredError(Kind.UNKNOWN, message)

    fun chatGone(): SecuredError = SecuredError(Kind.ENGINE, CHAT_GONE)

    fun messageGone(): SecuredError = SecuredError(Kind.ENGINE, MESSAGE_GONE)

    fun notRunning(state: app.encrogram.core.api.EngineState): SecuredError =
        if (state == app.encrogram.core.api.EngineState.NEEDS_PROFILE) SecuredError(Kind.NO_PROFILE, NO_PROFILE)
        else SecuredError(Kind.ENGINE_NOT_RUNNING, NOT_RUNNING)

    fun unapprovedRelay(host: String): SecuredError =
        SecuredError(Kind.UNAPPROVED_RELAY, "This link points to a server outside Encrogram ($host). Ask your contact for a new Encrogram invite.")

    fun invalidLink(): SecuredError = SecuredError(Kind.INVALID_LINK, LINK_INVALID)

    fun ownLink(): SecuredError = SecuredError(Kind.OWN_LINK, OWN_LINK)

    /** Saved Messages (the local note folder) refuses replies, reactions, timers, mute, archive and verification. */
    fun notInSavedMessages(): SecuredError = notAllowed(NOT_IN_SAVED)

    fun engine(tag: String): SecuredError = SecuredError(Kind.ENGINE, "Secured engine error ($tag).")

    fun fromCore(e: CoreError, op: Op): SecuredError {
        when (e.type) {
            "error" -> when (e.detail) {
                "noActiveUser", "noConnectionUser" -> return SecuredError(Kind.NO_PROFILE, NO_PROFILE)
                "chatNotStarted", "chatStoreChanged" -> return SecuredError(Kind.ENGINE_NOT_RUNNING, NOT_RUNNING)
                "invalidConnReq", "unsupportedConnReq" ->
                    return if (op == Op.CONNECT) invalidLink() else engine(e.tag)
                "connReqMessageProhibited", "contactNotReady", "contactNotActive", "contactDisabled" ->
                    return SecuredError(Kind.ENGINE, "This contact isn't ready to receive messages yet.")
                "userExists", "invalidDisplayName" ->
                    return SecuredError(Kind.ENGINE, "This name can't be used. Try a different name.")
                "invalidChatItemUpdate" -> return notAllowed(CANT_EDIT)
                "invalidChatItemDelete" -> return notAllowed(CANT_DELETE_FOR_EVERYONE)
                // Group errors (ChatErrorType, Controller.hs:1465-1478).
                "groupUserRole", "groupContactRole", "groupMemberInitialRole" -> return notAllowed(GROUP_ROLE)
                "groupNotJoined" -> return notAllowed(NOT_JOINED)
                "groupMemberUserRemoved" -> return notAllowed(NOT_A_MEMBER)
                "groupMemberNotActive" -> return SecuredError(Kind.ENGINE, GROUP_CONNECTING)
                "groupMemberNotFound" -> return SecuredError(Kind.ENGINE, MEMBER_GONE)
                "groupDuplicateMember", "groupDuplicateMemberId" -> return notAllowed(ALREADY_MEMBER)
                "groupCantResendInvitation" -> return SecuredError(Kind.ENGINE, INVITATION_GONE)
                "contactIncognitoCantInvite", "groupIncognitoCantInvite" -> return notAllowed(GROUP_ROLE)
                // throwCmdError: "reaction already added/removed", "too many reactions", "no shared item ID".
                "commandError" -> if (op == Op.REACT) return notAllowed(CANT_REACT)
            }
            "errorStore" -> when (e.detail) {
                "duplicateName" -> return SecuredError(Kind.ENGINE, "This name can't be used. Try a different name.")
                "contactNotFound", "contactNotReady" -> return chatGone()
                "chatItemNotFound" -> return messageGone()
                // StoreError, Store/Shared.hs:98-112.
                "groupNotFound", "groupWithoutUser" -> return chatGone()
                "groupMemberNotFound" -> return SecuredError(Kind.ENGINE, MEMBER_GONE)
                "groupInvitationNotFound" -> return SecuredError(Kind.ENGINE, INVITATION_GONE)
                "groupAlreadyJoined" -> return notAllowed(ALREADY_JOINED)
                "groupLinkNotFound" -> return SecuredError(Kind.ENGINE, "This group has no link yet.")
                "invalidQuote" -> return notAllowed(CANT_REPLY)
                // encodeChatMessage: the whole message would be over maxEncodedMsgLength.
                "largeMsg" -> return invalid(TOO_LONG)
            }
            "errorAgent" -> return fromAgent(e, op)
        }
        return engine(e.tag)
    }

    private fun fromAgent(e: CoreError, op: Op): SecuredError {
        return when (e.detail) {
            "BROKER", "NETWORK", "PROXY" -> SecuredError(Kind.NETWORK, NETWORK)
            "SMP" -> when (e.subDetail) {
                // AUTH on a link operation: the one-time queue is gone (already used, deleted or expired).
                "AUTH", "NO_MSG", "EXPIRED" ->
                    if (op == Op.CONNECT) SecuredError(Kind.INVALID_LINK, LINK_USED) else engine(e.tag)
                "PROXY" -> SecuredError(Kind.NETWORK, NETWORK)
                "QUOTA" -> SecuredError(Kind.NETWORK, "The contact's inbox is full. Try again later.")
                else -> engine(e.tag)
            }
            "CONN" -> if (op == Op.CONNECT) SecuredError(Kind.INVALID_LINK, LINK_USED) else engine(e.tag)
            else -> engine(e.tag)
        }
    }

    /**
     * The verdict of `/_connect plan` before connecting (ConnectPlan.verdict). Our own invite or address: [ownLink]
     * (the core itself would go ahead and connect us to ourselves: connectionPlanProceed is True for ownLink,
     * Controller.hs:1163-1182). Group links (GroupLinkPlan, Controller.hs:1127-1134): our own group's link (the core
     * would also proceed), a group we're in or still joining, and relay channels (noRelays, updateRequired) are refused
     * here, since groups are joined with `/_connect`, which doesn't refuse them itself. Else null: a 1:1 `/connect`
     * decides as before (it refuses known and connecting links with its own `connectionPlan` reply).
     *
     * A group plan never uses [Kind.OWN_LINK]: that kind means "notes to yourself go to Saved Messages".
     */
    fun planRefusal(planTag: String): SecuredError? = when (planTag) {
        "invitationLink.ownLink", "contactAddress.ownLink" -> ownLink()
        "groupLink.ownLink" -> SecuredError(Kind.INVALID_LINK, OWN_GROUP_LINK)
        "groupLink.known" -> SecuredError(Kind.INVALID_LINK, ALREADY_IN_GROUP)
        "groupLink.connectingProhibit" -> SecuredError(Kind.INVALID_LINK, ALREADY_JOINING)
        "groupLink.noRelays", "groupLink.updateRequired" -> notAllowed(UNSUPPORTED_GROUP_LINK)
        else -> null
    }

    // ---- 12-word backup ------------------------------------------------------------------------------------------

    const val WRONG_PHRASE = "These aren't 12 valid recovery words. Check each word and their order."
    const val NO_BACKUP = "No backup was found for these 12 words."
    const val NEEDS_UNLOCK = "Unlock your phone to see the 12 words."
    const val BACKUP_NETWORK = "Can't reach the backup server. Check your internet connection and try again."
    const val BACKUP_BUSY = "The backup server is busy. Try again in a few minutes."
    const val BACKUP_TOO_LARGE = "Your Secured data is too large to back up (over 20 MB)."
    const val BACKUP_DAMAGED = "This backup is damaged and can't be restored."
    const val BACKUP_ROLLBACK = "The server sent an older backup than one this phone has already seen, so it was refused. Try again later."
    const val BACKUP_NEWER_APP = "This backup was made by a newer version of Encrogram. Update the app and try again."
    const val BACKUP_NOT_CONFIGURED = "Backup isn't available in this version."
    const val BACKUP_OFF = "Backup is off."
    const val NO_SCREEN_LOCK = "Set a screen lock on this phone first: the 12 words are kept behind it."
    const val KEYSTORE = "This phone's secure key storage isn't working, so backup can't be used."
    const val PHRASE_INVALIDATED = "The 12 words can't be shown because the phone's screen lock changed. Turn backup off and on again to get new words."
    const val PROFILE_EXISTS = "A Secured profile already exists on this phone."
    const val BACKUP_FAILED = "The backup couldn't be made. Try again."
    const val RESTORE_FAILED = "The backup couldn't be restored. Try again."
    const val OTHER_PHONE = "Another phone made a newer backup with these words. Automatic backups on this phone are paused."
    const val BACKUP_RUNNING = "A backup is already under way. Try again in a moment."
    const val BACKUP_SERVER_ODD = "The backup server didn't answer as expected. Encrogram will try again later."
    /** "Turn off" removed everything on this phone, but the server delete failed and is queued for later. */
    const val SERVER_COPY_NOT_DELETED =
        "The copy on our server could not be deleted yet. Backup is off on this phone, and Encrogram will try to delete the copy again later."
    /** "Turn off" removed everything on this phone, but the key that names the server copy was unreadable. */
    const val SERVER_COPY_UNREACHABLE =
        "The copy on our server could not be deleted, because this phone's secure key storage failed. Backup is off on this phone. " +
            "Anyone with your 12 words can still restore that copy until the server removes it after 400 days unused."
    /** "Turn off" removed everything on this phone, but the server copy isn't provably this phone's, so it was kept (R6.3). */
    const val SERVER_COPY_KEPT =
        "Backup is off on this phone. The copy on our server was kept: another phone with the same 12 words made it, " +
            "or this phone can't confirm it made it. Anyone with your 12 words can still restore that copy."
    /** "Turn off" stopped: the server delete failed and couldn't be queued, so nothing was changed. */
    const val SERVER_COPY_KEEP_ON =
        "The copy on our server could not be deleted. Check your internet connection and try again; backup is still on."

    fun wrongPhrase(): SecuredError = SecuredError(Kind.WRONG_PHRASE, WRONG_PHRASE)

    fun noBackup(): SecuredError = SecuredError(Kind.NO_BACKUP, NO_BACKUP)

    fun needsUnlock(): SecuredError = SecuredError(Kind.NEEDS_UNLOCK, NEEDS_UNLOCK)

    /** The backup server's answer: [failure] is BackupClient.Failure's name, [code] the HTTP status (0: none). */
    fun backupHttp(failure: String, code: Int): SecuredError = when (failure) {
        "NETWORK" -> SecuredError(Kind.NETWORK, BACKUP_NETWORK)
        "NOT_FOUND" -> noBackup()
        "BUSY" -> SecuredError(Kind.NETWORK, BACKUP_BUSY)
        "TOO_LARGE" -> SecuredError(Kind.ENGINE, BACKUP_TOO_LARGE)
        else -> SecuredError(Kind.NETWORK, if (code > 0) "The backup server had a problem (error $code). Try again later." else BACKUP_NETWORK)
    }

    /**
     * The fixed error codes kept in the backup's state file (BackupStore.Meta.error), as readable text for
     * BackupState.lastError. Codes are ours only ("http:503", "network", ...), never engine text.
     */
    fun backupErrorText(code: String?): String? {
        if (code == null) return null
        val parts = code.split(':')
        val status = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return when (parts[0]) {
            "network" -> BACKUP_NETWORK
            "busy" -> BACKUP_BUSY
            "too_large" -> BACKUP_TOO_LARGE
            "server" -> if (status > 0) "The backup server had a problem (error $status). Try again later." else BACKUP_NETWORK
            "keystore" -> KEYSTORE
            "not_running" -> NOT_RUNNING
            "other_phone" -> OTHER_PHONE
            "no_tag" -> BACKUP_SERVER_ODD
            "storage" -> STORAGE
            else -> BACKUP_FAILED
        }
    }

    /** A `connectionPlan` reply means the engine refused to proceed with this link. */
    fun fromConnectionPlan(planTag: String): SecuredError = when (planTag) {
        "invitationLink.ownLink", "contactAddress.ownLink" -> ownLink()
        "invitationLink.connecting", "contactAddress.connectingProhibit", "contactAddress.connectingConfirmReconnect" ->
            SecuredError(Kind.INVALID_LINK, "You're already connecting with this contact.")
        "invitationLink.known", "contactAddress.known", "contactAddress.contactViaAddress" ->
            SecuredError(Kind.INVALID_LINK, "You're already connected with this contact.")
        else -> planRefusal(planTag) ?: SecuredError(Kind.INVALID_LINK, LINK_INVALID)
    }
}
