package app.encrogram.core.internal

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.encrogram.core.api.ChatSummary
import app.encrogram.core.api.EngineState
import app.encrogram.core.api.LinkCheck
import app.encrogram.core.api.RelayConfig
import app.encrogram.core.api.SecuredCallback
import app.encrogram.core.api.SecuredEngine
import app.encrogram.core.api.SecuredError
import app.encrogram.core.api.SecuredInvite
import app.encrogram.core.api.SecuredListener
import app.encrogram.core.api.SecuredMessage
import app.encrogram.core.api.SecuredProfile
import app.encrogram.core.api.SecuredResult
import app.encrogram.core.internal.ErrorMapper.Op
import app.encrogram.core.internal.SecuredLog.Ev
import chat.simplex.common.platform.chatCloseStore
import chat.simplex.common.platform.chatMigrateInit
import chat.simplex.common.platform.chatRecvMsgWait
import chat.simplex.common.platform.chatSendCmdRetry
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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
    private var receiver: Receiver? = null

    private val dataDir: File get() = File(appContext.noBackupFilesDir, DATA_DIR)

    /** Present from profile creation until our servers have been verified once: start with the network locked. */
    private val lockdownMarker: File get() = File(dataDir, LOCKDOWN_MARKER)

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

    override fun chats(): List<ChatSummary> = chatSnapshot

    override fun messages(chatId: Long, limit: Int, callback: SecuredCallback<List<SecuredMessage>>) = post(callback) {
        if (!ensureRunning(callback)) return@post
        val want = limit.coerceIn(1, MAX_PAGE)
        // Service items (e.g. "connected", encryption notices) count against the engine's page, so ask for more.
        when (val r = send(Commands.getChat(chatId, minOf(want + 50, MAX_PAGE * 2)))) {
            is CoreReply.Result ->
                if (r.type == "apiChat") {
                    val msgs = CoreJson.parseChatItemsOfChat(r.body).filter { it.isMessage }.takeLast(want).map { it.toMessage() }
                    ok(callback, msgs)
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
                    LinkCheck.Approved -> ok(callback, SecuredInvite(link))
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

    override fun checkLink(link: String): LinkCheck = LinkChecker.check(link, config.allowedHosts, config.smpServers)

    override fun connect(link: String, callback: SecuredCallback<Unit>) = post(callback) {
        if (!ensureRunning(callback)) return@post
        val analysis = LinkChecker.analyze(link, config.allowedHosts)
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

    override fun sendText(chatId: Long, text: String, callback: SecuredCallback<SecuredMessage>) = post(callback) {
        if (!ensureRunning(callback)) return@post
        if (text.isBlank()) return@post err(callback, SecuredError(SecuredError.Kind.UNKNOWN, "The message is empty."))
        if (encodedTextBytes(text) > MAX_TEXT_BYTES) return@post err(callback, SecuredError(SecuredError.Kind.UNKNOWN, "The message is too long."))
        when (val r = send(Commands.sendText(chatId, text))) {
            is CoreReply.Result -> {
                val item = if (r.type == "newChatItems") CoreJson.parseChatItems(r.body).firstOrNull { it.chatId == chatId } else null
                if (item == null) return@post err(callback, unexpected(r))
                ok(callback, item.toMessage())
                applyNewItems(listOf(item))
            }
            is CoreReply.Error -> err(callback, ErrorMapper.fromCore(r.error, Op.SEND))
            CoreReply.Invalid -> err(callback, ErrorMapper.engine("invalid_reply"))
        }
    }

    override fun markRead(chatId: Long) = post {
        if (state != EngineState.RUNNING || ctrl == 0L) return@post
        val r = send(Commands.markRead(chatId))
        if (r is CoreReply.Result) {
            val s = chatCache[chatId] ?: return@post
            if (s.unreadCount != 0) {
                chatCache[chatId] = s.copy(unreadCount = 0)
                publishChats()
                notifyChatsChanged()
            }
        }
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
            if (ctrl == 0L) openDatabase()
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
        val displayName = name.trim()
        // The core counts characters (code points), not UTF-16 units: mkValidName keeps at most 50.
        val nameLength = displayName.codePointCount(0, displayName.length)
        if (nameLength == 0 || nameLength > MAX_NAME_CHARS) {
            return err(callback, SecuredError(SecuredError.Kind.UNKNOWN, "Enter a name of 1 to $MAX_NAME_CHARS characters."))
        }
        // Before the user exists: from here until our servers are verified, every start locks the network first.
        if (!markFirstRunLockdown()) {
            SecuredLog.e(Ev.CONFIG_STEP_FAILED, "lockdown_mark")
            return err(callback, SecuredError(SecuredError.Kind.ENGINE, "Secured storage couldn't be written. Try again."))
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
            val files = File(dir, "files")
            val temp = File(dir, "tmp").also { it.deleteRecursively() }
            val assets = File(dir, "assets")
            val remoteHosts = File(dir, "remote_hosts")
            expectResult(send(Commands.setFilePaths(files.absolutePath, temp.absolutePath, assets.absolutePath, remoteHosts.absolutePath)), "file_paths", "cmdOk")
            expectResult(send(Commands.ENCRYPT_LOCAL_FILES), "encrypt_files", "cmdOk")
        } catch (t: Throwable) {
            // Never keep a half-open store (no file paths, or no local file encryption): close it so the next
            // start redoes the whole open.
            try {
                chatCloseStore(c)
            } catch (e: Throwable) {
                SecuredLog.crash(Ev.DB_OPEN_FAILED, e)
            }
            ctrl = 0L
            if (t is StartFailure) throw t
            SecuredLog.crash(Ev.DB_OPEN_FAILED, t)
            throw StartFailure(SecuredError(SecuredError.Kind.ENGINE, "Secured storage couldn't be opened (setup)."), "db_setup")
        }
    }

    private fun migrateInit(prefix: String, key: String): Pair<String, Long> {
        val out = chatMigrateInit(prefix, key, MIGRATION_CONFIRM)
        val tag = CoreJson.migrationResultTag(out.getOrNull(0) as? String)
        val c = (out.getOrNull(1) as? Long) ?: 0L
        SecuredLog.i(Ev.DB_OPEN, tag)
        return tag to c
    }

    private fun activeUser(): CoreUser? = when (val r = send(Commands.SHOW_ACTIVE_USER)) {
        is CoreReply.Result ->
            if (r.type == "activeUser") {
                CoreJson.parseUser(r.body.optJSONObject("user")) ?: throw configFailure("active_user", r)
            } else {
                throw configFailure("active_user", r)
            }
        is CoreReply.Error ->
            if (r.error.detail == "noActiveUser") null
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
        } catch (t: Throwable) {
            val f = t as? StartFailure ?: run {
                SecuredLog.crash(Ev.START_FAILED, t)
                StartFailure(SecuredError(SecuredError.Kind.ENGINE, "Secured stopped for safety after an internal error. Try again."), "internal")
            }
            failSafe(relock = unlockSent)
            throw f
        }
        setState(EngineState.RUNNING)
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
        if (ctrl == 0L) {
            if (state != EngineState.NOT_STARTED) setState(EngineState.NOT_STARTED)
            return
        }
        stopReceiver()
        stopChatQuietly()
        try {
            chatCloseStore(ctrl)
        } catch (t: Throwable) {
            SecuredLog.crash(Ev.STOPPED, t)
        }
        ctrl = 0L
        userId = 0L
        chatCache.clear()
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
        if (state != EngineState.RUNNING) return
        when (reply) {
            is CoreReply.Error -> SecuredLog.w(Ev.EVENT_ERROR, reply.error.tag)
            is CoreReply.Result -> when (reply.type) {
                "newChatItems" -> {
                    val items = CoreJson.parseChatItems(reply.body)
                    applyNewItems(items)
                    val incoming = items.filter { it.isMessage && !it.outgoing }.map { it.toMessage() }
                    if (incoming.isNotEmpty()) main.post { for (m in incoming) for (l in listeners) l.onMessageReceived(m) }
                }
                "chatItemsStatusesUpdated" -> {
                    val items = CoreJson.parseChatItems(reply.body).filter { it.isMessage }
                    if (items.isNotEmpty()) {
                        main.post { for (i in items) for (l in listeners) l.onMessageStatusChanged(i.chatId, i.itemId, i.status) }
                    }
                }
                in CHAT_LIST_EVENTS -> {
                    SecuredLog.i(Ev.EVENT, reply.type)
                    refreshChats(notify = true)
                }
                "chatErrors" -> SecuredLog.w(Ev.EVENT_ERROR, "chatErrors", reply.body.optJSONArray("chatErrors")?.length())
                "messageError" -> SecuredLog.w(Ev.EVENT_ERROR, "messageError")
                else -> Unit
            }
            CoreReply.Invalid -> Unit
        }
    }

    // =============================================================================================================
    // Chat list cache
    // =============================================================================================================

    private fun refreshChats(notify: Boolean) {
        val r = send(Commands.getChats(userId))
        if (r !is CoreReply.Result || r.type != "apiChats") return
        chatCache.clear()
        for (chat in CoreJson.parseChats(r.body)) {
            if (chat.contact.isContactCard || chat.contact.removed) continue
            chatCache[chat.contact.contactId] = summaryOf(chat)
        }
        SecuredLog.i(Ev.CHATS_LOADED, count = chatCache.size)
        publishChats()
        if (notify) notifyChatsChanged()
    }

    /** Updates the cached rows for new items; reloads the list when an item belongs to a chat we don't know. */
    private fun applyNewItems(items: List<CoreItem>) {
        var changed = false
        var unknownChat = false
        for (item in items) {
            val row = chatCache[item.chatId]
            if (row == null) {
                unknownChat = true
                continue
            }
            if (!item.isMessage) continue
            chatCache[item.chatId] = row.copy(
                lastMessageText = item.text,
                lastMessageTimeMs = if (item.timeMs > 0) item.timeMs else row.lastMessageTimeMs,
                unreadCount = row.unreadCount + if (item.unread) 1 else 0,
            )
            changed = true
        }
        if (unknownChat) {
            refreshChats(notify = true)
        } else if (changed) {
            publishChats()
            notifyChatsChanged()
        }
    }

    private fun summaryOf(chat: CoreChat): ChatSummary {
        val c = chat.contact
        val lastMessage = chat.lastItem?.takeIf { it.isMessage }
        val time = lastMessage?.timeMs?.takeIf { it > 0 } ?: maxOf(c.activityTimeMs, chat.lastItem?.timeMs ?: 0L)
        return ChatSummary(
            chatId = c.contactId,
            displayName = c.displayName,
            lastMessageText = lastMessage?.text,
            lastMessageTimeMs = time,
            unreadCount = chat.unreadCount,
            verified = c.verified,
            ready = c.ready,
        )
    }

    private fun publishChats() {
        chatSnapshot = chatCache.values.sortedWith(
            compareByDescending<ChatSummary> { it.lastMessageTimeMs }.thenByDescending { it.chatId },
        )
    }

    // =============================================================================================================
    // Plumbing
    // =============================================================================================================

    /** Runs one engine command on the engine thread. Logs only reply/error type tags. */
    private fun send(cmd: String): CoreReply {
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
            is CoreReply.Error -> SecuredLog.w(Ev.CMD_ERROR, reply.error.tag)
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

    private fun CoreItem.toMessage() = SecuredMessage(chatId, itemId, outgoing, text, timeMs, status)

    /**
     * UTF-8 bytes of [text] once the core JSON-encodes it into the message body (aeson writes non-ASCII as raw
     * UTF-8 and escapes quotes, backslashes and control characters). Lone surrogates become U+FFFD (3 bytes).
     */
    private fun encodedTextBytes(text: String): Int {
        var n = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            n += when {
                cp == '"'.code || cp == '\\'.code || cp == '\n'.code || cp == '\r'.code || cp == '\t'.code -> 2
                cp < 0x20 -> 6
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            i += Character.charCount(cp)
        }
        return n
    }

    private companion object {
        const val DATA_DIR = "encrogram-secured"
        const val DB_PREFIX = "secured"
        const val LOCKDOWN_MARKER = "first_run_lockdown"
        const val MIGRATION_CONFIRM = "yesUp"
        const val RECV_WAIT_MICROS = 2_000_000
        const val MAX_PAGE = 500
        /** Commands.hs mkValidName keeps at most 50 characters (code points) of a display name. */
        const val MAX_NAME_CHARS = 50
        /**
         * Protocol.hs: a message body over maxEncodedMsgLength (15602 bytes) is refused, and on PQ connections a
         * body over maxCompressedMsgLength (13380) must compress below 13380. Staying under 13380 including the
         * ~200-byte x.msg.new envelope never depends on compression: 13380 - 380 reserved.
         */
        const val MAX_TEXT_BYTES = 13_000

        /** The unreachable SOCKS proxy for the first-run lock (port 9 is privileged: no app can listen on it). */
        const val LOCK_SOCKS_ADDR = "127.0.0.1:9"
        /**
         * SetNetworkConfig (netCfgP, Commands.hs): SOCKS for every host (socks-mode=always) to a closed port, same
         * proxy settings as [Commands.NETWORK_CONFIG]. Replaced by NETWORK_CONFIG once our servers are verified.
         */
        const val NETWORK_LOCKED = "/network socks=$LOCK_SOCKS_ADDR socks-mode=always smp-proxy=unknown smp-proxy-fallback=no"

        /** Events after which the chat list is reloaded from the engine. */
        val CHAT_LIST_EVENTS = setOf(
            "contactConnecting", "contactConnected", "contactSndReady", "contactUpdated", "contactDeletedByContact",
            "contactAnotherClient", "contactDisabled", "contactRatchetSync", "acceptingContactRequest",
            "chatInfoUpdated", "chatItemUpdated", "chatItemsDeleted",
        )
    }
}
