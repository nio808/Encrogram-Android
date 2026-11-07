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

    enum class Op { START, PROFILE, INVITE, CONNECT, SEND, LOAD, READ, OTHER }

    fun notRunning(state: app.encrogram.core.api.EngineState): SecuredError =
        if (state == app.encrogram.core.api.EngineState.NEEDS_PROFILE) SecuredError(Kind.NO_PROFILE, NO_PROFILE)
        else SecuredError(Kind.ENGINE_NOT_RUNNING, NOT_RUNNING)

    fun unapprovedRelay(host: String): SecuredError =
        SecuredError(Kind.UNAPPROVED_RELAY, "This link points to a server outside Encrogram ($host). Ask your contact for a new Encrogram invite.")

    fun invalidLink(): SecuredError = SecuredError(Kind.INVALID_LINK, LINK_INVALID)

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
            }
            "errorStore" -> when (e.detail) {
                "duplicateName" -> return SecuredError(Kind.ENGINE, "This name can't be used. Try a different name.")
                "contactNotFound", "contactNotReady" ->
                    return SecuredError(Kind.ENGINE, "This chat is no longer available.")
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

    /** A `connectionPlan` reply means the engine refused to proceed with this link. */
    fun fromConnectionPlan(planTag: String): SecuredError = when (planTag) {
        "invitationLink.ownLink", "contactAddress.ownLink" ->
            SecuredError(Kind.INVALID_LINK, "This is your own invite link. Send it to your contact instead.")
        "invitationLink.connecting", "contactAddress.connectingProhibit", "contactAddress.connectingConfirmReconnect" ->
            SecuredError(Kind.INVALID_LINK, "You're already connecting with this contact.")
        "invitationLink.known", "contactAddress.known", "contactAddress.contactViaAddress" ->
            SecuredError(Kind.INVALID_LINK, "You're already connected with this contact.")
        else -> SecuredError(Kind.INVALID_LINK, LINK_INVALID)
    }
}
