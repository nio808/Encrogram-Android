package app.encrogram.core.internal

import java.util.Locale

/**
 * A parsed SimpleX server address: `<scheme>://<keyHash>[:<password>]@<host1>[,<host2>...][:<port>]`.
 * Pure Kotlin, no network, no native calls. [raw] contains the password: never log it.
 */
internal class ServerAddress private constructor(
    val scheme: String,
    val keyHash: String,
    val hosts: List<String>,
    val port: String,
    val raw: String,
) {
    /** Same relay for configuration purposes: same scheme, same first host, same port. */
    fun sameRelay(other: ServerAddress): Boolean =
        scheme == other.scheme && hosts.first() == other.hosts.first() && port == other.port

    /**
     * Strict identity: same scheme, same key fingerprint, same host list and same effective port.
     * Hosts are compared as an ORDERED list: the core's ProtocolServer holds a NonEmpty list of hosts and
     * derives Eq on it, so another order or an extra host is a different server to the core. An empty port
     * means the scheme default (smp 5223, xftp 443). The server password is ignored: it is only a
     * basic-auth token for creating queues, not part of the relay's identity, and callers must not
     * log it either way.
     */
    fun sameRelayExact(other: ServerAddress): Boolean =
        scheme == other.scheme && keyHash == other.keyHash && hosts == other.hosts &&
            effectivePort() == other.effectivePort()

    private fun effectivePort(): String =
        port.ifEmpty { if (scheme == "xftp") "443" else "5223" }

    override fun toString(): String = "ServerAddress($scheme, hosts=${hosts.size})"

    companion object {
        fun parse(address: String?): ServerAddress? {
            if (address == null) return null
            val s = address.trim()
            val sep = s.indexOf("://")
            if (sep <= 0) return null
            val scheme = s.substring(0, sep).lowercase(Locale.ROOT)
            if (scheme != "smp" && scheme != "xftp" && scheme != "ntf") return null
            val rest = s.substring(sep + 3)
            val at = rest.lastIndexOf('@')
            if (at <= 0) return null
            val userInfo = rest.substring(0, at)
            val hostPart = rest.substring(at + 1).trimEnd('/')
            if (hostPart.contains('/') || hostPart.contains('?') || hostPart.contains('#')) return null
            val keyHash = userInfo.substringBefore(':')
            if (keyHash.isEmpty() || keyHash.contains('/')) return null
            val (hostsPart, port) = splitPort(hostPart) ?: return null
            val hosts = parseHostList(hostsPart) ?: return null
            return ServerAddress(scheme, keyHash, hosts, port, s)
        }

        /** "a.b,c.d:5223" -> ("a.b,c.d", "5223"); "a.b" -> ("a.b", ""). Null if the port is not numeric. */
        internal fun splitPort(hostsAndPort: String): Pair<String, String>? {
            val colon = hostsAndPort.lastIndexOf(':')
            if (colon < 0) return hostsAndPort to ""
            val port = hostsAndPort.substring(colon + 1)
            if (port.isEmpty() || port.length > 5 || !port.all { it in '0'..'9' }) return null
            return hostsAndPort.substring(0, colon) to port
        }

        /** Comma-separated host list, each validated and lower-cased. Null if any host is invalid. */
        internal fun parseHostList(list: String): List<String>? {
            if (list.isEmpty()) return null
            val hosts = list.split(',').map { normalizeHost(it) }
            if (hosts.any { !isValidHost(it) }) return null
            return hosts
        }

        internal fun normalizeHost(host: String): String = host.trim().lowercase(Locale.ROOT).trimEnd('.')

        /** DNS names, IPv4 literals and .onion names. IPv6 literals are not accepted. */
        internal fun isValidHost(host: String): Boolean {
            if (host.isEmpty() || host.length > 253) return false
            val labels = host.split('.')
            for (label in labels) {
                if (label.isEmpty() || label.length > 63) return false
                if (label.first() == '-' || label.last() == '-') return false
                if (!label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }) return false
            }
            return true
        }
    }
}
