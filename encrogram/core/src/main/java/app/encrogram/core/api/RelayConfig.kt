package app.encrogram.core.api

import java.util.Locale

/**
 * Our relays, passed in by the app module (the core never reads properties files itself).
 *
 * @param smpServers full SMP addresses, `smp://<fingerprint>[:<password>]@<host>[:<port>]`
 * @param xftpServers full XFTP addresses, `xftp://<fingerprint>[:<password>]@<host>[:<port>]`
 * @param allowedHosts hosts a pasted or tapped link may point at. Must include every SMP host, or our own
 *   invites would be refused. Compared case-insensitively and exactly (no subdomain matching).
 *
 * @param backupBaseUrl where encrypted 12-word backups are stored, `https://<our web host>/v1/b/` (ends with '/').
 *   Null or invalid: backup and restore fail with a readable error; messaging is unaffected.
 *
 * The addresses contain relay passwords, so [toString] prints counts only.
 * An empty or malformed config does not throw: the engine goes to FAILED with a readable error on start.
 */
class RelayConfig @JvmOverloads constructor(
    smpServers: List<String>,
    xftpServers: List<String>,
    allowedHosts: Collection<String>,
    backupBaseUrl: String? = null,
) {
    val smpServers: List<String> = smpServers.map { it.trim() }.filter { it.isNotEmpty() }
    val xftpServers: List<String> = xftpServers.map { it.trim() }.filter { it.isNotEmpty() }
    val allowedHosts: Set<String> = allowedHosts.map { normalizeHost(it) }.filter { it.isNotEmpty() }.toSet()
    val backupBaseUrl: String? = backupBaseUrl?.trim()?.ifEmpty { null }

    override fun equals(other: Any?): Boolean =
        other is RelayConfig &&
            smpServers == other.smpServers &&
            xftpServers == other.xftpServers &&
            allowedHosts == other.allowedHosts &&
            backupBaseUrl == other.backupBaseUrl

    override fun hashCode(): Int =
        ((smpServers.hashCode() * 31 + xftpServers.hashCode()) * 31 + allowedHosts.hashCode()) * 31 + backupBaseUrl.hashCode()

    override fun toString(): String =
        "RelayConfig(smp=${smpServers.size}, xftp=${xftpServers.size}, allowedHosts=${allowedHosts.size}, backup=${backupBaseUrl != null})"

    companion object {
        internal fun normalizeHost(host: String): String = host.trim().lowercase(Locale.ROOT).trimEnd('.')
    }
}
