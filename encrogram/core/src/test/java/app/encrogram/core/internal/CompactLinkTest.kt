package app.encrogram.core.internal

import app.encrogram.core.api.LinkCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compact invite links: the core writes `?p=<port>&c=<keyHash>` after a short link on our relay (simplexmq
 * slEncode); we drop it when it names exactly our relay and put it back on the way in.
 */
class CompactLinkTest {

    private val host = "encrogram.nytmanager.com"
    private val fp = "7fc79ge_ADax0g_Lwu6YYwI1hFH6KwOG0y11w1YVORg"
    private val smp = listOf(Samples.SMP)                                   // ...@encrogram.nytmanager.com:5223
    private val allowed = setOf(host, "engrogramapi.nytmanager.com")

    private val linkId = "AbCdEfGhIjKlMnOpQrStUvWxYz012345"
    private val linkKey = "x7Yk_9-Qw3ErTyUiOpAsDfGhJkLzXcVbNm123456789"

    private val coreLink = "https://$host/i#$linkId/$linkKey?p=5223&c=$fp"
    private val compactLink = "https://$host/i#$linkId/$linkKey"

    private fun check(link: String, servers: List<String> = smp, hosts: Set<String> = allowed) =
        LinkChecker.check(LinkChecker.expand(link, servers), hosts, servers)

    // ---- round trip ----------------------------------------------------------------------------------------------

    @Test
    fun ourInviteIsCompactedAndExpandsBackExactly() {
        assertEquals(compactLink, LinkChecker.compact(coreLink, smp))
        assertEquals(coreLink, LinkChecker.expand(compactLink, smp))
        assertTrue(compactLink.length < coreLink.length - 50)
        assertEquals(LinkCheck.Approved, check(compactLink))
        assertEquals(LinkCheck.Approved, check(coreLink))
    }

    @Test
    fun theSampleInvitationFromTheCoreIsCompacted() {
        val short = CoreJson.parseInvitation((CoreJson.parseReply(Samples.INVITATION) as CoreReply.Result).body).shortLink!!
        val compact = LinkChecker.compact(short, smp)
        assertEquals(short.substringBefore('?'), compact)
        assertEquals(short, LinkChecker.expand(compact, smp))
    }

    @Test
    fun contactAddressRoundTripsToo() {
        val address = "https://$host/a#$linkKey?p=5223&c=$fp"
        assertEquals("https://$host/a#$linkKey", LinkChecker.compact(address, smp))
        assertEquals(address, LinkChecker.expand("https://$host/a#$linkKey", smp))
    }

    @Test
    fun relayWithoutAPortGetsNoPortParameter() {
        // As the core writes it: an empty port in the server address means no p= in the link.
        val noPort = listOf("smp://$fp=:pw@$host")
        assertEquals("$compactLink?c=$fp", LinkChecker.expand(compactLink, noPort))
        assertEquals(compactLink, LinkChecker.compact("$compactLink?c=$fp", noPort))
        // A link that carries a port while the relay has none is not ours as written: kept full.
        assertEquals("$compactLink?p=5223&c=$fp", LinkChecker.compact("$compactLink?p=5223&c=$fp", noPort))
    }

    @Test
    fun relayOnAnotherPortKeepsThatPort() {
        val on443 = listOf("smp://$fp:pw@$host:443")
        val link = "$compactLink?p=443&c=$fp"
        assertEquals(compactLink, LinkChecker.compact(link, on443))
        assertEquals(link, LinkChecker.expand(compactLink, on443))
        assertEquals(LinkCheck.Approved, check(compactLink, on443))
    }

    @Test
    fun paddedOrStandardBase64FingerprintsAreWrittenAsTheCoreDoes() {
        val padded = listOf("smp://$fp=:pw@$host:5223")
        assertEquals(coreLink, LinkChecker.expand(compactLink, padded))
        assertEquals(compactLink, LinkChecker.compact(coreLink, padded))
    }

    @Test
    fun expandTrimsAndMatchesTheHostCaseInsensitively() {
        assertEquals(coreLink, LinkChecker.expand("  $compactLink\n", smp))
        val upper = "https://Encrogram.NytManager.com/i#$linkId/$linkKey"
        assertEquals("$upper?p=5223&c=$fp", LinkChecker.expand(upper, smp))
        assertEquals(LinkCheck.Approved, check(upper))
    }

    // ---- refusals: compact keeps the link as it is ----------------------------------------------------------------

    @Test
    fun compactLeavesEverythingElseAlone() {
        val keep = listOf(
            "https://$host/i#$linkId/$linkKey?p=5224&c=$fp",                       // another port
            "https://$host/i#$linkId/$linkKey?p=5223&c=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", // another key
            "https://$host/i#$linkId/$linkKey?p=5223",                             // no key hash
            "https://$host/i#$linkId/$linkKey?h=engrogramapi.nytmanager.com&p=5223&c=$fp", // extra host
            "https://$host/i#$linkId/$linkKey?p=5223&c=$fp&x=1",                   // extra parameter
            "https://$host/i#$linkId/$linkKey?c=$fp&c=$fp",                        // repeated parameter
            "https://smp8.simplex.im/i#$linkId/$linkKey?p=5223&c=$fp",             // another host
            "simplex:/i#$linkId/$linkKey?h=$host&p=5223&c=$fp",                    // app scheme
            "https://$host/invitation#/?v=2-7&smp=x",                               // full link
            "https://$host/g#$linkKey?p=5223&c=$fp",                               // group link
            compactLink,                                                           // already compact
        )
        for (link in keep) assertEquals(link, link, LinkChecker.compact(link, smp))
    }

    @Test
    fun multiHostOrAmbiguousRelaysAreNeverCompactedOrExpanded() {
        val multiHost = listOf("smp://$fp:pw@$host,backup.nytmanager.com:5223")
        assertEquals(coreLink, LinkChecker.compact(coreLink, multiHost))
        assertEquals(compactLink, LinkChecker.expand(compactLink, multiHost))
        val twoOnOneHost = listOf("smp://$fp:pw@$host:5223", "smp://AAAA:pw@$host:443")
        assertEquals(coreLink, LinkChecker.compact(coreLink, twoOnOneHost))
        assertEquals(compactLink, LinkChecker.expand(compactLink, twoOnOneHost))
    }

    // ---- refusals: expand changes nothing else and never approves on its own -----------------------------------

    @Test
    fun expandLeavesEverythingElseAlone() {
        val keep = listOf(
            coreLink,                                                              // already full
            "https://$host/i#$linkId/$linkKey?p=5223",                             // carries p=
            "https://$host/i#$linkId/$linkKey?c=$fp",                              // carries c=
            "https://$host/i#$linkId/$linkKey?",                                   // empty query
            "https://smp8.simplex.im/i#$linkId/$linkKey",                          // other host
            "https://engrogramapi.nytmanager.com/i#$linkId/$linkKey",             // allowed host, but not an SMP relay
            "simplex:/i#$linkId/$linkKey?h=$host",                                 // app scheme
            "https://$host/invitation#/?v=2-7&smp=x",                               // full link
            "https://$host/c#$linkKey",                                            // channel link
            "https://$host:443/i#$linkId/$linkKey",                                // port in the authority
            "not a link",
        )
        for (link in keep) assertEquals(link, link, LinkChecker.expand(link, smp))
    }

    @Test
    fun checksStayAsStrictAsBefore() {
        // Other hosts: refused exactly as before.
        assertEquals(LinkCheck.UnapprovedRelay("smp8.simplex.im"), check("https://smp8.simplex.im/i#$linkId/$linkKey"))
        // A host missing from the allowlist stays refused even when it is in the server list.
        assertEquals(LinkCheck.UnapprovedRelay(host), check(compactLink, hosts = setOf("engrogramapi.nytmanager.com")))
        // Expanding does not repair a broken link.
        assertEquals(LinkCheck.Malformed, check("https://$host/i#short/key"))
        // A link that names another key for our host is still refused.
        assertEquals(
            LinkCheck.UnapprovedRelay(host),
            check("https://$host/i#$linkId/$linkKey?p=5223&c=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"),
        )
        // An allowed non-SMP host stays refused by the server check.
        assertEquals(
            LinkCheck.UnapprovedRelay("engrogramapi.nytmanager.com"),
            check("https://engrogramapi.nytmanager.com/i#$linkId/$linkKey"),
        )
    }
}
