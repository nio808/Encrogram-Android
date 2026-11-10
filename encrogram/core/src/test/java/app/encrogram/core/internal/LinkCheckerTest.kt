package app.encrogram.core.internal

import app.encrogram.core.api.LinkCheck
import app.encrogram.core.api.SecuredError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

class LinkCheckerTest {

    private val allowed = setOf("encrogram.nytmanager.com", "engrogramapi.nytmanager.com")

    private val linkId = "AbCdEfGhIjKlMnOpQrStUvWxYz012345"            // 24 bytes, base64url
    private val linkKey = "x7Yk_9-Qw3ErTyUiOpAsDfGhJkLzXcVbNm123456789"  // 32 bytes, base64url
    private val fp = "7fc79ge_ADax0g_Lwu6YYwI1hFH6KwOG0y11w1YVORg"

    private fun check(link: String) = LinkChecker.check(link, allowed)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** A full one-time invitation as the engine encodes it (crEncode), with one or more SMP queues. */
    private fun fullInvitation(scheme: String, vararg queueServers: String): String {
        val queues = queueServers.joinToString(";") { srv -> "smp://$fp@$srv/q1q2q3q4#/?v=1-4&dh=${enc("MCowBQYDK2VuAyEA+abc=")}" }
        return "$scheme/invitation#/?v=2-7&smp=${enc(queues)}&e2e=${enc("v=2-3&x3dh=MEIwBQYDK2VvAzkA,MEIwBQYDK2VvAzkA")}"
    }

    // ---- short links on https --------------------------------------------------------------------------------

    @Test
    fun ourShortInviteIsApproved() {
        assertEquals(LinkCheck.Approved, check("https://encrogram.nytmanager.com/i#$linkId/$linkKey?p=5223&c=$fp"))
        assertEquals(LinkCheck.Approved, check("https://encrogram.nytmanager.com/i#$linkId/$linkKey"))
        assertEquals(LinkCheck.Approved, check("  https://encrogram.nytmanager.com/a#$linkKey?p=5223&c=$fp\n"))
    }

    @Test
    fun otherHostIsUnapproved() {
        assertEquals(LinkCheck.UnapprovedRelay("smp8.simplex.im"), check("https://smp8.simplex.im/i#$linkId/$linkKey"))
        assertEquals(LinkCheck.UnapprovedRelay("simplex.chat"), check("https://simplex.chat/a#$linkKey"))
    }

    @Test
    fun lookalikeHostsAreUnapproved() {
        assertEquals(
            LinkCheck.UnapprovedRelay("encrogram.nytmanager.com.evil.example"),
            check("https://encrogram.nytmanager.com.evil.example/i#$linkId/$linkKey"),
        )
        assertEquals(
            LinkCheck.UnapprovedRelay("evil-encrogram.nytmanager.com"),
            check("https://evil-encrogram.nytmanager.com/i#$linkId/$linkKey"),
        )
    }

    @Test
    fun extraHostsInQueryMustAlsoBeAllowed() {
        assertEquals(
            LinkCheck.UnapprovedRelay("relay.evil.example"),
            check("https://encrogram.nytmanager.com/i#$linkId/$linkKey?h=relay.evil.example&p=5223&c=$fp"),
        )
        assertEquals(
            LinkCheck.Approved,
            check("https://encrogram.nytmanager.com/i#$linkId/$linkKey?h=engrogramapi.nytmanager.com"),
        )
    }

    @Test
    fun hostIsCaseInsensitiveAndNormalized() {
        val analysis = LinkChecker.analyze("HTTPS://Encrogram.NytManager.com/i#$linkId/$linkKey?p=5223", allowed)
        assertEquals(LinkCheck.Approved, analysis.result)
        // Scheme and host lower-cased; the secret part is untouched.
        assertEquals("https://encrogram.nytmanager.com/i#$linkId/$linkKey?p=5223", analysis.normalized)
    }

    // ---- simplex: links ------------------------------------------------------------------------------------

    @Test
    fun appSchemeShortLinkUsesTheHostParameter() {
        assertEquals(LinkCheck.Approved, check("simplex:/i#$linkId/$linkKey?h=encrogram.nytmanager.com&p=5223&c=$fp"))
        assertEquals(LinkCheck.UnapprovedRelay("smp4.simplex.im"), check("simplex:/a#$linkKey?h=smp4.simplex.im"))
        // Without h= there is no server at all.
        assertEquals(LinkCheck.Malformed, check("simplex:/i#$linkId/$linkKey"))
    }

    @Test
    fun fullInvitationOnOurRelayIsApproved() {
        assertEquals(LinkCheck.Approved, check(fullInvitation("simplex:", "encrogram.nytmanager.com:5223")))
    }

    @Test
    fun fullInvitationOnAnotherRelayIsUnapproved() {
        assertEquals(LinkCheck.UnapprovedRelay("smp11.simplex.im"), check(fullInvitation("simplex:", "smp11.simplex.im")))
        // Any queue on a foreign relay is enough to refuse.
        assertEquals(
            LinkCheck.UnapprovedRelay("smp11.simplex.im"),
            check(fullInvitation("simplex:", "encrogram.nytmanager.com:5223", "smp11.simplex.im")),
        )
        // A second hostname of the queue's server counts too.
        assertEquals(
            LinkCheck.UnapprovedRelay("abcdef.onion"),
            check(fullInvitation("simplex:", "encrogram.nytmanager.com,abcdef.onion:5223")),
        )
    }

    @Test
    fun legacySrvParameterHostsAreChecked() {
        val queue = "smp://$fp@encrogram.nytmanager.com/q1q2q3q4#/?v=1-2&dh=abc&srv=${enc("hidden.example.net")}"
        val link = "simplex:/contact#/?v=1-2&smp=${enc(queue)}"
        assertEquals(LinkCheck.UnapprovedRelay("hidden.example.net"), check(link))
    }

    @Test
    fun fullLinkBehindHttpsNeedsAnAllowedHostToo() {
        assertEquals(
            LinkCheck.UnapprovedRelay("simplex.chat"),
            check(fullInvitation("https://simplex.chat", "encrogram.nytmanager.com:5223")),
        )
        assertEquals(
            LinkCheck.Approved,
            check(fullInvitation("https://encrogram.nytmanager.com", "encrogram.nytmanager.com:5223")),
        )
    }

    // ---- garbage -------------------------------------------------------------------------------------------

    @Test
    fun malformed() {
        val bad = listOf(
            "",
            "   ",
            "hello",
            "encrogram.nytmanager.com/i#$linkId/$linkKey",
            "http://encrogram.nytmanager.com/i#$linkId/$linkKey",
            "ftp://encrogram.nytmanager.com/i#$linkId/$linkKey",
            "https://encrogram.nytmanager.com/",
            "https://encrogram.nytmanager.com/i",
            "https://encrogram.nytmanager.com/i#",
            "https://encrogram.nytmanager.com/i#short/key",
            "https://encrogram.nytmanager.com/i#$linkKey",                 // invitation needs id and key
            "https://encrogram.nytmanager.com/i#$linkId/$linkKey extra",   // spaces would split the command
            "https://encrogram.nytmanager.com:443/i#$linkId/$linkKey",     // engine accepts a bare host only
            "https://user@encrogram.nytmanager.com/i#$linkId/$linkKey",
            "https://encrogram.nytmanager.com/c#$linkKey",                 // relay channels: never
            "https://encrogram.nytmanager.com/r#$linkKey",                 // chat relays: never
            "https://encrogram.nytmanager.com/g#$linkId/$linkKey",         // a group link has one token
            "https://encrogram.nytmanager.com/i#$linkId/$linkKey?h=bad_host!",
            "https://encrogram.nytmanager.com/i#$linkId/$linkKey?p=port",
            "https://xn--80ak6aa92e.com/i#$linkId/$linkKey".replace("xn--80ak6aa92e", "аррӏе"),
            "simplex:/invitation#/?v=2-7",                                 // no queues
            "simplex:/invitation#/?v=2-7&smp=" + enc("smp://nohost/q"),
            "simplex:/invitation#/?v=2-7&smp=%ZZ",
            "simplex:invitation",
            "javascript:alert(1)",
        )
        for (link in bad) assertEquals("for <$link>", LinkCheck.Malformed, check(link))
    }

    @Test
    fun onlyApprovedLinksGetANormalizedForm() {
        assertNull(LinkChecker.analyze("https://smp8.simplex.im/i#$linkId/$linkKey", allowed).normalized)
        assertNull(LinkChecker.analyze("hello", allowed).normalized)
    }

    @Test
    fun queueHostParsing() {
        assertEquals(listOf("a.example", "b.example"), LinkChecker.queueHosts("smp://kh@a.example,b.example:5223/q#/?v=1-4&dh=x"))
        assertNull(LinkChecker.queueHosts("smp://a.example/q"))
        assertNull(LinkChecker.queueHosts("xftp://kh@a.example/q"))
    }

    // ---- ambiguous queries (the core's parser also splits on ';', decodes names, takes the first duplicate) ----

    private val evil = "evil.example"

    @Test
    fun semicolonSmugglingIsMalformed() {
        val link = "simplex:/a#$linkKey?x=1;h=$evil&c=$fp&h=encrogram.nytmanager.com"
        assertEquals(LinkCheck.Malformed, check(link))
        assertEquals(LinkCheck.Malformed, check("https://encrogram.nytmanager.com/a#$linkKey?h=$evil;p=5223"))
    }

    @Test
    fun encodedParameterNameIsMalformed() {
        assertEquals(LinkCheck.Malformed, check("simplex:/a#$linkKey?%68=$evil&h=encrogram.nytmanager.com"))
        assertEquals(LinkCheck.Malformed, check("simplex:/a#$linkKey?h%3D=x&h=encrogram.nytmanager.com"))
    }

    @Test
    fun duplicateAndEmptyNamesAreMalformed() {
        assertEquals(LinkCheck.Malformed, check("simplex:/a#$linkKey?h=$evil&h=encrogram.nytmanager.com"))
        assertEquals(LinkCheck.Malformed, check("simplex:/a#$linkKey?h=encrogram.nytmanager.com&=x"))
        assertEquals(LinkCheck.Malformed, check("simplex:/a#$linkKey?h=encrogram.nytmanager.com&p=5223&p=5223"))
        assertEquals(LinkCheck.Malformed, check("simplex:/a#$linkKey?h=encrogram.nytmanager.com&q%0A=1"))
    }

    @Test
    fun ambiguousNestedQueriesAreMalformed() {
        val q = "smp://$fp@encrogram.nytmanager.com:5223/q1q2q3q4"
        // ';' inside a queue's own query (legacy srv= smuggling)
        assertEquals(LinkCheck.Malformed, check("simplex:/contact#/?v=1-2&smp=${enc("$q#/?v=1-2&x=1;srv=$evil")}"))
        // duplicate srv=, encoded name, in the nested query
        assertEquals(LinkCheck.Malformed, check("simplex:/contact#/?v=1-2&smp=${enc("$q#/?v=1-2&srv=a.example&srv=b.example")}"))
        assertEquals(LinkCheck.Malformed, check("simplex:/contact#/?v=1-2&smp=${enc("$q#/?v=1-2&%73rv=$evil")}"))
        // raw ';' at the top level of a full link
        assertEquals(LinkCheck.Malformed, check("simplex:/contact#/?v=1-2&smp=$q#/?v=1-2;h=$evil"))
        // duplicate top-level smp=
        assertEquals(LinkCheck.Malformed, check("simplex:/contact#/?v=1-2&smp=${enc(q)}&smp=${enc(q)}"))
    }

    // ---- port and fingerprint (new overload) --------------------------------------------------------------------

    private val smp = listOf("smp://$fp=@encrogram.nytmanager.com:5223")

    private fun checkSmp(link: String) = LinkChecker.check(link, allowed, smp)

    @Test
    fun rightPortAndFingerprintPass() {
        assertEquals(LinkCheck.Approved, checkSmp("https://encrogram.nytmanager.com/i#$linkId/$linkKey?p=5223&c=$fp"))
        assertEquals(LinkCheck.Approved, checkSmp("https://encrogram.nytmanager.com/i#$linkId/$linkKey"))
        assertEquals(LinkCheck.Approved, checkSmp("simplex:/i#$linkId/$linkKey?h=encrogram.nytmanager.com&p=5223&c=$fp"))
        assertEquals(LinkCheck.Approved, checkSmp(fullInvitation("simplex:", "encrogram.nytmanager.com:5223")))
        assertEquals(LinkCheck.Approved, checkSmp(fullInvitation("simplex:", "encrogram.nytmanager.com")))
    }

    @Test
    fun wrongPortIsUnapproved() {
        val host = "encrogram.nytmanager.com"
        assertEquals(LinkCheck.UnapprovedRelay(host), checkSmp("https://$host/i#$linkId/$linkKey?p=443&c=$fp"))
        assertEquals(LinkCheck.UnapprovedRelay(host), checkSmp(fullInvitation("simplex:", "$host:5224")))
        assertEquals(LinkCheck.UnapprovedRelay(host), checkSmp(fullInvitation("simplex:", "$host:443")))
    }

    @Test
    fun wrongFingerprintIsUnapproved() {
        val host = "encrogram.nytmanager.com"
        val other = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        assertEquals(LinkCheck.UnapprovedRelay(host), checkSmp("https://$host/i#$linkId/$linkKey?p=5223&c=$other"))
        val queue = "smp://$other@$host:5223/q1q2q3q4#/?v=1-4&dh=abc"
        assertEquals(LinkCheck.UnapprovedRelay(host), checkSmp("simplex:/contact#/?v=1-2&smp=${enc(queue)}"))
    }

    @Test
    fun hostNotInSmpListIsUnapprovedByNewOverload() {
        // Allowed as a host (XFTP) but not an SMP server.
        assertEquals(
            LinkCheck.UnapprovedRelay("engrogramapi.nytmanager.com"),
            checkSmp("https://encrogram.nytmanager.com/i#$linkId/$linkKey?h=engrogramapi.nytmanager.com"),
        )
        assertEquals(LinkCheck.UnapprovedRelay("encrogram.nytmanager.com"), LinkChecker.check("https://encrogram.nytmanager.com/a#$linkKey", allowed, emptyList()))
    }

    // ---- cut-off keys (H4) ----------------------------------------------------------------------------------------

    /** Every short link these tests approve, as the core writes it or as we compact it. Keys are 43 characters. */
    private val wholeShortLinks: List<String>
        get() = listOf(
            "https://encrogram.nytmanager.com/i#$linkId/$linkKey?p=5223&c=$fp",
            "https://encrogram.nytmanager.com/i#$linkId/$linkKey",
            "https://encrogram.nytmanager.com/i#$linkId/$linkKey=",            // padded: the core's base64url parser takes it
            "https://encrogram.nytmanager.com/a#$linkKey?p=5223&c=$fp",
            "https://encrogram.nytmanager.com/a#$linkKey",
            "https://encrogram.nytmanager.com/g#$linkKey",
            "https://Encrogram.NytManager.com/g/#$linkKey",
            "simplex:/i#$linkId/$linkKey?h=encrogram.nytmanager.com&p=5223&c=$fp",
            "simplex:/a#$linkKey?h=encrogram.nytmanager.com",
            "simplex:/g#$linkKey?h=encrogram.nytmanager.com&p=5223&c=$fp",
            "  https://encrogram.nytmanager.com/i#$linkId/$linkKey\n",
        )

    @Test
    fun wholeLinksNeverCountAsDamaged() {
        assertEquals(43, linkKey.length)
        for (link in wholeShortLinks) {
            assertEquals("for <$link>", LinkCheck.Approved, check(link))
            assertFalse("for <$link>", LinkChecker.hasDamagedKey(link))
        }
        // Full links have no link key: never this check's business (a cut one fails to parse in the core: invalidConnReq).
        for (link in listOf(fullInvitation("simplex:", "encrogram.nytmanager.com:5223"), fullInvitation("https://encrogram.nytmanager.com", "encrogram.nytmanager.com"))) {
            assertFalse(LinkChecker.hasDamagedKey(link))
            assertFalse(LinkChecker.hasDamagedKey(link.dropLast(20)))
        }
        // The compact invite a phone shows, expanded for our relay first, as connect does.
        val compact = "https://encrogram.nytmanager.com/i#$linkId/$linkKey"
        assertFalse(LinkChecker.hasDamagedKey(LinkChecker.expand(compact, listOf("smp://$fp@encrogram.nytmanager.com:5223"))))
        assertFalse(LinkChecker.hasDamagedKey(null))
        assertFalse(LinkChecker.hasDamagedKey("hello"))
    }

    @Test
    fun cutOffKeysAreDamagedThoughTheRelayIsOurs() {
        val cut = listOf(
            "https://encrogram.nytmanager.com/i#$linkId/${linkKey.dropLast(1)}",
            "https://encrogram.nytmanager.com/i#$linkId/${linkKey.take(20)}",
            "https://encrogram.nytmanager.com/i#$linkId/${linkKey.take(16)}",
            "https://encrogram.nytmanager.com/i#$linkId/${linkKey.dropLast(1)}=",
            "https://encrogram.nytmanager.com/a#${linkKey.dropLast(5)}",
            "https://encrogram.nytmanager.com/g#${linkKey.dropLast(1)}",
            "simplex:/i#$linkId/${linkKey.dropLast(3)}?h=encrogram.nytmanager.com",
            // A key that grew (two links pasted together) is not a whole key either.
            "https://encrogram.nytmanager.com/i#$linkId/${linkKey}AB",
        )
        for (link in cut) {
            // Still on our relay, so the connect sheet goes on to connect, which refuses it before asking the core.
            assertEquals("for <$link>", LinkCheck.Approved, check(link))
            assertTrue("for <$link>", LinkChecker.hasDamagedKey(link))
        }
        // Shorter than any link token: not a link at all.
        assertEquals(LinkCheck.Malformed, check("https://encrogram.nytmanager.com/i#$linkId/${linkKey.take(15)}"))
    }

    @Test
    fun linkDataTheKeyCantOpenIsADamagedLinkOnlyWhenConnecting() {
        for (sub in listOf("A_CRYPTO", "A_LINK")) {
            val e = ErrorMapper.fromCore(CoreError("errorAgent", "AGENT", sub), ErrorMapper.Op.CONNECT)
            assertEquals(SecuredError.Kind.INVALID_LINK, e.kind)
            assertEquals(ErrorMapper.LINK_DAMAGED, e.message)
            assertEquals(ErrorMapper.engine("errorAgent.AGENT.$sub"), ErrorMapper.fromCore(CoreError("errorAgent", "AGENT", sub), ErrorMapper.Op.SEND))
        }
        // The core's reply as it arrives (AgentErrorType AGENT {agentErr: A_CRYPTO {cryptoErr}}).
        val raw = """{"result":{"type":"chatCmdError","chatError":{"type":"errorAgent","agentError":{"type":"AGENT",
            "agentErr":{"type":"A_CRYPTO","cryptoErr":{"type":"DECRYPT_CB"}}}}}}"""
        val reply = CoreJson.parseReply(raw) as CoreReply.Error
        assertEquals(ErrorMapper.LINK_DAMAGED, ErrorMapper.fromCore(reply.error, ErrorMapper.Op.CONNECT).message)
        // Other agent errors on a link keep their meaning.
        assertEquals(ErrorMapper.LINK_USED, ErrorMapper.fromCore(CoreError("errorAgent", "SMP", "AUTH"), ErrorMapper.Op.CONNECT).message)
        assertEquals(ErrorMapper.engine("errorAgent.AGENT.A_VERSION"), ErrorMapper.fromCore(CoreError("errorAgent", "AGENT", "A_VERSION"), ErrorMapper.Op.CONNECT))
    }
}
