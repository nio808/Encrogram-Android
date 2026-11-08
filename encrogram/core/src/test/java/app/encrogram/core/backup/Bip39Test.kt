package app.encrogram.core.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class Bip39Test {

    /**
     * The 12-word English vectors of the official BIP39 test set (trezor/python-mnemonic vectors.json, passphrase
     * "TREZOR"): entropy, mnemonic, seed. Each was also recomputed independently (Python hashlib) on 8 Nov 2026.
     */
    private val vectors = listOf(
        Triple(
            "00000000000000000000000000000000",
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
            "c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e53495531f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04",
        ),
        Triple(
            "7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f",
            "legal winner thank year wave sausage worth useful legal winner thank yellow",
            "2e8905819b8723fe2c1d161860e5ee1830318dbf49a83bd451cfb8440c28bd6fa457fe1296106559a3c80937a1c1069be3a3a5bd381ee6260e8d9739fce1f607",
        ),
        Triple(
            "80808080808080808080808080808080",
            "letter advice cage absurd amount doctor acoustic avoid letter advice cage above",
            "d71de856f81a8acc65e6fc851a38d4d7ec216fd0796d0a6827a3ad6ed5511a30fa280f12eb2e47ed2ac03b5c462a0358d18d69fe4f985ec81778c1b370b652a8",
        ),
        Triple(
            "ffffffffffffffffffffffffffffffff",
            "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong",
            "ac27495480225222079d7be181583751e86f571027b0497b5b5d11218e0a8a13332572917f0f8e5a589620c6f15b11c61dee327651a14c34e18231052e48c069",
        ),
        Triple(
            "9e885d952ad362caeb4efe34a8e91bd2",
            "ozone drill grab fiber curtain grace pudding thank cruise elder eight picnic",
            "274ddc525802f7c828d8ef7ddbcdc5304e87ac3535913611fbbfa986d0c9e5476c91689f9c8a54fd55bd38606aa6a8595ad213d4c9c9f9aca3fb217069a41028",
        ),
        Triple(
            "c0ba5a8e914111210f2bd131f3d5e08d",
            "scheme spot photo card baby mountain device kick cradle pact join borrow",
            "ea725895aaae8d4c1cf682c1bfd2d358d52ed9f0f0591131b559e2724bb234fca05aa9c02c57407e04ee9dc3b454aa63fbff483a8b11de949624b9f1831a9612",
        ),
        Triple(
            "f30f8c1da665478f49b001d94c5fc452",
            "vessel ladder alter error federal sibling chat ability sun glass valve picture",
            "2aaa9242daafcee6aa9d7269f17d4efe271e1b9a529178d7dc139cd18747090bf9d60295d0ce74309a78852a9caadf0af48aae1c6253839624076224374bc63f",
        ),
    )

    @Test
    fun wordListIsTheOfficialOne() {
        val bytes = Bip39::class.java.getResourceAsStream("/app/encrogram/core/backup/bip39-english.txt")!!.use { it.readBytes() }
        assertEquals(Bip39.WORDLIST_SHA256, hex(MessageDigest.getInstance("SHA-256").digest(bytes)))
        assertEquals("2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda", Bip39.WORDLIST_SHA256)
        assertEquals(2048, Bip39.words.size)
        assertEquals("abandon", Bip39.words.first())
        assertEquals("zoo", Bip39.words.last())
        assertEquals(2048, Bip39.words.toSet().size)
        assertNotNull("the MIT notice ships with the list", Bip39::class.java.getResource("/app/encrogram/core/backup/bip39-english.NOTICE.txt"))
    }

    @Test
    fun officialVectorsEntropyToWords() {
        for ((entropy, mnemonic, _) in vectors) {
            assertEquals(mnemonic, Bip39.toWords(unhex(entropy)).joinToString(" "))
        }
    }

    @Test
    fun officialVectorsWordsToEntropy() {
        for ((entropy, mnemonic, _) in vectors) {
            assertArrayEquals(unhex(entropy), Bip39.toEntropy(mnemonic.split(" ")))
        }
    }

    @Test
    fun officialVectorsSeedWithTrezorPassphrase() {
        for ((_, mnemonic, seed) in vectors) {
            assertEquals(seed, hex(Kdf.bip39Seed(mnemonic, "TREZOR")))
        }
    }

    @Test
    fun handWrittenPbkdf2MatchesTheVectorsToo() {
        for ((_, mnemonic, seed) in vectors) {
            val out = Kdf.pbkdf2HmacSha512(mnemonic.toByteArray(), "mnemonicTREZOR".toByteArray(), 2048, 64)
            assertEquals(seed, hex(out))
        }
    }

    @Test
    fun platformPbkdf2MatchesTheFallback() {
        val m = vectors[4].second
        val platform = Kdf.platformPbkdf2(m, "mnemonicTREZOR".toByteArray(), 2048, 64)
        assertNotNull("the JVM has PBKDF2WithHmacSHA512", platform)
        assertEquals(vectors[4].third, hex(platform!!))
    }

    @Test
    fun checksumFailuresAreRefused() {
        // Same 11 words, wrong last word: "abandon x11 abandon" fails the 4-bit checksum ("about" is right).
        assertNull(Bip39.toEntropy(List(12) { "abandon" }))
        val good = vectors[1].second.split(" ")
        // Swapping two different words keeps every word valid but breaks the checksum (checked in Python: swapping
        // words 0 and 2 happens to pass, as about 1 in 16 swaps do, so these pairs were picked).
        for ((a, b) in listOf(0 to 1, 1 to 3, 0 to 4, 2 to 5)) {
            val swapped = good.toMutableList().also { val t = it[a]; it[a] = it[b]; it[b] = t }
            assertNull("swap $a,$b", Bip39.toEntropy(swapped))
        }
        // Last word replaced by its neighbour in the list.
        val lastIndex = Bip39.words.indexOf(good.last())
        val neighbour = good.dropLast(1) + Bip39.words[(lastIndex + 1) % 2048]
        assertNull(Bip39.toEntropy(neighbour))
    }

    @Test
    fun wrongCountsAndUnknownWordsAreRefused() {
        val good = vectors[0].second.split(" ")
        assertNull(Bip39.toEntropy(good.dropLast(1)))
        assertNull(Bip39.toEntropy(good + "abandon"))
        assertNull(Bip39.toEntropy(good.dropLast(1) + "bitcoin"))
        assertNull(Bip39.toEntropy(emptyList()))
        assertNull(Kdf.secretsFromWords(good.dropLast(1) + "zebra1"))
    }

    @Test
    fun inputIsNormalised() {
        val (entropy, mnemonic, _) = vectors[2]
        val messy = mnemonic.split(" ").map { "  " + it.uppercase() + "\t" } + listOf("", "  ")
        assertArrayEquals(unhex(entropy), Bip39.toEntropy(messy))
        // NFKD: full-width letters fold to ASCII, as BIP39 requires.
        val fullWidth = mnemonic.split(" ").map { w -> w.map { (it.code - 'a'.code + 0xFF41).toChar() }.joinToString("") }
        assertArrayEquals(unhex(entropy), Bip39.toEntropy(fullWidth))
        assertEquals(mnemonic, Bip39.sentence(messy))
    }

    @Test
    fun newEntropyRoundTrips() {
        repeat(20) {
            val e = Bip39.newEntropy()
            assertEquals(16, e.size)
            val words = Bip39.toWords(e)
            assertEquals(12, words.size)
            assertArrayEquals(e, Bip39.toEntropy(words))
        }
    }

    @Test
    fun suggestions() {
        assertEquals(listOf("abandon", "ability", "able"), Bip39.suggestions("ab", 3))
        assertEquals(listOf("zone", "zoo"), Bip39.suggestions("zo", 5))
        assertEquals(listOf("zoo"), Bip39.suggestions(" ZOO ", 5))
        assertTrue(Bip39.suggestions("", 5).isEmpty())
        assertTrue(Bip39.suggestions("qq", 5).isEmpty())
        assertTrue(Bip39.suggestions("a", 0).isEmpty())
        assertTrue(Bip39.suggestions("a", 1000).all { it.startsWith("a") })
    }

    private fun hex(b: ByteArray) = Bip39.hex(b)

    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
