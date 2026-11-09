package app.encrogram.core.internal

import android.os.Handler
import android.os.Looper
import chat.simplex.common.platform.chatCloseStore
import chat.simplex.common.platform.chatMigrateInit
import chat.simplex.common.platform.chatRecvMsgWait
import chat.simplex.common.platform.chatSendCmdRetry

/**
 * The SimpleX core's controller calls the engine makes (A11). Production: [JniNativeApi], the JNI functions in
 * `chat/simplex/common/platform/Core.kt`. Tests run [SimplexSecuredEngine] on the JVM with a scripted fake.
 * The media file calls have their own seam ([app.encrogram.core.internal.media.CoreFileCalls]).
 */
internal interface NativeApi {
    /** Loads the libraries and starts the runtime, once per process; throws [NativeCore.LoadFailure]. */
    fun load()

    /** chatMigrateInit: `[migrationResultJson: String, ctrl: Long]`. */
    fun migrateInit(dbPath: String, dbKey: String, confirm: String): Array<Any>

    /** chatSendCmdRetry. Thread-safe in the core: the official app calls it from any IO thread. */
    fun sendCmd(ctrl: Long, cmd: String, retryNum: Int): String

    /** chatRecvMsgWait: blocks up to [timeoutMicros]; "" on timeout. */
    fun recvMsgWait(ctrl: Long, timeoutMicros: Int): String

    /** chatCloseStore. */
    fun closeStore(ctrl: Long): String
}

/** The real core (JNI). */
internal object JniNativeApi : NativeApi {
    override fun load() = NativeCore.ensureLoaded()
    override fun migrateInit(dbPath: String, dbKey: String, confirm: String): Array<Any> = chatMigrateInit(dbPath, dbKey, confirm)
    override fun sendCmd(ctrl: Long, cmd: String, retryNum: Int): String = chatSendCmdRetry(ctrl, cmd, retryNum)
    override fun recvMsgWait(ctrl: Long, timeoutMicros: Int): String = chatRecvMsgWait(ctrl, timeoutMicros)
    override fun closeStore(ctrl: Long): String = chatCloseStore(ctrl)
}

/** Where callbacks, listener calls and timers run (A11). Production: [HandlerMainThread], Android's main thread. */
internal interface MainThread {
    fun post(task: Runnable)
    fun postDelayed(task: Runnable, delayMs: Long)
}

internal class HandlerMainThread(private val handler: Handler = Handler(Looper.getMainLooper())) : MainThread {
    override fun post(task: Runnable) {
        handler.post(task)
    }

    override fun postDelayed(task: Runnable, delayMs: Long) {
        handler.postDelayed(task, delayMs)
    }
}
