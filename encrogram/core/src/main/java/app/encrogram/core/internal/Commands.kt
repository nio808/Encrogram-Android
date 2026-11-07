package app.encrogram.core.internal

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

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

    /** CreateActiveUser -> `activeUser`. NewUser {profile, pastTimestamp}; userChatRelay/clientService default off. */
    fun createUser(displayName: String): String {
        val profile = JSONObject().put("displayName", displayName).put("fullName", "")
        val newUser = JSONObject().put("profile", profile).put("pastTimestamp", false)
        return "/_create user " + asciiJson(newUser.toString())
    }

    /** APISetServerOperators -> `serverOperatorConditions`. */
    fun setOperators(operators: JSONArray): String = "/_operators " + asciiJson(operators.toString())

    /** APIGetUserServers -> `userServers`. Requires the chat to be started. */
    fun getUserServers(userId: Long): String = "/_servers $userId"

    /** APISetUserServers -> `cmdOk`. Requires the chat to be started. */
    fun setUserServers(userId: Long, groups: JSONArray): String = "/_servers $userId " + asciiJson(groups.toString())

    /** APIGetChats -> `apiChats` (each chat with its last item). */
    fun getChats(userId: Long): String = "/_get chats $userId"

    /** APIGetChat with ChatPagination CPLast -> `apiChat`. */
    fun getChat(contactId: Long, count: Int): String = "/_get chat @$contactId count=$count"

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

    /** APISendMessages to a contact -> `newChatItems`. */
    fun sendText(contactId: Long, text: String): String {
        val msgContent = JSONObject().put("type", "text").put("text", text)
        val composed = JSONObject().put("msgContent", msgContent).put("mentions", JSONObject())
        return "/_send @$contactId json " + asciiJson(JSONArray().put(composed).toString())
    }

    /** APIChatRead -> `cmdOk`. */
    fun markRead(contactId: Long): String = "/_read chat @$contactId"

    /** APIDeleteChat for a contact, without notifying anyone. Used only for preset contact cards. */
    fun deleteContactQuietly(contactId: Long): String = "/_delete @$contactId full notify=off"

    /** APIDeleteChat for a pending connection (e.g. an invite we refuse to hand out). */
    fun deletePendingConnection(connId: Long): String = "/_delete :$connId full notify=off"

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
