package app.encrogram.core.internal

import app.encrogram.core.api.RelayConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure logic that restricts the engine to our relays. Applied on every start, because the preset operators
 * (SimpleX Chat, Flux) and their servers are compiled into the core and a new user is seeded with them.
 *
 * How the engine decides which servers to use (chat core 7.0.0.12, src/Simplex/Chat/Operators.hs
 * `agentServerCfgs`, Library/Internal.hs `useServerCfgs`):
 *  - a server whose host matches a *disabled* operator's domains is dropped;
 *  - a server with no operator ("custom") is used if it is enabled;
 *  - if nothing is left for a protocol, the engine falls back to random PRESET servers (fail-open!).
 * So we (1) disable every operator, (2) put exactly our SMP and XFTP servers in the custom group and delete or
 * disable everything else there, and (3) verify the stored result before declaring the engine RUNNING.
 */
internal object ServerConfigPlanner {

    private val PROTOCOLS = listOf("smp" to "smpServers", "xftp" to "xftpServers")

    /** A config problem, as a stable tag (safe for logs and error messages), or null when the config is usable. */
    fun configProblem(config: RelayConfig): String? {
        if (config.smpServers.isEmpty()) return "no_smp_server"
        if (config.xftpServers.isEmpty()) return "no_xftp_server"
        if (config.allowedHosts.isEmpty()) return "no_allowed_hosts"
        val smp = config.smpServers.map { ServerAddress.parse(it) ?: return "bad_smp_address" }
        val xftp = config.xftpServers.map { ServerAddress.parse(it) ?: return "bad_xftp_address" }
        if (smp.any { it.scheme != "smp" }) return "bad_smp_address"
        if (xftp.any { it.scheme != "xftp" }) return "bad_xftp_address"
        // Our invites are short links on our SMP host: that host must pass our own link check.
        if (smp.any { s -> s.hosts.any { it !in config.allowedHosts } }) return "smp_host_not_allowed"
        return null
    }

    // ---- operators ---------------------------------------------------------------------------------------------

    /** `serverOperatorConditions` body -> the operators array with every operator disabled, or null if none. */
    fun disabledOperators(body: JSONObject): JSONArray? {
        val ops = operatorsOf(body)
        if (ops.length() == 0) return null
        val out = JSONArray()
        ops.forEachObject { op -> out.put(op.deepCopy().put("enabled", false)) }
        return if (out.length() == 0) null else out
    }

    /** True when the `serverOperatorConditions` body lists no enabled operator. */
    fun allOperatorsDisabled(body: JSONObject): Boolean {
        var ok = body.optJSONObject("conditions") != null
        operatorsOf(body).forEachObject { op -> if (op.optBoolean("enabled", true)) ok = false }
        return ok
    }

    private fun operatorsOf(body: JSONObject): JSONArray =
        body.optJSONObject("conditions")?.optJSONArray("serverOperators") ?: JSONArray()

    // ---- network ------------------------------------------------------------------------------------------------

    /** `networkConfig` body: proxy for unknown relays, never a direct fallback, no SOCKS. */
    fun networkConfigOk(body: JSONObject): Boolean {
        val cfg = body.optJSONObject("networkConfig") ?: return false
        return cfg.str("smpProxyMode") == "unknown" &&
            cfg.str("smpProxyFallback") == "prohibit" &&
            (!cfg.has("socksProxy") || cfg.isNull("socksProxy"))
    }

    // ---- user servers -------------------------------------------------------------------------------------------

    /**
     * Builds the APISetUserServers payload from a `userServers` body: one custom group (operator null) holding
     * exactly our servers. Existing custom entries for our relays are updated in place (keeping their ids);
     * other custom entries are deleted (verification rejects any non-deleted leftover, even disabled). Deletions come first so a
     * re-added host never collides with its old row. Operator groups are not sent: their operators are disabled,
     * which already excludes their servers.
     */
    fun customServersUpdate(body: JSONObject, config: RelayConfig): JSONArray {
        val custom = customGroup(body)
        val group = JSONObject().put("operator", JSONObject.NULL)
        for ((scheme, key) in PROTOCOLS) {
            val desired = (if (scheme == "smp") config.smpServers else config.xftpServers).mapNotNull { ServerAddress.parse(it) }
            val deletions = JSONArray()
            val updates = JSONArray()
            val matched = HashSet<Int>()
            custom?.optJSONArray(key).forEachObject { existing ->
                val addr = ServerAddress.parse(existing.str("server"))
                val idx = if (addr == null) -1 else desired.indices.firstOrNull { it !in matched && desired[it].sameRelay(addr) } ?: -1
                val copy = existing.deepCopy()
                when {
                    idx >= 0 -> {
                        matched.add(idx)
                        copy.put("server", desired[idx].raw).put("enabled", true).put("deleted", false).put("roles", JSONObject())
                        updates.put(copy)
                    }
                    // Presets here are odd (they belong to operators) but must not linger even disabled:
                    // the core still counts disabled, non-deleted hosts as known and can fall back to them.
                    else -> deletions.put(copy.put("deleted", true))
                }
            }
            val list = JSONArray()
            for (i in 0 until deletions.length()) list.put(deletions.get(i))
            for (i in 0 until updates.length()) list.put(updates.get(i))
            for (i in desired.indices) {
                if (i in matched) continue
                list.put(
                    JSONObject()
                        .put("serverId", JSONObject.NULL)
                        .put("server", desired[i].raw)
                        .put("preset", false)
                        .put("enabled", true)
                        .put("roles", JSONObject())
                        .put("deleted", false),
                )
            }
            group.put(key, list)
        }
        // Chat relays are only used for channels, which Encrogram does not create; keep whatever is there.
        group.put("chatRelays", custom?.optJSONArray("chatRelays")?.let { JSONArray(it.toString()) } ?: JSONArray())
        return JSONArray().put(group)
    }

    /**
     * Checks a `userServers` body (read back after configuring) the way the engine will use it.
     * Returns null when the effective SMP and XFTP servers are exactly our relays, else a problem tag.
     */
    fun verifyUserServers(body: JSONObject, config: RelayConfig): String? {
        val groups = body.optJSONArray("userServers") ?: return "no_user_servers"
        val operatorDomains = ArrayList<String>()
        groups.forEachObject { g ->
            val op = g.optJSONObject("operator") ?: return@forEachObject
            if (op.optBoolean("enabled", true)) return "operator_enabled"
            op.optJSONArray("serverDomains")?.let { ds -> for (i in 0 until ds.length()) ds.optString(i).takeIf { it.isNotEmpty() }?.let(operatorDomains::add) }
        }
        for ((scheme, key) in PROTOCOLS) {
            val desired = (if (scheme == "smp") config.smpServers else config.xftpServers).mapNotNull { ServerAddress.parse(it) }
            // A disabled operator's domain covering our host would make the engine drop our server (then fall back).
            if (desired.any { d -> d.hosts.any { h -> operatorDomains.any { dom -> h.endsWith(dom.lowercase()) } } }) {
                return "${scheme}_host_matches_operator_domain"
            }
            val effective = ArrayList<ServerAddress>()
            var problem: String? = null
            var leftover = false
            groups.forEachObject { g ->
                val op = g.optJSONObject("operator")
                val opEnabled = op == null || op.optBoolean("enabled", true)
                g.optJSONArray(key).forEachObject { srv ->
                    // Any non-deleted custom entry counts for the core's known hosts, enabled or not.
                    if (op == null && !srv.optBoolean("deleted", false) && !(srv.optBoolean("enabled", false))) leftover = true
                    val used = opEnabled && srv.optBoolean("enabled", false) && !srv.optBoolean("deleted", false)
                    if (used) {
                        val addr = ServerAddress.parse(srv.str("server"))
                        if (addr == null) problem = "${scheme}_unparsable_server" else effective.add(addr)
                    }
                }
            }
            problem?.let { return it }
            if (effective.isEmpty()) return "${scheme}_no_effective_server"
            if (effective.any { e -> desired.none { it.sameRelayExact(e) } }) return "${scheme}_unexpected_server"
            if (desired.any { d -> effective.none { it.sameRelayExact(d) } }) return "${scheme}_missing_server"
            if (leftover) return "${scheme}_leftover_custom_server"
        }
        return null
    }

    private fun customGroup(body: JSONObject): JSONObject? {
        var found: JSONObject? = null
        body.optJSONArray("userServers").forEachObject { g ->
            if (found == null && (!g.has("operator") || g.isNull("operator"))) found = g
        }
        return found
    }
}
