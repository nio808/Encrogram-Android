package app.encrogram.core.internal

import android.os.Build
import chat.simplex.common.platform.initHS

/**
 * Loads the SimpleX native libraries once per process, the way the upstream Android app does
 * (AppCommon.android.kt `initHaskell`): `System.loadLibrary("app-lib")` (the dynamic linker pulls in its
 * dependencies libsimplex.so and libsupport.so from the APK), then `initHS()` to start the GHC runtime.
 * Unlike upstream we never call `pipeStdOutToSocket`, so the engine's stdout/stderr is not captured anywhere.
 *
 * The GHC runtime cannot be restarted: once initialised it lives until the process dies.
 */
internal object NativeCore {

    class LoadFailure(val reason: String) : Exception(reason)

    private const val REQUIRED_ABI = "arm64-v8a"

    @Volatile
    private var loaded = false
    private var failure: LoadFailure? = null

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        failure?.let { throw it }
        if (!Build.SUPPORTED_ABIS.contains(REQUIRED_ABI)) {
            throw LoadFailure("unsupported_abi").also { failure = it }
        }
        try {
            System.loadLibrary("app-lib")
        } catch (t: Throwable) {
            SecuredLog.crash(SecuredLog.Ev.NATIVE_LOAD_FAILED, t)
            throw LoadFailure("load_failed").also { failure = it }
        }
        try {
            initHS()
        } catch (t: Throwable) {
            // Remembered like a load failure: the GHC runtime must never be initialised twice.
            SecuredLog.crash(SecuredLog.Ev.NATIVE_LOAD_FAILED, t)
            throw LoadFailure("init_failed").also { failure = it }
        }
        loaded = true
        SecuredLog.i(SecuredLog.Ev.NATIVE_LOADED)
    }
}
