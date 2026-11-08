package app.encrogram.core.internal.media

import app.encrogram.core.internal.Samples
import app.encrogram.core.internal.ServerAddress

/**
 * File descriptions for [FileDescriptionCheckTest], built on the canonical encoder output pinned in
 * reference/simplexmq/tests/fixtures/file_description.yaml (simplexmq v7.0.1). FileDescriptionTests.hs:160-165 checks
 * that `strEncode` writes that file byte for byte, so its shape is the real emitter format: keys in alphabetical
 * order, indentless sequences, a final LF. Only the servers are swapped for ours; key, nonce, digests and replica
 * keys are the fixture's own values.
 */
internal object DescriptionSamples {

    /** Our XFTP relay as the app configures it, password included. */
    const val OUR_CONFIG = Samples.XFTP

    /** The same relay as the sender's agent writes it into a description: strEncode drops the password (Protocol.hs:1310-1311). */
    const val OURS = "xftp://sySVgvyI926QYrbpVtwz-SqLumN-LcYUKRFBYBW-5bM=@engrogramapi.nytmanager.com:5443"

    /** A second relay of ours (configured without a password), for descriptions spread over two servers. */
    const val OURS_2 = "xftp://Q2ZnHkRtYbVcXzLmNpQrStUvWxYz0123456789-_Abc=@files2.nytmanager.com:443"

    /** A stranger's relay: the fixture's own first server. */
    const val FOREIGN = "xftp://abc=@example1.com"

    const val KEY = "00n8p1tJq5E-SGnHcYTOrS4A9I07gTA_WFD6MTFFFOY="
    const val NONCE = "dPSF-wrQpDiK_K6sYv0BDBZ9S4dg-jmu"
    const val REPLICA_KEY = "MC4CAQAwBQYDK2VwBCIEIDfEfevydXXfKajz3sRkcQ7RPvfWUPoq6pu1TYHV1DEe"

    /** Canary key and nonce of the right lengths (32 and 24 bytes). They must never show up in any output. */
    const val CANARY_KEY = "CANARYKEY0000000000000000000000000000000000="
    const val CANARY_NONCE = "CANARYNONCE000000000000000000000"

    /** 26 MiB: the fixture's size (3 chunks of 8 MiB and one of 2 MiB). */
    const val FIXTURE_SIZE = 26L * 1024 * 1024

    /** A plausible plain size for a 26 MiB upload: within the 10 MiB margin below it. */
    const val INVITED = 25_000_000L

    val ourServers: List<ServerAddress> = listOf(ServerAddress.parse(OUR_CONFIG)!!)
    val bothOurServers: List<ServerAddress> = listOf(ServerAddress.parse(OUR_CONFIG)!!, ServerAddress.parse(OURS_2)!!)

    private fun text(vararg lines: String) = lines.joinToString("\n", postfix = "\n")

    /**
     * The fixture exactly, with its four servers replaced by [s1]..[s4]. With the fixture's own servers
     * (`xftp://abc=@example1.com` .. `example4.com`) this is the fixture file byte for byte.
     */
    fun fixture(s1: String, s2: String, s3: String, s4: String, key: String = KEY, nonce: String = NONCE): String = text(
        "chunkSize: 8mb",
        "digest: YWJj",
        "key: $key",
        "nonce: $nonce",
        "party: recipient",
        "replicas:",
        "- chunks:",
        "  - 1:YWJj:$REPLICA_KEY:Z2hp",
        "  - 3:YWJj:$REPLICA_KEY:Z2hp",
        "  server: $s1",
        "- chunks:",
        "  - 2:YWJj:$REPLICA_KEY:Z2hp",
        "  - 4:YWJj:$REPLICA_KEY:Z2hp:2mb",
        "  server: $s2",
        "- chunks:",
        "  - 1:YWJj:$REPLICA_KEY",
        "  - 4:YWJj:$REPLICA_KEY",
        "  server: $s3",
        "- chunks:",
        "  - 2:YWJj:$REPLICA_KEY",
        "  - 3:YWJj:$REPLICA_KEY",
        "  server: $s4",
        "size: 26mb",
    )

    /** The fixture's file with its replicas on our two relays, as several replica groups. */
    val SEVERAL = fixture(OURS, OURS_2, OURS_2, OURS)

    /**
     * The same file with one replica per chunk, all on our one relay: what the encoder writes for an upload to a single
     * server. Each line is a chunk's first replica, so each carries the digest, and chunk 4 its own 2 MiB size.
     */
    fun single(server: String = OURS, key: String = KEY, nonce: String = NONCE): String = text(
        "chunkSize: 8mb",
        "digest: YWJj",
        "key: $key",
        "nonce: $nonce",
        "party: recipient",
        "replicas:",
        "- chunks:",
        "  - 1:YWJj:$REPLICA_KEY:Z2hp",
        "  - 2:YWJj:$REPLICA_KEY:Z2hp",
        "  - 3:YWJj:$REPLICA_KEY:Z2hp",
        "  - 4:YWJj:$REPLICA_KEY:Z2hp:2mb",
        "  server: $server",
        "size: 26mb",
    )

    /** The honest sample most tests start from. */
    val HONEST = single()

    /** [HONEST] with the replica list indented by 2 and the chunk list 2 deeper than its key. */
    val INDENTED = text(
        "chunkSize: 8mb",
        "digest: YWJj",
        "key: $KEY",
        "nonce: $NONCE",
        "party: recipient",
        "replicas:",
        "  - chunks:",
        "      - 1:YWJj:$REPLICA_KEY:Z2hp",
        "      - 2:YWJj:$REPLICA_KEY:Z2hp",
        "      - 3:YWJj:$REPLICA_KEY:Z2hp",
        "      - 4:YWJj:$REPLICA_KEY:Z2hp:2mb",
        "    server: $OURS",
        "size: 26mb",
    )

    /** [HONEST] with the replica list indented by 2 and an indentless chunk list. */
    val INDENTED_REPLICAS = text(
        "chunkSize: 8mb",
        "digest: YWJj",
        "key: $KEY",
        "nonce: $NONCE",
        "party: recipient",
        "replicas:",
        "  - chunks:",
        "    - 1:YWJj:$REPLICA_KEY:Z2hp",
        "    - 2:YWJj:$REPLICA_KEY:Z2hp",
        "    - 3:YWJj:$REPLICA_KEY:Z2hp",
        "    - 4:YWJj:$REPLICA_KEY:Z2hp:2mb",
        "    server: $OURS",
        "size: 26mb",
    )

    /** [HONEST] with an indentless replica list but the chunk list 2 deeper than its key. */
    val INDENTED_CHUNKS = text(
        "chunkSize: 8mb",
        "digest: YWJj",
        "key: $KEY",
        "nonce: $NONCE",
        "party: recipient",
        "replicas:",
        "- chunks:",
        "    - 1:YWJj:$REPLICA_KEY:Z2hp",
        "    - 2:YWJj:$REPLICA_KEY:Z2hp",
        "    - 3:YWJj:$REPLICA_KEY:Z2hp",
        "    - 4:YWJj:$REPLICA_KEY:Z2hp:2mb",
        "  server: $OURS",
        "size: 26mb",
    )

    /** 1,234,567 bytes: not a whole number of KiB, so Data.Yaml writes the numeric string single-quoted. */
    val QUOTED_SIZE = text(
        "chunkSize: 1mb",
        "digest: YWJj",
        "key: $KEY",
        "nonce: $NONCE",
        "party: recipient",
        "replicas:",
        "- chunks:",
        "  - 1:YWJj:$REPLICA_KEY:Z2hp",
        "  - 2:YWJj:$REPLICA_KEY:Z2hp:185991",
        "  server: $OURS",
        "size: '1234567'",
    )

    /** 12 MiB in three default 4 MiB chunks: no five-field line. */
    val FOUR_MB_CHUNKS = text(
        "chunkSize: 4mb",
        "digest: YWJj",
        "key: $KEY",
        "nonce: $NONCE",
        "party: recipient",
        "replicas:",
        "- chunks:",
        "  - 1:YWJj:$REPLICA_KEY:Z2hp",
        "  - 2:YWJj:$REPLICA_KEY:Z2hp",
        "  - 3:YWJj:$REPLICA_KEY:Z2hp",
        "  server: $OURS",
        "size: 12mb",
    )

    /**
     * One replica group on our relay with [count] default chunks of [chunkBytes]: `count + 9` lines. A short
     * [replicaKey] keeps a long description under the character limit, to reach the line limit on its own.
     */
    fun manyChunks(count: Int, chunkBytes: Long = 64L * 1024, replicaKey: String = REPLICA_KEY): String {
        val lines = ArrayList<String>()
        lines += listOf("chunkSize: ${sizeText(chunkBytes)}", "digest: YWJj", "key: $KEY", "nonce: $NONCE", "party: recipient", "replicas:", "- chunks:")
        for (n in 1..count) lines += "  - $n:YWJj:$replicaKey:Z2hp"
        lines += "  server: $OURS"
        lines += "size: ${sizeText(count * chunkBytes)}"
        return text(*lines.toTypedArray())
    }

    /** FileSize strEncode (Description.hs:288-296), quoted the way Data.Yaml quotes a digits-only string. */
    fun sizeText(bytes: Long): String = when {
        bytes % 1024 != 0L -> "'$bytes'"
        (bytes / 1024) % 1024 != 0L -> "${bytes / 1024}kb"
        (bytes / (1024 * 1024)) % 1024 != 0L -> "${bytes / (1024 * 1024)}mb"
        else -> "${bytes / (1024L * 1024 * 1024)}gb"
    }

    /** One chunk of [bytes] on our relay, written as the encoder would write it. */
    fun oneChunk(bytes: Long): String = text(
        "chunkSize: ${sizeText(bytes)}",
        "digest: YWJj",
        "key: $KEY",
        "nonce: $NONCE",
        "party: recipient",
        "replicas:",
        "- chunks:",
        "  - 1:YWJj:$REPLICA_KEY:Z2hp",
        "  server: $OURS",
        "size: ${sizeText(bytes)}",
    )
}
