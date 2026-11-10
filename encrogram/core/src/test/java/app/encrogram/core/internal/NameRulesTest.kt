package app.encrogram.core.internal

import app.encrogram.core.api.SecuredError
import app.encrogram.core.api.SecuredProfile
import app.encrogram.core.internal.InputRules.NameProblem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Random

/**
 * H8: display names checked before they reach the core, by a port of its mkValidName (Commands.hs:6035-6060), which
 * checkValidName (Commands.hs:4058) applies to profile names (CreateActiveUser, updateProfile_) and group names
 * (newGroup, runUpdateGroupProfile) alike. Expected values are worked out by hand from the Haskell fold.
 */
class NameRulesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var harness: EngineHarness? = null

    @After
    fun tearDown() {
        harness?.close()
    }

    private fun valid(name: String) = InputRules.mkValidName(name)

    @Test
    fun namesTheCoreKeepsAsTheyAre() {
        for (n in listOf(
            "Alice", "Bob Smith", "Harsh A <b>", "Bee 2 the long-named tester", "Mary-Jane", "Ali_", "007", "100%",
            "Bob & Alice", "A - B", "A! B", "Bob \$5", "x+y", "😀", "😀 Bob", "❤️ Bob", "Bob ❤️", "1️⃣", "Jose\u0301",
            "Привет", "李小龍", "مرحبا", "שלום", "🇩🇪 Berlin", "a".repeat(50),
        )) {
            assertEquals(n, n, valid(n))
            assertNull(n, InputRules.nameProblem(n))
        }
    }

    @Test
    fun whatTheFoldDropsAndWhy() {
        val cases = listOf(
            // prohibited anywhere: . , ; / \ # @ ' " ` ~
            Triple("a.b", "ab", NameProblem.PROHIBITED),
            Triple("Bob's", "Bobs", NameProblem.PROHIBITED),
            Triple("Bob \"x\"", "Bob x", NameProblem.PROHIBITED),
            Triple("@bob", "bob", NameProblem.PROHIBITED),
            Triple("Bob ~", "Bob", NameProblem.PROHIBITED),
            // the first character must be a letter, a digit or an OtherSymbol (most emoji)
            Triple("-Bob", "Bob", NameProblem.START),
            Triple("\$Bob", "Bob", NameProblem.START),
            Triple("+1 Bob", "1 Bob", NameProblem.START),
            Triple("__init", "init", NameProblem.START),
            Triple("%Bob", "Bob", NameProblem.START),
            Triple("\u0301A", "A", NameProblem.START),
            // after punctuation (Pc, Pd, Po), only a letter, digit, symbol or space; the count goes on over a space
            Triple("Wow!!", "Wow!", NameProblem.PUNCTUATION),
            Triple("Wow!?", "Wow!", NameProblem.PUNCTUATION),
            Triple("Mary--Jane", "Mary-Jane", NameProblem.PUNCTUATION),
            Triple("A! !B", "A! B", NameProblem.PUNCTUATION),
            Triple("Bob && Alice", "Bob & Alice", NameProblem.PUNCTUATION),
            // composed emoji: the joiner and skin tones are dropped
            Triple("👍🏽", "👍", NameProblem.EMOJI),
            Triple("👨\u200D💻", "👨💻", NameProblem.EMOJI),
            // everything else: brackets and quotes (Ps, Pe, Pi, Pf), other numbers (No), invisible formats (Cf)
            Triple("Bob (work)", "Bob work", NameProblem.OTHER),
            Triple("Hi :)", "Hi :", NameProblem.OTHER),
            Triple("«Bob»", "Bob", NameProblem.START),
            Triple("Bob «x»", "Bob x", NameProblem.OTHER),
            Triple("x²", "x", NameProblem.OTHER),
            Triple("A\u200BB", "AB", NameProblem.OTHER),
            Triple("ab\u200Ccd", "abcd", NameProblem.OTHER),
            Triple("^_^", "", NameProblem.START),
            Triple("ab\uD83D", "ab", NameProblem.OTHER),
        )
        for ((name, kept, problem) in cases) {
            assertEquals(name, kept, valid(name))
            assertEquals(name, problem, InputRules.nameProblem(name))
        }
    }

    @Test
    fun spacesAndLength() {
        // isSpace (tab, newline, no-break space, other Zs) becomes ' ', runs become one, none at the ends.
        assertEquals("Bob", valid("  Bob  "))
        assertEquals("Bob Smith", valid("Bob  Smith"))
        assertEquals("Bob Smith", valid("Bob\tSmith"))
        assertEquals("Bob Smith", valid("Bob\u00A0Smith"))
        assertEquals("Bob Smith", valid("Bob\u3000Smith"))
        assertEquals("a".repeat(50), valid("a".repeat(60)))
        assertEquals("Bob Smith", InputRules.cleanName(" \u00A0Bob \t\n Smith\u2003 "))
        assertEquals("", InputRules.cleanName("   "))
        // After cleaning, a name differs from the core's only in what it really refuses.
        assertNull(InputRules.nameProblem(InputRules.cleanName("  Bob \t Smith ")))
        assertEquals(NameProblem.OTHER, InputRules.nameProblem(""))
    }

    @Test
    fun cleaningSpacesNeverChangesWhatTheCoreKeeps() {
        val alphabet = listOf(" ", "\t", "\u00A0", "\u2003", "a", "B", "7", "-", "!", "_", "(", ".", "😀", "\u0301", "\u200D", "+", "\$")
        val random = Random(8)
        repeat(5000) {
            val s = buildString { repeat(random.nextInt(12)) { append(alphabet[random.nextInt(alphabet.size)]) } }
            assertEquals(s, valid(s), valid(InputRules.normalizeNameSpaces(s)))
        }
    }

    @Test
    fun eachProblemHasItsOwnFixedSentence() {
        assertNull(ErrorMapper.nameRefusal("Alice"))
        assertEquals(ErrorMapper.NAME_LENGTH, ErrorMapper.nameRefusal("")?.message)
        assertEquals(ErrorMapper.NAME_LENGTH, ErrorMapper.nameRefusal("a".repeat(51))?.message)
        assertNull(ErrorMapper.nameRefusal("😀".repeat(50)))
        assertEquals("Names can't contain these characters: . , ; / \\ # @ ' \" ` ~", ErrorMapper.nameRefusal("a.b")?.message)
        assertEquals(ErrorMapper.NAME_START, ErrorMapper.nameRefusal("-Bob")?.message)
        assertEquals(ErrorMapper.NAME_PUNCTUATION, ErrorMapper.nameRefusal("Wow!!")?.message)
        assertEquals(ErrorMapper.NAME_EMOJI, ErrorMapper.nameRefusal("Bob 👍🏽")?.message)
        assertEquals(ErrorMapper.NAME_OTHER, ErrorMapper.nameRefusal("Bob (work)")?.message)
        val canary = "CANARY-NAME"
        for (bad in listOf("$canary..", "-$canary", "$canary!!", "$canary 👍🏽", "$canary (x)", canary.repeat(5))) {
            val e = ErrorMapper.nameRefusal(bad)!!
            assertEquals(SecuredError.Kind.UNKNOWN, e.kind)
            assertFalse(e.toString().contains("CANARY"))
        }
    }

    @Test
    fun theEngineRefusesBeforeSending() {
        val h = EngineHarness(tmp.newFolder("nobackup")).also { harness = it }
        h.startRunning()
        val profilesAtStart = h.core.sent("/_profile ").size   // the start merges our preferences into the profile
        val renamed = Answer<SecuredProfile>()
        h.engine.updateProfile("Bob (work)", renamed)
        val group = Answer<Long>()
        h.engine.createGroup("Team!!", false, group)
        h.awaitIdle()
        assertEquals(ErrorMapper.NAME_OTHER, renamed.error().message)
        assertEquals(ErrorMapper.NAME_PUNCTUATION, group.error().message)
        assertEquals(profilesAtStart, h.core.sent("/_profile ").size)
        assertTrue(h.core.sent("/_group ").isEmpty())

        // Spaces are cleaned the way the core would, and the cleaned name is what goes out.
        val ok = Answer<SecuredProfile>()
        h.engine.updateProfile("  Bob \t Smith ", ok)
        h.awaitIdle()
        assertEquals("Bob Smith", ok.value().displayName)
        assertTrue(h.core.sent("/_profile 1 ").last().cmd.contains("\"displayName\":\"Bob Smith\""))
        assertEquals(profilesAtStart + 1, h.core.sent("/_profile ").size)
    }
}
