package app.encrogram.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KdfTest {

    // ---- RFC 5869 appendix A (SHA-256 cases) ----

    @Test
    fun hkdfRfc5869Case1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = unhex("000102030405060708090a0b0c")
        val info = unhex("f0f1f2f3f4f5f6f7f8f9")
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", hex(Kdf.hkdfExtract(salt, ikm)))
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            hex(Kdf.hkdfSha256(ikm, salt, info, 42)),
        )
    }

    @Test
    fun hkdfRfc5869Case2LongInputs() {
        val ikm = ByteArray(80) { it.toByte() }
        val salt = ByteArray(80) { (0x60 + it).toByte() }
        val info = ByteArray(80) { (0xb0 + it).toByte() }
        assertEquals("06a6b88c5853361a06104c9ceb35b45cef760014904671014a193f40c15fc244", hex(Kdf.hkdfExtract(salt, ikm)))
        assertEquals(
            "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71cc30c58179ec3e87c14c01d5c1f3434f1d87",
            hex(Kdf.hkdfSha256(ikm, salt, info, 82)),
        )
    }

    @Test
    fun hkdfRfc5869Case3NoSaltNoInfo() {
        val ikm = ByteArray(22) { 0x0b }
        assertEquals("19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04", hex(Kdf.hkdfExtract(null, ikm)))
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            hex(Kdf.hkdfSha256(ikm, ByteArray(0), ByteArray(0), 42)),
        )
    }

    // ---- backup id and key ----

    /**
     * Known answers pinning the storage format (iOS must match): BIP39 seed with an empty passphrase, then
     * HKDF-SHA256(salt none, info "encrogram/backup/id/v1" | ".../key/v1", 32). Recomputed with Python on 8 Nov 2026.
     */
    @Test
    fun backupSecretsKnownAnswers() {
        val abandon = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        assertEquals(
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4",
            hex(Kdf.bip39Seed(abandon)),
        )
        val a = Kdf.secretsFromWords(abandon.split(" "))!!
        assertEquals("0759aa5ae6344c2e4480462c2b2a64c6f5a0de69109a5ff2aa57645311379e4d", a.id)
        assertEquals("3a56b75d453cc85a328b99090bc07eff51af1ab4c76c0c6f62733264d031ee30", hex(a.key))

        val legal = Kdf.secretsFromWords("legal winner thank year wave sausage worth useful legal winner thank yellow".split(" "))!!
        assertEquals("24487950b68f8459bbe18e1a5baf9cb8472e9ac366dcfe035a9f99b89b20868e", legal.id)
        assertEquals("67aa5aa1eba1e10c7efc383e470b16087428b108486a10569ec6809340671134", hex(legal.key))
    }

    @Test
    fun idAndKeyAreSeparated() {
        val words = Bip39.toWords(Bip39.newEntropy())
        val s = Kdf.secretsFromWords(words)!!
        assertEquals(64, s.id.length)
        assertTrue(s.id.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(32, s.key.size)
        // Different info strings: the id (which travels to the server) says nothing about the key.
        assertNotEquals(s.id, hex(s.key))
        val seed = Kdf.bip39Seed(Bip39.sentence(words))
        assertEquals(s.id, hex(Kdf.hkdfSha256(seed, null, Kdf.INFO_ID.toByteArray(), 32)))
        assertEquals(hex(s.key), hex(Kdf.hkdfSha256(seed, null, Kdf.INFO_KEY.toByteArray(), 32)))
        // Deterministic, and normalisation doesn't change the result.
        assertEquals(s.id, Kdf.secretsFromWords(words.map { " " + it.uppercase() })!!.id)
        // Another phrase, another id.
        assertNotEquals(s.id, Kdf.secretsFromWords(Bip39.toWords(Bip39.newEntropy()))!!.id)
    }

    @Test
    fun secretsNeedValidWords() {
        assertNull(Kdf.secretsFromWords(List(12) { "abandon" }))
        assertNull(Kdf.secretsFromWords(listOf("abandon")))
        assertNotNull(Kdf.secretsFromWords(List(11) { "abandon" } + "about"))
    }

    @Test
    fun secretsNeverPrintThemselves() {
        val s = Kdf.secretsFromWords(List(11) { "abandon" } + "about")!!
        assertFalse(s.toString().contains(s.id))
        s.wipe()
        assertTrue(s.key.all { it == 0.toByte() })
    }

    @Test
    fun nonAsciiPassphraseUsesTheFallbackAndNfkd() {
        // "ｐａｓｓ" (full width) normalises to "pass": both spellings give the same seed.
        val m = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        assertEquals(hex(Kdf.bip39Seed(m, "pass")), hex(Kdf.bip39Seed(m, "ｐａｓｓ")))
        // A non-ASCII passphrase after NFKD (e accent) goes through the hand-written PBKDF2 with UTF-8 bytes.
        val nfkd = java.text.Normalizer.normalize("café", java.text.Normalizer.Form.NFKD)
        val expected = Kdf.pbkdf2HmacSha512(m.toByteArray(), ("mnemonic$nfkd").toByteArray(Charsets.UTF_8), 2048, 64)
        assertEquals(hex(expected), hex(Kdf.bip39Seed(m, "café")))
    }

    private fun hex(b: ByteArray) = Bip39.hex(b)

    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
