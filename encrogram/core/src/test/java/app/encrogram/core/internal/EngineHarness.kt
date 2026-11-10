package app.encrogram.core.internal

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import app.encrogram.core.api.EngineState
import app.encrogram.core.api.MessageStatus
import app.encrogram.core.api.RelayConfig
import app.encrogram.core.api.SecuredCallback
import app.encrogram.core.api.SecuredError
import app.encrogram.core.api.SecuredListener
import app.encrogram.core.api.SecuredMessage
import app.encrogram.core.api.SecuredResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A11: [SimplexSecuredEngine] on the JVM. The SimpleX core is [FakeCore] (scripted replies, events, held calls), the
 * main thread a counting executor ([TestMain]), the engine and network threads counting executors ([CountingThread]),
 * so a test can wait until everything queued has run ([awaitIdle]). Only the Android pieces the engine touches on its
 * start path are stood in for ([TestContext]); the Keystore is replaced by the database-key seam.
 */
internal class EngineHarness(dir: File, wake: WakeGuard = WakeGuard.NONE) : AutoCloseable {
    val core = FakeCore()
    val main = TestMain()
    val engineThread = CountingThread("test-engine")
    val netThread = CountingThread("test-net")
    val listener = Recorder()
    val engine = SimplexSecuredEngine(TestContext(dir), CONFIG, core, main, engineThread, netThread, wake) { "test-db-key" }

    /** Waits until the engine, network and main threads are empty and every pushed event reached the engine. */
    fun awaitIdle(timeoutMs: Long = 10_000) {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        var quiet = 0
        while (quiet < 3) {
            check(System.nanoTime() < end) { "engine never went idle" }
            val idle = engineThread.pending.get() == 0 && netThread.pending.get() == 0 && main.thread.pending.get() == 0 &&
                core.eventsPending == 0
            quiet = if (idle) quiet + 1 else 0
            Thread.sleep(2)
        }
    }

    /** Polls [condition] (another thread makes it true). */
    fun waitFor(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition()) {
            check(System.nanoTime() < end) { "timed out waiting for $what" }
            Thread.sleep(2)
        }
    }

    fun startRunning() {
        engine.addListener(listener)
        engine.start()
        awaitIdle()
        assertEquals(EngineState.RUNNING, engine.state())
    }

    override fun close() {
        engine.stop()
        try {
            awaitIdle()
        } finally {
            for (e in listOf(engineThread, netThread, main.thread)) e.shutdownNow()
        }
    }

    companion object {
        val CONFIG = RelayConfig(
            listOf(Samples.SMP),
            listOf(Samples.XFTP),
            listOf("encrogram.nytmanager.com", "engrogramapi.nytmanager.com"),
        )
    }
}

/** A single named thread that counts what is queued or running on it. */
internal class CountingThread(name: String) : ThreadPoolExecutor(
    1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(),
    ThreadFactory { r -> Thread(r, name).apply { isDaemon = true } },
) {
    val pending = AtomicInteger()

    override fun execute(command: Runnable) {
        pending.incrementAndGet()
        try {
            super.execute(command)
        } catch (e: RejectedExecutionException) {
            pending.decrementAndGet()
            throw e
        }
    }

    override fun afterExecute(r: Runnable?, t: Throwable?) {
        pending.decrementAndGet()
    }
}

/** The main thread: posts run on "test-main"; timers wait until [fireTimers]. */
internal class TestMain : MainThread {
    val thread = CountingThread("test-main")
    private val timers = CopyOnWriteArrayList<Runnable>()

    override fun post(task: Runnable) = thread.execute(task)

    override fun postDelayed(task: Runnable, delayMs: Long) {
        timers.add(task)
    }

    /** Runs every timer posted so far (the refresh window, retention checks...). */
    fun fireTimers() {
        val due = ArrayList(timers)
        timers.removeAll(due.toSet())
        for (t in due) thread.execute(t)
    }
}

/** No-backup folder, package name and a content resolver: what the engine's start path reads from its Context. */
internal class TestContext(private val dir: File) : ContextWrapper(null) {
    override fun getNoBackupFilesDir(): File = dir
    override fun getPackageName(): String = "app.encrogram.test"
    override fun getApplicationContext(): Context = this
    override fun getContentResolver(): ContentResolver = object : ContentResolver(null) {}
}

/** Every listener call, in order, with the thread it ran on. */
internal class Recorder : SecuredListener {
    val log = CopyOnWriteArrayList<String>()
    val received = CopyOnWriteArrayList<SecuredMessage>()
    val errors = CopyOnWriteArrayList<SecuredError>()
    val states = CopyOnWriteArrayList<EngineState>()
    val threads = CopyOnWriteArrayList<String>()

    private fun note(s: String) {
        threads.add(Thread.currentThread().name)
        log.add(s)
    }

    /** What listeners heard after the engine reported NOT_STARTED the last time. */
    fun afterStop(): List<String> = log.subList(log.lastIndexOf("state:NOT_STARTED") + 1, log.size)

    override fun onStateChanged(state: EngineState) {
        states.add(state)
        note("state:$state")
    }

    override fun onChatsChanged() = note("chats")

    override fun onMessageReceived(message: SecuredMessage) {
        received.add(message)
        note("received:${message.messageId}")
    }

    override fun onMessageStatusChanged(chatId: Long, messageId: Long, status: MessageStatus) = note("status:$messageId")
    override fun onMessageChanged(message: SecuredMessage) = note("changed:${message.messageId}")
    override fun onMessagesRemoved(chatId: Long, messageIds: List<Long>) = note("removed:$messageIds")

    override fun onError(error: SecuredError) {
        errors.add(error)
        note("error:${error.kind}")
    }

    override fun onConnectionChanged(online: Boolean) = note("online:$online")
    override fun onBackupStateChanged() = note("backup")
    override fun onMediaProgress(chatId: Long, messageId: Long, transferredBytes: Long, totalBytes: Long) = note("progress")
}

/** A callback's results, with the thread each arrived on. */
internal class Answer<T> : SecuredCallback<T> {
    val results = CopyOnWriteArrayList<SecuredResult<T>>()
    val threads = CopyOnWriteArrayList<String>()

    override fun onResult(result: SecuredResult<T>) {
        threads.add(Thread.currentThread().name)
        results.add(result)
    }

    fun error(): SecuredError = (results.single() as SecuredResult.Err).error

    @Suppress("UNCHECKED_CAST")
    fun value(): T = (results.single() as SecuredResult.Ok<T>).value
}

/**
 * The SimpleX core as the engine sees it ([NativeApi]): a reply per command (the newest matching rule wins; the
 * defaults answer a healthy start), the events [push]ed, controllers that open and close, and calls a test can
 * [hold] inside the core. Records every call with its thread, and every command sent to a closed controller.
 */
internal class FakeCore : NativeApi {

    class Call(val ctrl: Long, val cmd: String, val retryNum: Int, val thread: String)

    private class Rule(val match: (String) -> Boolean, val reply: (Call) -> String)

    /** One call matching [match] waits inside the core until [release]. */
    class Hold(val match: (String) -> Boolean) {
        val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)
        fun release() = released.countDown()
        fun awaitEntered() = check(entered.await(5, TimeUnit.SECONDS)) { "held call never came" }
        internal fun block() = check(released.await(10, TimeUnit.SECONDS)) { "held call never released" }
    }

    private val rules = CopyOnWriteArrayList<Rule>()
    private val holds = CopyOnWriteArrayList<Hold>()
    val calls = CopyOnWriteArrayList<Call>()
    /** Commands sent to a controller after its close: must stay empty. */
    val closedCalls = CopyOnWriteArrayList<String>()
    val closed = CopyOnWriteArrayList<Long>()
    private val nextCtrl = AtomicLong(100)

    private val events = LinkedBlockingQueue<String>()
    private val unhanded = AtomicInteger()
    private val tookEvent = ThreadLocal.withInitial { false }

    /** The `/_network` config last set; `/network` reads it back. */
    @Volatile var networkConfig: String = Samples.NETWORK_CONFIG_DEFAULT
    @Volatile var serversSet = false
    /** `/_get chats 1`. */
    @Volatile var chats: String = chatsReply(contact(5, "Bob", unread = 0, last = rcv(40)))

    /** Events pushed that the receive thread hasn't handed to the engine yet. */
    val eventsPending: Int get() = unhanded.get()

    init {
        prefix("", ERROR_UNKNOWN)
        prefix("/set file paths ", OK)
        exact(Commands.ENCRYPT_LOCAL_FILES, OK)
        prefix("/_network info ", OK)
        exact(Commands.SHOW_ACTIVE_USER, ACTIVE_USER)
        exact(Commands.NETWORK_CONFIG, Samples.NETWORK_CONFIG_DEFAULT)
        prefix("/_network {") { c ->
            networkConfig = """{"result":{"type":"networkConfig","networkConfig":${c.cmd.removePrefix("/_network ")}}}"""
            OK
        }
        exact(Commands.GET_NETWORK_CONFIG) { networkConfig }
        exact(Commands.GET_OPERATORS, Samples.OPERATORS)
        prefix("/_operators [", Samples.OPERATORS.replace("\"enabled\":true", "\"enabled\":false"))
        exact(Commands.START_CHAT, result("chatStarted"))
        exact(Commands.STOP_CHAT, result("chatStopped"))
        exact("/_servers 1") { if (serversSet) Samples.userServersConfigured() else Samples.USER_SERVERS_FRESH }
        prefix("/_servers 1 [") {
            serversSet = true
            OK
        }
        exact("/_get chats 1") { chats }
        prefix("/_profile 1 ", result("userProfileNoChange"))
        prefix("/_info ", storeError("contactNotFound"))
        prefix("/_get chat ", EMPTY_CHAT)
        prefix("/_read chat items ", result("itemsReadForChat"))
        prefix("/_read chat ", OK)
        exact(Commands.RECONNECT_ALL, OK)
    }

    fun exact(cmd: String, reply: String) = exact(cmd) { reply }
    fun exact(cmd: String, reply: (Call) -> String) = rules.add(Rule({ it == cmd }, reply))
    fun prefix(p: String, reply: String) = prefix(p) { reply }
    fun prefix(p: String, reply: (Call) -> String) = rules.add(Rule({ it.startsWith(p) }, reply))

    /** The next call starting with [prefix] waits inside the core until the hold is released (a slow relay). */
    fun hold(prefix: String): Hold = Hold { it.startsWith(prefix) }.also { holds.add(it) }

    /**
     * Calls starting with [p] get [replies] in turn, then the last one for good (a relay that times out, then answers).
     * Each call runs [stored] first: what the core keeps whatever the reply (it saves a group profile before the relay).
     */
    fun replies(p: String, vararg replies: String, stored: (Call) -> Unit = {}) {
        val next = AtomicInteger()
        prefix(p) { c ->
            stored(c)
            replies[minOf(next.getAndIncrement(), replies.size - 1)]
        }
    }

    /**
     * `/_group_profile #<groupId>` as the core runs it: the profile sent is stored ([chats] and `/_get chat #<id>
     * count=1` read it back from then on, on top of [info]), then each call answers [replies] in turn, then the last
     * one for good: a relay timeout comes after the save (runUpdateGroupProfile, Commands.hs:4025, :4043).
     */
    fun groupProfiles(groupId: Long, info: String, vararg replies: String) {
        val p = "/_group_profile #$groupId "
        replies(p, *replies) { c ->
            val sent = JSONObject(c.cmd.removePrefix(p))
            val g = JSONObject(info).put("groupProfile", sent)
            // The merged preferences the row reads follow the profile's.
            sent.optJSONObject("groupPreferences")?.optJSONObject("timedMessages")
                ?.let { g.getJSONObject("fullGroupPreferences").put("timedMessages", it) }
            chats = chatsReply(contact(5, "Bob", last = rcv(40)), group(g.toString()))
            exact("/_get chat #$groupId count=1", groupChat(g.toString()))
        }
    }

    fun push(vararg raw: String) {
        for (e in raw) {
            unhanded.incrementAndGet()
            events.put(e)
        }
    }

    /** Commands sent (in order), optionally only those starting with [prefix]. */
    fun sent(prefix: String = ""): List<Call> = calls.filter { it.cmd.startsWith(prefix) }

    fun indexOf(prefix: String): Int = calls.indexOfFirst { it.cmd.startsWith(prefix) }

    override fun load() = Unit

    /** The migration result the next [migrateInit] reports; anything but "ok" opens no controller. */
    @Volatile var migrateTag = "ok"

    override fun migrateInit(dbPath: String, dbKey: String, confirm: String): Array<Any> =
        if (migrateTag == "ok") arrayOf("""{"type":"ok"}""", nextCtrl.incrementAndGet()) else arrayOf("""{"type":"$migrateTag"}""", 0L)

    override fun sendCmd(ctrl: Long, cmd: String, retryNum: Int): String {
        val call = Call(ctrl, cmd, retryNum, Thread.currentThread().name)
        if (ctrl in closed) closedCalls.add(cmd)
        calls.add(call)
        val hold = holds.firstOrNull { it.match(cmd) }
        if (hold != null && holds.remove(hold)) {
            hold.entered.countDown()
            hold.block()
        }
        return rules.last { it.match(cmd) }.reply(call)
    }

    override fun recvMsgWait(ctrl: Long, timeoutMicros: Int): String {
        if (tookEvent.get()) {
            tookEvent.set(false)
            unhanded.decrementAndGet()
        }
        if (ctrl in closed) {
            Thread.sleep(5)
            return ""
        }
        val e = events.poll(20, TimeUnit.MILLISECONDS) ?: return ""
        tookEvent.set(true)
        return e
    }

    override fun closeStore(ctrl: Long): String {
        closed.add(ctrl)
        calls.add(Call(ctrl, CLOSE, 0, Thread.currentThread().name))
        return ""
    }

    companion object {
        const val CLOSE = "<close>"
        const val USER = """{"userId":1,"agentUserId":"1","userContactId":1,"localDisplayName":"alice",
            "profile":{"profileId":1,"displayName":"Alice","fullName":"","localAlias":""},"fullPreferences":{},"activeUser":true}"""
        val ACTIVE_USER = """{"result":{"type":"activeUser","user":$USER}}"""
        val OK = result("cmdOk")
        val ERROR_UNKNOWN = """{"error":{"type":"error","errorType":{"type":"commandError","message":"x"}}}"""
        val EMPTY_CHAT = """{"result":{"type":"apiChat","user":$USER,"chat":{"chatInfo":{"type":"direct","contact":${Samples.contact(5, "Bob", "ready")}},
            "chatItems":[],"chatStats":{"unreadCount":0,"unreadMentions":0,"reportsCount":0,"minUnreadItemId":0,"unreadChat":false}}}}"""

        fun result(type: String, fields: String = "") = """{"result":{"type":"$type"${if (fields.isEmpty()) "" else ",$fields"}}}"""

        fun storeError(type: String) = """{"error":{"type":"errorStore","storeError":{"type":"$type"}}}"""

        fun chatsReply(vararg chats: String) = """{"result":{"type":"apiChats","user":$USER,"chats":[${chats.joinToString(",")}]}}"""

        private fun stats(unread: Int) =
            """{"unreadCount":$unread,"unreadMentions":0,"reportsCount":0,"minUnreadItemId":0,"unreadChat":false}"""

        fun contact(id: Long, name: String, unread: Int = 0, last: String? = null, mergedPrefs: String = "{}") =
            """{"chatInfo":{"type":"direct","contact":${Samples.contact(id, name, "ready", mergedPrefs = mergedPrefs)}},
               "chatItems":[${last ?: ""}],"chatStats":${stats(unread)}}"""

        /** A received text in a chat with a contact. */
        fun rcv(id: Long) = Samples.chatItem(id, "directRcv", "rcvMsgContent", "m$id", "rcvNew")

        /** `newChatItems` with one received message per id, from contact [contactId]. */
        fun newItems(contactId: Long, vararg ids: Long) = """{"result":{"type":"newChatItems","user":$USER,"chatItems":[${
            ids.joinToString(",") { """{"chatInfo":{"type":"direct","contact":${Samples.contact(contactId, "Bob", "ready")}},"chatItem":${rcv(it)}}""" }
        }]}}"""

        /**
         * A GroupInfo (Types.hs:477-503) we are [myRole] in, with the group's disappearing-message timer as its
         * profile and the merged preferences hold it ([timedTtl] null: off).
         */
        fun groupInfo(id: Long = 3, myRole: String = "owner", myStatus: String = "creator", timedTtl: Int? = null): String {
            val timed = if (timedTtl == null) """{"enable":"off"}""" else """{"enable":"on","ttl":$timedTtl}"""
            return """{"groupId":$id,"useRelays":false,"localDisplayName":"team",
             "groupProfile":{"displayName":"Team","fullName":"","description":"keep me","groupPreferences":{"history":{"enable":"on"},
               "fullDelete":{"enable":"on"},"timedMessages":$timed},"memberAdmission":{"review":"all"}},
             "localAlias":"",
             "fullGroupPreferences":{"history":{"enable":"on"},"fullDelete":{"enable":"on"},"reactions":{"enable":"on"},"timedMessages":$timed},
             "membership":{"groupMemberId":1,"groupId":$id,"memberId":"bWU=","memberRole":"$myRole","memberCategory":"user",
               "memberStatus":"$myStatus","memberSettings":{"showMessages":true},"blockedByAdmin":false,"invitedBy":{"type":"user"},
               "localDisplayName":"alice","memberProfile":{"profileId":1,"displayName":"Alice","fullName":"","localAlias":""},
               "memberContactId":null,"createdAt":"2026-11-08T09:00:00Z","updatedAt":"2026-11-08T09:00:00Z"},
             "chatSettings":{"enableNtfs":"all","favorite":false},
             "createdAt":"2026-11-08T09:00:00Z","updatedAt":"2026-11-08T09:10:00Z","chatTs":"2026-11-08T09:20:00Z",
             "groupSummary":{"currentMembers":3},"chatTags":[]}"""
        }

        fun group(info: String, unread: Int = 0) =
            """{"chatInfo":{"type":"group","groupInfo":$info},"chatItems":[],"chatStats":${stats(unread)}}"""

        /** `/_get chat #<id> count=1` for a group: its GroupInfo as stored now. */
        fun groupChat(info: String) = """{"result":{"type":"apiChat","user":$USER,"chat":{"chatInfo":{"type":"group","groupInfo":$info},
            "chatItems":[],"chatStats":${stats(0)}}}}"""

        // ---- links on our relay (LinkCheckerTest's shapes) ----
        private const val FP = "7fc79ge_ADax0g_Lwu6YYwI1hFH6KwOG0y11w1YVORg"
        const val SHORT_INVITE = "https://encrogram.nytmanager.com/i#AbCdEfGhIjKlMnOpQrStUvWxYz012345/x7Yk_9-Qw3ErTyUiOpAsDfGhJkLzXcVbNm123456789?p=5223&c=$FP"

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        val FULL_INVITE: String = "simplex:/invitation#/?v=2-7&smp=" +
            enc("smp://$FP@encrogram.nytmanager.com:5223/q1q2q3q4#/?v=1-4&dh=${enc("MCowBQYDK2VuAyEA+abc=")}") +
            "&e2e=" + enc("v=2-3&x3dh=MEIwBQYDK2VvAzkA,MEIwBQYDK2VvAzkA")

        /** `/_connect plan` for an invitation on our relay that can be used. */
        val PLAN_OK = """{"result":{"type":"connectionPlan","user":$USER,
            "connLink":{"connFullLink":"$FULL_INVITE","connShortLink":"$SHORT_INVITE"},
            "connectionPlan":{"type":"invitationLink","invitationLinkPlan":{"type":"ok","contactSLinkData_":null}}}}"""
    }
}
