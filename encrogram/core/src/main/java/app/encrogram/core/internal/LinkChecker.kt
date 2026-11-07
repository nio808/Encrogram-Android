package app.encrogram.core.internal

import app.encrogram.core.api.LinkCheck
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Offline check of a pasted or tapped SimpleX link against our relay allowlist. Never touches the network
 * or the native core, so it is safe on any thread and in JVM tests.
 *
 * Accepted shapes (formats from simplexmq Agent/Protocol.hs, `StrEncoding ConnShortLink` and `crEncode`):
 *  - short:  `https://<host>/i#<linkId>/<linkKey>[?h=<hosts>&p=<port>&c=<fp>]`, `https://<host>/a#<linkKey>[?...]`
 *  - short, app scheme: `simplex:/i#<linkId>/<linkKey>?h=<hosts>[&p=..&c=..]`, `simplex:/a#<linkKey>?h=...`
 *  - full:   `simplex:/invitation#/?v=..&smp=<queue URIs>&e2e=..`, `simplex:/contact#/?v=..&smp=..`
 *            and the same behind `https://<host>/invitation#/?...`
 *
 * Every host found (link host, `h=` hosts, and each SMP queue's server hosts including `srv=`) must be in the
 * allowlist; the first one that is not is reported as [LinkCheck.UnapprovedRelay]. Group, channel and relay
 * links (`/g`, `/c`, `/r`) are not supported in the MVP and are reported as [LinkCheck.Malformed].
 */
internal object LinkChecker {

    private const val MAX_LENGTH = 16 * 1024

    class Analysis(val result: LinkCheck, val normalized: String?)

    private enum class Kind { SHORT_INVITATION, SHORT_CONTACT, FULL_INVITATION, FULL_CONTACT }

    private val MALFORMED = Analysis(LinkCheck.Malformed, null)

    private const val DEFAULT_SMP_PORT = "5223"

    /** One server named by a link: its hosts plus the port and key fingerprint the link claims (null = absent). */
    private class Endpoint(val hosts: List<String>, val port: String?, val fp: String?)

    private class Allowed(val hosts: List<String>, val port: String, val fp: String)

    fun check(link: String?, allowedHosts: Set<String>): LinkCheck = analyze(link, allowedHosts).result

    /**
     * Like [check], and additionally every server in the link must be one of [smpServers] (full addresses such as
     * `smp://<fingerprint>@host:5223`): host, port (default 5223 when absent) and, when the link carries one,
     * the key fingerprint. Anything else is [LinkCheck.UnapprovedRelay].
     */
    fun check(link: String?, allowedHosts: Set<String>, smpServers: List<String>): LinkCheck =
        analyze(link, allowedHosts, smpServers).result

    /** [Analysis.normalized] is set only for approved links: scheme and host lower-cased, the rest untouched. */
    fun analyze(link: String?, allowedHosts: Set<String>, smpServers: List<String>? = null): Analysis {
        val s = link?.trim() ?: return MALFORMED
        if (s.isEmpty() || s.length > MAX_LENGTH) return MALFORMED
        // Printable ASCII only: no spaces (they would split the engine command), no IDN tricks.
        if (s.any { it.code < 0x21 || it.code > 0x7E }) return MALFORMED
        val lower = s.lowercase(Locale.ROOT)
        val parsed = when {
            lower.startsWith("https://") -> parseHttps(s.substring("https://".length))
            lower.startsWith("simplex:") -> parseAppScheme(s.substring("simplex:".length))
            else -> null
        } ?: return MALFORMED
        val (endpoints, normalized) = parsed
        val hosts = endpoints.flatMap { it.hosts }.distinct()
        if (hosts.isEmpty()) return MALFORMED
        val unapproved = hosts.firstOrNull { it !in allowedHosts }
        if (unapproved != null) return Analysis(LinkCheck.UnapprovedRelay(unapproved), null)
        if (smpServers != null) {
            val allowed = smpServers.mapNotNull { a ->
                val sa = ServerAddress.parse(a)?.takeIf { it.scheme == "smp" } ?: return@mapNotNull null
                Allowed(sa.hosts, sa.port.ifEmpty { DEFAULT_SMP_PORT }, fpKey(sa.keyHash))
            }
            for (e in endpoints) for (h in e.hosts) {
                val port = e.port?.trimStart('0')?.ifEmpty { "0" } ?: DEFAULT_SMP_PORT
                val ok = allowed.any { a ->
                    h in a.hosts && a.port == port && (e.fp == null || fpKey(e.fp) == a.fp)
                }
                if (!ok) return Analysis(LinkCheck.UnapprovedRelay(h), null)
            }
        }
        return Analysis(LinkCheck.Approved, normalized)
    }

    /** Fingerprints compare as unpadded base64url. */
    private fun fpKey(fp: String): String = fp.trimEnd('=').replace('+', '-').replace('/', '_')

    /** Returns (endpoints, normalized link) or null if malformed. [rest] follows "https://". */
    private fun parseHttps(rest: String): Pair<List<Endpoint>, String>? {
        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (authorityEnd <= 0) return null
        val authority = rest.substring(0, authorityEnd)
        // No user info and no port: the engine's short-link parser accepts a bare host only.
        if (authority.contains('@') || authority.contains(':')) return null
        val host = ServerAddress.normalizeHost(authority)
        if (!ServerAddress.isValidHost(host)) return null
        val tail = rest.substring(authorityEnd)
        val hosts = parsePathAndFragment(tail, linkHost = host) ?: return null
        return hosts to ("https://$host$tail")
    }

    /** [rest] follows "simplex:". */
    private fun parseAppScheme(rest: String): Pair<List<Endpoint>, String>? {
        val hosts = parsePathAndFragment(rest, linkHost = null) ?: return null
        return hosts to ("simplex:$rest")
    }

    /** [tail] is "/<path>#<fragment>". Returns every server the link would make the engine contact. */
    private fun parsePathAndFragment(tail: String, linkHost: String?): List<Endpoint>? {
        if (!tail.startsWith("/")) return null
        val hash = tail.indexOf('#')
        if (hash < 0) return null
        val path = tail.substring(1, hash)
        if (path.contains('?')) return null
        val kind = kindOf(path.removeSuffix("/")) ?: return null
        val fragment = tail.substring(hash + 1)
        val out = ArrayList<Endpoint>()
        when (kind) {
            Kind.SHORT_INVITATION, Kind.SHORT_CONTACT -> {
                val extra = parseShortFragment(kind, fragment) ?: return null
                // The app-scheme form has no authority: its server must come from h=.
                if (linkHost == null && extra.hosts.isEmpty()) return null
                val hosts = (listOfNotNull(linkHost) + extra.hosts).distinct()
                out.add(Endpoint(hosts, extra.port, extra.fp))
            }
            Kind.FULL_INVITATION, Kind.FULL_CONTACT -> {
                if (linkHost != null) out.add(Endpoint(listOf(linkHost), null, null))
                out.addAll(parseFullFragment(fragment) ?: return null)
            }
        }
        return out
    }

    private fun kindOf(path: String): Kind? = when (path) {
        "i" -> Kind.SHORT_INVITATION
        "a", "A" -> Kind.SHORT_CONTACT
        "invitation" -> Kind.FULL_INVITATION
        "contact" -> Kind.FULL_CONTACT
        else -> null // g, c, r (groups, channels, relays) are out of MVP scope
    }

    /** "<linkId>/<linkKey>[?query]" or "<linkKey>[?query]". Returns the h= hosts (possibly empty), p= and c=. */
    private fun parseShortFragment(kind: Kind, fragment: String): Endpoint? {
        val q = fragment.indexOf('?')
        val main = if (q >= 0) fragment.substring(0, q) else fragment
        val query = if (q >= 0) fragment.substring(q + 1) else ""
        val tokens = main.split('/')
        val expected = if (kind == Kind.SHORT_INVITATION) 2 else 1
        if (tokens.size != expected || tokens.any { !isLinkToken(it) }) return null
        val params = parseQuery(query) ?: return null
        val p = params["p"]
        if (p != null && (p.isEmpty() || p.length > 5 || !p.all { it in '0'..'9' })) return null
        val c = params["c"]
        if (c != null && (c.isEmpty() || !c.all { it.isLetterOrDigit() && it.code < 0x7F || it == '-' || it == '_' || it == '=' || it == '+' || it == '/' })) return null
        val h = params["h"] ?: return Endpoint(emptyList(), p, c)
        return Endpoint(ServerAddress.parseHostList(h) ?: return null, p, c)
    }

    /** "/?v=..&smp=<q1>;<q2>&e2e=..". Returns the hosts of every SMP queue. */
    private fun parseFullFragment(fragment: String): List<Endpoint>? {
        if (!fragment.startsWith("/?")) return null
        val params = parseQuery(fragment.substring(2)) ?: return null
        val smp = params["smp"] ?: return null
        if (params["v"].isNullOrEmpty()) return null
        val out = ArrayList<Endpoint>()
        for (queue in smp.split(';')) {
            out.add(queueEndpoint(queue) ?: return null)
        }
        return if (out.all { it.hosts.isEmpty() }) null else out
    }

    internal fun queueHosts(queue: String): List<String>? = queueEndpoint(queue)?.hosts


    /** `smp://<keyHash>@<hosts>[:<port>]/<queueId>[#/?v=..&dh=..&srv=<more hosts>]` -> all hosts. */
    private fun queueEndpoint(queue: String): Endpoint? {
        if (!queue.startsWith("smp://")) return null
        val afterScheme = queue.substring("smp://".length)
        val slash = afterScheme.indexOf('/')
        if (slash <= 0) return null
        val server = afterScheme.substring(0, slash)
        val at = server.lastIndexOf('@')
        if (at <= 0) return null
        val keyHash = server.substring(0, at)
        if (keyHash.any { it.code < 0x21 || it == '%' || it == ';' }) return null
        val (hostList, port) = ServerAddress.splitPort(server.substring(at + 1)) ?: return null
        val hosts = ServerAddress.parseHostList(hostList)?.toMutableList() ?: return null
        val hash = afterScheme.indexOf('#')
        if (hash >= 0) {
            val frag = afterScheme.substring(hash + 1).removePrefix("/")
            val query = frag.substringAfter('?', "")
            val params = parseQuery(query) ?: return null
            params["srv"]?.let { srv -> hosts.addAll(ServerAddress.parseHostList(srv) ?: return null) }
        }
        return Endpoint(hosts, port.ifEmpty { null }, keyHash)
    }

    private fun isLinkToken(t: String): Boolean {
        if (t.length < 16 || t.length > 128) return false
        val body = t.trimEnd('=')
        return body.isNotEmpty() && body.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }
    }

    /**
     * Parses "k=v&k2=v2" with percent-decoding of values ('+' is kept literally). The core's parser also splits on
     * ';', decodes names and takes the first duplicate, so every such ambiguity is refused (null): raw ';', names
     * that are empty or hold anything but letters, digits, '-', '_' or '.', repeated names, bad escapes and
     * control characters in decoded values.
     */
    internal fun parseQuery(query: String): Map<String, String>? {
        if (query.isEmpty()) return emptyMap()
        if (query.contains(';')) return null
        val out = HashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val key = if (eq >= 0) pair.substring(0, eq) else pair
            if (key.isEmpty() || !key.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' || it == '.' }) return null
            val value = if (eq >= 0) percentDecode(pair.substring(eq + 1)) ?: return null else ""
            if (value.any { it.code < 0x20 || it.code == 0x7F }) return null
            if (out.put(key, value) != null) return null
        }
        return out
    }

    internal fun percentDecode(s: String): String? {
        if (!s.contains('%')) return s
        val bytes = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 2 >= s.length) return null
                val hi = Character.digit(s[i + 1], 16)
                val lo = Character.digit(s[i + 2], 16)
                if (hi < 0 || lo < 0) return null
                bytes.write(hi * 16 + lo)
                i += 3
            } else {
                if (c.code > 0x7E) return null
                bytes.write(c.code)
                i++
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }
}
