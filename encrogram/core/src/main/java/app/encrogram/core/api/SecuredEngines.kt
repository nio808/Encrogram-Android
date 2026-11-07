package app.encrogram.core.api

import android.content.Context
import app.encrogram.core.internal.SimplexSecuredEngine

/**
 * Factory for the process-wide Secured engine.
 *
 * Creating the engine is cheap: it does not load the SimpleX native libraries or touch storage.
 * That happens on the engine thread when [SecuredEngine.start] is first called.
 */
object SecuredEngines {

    private var instance: SecuredEngine? = null
    private var instanceConfig: RelayConfig? = null

    /**
     * Returns the single engine, creating it on the first call.
     * A second call with an equal config returns the same engine; a different config throws
     * [IllegalStateException] (the relays cannot change while the process lives).
     */
    @JvmStatic
    @Synchronized
    fun create(context: Context, config: RelayConfig): SecuredEngine {
        instance?.let { existing ->
            check(instanceConfig == config) { "Secured engine already exists with a different relay config" }
            return existing
        }
        val engine = SimplexSecuredEngine(context.applicationContext ?: context, config)
        instance = engine
        instanceConfig = config
        return engine
    }

    /** The engine if [create] has been called in this process, else null. */
    @JvmStatic
    @Synchronized
    fun current(): SecuredEngine? = instance
}
