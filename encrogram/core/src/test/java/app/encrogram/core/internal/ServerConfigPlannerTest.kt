package app.encrogram.core.internal

import app.encrogram.core.api.RelayConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerConfigPlannerTest {

    private val config = RelayConfig(
        listOf(Samples.SMP),
        listOf(Samples.XFTP),
        listOf("encrogram.nytmanager.com", "engrogramapi.nytmanager.com"),
    )

    private fun body(raw: String): JSONObject = (CoreJson.parseReply(raw) as CoreReply.Result).body

    private fun servers(group: JSONObject, key: String): List<JSONObject> {
        val arr = group.getJSONArray(key)
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    // ---- config validation ------------------------------------------------------------------------------------

    @Test
    fun configValidation() {
        assertNull(ServerConfigPlanner.configProblem(config))
        assertEquals("no_smp_server", ServerConfigPlanner.configProblem(RelayConfig(emptyList(), listOf(Samples.XFTP), config.allowedHosts)))
        assertEquals("no_xftp_server", ServerConfigPlanner.configProblem(RelayConfig(listOf(Samples.SMP), listOf(" "), config.allowedHosts)))
        assertEquals("bad_smp_address", ServerConfigPlanner.configProblem(RelayConfig(listOf("encrogram.nytmanager.com"), listOf(Samples.XFTP), config.allowedHosts)))
        assertEquals("bad_xftp_address", ServerConfigPlanner.configProblem(RelayConfig(listOf(Samples.SMP), listOf(Samples.SMP), config.allowedHosts)))
        assertEquals("smp_host_not_allowed", ServerConfigPlanner.configProblem(RelayConfig(listOf(Samples.SMP), listOf(Samples.XFTP), listOf("other.example"))))
    }

    @Test
    fun relayConfigNeverPrintsAddresses() {
        assertFalse(config.toString().contains("secretpass"))
        assertFalse(config.toString().contains("nytmanager"))
    }

    // ---- operators ------------------------------------------------------------------------------------------

    @Test
    fun everyOperatorIsDisabledAndOtherFieldsSurvive() {
        val ops = ServerConfigPlanner.disabledOperators(body(Samples.OPERATORS))!!
        assertEquals(2, ops.length())
        for (i in 0 until ops.length()) {
            val op = ops.getJSONObject(i)
            assertFalse(op.getBoolean("enabled"))
            assertTrue(op.has("conditionsAcceptance"))
            assertTrue(op.has("smpRoles"))
        }
        assertEquals(1, ops.getJSONObject(0).getInt("operatorId"))
        assertEquals("simplex", ops.getJSONObject(0).getString("operatorTag"))
    }

    @Test
    fun operatorsVerification() {
        assertFalse(ServerConfigPlanner.allOperatorsDisabled(body(Samples.OPERATORS)))
        val disabled = Samples.OPERATORS.replace("\"enabled\":true", "\"enabled\":false")
        assertTrue(ServerConfigPlanner.allOperatorsDisabled(body(disabled)))
        assertTrue(ServerConfigPlanner.allOperatorsDisabled(JSONObject().put("conditions", JSONObject().put("serverOperators", JSONArray()))))
        assertFalse("a reply without conditions proves nothing", ServerConfigPlanner.allOperatorsDisabled(JSONObject()))
        assertNull(ServerConfigPlanner.disabledOperators(JSONObject()))
    }

    // ---- network --------------------------------------------------------------------------------------------

    @Test
    fun networkVerification() {
        assertTrue(ServerConfigPlanner.networkConfigOk(body(Samples.NETWORK_CONFIG_OK)))
        assertFalse(ServerConfigPlanner.networkConfigOk(body(Samples.NETWORK_CONFIG_DEFAULT)))
        val withSocks = JSONObject(Samples.NETWORK_CONFIG_OK).getJSONObject("result")
        withSocks.getJSONObject("networkConfig").put("socksProxy", "127.0.0.1:9050")
        assertFalse(ServerConfigPlanner.networkConfigOk(withSocks))
    }

    // ---- user servers ---------------------------------------------------------------------------------------

    @Test
    fun freshUserGetsExactlyOurServersInTheCustomGroup() {
        val update = ServerConfigPlanner.customServersUpdate(body(Samples.USER_SERVERS_FRESH), config)
        assertEquals("only the custom group is sent", 1, update.length())
        val group = update.getJSONObject(0)
        assertTrue(group.isNull("operator"))
        val smp = servers(group, "smpServers").single()
        assertEquals(Samples.SMP, smp.getString("server"))
        assertTrue(smp.isNull("serverId"))
        assertTrue(smp.getBoolean("enabled"))
        assertFalse(smp.getBoolean("deleted"))
        assertFalse(smp.getBoolean("preset"))
        assertTrue(smp.has("roles"))
        assertEquals(Samples.XFTP, servers(group, "xftpServers").single().getString("server"))
        assertEquals(0, group.getJSONArray("chatRelays").length())
    }

    @Test
    fun staleCustomServersAreDeletedFirstAndOurOldEntryIsUpdatedInPlace() {
        val update = ServerConfigPlanner.customServersUpdate(body(Samples.USER_SERVERS_STALE), config)
        val group = update.getJSONObject(0)
        val smp = servers(group, "smpServers")
        assertEquals(2, smp.size)
        // Deletions first, so a re-added host never collides with its old row.
        assertEquals(10, smp[0].getInt("serverId"))
        assertTrue(smp[0].getBoolean("deleted"))
        // Our relay's old row keeps its id and gets the current address, enabled.
        assertEquals(11, smp[1].getInt("serverId"))
        assertEquals(Samples.SMP, smp[1].getString("server"))
        assertTrue(smp[1].getBoolean("enabled"))
        assertFalse(smp[1].getBoolean("deleted"))
        // XFTP was missing: added new.
        val xftp = servers(group, "xftpServers").single()
        assertTrue(xftp.isNull("serverId"))
        assertEquals(Samples.XFTP, xftp.getString("server"))
    }

    @Test
    fun presetEntriesInTheCustomGroupAreDeletedToo() {
        val raw = Samples.USER_SERVERS_STALE.replace(
            "\"serverId\":10,\"server\":\"smp://old@other.example.org:5223\",\"preset\":false",
            "\"serverId\":10,\"server\":\"smp://old@other.example.org:5223\",\"preset\":true",
        )
        val smp = servers(ServerConfigPlanner.customServersUpdate(body(raw), config).getJSONObject(0), "smpServers")
        val old = smp.first { it.optInt("serverId") == 10 }
        assertTrue("no non-deleted leftover, even disabled", old.getBoolean("deleted"))
    }

    @Test
    fun verificationAcceptsOnlyOurRelays() {
        assertNull(ServerConfigPlanner.verifyUserServers(body(Samples.userServersConfigured()), config))
    }

    @Test
    fun verificationFailsClosed() {
        assertEquals("operator_enabled", ServerConfigPlanner.verifyUserServers(body(Samples.userServersConfigured(simplexEnabled = true)), config))
        assertEquals(
            "smp_unexpected_server",
            ServerConfigPlanner.verifyUserServers(body(Samples.userServersConfigured(extraCustomSmp = "smp://zz@relay.other.example")), config),
        )
        assertEquals("xftp_no_effective_server", ServerConfigPlanner.verifyUserServers(body(Samples.userServersConfigured(withXftp = false)), config))
        // A fresh user (presets on, nothing of ours) must never pass.
        assertEquals("operator_enabled", ServerConfigPlanner.verifyUserServers(body(Samples.USER_SERVERS_FRESH), config))
        assertEquals("no_user_servers", ServerConfigPlanner.verifyUserServers(JSONObject(), config))
    }

    private val smpKey = "7fc79ge_ADax0g_Lwu6YYwI1hFH6KwOG0y11w1YVORg="

    @Test
    fun verificationRejectsWrongFingerprint() {
        val raw = Samples.userServersConfigured().replace(smpKey, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
        assertEquals("smp_unexpected_server", ServerConfigPlanner.verifyUserServers(body(raw), config))
    }

    @Test
    fun verificationRejectsAnExtraHost() {
        val raw = Samples.userServersConfigured().replace("encrogram.nytmanager.com:5223", "encrogram.nytmanager.com,evil.example:5223")
        assertEquals("smp_unexpected_server", ServerConfigPlanner.verifyUserServers(body(raw), config))
    }

    @Test
    fun verificationRejectsALeftoverDisabledCustomServer() {
        val srv = """{"serverId":30,"server":"smp://zz@relay.other.example","preset":false,"tested":null,"enabled":false,"roles":{},"deleted":false}"""
        val raw = Samples.userServersConfigured().replace("\"smpServers\":[{\"serverId\":20", "\"smpServers\":[$srv,{\"serverId\":20")
        assertEquals("smp_leftover_custom_server", ServerConfigPlanner.verifyUserServers(body(raw), config))
        // The same entry marked deleted is fine.
        assertNull(ServerConfigPlanner.verifyUserServers(body(raw.replace("\"enabled\":false,\"roles\":{},\"deleted\":false", "\"enabled\":false,\"roles\":{},\"deleted\":true")), config))
    }

    @Test
    fun verificationRejectsAnEmptyXftpList() {
        // The operator's XFTP server sits under a disabled operator, so nothing is effective for XFTP.
        val bodyJson = JSONObject(Samples.userServersConfigured(withXftp = false)).getJSONObject("result")
        bodyJson.getJSONArray("userServers").getJSONObject(0).put("xftpServers", JSONArray())
        assertEquals("xftp_no_effective_server", ServerConfigPlanner.verifyUserServers(bodyJson, config))
    }

    @Test
    fun verificationAcceptsAnOmittedDefaultPortOnlyWhenItMatches() {
        val a = ServerAddress.parse("smp://k@h.example")!!
        assertTrue(a.sameRelayExact(ServerAddress.parse("smp://k:pw@h.example:5223")!!))
        assertFalse(a.sameRelayExact(ServerAddress.parse("smp://k@h.example:443")!!))
        assertFalse(a.sameRelayExact(ServerAddress.parse("smp://k@h.example,i.example")!!))
        assertFalse(a.sameRelayExact(ServerAddress.parse("smp://j@h.example")!!))
    }

    @Test
    fun verificationRejectsARelayHiddenUnderAnOperatorDomain() {
        // If an operator's domain covered our host, the engine would drop our server and fall back to presets.
        val raw = Samples.userServersConfigured().replace("\"serverDomains\":[\"simplex.im\"]", "\"serverDomains\":[\"nytmanager.com\"]")
        assertEquals("smp_host_matches_operator_domain", ServerConfigPlanner.verifyUserServers(body(raw), config))
    }

    @Test
    fun serverAddressParsing() {
        val a = ServerAddress.parse(Samples.SMP)!!
        assertEquals("smp", a.scheme)
        assertEquals(listOf("encrogram.nytmanager.com"), a.hosts)
        assertEquals("5223", a.port)
        assertFalse(a.toString().contains("secretpass"))
        val b = ServerAddress.parse("smp://kh@One.Example,two.example")!!
        assertEquals(listOf("one.example", "two.example"), b.hosts)
        assertEquals("", b.port)
        assertNull(ServerAddress.parse("smp://nohash"))
        assertNull(ServerAddress.parse("https://kh@host.example"))
        assertNull(ServerAddress.parse("smp://kh@host.example:port"))
        assertTrue(a.sameRelay(ServerAddress.parse("smp://other:pw@encrogram.nytmanager.com:5223")!!))
        assertFalse(a.sameRelay(ServerAddress.parse("smp://other@encrogram.nytmanager.com:443")!!))
    }
}
