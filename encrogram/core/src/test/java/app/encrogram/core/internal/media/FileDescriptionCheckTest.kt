package app.encrogram.core.internal.media

import app.encrogram.core.internal.Samples
import app.encrogram.core.internal.ServerAddress
import app.encrogram.core.internal.media.DescriptionSamples.CANARY_KEY
import app.encrogram.core.internal.media.DescriptionSamples.CANARY_NONCE
import app.encrogram.core.internal.media.DescriptionSamples.FIXTURE_SIZE
import app.encrogram.core.internal.media.DescriptionSamples.FOREIGN
import app.encrogram.core.internal.media.DescriptionSamples.FOUR_MB_CHUNKS
import app.encrogram.core.internal.media.DescriptionSamples.HONEST
import app.encrogram.core.internal.media.DescriptionSamples.INDENTED
import app.encrogram.core.internal.media.DescriptionSamples.INDENTED_CHUNKS
import app.encrogram.core.internal.media.DescriptionSamples.INDENTED_REPLICAS
import app.encrogram.core.internal.media.DescriptionSamples.INVITED
import app.encrogram.core.internal.media.DescriptionSamples.KEY
import app.encrogram.core.internal.media.DescriptionSamples.NONCE
import app.encrogram.core.internal.media.DescriptionSamples.OURS
import app.encrogram.core.internal.media.DescriptionSamples.OURS_2
import app.encrogram.core.internal.media.DescriptionSamples.QUOTED_SIZE
import app.encrogram.core.internal.media.DescriptionSamples.REPLICA_KEY
import app.encrogram.core.internal.media.DescriptionSamples.SEVERAL
import app.encrogram.core.internal.media.DescriptionSamples.bothOurServers
import app.encrogram.core.internal.media.DescriptionSamples.fixture
import app.encrogram.core.internal.media.DescriptionSamples.manyChunks
import app.encrogram.core.internal.media.DescriptionSamples.oneChunk
import app.encrogram.core.internal.media.DescriptionSamples.ourServers
import app.encrogram.core.internal.media.DescriptionSamples.single
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier
import java.util.Random

class FileDescriptionCheckTest {

    private val mib = 1024L * 1024

    private fun verdict(
        text: String?,
        invited: Long = INVITED,
        servers: List<ServerAddress> = ourServers,
        protocol: String = "xftp",
    ): Verdict = FileDescriptionCheck.check(text, invited, protocol, servers)

    /** replace() that fails loudly when the sample does not contain [old], so no test passes on an unchanged sample. */
    private fun String.swap(old: String, new: String): String {
        require(contains(old)) { "sample lacks: $old" }
        return replace(old, new)
    }

    private fun parse(address: String): ServerAddress = ServerAddress.parse(address)!!

    // ---- accepted ----------------------------------------------------------------------------------------------

    @Test
    fun honestSingleReplicaPasses() {
        assertEquals(Verdict.PASSED, verdict(HONEST))
    }

    @Test
    fun fixtureShapeOnOurServersPasses() {
        assertEquals(Verdict.PASSED, verdict(fixture(OURS, OURS, OURS, OURS)))
        assertEquals(Verdict.PASSED, verdict(SEVERAL, servers = bothOurServers))
    }

    @Test
    fun verbatimFixtureParsesAndOnlyItsServersDecide() {
        val verbatim = fixture("xftp://abc=@example1.com", "xftp://abc=@example2.com", "xftp://abc=@example3.com", "xftp://abc=@example4.com")
        assertEquals(Verdict.FOREIGN_SERVER, verdict(verbatim))
        val fixtureServers = (1..4).map { parse("xftp://abc=@example$it.com") }
        assertEquals(Verdict.PASSED, verdict(verbatim, servers = fixtureServers))
    }

    @Test
    fun fixtureSampleMatchesThePinnedFile() {
        // reference/ is git-ignored, so this runs only where the pinned simplexmq clone is present.
        val pinned = listOf("../../../reference", "../../reference", "reference")
            .map { File(it, "simplexmq/tests/fixtures/file_description.yaml") }
            .firstOrNull { it.isFile }
        assumeTrue(pinned != null)
        val verbatim = fixture("xftp://abc=@example1.com", "xftp://abc=@example2.com", "xftp://abc=@example3.com", "xftp://abc=@example4.com")
        assertEquals(pinned!!.readText(), verbatim)
    }

    @Test
    fun indentedSequencesPass() {
        assertEquals(Verdict.PASSED, verdict(INDENTED))
        assertEquals(Verdict.PASSED, verdict(INDENTED_REPLICAS))
        assertEquals(Verdict.PASSED, verdict(INDENTED_CHUNKS))
    }

    @Test
    fun quotedNumericSizePasses() {
        assertEquals(Verdict.PASSED, verdict(QUOTED_SIZE, invited = 1_200_000))
        // Quoting a value that needs no quotes changes nothing for YAML or the core.
        assertEquals(Verdict.PASSED, verdict(HONEST.swap("size: 26mb", "size: '26mb'")))
        assertEquals(Verdict.PASSED, verdict(HONEST.swap("key: $KEY", "key: '$KEY'")))
        assertEquals(Verdict.PASSED, verdict(HONEST.swap("digest: YWJj", "digest: '1234'")))
    }

    @Test
    fun keyStartingWithDocumentMarkerPassesQuoted() {
        // libyaml's emitter single-quotes a plain value that starts with "---".
        val dashKey = "---" + KEY.substring(3)
        assertEquals(Verdict.PASSED, verdict(HONEST.swap("key: $KEY", "key: '$dashKey'")))
    }

    @Test
    fun fourMbChunksPass() {
        assertEquals(Verdict.PASSED, verdict(FOUR_MB_CHUNKS, invited = 12_000_000))
    }

    @Test
    fun fiveFieldChunkSizeIsCounted() {
        assertEquals(Verdict.PASSED, verdict(HONEST))
        // Without its own 2 MiB, chunk 4 is a default 8 MiB chunk and the chunks add up to 32 MiB, not 26.
        assertEquals(Verdict.SIZE_MISMATCH, verdict(HONEST.swap(":Z2hp:2mb", ":Z2hp")))
    }

    @Test
    fun ourServerPasswordIsIgnored() {
        assertEquals(Verdict.PASSED, verdict(HONEST, servers = listOf(parse(Samples.XFTP))))
        assertEquals(Verdict.PASSED, verdict(HONEST, servers = listOf(parse(OURS))))
    }

    @Test
    fun otherConfiguredEntriesAreIgnored() {
        assertEquals(Verdict.PASSED, verdict(HONEST, servers = listOf(parse(Samples.SMP), parse(Samples.XFTP))))
    }

    @Test
    fun finalLineEndIsOptional() {
        assertEquals(Verdict.PASSED, verdict(HONEST.dropLast(1)))
    }

    @Test
    fun manyChunksPass() {
        assertEquals(Verdict.PASSED, verdict(manyChunks(1600), invited = 1600 * 64 * 1024 - 1000))
    }

    @Test
    fun defaultCapIs100MiB() {
        assertEquals(Verdict.PASSED, verdict(oneChunk(100 * mib), invited = 100 * mib))
        assertEquals(Verdict.TOO_LARGE, verdict(oneChunk(100 * mib + 1024), invited = 100 * mib + 1))
    }

    // ---- foreign servers ---------------------------------------------------------------------------------------

    @Test
    fun oneForeignReplicaAmongOurs() {
        assertEquals(Verdict.FOREIGN_SERVER, verdict(fixture(OURS, OURS, FOREIGN, OURS)))
        assertEquals(Verdict.FOREIGN_SERVER, verdict(fixture(OURS, OURS_2, OURS_2, FOREIGN), servers = bothOurServers))
    }

    @Test
    fun ourHostWithAnotherKeyHash() {
        val otherKey = "xftp://AAAAgvyI926QYrbpVtwz-SqLumN-LcYUKRFBYBW-5bM=@engrogramapi.nytmanager.com:5443"
        assertEquals(Verdict.FOREIGN_SERVER, verdict(single(otherKey)))
    }

    @Test
    fun ourHostOnAnotherPort() {
        val base = "xftp://sySVgvyI926QYrbpVtwz-SqLumN-LcYUKRFBYBW-5bM=@engrogramapi.nytmanager.com"
        assertEquals(Verdict.FOREIGN_SERVER, verdict(single("$base:5444")))
        assertEquals(Verdict.FOREIGN_SERVER, verdict(single("$base:443")))
        assertEquals(Verdict.FOREIGN_SERVER, verdict(single(base)))   // no port: the xftp default, 443
    }

    @Test
    fun hostListReorderedOrExtended() {
        val k = "sySVgvyI926QYrbpVtwz-SqLumN-LcYUKRFBYBW-5bM="
        val twoHosts = listOf(parse("xftp://$k@a.nytmanager.com,b.nytmanager.com:5443"))
        assertEquals(Verdict.PASSED, verdict(single("xftp://$k@a.nytmanager.com,b.nytmanager.com:5443"), servers = twoHosts))
        assertEquals(Verdict.FOREIGN_SERVER, verdict(single("xftp://$k@b.nytmanager.com,a.nytmanager.com:5443"), servers = twoHosts))
        assertEquals(Verdict.FOREIGN_SERVER, verdict(single("xftp://$k@engrogramapi.nytmanager.com,evil.example:5443")))
        assertEquals(Verdict.FOREIGN_SERVER, verdict(single("xftp://$k@evil.example,engrogramapi.nytmanager.com:5443")))
    }

    @Test
    fun smpSchemeIsForeign() {
        assertEquals(Verdict.FOREIGN_SERVER, verdict(single("smp://sySVgvyI926QYrbpVtwz-SqLumN-LcYUKRFBYBW-5bM=@engrogramapi.nytmanager.com:5443")))
    }

    @Test
    fun unconfiguredSecondRelayIsForeign() {
        assertEquals(Verdict.FOREIGN_SERVER, verdict(SEVERAL, servers = ourServers))
        assertEquals(Verdict.FOREIGN_SERVER, verdict(single(FOREIGN)))
    }

    @Test
    fun noConfiguredXftpRelayRefusesEverything() {
        assertEquals(Verdict.FOREIGN_SERVER, verdict(HONEST, servers = emptyList()))
        assertEquals(Verdict.FOREIGN_SERVER, verdict(HONEST, servers = listOf(parse(Samples.SMP))))
    }

    // ---- redirect ----------------------------------------------------------------------------------------------

    private val redirectBlock = "redirect:\n  digest: YWJj\n  size: 512\n"

    @Test
    fun redirectBlockAnywhere() {
        assertEquals(Verdict.REDIRECT, verdict(redirectBlock + HONEST))
        assertEquals(Verdict.REDIRECT, verdict(HONEST + redirectBlock))
        // Where the encoder would put it (keys in alphabetical order).
        assertEquals(Verdict.REDIRECT, verdict(HONEST.swap("party: recipient\n", "party: recipient\n$redirectBlock")))
    }

    @Test
    fun redirectNullOrEmpty() {
        assertEquals(Verdict.REDIRECT, verdict(HONEST.swap("party: recipient\n", "party: recipient\nredirect: null\n")))
        assertEquals(Verdict.REDIRECT, verdict(HONEST.swap("party: recipient\n", "party: recipient\nredirect:\n")))
        assertEquals(Verdict.REDIRECT, verdict(HONEST + "redirect:"))
    }

    // ---- malformed ---------------------------------------------------------------------------------------------

    @Test
    fun flowStyle() {
        assertEquals(Verdict.MALFORMED, verdict("{redirect: {digest: YWJj, size: 512}, party: recipient}\n"))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("party: recipient\n", "party: recipient\nredirect: {digest: YWJj, size: 512}\n")))
        assertEquals(Verdict.MALFORMED, verdict("replicas: [{server: '$OURS', chunks: ['1:YWJj:$REPLICA_KEY:Z2hp']}]\n"))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("party: recipient", "party: {recipient}")))
    }

    @Test
    fun quotedKeys() {
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("party: recipient\n", "party: recipient\n\"redirect\": null\n")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("party: recipient\n", "party: recipient\n'redirect': null\n")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("party: recipient", "'party': recipient")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("  server: ", "  'server': ")))
    }

    @Test
    fun duplicateKeys() {
        assertEquals(Verdict.MALFORMED, verdict(HONEST + "replicas:\n- chunks:\n  - 1:YWJj:$REPLICA_KEY:Z2hp\n  server: $FOREIGN\n"))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("  server: $OURS\n", "  server: $OURS\n  server: $FOREIGN\n")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("  server: $OURS\n", "  server: $OURS\n  chunks:\n  - 1:YWJj:$REPLICA_KEY:Z2hp\n")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST + "size: 26mb\n"))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("party: recipient\n", "party: recipient\nparty: recipient\n")))
    }

    @Test
    fun unknownKeys() {
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("party: recipient\n", "party: recipient\nextra: abc\n")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("  server: $OURS\n", "  server: $OURS\n  weight: abc\n")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("  server: $OURS\n", "  server: $OURS\n  redirect: abc\n")))
    }

    @Test
    fun anchorsAliasesAndTags() {
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("key: $KEY", "key: &k $KEY")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("nonce: $NONCE", "nonce: *n")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("digest: YWJj", "digest: !!str YWJj")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("digest: YWJj", "digest: !!binary YWJj")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("party: recipient\n", "party: recipient\n<<: *base\n")))
    }

    @Test
    fun blockScalars() {
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("digest: YWJj\n", "digest: |\n  YWJj\n")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("digest: YWJj\n", "digest: >\n  YWJj\n")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("  server: $OURS\n", "  server: |-\n    $OURS\n")))
    }

    @Test
    fun comments() {
        assertEquals(Verdict.MALFORMED, verdict("# description\n$HONEST"))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("party: recipient", "party: recipient #x")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("party: recipient", "party: recipient#x")))
        assertEquals(Verdict.MALFORMED, verdict("################################\n$HONEST"))   // fdSeparator
    }

    @Test
    fun documentMarkers() {
        assertEquals(Verdict.MALFORMED, verdict("---\n$HONEST"))
        assertEquals(Verdict.MALFORMED, verdict("--- \n$HONEST"))
        assertEquals(Verdict.MALFORMED, verdict("$HONEST...\n"))
        assertEquals(Verdict.MALFORMED, verdict("$HONEST---\n$HONEST"))
        assertEquals(Verdict.MALFORMED, verdict("%YAML 1.1\n---\n$HONEST"))
    }

    @Test
    fun tabsCarriageReturnsAndNonAscii() {
        assertEquals(Verdict.MALFORMED, verdict(HONEST.replace("\n", "\r\n")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("  server: ", "\tserver: ")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("party: recipient", "party:\trecipient")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("engrogramapi", "engrogramapı")))   // dotless i
        assertEquals(Verdict.MALFORMED, verdict("﻿$HONEST"))   // byte order mark
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("digest: YWJj", "digest: YW\u0000Jj")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("party: recipient\n", "party: recipient ")))
    }

    @Test
    fun chunkNumberGap() {
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("  - 4:", "  - 5:")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("  - 1:", "  - 0:")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("  - 4:", "  - 04:")))
    }

    @Test
    fun conflictingChunkSizesOrDigests() {
        val shape = fixture(OURS, OURS, OURS, OURS)
        assertEquals(Verdict.PASSED, verdict(shape))
        assertEquals(Verdict.MALFORMED, verdict(shape.swap("  - 4:YWJj:$REPLICA_KEY\n", "  - 4:YWJj:$REPLICA_KEY:Z2hp:3mb\n")))
        assertEquals(Verdict.MALFORMED, verdict(shape.swap("  - 1:YWJj:$REPLICA_KEY\n", "  - 1:YWJj:$REPLICA_KEY:Z2hq\n")))
        // A second replica may repeat the same digest and size: no conflict.
        assertEquals(Verdict.PASSED, verdict(shape.swap("  - 4:YWJj:$REPLICA_KEY\n", "  - 4:YWJj:$REPLICA_KEY:Z2hp:2mb\n")))
    }

    @Test
    fun chunkWithoutDigest() {
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("  - 2:YWJj:$REPLICA_KEY:Z2hp\n", "  - 2:YWJj:$REPLICA_KEY\n")))
    }

    @Test
    fun emptyReplicas() {
        assertEquals(Verdict.MALFORMED, verdict(HONEST.substringBefore("replicas:") + "replicas:\nsize: 26mb\n"))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.substringBefore("replicas:") + "replicas: []\nsize: 26mb\n"))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.substringBefore("replicas:") + "size: 26mb\n"))
    }

    @Test
    fun replicaWithoutServerOrChunks() {
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("  server: $OURS\n", "")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.substringBefore("replicas:") + "replicas:\n- server: $OURS\nsize: 26mb\n"))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.substringBefore("replicas:") + "replicas:\n- chunks:\n  server: $OURS\nsize: 26mb\n"))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("  server: $OURS", "  server:")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("- chunks:\n", "-\n  chunks:\n")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("- chunks:\n", "-chunks:\n")))
    }

    @Test
    fun serverFirstInItemIsFine() {
        val serverFirst = HONEST.substringBefore("replicas:") +
            "replicas:\n- server: $OURS\n  chunks:\n  - 1:YWJj:$REPLICA_KEY:Z2hp\n  - 2:YWJj:$REPLICA_KEY:Z2hp\n" +
            "  - 3:YWJj:$REPLICA_KEY:Z2hp\n  - 4:YWJj:$REPLICA_KEY:Z2hp:2mb\nsize: 26mb\n"
        assertEquals(Verdict.PASSED, verdict(serverFirst))
    }

    @Test
    fun overflowingSizes() {
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("size: 26mb", "size: 99999999999gb")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("size: 26mb", "size: '99999999999999999999'")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("chunkSize: 8mb", "chunkSize: 4gb")))   // over Word32
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap(":Z2hp:2mb", ":Z2hp:5gb")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("chunkSize: 8mb", "chunkSize: 0gb")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap(":Z2hp:2mb", ":Z2hp:0gb")))
    }

    @Test
    fun nonCanonicalSizes() {
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("size: 26mb", "size: 26624kb")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("size: 26mb", "size: 026mb")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("size: 26mb", "size: 26MB")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("size: 26mb", "size: '27262976'")))   // is 26mb
        assertEquals(Verdict.MALFORMED, verdict(QUOTED_SIZE.swap("size: '1234567'", "size: 1234567")))   // a YAML number
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("size: 26mb", "size: 26 mb")))
    }

    @Test
    fun tooLongText() {
        val room = FileDescriptionCheck.MAX_TEXT_CHARS - HONEST.length + 4
        val fits = room - room % 4
        val atLimit = HONEST.swap("digest: YWJj", "digest: " + "A".repeat(fits))
        val overLimit = HONEST.swap("digest: YWJj", "digest: " + "A".repeat(fits + 4))
        assertFalse(atLimit.length > FileDescriptionCheck.MAX_TEXT_CHARS)
        assertEquals(Verdict.PASSED, verdict(atLimit))
        assertEquals(Verdict.MALFORMED, verdict(overLimit))
    }

    @Test
    fun tooManyLines() {
        val kib = 1024L
        val count = FileDescriptionCheck.MAX_LINES - 9
        assertEquals(Verdict.PASSED, verdict(manyChunks(count, kib, "YWJj"), invited = count * kib - 100))
        assertEquals(Verdict.MALFORMED, verdict(manyChunks(count + 1, kib, "YWJj"), invited = (count + 1) * kib - 100))
    }

    @Test
    fun nullOrEmpty() {
        assertEquals(Verdict.MALFORMED, verdict(null))
        assertEquals(Verdict.MALFORMED, verdict(""))
        assertEquals(Verdict.MALFORMED, verdict("\n"))
        assertEquals(Verdict.MALFORMED, verdict("   "))
    }

    @Test
    fun missingRequiredKey() {
        for (key in listOf("chunkSize", "digest", "key", "nonce", "party", "size")) {
            val line = HONEST.lines().first { it.startsWith("$key: ") }
            assertEquals(key, Verdict.MALFORMED, verdict(HONEST.swap("$line\n", "")))
        }
    }

    @Test
    fun continuationLines() {
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("  server: $OURS\n", "  server: $OURS\n   ,evil.example\n")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("key: $KEY\n", "key: $KEY\n  more\n")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("  - 2:YWJj:$REPLICA_KEY:Z2hp\n", "  - 2:YWJj:$REPLICA_KEY:Z2hp\n    :x\n")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("party: recipient\n", "party:\n  recipient\n")))
    }

    @Test
    fun wrongIndentation() {
        assertEquals(Verdict.MALFORMED, verdict(HONEST.replace("  - ", "   - ")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("- chunks:", " - chunks:")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("party: recipient", " party: recipient")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("  server: ", "    server: ")))
        // Chunk items after the server, or one list at two depths.
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("  server: $OURS\n", "  server: $OURS\n  - 5:YWJj:$REPLICA_KEY:Z2hp\n")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("  - 3:", "    - 3:")))
    }

    @Test
    fun spacing() {
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("party: recipient", "party: recipient ")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("party: recipient", "party:  recipient")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("party: recipient", "party : recipient")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("party: recipient", "party:recipient")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("party: recipient\n", "party: recipient\n\n")))
        assertEquals(Verdict.MALFORMED, verdict("$HONEST\n"))
    }

    @Test
    fun badPartyValue() {
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("party: recipient", "party: Recipient")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("party: recipient", "party: owner")))
    }

    @Test
    fun badKeyNonceOrBase64() {
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("key: $KEY", "key: ${KEY.dropLast(1)}")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("key: $KEY", "key: ${KEY.dropLast(1)}A")))      // 33 bytes
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("nonce: $NONCE", "nonce: ${NONCE.drop(4)}")))   // 21 bytes
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("digest: YWJj", "digest: YWJ")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("digest: YWJj", "digest: Y===")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("digest: YWJj", "digest: YW=j")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("digest: YWJj", "digest: YW/j")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap(":Z2hp:2mb", ":Z2h:2mb")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("  - 1:YWJj:", "  - 1::")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap(":Z2hp:2mb", ":Z2hp:2mb:")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap(":Z2hp:2mb", ":Z2hp:2mb:YWJj")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("  - 1:YWJj:$REPLICA_KEY:Z2hp", "  - 1:YWJj")))
    }

    @Test
    fun plainValuesYamlWouldNotReadAsStrings() {
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("digest: YWJj", "digest: null")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("digest: YWJj", "digest: TRUE")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("digest: YWJj", "digest: 1234")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("digest: YWJj", "digest: 1e10")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("digest: YWJj", "digest: 0x1f")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("digest: YWJj", "digest: ''")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("digest: YWJj", "digest: 'YW'Jj'")))
        assertEquals(Verdict.MALFORMED, verdict(HONEST.swap("digest: YWJj", "digest: 'YWJj")))
    }

    @Test
    fun passwordInDescriptionServer() {
        // strEncode never writes one and the core refuses one (Protocol.hs:1309-1315).
        assertEquals(Verdict.MALFORMED, verdict(single("xftp://sySVgvyI926QYrbpVtwz-SqLumN-LcYUKRFBYBW-5bM=:secretpass@engrogramapi.nytmanager.com:5443")))
    }

    @Test
    fun malformedServerValues() {
        val k = "sySVgvyI926QYrbpVtwz-SqLumN-LcYUKRFBYBW-5bM="
        assertEquals(Verdict.MALFORMED, verdict(single("XFTP://$k@engrogramapi.nytmanager.com:5443")))
        assertEquals(Verdict.MALFORMED, verdict(single("xftp://$k@EngrogramAPI.nytmanager.com:5443")))
        assertEquals(Verdict.MALFORMED, verdict(single("xftp://$k@engrogramapi.nytmanager.com.:5443")))
        assertEquals(Verdict.MALFORMED, verdict(single("xftp://$k@engrogramapi.nytmanager.com:05443")))
        assertEquals(Verdict.MALFORMED, verdict(single("xftp://$k@engrogramapi.nytmanager.com:5443/path")))
        assertEquals(Verdict.MALFORMED, verdict(single("xftp://$k@engrogramapi.nytmanager.com:")))
        assertEquals(Verdict.MALFORMED, verdict(single("xftp://$k@ours@engrogramapi.nytmanager.com:5443")))
        assertEquals(Verdict.MALFORMED, verdict(single("xftp://@engrogramapi.nytmanager.com:5443")))
        assertEquals(Verdict.MALFORMED, verdict(single("xftp://$k@engrogramapi.nytmanager.com,,b.example:5443")))
        assertEquals(Verdict.MALFORMED, verdict(single("engrogramapi.nytmanager.com")))
        assertEquals(Verdict.MALFORMED, verdict(single("'$OURS'")))
    }

    // ---- other refusals ----------------------------------------------------------------------------------------

    @Test
    fun senderPartyIsRefused() {
        assertEquals(Verdict.NOT_RECIPIENT, verdict(HONEST.swap("party: recipient", "party: sender")))
    }

    @Test
    fun chunkSumDiffersFromSize() {
        assertEquals(Verdict.SIZE_MISMATCH, verdict(HONEST.swap("size: 26mb", "size: 27mb")))
        assertEquals(Verdict.SIZE_MISMATCH, verdict(HONEST.swap("size: 26mb", "size: 25mb")))
    }

    @Test
    fun sizeAgainstTheInvitation() {
        assertEquals(Verdict.SIZE_MISMATCH, verdict(HONEST, invited = FIXTURE_SIZE + 1))
        assertEquals(Verdict.PASSED, verdict(HONEST, invited = FIXTURE_SIZE))
        assertEquals(Verdict.PASSED, verdict(HONEST, invited = FIXTURE_SIZE - FileDescriptionCheck.SIZE_MARGIN))
        assertEquals(Verdict.SIZE_MISMATCH, verdict(HONEST, invited = FIXTURE_SIZE - FileDescriptionCheck.SIZE_MARGIN - 1))
        assertEquals(Verdict.SIZE_MISMATCH, verdict(HONEST, invited = -1))
    }

    @Test
    fun invitationOverTheCap() {
        assertEquals(Verdict.TOO_LARGE, verdict(HONEST, invited = 100 * mib + 1))
        assertEquals(Verdict.TOO_LARGE, FileDescriptionCheck.check(HONEST, INVITED, "xftp", ourServers, maxBytes = 20 * mib))
        assertEquals(Verdict.TOO_LARGE, verdict(null, invited = 100 * mib + 1))   // decided before the text is read
    }

    @Test
    fun describedOverTheCapPlusMargin() {
        assertEquals(Verdict.PASSED, verdict(oneChunk(110 * mib), invited = 100 * mib))
        assertEquals(Verdict.TOO_LARGE, verdict(oneChunk(110 * mib + 1024), invited = 100 * mib))
        assertEquals(Verdict.TOO_LARGE, verdict(oneChunk(2048 * mib), invited = 100 * mib))
    }

    @Test
    fun onlyXftpIsChecked() {
        for (protocol in listOf("smp", "local", "XFTP", "", "xftp ")) {
            assertEquals(protocol, Verdict.PROTOCOL, verdict(HONEST, protocol = protocol))
        }
        assertEquals(Verdict.PROTOCOL, verdict(null, protocol = "smp"))
    }

    // ---- canary and robustness ---------------------------------------------------------------------------------

    @Test
    fun canaryKeyAndNonceAreNeverKept() {
        val canary = single(key = CANARY_KEY, nonce = CANARY_NONCE)
        val results = listOf(
            verdict(canary),
            verdict(single(FOREIGN, key = CANARY_KEY, nonce = CANARY_NONCE)),
            verdict(canary.swap("party: recipient", "party: sender")),
            verdict(canary + "redirect:\n"),
            verdict(canary.dropLast(5)),
        )
        assertEquals(Verdict.PASSED, results[0])
        for (v in results) assertFalse(v.toString().contains("CANARY"))
        // The checker is stateless: no static field holds anything from a description.
        for (field in FileDescriptionCheck::class.java.declaredFields) {
            if (!Modifier.isStatic(field.modifiers)) continue
            field.isAccessible = true
            val value = field.get(null)
            assertFalse(field.name, "$value".contains("CANARY"))
        }
    }

    @Test
    fun truncationAtEveryLineNeverPasses() {
        for (sample in listOf(HONEST, SEVERAL, INDENTED, QUOTED_SIZE)) {
            val lines = sample.trimEnd('\n').split('\n')
            for (k in 0 until lines.size) {
                val cut = lines.take(k).joinToString("\n", postfix = "\n")
                assertNotEquals("first $k lines", Verdict.PASSED, verdict(cut, invited = 1_200_000, servers = bothOurServers))
            }
        }
    }

    @Test
    fun truncationAtEveryByteNeverThrowsAndPassesOnlyWhenWhole() {
        for (sample in listOf(HONEST, SEVERAL, INDENTED, QUOTED_SIZE)) {
            val invited = if (sample === QUOTED_SIZE) 1_200_000 else INVITED
            for (p in 0 until sample.length) {
                val v = verdict(sample.substring(0, p), invited = invited, servers = bothOurServers)
                if (p < sample.length - 1) assertNotEquals("cut at $p", Verdict.PASSED, v)
                else assertEquals("cut at $p (only the final LF dropped)", Verdict.PASSED, v)
            }
        }
    }

    @Test
    fun randomEditsNeverThrow() {
        val palette = "\r\t\n \"'#{}[]&*!|>%?~:-_=/@.,\u0000é aZ09"
        val random = Random(20261108)
        for (round in 0 until 5000) {
            val chars = StringBuilder(if (round % 2 == 0) HONEST else SEVERAL)
            repeat(1 + random.nextInt(3)) {
                val at = random.nextInt(chars.length)
                when (random.nextInt(3)) {
                    0 -> chars.setCharAt(at, palette[random.nextInt(palette.length)])
                    1 -> chars.deleteCharAt(at)
                    else -> chars.insert(at, palette[random.nextInt(palette.length)])
                }
            }
            verdict(chars.toString(), servers = bothOurServers)   // must return, whatever the verdict
        }
    }
}
