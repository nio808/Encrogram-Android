package app.encrogram.core.internal

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.encrogram.core.api.BackupInfo
import app.encrogram.core.api.BackupState
import app.encrogram.core.api.ChatAction
import app.encrogram.core.api.ChatKind
import app.encrogram.core.api.ChatSummary
import app.encrogram.core.api.EngineState
import app.encrogram.core.api.GroupMember
import app.encrogram.core.api.ItemAction
import app.encrogram.core.api.LinkCheck
import app.encrogram.core.api.MediaInput
import app.encrogram.core.api.MediaPlayback
import app.encrogram.core.api.MemberRole
import app.encrogram.core.api.MemberStatus
import app.encrogram.core.api.MessagePage
import app.encrogram.core.api.MessageStatus
import app.encrogram.core.api.PreparedMedia
import app.encrogram.core.api.RelayConfig
import app.encrogram.core.api.SAVED_MESSAGES_CHAT_ID
import app.encrogram.core.api.SecuredCallback
import app.encrogram.core.api.SecuredEngine
import app.encrogram.core.api.SecuredError
import app.encrogram.core.api.SecuredInvite
import app.encrogram.core.api.SecuredListener
import app.encrogram.core.api.SecuredMessage
import app.encrogram.core.api.SecuredProfile
import app.encrogram.core.api.SecuredResult
import app.encrogram.core.api.isGroupChatId
import app.encrogram.core.backup.BackupClient
import app.encrogram.core.backup.BackupSecrets
import app.encrogram.core.backup.BackupStore
import app.encrogram.core.backup.Bip39
import app.encrogram.core.backup.Envelope
import app.encrogram.core.backup.KeyVault
import app.encrogram.core.backup.Kdf
import app.encrogram.core.backup.RepairKey
import app.encrogram.core.backup.RestoreRepair
import app.encrogram.core.internal.ErrorMapper.Op
import app.encrogram.core.internal.InputRules.MAX_PAGE
import app.encrogram.core.internal.SecuredLog.Ev
import app.encrogram.core.internal.media.CtrlGate
import app.encrogram.core.internal.media.MediaCoordinator
import app.encrogram.core.internal.media.MediaHost
import app.encrogram.core.internal.media.MediaLedger
import app.encrogram.core.internal.media.MediaVault
import app.encrogram.core.internal.media.SimplexMediaTransport
import app.encrogram.core.internal.media.SimplexNativeFiles
import app.encrogram.core.internal.media.prep.MediaPreparer
import app.encrogram.core.internal.media.prep.android.AndroidImageCodec
import app.encrogram.core.internal.media.prep.android.AndroidVideoTool
import app.encrogram.core.internal.media.prep.android.ContentInputOpener
import chat.simplex.common.platform.chatCloseStore
import chat.simplex.common.platform.chatMigrateInit
import chat.simplex.common.platform.chatRecvMsgWait
import chat.simplex.common.platform.chatSendCmdRetry
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * [SecuredEngine] over the embedded SimpleX chat core.
 *
 * Threading:
 *  - one engine thread ("encrogram-secured-engine") runs every engine command, all state changes and the chat
 *    list cache, so none of them race;
 *  - one receive thread ("encrogram-secured-recv") blocks in chat_recv_msg_wait and hands each event to the
 *    engine thread;
 *  - every callback and listener call is posted to the Android main thread.
 *
 * Fail closed: the engine reaches RUNNING only after the relay configuration has been applied and read back.
 * Any failure on that path stops the chat and moves to FAILED with a readable [SecuredError]. On a first run
 * (until our servers have been verified once) every connection is held behind an unreachable SOCKS proxy, so the
 * core's preset servers can't be reached between `/_start` and the server check.
 *
 * A listener added with [addListener] immediately receives the current state via onStateChanged (and, when the
 * state is FAILED, the failure via onError), so a screen opened later still learns why Secured is not running.
 */
internal class SimplexSecuredEngine(
    private val appContext: Context,
    private val config: RelayConfig,
) : SecuredEngine {

    private class StartFailure(val error: SecuredError, val logTag: String) : Exception(logTag)

    /** One receive thread's own stop token, bound to the controller it reads. */
    private class Receiver(val ctrl: Long) {
        @Volatile var running = true
        lateinit var thread: Thread
    }

    private val main = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "encrogram-secured-engine").apply { isDaemon = true }
    }
    private val listeners = CopyOnWriteArrayList<SecuredListener>()

    @Volatile private var state = EngineState.NOT_STARTED
    @Volatile private var lastFailure: SecuredError? = null
    @Volatile private var currentProfile: SecuredProfile? = null
    @Volatile private var chatSnapshot: List<ChatSummary> = emptyList()

    // ---- written on the engine thread only (ctrl is also read by the receive thread) ----
    @Volatile private var ctrl = 0L
    private var userId = 0L
    private var chatStarted = false
    private val chatCache = HashMap<Long, ChatSummary>()
    /** Item id of each cached row's last message, so status and edit events can update the row. */
    private val lastItemIds = HashMap<Long, Long>()
    /** Chats where disappearing messages are on for both sides (marking read starts received timers). */
    private val timedChats = HashSet<Long>()
    /** The local note folder behind Saved Messages ([SAVED_MESSAGES_CHAT_ID]), from the last chat list load. */
    private var noteFolderId: Long? = null
    /** Each group row's GroupInfo as of the last chat list load, by our chat id (membership status, channel marker). */
    private val groupCache = HashMap<Long, CoreGroup>()
    /**
     * Old group rows left out of [chats] because a rejoin made a newer row for the same group ([GroupRules.successors]).
     * They stay in [chatCache] and [groupCache], so their history can still be opened and deleted.
     */
    private var hiddenChats: Set<Long> = emptySet()
    /**
     * Per group row a rejoin made (chat id): proof of what we sent from its earlier rows ([GroupRules.ownProofs]: our
     * earlier memberIds and the messages each sent), so our earlier messages in the history it received read as ours
     * ([localView]) and nothing else does. Set by every list load.
     */
    private var earlierProofs: Map<Long, OwnProofs> = emptyMap()
    /** Our own sent messages per ended group row (groupId -> [GroupRules.ownSent]), read once: those rows don't change. */
    private val ownSentCache = HashMap<Long, Map<String, String>>()
    /** Rows already searched for our own unread history ([sweepOwnHistory]), with the proofs searched with. */
    private val ownHistorySwept = HashMap<Long, OwnProofs>()
    /** The other members' memberIds per groupId ([CoreJson.otherMemberIds]), with the member count they were read at. */
    private val memberIdCache = HashMap<Long, Pair<Int, Set<String>>>()
    private var receiver: Receiver? = null

    // ---- the phone's network ([NetworkWatch]): engine thread, except [online] ----
    /** Reports network changes to the core, so it reconnects as soon as the network is back (like the official app). */
    private val network = NetworkWatch(appContext, main) { info -> post { applyNetwork(info) } }
    /** The watch is registered: from opening the store until [stopOnEngine]. */
    private var watchingNetwork = false
    /** The last network the watch reported; sent again to each newly opened store. */
    private var netInfo: NetInfo? = null
    /** Bumped for each network change, so only the last one of a burst reconnects ([applyNetwork]). */
    private var reconnectGen = 0L
    @Volatile private var online = true

    /** [lastShown] results: chat id -> (that chat's newest item id when looked up, the item found or null). */
    private val previewCache = HashMap<Long, Pair<Long, CoreItem?>>()
    /** A list reload ran less than [REFRESH_WINDOW_MS] ago; [refreshOwed]: events asked for another one meanwhile. */
    private var refreshWindowOpen = false
    private var refreshOwed = false

    /** Members who came in through one of our group links (their invitedViaGroupLink row), as [CoreJson.linkJoinKey]s. */
    private val linkJoins: ArchiveStore by lazy { ArchiveStore(File(dataDir, LINK_JOINS_FILE)) }

    private val dataDir: File get() = File(appContext.noBackupFilesDir, DATA_DIR)

    /** Local-only archive flags, in the same no-backup folder as the databases. */
    private val archive: ArchiveStore by lazy { ArchiveStore(File(dataDir, ARCHIVE_FILE)) }

    /** Present from profile creation until our servers have been verified once: start with the network locked. */
    private val lockdownMarker: File get() = File(dataDir, LOCKDOWN_MARKER)

    // ---- 12-word backup (docs/recovery-phrase-plan.md) ----
    private val backupDir: File get() = File(dataDir, BACKUP_DIR)
    private val vault: KeyVault by lazy { KeyVault(appContext, backupDir) }
    private val backupStore: BackupStore by lazy { BackupStore(backupDir, vault.metaBox()) }
    private val backupClient: BackupClient? = BackupClient.create(config.backupBaseUrl)

    /** Network and sealing work: never on the engine thread, so receiving doesn't wait for an upload. */
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "encrogram-secured-backup").apply { isDaemon = true }
    }
    /** Keystore and phrase work ("Show 12 words", new words, storing them): never waits behind an upload (finding 6). */
    private val phraseExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "encrogram-secured-phrase").apply { isDaemon = true }
    }
    private val backupLock = Any()
    @Volatile private var backupStatus: BackupState? = null
    // ---- guarded by backupLock ----
    /** The one timer for the next automatic upload ([scheduleAuto]). */
    private var autoTimer: ScheduledFuture<*>? = null
    private var autoTimerAtMs = 0L
    /** Bumped for each new timer, so a stale timer that fired anyway doesn't forget the current one. */
    private var autoTimerGen = 0L
    /** First request of the current burst of urgent requests (coalescing cap), 0 when none. */
    private var firstUrgentMs = 0L
    /** An upload is under way (probe, export or upload); only one at a time. */
    private var attemptRunning = false
    /** "Back up now" / "turn on" requests that arrived during an attempt: one upload for them right after it. */
    private val waitingManual = ArrayList<SecuredCallback<Unit>>()
    /** Counts urgent requests, so an upload that started before one doesn't clear [BackupStore.Meta.urgent]. */
    private var urgentSeq = 0L

    /** Exists from the start of a restore until its databases, repair list and keys are final (finding 8). */
    private val restoreMarker: File get() = File(dataDir, RESTORE_MARKER)

    // engine thread only
    /** Contacts and member connections still being repaired after a restore (mirror of BackupStore.pending()). */
    private var pendingCache: MutableMap<RepairKey, BackupStore.Pending>? = null
    /** Item id the restore started from (BackupStore.repairSince), loaded with [pendingCache]. */
    private var repairSinceCache = Long.MAX_VALUE
    /** When each connection's ratchet was last re-synced automatically (loop guard). */
    private val lastAutoSync = HashMap<RepairKey, Long>()
    /** Connections with a re-check queued for when their sync guard ends (R6.6), so at most one each. */
    private val syncRechecks = HashSet<RepairKey>()

    // ---- media (docs/media-plan.md §4.5) ----
    /** Native file calls vs. the store's close: every chatCloseStore runs in [CtrlGate.close], each open reopens it. */
    private val ctrlGate = CtrlGate()

    /**
     * Built at the first store open; that creates no folder and no thread (they come with the first file prepared, read
     * or received). Its folders and ledger sit next to `files/`, never inside it: the 12-word export swaps `files/` for
     * an empty folder and takes nothing but the databases (Media never in the backup, media-plan §6).
     */
    private val media: MediaCoordinator by lazy {
        val files = MediaVault(File(dataDir, "files"), File(dataDir, "media"), SimplexNativeFiles(ctrlGate) { ctrl })
        MediaCoordinator(
            // Already receiving, nothing left to cancel: answers, not command errors.
            mediaHost, SimplexMediaTransport { send(it, setOf("fileAlreadyReceiving", "fileCancel")) }, files,
            MediaLedger(File(dataDir, "media_ledger")),
            MediaPreparer(ContentInputOpener(appContext), AndroidImageCodec(), AndroidVideoTool(), files),
            config.xftpServers.mapNotNull { ServerAddress.parse(it) },
            MediaCoordinator.lazyThread("encrogram-secured-media-prep"), MediaCoordinator.lazyThread("encrogram-secured-media-read"),
        )
    }

    private val mediaHost = object : MediaHost {
        override fun run(cmd: String) = send(cmd)
        override fun toMessage(item: CoreItem) = MessageMapper.toMessage(localView(listOf(item)).first(), roleIn(item.chatId))
        // The row's tick follows (a finished upload lifts the clock); never applyNewItems, which would make an older
        // item the row's last message.
        override fun messageChanged(items: List<CoreItem>) {
            emitChanged(items)
            applyStatuses(items)
        }
        override fun itemsAdded(items: List<CoreItem>) = applyNewItems(items)
        override fun progress(chatId: Long, itemId: Long, done: Long, total: Long) {
            main.post { for (l in listeners) l.onMediaProgress(chatId, itemId, done, total) }
        }
        override fun <T> deliver(callback: SecuredCallback<T>, result: SecuredResult<T>) {
            main.post { callback.onResult(result) }
        }
        override fun onEngine(task: () -> Unit) = post(task)
        override fun running() = state == EngineState.RUNNING && ctrl != 0L
        override fun nowMs() = System.currentTimeMillis()
    }

    // =============================================================================================================
    // SecuredEngine
    // =============================================================================================================

    override fun start() = post { startOnEngine() }

    override fun stop() = post { stopOnEngine() }

    override fun state(): EngineState = state

    override fun addListener(listener: SecuredListener) {
        listeners.addIfAbsent(listener)
        main.post {
            if (!listeners.contains(listener)) return@post
            val s = state
            listener.onStateChanged(s)
            if (s == EngineState.FAILED) lastFailure?.let { listener.onError(it) }
        }
    }

    override fun removeListener(listener: SecuredListener) {
        listeners.remove(listener)
    }

    override fun profile(): SecuredProfile? = currentProfile

    override fun createProfile(displayName: String, callback: SecuredCallback<SecuredProfile>) =
        post(callback) { createProfileOnEngine(displayName, callback) }

    override fun updateProfile(displayName: String, callback: SecuredCallback<SecuredProfile>) = post(callback) {
        if (!ensureRunning(callback)) return@post
        val name = validName(displayName) ?: return@post err(callback, nameError())
        val local = activeProfileJson() ?: return@post err(callback, ErrorMapper.engine("active_user"))
        // Read-modify-write: the core replaces the whole profile (G4). Our preferences ride along.
        val update = CoreJson.profileUpdate(local, name) ?: return@post ok(callback, SecuredProfile(name))
        when (val r = send(Commands.updateProfile(userId, update))) {
            is CoreReply.Result -> when (r.type) {
                "userProfileUpdated", "userProfileNoChange" -> {
                    SecuredLog.i(Ev.PROFILE_UPDATED, r.type)
                    val profile = SecuredProfile(name)
                    currentProfile = profile
                    ok(callback, profile)
                    markChanged()
                }
                else -> err(callback, unexpected(r))
            }
            is CoreReply.Error -> err(callback, ErrorMapper.fromCore(r.error, Op.PROFILE))
            CoreReply.Invalid -> err(callback, ErrorMapper.engine("invalid_reply"))
        }
    }

    override fun chats(): List<ChatSummary> = chatSnapshot

    override fun isOnline(): Boolean = online

    override fun messages(chatId: Long, limit: Int, callback: SecuredCallback<List<SecuredMessage>>) =
        messages(chatId, MessagePage(limit), callback)

    override fun messages(chatId: Long, page: MessagePage, callback: SecuredCallback<List<SecuredMessage>>) = post(callback) {
        if (!ensureRunning(callback)) return@post
        if (listOfNotNull(page.beforeId, page.afterId, page.aroundId).size > 1) {
            return@post err(callback, ErrorMapper.invalid("Only one of before, after and around can be set."))
        }
        val search = page.search?.takeIf { it.isNotBlank() }?.let { raw ->
            if (raw.length > InputRules.MAX_SEARCH_CHARS) return@post err(callback, ErrorMapper.invalid("The search text is too long."))
            InputRules.searchQuery(raw)
                ?: return@post err(callback, ErrorMapper.notAllowed("Search needs some text: emoji alone can't be searched yet."))
        }
        val want = page.count.coerceIn(1, MAX_PAGE)
        val ref = refOf(chatId) ?: return@post err(callback, ErrorMapper.chatGone())
        val cmd = Commands.getChat(ref, InputRules.corePageCount(page, want), page.beforeId, page.afterId, page.aroundId, search)
        when (val r = send(cmd)) {
            is CoreReply.Result ->
                if (r.type == "apiChat") {
                    val items = InputRules.pageWindow(localView(CoreJson.parseChatItemsOfChat(r.body)), page, want)
                    val role = roleIn(chatId)
                    ok(callback, items.map { MessageMapper.toMessage(it, role) })
                } else {
                    err(callback, unexpected(r))
                }
            is CoreReply.Error -> err(callback, ErrorMapper.fromCore(r.error, Op.LOAD))
            CoreReply.Invalid -> err(callback, ErrorMapper.engine("invalid_reply"))
        }
    }

    override fun createInvite(callback: SecuredCallback<SecuredInvite>) = post(callback) {
        if (!ensureRunning(callback)) return@post
        when (val r = send(Commands.addContact(userId))) {
            is CoreReply.Result -> {
                if (r.type != "invitation") return@post err(callback, unexpected(r))
                val created = CoreJson.parseInvitation(r.body)
                // Prefer the short https link on our SMP host; the long simplex: form is the fallback.
                val link = created.shortLink ?: created.fullLink
                    ?: return@post err(callback, ErrorMapper.engine("invitation.no_link"))
                when (LinkChecker.check(link, config.allowedHosts, config.smpServers)) {
                    // On our relay the short link drops the relay's port and key hash (?p=..&c=..): checkLink and
                    // connect put them back for our relays. Kept full unless the short form checks the same way.
                    LinkCheck.Approved -> ok(callback, SecuredInvite(compactInvite(link)))
                    else -> {
                        // Never hand out an invite that would bring a contact to a server we don't run.
                        SecuredLog.e(Ev.INVITE_REFUSED)
                        created.pendingConnId?.let { send(Commands.deletePendingConnection(it)) }
                        err(callback, SecuredError(SecuredError.Kind.UNAPPROVED_RELAY, "The invite wasn't created on Encrogram's server, so it was discarded."))
                    }
                }
            }
            is CoreReply.Error -> err(callback, ErrorMapper.fromCore(r.error, Op.INVITE))
            CoreReply.Invalid -> err(callback, ErrorMapper.engine("invalid_reply"))
        }
    }

    override fun checkLink(link: String): LinkCheck =
        LinkChecker.check(LinkChecker.expand(link, config.smpServers), config.allowedHosts, config.smpServers)

    /** The short form of our own invite, only when it checks exactly like the core's link. */
    private fun compactInvite(link: String): String {
        val short = LinkChecker.compact(link, config.smpServers)
        if (short == link) return link
        return if (checkLink(short) == LinkCheck.Approved) short else link
    }

    override fun connect(link: String, callback: SecuredCallback<Unit>) = post(callback) {
        if (!ensureRunning(callback)) return@post
        // Same check as checkLink: our relays' compact links get their port and key hash back first.
        val analysis = LinkChecker.analyze(LinkChecker.expand(link, config.smpServers), config.allowedHosts, config.smpServers)
        when (val check = analysis.result) {
            is LinkCheck.UnapprovedRelay -> {
                SecuredLog.w(Ev.CONNECT_REFUSED, "unapproved_relay")
                return@post err(callback, ErrorMapper.unapprovedRelay(check.host))
            }
            LinkCheck.Malformed -> {
                SecuredLog.w(Ev.CONNECT_REFUSED, "malformed")
                return@post err(callback, ErrorMapper.invalidLink())
            }
            LinkCheck.Approved -> Unit
        }
        val normalized = analysis.normalized ?: return@post err(callback, ErrorMapper.invalidLink())
        // Always ask for the plan first, and connect only on its verdict (ConnectPlan): the request a short link holds
        // must be on our relay too, our own invite would connect us to ourselves (the core proceeds on ownLink), and
        // group links are joined with exactly the checked request. No plan, no connect: a short link we couldn't
        // resolve here would only fail the same way inside /connect.
        // A `/g` link may only join a group: any other plan for it is refused (review #4).
        val groupInput = LinkChecker.isShortGroupLink(normalized)
        val verdict = when (val plan = send(Commands.connectPlan(userId, normalized))) {
            is CoreReply.Result ->
                if (plan.type == "connectionPlan") ConnectPlan.verdict(plan.body, config.allowedHosts, config.smpServers, groupInput)
                else ConnectPlan.Verdict.Refuse(unexpected(plan), "plan_reply")
            is CoreReply.Error -> ConnectPlan.Verdict.Refuse(ErrorMapper.fromCore(plan.error, Op.CONNECT), "plan_error")
            CoreReply.Invalid -> ConnectPlan.Verdict.Refuse(ErrorMapper.engine("invalid_reply"), "plan_reply")
        }
        when (verdict) {
            is ConnectPlan.Verdict.Refuse -> {
                SecuredLog.i(Ev.CONNECT_REFUSED, verdict.logTag)
                return@post err(callback, verdict.error)
            }
            is ConnectPlan.Verdict.JoinGroup -> return@post joinGroupLinkOnEngine(verdict, callback)
            ConnectPlan.Verdict.Connect -> Unit
        }
        when (val r = send(Commands.connect(normalized))) {
            is CoreReply.Result -> when (r.type) {
                "sentConfirmation", "sentInvitation" -> {
                    ok(callback, Unit)
                    refreshChats(notify = true)
                }
                "contactAlreadyExists" -> err(callback, SecuredError(SecuredError.Kind.INVALID_LINK, "You're already connected with this contact."))
                "connectionPlan" -> err(callback, ErrorMapper.fromConnectionPlan(CoreJson.connectionPlanTag(r.body)))
                else -> err(callback, unexpected(r))
            }
            is CoreReply.Error -> err(callback, ErrorMapper.fromCore(r.error, Op.CONNECT))
            CoreReply.Invalid -> err(callback, ErrorMapper.engine("invalid_reply"))
        }
    }

    override fun sendText(chatId: Long, text: String, callback: SecuredCallback<SecuredMessage>) =
        sendMessage(chatId, text, null, callback)

    override fun sendMessage(chatId: Long, text: String, replyToId: Long?, callback: SecuredCallback<SecuredMessage>) = post(callback) {
        if (!ensureRunning(callback)) return@post
        if (text.isBlank()) return@post err(callback, ErrorMapper.invalid("The message is empty."))
        if (!InputRules.textFits(text)) return@post err(callback, ErrorMapper.invalid(ErrorMapper.TOO_LONG))
        if (chatId == SAVED_MESSAGES_CHAT_ID) return@post createNoteOnEngine(text, replyToId, callback)
        val ref = refOf(chatId) ?: return@post err(callback, ErrorMapper.chatGone())
        postRefusal(chatId)?.let { return@post refuse(callback, "send_group", it) }
        if (replyToId != null) {
            val quoted = itemOrErr(chatId, replyToId, callback) ?: return@post
            if (quoted.deleted) return@post refuse(callback, "reply_deleted", ErrorMapper.CANT_REPLY)
            // The core copies the whole quoted text into the message (it can't be shortened), so it uses up
            // part of the body cap; a photo's or video's preview too ([textReplyFits]).
            if (!textReplyFits(text, quoted)) {
                return@post err(callback, ErrorMapper.invalid("This reply is too long. Shorten it, or send it without the quote."))
            }
        }
        when (val r = send(Commands.sendText(ref, text, replyToId))) {
            is CoreReply.Result -> {
                // localView: a reply to one of our earlier messages in a rejoin's history quotes it as ours.
                val item = if (r.type == "newChatItems") localView(CoreJson.parseChatItems(r.body)).firstOrNull { it.chatId == chatId } else null
                if (item == null) return@post err(callback, unexpected(r))
                ok(callback, MessageMapper.toMessage(item, roleIn(chatId)))
                applyNewItems(listOf(item))
            }
            is CoreReply.Error -> err(callback, ErrorMapper.fromCore(r.error, Op.SEND))
            CoreReply.Invalid -> err(callback, ErrorMapper.engine("invalid_reply"))
        }
    }

    override fun itemAction(chatId: Long, messageIds: List<Long>, action: ItemAction, callback: SecuredCallback<Unit>) = post(callback) {
        if (!ensureRunning(callback)) return@post
        val ids = messageIds.distinct()
        if (ids.isEmpty() || ids.size > InputRules.MAX_ITEM_IDS) {
            return@post err(callback, ErrorMapper.invalid("Select 1 to ${InputRules.MAX_ITEM_IDS} messages."))
        }
        if (chatCache[chatId] == null) return@post err(callback, ErrorMapper.chatGone())
        val notes = chatId == SAVED_MESSAGES_CHAT_ID
        when (action) {
            is ItemAction.Edit -> editOnEngine(chatId, ids, action.newText, callback)
            ItemAction.DeleteForMe -> deleteOnEngine(chatId, ids, Commands.DeleteMode.INTERNAL, callback)
            // A note exists only on this phone: deleting it "for everyone" is the same local delete.
            ItemAction.DeleteForEveryone -> when {
                notes -> deleteOnEngine(chatId, ids, Commands.DeleteMode.INTERNAL, callback)
                isGroupChatId(chatId) -> deleteInGroupOnEngine(chatId, ids, callback)
                else -> deleteOnEngine(chatId, ids, Commands.DeleteMode.BROADCAST, callback)
            }
            is ItemAction.React ->
                if (notes) refuse(callback, "react_notes", ErrorMapper.NOT_IN_SAVED) else reactOnEngine(chatId, ids, action, callback)
            is ItemAction.ForwardTo -> forwardOnEngine(chatId, ids, action.toChatId, callback)
        }
    }

    override fun chatAction(chatId: Long, action: ChatAction, callback: SecuredCallback<Unit>) = post(callback) {
        if (!ensureRunning(callback)) return@post
        if (chatCache[chatId] == null) return@post err(callback, ErrorMapper.chatGone())
        if (chatId == SAVED_MESSAGES_CHAT_ID) return@post notesChatAction(action, callback)
        val group = isGroupChatId(chatId)
        when (action) {
            is ChatAction.SetPinned ->
                updateChatSettings(chatId, "pin", callback) { it.put("favorite", action.pinned) }
            is ChatAction.SetMuted ->
                updateChatSettings(chatId, "mute", callback) { it.put("enableNtfs", if (action.muted) "none" else "all") }
            is ChatAction.SetArchived -> setArchivedOnEngine(chatId, action.archived, callback)
            ChatAction.ClearHistory -> {
                val ref = refOf(chatId) ?: return@post err(callback, ErrorMapper.chatGone())
                chatCommand("clear", Commands.clearChat(ref), "chatCleared", callback) { media.onChatRemoved(chatId) }
            }
            // The reload that follows drops the chat's archive flag (ArchiveStore.retainOnly).
            is ChatAction.Delete ->
                if (group) deleteGroupOnEngine(chatId, action.notifyContact, callback)
                else chatCommand("delete", Commands.deleteContact(chatId, action.notifyContact), "contactDeleted", callback) { media.onChatRemoved(chatId) }
            is ChatAction.SetDisappearing -> {
                if (group) return@post refuse(callback, "disappearing_group", ErrorMapper.CONTACTS_ONLY)
                val ttl = action.ttlSeconds
                if (ttl != null && !InputRules.ttlAllowed(ttl)) {
                    return@post err(callback, ErrorMapper.invalid("Choose a timer between 1 second and 1 year."))
                }
                val contact = contactOrErr(chatId, callback) ?: return@post
                chatCommand("disappearing", Commands.setContactPrefs(chatId, CoreJson.contactPrefsUpdate(contact, ttl)), "contactPrefsUpdated", callback)
            }
        }
    }

    override fun markRead(chatId: Long) = post {
        if (state != EngineState.RUNNING || ctrl == 0L) return@post
        // Notes are never unread.
        if (chatId == SAVED_MESSAGES_CHAT_ID) return@post
        val ref = refOf(chatId) ?: return@post
        val r = send(Commands.markRead(ref))
        if (r is CoreReply.Result) {
            val s = chatCache[chatId] ?: return@post
            if (s.unreadCount != 0) {
                chatCache[chatId] = s.copy(unreadCount = 0)
                publishChats()
                notifyChatsChanged()
                // Reading starts the countdown of received disappearing messages (APIChatRead).
                if (chatId in timedChats) reportStartedTimers(chatId, s.unreadCount)
            }
        }
    }

    override fun securityCode(chatId: Long, callback: SecuredCallback<String>) = post(callback) {
        if (!ensureRunning(callback)) return@post
        if (chatId == SAVED_MESSAGES_CHAT_ID) return@post err(callback, ErrorMapper.notInSavedMessages())
        if (isGroupChatId(chatId)) return@post refuse(callback, "code_group", ErrorMapper.CONTACTS_ONLY)
        when (val r = send(Commands.getCode(chatId))) {
            is CoreReply.Result -> {
                val code = if (r.type == "contactCode") CoreJson.contactCode(r.body) else null
                if (code == null) return@post err(callback, unexpected(r))
                // The core clears the verified mark here when the keys changed since verification. Refresh the
                // cache first, so a caller reading chats() in the callback never sees a stale "verified".
                val verified = CoreJson.parseContact(r.body.optJSONObject("contact"))?.verified
                if (verified != null && verified != chatCache[chatId]?.verified) refreshChats(notify = true)
                ok(callback, code)
            }
            is CoreReply.Error -> err(callback, ErrorMapper.fromCore(r.error, Op.VERIFY))
            CoreReply.Invalid -> err(callback, ErrorMapper.engine("invalid_reply"))
        }
    }

    override fun verifyContact(chatId: Long, code: String?, callback: SecuredCallback<Boolean>) = post(callback) {
        if (!ensureRunning(callback)) return@post
        if (chatId == SAVED_MESSAGES_CHAT_ID) return@post err(callback, ErrorMapper.notInSavedMessages())
        if (isGroupChatId(chatId)) return@post refuse(callback, "verify_group", ErrorMapper.CONTACTS_ONLY)
        val checked = code?.let { InputRules.securityCode(it) ?: return@post err(callback, ErrorMapper.invalid("A security code has only digits and spaces.")) }
        when (val r = send(Commands.verifyCode(chatId, checked))) {
            is CoreReply.Result -> {
                if (r.type != "connectionVerified") return@post err(callback, unexpected(r))
                ok(callback, checked != null && CoreJson.connectionVerified(r.body))
                refreshChats(notify = true)
            }
            is CoreReply.Error -> err(callback, ErrorMapper.fromCore(r.error, Op.VERIFY))
            CoreReply.Invalid -> err(callback, ErrorMapper.engine("invalid_reply"))
        }
    }

    // ---- media: the engine's checks, then MediaCoordinator (docs/media-plan.md §4.5) ----

    override fun prepareMedia(input: MediaInput, callback: SecuredCallback<PreparedMedia>) =
        if (state != EngineState.RUNNING) err(callback, ErrorMapper.notRunning(state)) else media.prepare(input, callback)

    override fun discardMedia(prepared: PreparedMedia) = media.discard(prepared)

    override fun sendMedia(chatId: Long, prepared: PreparedMedia, caption: String, replyToId: Long?, callback: SecuredCallback<SecuredMessage>) = post(callback) {
        if (!ensureRunning(callback)) return@post
        if (chatId == SAVED_MESSAGES_CHAT_ID) return@post refuse(callback, "media_notes", ErrorMapper.MEDIA_NOT_IN_SAVED)
        val ref = refOf(chatId) ?: return@post err(callback, ErrorMapper.chatGone())
        postRefusal(chatId)?.let { return@post refuse(callback, "send_group", it) }
        val quoted = replyToId?.let { itemOrErr(chatId, it, callback) ?: return@post }
        if (quoted?.deleted == true) return@post refuse(callback, "reply_deleted", ErrorMapper.CANT_REPLY)
        media.send(chatId, ref, prepared, caption, quoted, callback)
    }

    override fun acceptMedia(chatId: Long, messageId: Long, callback: SecuredCallback<Unit>) =
        withItem(chatId, messageId, callback) { media.accept(it, callback) }

    override fun cancelMedia(chatId: Long, messageId: Long, callback: SecuredCallback<Unit>) =
        withItem(chatId, messageId, callback) { media.cancel(it, callback) }

    override fun readMediaBytes(chatId: Long, messageId: Long, callback: SecuredCallback<ByteArray>) =
        withItem(chatId, messageId, callback) { media.readBytes(it, callback) }

    override fun openMediaForPlayback(chatId: Long, messageId: Long, callback: SecuredCallback<MediaPlayback>) =
        withItem(chatId, messageId, callback) { media.openPlayback(it, callback) }

    /** Engine thread, RUNNING: [chatId]'s message [messageId] as the chat shows it, or the reason goes to [callback]. */
    private fun <T> withItem(chatId: Long, messageId: Long, callback: SecuredCallback<T>, block: (CoreItem) -> Unit) = post(callback) {
        if (!ensureRunning(callback)) return@post
        block(itemOrErr(chatId, messageId, callback) ?: return@post)
    }

    // =============================================================================================================
    // Groups and channels (docs/groups-plan.md, first release). Engine thread, like every other command. Logs carry
    // only fixed tags and counts: never group names, member names or text.
    // =============================================================================================================

    /** `/_group` with history and full delete on; a channel also carries our marker (CoreJson.CHANNEL_MARKER_PREF). */
    override fun createGroup(name: String, channel: Boolean, callback: SecuredCallback<Long>) = post(callback) {
        if (!ensureRunning(callback)) return@post
        val groupName = validName(name) ?: return@post err(callback, nameError())
        when (val r = send(Commands.newGroup(userId, CoreJson.newGroupProfile(groupName, channel)))) {
            is CoreReply.Result -> {
                val groupId = if (r.type == "groupCreated") CoreJson.createdGroupId(r.body) else null
                if (groupId == null) return@post err(callback, unexpected(r))
                SecuredLog.i(Ev.GROUP_ACTION, if (channel) "create_channel" else "create_group")
                // The row first, so a caller reading chats() in the callback finds it.
                refreshChats(notify = true)
                // A group made after the last upload would be missing after a restore (G7).
                requestUrgentBackup("group", CONTACT_BACKUP_DELAY_MS)
                ok(callback, CoreJson.groupChatId(groupId))
            }
            is CoreReply.Error -> err(callback, ErrorMapper.fromCore(r.error, Op.CHAT))
            CoreReply.Invalid -> err(callback, ErrorMapper.engine("invalid_reply"))
        }
    }

    /**
     * `/_add #g <contactId> member` (observer in a channel) for each contact. Only our own ready contacts, checked
     * before anything is sent; the core then checks our role (admin). Failures don't stop the rest; the result says
     * how many failed. Someone already in the group counts as added.
     */
    override fun addMembers(chatId: Long, contactChatIds: List<Long>, callback: SecuredCallback<Unit>) = post(callback) {
        if (!ensureRunning(callback)) return@post
        val g = groupCache[chatId] ?: return@post err(callback, ErrorMapper.chatGone())
        if (g.invited) return@post refuse(callback, "add_not_joined", ErrorMapper.NOT_JOINED)
        if (g.myRole < MemberRole.ADMIN) return@post refuse(callback, "add_role", ErrorMapper.ONLY_ADMINS_ADD)
        val ids = contactChatIds.distinct()
        if (ids.isEmpty() || ids.size > InputRules.MAX_ITEM_IDS) {
            return@post err(callback, ErrorMapper.invalid("Choose 1 to ${InputRules.MAX_ITEM_IDS} people."))
        }
        for (id in ids) {
            val row = chatCache[id]
            val contact = row != null && row.kind == ChatKind.DIRECT && id > 0 && !isGroupChatId(id) && row.ready
            if (!contact) return@post refuse(callback, "add_not_contact", ErrorMapper.NOT_A_CONTACT)
        }
        val role = if (g.channel) Commands.ROLE_OBSERVER else Commands.ROLE_MEMBER
        var failed = 0
        for (id in ids) {
            when (val r = send(Commands.addMember(g.groupId, id, role))) {
                is CoreReply.Result -> if (r.type != "sentGroupInvitation") {
                    SecuredLog.w(Ev.CMD_UNEXPECTED_REPLY, r.type)
                    failed++
                }
                is CoreReply.Error -> if (r.error.detail != "groupDuplicateMember") failed++
                CoreReply.Invalid -> failed++
            }
        }
        SecuredLog.i(Ev.GROUP_ACTION, "add", ids.size)
        if (failed > 0) SecuredLog.w(Ev.GROUP_ACTION, "add_failed", failed)
        refreshChats(notify = true)
        if (failed == 0) ok(callback, Unit) else err(callback, ErrorMapper.addFailed(failed, ids.size))
    }

    /** `/_join #g`: accepts an invitation ([ChatSummary.invited]). Declining is chatAction(Delete). */
    override fun joinGroup(chatId: Long, callback: SecuredCallback<Unit>) = post(callback) {
        if (!ensureRunning(callback)) return@post
        val g = groupCache[chatId] ?: return@post err(callback, ErrorMapper.chatGone())
        if (!g.invited) return@post refuse(callback, "join_not_invited", ErrorMapper.ALREADY_JOINED)
        when (val r = send(Commands.joinGroup(g.groupId))) {
            is CoreReply.Result -> {
                if (r.type != "userAcceptedGroupSent") return@post err(callback, unexpected(r))
                SecuredLog.i(Ev.GROUP_ACTION, "join")
                refreshChats(notify = true)
                ok(callback, Unit)
            }
            is CoreReply.Error -> err(callback, ErrorMapper.fromCore(r.error, Op.CHAT))
            CoreReply.Invalid -> err(callback, ErrorMapper.engine("invalid_reply"))
        }
    }

    /**
     * `/_members #g`, with us first. [GroupMember.contactChatId] is the member's 1:1 chat when the core links them to
     * one of our contacts (memberContactId) that is in our chat list. In a group its owner deleted nobody is a member
     * any more: the core keeps the others' rows as they were (xGrpDel changes only ours, Subscriber.hs:3717-3727), so
     * they are reported REMOVED, matching the row's member count of 0.
     */
    override fun members(chatId: Long, callback: SecuredCallback<List<GroupMember>>) = post(callback) {
        if (!ensureRunning(callback)) return@post
        val groupId = CoreJson.groupIdFromChatId(chatId) ?: return@post err(callback, ErrorMapper.chatGone())
        val g = groupCache[chatId] ?: return@post err(callback, ErrorMapper.chatGone())
        when (val r = send(Commands.listMembers(groupId))) {
            is CoreReply.Result -> {
                if (r.type != "groupMembers") return@post err(callback, unexpected(r))
                val list = CoreJson.parseMembers(r.body).map { m ->
                    val contactChat = m.memberContactId?.takeIf { id -> chatCache[id]?.kind == ChatKind.DIRECT && !isGroupChatId(id) }
                    val status = if (g.deleted) MemberStatus.REMOVED else m.status
                    GroupMember(m.groupMemberId, m.displayName, m.role, status, m.isMe, contactChat)
                }
                ok(callback, list)
            }
            is CoreReply.Error -> err(callback, ErrorMapper.fromCore(r.error, Op.LOAD))
            CoreReply.Invalid -> err(callback, ErrorMapper.engine("invalid_reply"))
        }
    }

    /** `/_leave #g`: the members are told, the chat stays (status left) until it is deleted. */
    override fun leaveGroup(chatId: Long, callback: SecuredCallback<Unit>) = post(callback) {
        if (!ensureRunning(callback)) return@post
        val g = groupCache[chatId] ?: return@post err(callback, ErrorMapper.chatGone())
        if (g.invited) return@post refuse(callback, "leave_invited", ErrorMapper.DECLINE_INSTEAD)
        if (g.gone) return@post refuse(callback, "leave_gone", ErrorMapper.ALREADY_LEFT)
        when (val r = send(Commands.leaveGroup(g.groupId))) {
            is CoreReply.Result -> {
                if (r.type != "leftMemberUser") return@post err(callback, unexpected(r))
                SecuredLog.i(Ev.GROUP_ACTION, "leave")
                refreshChats(notify = true)
                requestUrgentBackup("group", CONTACT_BACKUP_DELAY_MS)
                ok(callback, Unit)
            }
            is CoreReply.Error -> err(callback, ErrorMapper.fromCore(r.error, Op.CHAT))
            CoreReply.Invalid -> err(callback, ErrorMapper.engine("invalid_reply"))
        }
    }

    // ---- Roles, removal and group links (G3) ----------------------------------------------------------------------

    /**
     * `/_member role #g <groupMemberId> <role>`, after the core's own rule ([GroupRules.roleChangeRefusal]) on a fresh
     * member list. Everyone sees a "X is now <role>" row (ROLE_CHANGED), which arrives as newChatItems.
     */
    override fun setMemberRole(chatId: Long, memberId: Long, role: MemberRole, callback: SecuredCallback<Unit>) = post(callback) {
        if (!ensureRunning(callback)) return@post
        val g = groupCache[chatId] ?: return@post err(callback, ErrorMapper.chatGone())
        val members = membersOrErr(g, callback) ?: return@post
        GroupRules.roleChangeRefusal(members.firstOrNull { it.isMe }, members.firstOrNull { !it.isMe && it.groupMemberId == memberId }, role)
            ?.let { return@post refuse(callback, "role", it) }
        chatCommand("member_role", Commands.setMemberRole(g.groupId, memberId, role), "membersRoleUser", callback)
    }

    /**
     * `/_remove #g <groupMemberId> messages=off`, after [GroupRules.removeRefusal]. Members see "X was removed"; the
     * removed member's row gets YOU_REMOVED and their chat stays readable (status removed, can't post) until deleted.
     */
    override fun removeMember(chatId: Long, memberId: Long, callback: SecuredCallback<Unit>) = post(callback) {
        if (!ensureRunning(callback)) return@post
        val g = groupCache[chatId] ?: return@post err(callback, ErrorMapper.chatGone())
        val members = membersOrErr(g, callback) ?: return@post
        GroupRules.removeRefusal(members.firstOrNull { it.isMe }, members.firstOrNull { !it.isMe && it.groupMemberId == memberId })
            ?.let { return@post refuse(callback, "remove", it) }
        chatCommand("member_remove", Commands.removeMember(g.groupId, memberId), "userDeletedMembers", callback)
    }

    /**
     * The group's link: the existing one (`/_get link #g`) or a new one (`/_create link #g <role>`). People join as
     * members, or as observers in a channel; an existing link with another role is set right (`/_set link role`).
     * Both the link and the connection request behind it (what a joiner's core reads from the short link) must be
     * on our relay, else the link is deleted and nothing is handed out. The short link is compacted like our invites
     * (`https://<relay>/g#<key>`, the relay's `?p=..&c=..` dropped); without a short link the full link is returned.
     */
    override fun groupLink(chatId: Long, callback: SecuredCallback<SecuredInvite>) = post(callback) {
        if (!ensureRunning(callback)) return@post
        val g = groupCache[chatId] ?: return@post err(callback, ErrorMapper.chatGone())
        GroupRules.linkRefusal(g)?.let { return@post refuse(callback, "link_role", it) }
        val role = if (g.channel) Commands.ROLE_OBSERVER else Commands.ROLE_MEMBER
        var created = false
        var link: CoreGroupLink? = when (val r = send(Commands.getGroupLink(g.groupId))) {
            // A link exists: never create a second one (the core would make an unused connection, then refuse).
            is CoreReply.Result ->
                if (r.type == "groupLink") CoreJson.parseGroupLink(r.body) ?: return@post err(callback, ErrorMapper.engine("group_link.reply"))
                else return@post err(callback, unexpected(r))
            // No link yet (StoreError SEGroupLinkNotFound, Store/Groups.hs:342-345): create one below.
            is CoreReply.Error ->
                if (r.error.type == "errorStore" && r.error.detail == "groupLinkNotFound") null
                else return@post err(callback, ErrorMapper.fromCore(r.error, Op.INVITE))
            CoreReply.Invalid -> return@post err(callback, ErrorMapper.engine("invalid_reply"))
        }
        if (link == null) {
            link = when (val r = send(Commands.createGroupLink(g.groupId, role))) {
                is CoreReply.Result -> if (r.type == "groupLinkCreated") CoreJson.parseGroupLink(r.body) else return@post err(callback, unexpected(r))
                is CoreReply.Error -> return@post err(callback, ErrorMapper.fromCore(r.error, Op.INVITE))
                CoreReply.Invalid -> return@post err(callback, ErrorMapper.engine("invalid_reply"))
            }
            created = true
        } else if (CoreJson.roleWord(link.role) != role) {
            link = when (val r = send(Commands.setGroupLinkRole(g.groupId, role))) {
                is CoreReply.Result -> if (r.type == "groupLink") CoreJson.parseGroupLink(r.body) else return@post err(callback, unexpected(r))
                is CoreReply.Error -> return@post err(callback, ErrorMapper.fromCore(r.error, Op.INVITE))
                CoreReply.Invalid -> return@post err(callback, ErrorMapper.engine("invalid_reply"))
            }
            SecuredLog.i(Ev.GROUP_ACTION, "link_role_fixed")
        }
        val l = link ?: return@post err(callback, ErrorMapper.engine("group_link.no_link"))
        val full = l.fullLink
        val approved = full != null && listOfNotNull(full, l.shortLink).all {
            LinkChecker.check(it, config.allowedHosts, config.smpServers) == LinkCheck.Approved
        }
        if (!approved) {
            // Never hand out a link that would bring people to a server we don't run.
            SecuredLog.e(Ev.INVITE_REFUSED, "group_link")
            send(Commands.deleteGroupLink(g.groupId))
            return@post err(callback, SecuredError(SecuredError.Kind.UNAPPROVED_RELAY, ErrorMapper.GROUP_LINK_ELSEWHERE))
        }
        // A channel link must let people in as observers only; never hand out one that doesn't.
        if (CoreJson.roleWord(l.role) != role) return@post err(callback, ErrorMapper.engine("group_link.role"))
        SecuredLog.i(Ev.GROUP_ACTION, if (created) "link_create" else "link_get")
        ok(callback, SecuredInvite(compactInvite(l.shortLink ?: full!!)))
    }

    /**
     * `/_delete link #g`. No role or membership check, like the core (APIDeleteGroupLink, Commands.hs:3265-3268): the
     * link is our own, so an admin who was made a member again can still delete the link they made (review #6).
     * No link is not an error: the result is the same.
     */
    override fun deleteGroupLink(chatId: Long, callback: SecuredCallback<Unit>) = post(callback) {
        if (!ensureRunning(callback)) return@post
        val g = groupCache[chatId] ?: return@post err(callback, ErrorMapper.chatGone())
        when (val r = send(Commands.deleteGroupLink(g.groupId))) {
            is CoreReply.Result -> {
                if (r.type != "groupLinkDeleted") return@post err(callback, unexpected(r))
                SecuredLog.i(Ev.GROUP_ACTION, "link_delete")
                ok(callback, Unit)
            }
            is CoreReply.Error ->
                if (r.error.type == "errorStore" && r.error.detail == "groupLinkNotFound") ok(callback, Unit)
                else err(callback, ErrorMapper.fromCore(r.error, Op.INVITE))
            CoreReply.Invalid -> err(callback, ErrorMapper.engine("invalid_reply"))
        }
    }

    /**
     * Joins a group by its link with exactly the request [ConnectPlan] checked (`/_connect <userId> <full> <short>`)
     * -> `sentInvitation`. Nothing shows until the admin's phone answers: then the group appears
     * (groupLinkConnecting, a chat-list event) with the link's role, connecting, and later connected.
     */
    private fun joinGroupLinkOnEngine(join: ConnectPlan.Verdict.JoinGroup, callback: SecuredCallback<Unit>) {
        when (val r = send(Commands.connectResolved(userId, join.fullLink, join.shortLink))) {
            is CoreReply.Result -> when (r.type) {
                "sentInvitation" -> {
                    SecuredLog.i(Ev.GROUP_ACTION, "join_link")
                    ok(callback, Unit)
                    refreshChats(notify = true)
                }
                else -> err(callback, unexpected(r))
            }
            is CoreReply.Error -> err(callback, ErrorMapper.fromCore(r.error, Op.CONNECT))
            CoreReply.Invalid -> err(callback, ErrorMapper.engine("invalid_reply"))
        }
    }

    /** `/_members #g` parsed (us first), or null after reporting an error to [callback]. */
    private fun <T> membersOrErr(g: CoreGroup, callback: SecuredCallback<T>): List<CoreMember>? {
        when (val r = send(Commands.listMembers(g.groupId))) {
            is CoreReply.Result -> {
                if (r.type == "groupMembers") return CoreJson.parseMembers(r.body)
                err(callback, unexpected(r))
            }
            is CoreReply.Error -> err(callback, ErrorMapper.fromCore(r.error, Op.LOAD))
            CoreReply.Invalid -> err(callback, ErrorMapper.engine("invalid_reply"))
        }
        return null
    }

    // =============================================================================================================
    // 12-word backup (docs/recovery-phrase-plan.md). Network and sealing on the backup thread; Keystore and phrase
    // work on the phrase thread, so it never waits behind an upload; export, import and the per-contact repair on the
    // engine thread. Nothing here logs words, ids, keys, URLs, server tags or archives.
    // =============================================================================================================

    override fun backupState(): BackupState = backupStatus ?: synchronized(backupLock) {
        backupStatus ?: loadBackupState().also { backupStatus = it }
    }

    override fun newPhrase(callback: SecuredCallback<List<String>>) = onPhrase(callback) {
        val entropy = Bip39.newEntropy()
        try {
            ok(callback, Bip39.toWords(entropy))
        } finally {
            entropy.fill(0)
        }
    }

    override fun enableBackup(words: List<String>, callback: SecuredCallback<Unit>) = onPhrase(callback) { enableOnPhraseThread(words, callback) }

    /** On the phrase thread: the 30-second unlock window must not run out behind a 20 MB upload or a restore. */
    override fun showPhrase(callback: SecuredCallback<List<String>>) = onPhrase(callback) {
        val entropy = try {
            vault.loadEntropy()
        } catch (e: KeyVault.NeedsUnlock) {
            SecuredLog.i(Ev.PHRASE_REFUSED, "needs_unlock")
            return@onPhrase err(callback, ErrorMapper.needsUnlock())
        } catch (e: KeyVault.Invalidated) {
            SecuredLog.w(Ev.PHRASE_REFUSED, "invalidated")
            return@onPhrase err(callback, ErrorMapper.notAllowed(ErrorMapper.PHRASE_INVALIDATED))
        } catch (e: KeyVault.Missing) {
            return@onPhrase err(callback, ErrorMapper.notAllowed(ErrorMapper.BACKUP_OFF))
        } catch (e: KeyVault.Unavailable) {
            SecuredLog.w(Ev.PHRASE_REFUSED, e.reason)
            return@onPhrase err(callback, SecuredError(SecuredError.Kind.ENGINE, ErrorMapper.KEYSTORE))
        }
        try {
            SecuredLog.i(Ev.PHRASE_SHOWN)
            ok(callback, Bip39.toWords(entropy))
        } finally {
            entropy.fill(0)
        }
    }

    /** Skips the server-tag check, and on success makes this phone the backup phone again (clears otherPhone). */
    override fun backupNow(callback: SecuredCallback<Unit>) {
        if (backupClient == null) return err(callback, ErrorMapper.notAllowed(ErrorMapper.BACKUP_NOT_CONFIGURED))
        if (!backupState().enabled) return err(callback, ErrorMapper.notAllowed(ErrorMapper.BACKUP_OFF))
        if (state != EngineState.RUNNING) return err(callback, ErrorMapper.notRunning(state))
        onWorker(callback) { beginAttemptOnWorker("now", manual = true, callback = callback) }
    }

    /**
     * Turns backup off. The server copy's id is queued for deletion (with the tag of this phone's last upload) before
     * the DELETE, so a failed delete is retried later ([retryPendingDeletes]); the words and keys on this phone go
     * either way. Only this phone's own last copy is deleted: a copy another phone made with the same words stays
     * (R6.3, [deleteIfOurs]). When the server copy is still there, the result says so instead of claiming it was
     * deleted (finding 7).
     */
    override fun disableBackup(callback: SecuredCallback<Unit>) = onWorker(callback) {
        var unreadable = false
        val secrets = try {
            vault.loadSecrets()
        } catch (e: KeyVault.Unavailable) {
            unreadable = true
            null
        }
        var notDeleted: SecuredError? = null
        if (secrets != null) {
            val id = secrets.id
            secrets.wipe()
            val client = backupClient ?: return@onWorker err(callback, ErrorMapper.notAllowed(ErrorMapper.BACKUP_NOT_CONFIGURED))
            val tag = backupStore.serverTag(id)
            val queued = backupStore.addPendingDelete(id, tag)
            when (val outcome = deleteIfOurs(client, id, tag)) {
                DeleteOutcome.Deleted, DeleteOutcome.Gone ->
                    if (!backupStore.removePendingDelete(id)) SecuredLog.w(Ev.BACKUP_IO_FAILED, "delete_queue")
                DeleteOutcome.Kept -> {
                    if (!backupStore.removePendingDelete(id)) SecuredLog.w(Ev.BACKUP_IO_FAILED, "delete_queue")
                    notDeleted = SecuredError(SecuredError.Kind.NOT_ALLOWED, ErrorMapper.SERVER_COPY_KEPT)
                }
                is DeleteOutcome.Failed -> {
                    SecuredLog.w(Ev.BACKUP_FAILED, "delete_" + outcome.failure.name.lowercase(), outcome.code)
                    // Nothing could remember the id for a later try: change nothing, so the user can try again.
                    if (!queued) return@onWorker err(callback, SecuredError(SecuredError.Kind.NETWORK, ErrorMapper.SERVER_COPY_KEEP_ON))
                    notDeleted = SecuredError(SecuredError.Kind.NETWORK, ErrorMapper.SERVER_COPY_NOT_DELETED)
                }
            }
        } else if (unreadable) {
            // The id that names the server copy is gone with the key: it can't be deleted from here.
            SecuredLog.w(Ev.BACKUP_FAILED, "delete_unreadable")
            notDeleted = SecuredError(SecuredError.Kind.ENGINE, ErrorMapper.SERVER_COPY_UNREACHABLE)
        }
        if (!vault.deleteAll()) SecuredLog.w(Ev.BACKUP_IO_FAILED, "delete")
        backupStore.reset()
        cancelAutoTimer()
        SecuredLog.i(Ev.BACKUP_DISABLED, if (notDeleted != null) "server_copy_kept" else null)
        publishBackupState { BackupState(lastError = notDeleted?.message) }
        if (notDeleted != null) err(callback, notDeleted) else ok(callback, Unit)
        if (notDeleted == null) retryPendingDeletes()
    }

    /** Keeps the downloaded (still sealed) copy for a short while, so a restore right after it skips a second GET (R6.7). */
    override fun findBackup(words: List<String>, callback: SecuredCallback<BackupInfo>) = onWorker(callback) {
        val client = backupClient ?: return@onWorker err(callback, ErrorMapper.notAllowed(ErrorMapper.BACKUP_NOT_CONFIGURED))
        val secrets = Kdf.secretsFromWords(words) ?: return@onWorker err(callback, ErrorMapper.wrongPhrase())
        try {
            when (val f = fetchBackup(client, secrets, keep = true)) {
                is Fetched.Ok -> {
                    f.payload.archive.fill(0)
                    ok(callback, BackupInfo(f.payload.createdAtMs))
                }
                is Fetched.Failed -> err(callback, f.error)
            }
        } finally {
            secrets.wipe()
        }
    }

    override fun restore(words: List<String>, callback: SecuredCallback<Unit>) = onWorker(callback) { restoreOnWorker(words, callback) }

    override fun phraseSuggestions(prefix: String, limit: Int): List<String> = try {
        Bip39.suggestions(prefix, limit.coerceAtMost(Bip39.words.size))
    } catch (e: Exception) {
        emptyList()
    }

    private fun loadBackupState(): BackupState {
        val meta = backupStore.meta()
        return BackupState(
            enabled = vault.hasSecrets(),
            lastBackupMs = meta.lastBackupMs,
            lastError = ErrorMapper.backupErrorText(meta.error),
            running = false,
            otherPhone = meta.otherPhone,
        )
    }

    private fun publishBackupState(change: (BackupState) -> BackupState) {
        synchronized(backupLock) { backupStatus = change(backupState()) }
        main.post { for (l in listeners) l.onBackupStateChanged() }
    }

    /**
     * Phrase thread. Stores the words (screen-lock key) and the id+key (no-auth key), then asks the backup thread for
     * the first upload. A changed phrase queues the old server copy for deletion once the new words are stored, before
     * the first upload: it must not stay restorable with the old words, even when that upload fails (finding 7). When
     * storing the new words fails, nothing is queued and the old copy stays (R6.8).
     */
    private fun enableOnPhraseThread(words: List<String>, callback: SecuredCallback<Unit>) {
        if (backupClient == null) return err(callback, ErrorMapper.notAllowed(ErrorMapper.BACKUP_NOT_CONFIGURED))
        if (state != EngineState.RUNNING) return err(callback, ErrorMapper.notRunning(state))
        val entropy = Bip39.toEntropy(words) ?: return err(callback, ErrorMapper.wrongPhrase())
        val secrets = Kdf.secretsFromWords(words)
        try {
            if (secrets == null) return err(callback, ErrorMapper.wrongPhrase())
            if (!vault.deviceSecure()) return err(callback, ErrorMapper.notAllowed(ErrorMapper.NO_SCREEN_LOCK))
            val previous = try {
                vault.loadSecrets()
            } catch (e: KeyVault.Unavailable) {
                null
            }
            val previousId = previous?.id
            previous?.wipe()
            val sameBackup = previousId == secrets.id
            val obsoleteId = previousId?.takeIf { !sameBackup }
            // The tag of this phone's last upload of the old copy, read before the new backup forgets it (R6.3).
            val obsoleteTag = obsoleteId?.let { backupStore.serverTag(it) }
            // These words may have been turned off on this phone before: their copy is wanted again.
            backupStore.removePendingDelete(secrets.id)
            try {
                vault.storeEntropy(entropy)
                vault.storeSecrets(secrets)
            } catch (e: KeyVault.Unavailable) {
                // Never leave words of one phrase next to the keys of another: start again from nothing.
                vault.deleteAll()
                backupStore.reset()
                publishBackupState { BackupState(lastError = ErrorMapper.KEYSTORE) }
                SecuredLog.e(Ev.VAULT_FAILED, e.reason)
                return err(
                    callback,
                    if (e.reason == "no_screen_lock") ErrorMapper.notAllowed(ErrorMapper.NO_SCREEN_LOCK)
                    else SecuredError(SecuredError.Kind.ENGINE, ErrorMapper.KEYSTORE),
                )
            }
            // Both stored: only now may the old copy go (R6.8).
            val queued = obsoleteId == null || backupStore.addPendingDelete(obsoleteId, obsoleteTag)
            if (!sameBackup) {
                // A new backup: nothing uploaded yet, no tag, no pause, no backoff.
                backupStore.update { BackupStore.Meta(counter = it.counter, dirty = true) }
                backupStore.setServerTag(secrets.id, null)
            }
            publishBackupState {
                BackupState(
                    enabled = true,
                    lastBackupMs = if (sameBackup) it.lastBackupMs else null,
                    otherPhone = sameBackup && it.otherPhone,
                )
            }
            SecuredLog.i(Ev.BACKUP_ENABLED)
            if (obsoleteId != null && !queued) worker.execute { deleteOldCopyOnWorker(obsoleteId, obsoleteTag) }
            onWorker(callback) { beginAttemptOnWorker("enable", manual = true, callback = callback) }
        } finally {
            entropy.fill(0)
            secrets?.wipe()
        }
    }

    /**
     * Backup thread. One upload at a time:
     *  1. HEAD the server copy while the chat still runs: no answer means no pause and a later try (finding 5); a busy
     *     server (429/503) gets one more HEAD after [BUSY_RETRY_MS], not counted as a failure (R6.7);
     *  2. automatic uploads compare its tag with this phone's last one; when the tag doesn't prove it, the copy is
     *     downloaded and its counter decides ([verifyServerCopy], R6.2), so a copy that another phone made with the
     *     same words is never overwritten (finding 4) and a lost tag never stops uploads; [manual] ("Back up now",
     *     turning backup on) and the first upload after a restore skip that check;
     *  3. the export on the engine thread ([startBackupOnEngine]), then the upload back here ([uploadOnWorker]).
     * Every path ends in [failAttempt], [pauseForOtherPhone] or the success branch of [uploadOnWorker].
     */
    private fun beginAttemptOnWorker(reason: String, manual: Boolean, callback: SecuredCallback<Unit>?) {
        val client = backupClient ?: run {
            callback?.let { err(it, ErrorMapper.notAllowed(ErrorMapper.BACKUP_NOT_CONFIGURED)) }
            return
        }
        synchronized(backupLock) {
            if (attemptRunning) {
                // Manual requests get their own upload right after the running one ([endAttempt]).
                if (manual && callback != null) waitingManual.add(callback)
                return
            }
            attemptRunning = true
            firstUrgentMs = 0L
            autoTimer?.cancel(false)
            autoTimer = null
        }
        try {
            if (state != EngineState.RUNNING) return failAttempt(callback, ErrorMapper.notRunning(state), null, manual)
            val meta = backupStore.meta()
            if (!manual && BackupStore.autoPaused(meta)) return endAttempt()
            val secrets = try {
                vault.loadSecrets()
            } catch (e: KeyVault.Unavailable) {
                SecuredLog.e(Ev.VAULT_FAILED, e.reason)
                return failAttempt(callback, SecuredError(SecuredError.Kind.ENGINE, ErrorMapper.KEYSTORE), "keystore", manual)
            } ?: return failAttempt(callback, ErrorMapper.notAllowed(ErrorMapper.BACKUP_OFF), null, manual)
            val id = secrets.id
            if (!manual) backupStore.update { it.copy(lastAttemptMs = System.currentTimeMillis()) }
            SecuredLog.i(Ev.BACKUP_STARTED, reason)
            publishBackupState { it.copy(running = true) }
            var probe = client.head(id)
            if (probe is BackupClient.Probe.Failed && probe.failure == BackupClient.Failure.BUSY) {
                SecuredLog.w(Ev.BACKUP_FAILED, "probe_busy_retry", probe.code)
                sleepQuietly(BUSY_RETRY_MS)
                probe = client.head(id)
            }
            var check = BackupClient.tagDecision(probe, backupStore.serverTag(id), skipCheck = manual || meta.unconditional)
            if (check == BackupClient.Check.VERIFY) {
                val verified = try {
                    verifyServerCopy(client, secrets)
                } finally {
                    secrets.wipe()
                }
                check = verified.first
                verified.second?.let { probe = it }
            } else {
                secrets.wipe()
            }
            when (check) {
                BackupClient.Check.UPLOAD -> post {
                    try {
                        startBackupOnEngine(manual, callback)
                    } catch (t: Throwable) {
                        SecuredLog.crash(Ev.BACKUP_FAILED, t)
                        failAttempt(callback, SecuredError(SecuredError.Kind.ENGINE, ErrorMapper.BACKUP_FAILED), "internal", manual)
                    }
                }
                BackupClient.Check.OTHER_PHONE -> pauseForOtherPhone()
                // verifyServerCopy never returns VERIFY; kept for an exhaustive when.
                BackupClient.Check.VERIFY -> {
                    SecuredLog.w(Ev.BACKUP_FAILED, "no_tag")
                    failAttempt(callback, SecuredError(SecuredError.Kind.NETWORK, ErrorMapper.BACKUP_SERVER_ODD), "no_tag", manual)
                }
                BackupClient.Check.UNREACHABLE -> {
                    val f = probe as? BackupClient.Probe.Failed ?: BackupClient.Probe.Failed(BackupClient.Failure.NETWORK, 0)
                    SecuredLog.w(Ev.BACKUP_FAILED, "probe_" + f.failure.name.lowercase(), f.code)
                    failAttempt(callback, ErrorMapper.backupHttp(f.failure.name, f.code), failureCode(f.failure, f.code), manual)
                }
            }
        } catch (t: Throwable) {
            SecuredLog.crash(Ev.BACKUP_FAILED, t)
            failAttempt(callback, SecuredError(SecuredError.Kind.ENGINE, ErrorMapper.BACKUP_FAILED), "internal", manual)
        }
    }

    private class Exported(val archive: ByteArray, val dbKey: String, val createdAtMs: Long)

    /**
     * Backup thread, R6.2 part B: the stored copy's tag doesn't prove it is this phone's (another tag, none stored or
     * none sent). Download it once, open it and compare its counter with this phone's ([BackupClient.counterDecision]).
     * Ours: keep its tag and upload. Higher: another phone uploaded after us. A copy nobody can open (damaged or not
     * ours to read) can't be restored by anyone, so it is replaced. Returns the check and, for UNREACHABLE, the failure.
     */
    private fun verifyServerCopy(client: BackupClient, secrets: BackupSecrets): Pair<BackupClient.Check, BackupClient.Probe.Failed?> {
        val download = try {
            client.get(secrets.id)
        } catch (e: BackupClient.HttpFailure) {
            // Gone since the HEAD: nothing to overwrite.
            if (e.failure == BackupClient.Failure.NOT_FOUND) return BackupClient.Check.UPLOAD to null
            SecuredLog.w(Ev.BACKUP_FAILED, "verify_" + e.failure.name.lowercase(), e.code)
            return BackupClient.Check.UNREACHABLE to BackupClient.Probe.Failed(e.failure, e.code)
        }
        return when (val o = Envelope.open(secrets.key, download.body)) {
            is Envelope.Opened.Ok -> {
                val counter = o.payload.counter
                o.payload.archive.fill(0)
                backupStore.noteSeen(secrets.id, counter)
                val decision = BackupClient.counterDecision(counter, backupStore.meta().counter)
                if (decision == BackupClient.Check.UPLOAD) {
                    if (!backupStore.setServerTag(secrets.id, download.etag)) SecuredLog.w(Ev.BACKUP_IO_FAILED, "tag")
                    SecuredLog.i(Ev.BACKUP_STARTED, "verify_ours")
                } else {
                    SecuredLog.w(Ev.BACKUP_FAILED, "verify_other")
                }
                decision to null
            }
            Envelope.Opened.BadKey, Envelope.Opened.BadFormat -> {
                SecuredLog.w(Ev.BACKUP_FAILED, "verify_damaged")
                BackupClient.Check.UPLOAD to null
            }
        }
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** What happened to a server copy we wanted gone ([deleteIfOurs]). */
    private sealed class DeleteOutcome {
        object Deleted : DeleteOutcome()
        /** Nothing stored. */
        object Gone : DeleteOutcome()
        /** Another phone's copy, or one this phone can't prove is its own: left alone. */
        object Kept : DeleteOutcome()
        class Failed(val failure: BackupClient.Failure, val code: Int) : DeleteOutcome()
    }

    /**
     * Backup thread, R6.3: HEAD first, DELETE only when the stored copy carries [tag], the tag of this phone's last
     * upload of [id]. A copy another phone uploaded with the same words is never deleted from here.
     */
    private fun deleteIfOurs(client: BackupClient, id: String, tag: String?): DeleteOutcome {
        val probe = client.head(id)
        return when (BackupClient.deleteDecision(probe, tag)) {
            BackupClient.Del.GONE -> DeleteOutcome.Gone
            BackupClient.Del.KEEP -> DeleteOutcome.Kept
            BackupClient.Del.RETRY -> {
                val f = probe as? BackupClient.Probe.Failed ?: BackupClient.Probe.Failed(BackupClient.Failure.NETWORK, 0)
                DeleteOutcome.Failed(f.failure, f.code)
            }
            BackupClient.Del.DELETE -> try {
                client.delete(id)
                DeleteOutcome.Deleted
            } catch (e: BackupClient.HttpFailure) {
                DeleteOutcome.Failed(e.failure, e.code)
            }
        }
    }

    /**
     * Engine thread. One backup: stop the chat, point the files folder at an empty folder (text-only backup), export,
     * put the folders back, start the chat again, then hand the archive to the backup thread for sealing and upload.
     * Any failure to resume stops Secured (fail closed) rather than leave it half configured.
     */
    private fun startBackupOnEngine(manual: Boolean, callback: SecuredCallback<Unit>?) {
        if (state != EngineState.RUNNING || ctrl == 0L || !chatStarted) {
            return failAttempt(callback, ErrorMapper.notRunning(state), null, manual)
        }
        if (!vault.hasSecrets()) return failAttempt(callback, ErrorMapper.notAllowed(ErrorMapper.BACKUP_OFF), null, manual)
        val dbKey = try {
            DbKeyStore(appContext).obtain(databaseExists = true)
        } catch (f: DbKeyStore.KeyFailure) {
            return failAttempt(callback, SecuredError(SecuredError.Kind.ENGINE, ErrorMapper.KEYSTORE), "keystore", manual)
        }
        // Everything up to now is in this export; later changes set the flag (and count as new urgent requests) again.
        backupStore.update { it.copy(dirty = false) }
        val seq = synchronized(backupLock) { urgentSeq }
        val createdAt = System.currentTimeMillis()
        val dir = dataDir
        val temp = File(dir, "tmp")
        val work = File(temp, "backup-" + randomTag())
        val emptyFiles = File(work, "files")
        val emptyAssets = File(work, "assets")
        val zip = File(temp, "backup-" + randomTag() + ".zip")
        var archive: ByteArray? = null
        var failure: SecuredError? = null
        var failTag: String? = null

        // 1. Export needs a stopped chat (Commands.hs:610 checkChatStopped).
        send(Commands.STOP_CHAT)
        chatStarted = false
        try {
            // /_stop cancels the agent's threads in a forked thread (Commands.hs:353): let them finish first.
            Thread.sleep(STOP_SETTLE_MS)
            // 2. exportArchive copies the files folder and assets/wallpapers (Archive.hs:61-65, listDirectory fails
            //    on a missing folder): point both at empty folders that exist.
            if (!File(emptyAssets, "wallpapers").mkdirs() || !emptyFiles.mkdirs()) throw IllegalStateException("mkdir")
            if (!isResult(send(filePathsCommand(emptyFiles, temp, emptyAssets)), "cmdOk")) {
                failTag = "file_paths"
            } else {
                // 3. The zip of both SQLCipher databases (Archive.hs:55-70).
                val r = send(Commands.exportArchive(zip.absolutePath, temp.absolutePath))
                if (r is CoreReply.Result && r.type == "archiveExported") {
                    val errors = CoreJson.archiveErrorCount(r.body)
                    if (errors > 0) SecuredLog.w(Ev.BACKUP_EXPORTED, "file_errors", errors)
                    val size = zip.length()
                    if (!zip.isFile || size <= 0) {
                        failTag = "export_empty"
                    } else if (size > Envelope.MAX_ARCHIVE_BYTES) {
                        failTag = BackupStore.TOO_LARGE
                        failure = SecuredError(SecuredError.Kind.ENGINE, ErrorMapper.BACKUP_TOO_LARGE)
                    } else {
                        archive = zip.readBytes()
                        SecuredLog.i(Ev.BACKUP_EXPORTED)
                    }
                } else {
                    failTag = "export_" + replyTag(r)
                }
            }
        } catch (t: Throwable) {
            SecuredLog.crash(Ev.BACKUP_FAILED, t)
            failTag = failTag ?: "export_internal"
        } finally {
            // 4. Normal folders back, plaintext gone, chat running again.
            val pathsBack = isResult(send(filePathsCommand(File(dir, "files"), temp, File(dir, "assets"))), "cmdOk")
            zip.delete()
            work.deleteRecursively()
            if (!pathsBack || !resumeAfterPause()) {
                archive?.fill(0)
                archive = null
                if (pathsBack) SecuredLog.e(Ev.BACKUP_FAILED, "resume") else SecuredLog.e(Ev.BACKUP_FAILED, "file_paths_back")
                abortAfterPause(if (pathsBack) "resume" else "file_paths_back")
            }
        }
        val bytes = archive
        if (bytes == null) {
            SecuredLog.e(Ev.BACKUP_FAILED, failTag ?: "export")
            val code = if (failTag == BackupStore.TOO_LARGE) BackupStore.TOO_LARGE else "export"
            return failAttempt(callback, failure ?: SecuredError(SecuredError.Kind.ENGINE, ErrorMapper.BACKUP_FAILED), code, manual)
        }
        val exported = Exported(bytes, dbKey, createdAt)
        worker.execute {
            try {
                uploadOnWorker(exported, manual, callback, seq)
            } catch (t: Throwable) {
                SecuredLog.crash(Ev.BACKUP_FAILED, t)
                exported.archive.fill(0)
                failAttempt(callback, SecuredError(SecuredError.Kind.ENGINE, ErrorMapper.BACKUP_FAILED), "internal", manual)
            }
        }
    }

    /**
     * Backup thread: seal with the backup key, PUT with a `Date` header and keep the copy's tag that the PUT returns
     * ([BackupClient.put], computed like nginx does: no HEAD that could fail and leave no tag, R6.2). A busy server gets
     * one more PUT after [BUSY_RETRY_MS] (R6.7). The plaintext archive is wiped from memory afterwards. [seq]: the
     * urgent request count when the export ran; a newer request keeps the next upload owed.
     */
    private fun uploadOnWorker(ex: Exported, manual: Boolean, callback: SecuredCallback<Unit>?, seq: Long) {
        val client = backupClient
        var unreadable = false
        val secrets = try {
            vault.loadSecrets()
        } catch (e: KeyVault.Unavailable) {
            SecuredLog.e(Ev.VAULT_FAILED, e.reason)
            unreadable = true
            null
        }
        try {
            if (client == null) return failAttempt(callback, ErrorMapper.notAllowed(ErrorMapper.BACKUP_NOT_CONFIGURED), null, manual)
            if (unreadable) return failAttempt(callback, SecuredError(SecuredError.Kind.ENGINE, ErrorMapper.KEYSTORE), "keystore", manual)
            // Turned off while exporting: nothing to upload.
            if (secrets == null) return failAttempt(callback, ErrorMapper.notAllowed(ErrorMapper.BACKUP_OFF), null, manual)
            val counter = maxOf(backupStore.meta().counter, backupStore.highestSeen(secrets.id) ?: 0L) + 1
            if (!backupStore.update { it.copy(counter = counter) }) {
                return failAttempt(callback, SecuredError(SecuredError.Kind.ENGINE, ErrorMapper.STORAGE), "storage", manual)
            }
            val sealed = Envelope.seal(secrets.key, Envelope.Payload(ex.createdAtMs, counter, ex.dbKey, ex.archive))
            ex.archive.fill(0)
            val tag = try {
                putOnce(client, secrets.id, sealed)
            } catch (e: BackupClient.HttpFailure) {
                if (e.failure != BackupClient.Failure.BUSY) throw e
                SecuredLog.w(Ev.BACKUP_FAILED, "upload_busy_retry", e.code)
                sleepQuietly(BUSY_RETRY_MS)
                // A second failure is reported (and counted) as usual by the catch below.
                putOnce(client, secrets.id, sealed)
            }
            backupStore.noteSeen(secrets.id, counter)
            if (!backupStore.setServerTag(secrets.id, tag)) SecuredLog.w(Ev.BACKUP_IO_FAILED, "tag")
            // One block with requestUrgentBackup's: a request between reading the count and writing the flag would
            // otherwise be lost (R6.4). Lock order backupLock -> store, as in scheduleAuto.
            synchronized(backupLock) {
                val stillOwed = urgentSeq != seq
                backupStore.update {
                    it.copy(
                        lastBackupMs = ex.createdAtMs, error = null, failures = 0, otherPhone = false, unconditional = false,
                        urgent = it.urgent && stillOwed,
                    )
                }
            }
            SecuredLog.i(Ev.BACKUP_UPLOADED, count = sealed.size / 1024)
            publishBackupState { it.copy(running = false, lastBackupMs = ex.createdAtMs, lastError = null, otherPhone = false) }
            callback?.let { ok(it, Unit) }
            endAttempt()
        } catch (e: BackupClient.HttpFailure) {
            SecuredLog.w(Ev.BACKUP_FAILED, "upload_" + e.failure.name.lowercase(), e.code)
            failAttempt(callback, ErrorMapper.backupHttp(e.failure.name, e.code), failureCode(e.failure, e.code), manual)
        } finally {
            ex.archive.fill(0)
            secrets?.wipe()
        }
    }

    /** One PUT dated now (whole seconds); returns the stored copy's tag. */
    private fun putOnce(client: BackupClient, id: String, sealed: ByteArray): String =
        client.put(id, sealed, System.currentTimeMillis() / 1000L)

    /** Our error code for a failed request (ErrorMapper.backupErrorText). */
    private fun failureCode(f: BackupClient.Failure, code: Int): String = when (f) {
        BackupClient.Failure.NETWORK -> "network"
        BackupClient.Failure.BUSY -> "busy"
        BackupClient.Failure.TOO_LARGE -> BackupStore.TOO_LARGE
        else -> if (code in 100..999) "server:$code" else "server"
    }

    /**
     * A failed attempt: still owed, the error for the settings screen and, for automatic ones, one more failure for
     * the backoff (BackupStore.backoffMs). [code] null: nothing to record (not running, turned off).
     */
    private fun failAttempt(callback: SecuredCallback<Unit>?, error: SecuredError, code: String?, manual: Boolean) {
        if (code != null) backupStore.update { it.copy(dirty = true, error = code, failures = if (manual) it.failures else it.failures + 1) }
        publishBackupState { it.copy(running = false, lastError = if (code != null) ErrorMapper.backupErrorText(code) else it.lastError) }
        callback?.let { err(it, error) }
        endAttempt()
    }

    /** Another phone uploaded with these words after this phone's last upload: pause automatic uploads (finding 4). */
    private fun pauseForOtherPhone() {
        SecuredLog.w(Ev.BACKUP_FAILED, BackupStore.OTHER_PHONE)
        backupStore.update { it.copy(otherPhone = true, error = BackupStore.OTHER_PHONE) }
        publishBackupState { it.copy(running = false, otherPhone = true, lastError = ErrorMapper.OTHER_PHONE) }
        endAttempt()
    }

    /** Any thread. The attempt is over: waiting manual requests go next; otherwise the queued deletes and the next timer. */
    private fun endAttempt() {
        val waiting: List<SecuredCallback<Unit>>
        synchronized(backupLock) {
            attemptRunning = false
            waiting = ArrayList(waitingManual)
            waitingManual.clear()
        }
        worker.execute {
            try {
                if (waiting.isNotEmpty()) {
                    val all = SecuredCallback<Unit> { result -> for (c in waiting) c.onResult(result) }
                    beginAttemptOnWorker("now", manual = true, callback = all)
                } else {
                    retryPendingDeletes()
                    scheduleAuto(0L)
                }
            } catch (t: Throwable) {
                SecuredLog.crash(Ev.BACKUP_FAILED, t)
            }
        }
    }

    /** The chat couldn't be resumed after a backup: close everything so the next start() opens it from scratch. */
    private fun abortAfterPause(tag: String) {
        stopReceiver()
        stopChatQuietly()
        closeCtrlQuietly()
        userId = 0L
        chatCache.clear()
        lastItemIds.clear()
        timedChats.clear()
        groupCache.clear()
        hiddenChats = emptySet()
        earlierProofs = emptyMap()
        ownSentCache.clear()
        ownHistorySwept.clear()
        memberIdCache.clear()
        noteFolderId = null
        publishChats()
        notifyChatsChanged()
        failClosed(StartFailure(SecuredError(SecuredError.Kind.ENGINE, "Secured stopped for safety after a backup. Open it again to restart."), tag))
    }

    /** After an export: the network settings checked again, then the chat started. False when either failed. */
    private fun resumeAfterPause(): Boolean = try {
        val net = expectResult(send(Commands.NETWORK_CONFIG), "network_resume", "networkConfig")
        if (!ServerConfigPlanner.networkConfigOk(net.body)) throw configFailure("network_resume_verify", net)
        chatStarted = true
        expectResult(send(Commands.START_CHAT), "start_resume", "chatStarted", "chatRunning")
        true
    } catch (t: Throwable) {
        if (t !is StartFailure) SecuredLog.crash(Ev.BACKUP_FAILED, t)
        false
    }

    /** Engine thread, after any change that is part of the backup: due for the daily backup. */
    private fun markChanged() {
        if (!backupState().enabled) return
        if (!backupStore.meta().dirty) backupStore.update { it.copy(dirty = true) }
        scheduleAuto(0L)
    }

    /**
     * Any thread. An automatic upload is owed soon: a new contact, a finished queue switch or a completed repair sync
     * (item 1). Coalesced: each request in a burst moves the timer to [delayMs] from now, but never later than
     * [MAX_COALESCE_MS] after the burst's first request, so several switches in a row give one upload.
     */
    private fun requestUrgentBackup(reason: String, delayMs: Long) {
        if (backupClient == null || !backupState().enabled) return
        // The count and the flag together, like the upload's success block reads and clears them (R6.4).
        synchronized(backupLock) {
            urgentSeq++
            val m = backupStore.meta()
            if (!m.urgent || !m.dirty) backupStore.update { it.copy(urgent = true, dirty = true) }
        }
        SecuredLog.i(Ev.BACKUP_STARTED, "queued_$reason")
        scheduleAuto(delayMs)
    }

    /**
     * Any thread. The one timer for the next automatic upload, from BackupStore.autoDueMs (urgent now, daily when
     * changed, backoff after failures, none while paused). [debounceMs] > 0: an urgent request that pushes the timer
     * (see [requestUrgentBackup]); otherwise an earlier timer is kept.
     */
    private fun scheduleAuto(debounceMs: Long) {
        synchronized(backupLock) {
            if (backupClient == null || !backupState().enabled) return cancelAutoTimerLocked()
            if (attemptRunning) return   // endAttempt schedules again
            val now = System.currentTimeMillis()
            val meta = backupStore.meta()
            val due = BackupStore.autoDueMs(meta, now) ?: return cancelAutoTimerLocked()
            var at = maxOf(due, now + if (meta.urgent) AUTO_MIN_DELAY_MS else MIN_TIMER_MS)
            if (debounceMs > 0L) {
                if (firstUrgentMs == 0L) firstUrgentMs = now
                at = maxOf(at, minOf(now + debounceMs, firstUrgentMs + MAX_COALESCE_MS))
            } else if (autoTimer?.isDone == false && autoTimerAtMs <= at) {
                return
            }
            autoTimer?.cancel(false)
            autoTimerAtMs = at
            val gen = ++autoTimerGen
            autoTimer = worker.schedule({ autoTimerFired(gen) }, at - now, TimeUnit.MILLISECONDS)
        }
    }

    /** Backup thread: the timer fired. Re-checks that an upload is still due, then starts it. */
    private fun autoTimerFired(gen: Long) {
        try {
            synchronized(backupLock) {
                if (gen != autoTimerGen) return   // replaced by a newer timer
                autoTimer = null
                firstUrgentMs = 0L                // this burst's timer has fired
            }
            // Not running: afterRunning() schedules again at the next start.
            if (state != EngineState.RUNNING || !backupState().enabled) return
            val meta = backupStore.meta()
            val now = System.currentTimeMillis()
            val due = BackupStore.autoDueMs(meta, now) ?: return
            if (due > now + TIMER_SLACK_MS) return scheduleAuto(0L)
            beginAttemptOnWorker(if (meta.urgent) "urgent" else "daily", manual = false, callback = null)
        } catch (t: Throwable) {
            SecuredLog.crash(Ev.BACKUP_FAILED, t)
        }
    }

    private fun cancelAutoTimer() = synchronized(backupLock) { cancelAutoTimerLocked() }

    private fun cancelAutoTimerLocked() {
        autoTimer?.cancel(false)
        autoTimer = null
        firstUrgentMs = 0L
    }

    /**
     * Backup thread. Deletes server copies queued by a failed "turn off" or left by a phrase change (finding 7), but
     * only copies this phone uploaded last ([deleteIfOurs], R6.3); another phone's copy is dropped from the queue and
     * kept. The current backup's id is never deleted. Stops at the first network failure; the next attempt tries again.
     */
    private fun retryPendingDeletes() {
        val client = backupClient ?: return
        val entries = backupStore.pendingDeleteEntries()
        if (entries.isEmpty()) return
        val current = try {
            vault.loadSecrets()
        } catch (e: KeyVault.Unavailable) {
            null
        }
        val currentId = current?.id
        current?.wipe()
        for (entry in entries) {
            val id = entry.id
            if (id == currentId) {
                backupStore.removePendingDelete(id)
                continue
            }
            when (val outcome = deleteIfOurs(client, id, entry.tag)) {
                DeleteOutcome.Deleted -> {
                    backupStore.removePendingDelete(id)
                    SecuredLog.i(Ev.BACKUP_DISABLED, "old_copy_deleted")
                }
                DeleteOutcome.Gone -> backupStore.removePendingDelete(id)
                DeleteOutcome.Kept -> {
                    backupStore.removePendingDelete(id)
                    SecuredLog.w(Ev.BACKUP_DISABLED, "old_copy_kept")
                }
                is DeleteOutcome.Failed -> {
                    SecuredLog.w(Ev.BACKUP_FAILED, "delete_queued_" + outcome.failure.name.lowercase(), outcome.code)
                    if (outcome.failure == BackupClient.Failure.NETWORK || outcome.failure == BackupClient.Failure.BUSY) return
                }
            }
        }
    }

    /** Backup thread: best effort for an old copy that couldn't be queued; only if it is still this phone's (R6.3). */
    private fun deleteOldCopyOnWorker(id: String, tag: String?) {
        val client = backupClient ?: return
        val outcome = deleteIfOurs(client, id, tag)
        if (outcome is DeleteOutcome.Failed) SecuredLog.w(Ev.BACKUP_FAILED, "delete_old", outcome.code)
        if (outcome == DeleteOutcome.Kept) SecuredLog.w(Ev.BACKUP_DISABLED, "old_copy_kept")
    }

    /** A sealed envelope findBackup downloaded (backup thread only); [key] is the one-way hash of its id. */
    private class FoundDownload(val key: String, val download: BackupClient.Download, val atMs: Long)

    // backup thread only
    private var foundDownload: FoundDownload? = null

    private sealed class Fetched {
        class Ok(val payload: Envelope.Payload, val etag: String?) : Fetched()
        class Failed(val error: SecuredError) : Fetched()
    }

    /**
     * Backup thread: download, open and check the counter (a copy older than one already seen is refused). [keep]:
     * remember the sealed download for [FOUND_CACHE_MS] (findBackup); [reuse]: take that download instead of a second
     * GET (the restore right after a find, R6.7). Only the sealed envelope is kept, never the opened archive.
     */
    private fun fetchBackup(client: BackupClient, secrets: BackupSecrets, keep: Boolean = false, reuse: Boolean = false): Fetched {
        val key = BackupStore.seenKey(secrets.id)
        val cached = foundDownload?.takeIf { reuse && it.key == key && System.currentTimeMillis() - it.atMs < FOUND_CACHE_MS }
        foundDownload = null
        val download = cached?.download ?: try {
            client.get(secrets.id)
        } catch (e: BackupClient.HttpFailure) {
            SecuredLog.w(Ev.BACKUP_LOOKUP_FAILED, e.failure.name.lowercase(), e.code)
            return Fetched.Failed(ErrorMapper.backupHttp(e.failure.name, e.code))
        }
        return when (val o = Envelope.open(secrets.key, download.body)) {
            is Envelope.Opened.Ok -> {
                val p = o.payload
                if (Envelope.isRollback(p.counter, backupStore.highestSeen(secrets.id))) {
                    p.archive.fill(0)
                    SecuredLog.w(Ev.BACKUP_LOOKUP_FAILED, "rollback")
                    Fetched.Failed(SecuredError(SecuredError.Kind.ENGINE, ErrorMapper.BACKUP_ROLLBACK))
                } else {
                    backupStore.noteSeen(secrets.id, p.counter)
                    SecuredLog.i(Ev.BACKUP_LOOKUP, if (cached != null) "reused" else null)
                    if (keep) foundDownload = FoundDownload(key, download, System.currentTimeMillis())
                    Fetched.Ok(p, download.etag)
                }
            }
            Envelope.Opened.BadKey, Envelope.Opened.BadFormat -> {
                SecuredLog.w(Ev.BACKUP_LOOKUP_FAILED, "damaged")
                Fetched.Failed(SecuredError(SecuredError.Kind.ENGINE, ErrorMapper.BACKUP_DAMAGED))
            }
        }
    }

    /** Backup thread: checks, download and decryption; the database work then runs on the engine thread. */
    private fun restoreOnWorker(words: List<String>, callback: SecuredCallback<Unit>) {
        val client = backupClient ?: return err(callback, ErrorMapper.notAllowed(ErrorMapper.BACKUP_NOT_CONFIGURED))
        when (state) {
            EngineState.NEEDS_PROFILE -> Unit
            EngineState.RUNNING -> return err(callback, ErrorMapper.notAllowed(ErrorMapper.PROFILE_EXISTS))
            else -> return err(callback, ErrorMapper.notRunning(state))
        }
        val entropy = Bip39.toEntropy(words) ?: return err(callback, ErrorMapper.wrongPhrase())
        val secrets = Kdf.secretsFromWords(words)
        var handedOver = false
        try {
            if (secrets == null) return err(callback, ErrorMapper.wrongPhrase())
            // Checked first: the restored words must be stored behind the screen lock so backup stays on.
            if (!vault.deviceSecure()) return err(callback, ErrorMapper.notAllowed(ErrorMapper.NO_SCREEN_LOCK))
            val fetched = when (val f = fetchBackup(client, secrets, reuse = true)) {
                is Fetched.Ok -> f
                is Fetched.Failed -> return err(callback, f.error)
            }
            handedOver = true
            post(callback) { restoreOnEngine(fetched.payload, fetched.etag, secrets, entropy, callback) }
        } finally {
            if (!handedOver) {
                entropy.fill(0)
                secrets?.wipe()
            }
        }
    }

    private class RestoreFailure(val error: SecuredError, val tag: String) : Exception(tag)

    /**
     * Engine thread. The import sequence (research/v2-plan-review.md §5.3 and the Fable review in
     * docs/recovery-phrase-plan.md):
     *  1. `/_db import` with the chat stopped: it closes both stores, keeps `.bak` copies and copies the backup's
     *     databases in (Archive.hs:72-85), then marks the store changed, so this controller is finished;
     *  2. close it (chatCloseStore is safe on closed stores, simplexmq Agent/Store/SQLite.hs:151-156), store the
     *     backup's passphrase (DbKeyStore.replace, commit() before init), chatMigrateInit with it;
     *  3. `/_db encryption` to a fresh random passphrase (Archive.hs:149-175), so the restored files don't stay under the
     *     old phone's key, store it, close that controller too. (Every later backup carries this key inside its
     *     envelope, so the words open the copy that holds it, as they always do.);
     *  4. delete the `.bak`/`.exported` copies and the zip, open a new controller with the fresh key;
     *  5. list the contacts and groups to repair (groups as placeholders, expanded into member connections once
     *     RUNNING), store the words, keys and server tag (backup stays on, one upload owed at once),
     *     and only then clear the restore marker ([RestoreRepair.commitRestore], finding 8): before that, a crash or a
     *     failed step is wiped at the next start, see [restoreMarker]; after it, nothing wipes the restore;
     *  6. lock the network until our servers are verified, and start like any other start ([configureAndRun]).
     */
    private fun restoreOnEngine(payload: Envelope.Payload, etag: String?, secrets: BackupSecrets, entropy: ByteArray, callback: SecuredCallback<Unit>) {
        try {
            when (state) {
                EngineState.NEEDS_PROFILE -> Unit
                EngineState.RUNNING -> return err(callback, ErrorMapper.notAllowed(ErrorMapper.PROFILE_EXISTS))
                else -> return err(callback, ErrorMapper.notRunning(state))
            }
            if (ctrl == 0L) return err(callback, ErrorMapper.notRunning(state))
            SecuredLog.i(Ev.RESTORE_STARTED)
            setState(EngineState.STARTING)
            try {
                importBackupOnEngine(payload)
            } catch (t: Throwable) {
                val f = t as? RestoreFailure ?: run {
                    SecuredLog.crash(Ev.RESTORE_FAILED, t)
                    RestoreFailure(SecuredError(SecuredError.Kind.ENGINE, ErrorMapper.RESTORE_FAILED), "internal")
                }
                SecuredLog.e(Ev.RESTORE_FAILED, f.tag)
                backToEmptyProfile()
                return err(callback, f.error)
            }

            // The databases are final and open with the fresh key; the marker still stands.
            try {
                startReceiver()
                val user = activeUser()
                if (user == null) {
                    // A backup without a profile: nothing was restored, so offer a fresh start again.
                    SecuredLog.e(Ev.RESTORE_FAILED, "no_user")
                    backToEmptyProfile()
                    return err(callback, SecuredError(SecuredError.Kind.ENGINE, ErrorMapper.BACKUP_DAMAGED))
                }
                userId = user.userId
                val r = send(Commands.getChats(user.userId))
                if (r !is CoreReply.Result || r.type != "apiChats") {
                    // Without the list no contact would be repaired: fail rather than leave them on old ratchets.
                    SecuredLog.e(Ev.RESTORE_FAILED, "chats_" + replyTag(r))
                    backToEmptyProfile()
                    return err(callback, SecuredError(SecuredError.Kind.ENGINE, ErrorMapper.RESTORE_FAILED))
                }
                val chats = CoreJson.parseChats(r.body)
                val contacts = chats.filter { !it.contact.isContactCard && !it.contact.removed }.map { RepairKey.contact(it.contact.contactId) }
                // Every group member connection joins the same repair, keyed apart from contacts: `/_info #g <memberId>`,
                // one `/_sync #g <memberId> force=on` (Commands.hs:5572), `/_switch #g <memberId>` after proof, and the
                // groupMemberRatchetSync / groupMemberSwitch events (Controller.hs:926-928). The chat isn't started
                // here, and the core refuses `/_members` until it is (8 Nov device test: chatNotStarted), so each
                // group goes on the list as one placeholder, expanded into its members once RUNNING
                // ([expandGroupPlaceholders]). Saved before the marker is cleared, like the contacts.
                val groups = CoreJson.parseGroupChats(r.body)
                val groupKeys = RestoreRepair.groupPlaceholders(groups.map { it.group })
                // Our messages with a higher item id than every restored one were sent after the restore (receipt proof).
                // Item ids are one counter across all chats, so groups and notes count too.
                val since = (chats.mapNotNull { it.lastItem?.itemId } + groups.mapNotNull { it.lastItem?.itemId } +
                    listOfNotNull(CoreJson.parseNoteChat(r.body)?.lastItem?.itemId)).maxOrNull() ?: 0L
                // The restored databases carry the old phone's server list: lock the network until ours are verified.
                if (!markFirstRunLockdown()) SecuredLog.w(Ev.CONFIG_STEP_FAILED, "lockdown_mark")
                val failed = RestoreRepair.commitRestore(
                    saveRepairList = { saveRepairList(contacts + groupKeys, since) },
                    storeKeys = { storeRestoredSecrets(payload, etag, secrets, entropy) },
                    clearMarker = { clearRestoreMarker() },
                )
                if (failed != null) {
                    SecuredLog.e(Ev.RESTORE_FAILED, failed)
                    backToEmptyProfile()
                    val msg = if (failed == "keys") ErrorMapper.KEYSTORE else ErrorMapper.STORAGE
                    return err(callback, SecuredError(SecuredError.Kind.ENGINE, msg))
                }
                SecuredLog.i(Ev.RESTORE_DONE, "contacts", contacts.size)
                SecuredLog.i(Ev.RESTORE_DONE, "groups", groupKeys.size)
                configureAndRun(user)
            } catch (f: StartFailure) {
                failClosed(f)
                return err(callback, f.error)
            }
            ok(callback, Unit)
        } catch (t: Throwable) {
            SecuredLog.crash(Ev.RESTORE_FAILED, t)
            val f = StartFailure(ErrorMapper.engine("internal"), "internal")
            failClosed(f)
            err(callback, f.error)
        } finally {
            payload.archive.fill(0)
            entropy.fill(0)
            secrets.wipe()
        }
    }

    /** Steps 1-4 of [restoreOnEngine]. Throws [RestoreFailure]; the caller wipes (the marker is still there). */
    private fun importBackupOnEngine(payload: Envelope.Payload) {
        val dir = dataDir
        if (!markRestoreInProgress()) throw RestoreFailure(SecuredError(SecuredError.Kind.ENGINE, ErrorMapper.STORAGE), "marker")
        val temp = File(dir, "tmp").apply { mkdirs() }
        val zip = File(temp, "restore-" + randomTag() + ".zip")
        try {
            try {
                zip.writeBytes(payload.archive)
            } catch (e: Exception) {
                throw RestoreFailure(SecuredError(SecuredError.Kind.ENGINE, ErrorMapper.STORAGE), "zip_write")
            }
            payload.archive.fill(0)
            // A profile-less engine never started its chat; stop it anyway if it did (import needs it stopped).
            stopChatQuietly()
            val r = send(Commands.importArchive(zip.absolutePath, temp.absolutePath))
            if (r !is CoreReply.Result || r.type != "archiveImported") {
                throw RestoreFailure(SecuredError(SecuredError.Kind.ENGINE, ErrorMapper.BACKUP_DAMAGED), "import_" + replyTag(r))
            }
            val errors = CoreJson.archiveErrorCount(r.body)
            if (errors > 0) SecuredLog.w(Ev.RESTORE_STARTED, "file_errors", errors)
            zip.delete()

            stopReceiver()
            closeCtrlQuietly()
            // The store was replaced: file ids start again, so every verdict and transient file goes.
            media.onStoreReplaced()
            val keys = DbKeyStore(appContext)
            try {
                keys.replace(payload.dbKey)
            } catch (f: DbKeyStore.KeyFailure) {
                throw RestoreFailure(SecuredError(SecuredError.Kind.ENGINE, ErrorMapper.KEYSTORE), f.reason)
            }
            val prefix = File(dir, DB_PREFIX).absolutePath
            val (tag, c) = migrateInit(prefix, payload.dbKey)
            if (tag != "ok" || c == 0L) {
                if (c != 0L) closeQuietly(c)
                val msg = if (tag == "errorMigration") ErrorMapper.BACKUP_NEWER_APP else ErrorMapper.BACKUP_DAMAGED
                throw RestoreFailure(SecuredError(SecuredError.Kind.ENGINE, msg), "open_$tag")
            }
            // Only for re-keying: the gate stays closed until openDatabase reopens it with the final controller.
            ctrlGate.close { ctrl = c }
            val fresh = keys.freshPassphrase()
            val enc = send(Commands.storageEncryption(payload.dbKey, fresh))
            if (enc !is CoreReply.Result || enc.type != "cmdOk") {
                throw RestoreFailure(SecuredError(SecuredError.Kind.ENGINE, ErrorMapper.BACKUP_FAILED), "rekey_" + replyTag(enc))
            }
            try {
                keys.replace(fresh)
            } catch (f: DbKeyStore.KeyFailure) {
                throw RestoreFailure(SecuredError(SecuredError.Kind.ENGINE, ErrorMapper.KEYSTORE), f.reason)
            }
            closeCtrlQuietly()
            deleteDatabaseCopies(dir)
            try {
                openDatabase()
            } catch (f: StartFailure) {
                throw RestoreFailure(f.error, "reopen_" + f.logTag)
            }
            // Not committed yet: restoreOnEngine clears the marker after the repair list and keys (finding 8).
        } finally {
            zip.delete()
        }
    }

    /** After a restore failed before its commit point: drop the partial databases and offer a fresh start again. */
    private fun backToEmptyProfile() {
        try {
            userId = 0L
            wipePartialRestore()
            openDatabase()
            startReceiver()
            currentProfile = null
            setState(EngineState.NEEDS_PROFILE)
        } catch (f: StartFailure) {
            failClosed(f)
        } catch (t: Throwable) {
            SecuredLog.crash(Ev.RESTORE_FAILED, t)
            failClosed(StartFailure(ErrorMapper.engine("internal"), "internal"))
        }
    }

    /**
     * Deletes the databases of an unfinished restore and its leftovers: repair list, and the words and keys it may
     * have stored (a restore runs only without a profile, so they are its own). The passphrase in DbKeyStore stays
     * (a new database takes any). The delete queue and rollback counters stay.
     */
    private fun wipePartialRestore() {
        stopReceiver()
        closeCtrlQuietly()
        val dir = dataDir
        for (db in listOf("_chat.db", "_agent.db")) {
            for (suffix in listOf("", ".bak", ".exported", "-journal")) File(dir, DB_PREFIX + db + suffix).delete()
        }
        media.onStoreReplaced()
        File(dir, "tmp").deleteRecursively()
        savePending(LinkedHashMap())
        if (vault.hasSecrets() || vault.hasPhrase()) {
            if (!vault.deleteAll()) SecuredLog.w(Ev.BACKUP_IO_FAILED, "delete")
            backupStore.reset()
            cancelAutoTimer()
            publishBackupState { BackupState() }
        }
        if (!clearRestoreMarker()) SecuredLog.w(Ev.RESTORE_FAILED, "marker_clear")
        SecuredLog.w(Ev.RESTORE_WIPED)
    }

    /** The `.bak` copies left by import and re-keying (old keys!) and any `.exported` leftover. */
    private fun deleteDatabaseCopies(dir: File) {
        for (db in listOf("_chat.db", "_agent.db")) {
            for (suffix in listOf(".bak", ".exported")) {
                val f = File(dir, DB_PREFIX + db + suffix)
                if (f.exists() && !f.delete()) SecuredLog.w(Ev.RESTORE_FAILED, "bak_delete")
            }
        }
    }

    /**
     * The restored words, keys and server tag (from the GET that fetched the copy), so backup stays on with the same
     * phrase. The restored phone becomes the backup phone: one upload is owed right after the start and skips the tag
     * check (item 1). False when they couldn't be stored; the restore then fails and is wiped.
     */
    private fun storeRestoredSecrets(payload: Envelope.Payload, etag: String?, secrets: BackupSecrets, entropy: ByteArray): Boolean {
        try {
            vault.storeEntropy(entropy)
            vault.storeSecrets(secrets)
        } catch (e: KeyVault.Unavailable) {
            SecuredLog.e(Ev.VAULT_FAILED, e.reason)
            vault.deleteAll()
            return false
        }
        backupStore.removePendingDelete(secrets.id)
        if (!backupStore.setServerTag(secrets.id, etag)) SecuredLog.w(Ev.BACKUP_IO_FAILED, "tag")
        // The restored phone's uploads count on from far above the copy's counter, so an upload the old phone still
        // makes from the same counter can never look newer than, or equal to, the restored phone's (R6.2 part B).
        val stored = backupStore.update {
            BackupStore.Meta(
                counter = maxOf(it.counter, payload.counter) + RESTORE_COUNTER_STEP, lastBackupMs = payload.createdAtMs,
                dirty = true, urgent = true, unconditional = true,
            )
        }
        if (!stored) {
            vault.deleteAll()
            return false
        }
        publishBackupState { BackupState(enabled = true, lastBackupMs = payload.createdAtMs) }
        return true
    }

    // ---- per-connection repair after a restore (RestoreRepair): contacts and group member connections ----

    private fun pendingMap(): MutableMap<RepairKey, BackupStore.Pending> =
        pendingCache ?: LinkedHashMap(backupStore.pending()).also {
            pendingCache = it
            repairSinceCache = backupStore.repairSince()
        }

    private fun repairSince(): Long {
        pendingMap()
        return repairSinceCache
    }

    private fun savePending(map: MutableMap<RepairKey, BackupStore.Pending>) {
        pendingCache = map
        if (map.isEmpty()) repairSinceCache = Long.MAX_VALUE
        if (!backupStore.setPending(map)) SecuredLog.w(Ev.BACKUP_IO_FAILED, "pending")
    }

    /** A fresh repair list after a restore. False when it couldn't be written (the restore then fails). */
    private fun saveRepairList(connections: List<RepairKey>, sinceItemId: Long): Boolean {
        val map = connections.associateWithTo(LinkedHashMap()) { BackupStore.Pending() }
        pendingCache = map
        repairSinceCache = if (map.isEmpty()) Long.MAX_VALUE else sinceItemId
        return backupStore.startRepair(map, sinceItemId)
    }

    private fun updatePending(key: RepairKey, change: (BackupStore.Pending) -> BackupStore.Pending?) {
        val map = pendingMap()
        val old = map[key] ?: return
        val next = change(old)
        if (next == old) return
        if (next == null) map.remove(key) else map[key] = next
        savePending(map)
    }

    private fun checkAllPending() {
        if (state != EngineState.RUNNING) return
        expandGroupPlaceholders()
        for (key in pendingMap().keys.toList()) checkPending(key)
    }

    /**
     * Engine thread, RUNNING (the chat is started, so `/_members` works). Each group placeholder the restore listed
     * ([RestoreRepair.groupPlaceholders]) becomes its member connections ([RestoreRepair.memberKeys]). The new list is
     * written in one atomic save, and the placeholder is dropped only when that save succeeded, so a member key is
     * never lost: a failed save or `/_members` keeps the placeholder for the next start. A group that is gone
     * (groupNotFound) has nothing left to repair.
     */
    private fun expandGroupPlaceholders() {
        val placeholders = pendingMap().keys.filter { it.isGroupPlaceholder }
        for (key in placeholders) {
            val r = send(Commands.listMembers(key.groupId))
            val members = when {
                r is CoreReply.Result && r.type == "groupMembers" -> RestoreRepair.memberKeys(key.groupId, CoreJson.parseMembers(r.body))
                r is CoreReply.Error && r.error.detail in GONE_CONNECTION_ERRORS -> emptyList()
                else -> {
                    SecuredLog.w(Ev.RESTORE_REPAIR, "expand_" + replyTag(r))
                    continue
                }
            }
            val next = RestoreRepair.expand(pendingMap(), key, members)
            if (!backupStore.setPending(next)) {
                SecuredLog.w(Ev.BACKUP_IO_FAILED, "pending")
                continue
            }
            pendingCache = next
            if (next.isEmpty()) repairSinceCache = Long.MAX_VALUE
            SecuredLog.i(Ev.RESTORE_REPAIR, "members", members.size)
        }
    }

    /** `/_info @id` or `/_info #g <memberId>`. */
    private fun infoCommand(key: RepairKey): String =
        if (key.isMember) Commands.groupMemberInfo(key.groupId, key.memberId) else Commands.contactInfo(key.contactId)

    /** (connection ready, stats) of an info reply for [key], or null when the reply isn't one. */
    private fun connInfo(key: RepairKey, r: CoreReply.Result): Pair<Boolean, RestoreRepair.ConnStats?>? = when {
        key.isMember && r.type == "groupMemberInfo" -> CoreJson.memberInfoStats(r.body)
        !key.isMember && r.type == "contactInfo" -> CoreJson.contactInfoStats(r.body).let { (c, stats) -> (c?.ready == true) to stats }
        else -> null
    }

    /**
     * One step for one restored connection: `/_info`, fold the sync state into its entry, then sync (plain, or the
     * one forced sync), switch, wait or drop (RestoreRepair.decide).
     */
    private fun checkPending(key: RepairKey) {
        if (state != EngineState.RUNNING || ctrl == 0L) return
        // A group placeholder is no connection: [expandGroupPlaceholders] turns it into member keys first.
        if (key.isGroupPlaceholder || pendingMap()[key] == null) return
        val (ready, stats) = when (val r = send(infoCommand(key))) {
            is CoreReply.Result -> connInfo(key, r) ?: return
            is CoreReply.Error -> {
                if (r.error.detail in GONE_CONNECTION_ERRORS) {
                    SecuredLog.i(Ev.RESTORE_REPAIR, "drop")
                    updatePending(key) { null }
                }
                return
            }
            CoreReply.Invalid -> return
        }
        if (stats != null) observeSync(key, stats.ratchetSyncState)
        val entry = pendingMap()[key] ?: return
        when (RestoreRepair.decide(stats, ready, entry, System.currentTimeMillis())) {
            RestoreRepair.Action.SYNC -> syncRatchet(key, "restore")
            RestoreRepair.Action.FORCE_SYNC -> syncRatchet(key, "restore_force", force = true)
            RestoreRepair.Action.SWITCH -> {
                val cmd = if (key.isMember) Commands.switchGroupMember(key.groupId, key.memberId) else Commands.switchContact(key.contactId)
                val expected = if (key.isMember) "groupMemberSwitchStarted" else "contactSwitchStarted"
                val s = send(cmd)
                if (s is CoreReply.Result && s.type == expected) {
                    SecuredLog.i(Ev.QUEUE_SWITCH, "started")
                    updatePending(key) { it.copy(switched = true) }
                } else {
                    SecuredLog.w(Ev.QUEUE_SWITCH, replyTag(s))
                }
            }
            RestoreRepair.Action.WAIT -> Unit
            RestoreRepair.Action.DONE -> {
                SecuredLog.i(Ev.RESTORE_REPAIR, "done")
                updatePending(key) { null }
                // DONE means our switch finished. Its event may have come while not RUNNING (dropped), so the copy
                // that knows the new address is owed here too; coalesced with any other request (R6.5).
                requestUrgentBackup("switch", SWITCH_BACKUP_DELAY_MS)
            }
            RestoreRepair.Action.DROP -> {
                SecuredLog.i(Ev.RESTORE_REPAIR, "drop")
                updatePending(key) { null }
            }
        }
    }

    /** A sync state seen for a restored connection. A completed sync proves the ratchet and refreshes the server copy. */
    private fun observeSync(key: RepairKey, status: String) {
        val old = pendingMap()[key] ?: return
        val next = RestoreRepair.observe(old, status)
        if (next == old) return
        updatePending(key) { next }
        if (next.proven && !old.proven) {
            SecuredLog.i(Ev.RESTORE_REPAIR, "sync_done")
            requestUrgentBackup("repair_sync", SWITCH_BACKUP_DELAY_MS)
        }
    }

    /**
     * `/_sync @id` or `/_sync #g <memberId>`; [force] only from the restore repair (RestoreRepair.Action.FORCE_SYNC).
     * At most once per connection per [AUTO_SYNC_GAP_MS] (a peer can't make us loop); a refusal by that guard queues a
     * re-check for when it ends, so the sync isn't simply lost (R6.6).
     */
    private fun syncRatchet(key: RepairKey, tag: String, force: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        val last = lastAutoSync[key]
        if (last != null && now - last < AUTO_SYNC_GAP_MS) {
            scheduleSyncRecheck(key, last + AUTO_SYNC_GAP_MS - now)
            return false
        }
        lastAutoSync[key] = now
        val cmd = if (key.isMember) Commands.syncGroupMember(key.groupId, key.memberId, force) else Commands.syncRatchet(key.contactId, force)
        val expected = if (key.isMember) "groupMemberRatchetSyncStarted" else "contactRatchetSyncStarted"
        val r = send(cmd)
        return if (r is CoreReply.Result && r.type == expected) {
            SecuredLog.i(Ev.RATCHET_SYNC, tag)
            updatePending(key) { RestoreRepair.syncAccepted(it, now) }
            true
        } else {
            SecuredLog.w(Ev.RATCHET_SYNC, replyTag(r))
            false
        }
    }

    /** One re-check per connection, on the engine thread, a moment after its sync guard ends (R6.6). */
    private fun scheduleSyncRecheck(key: RepairKey, delayMs: Long) {
        if (!syncRechecks.add(key)) return
        try {
            worker.schedule({ post { syncRechecks.remove(key); recheckSync(key) } }, delayMs + SYNC_RECHECK_SLACK_MS, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            syncRechecks.remove(key)
            SecuredLog.crash(Ev.RATCHET_SYNC, t)
        }
    }

    /**
     * Engine thread. A restored connection takes its normal step; any other connection is synced again only when the
     * core still says it must (`required`, sending blocked).
     */
    private fun recheckSync(key: RepairKey) {
        if (state != EngineState.RUNNING || ctrl == 0L) return
        if (key in pendingMap()) return checkPending(key)
        val r = send(infoCommand(key)) as? CoreReply.Result ?: return
        val stats = connInfo(key, r)?.second ?: return
        if (RestoreRepair.syncOnEvent(stats.ratchetSyncState, restoredContact = false) && RestoreRepair.syncAllowed(stats)) {
            syncRatchet(key, "recheck")
        }
    }

    /** A sync state change for any connection, from a contactRatchetSync or groupMemberRatchetSync event. */
    private fun onRatchetSyncEvent(key: RepairKey, status: String) {
        val restored = key in pendingMap()
        if (restored) observeSync(key, status)
        val synced = RestoreRepair.syncOnEvent(status, restored) && syncRatchet(key, "event_$status")
        if (restored && !synced) checkPending(key)
    }

    /** A queue switch step for any connection, from a contactSwitch or groupMemberSwitch event. */
    private fun onSwitchEvent(key: RepairKey, progress: String) {
        if (!progress.endsWith(".completed")) return
        SecuredLog.i(Ev.QUEUE_SWITCH, progress)
        // The old queue is deleted on the server: a copy from before this switch would restore a dead address
        // (8 Nov device test), so the server copy is refreshed. Coalesced with other switches.
        requestUrgentBackup("switch", SWITCH_BACKUP_DELAY_MS)
        if (progress == "rcv.completed" && key in pendingMap()) updatePending(key) { null }
    }

    /** The connection an incoming item came over: the contact, or the member who sent it in a group. */
    private fun keyOfIncoming(item: CoreItem): RepairKey? = when {
        item.chatId == SAVED_MESSAGES_CHAT_ID -> null
        item.group -> {
            val groupId = CoreJson.groupIdFromChatId(item.chatId)
            val sender = item.sender
            if (groupId != null && sender != null) RepairKey.member(groupId, sender.memberId) else null
        }
        else -> RepairKey.contact(item.chatId)
    }

    /** Events that matter for the backup and for repairing restored connections. Engine thread, RUNNING only. */
    private fun backupOnEvent(reply: CoreReply.Result) {
        // New contacts, member connections and changes to our own memberships: the server copy must have them soon.
        BackupStore.urgentReason(reply.type)?.let { reason ->
            markChanged()
            requestUrgentBackup(reason, CONTACT_BACKUP_DELAY_MS)
        }
        when (reply.type) {
            "contactRatchetSync" -> {
                val (contactId, status) = CoreJson.ratchetSyncEvent(reply.body) ?: return
                onRatchetSyncEvent(RepairKey.contact(contactId), status)
            }
            "groupMemberRatchetSync" -> {
                val (groupId, memberId, status) = CoreJson.memberRatchetSyncEvent(reply.body) ?: return
                onRatchetSyncEvent(RepairKey.member(groupId, memberId), status)
            }
            "contactSwitch" -> {
                val (contactId, progress) = CoreJson.switchEvent(reply.body) ?: return
                onSwitchEvent(RepairKey.contact(contactId), progress)
            }
            "groupMemberSwitch" -> {
                val (groupId, memberId, progress) = CoreJson.memberSwitchEvent(reply.body) ?: return
                onSwitchEvent(RepairKey.member(groupId, memberId), progress)
            }
            "connectedToGroupMember" -> {
                val groupId = reply.body.optJSONObject("groupInfo")?.optLong("groupId", 0L) ?: 0L
                val memberId = reply.body.optJSONObject("member")?.optLong("groupMemberId", 0L) ?: 0L
                if (groupId > 0 && memberId > 0) checkPending(RepairKey.member(groupId, memberId))
            }
            "contactSndReady" -> CoreJson.parseContact(reply.body.optJSONObject("contact"))?.contactId?.let { checkPending(RepairKey.contact(it)) }
            "newChatItems" -> {
                val pending = pendingMap()
                if (pending.isEmpty()) return
                // A message from the other side proves only their direction (finding 1): look again, but it is no proof.
                val heard = CoreJson.parseChatItems(reply.body).filter { it.isMessage && !it.outgoing }
                    .mapNotNull { keyOfIncoming(it) }.filter { it in pending }.toSet()
                for (key in heard) {
                    updatePending(key) { RestoreRepair.received(it) }
                    checkPending(key)
                }
            }
            "chatItemsStatusesUpdated" -> {
                val pending = pendingMap()
                if (pending.isEmpty()) return
                val since = repairSince()
                val delivered = CoreJson.parseChatItems(reply.body).filter { it.outgoing && it.status == MessageStatus.DELIVERED }
                val touched = LinkedHashSet<RepairKey>()
                for (item in delivered) {
                    // In a group DELIVERED means every member sent a receipt (StatusMapper): proof for each of them.
                    val keys = if (item.group) {
                        val groupId = CoreJson.groupIdFromChatId(item.chatId)
                        pending.keys.filter { it.isMember && it.groupId == groupId }
                    } else {
                        listOf(RepairKey.contact(item.chatId)).filter { it in pending }
                    }
                    for (key in keys) {
                        updatePending(key) { RestoreRepair.delivered(it, item.itemId, since) }
                        touched.add(key)
                    }
                }
                for (key in touched) checkPending(key)
            }
            // A decryption error merged into the last item arrives as an update, not as a ratchet sync event (Subscriber.hs:686-692).
            "chatItemUpdated" -> {
                val pending = pendingMap()
                if (pending.isEmpty()) return
                val item = CoreJson.parseAChatItem(reply.body.optJSONObject("chatItem")) ?: return
                if (item.isMessage || item.outgoing) return
                keyOfIncoming(item)?.takeIf { it in pending }?.let { checkPending(it) }
            }
        }
    }

    private fun markRestoreInProgress(): Boolean = try {
        val m = restoreMarker
        m.exists() || m.createNewFile() || m.exists()
    } catch (e: Exception) {
        false
    }

    /** True when the restore marker is gone afterwards. */
    private fun clearRestoreMarker(): Boolean = try {
        val m = restoreMarker
        !m.exists() || m.delete() || !m.exists()
    } catch (e: Exception) {
        false
    }

    private fun closeCtrlQuietly() {
        val c = ctrl
        if (c == 0L) return
        media.onStop()
        closeQuietly(c)
        ctrl = 0L
        chatStarted = false
        media.afterClose()
    }

    /** Through [CtrlGate.close]: waits for native file calls in flight, and none starts again until the next open. */
    private fun closeQuietly(c: Long) {
        try {
            ctrlGate.close { chatCloseStore(c) }
        } catch (t: Throwable) {
            SecuredLog.crash(Ev.STOPPED, t)
        }
    }

    private fun isResult(r: CoreReply, type: String) = r is CoreReply.Result && r.type == type

    private fun replyTag(r: CoreReply): String = when (r) {
        is CoreReply.Result -> r.type
        is CoreReply.Error -> r.error.tag
        CoreReply.Invalid -> "invalid"
    }

    private fun randomTag(): String = Bip39.hex(ByteArray(8).also { SecureRandom().nextBytes(it) })

    /** Runs [task] on the backup thread; a crash becomes a readable error for [callback]. */
    private fun <T> onWorker(callback: SecuredCallback<T>, task: () -> Unit) = runGuarded(worker, callback, task)

    /** Runs [task] on the phrase thread (Keystore and words; never behind an upload). */
    private fun <T> onPhrase(callback: SecuredCallback<T>, task: () -> Unit) = runGuarded(phraseExecutor, callback, task)

    private fun <T> runGuarded(exec: ExecutorService, callback: SecuredCallback<T>, task: () -> Unit) {
        exec.execute {
            try {
                task()
            } catch (e: Bip39.WordListError) {
                SecuredLog.e(Ev.TASK_CRASHED, "wordlist")
                err(callback, SecuredError(SecuredError.Kind.ENGINE, "This app's recovery word list is damaged. Reinstall Encrogram."))
            } catch (t: Throwable) {
                SecuredLog.crash(Ev.TASK_CRASHED, t)
                err(callback, ErrorMapper.engine("internal"))
            }
        }
    }

    // =============================================================================================================
    // Item and chat actions (engine thread)
    // =============================================================================================================

    private fun editOnEngine(chatId: Long, ids: List<Long>, text: String, callback: SecuredCallback<Unit>) {
        val id = ids.singleOrNull() ?: return err(callback, ErrorMapper.invalid("Edit one message at a time."))
        if (text.isBlank()) return err(callback, ErrorMapper.invalid("The message is empty."))
        if (!InputRules.textFits(text)) return err(callback, ErrorMapper.invalid(ErrorMapper.TOO_LONG))
        val ref = refOf(chatId) ?: return err(callback, ErrorMapper.chatGone())
        val item = itemOrErr(chatId, id, callback) ?: return
        // meta.editable: our own text message, under 24 h (no limit for notes), not forwarded, not deleted.
        if (!MessageMapper.toMessage(item).canEdit) return refuse(callback, "edit", ErrorMapper.CANT_EDIT)
        when (val r = send(Commands.updateItem(ref, id, text))) {
            is CoreReply.Result -> when (r.type) {
                "chatItemUpdated" -> {
                    SecuredLog.i(Ev.ITEM_ACTION, "edit")
                    ok(callback, Unit)
                    CoreJson.parseAChatItem(r.body.optJSONObject("chatItem"))?.let { applyItemUpdated(it) }
                }
                "chatItemNotChanged" -> ok(callback, Unit)
                else -> err(callback, unexpected(r))
            }
            is CoreReply.Error -> err(callback, ErrorMapper.fromCore(r.error, Op.EDIT))
            CoreReply.Invalid -> err(callback, ErrorMapper.engine("invalid_reply"))
        }
    }

    /** `broadcast` sends x.msg.del; with fullDelete on for both, the rows go on both sides, else they are marked. */
    private fun deleteOnEngine(chatId: Long, ids: List<Long>, mode: Commands.DeleteMode, callback: SecuredCallback<Unit>) {
        val ref = refOf(chatId) ?: return err(callback, ErrorMapper.chatGone())
        when (val r = send(Commands.deleteItems(ref, ids, mode))) {
            is CoreReply.Result -> {
                if (r.type != "chatItemsDeleted") return err(callback, unexpected(r))
                SecuredLog.i(Ev.ITEM_ACTION, mode.word, ids.size)
                ok(callback, Unit)
                applyDeletions(CoreJson.parseDeletions(r.body))
            }
            is CoreReply.Error -> err(callback, ErrorMapper.fromCore(r.error, Op.DELETE))
            CoreReply.Invalid -> err(callback, ErrorMapper.engine("invalid_reply"))
        }
    }

    private fun reactOnEngine(chatId: Long, ids: List<Long>, action: ItemAction.React, callback: SecuredCallback<Unit>) {
        val id = ids.singleOrNull() ?: return err(callback, ErrorMapper.invalid("React to one message at a time."))
        if (!InputRules.reactionAllowed(action.emoji)) return refuse(callback, "react_unknown", ErrorMapper.CANT_REACT)
        val ref = refOf(chatId) ?: return err(callback, ErrorMapper.chatGone())
        val item = itemOrErr(chatId, id, callback) ?: return
        if (item.deleted || !item.shared) return refuse(callback, "react_item", ErrorMapper.CANT_REACT)
        // No own-reaction check here: `/_get chat around=` returns the item itself WITHOUT reactions
        // (Store/Messages.hs getDirectChatAround' uses getDirectChatItem), so the core decides and we map its answer.
        when (val r = send(Commands.reaction(ref, id, action.add, action.emoji))) {
            is CoreReply.Result -> {
                if (r.type != "chatItemReaction") return err(callback, unexpected(r))
                SecuredLog.i(Ev.ITEM_ACTION, if (action.add) "react_on" else "react_off")
                ok(callback, Unit)
                CoreJson.parseReactionItem(r.body)?.let { emitChanged(listOf(it)) }
            }
            is CoreReply.Error -> when (r.error.subDetail) {
                // Adding twice or removing an absent reaction: already the wanted state.
                "reactionAlreadyAdded", "reactionAlreadyRemoved" -> ok(callback, Unit)
                "tooManyReactions" -> refuse(callback, "react_limit", "You can add up to ${InputRules.MAX_OWN_REACTIONS} reactions to a message.")
                else -> err(callback, ErrorMapper.fromCore(r.error, Op.REACT))
            }
            CoreReply.Invalid -> err(callback, ErrorMapper.engine("invalid_reply"))
        }
    }

    /** Between contacts and Saved Messages in any direction. Copies into notes are local; out of notes they are sent. */
    private fun forwardOnEngine(chatId: Long, ids: List<Long>, toChatId: Long, callback: SecuredCallback<Unit>) {
        if (chatCache[toChatId] == null) return err(callback, ErrorMapper.chatGone())
        postRefusal(toChatId)?.let { return refuse(callback, "forward_group", it) }
        val from = refOf(chatId) ?: return err(callback, ErrorMapper.chatGone())
        val to = refOf(toChatId) ?: return err(callback, ErrorMapper.chatGone())
        when (val r = send(Commands.forward(to, from, ids))) {
            is CoreReply.Result -> {
                if (r.type != "newChatItems") return err(callback, unexpected(r))
                val items = CoreJson.parseChatItems(r.body).filter { it.chatId == toChatId }
                val role = roleIn(toChatId)
                val copies = items.filter { it.isMessage }.map { MessageMapper.toMessage(it, role) }
                if (copies.isEmpty()) return refuse(callback, "forward_none", "These messages can't be forwarded.")
                SecuredLog.i(Ev.ITEM_ACTION, "forward", copies.size)
                ok(callback, Unit)
                applyNewItems(items)
                main.post { for (m in copies) for (l in listeners) l.onMessageReceived(m) }
            }
            is CoreReply.Error -> err(callback, ErrorMapper.fromCore(r.error, Op.FORWARD))
            CoreReply.Invalid -> err(callback, ErrorMapper.engine("invalid_reply"))
        }
    }

    /**
     * `/_settings` replaces all of ChatSettings: read the current one (the contact's, or the group's GroupInfo), change
     * one field, send it all back.
     */
    private fun updateChatSettings(chatId: Long, tag: String, callback: SecuredCallback<Unit>, edit: (JSONObject) -> Unit) {
        val ref = refOf(chatId) ?: return err(callback, ErrorMapper.chatGone())
        val owner = (if (ref.isGroup) groupInfoOrErr(chatId, callback) else contactOrErr(chatId, callback)) ?: return
        chatCommand(tag, Commands.chatSettings(ref, CoreJson.chatSettingsUpdate(owner, edit)), "cmdOk", callback)
    }

    /** The group's GroupInfo as stored now (for read-modify-write of its settings), or null after an error. */
    private fun <T> groupInfoOrErr(chatId: Long, callback: SecuredCallback<T>): JSONObject? {
        val ref = refOf(chatId) ?: run {
            err(callback, ErrorMapper.chatGone())
            return null
        }
        when (val r = send(Commands.getChat(ref, 1))) {
            is CoreReply.Result -> {
                if (r.type == "apiChat") CoreJson.groupInfoOfChat(r.body)?.let { return it }
                err(callback, unexpected(r))
            }
            is CoreReply.Error -> err(callback, ErrorMapper.fromCore(r.error, Op.CHAT))
            CoreReply.Invalid -> err(callback, ErrorMapper.engine("invalid_reply"))
        }
        return null
    }

    /** Our role in a group chat as of the last chat list load; null for contacts and notes. */
    private fun roleIn(chatId: Long): MemberRole? = if (isGroupChatId(chatId)) chatCache[chatId]?.myRole else null

    /**
     * Why we can't post to [chatId] right now, or null (contacts and notes: never refused here). Not joined yet, or an
     * observer (a channel's subscriber). Other states (left, removed, still connecting) are left to the core's answer.
     */
    private fun postRefusal(chatId: Long): String? {
        if (!isGroupChatId(chatId)) return null
        val g = groupCache[chatId] ?: return null
        return when {
            g.invited -> ErrorMapper.NOT_JOINED
            g.myRole < MemberRole.MEMBER -> ErrorMapper.ONLY_ADMINS_POST
            else -> null
        }
    }

    /**
     * Delete for everyone in a group. Our own messages: a broadcast delete (`/_delete item #g ids broadcast`, 24 h
     * limit, core :859-865). Someone else's: `/_delete member item #g ids` (:901-907), only as a moderator or higher
     * and never for a sender of a higher role (:4079-4088); anything else is refused before anything is sent.
     */
    private fun deleteInGroupOnEngine(chatId: Long, ids: List<Long>, callback: SecuredCallback<Unit>) {
        val groupId = CoreJson.groupIdFromChatId(chatId) ?: return err(callback, ErrorMapper.chatGone())
        val ref = ChatRef.group(groupId)
        val myRole = roleIn(chatId)
        val own = ArrayList<Long>()
        val others = ArrayList<Long>()
        for (id in ids) {
            val item = itemOrErr(chatId, id, callback) ?: return
            // Our own message from an earlier membership (a rejoin's history): the core holds it as received, so a
            // broadcast delete is refused (Commands.hs:889) and moderating it would delete it for others as an admin.
            if (item.earlierOwn) return refuse(callback, "delete_history", ErrorMapper.CANT_DELETE_FOR_EVERYONE)
            if (item.outgoing) {
                own.add(id)
            } else {
                val sender = item.sender
                val allowed = item.shared && !item.deleted && sender != null && myRole != null &&
                    myRole >= MemberRole.MODERATOR && myRole >= sender.role
                if (!allowed) return refuse(callback, "moderate", ErrorMapper.CANT_MODERATE)
                others.add(id)
            }
        }
        val deletions = ArrayList<CoreDeletion>()
        var failure: SecuredError? = null
        if (own.isNotEmpty()) {
            when (val r = send(Commands.deleteItems(ref, own, Commands.DeleteMode.BROADCAST))) {
                is CoreReply.Result -> if (r.type == "chatItemsDeleted") deletions.addAll(CoreJson.parseDeletions(r.body)) else failure = unexpected(r)
                is CoreReply.Error -> failure = ErrorMapper.fromCore(r.error, Op.DELETE)
                CoreReply.Invalid -> failure = ErrorMapper.engine("invalid_reply")
            }
        }
        if (others.isNotEmpty() && failure == null) {
            when (val r = send(Commands.deleteMemberItems(groupId, others))) {
                is CoreReply.Result -> if (r.type == "chatItemsDeleted") deletions.addAll(CoreJson.parseDeletions(r.body)) else failure = unexpected(r)
                is CoreReply.Error -> failure = ErrorMapper.fromCore(r.error, Op.DELETE)
                CoreReply.Invalid -> failure = ErrorMapper.engine("invalid_reply")
            }
        }
        if (own.isNotEmpty()) SecuredLog.i(Ev.ITEM_ACTION, Commands.DeleteMode.BROADCAST.word, own.size)
        if (others.isNotEmpty()) SecuredLog.i(Ev.ITEM_ACTION, "moderate", others.size)
        val f = failure
        if (f != null) err(callback, f) else ok(callback, Unit)
        applyDeletions(deletions)
    }

    /**
     * ChatAction.Delete on a group. Invited (declining), already left or removed, or the group is gone: delete it here
     * (`/_delete #g`, allowed once we are not a current member, core :1339-1341). The owner with [forEveryone]: delete
     * it for everyone. Anyone else, and the owner who chose "this phone only": leave first (the core would otherwise
     * tell every member, since it deletes for everyone whenever an active owner deletes, :1348), then delete here.
     */
    private fun deleteGroupOnEngine(chatId: Long, forEveryone: Boolean, callback: SecuredCallback<Unit>) {
        val g = groupCache[chatId] ?: return err(callback, ErrorMapper.chatGone())
        val stillMember = !g.invited && !g.gone
        val everyone = forEveryone && stillMember && g.myRole == MemberRole.OWNER
        if (stillMember && !everyone) {
            when (val r = send(Commands.leaveGroup(g.groupId))) {
                is CoreReply.Result -> if (r.type != "leftMemberUser") return err(callback, unexpected(r))
                is CoreReply.Error -> return err(callback, ErrorMapper.fromCore(r.error, Op.CHAT))
                CoreReply.Invalid -> return err(callback, ErrorMapper.engine("invalid_reply"))
            }
            SecuredLog.i(Ev.GROUP_ACTION, "leave")
        }
        // We leave (or end the group for everyone): the server copy must not bring the membership back.
        if (stillMember) requestUrgentBackup("group", CONTACT_BACKUP_DELAY_MS)
        val tag = when {
            everyone -> "delete_everyone"
            g.invited -> "decline"
            else -> "delete_here"
        }
        when (val r = send(Commands.deleteGroup(g.groupId, everyone))) {
            is CoreReply.Result -> {
                if (r.type != "groupDeletedUser") {
                    refreshChats(notify = true)
                    return err(callback, unexpected(r))
                }
                SecuredLog.i(Ev.GROUP_ACTION, tag)
                ok(callback, Unit)
                media.onChatRemoved(chatId)
                refreshChats(notify = true)
            }
            is CoreReply.Error -> {
                // After a leave the chat stays as "left": show that, then report.
                refreshChats(notify = true)
                err(callback, ErrorMapper.fromCore(r.error, Op.CHAT))
            }
            CoreReply.Invalid -> err(callback, ErrorMapper.engine("invalid_reply"))
        }
    }

    /** Local only: never sent. */
    private fun setArchivedOnEngine(chatId: Long, archived: Boolean, callback: SecuredCallback<Unit>) {
        if (!archive.set(chatId, archived)) return err(callback, SecuredError(SecuredError.Kind.ENGINE, ErrorMapper.STORAGE))
        chatCache[chatId]?.let { chatCache[chatId] = it.copy(archived = archived) }
        SecuredLog.i(Ev.CHAT_ACTION, "archive")
        publishChats()
        ok(callback, Unit)
        notifyChatsChanged()
    }

    /** Sends a chat command that answers [expected]; on success runs [done] and reloads the chat list (onChatsChanged). */
    private fun chatCommand(tag: String, cmd: String, expected: String, callback: SecuredCallback<Unit>, done: () -> Unit = {}) {
        when (val r = send(cmd)) {
            is CoreReply.Result -> {
                if (r.type != expected) return err(callback, unexpected(r))
                SecuredLog.i(Ev.CHAT_ACTION, tag)
                ok(callback, Unit)
                done()
                refreshChats(notify = true)
            }
            is CoreReply.Error -> err(callback, ErrorMapper.fromCore(r.error, Op.CHAT))
            CoreReply.Invalid -> err(callback, ErrorMapper.engine("invalid_reply"))
        }
    }

    /** The contact as stored now (for read-modify-write), or null after reporting an error to [callback]. */
    private fun <T> contactOrErr(chatId: Long, callback: SecuredCallback<T>): JSONObject? {
        when (val r = send(Commands.getChat(chatId, 1))) {
            is CoreReply.Result -> {
                if (r.type == "apiChat") CoreJson.contactOfChat(r.body)?.let { return it }
                err(callback, unexpected(r))
            }
            is CoreReply.Error -> err(callback, ErrorMapper.fromCore(r.error, Op.CHAT))
            CoreReply.Invalid -> err(callback, ErrorMapper.engine("invalid_reply"))
        }
        return null
    }

    /** One message of a chat as stored now, or null after reporting an error to [callback]. */
    private fun <T> itemOrErr(chatId: Long, itemId: Long, callback: SecuredCallback<T>): CoreItem? {
        val ref = refOf(chatId) ?: run {
            err(callback, ErrorMapper.chatGone())
            return null
        }
        when (val r = send(Commands.getChat(ref, 1, aroundId = itemId))) {
            is CoreReply.Result -> {
                if (r.type != "apiChat") {
                    err(callback, unexpected(r))
                    return null
                }
                // As the chat shows it: our own earlier messages in a rejoin's history are ours, and not editable.
                localView(CoreJson.parseChatItemsOfChat(r.body)).firstOrNull { it.itemId == itemId && it.isMessage }?.let { return it }
                err(callback, ErrorMapper.messageGone())
            }
            is CoreReply.Error -> err(callback, ErrorMapper.fromCore(r.error, Op.LOAD))
            CoreReply.Invalid -> err(callback, ErrorMapper.engine("invalid_reply"))
        }
        return null
    }

    /** The chat as the core names it: `*<noteFolderId>` for Saved Messages, `#<groupId>` for groups, `@<contactId>` otherwise. */
    private fun refOf(chatId: Long): ChatRef? = when {
        chatId == SAVED_MESSAGES_CHAT_ID -> noteFolderId?.let { ChatRef.notes(it) }
        isGroupChatId(chatId) -> CoreJson.groupIdFromChatId(chatId)?.let { ChatRef.group(it) }
        chatId > 0 -> ChatRef.contact(chatId)
        else -> null
    }

    /** A text note in Saved Messages: stored on this phone by the core, never sent to any relay. No replies. */
    private fun createNoteOnEngine(text: String, replyToId: Long?, callback: SecuredCallback<SecuredMessage>) {
        if (replyToId != null) return refuse(callback, "reply_notes", ErrorMapper.NOT_IN_SAVED)
        val folderId = noteFolderId ?: return err(callback, ErrorMapper.chatGone())
        when (val r = send(Commands.createNote(folderId, text))) {
            is CoreReply.Result -> {
                val item = if (r.type == "newChatItems") {
                    CoreJson.parseChatItems(r.body).firstOrNull { it.chatId == SAVED_MESSAGES_CHAT_ID }
                } else {
                    null
                }
                if (item == null) return err(callback, unexpected(r))
                ok(callback, MessageMapper.toMessage(item))
                applyNewItems(listOf(item))
            }
            is CoreReply.Error -> err(callback, ErrorMapper.fromCore(r.error, Op.SEND))
            CoreReply.Invalid -> err(callback, ErrorMapper.engine("invalid_reply"))
        }
    }

    /**
     * Saved Messages: Delete and ClearHistory both clear every note (`/_clear chat *<id>`, local). The core has no
     * command to pin a note folder (APISetChatSettings refuses `local`), and mute, archive and timers don't apply.
     */
    private fun notesChatAction(action: ChatAction, callback: SecuredCallback<Unit>) {
        when (action) {
            // Only an explicit Clear empties Saved Messages; a list "Delete chats" must never wipe the notes.
            ChatAction.ClearHistory -> {
                val ref = refOf(SAVED_MESSAGES_CHAT_ID) ?: return err(callback, ErrorMapper.chatGone())
                chatCommand("clear_notes", Commands.clearChat(ref), "chatCleared", callback)
            }
            else -> refuse(callback, "notes_chat_action", ErrorMapper.NOT_IN_SAVED)
        }
    }

    private fun <T> refuse(callback: SecuredCallback<T>, tag: String, message: String) {
        SecuredLog.i(Ev.ACTION_REFUSED, tag)
        err(callback, ErrorMapper.notAllowed(message))
    }

    /** After a read: received timed messages now have a deleteAt. Bounded to the unread ones. */
    private fun reportStartedTimers(chatId: Long, unread: Int) {
        val r = send(Commands.getChat(chatId, minOf(unread + 50, MAX_PAGE * 2)))
        if (r !is CoreReply.Result || r.type != "apiChat") return
        val started = CoreJson.parseChatItemsOfChat(r.body).filter { it.isMessage && !it.outgoing && it.expiresAtMs != null }
        emitChanged(started)
    }

    // =============================================================================================================
    // Start, profile, stop
    // =============================================================================================================

    private fun startOnEngine() {
        when (state) {
            EngineState.STARTING, EngineState.RUNNING, EngineState.NEEDS_PROFILE -> return
            EngineState.NOT_STARTED, EngineState.FAILED -> Unit
        }
        setState(EngineState.STARTING)
        try {
            ServerConfigPlanner.configProblem(config)?.let {
                throw StartFailure(SecuredError(SecuredError.Kind.ENGINE, "Secured's servers aren't set up correctly in this build ($it)."), it)
            }
            loadNative()
            if (ctrl == 0L) {
                // A restore that stopped before its databases were final: drop it and offer a fresh start.
                if (restoreMarker.exists()) wipePartialRestore()
                openDatabase()
            }
            startReceiver()
            val user = activeUser()
            if (user == null) {
                currentProfile = null
                setState(EngineState.NEEDS_PROFILE)
                return
            }
            configureAndRun(user)
        } catch (f: StartFailure) {
            failClosed(f)
        } catch (t: Throwable) {
            SecuredLog.crash(Ev.START_FAILED, t)
            failClosed(StartFailure(ErrorMapper.engine("internal"), "internal"))
        }
    }

    private fun createProfileOnEngine(name: String, callback: SecuredCallback<SecuredProfile>) {
        when (state) {
            EngineState.NEEDS_PROFILE -> Unit
            EngineState.RUNNING -> return err(callback, SecuredError(SecuredError.Kind.ENGINE, "A Secured profile already exists."))
            else -> return err(callback, ErrorMapper.notRunning(state))
        }
        val displayName = validName(name) ?: return err(callback, nameError())
        // A restore marker that couldn't be cleared would wipe this new profile at the next start.
        if (!clearRestoreMarker()) {
            SecuredLog.e(Ev.RESTORE_FAILED, "marker_clear")
            return err(callback, SecuredError(SecuredError.Kind.ENGINE, ErrorMapper.STORAGE))
        }
        // Before the user exists: from here until our servers are verified, every start locks the network first.
        if (!markFirstRunLockdown()) {
            SecuredLog.e(Ev.CONFIG_STEP_FAILED, "lockdown_mark")
            return err(callback, SecuredError(SecuredError.Kind.ENGINE, ErrorMapper.STORAGE))
        }
        setState(EngineState.STARTING)
        val r = send(Commands.createUser(displayName))
        val user = if (r is CoreReply.Result && r.type == "activeUser") CoreJson.parseUser(r.body.optJSONObject("user")) else null
        if (user == null) {
            setState(EngineState.NEEDS_PROFILE)
            return err(callback, if (r is CoreReply.Error) ErrorMapper.fromCore(r.error, Op.PROFILE) else ErrorMapper.engine("create_user"))
        }
        try {
            configureAndRun(user)
            ok(callback, SecuredProfile(user.displayName))
        } catch (f: StartFailure) {
            failClosed(f)
            err(callback, f.error)
        } catch (t: Throwable) {
            SecuredLog.crash(Ev.START_FAILED, t)
            val f = StartFailure(ErrorMapper.engine("internal"), "internal")
            failClosed(f)
            err(callback, f.error)
        }
    }

    /** The trimmed name, or null. The core counts characters (code points): mkValidName keeps at most 50. */
    private fun validName(name: String): String? {
        val displayName = name.trim()
        val nameLength = displayName.codePointCount(0, displayName.length)
        return if (nameLength == 0 || nameLength > MAX_NAME_CHARS) null else displayName
    }

    private fun nameError() = ErrorMapper.invalid("Enter a name of 1 to $MAX_NAME_CHARS characters.")

    /** The active user's LocalProfile JSON as stored now (`/u`), or null. */
    private fun activeProfileJson(): JSONObject? {
        val r = send(Commands.SHOW_ACTIVE_USER)
        if (r !is CoreReply.Result || r.type != "activeUser") return null
        val user = r.body.optJSONObject("user") ?: return null
        if (user.optLong("userId") != userId) return null
        return user.optJSONObject("profile")
    }

    /**
     * Profiles created before v2 lack fullDelete=yes and calls=no: merge them in once, with a read-modify-write
     * `/_profile` that keeps every other field. A no-op when they are already there (every new profile). Runs
     * after the relay lock-down is verified, because it sends the profile to every contact. Never fails the
     * start: on any failure it is simply tried again at the next start.
     */
    private fun mergeSecuredPreferences() {
        try {
            val local = activeProfileJson() ?: return SecuredLog.w(Ev.PROFILE_PREFS_FAILED, "active_user")
            val update = CoreJson.profileUpdate(local) ?: return
            val r = send(Commands.updateProfile(userId, update))
            if (r is CoreReply.Result && (r.type == "userProfileUpdated" || r.type == "userProfileNoChange")) {
                SecuredLog.i(Ev.PROFILE_PREFS_MERGED, r.type)
            } else {
                SecuredLog.w(Ev.PROFILE_PREFS_FAILED, (r as? CoreReply.Result)?.type ?: (r as? CoreReply.Error)?.error?.tag)
            }
        } catch (t: Throwable) {
            SecuredLog.crash(Ev.PROFILE_PREFS_FAILED, t)
        }
    }

    private fun loadNative() {
        try {
            NativeCore.ensureLoaded()
        } catch (f: NativeCore.LoadFailure) {
            val msg = if (f.reason == "unsupported_abi") {
                "Secured needs a 64-bit ARM phone. It can't run on this device."
            } else {
                "Secured's engine couldn't be loaded on this device."
            }
            throw StartFailure(SecuredError(SecuredError.Kind.ENGINE, msg), f.reason)
        }
    }

    private fun openDatabase() {
        val dir = dataDir
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw StartFailure(SecuredError(SecuredError.Kind.ENGINE, "Secured storage couldn't be created."), "mkdir")
        }
        val prefix = File(dir, DB_PREFIX).absolutePath
        val chatDb = File(prefix + "_chat.db")
        val agentDb = File(prefix + "_agent.db")
        val key = try {
            DbKeyStore(appContext).obtain(databaseExists = chatDb.exists() || agentDb.exists())
        } catch (f: DbKeyStore.KeyFailure) {
            SecuredLog.e(Ev.DB_KEY_FAILED, f.reason)
            throw StartFailure(SecuredError(SecuredError.Kind.ENGINE, "Secured storage can't be unlocked on this device (${f.reason})."), f.reason)
        }
        var (tag, c) = migrateInit(prefix, key)
        if (tag != "ok" && chatDb.exists() != agentDb.exists()) {
            // A first start that stopped half way leaves one database file; upstream deletes both and retries.
            SecuredLog.w(Ev.DB_RETRY_AFTER_PARTIAL, tag)
            chatDb.delete()
            agentDb.delete()
            // A new store: file ids start again, so no verdict of the old one may survive.
            media.onStoreReplaced()
            val retry = migrateInit(prefix, key)
            tag = retry.first
            c = retry.second
        }
        if (tag != "ok" || c == 0L) {
            SecuredLog.e(Ev.DB_OPEN_FAILED, tag)
            throw StartFailure(SecuredError(SecuredError.Kind.ENGINE, "Secured storage couldn't be opened ($tag)."), tag)
        }
        ctrl = c
        try {
            // Keep the core's tmp: it is the file-transfer work folder (ciphertext chunks), needed to resume
            // transfers after a restart. Only our own backup and restore leftovers (database archives) go.
            val temp = File(dir, "tmp").also { t ->
                t.listFiles()?.filter { it.name.startsWith("backup-") || it.name.startsWith("restore-") }?.forEach { it.deleteRecursively() }
            }
            expectResult(send(filePathsCommand(File(dir, "files"), temp, File(dir, "assets"))), "file_paths", "cmdOk")
            expectResult(send(Commands.ENCRYPT_LOCAL_FILES), "encrypt_files", "cmdOk")
        } catch (t: Throwable) {
            // Never keep a half-open store (no file paths, or no local file encryption): close it so the next
            // start redoes the whole open.
            try {
                ctrlGate.close { chatCloseStore(c) }
            } catch (e: Throwable) {
                SecuredLog.crash(Ev.DB_OPEN_FAILED, e)
            }
            ctrl = 0L
            if (t is StartFailure) throw t
            SecuredLog.crash(Ev.DB_OPEN_FAILED, t)
            throw StartFailure(SecuredError(SecuredError.Kind.ENGINE, "Secured storage couldn't be opened (setup)."), "db_setup")
        }
        // A new store (first open, restore): item ids and group ids from an earlier one mean nothing here.
        previewCache.clear()
        memberIdCache.clear()
        // What a crash left in media prep/, view/ and link/ goes while the gate is still closed; then file calls may run.
        media.onStoreOpened()
        ctrlGate.reopen()
        watchNetwork()
    }

    /** `/set file paths` with our remote-hosts folder; the backup points [files] and [assets] elsewhere for a moment. */
    private fun filePathsCommand(files: File, temp: File, assets: File): String =
        Commands.setFilePaths(files.absolutePath, temp.absolutePath, assets.absolutePath, File(dataDir, "remote_hosts").absolutePath)

    private fun migrateInit(prefix: String, key: String): Pair<String, Long> {
        val out = chatMigrateInit(prefix, key, MIGRATION_CONFIRM)
        val tag = CoreJson.migrationResultTag(out.getOrNull(0) as? String)
        val c = (out.getOrNull(1) as? Long) ?: 0L
        SecuredLog.i(Ev.DB_OPEN, tag)
        return tag to c
    }

    /**
     * `/u`: the profile, or null when there is none yet (NEEDS_PROFILE). This probe is how a start finds out, so before
     * the first profile its `noActiveUser` answer is expected and not logged as an error (G7 log noise).
     */
    private fun activeUser(): CoreUser? = when (val r = send(Commands.SHOW_ACTIVE_USER, expected = NO_USER_YET)) {
        is CoreReply.Result ->
            if (r.type == "activeUser") {
                CoreJson.parseUser(r.body.optJSONObject("user")) ?: throw configFailure("active_user", r)
            } else {
                throw configFailure("active_user", r)
            }
        is CoreReply.Error ->
            if (r.error.detail in NO_USER_YET) null
            else throw StartFailure(ErrorMapper.fromCore(r.error, Op.START), r.error.tag)
        CoreReply.Invalid -> throw configFailure("active_user", r)
    }

    /**
     * Restricts the engine to our relays, starts the chat and loads the chat list. Runs on every start, because
     * the preset operators and servers are compiled into the core. Any failure, of any kind, stops the chat and
     * throws [StartFailure]; on a first run the unreachable SOCKS proxy stays in place.
     */
    private fun configureAndRun(user: CoreUser) {
        userId = user.userId
        currentProfile = SecuredProfile(user.displayName)
        // First run (or a first run that never finished): the stored servers may still be the core's random
        // presets until step 4 replaces them, so every SMP/XFTP connection goes to a SOCKS proxy that can't be
        // reached (127.0.0.1:9, a privileged port no app can listen on) until step 6.
        val lockNetwork = lockdownMarker.exists()
        var unlockSent = false
        try {
            // 1. Network, set before /_start: unknown relays only through our relay as proxy, never directly.
            if (lockNetwork) {
                val net = expectResult(send(NETWORK_LOCKED), "network_lock", "networkConfig")
                if (!networkLockedOk(net.body)) throw configFailure("network_lock_verify", net)
            } else {
                val net = expectResult(send(Commands.NETWORK_CONFIG), "network", "networkConfig")
                if (!ServerConfigPlanner.networkConfigOk(net.body)) throw configFailure("network_verify", net)
            }

            // 2. Disable every preset operator (SimpleX Chat, Flux) and with them all their servers.
            val ops = expectResult(send(Commands.GET_OPERATORS), "operators_get", "serverOperatorConditions")
            ServerConfigPlanner.disabledOperators(ops.body)?.let { disabled ->
                val set = expectResult(send(Commands.setOperators(disabled)), "operators_set", "serverOperatorConditions")
                if (!ServerConfigPlanner.allOperatorsDisabled(set.body)) throw configFailure("operators_verify", set)
            }

            // 3. Start the chat: the engine only accepts user server changes on a started chat. No user-initiated
            //    network action can run before step 4 is verified: every command goes through this thread.
            //    Counted as started once sent, so a lost or garbled reply still gets a /_stop.
            chatStarted = true
            expectResult(send(Commands.START_CHAT), "start", "chatStarted", "chatRunning")

            // 4. Exactly our SMP and XFTP servers, then read them back the way the engine will use them.
            val current = expectResult(send(Commands.getUserServers(userId)), "servers_get", "userServers")
            val update = ServerConfigPlanner.customServersUpdate(current.body, config)
            expectResult(send(Commands.setUserServers(userId, update)), "servers_set", "cmdOk")
            val stored = expectResult(send(Commands.getUserServers(userId)), "servers_read", "userServers")
            ServerConfigPlanner.verifyUserServers(stored.body, config)?.let { problem -> throw configFailure(problem, null) }
            SecuredLog.i(Ev.CONFIG_OK)

            // 5. Local only: drop the preset contact card and load the chat list.
            removePresetContactCards()
            refreshChats(notify = false)

            // 6. First run: our servers are verified, so lift the lock and verify the normal network settings.
            if (lockNetwork) {
                unlockSent = true
                val net = expectResult(send(Commands.NETWORK_CONFIG), "network_unlock", "networkConfig")
                if (!ServerConfigPlanner.networkConfigOk(net.body)) throw configFailure("network_unlock_verify", net)
                clearFirstRunLockdown()
            }

            // 7. Our chat preferences in the profile (existing users, once). Not fatal.
            mergeSecuredPreferences()
        } catch (t: Throwable) {
            val f = t as? StartFailure ?: run {
                SecuredLog.crash(Ev.START_FAILED, t)
                StartFailure(SecuredError(SecuredError.Kind.ENGINE, "Secured stopped for safety after an internal error. Try again."), "internal")
            }
            failSafe(relock = unlockSent)
            throw f
        }
        setState(EngineState.RUNNING)
        afterRunning()
    }

    /** Backup timer (an upload owed after a restore starts here), queued server deletes, the repair of restored contacts, and media catch-up. Never throws. */
    private fun afterRunning() {
        try {
            if (backupState().enabled) scheduleAuto(0L)
            if (backupClient != null) {
                worker.execute {
                    try {
                        retryPendingDeletes()
                    } catch (t: Throwable) {
                        SecuredLog.crash(Ev.BACKUP_FAILED, t)
                    }
                }
            }
            checkAllPending()
        } catch (t: Throwable) {
            SecuredLog.crash(Ev.RESTORE_REPAIR, t)
        }
        // Downloads whose passing check was recorded while the engine wasn't running (during start, or before a restart).
        try {
            media.catchUp()
        } catch (t: Throwable) {
            SecuredLog.crash(Ev.MEDIA_CATCHUP, t)
        }
    }

    /** After a failed start: put the unreachable proxy back if it was lifted, then stop the chat. Never throws. */
    private fun failSafe(relock: Boolean) {
        if (relock) {
            try {
                send(NETWORK_LOCKED)
            } catch (t: Throwable) {
                SecuredLog.crash(Ev.START_FAILED, t)
            }
        }
        try {
            stopChatQuietly()
        } catch (t: Throwable) {
            chatStarted = false
            SecuredLog.crash(Ev.START_FAILED, t)
        }
    }

    /** `networkConfig` body: every connection through the unreachable SOCKS proxy; unknown relays only proxied. */
    private fun networkLockedOk(body: JSONObject): Boolean {
        val cfg = body.optJSONObject("networkConfig") ?: return false
        val socks = cfg.str("socksProxy") ?: return false
        return (socks == LOCK_SOCKS_ADDR || socks.endsWith("@$LOCK_SOCKS_ADDR")) &&
            cfg.str("socksMode") == "always" &&
            cfg.str("smpProxyMode") == "unknown" &&
            cfg.str("smpProxyFallback") == "prohibit"
    }

    private fun markFirstRunLockdown(): Boolean = try {
        val m = lockdownMarker
        m.exists() || m.createNewFile() || m.exists()
    } catch (e: Exception) {
        false
    }

    private fun clearFirstRunLockdown() {
        val m = lockdownMarker
        // If it can't be deleted, later starts just lock the network again until it can.
        if (!m.delete() && m.exists()) SecuredLog.w(Ev.CONFIG_STEP_FAILED, "lockdown_clear")
    }

    /** The core adds an upstream "team" contact card to every new user. It is not ours: delete it (no network). */
    private fun removePresetContactCards() {
        val r = send(Commands.getChats(userId))
        if (r !is CoreReply.Result || r.type != "apiChats") return
        for (chat in CoreJson.parseChats(r.body)) {
            if (!chat.contact.isContactCard) continue
            if (send(Commands.deleteContactQuietly(chat.contact.contactId)) is CoreReply.Result) {
                SecuredLog.i(Ev.PRESET_CARD_REMOVED)
            }
        }
    }

    private fun stopOnEngine() {
        stopWatchingNetwork()
        if (ctrl == 0L) {
            if (state != EngineState.NOT_STARTED) setState(EngineState.NOT_STARTED)
            return
        }
        stopReceiver()
        stopChatQuietly()
        media.onStop()
        ctrlGate.close {
            try {
                chatCloseStore(ctrl)
            } catch (t: Throwable) {
                SecuredLog.crash(Ev.STOPPED, t)
            }
            ctrl = 0L
        }
        media.afterClose()
        userId = 0L
        chatCache.clear()
        lastItemIds.clear()
        timedChats.clear()
        groupCache.clear()
        hiddenChats = emptySet()
        earlierProofs = emptyMap()
        ownSentCache.clear()
        ownHistorySwept.clear()
        memberIdCache.clear()
        previewCache.clear()
        refreshWindowOpen = false
        refreshOwed = false
        noteFolderId = null
        publishChats()
        setState(EngineState.NOT_STARTED)
        notifyChatsChanged()
        SecuredLog.i(Ev.STOPPED)
    }

    private fun stopChatQuietly() {
        if (!chatStarted) return
        send(Commands.STOP_CHAT)
        chatStarted = false
    }

    private fun failClosed(f: StartFailure) {
        SecuredLog.e(Ev.START_FAILED, f.logTag)
        lastFailure = f.error
        setState(EngineState.FAILED)
        val e = f.error
        main.post { for (l in listeners) l.onError(e) }
    }

    // =============================================================================================================
    // Receiving and events
    // =============================================================================================================

    private fun startReceiver() {
        val current = receiver
        if (current != null && current.running && current.ctrl == ctrl && current.thread.isAlive) return
        current?.running = false
        val r = Receiver(ctrl)
        r.thread = Thread({ receiveLoop(r) }, "encrogram-secured-recv").apply { isDaemon = true }
        receiver = r
        r.thread.start()
    }

    /** Exits as soon as its own token is stopped or its controller is no longer the current one. */
    private fun receiveLoop(r: Receiver) {
        SecuredLog.i(Ev.RECEIVER_STARTED)
        while (r.running && ctrl == r.ctrl) {
            val raw = try {
                chatRecvMsgWait(r.ctrl, RECV_WAIT_MICROS)
            } catch (t: Throwable) {
                SecuredLog.crash(Ev.RECEIVER_ERROR, t)
                try {
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    break
                }
                continue
            }
            if (!r.running || ctrl != r.ctrl) break
            if (raw.isEmpty()) continue
            val reply = CoreJson.parseReply(raw)
            if (reply === CoreReply.Invalid) {
                SecuredLog.w(Ev.EVENT_PARSE_FAILED)
                continue
            }
            // Re-checked on the engine thread: an event from a replaced receiver is dropped.
            post { if (receiver === r) handleEvent(reply) }
        }
        SecuredLog.i(Ev.RECEIVER_STOPPED)
    }

    private fun stopReceiver() {
        val r = receiver ?: return
        r.running = false
        receiver = null
        try {
            r.thread.join(RECV_WAIT_MICROS / 1000L + 2000L)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun handleEvent(reply: CoreReply) {
        // Our check of a file description, in any state (a pure check and a ledger write); catch-up acts on it later.
        if (reply is CoreReply.Result && reply.type == "rcvFileDescrReady") return media.recordDescription(reply.body)
        if (state != EngineState.RUNNING) return
        if (reply is CoreReply.Result) {
            try {
                backupOnEvent(reply)
            } catch (t: Throwable) {
                SecuredLog.crash(Ev.RESTORE_REPAIR, t)
            }
        }
        when (reply) {
            is CoreReply.Error -> SecuredLog.w(Ev.EVENT_ERROR, reply.error.tag)
            is CoreReply.Result -> when (reply.type) {
                "newChatItems" -> {
                    val parsed = CoreJson.parseChatItems(reply.body)
                    // A group row we don't know yet (a join that just came up): load the list first (applyNewItems
                    // would do it anyway), so a rejoin is recognised before its history is mapped.
                    if (parsed.any { it.group && it.chatId !in chatCache }) refreshChats(notify = true)
                    val items = localView(parsed)
                    // Our own earlier messages in a rejoin's history: read in the core before the row counts them.
                    markOwnHistoryRead(items)
                    applyNewItems(items)
                    // Incoming messages, and service rows in either direction ("You left" comes from our own command
                    // as an event). Notes are always ours (outgoing) and never notify; the chat id check keeps it so.
                    // Our own earlier messages from a rejoin's history go to an open chat too; being outgoing, they
                    // never notify (SecuredService.notifies).
                    val incoming = items.filter { it.chatId != SAVED_MESSAGES_CHAT_ID && ((it.isMessage && (!it.outgoing || it.earlierOwn)) || it.event != null) }
                        .map { MessageMapper.toMessage(it, roleIn(it.chatId)) }
                    if (incoming.isNotEmpty()) main.post { for (m in incoming) for (l in listeners) l.onMessageReceived(m) }
                }
                "chatItemsStatusesUpdated" -> {
                    val items = CoreJson.parseChatItems(reply.body).filter { it.isMessage }
                    if (items.isNotEmpty()) {
                        main.post { for (i in items) for (l in listeners) l.onMessageStatusChanged(i.chatId, i.itemId, i.status) }
                        applyStatuses(items)
                    }
                }
                // An edit, by us on another path or by the contact.
                "chatItemUpdated" -> CoreJson.parseAChatItem(reply.body.optJSONObject("chatItem"))?.let { applyItemUpdated(it) }
                "chatItemReaction" -> CoreJson.parseReactionItem(reply.body)?.let { emitChanged(listOf(it)) }
                // Deleted by the contact (byUser=false) or a disappearing message that expired (timed=true).
                "chatItemsDeleted" -> {
                    SecuredLog.i(Ev.EVENT, if (reply.body.optBoolean("timed", false)) "chatItemsDeleted.timed" else reply.type)
                    applyDeletions(CoreJson.parseDeletions(reply.body))
                }
                // File states and progress: onMessageChanged / onMediaProgress only, never a new message or notification.
                in MediaCoordinator.EVENTS -> media.onEvent(reply.type, reply.body)
                in CHAT_LIST_EVENTS -> {
                    SecuredLog.i(Ev.EVENT, reply.type)
                    refreshSoon()
                }
                "chatErrors" -> SecuredLog.w(Ev.EVENT_ERROR, "chatErrors", reply.body.optJSONArray("chatErrors")?.length())
                "messageError" -> SecuredLog.w(Ev.EVENT_ERROR, "messageError")
                else -> Unit
            }
            CoreReply.Invalid -> Unit
        }
    }

    // =============================================================================================================
    // The phone's network
    // =============================================================================================================

    /** Starts watching the network (once) and tells a newly opened store what it is now. */
    private fun watchNetwork() {
        if (!watchingNetwork) {
            watchingNetwork = true
            network.start()
        }
        sendNetworkInfo()
    }

    private fun stopWatchingNetwork() {
        if (watchingNetwork) {
            watchingNetwork = false
            network.stop()
        }
        netInfo = null
        setOnline(true)
    }

    /**
     * A real change from the watch (offline already held back a few seconds): tell the core, then the screens. When the
     * phone moves to another network, or a network comes back, relay connections opened on the old one can go silent
     * without an error (seen on device: opened over mobile data a second before Wi-Fi took over), and the agent keeps
     * such a connection until 3 pings time out and 15 minutes pass without a reply (simplexmq Client.hs:660-674, pings
     * every 10 minutes). Meanwhile every send and the subscriptions sit on it. So once the network settles, reconnect.
     */
    private fun applyNetwork(info: NetInfo) {
        if (!watchingNetwork) return
        val previous = netInfo
        netInfo = info
        sendNetworkInfo()
        setOnline(info.usable)
        if (previous != null && info.coreOnline && (!previous.coreOnline || previous.handle != info.handle)) {
            val gen = ++reconnectGen
            main.postDelayed({ post { if (gen == reconnectGen) reconnectAll() } }, RECONNECT_SETTLE_MS)
        }
    }

    private fun sendNetworkInfo() {
        val info = netInfo ?: return
        if (ctrl == 0L) return
        if (!info.coreOnline && !TELL_CORE_OFFLINE) return
        val r = send(Commands.networkInfo(info))
        if (r is CoreReply.Result && r.type == "cmdOk") SecuredLog.i(Ev.NETWORK, if (info.coreOnline) info.type else "offline")
    }

    /** `/reconnect` on the network the phone settled on; only with a running chat and a network. */
    private fun reconnectAll() {
        if (state != EngineState.RUNNING || ctrl == 0L || netInfo?.coreOnline != true) return
        val r = send(Commands.RECONNECT_ALL)
        if (r is CoreReply.Result && r.type == "cmdOk") SecuredLog.i(Ev.NETWORK, "reconnect")
    }

    private fun setOnline(value: Boolean) {
        if (online == value) return
        online = value
        main.post { for (l in listeners) l.onConnectionChanged(value) }
    }

    // =============================================================================================================
    // Chat list cache
    // =============================================================================================================

    /**
     * The newest item a row can preview: a message or a service row we show. `apiChats` gives each chat's newest item
     * only (by item time, Store/Messages.hs:895-913), and that is often one we skip: after a link join, the members
     * who were there before connecting to us, a feature or profile change. Then look back through the chat, a few
     * pages at most; [newest] when nothing there is shown. Cached per chat while its newest item stays the same
     * ([previewCache]; [applyNewItems] moves the key along for each new item we skip, so a burst of skipped rows
     * costs one look-back, not one per reload).
     */
    private fun lastShown(chatId: Long, chat: ChatRef, newest: CoreItem?): CoreItem? {
        if (newest == null || newest.shown) {
            previewCache.remove(chatId)
            return newest
        }
        previewCache[chatId]?.let { (seen, found) -> if (seen == newest.itemId) return found ?: newest }
        var before: Long? = null
        for (attempt in 1..LOOKBACK_PAGES) {
            val r = send(Commands.getChat(chat, LOOKBACK_COUNT, beforeId = before))
            // Not cached: the next reload tries again.
            if (r !is CoreReply.Result || r.type != "apiChat") return newest
            val page = localView(CoreJson.parseChatItemsOfChat(r.body))
            CoreJson.newestShown(page)?.let {
                previewCache[chatId] = newest.itemId to it
                return it
            }
            val raw = r.body.optJSONObject("chat")?.optJSONArray("chatItems")?.length() ?: 0
            before = page.minWithOrNull(CoreJson.ITEM_ORDER)?.itemId
            if (raw < LOOKBACK_COUNT || before == null) break
        }
        previewCache[chatId] = newest.itemId to null
        return newest
    }

    /**
     * A list reload for an event: at once, then at most one more per [REFRESH_WINDOW_MS] however many events come in
     * meanwhile (joining a big group brings one event per member connecting to us).
     */
    private fun refreshSoon() {
        if (refreshWindowOpen) {
            refreshOwed = true
            return
        }
        refreshWindowOpen = true
        refreshChats(notify = true)
        main.postDelayed({ post { refreshWindowEnded() } }, REFRESH_WINDOW_MS)
    }

    private fun refreshWindowEnded() {
        if (!refreshOwed || state != EngineState.RUNNING) {
            refreshWindowOpen = false
            refreshOwed = false
            return
        }
        refreshOwed = false
        refreshChats(notify = true)
        main.postDelayed({ post { refreshWindowEnded() } }, REFRESH_WINDOW_MS)
    }

    /**
     * Items as this phone shows them, on every path that maps items (a chat page and search, new items, edits and
     * reactions, the list's last item, single-item checks): link joins worded ([withLinkJoins]) and our own earlier
     * messages in a rejoin's history made ours when proven ([GroupRules.markOwnHistory] with [earlierProofs]), and what
     * this phone knows about their files ([MediaCoordinator.annotate]; nothing to do for items without one).
     */
    private fun localView(items: List<CoreItem>): List<CoreItem> =
        media.annotate(GroupRules.markOwnHistory(withLinkJoins(items)) { earlierProofs[it] })

    /**
     * Our own sent messages in an ended group row ([GroupRules.ownSent] over its newest [OWN_SENT_READ] items: a rejoin's
     * history is the group's newest 100), read once and kept. Null when the core couldn't give them: nothing from that
     * row is then treated as ours (never memberId alone), and the next list load asks again.
     */
    private fun ownSentOf(g: CoreGroup): Map<String, String>? {
        ownSentCache[g.groupId]?.let { return it }
        val r = send(Commands.getChat(ChatRef.group(g.groupId), OWN_SENT_READ))
        if (r !is CoreReply.Result || r.type != "apiChat") {
            SecuredLog.w(Ev.GROUP_ACTION, "own_sent_unreadable")
            return null
        }
        val sent = GroupRules.ownSent(CoreJson.parseChatItemsOfChat(r.body))
        ownSentCache[g.groupId] = sent
        SecuredLog.i(Ev.GROUP_ACTION, "own_sent_read", sent.size)
        return sent
    }

    /**
     * Marks our own earlier messages from a rejoin's history ([CoreItem.earlierOwn]) read in the core while it still
     * counts them unread (rcvNew), so no unread count, here or in the core, includes them. Returns how many were
     * marked, or null when the core refused one of the commands (the next list load looks again).
     */
    private fun markOwnHistoryRead(items: List<CoreItem>): Int? {
        val byChat = items.filter { it.earlierOwn && it.statusTag == "rcvNew" }.groupBy { it.chatId }
        if (byChat.isEmpty()) return 0
        var marked = 0
        var failed = false
        for ((chatId, list) in byChat) {
            val groupId = CoreJson.groupIdFromChatId(chatId) ?: continue
            for (ids in list.map { it.itemId }.distinct().chunked(READ_ITEMS_CHUNK)) {
                if (isResult(send(Commands.readItems(ChatRef.group(groupId), ids)), "itemsReadForChat")) marked += ids.size else failed = true
            }
        }
        SecuredLog.i(Ev.GROUP_ACTION, if (failed) "own_history_read_failed" else "own_history_read", marked)
        return if (failed) null else marked
    }

    /**
     * Once per rejoined row and proof, while the core counts unread items there: our own earlier messages that arrived
     * while no listener ran (before the engine was running, or before the rejoin was recognised) are marked read
     * ([markOwnHistoryRead]). History is at most 100 messages, so the newest [OWN_HISTORY_SWEEP] items cover it right
     * after a join. Returns how many it marked, to take off the row's count.
     */
    private fun sweepOwnHistory(g: CoreGroup, unread: Int): Int {
        val proofs = earlierProofs[g.chatId] ?: return 0
        if (unread <= 0 || ownHistorySwept[g.chatId] == proofs) return 0
        val r = send(Commands.getChat(ChatRef.group(g.groupId), OWN_HISTORY_SWEEP))
        // Not remembered: the next list load looks again.
        if (r !is CoreReply.Result || r.type != "apiChat") return 0
        val marked = markOwnHistoryRead(GroupRules.markOwnHistory(CoreJson.parseChatItemsOfChat(r.body)) { earlierProofs[it] }) ?: return 0
        ownHistorySwept[g.chatId] = proofs
        return marked
    }

    /**
     * Remembers who came in through our group links (from their invitedViaGroupLink rows, live or on a page) and words
     * their join rows "joined via invite link". Stored, because the two rows are seconds apart and pages split them.
     */
    private fun withLinkJoins(items: List<CoreItem>): List<CoreItem> {
        for (i in items) {
            val key = CoreJson.linkJoinKey(i.chatId, i.linkJoinOf ?: continue) ?: continue
            if (!linkJoins.set(key, true)) SecuredLog.w(Ev.ARCHIVE_IO_FAILED, "link_joins")
        }
        return CoreJson.markLinkJoins(items) { chatId, memberId ->
            CoreJson.linkJoinKey(chatId, memberId)?.let { linkJoins.isArchived(it) } == true
        }
    }

    private fun refreshChats(notify: Boolean) {
        val r = send(Commands.getChats(userId))
        if (r !is CoreReply.Result || r.type != "apiChats") return
        val chats = CoreJson.parseChats(r.body)
        val groups = CoreJson.parseGroupChats(r.body)
        // Group chat ids are kept too: pruning only by contact ids would un-archive every group.
        val known = chats.mapTo(HashSet()) { it.contact.contactId }.apply { groups.mapTo(this) { it.group.chatId } }
        if (!archive.retainOnly(known)) SecuredLog.w(Ev.ARCHIVE_IO_FAILED, "prune")
        val groupIds = groups.mapTo(HashSet()) { it.group.groupId }
        if (!linkJoins.retainIf { CoreJson.linkJoinGroupId(it) in groupIds }) SecuredLog.w(Ev.ARCHIVE_IO_FAILED, "link_joins_prune")
        previewCache.keys.retainAll(known)
        chatCache.clear()
        lastItemIds.clear()
        timedChats.clear()
        groupCache.clear()
        for (listed in chats) {
            val c = listed.contact
            if (c.isContactCard || c.removed) continue
            val chat = listed.copy(lastItem = lastShown(c.contactId, ChatRef.contact(c.contactId), listed.lastItem))
            chatCache[c.contactId] = MessageMapper.summaryOf(chat, archive.isArchived(c.contactId))
            // Messages and the service rows we show (lastEvent): edits and deletions of either reload the row.
            chat.lastItem?.takeIf { it.shown }?.let { lastItemIds[c.contactId] = it.itemId }
            if (c.timedEnabled) timedChats.add(c.contactId)
        }
        // One row per group, like Telegram: a rejoin's new row replaces the old left or removed one. Worked out before the
        // rows are built, so our own earlier messages in a rejoin's history already read as ours in the preview.
        val replaced = rejoins(groups.map { it.group })
        val hidden = replaced.keys.mapTo(HashSet()) { CoreJson.groupChatId(it) }
        if (hidden != hiddenChats) SecuredLog.i(Ev.GROUP_ACTION, "rejoin_rows_hidden", hidden.size)
        hiddenChats = hidden
        // Proof of what we sent before, from the earlier rows (each read once, ownSentOf).
        val earlierRows = GroupRules.earlierRows(groups.map { it.group }, replaced)
        ownSentCache.keys.retainAll(earlierRows.values.flatMapTo(HashSet()) { rows -> rows.map { it.groupId } })
        earlierProofs = earlierRows.mapValues { (_, rows) -> GroupRules.ownProofs(rows) { ownSentOf(it) } }.filterValues { it.isNotEmpty() }
        ownHistorySwept.keys.retainAll(earlierProofs.keys)
        for (listed in groups) {
            val id = listed.group.chatId
            groupCache[id] = listed.group
            // Invited: nothing is previewed before joining, so no look-back.
            val newest = listed.lastItem?.let { localView(listOf(it)).single() }
            // The look-back may come from its cache, made before a rejoin was recognised: mapped again (a no-op otherwise).
            val last = if (listed.group.invited) newest else lastShown(id, ChatRef.group(listed.group.groupId), newest)?.let { localView(listOf(it)).single() }
            val unread = listed.unreadCount - sweepOwnHistory(listed.group, listed.unreadCount)
            val chat = listed.copy(lastItem = last, unreadCount = unread.coerceAtLeast(0))
            chatCache[id] = MessageMapper.groupSummary(chat, archive.isArchived(id))
            chat.lastItem?.takeIf { it.shown }?.let { lastItemIds[id] = it.itemId }
        }
        // Saved Messages: the user's note folder (the core creates one with every user).
        val notes = CoreJson.parseNoteChat(r.body)
        noteFolderId = notes?.folder?.noteFolderId
        if (notes != null) {
            chatCache[SAVED_MESSAGES_CHAT_ID] = MessageMapper.notesSummary(notes)
            notes.lastItem?.takeIf { it.isMessage }?.let { lastItemIds[SAVED_MESSAGES_CHAT_ID] = it.itemId }
        }
        SecuredLog.i(Ev.CHATS_LOADED, count = chatCache.size)
        publishChats()
        if (notify) {
            notifyChatsChanged()
            markChanged()
        }
    }

    /**
     * Updates the cached rows for new messages and service rows we show (ChatSummary.lastEvent); reloads the list when
     * an item belongs to a chat we don't know.
     */
    private fun applyNewItems(items: List<CoreItem>) {
        if (items.isNotEmpty()) markChanged()
        var changed = false
        var unknownChat = false
        for (item in items) {
            val row = chatCache[item.chatId]
            if (row == null) {
                unknownChat = true
                continue
            }
            if (!item.shown) {
                // The row keeps its preview, and a cached look-back stays right with this item as the chat's newest.
                previewCache[item.chatId]?.let { previewCache[item.chatId] = item.itemId to it.second }
                continue
            }
            previewCache.remove(item.chatId)
            chatCache[item.chatId] = MessageMapper.withNewItem(row, item)
            lastItemIds[item.chatId] = item.itemId
            changed = true
        }
        if (unknownChat) {
            refreshChats(notify = true)
        } else if (changed) {
            publishChats()
            notifyChatsChanged()
        }
    }

    /** Delivery ticks of a row's last message. */
    private fun applyStatuses(items: List<CoreItem>) {
        var changed = false
        for (item in items) {
            if (!item.outgoing || lastItemIds[item.chatId] != item.itemId) continue
            previewCache.remove(item.chatId)
            val row = chatCache[item.chatId] ?: continue
            if (row.lastMessageStatus == item.status) continue
            chatCache[item.chatId] = row.copy(lastMessageStatus = item.status)
            changed = true
        }
        if (changed) {
            publishChats()
            notifyChatsChanged()
        }
    }

    /** An edited (or otherwise updated) message or service row: tell the UI; reload the list when it is a row's last message. */
    private fun applyItemUpdated(item: CoreItem) {
        if (!item.shown) return
        emitChanged(listOf(item))
        if (lastItemIds[item.chatId] == item.itemId) {
            previewCache.remove(item.chatId)
            refreshChats(notify = true)
        }
    }

    /** Fully deleted -> onMessagesRemoved; only marked deleted -> onMessageChanged (deleted=true, text ""). */
    private fun applyDeletions(deletions: List<CoreDeletion>) {
        if (deletions.isEmpty()) return
        markChanged()
        val removed = LinkedHashMap<Long, MutableList<Long>>()
        val marked = ArrayList<CoreItem>()
        for (d in deletions) {
            // A message only marked deleted, or replaced by a "deleted by admin" row, changes in place.
            val now = d.replacement
            if (now == null || !now.shown) removed.getOrPut(d.chatId) { ArrayList() }.add(d.itemId) else marked.add(now)
        }
        // Removed or only marked deleted: their playbacks close and their files' ledger rows go.
        deletions.groupBy({ it.chatId }, { it.itemId }).forEach { (chatId, ids) -> media.onRemoved(chatId, ids) }
        if (removed.isNotEmpty()) {
            val gone = removed.mapValues { it.value.toList() }
            main.post { for ((chatId, ids) in gone) for (l in listeners) l.onMessagesRemoved(chatId, ids) }
        }
        emitChanged(marked)
        val lastGone = deletions.filter { lastItemIds[it.chatId] == it.itemId }
        if (lastGone.isNotEmpty()) {
            for (d in lastGone) previewCache.remove(d.chatId)
            refreshChats(notify = true)
        }
    }

    private fun emitChanged(items: List<CoreItem>) {
        val messages = localView(items).filter { it.shown }.map { MessageMapper.toMessage(it, roleIn(it.chatId)) }
        if (messages.isEmpty()) return
        markChanged()
        main.post { for (m in messages) for (l in listeners) l.onMessageChanged(m) }
    }

    private fun publishChats() {
        val hidden = hiddenChats
        chatSnapshot = MessageMapper.sortChats(if (hidden.isEmpty()) chatCache.values else chatCache.values.filter { it.chatId !in hidden })
    }

    /**
     * Old group rows that a rejoin replaced, old groupId -> newer groupId ([GroupRules.successors]). Asks the core for
     * member lists only when an ended membership and a newer row share a name, and again only when that row's member
     * count changes.
     */
    private fun rejoins(groups: List<CoreGroup>): Map<Long, Long> {
        memberIdCache.keys.retainAll(groups.mapTo(HashSet()) { it.groupId })
        return GroupRules.successors(groups) { memberIdsOf(it) }
    }

    private fun memberIdsOf(g: CoreGroup): Set<String>? {
        memberIdCache[g.groupId]?.let { (count, ids) -> if (count == g.memberCount) return ids }
        val r = send(Commands.listMembers(g.groupId))
        // Not cached: the next reload asks again.
        if (r !is CoreReply.Result || r.type != "groupMembers") return null
        val ids = CoreJson.otherMemberIds(r.body)
        memberIdCache[g.groupId] = g.memberCount to ids
        return ids
    }

    // =============================================================================================================
    // Plumbing
    // =============================================================================================================

    /**
     * Runs one engine command on the engine thread. Logs only reply/error type tags; an error whose detail is in
     * [expected] is an answer the caller asked for, not a failure, and isn't logged as CMD_ERROR.
     */
    private fun send(cmd: String, expected: Set<String> = emptySet()): CoreReply {
        val c = ctrl
        if (c == 0L) return CoreReply.Error(CoreError("error", "chatNotStarted", null))
        val raw = try {
            chatSendCmdRetry(c, cmd, 0)
        } catch (t: Throwable) {
            SecuredLog.crash(Ev.CMD_INVALID_REPLY, t)
            return CoreReply.Invalid
        }
        val reply = CoreJson.parseReply(raw)
        when (reply) {
            is CoreReply.Error -> if (reply.error.detail !in expected) SecuredLog.w(Ev.CMD_ERROR, reply.error.tag)
            CoreReply.Invalid -> SecuredLog.w(Ev.CMD_INVALID_REPLY)
            is CoreReply.Result -> Unit
        }
        return reply
    }

    private fun expectResult(reply: CoreReply, step: String, vararg types: String): CoreReply.Result {
        if (reply is CoreReply.Result && reply.type in types) return reply
        throw configFailure(step, reply)
    }

    private fun configFailure(step: String, reply: CoreReply?): StartFailure {
        val replyTag = when (reply) {
            is CoreReply.Result -> reply.type
            is CoreReply.Error -> reply.error.tag
            CoreReply.Invalid -> "invalid"
            null -> null
        }
        SecuredLog.e(Ev.CONFIG_STEP_FAILED, step)
        if (replyTag != null) SecuredLog.e(Ev.CMD_UNEXPECTED_REPLY, replyTag)
        return StartFailure(
            SecuredError(
                SecuredError.Kind.ENGINE,
                "Secured stopped for safety: it couldn't confirm that it uses only Encrogram's servers ($step).",
            ),
            step,
        )
    }

    private fun unexpected(r: CoreReply.Result): SecuredError {
        SecuredLog.w(Ev.CMD_UNEXPECTED_REPLY, r.type)
        return ErrorMapper.engine(r.type)
    }

    private fun <T> ensureRunning(callback: SecuredCallback<T>): Boolean {
        if (state == EngineState.RUNNING && ctrl != 0L) return true
        err(callback, ErrorMapper.notRunning(state))
        return false
    }

    private fun setState(s: EngineState) {
        if (state == s) return
        state = s
        if (s != EngineState.FAILED) lastFailure = null
        SecuredLog.i(Ev.STATE, s.name)
        main.post { for (l in listeners) l.onStateChanged(s) }
        // Events handled while not RUNNING are dropped: every screen reloads once when RUNNING is reached.
        if (s == EngineState.RUNNING) notifyChatsChanged()
    }

    private fun notifyChatsChanged() {
        main.post { for (l in listeners) l.onChatsChanged() }
    }

    private fun <T> ok(callback: SecuredCallback<T>, value: T) {
        val result = SecuredResult.Ok(value)
        main.post { callback.onResult(result) }
    }

    private fun <T> err(callback: SecuredCallback<T>, error: SecuredError) {
        main.post { callback.onResult(SecuredResult.Err(error)) }
    }

    private fun post(task: () -> Unit) {
        executor.execute {
            try {
                task()
            } catch (t: Throwable) {
                SecuredLog.crash(Ev.TASK_CRASHED, t)
            }
        }
    }

    private fun <T> post(callback: SecuredCallback<T>, task: () -> Unit) {
        executor.execute {
            try {
                task()
            } catch (t: Throwable) {
                SecuredLog.crash(Ev.TASK_CRASHED, t)
                err(callback, ErrorMapper.engine("internal"))
            }
        }
    }

    private companion object {
        const val DATA_DIR = "encrogram-secured"
        const val DB_PREFIX = "secured"
        const val LOCKDOWN_MARKER = "first_run_lockdown"
        const val MIGRATION_CONFIRM = "yesUp"
        const val RECV_WAIT_MICROS = 2_000_000
        /** [lastShown]: items per page and pages at most (a link join into a big group adds a row per earlier member). */
        const val LOOKBACK_COUNT = 50
        const val LOOKBACK_PAGES = 3
        /** [refreshSoon]: at most one event-driven list reload per this long. */
        const val REFRESH_WINDOW_MS = 500L
        /** [sweepOwnHistory]: newest items searched (a rejoin's history is at most 100, Library/Internal.hs:1320). */
        const val OWN_HISTORY_SWEEP = 200
        /** [ownSentOf]: newest items of an ended row read for our own sent messages (history: the newest 100). */
        const val OWN_SENT_READ = 200
        /** [markOwnHistoryRead]: item ids per `/_read chat items` command. */
        const val READ_ITEMS_CHUNK = 100
        /** Members who joined through our group links ([withLinkJoins]), next to the databases. */
        const val LINK_JOINS_FILE = "link_joins"
        /** [applyNetwork]: reconnect once the default network has stayed the same this long. */
        const val RECONNECT_SETTLE_MS = 2_000L
        /**
         * Tell the core "offline" when the phone has no network at all (held back 3 s), so it retries at the moment the
         * network is back (RetryInterval.hs:78-85, Client.hs:961-965). False: never tell it; its own back-off runs as
         * before (about 15 s after a short outage, up to 2 min after a long one). The reconnect after a network switch
         * runs either way.
         */
        const val TELL_CORE_OFFLINE = true
        /** Commands.hs mkValidName keeps at most 50 characters (code points) of a display name. */
        const val MAX_NAME_CHARS = 50
        /** Local-only archive flags ([ArchiveStore]), next to the databases. */
        const val ARCHIVE_FILE = "archived_chats"

        /** Backup files ([BackupStore], [KeyVault]), next to the databases. */
        const val BACKUP_DIR = "backup"
        const val RESTORE_MARKER = "restore_in_progress"
        /** `/_stop` cancels the agent's threads asynchronously (Commands.hs:353); wait before copying the databases. */
        const val STOP_SETTLE_MS = 500L
        /** The daily backup's timer never fires sooner than this (the app settles first). */
        const val MIN_TIMER_MS = 60_000L
        /** An owed (urgent) upload, e.g. right after a restore, waits at least this long. */
        const val AUTO_MIN_DELAY_MS = 5_000L
        /** A timer that fires this early still counts as due. */
        const val TIMER_SLACK_MS = 2_000L
        /** A new contact: back up after a short pause, so several connections share one backup. */
        const val CONTACT_BACKUP_DELAY_MS = 10_000L
        /** A finished queue switch or repair sync: upload after this pause; each further one in a burst pushes it again... */
        const val SWITCH_BACKUP_DELAY_MS = 30_000L
        /** ...but never later than this after the first request of the burst. */
        const val MAX_COALESCE_MS = 5L * 60 * 1000
        /**
         * A busy server (429/503) gets one more try after this long, without counting a failure (R6.7); nginx refills
         * one request per 10 s.
         */
        const val BUSY_RETRY_MS = 15_000L
        /** How long findBackup's download is kept for the restore that follows (sealed bytes only). */
        const val FOUND_CACHE_MS = 10L * 60 * 1000
        /** A restored phone's upload counter starts this far above the restored copy's (R6.2 part B). */
        const val RESTORE_COUNTER_STEP = 1_000_000L
        /** Automatic ratchet re-sync at most this often per connection. */
        const val AUTO_SYNC_GAP_MS = 10L * 60 * 1000
        /** A guard re-check runs this long after the guard ends (R6.6). */
        const val SYNC_RECHECK_SLACK_MS = 2_000L
        /** `/u` before any profile exists (ChatErrorType CENoActiveUser). */
        val NO_USER_YET = setOf("noActiveUser")

        /**
         * `/_info` errors that mean the connection is gone for good: contacts (errorStore contactNotFound, error
         * contactNotActive) and members (StoreError groupMemberNotFound / groupNotFound, Store/Shared.hs:98-101; error
         * groupMemberNotActive / groupMemberNotFound, Controller.hs:1473-1476).
         */
        val GONE_CONNECTION_ERRORS = setOf("contactNotFound", "contactNotActive", "groupMemberNotFound", "groupMemberNotActive", "groupNotFound")

        /** The unreachable SOCKS proxy for the first-run lock (port 9 is privileged: no app can listen on it). */
        const val LOCK_SOCKS_ADDR = "127.0.0.1:9"
        /**
         * SetNetworkConfig (netCfgP, Commands.hs): SOCKS for every host (socks-mode=always) to a closed port, same
         * proxy settings as [Commands.NETWORK_CONFIG]. Replaced by NETWORK_CONFIG once our servers are verified.
         */
        const val NETWORK_LOCKED = "/network socks=$LOCK_SOCKS_ADDR socks-mode=always smp-proxy=unknown smp-proxy-fallback=no"

        /**
         * Events after which the chat list is reloaded from the engine. Groups (Controller.hs:939-1000): an invitation,
         * joins and connections (member counts, our status), roles, removals, leaves, deletes and profile changes.
         */
        val CHAT_LIST_EVENTS = setOf(
            "contactConnecting", "contactConnected", "contactSndReady", "contactUpdated", "contactDeletedByContact",
            "contactAnotherClient", "contactDisabled", "contactRatchetSync", "acceptingContactRequest",
            "chatInfoUpdated", "chatCleared",
            "receivedGroupInvitation", "userJoinedGroup", "joinedGroupMember", "joinedGroupMemberConnecting",
            "connectedToGroupMember", "memberAcceptedByOther", "acceptingGroupJoinRequestMember", "memberRole",
            "memberBlockedForAll", "deletedMember", "deletedMemberUser", "leftMember", "groupDeleted", "groupUpdated",
            "groupMemberUpdated", "groupLinkConnecting",
        )
    }
}

/**
 * A text reply's body budget: the core copies the quoted text (a captionless file: its name) and, for a photo or video,
 * its preview into the reply (Internal.hs:314-335, quoteContent). For a text quote this is plain [InputRules.replyFits].
 */
internal fun textReplyFits(text: String, quoted: CoreItem): Boolean =
    InputRules.replyFits(text, quoted.text.ifEmpty { quoted.file?.name.orEmpty() }, quoted.preview?.length ?: 0)
