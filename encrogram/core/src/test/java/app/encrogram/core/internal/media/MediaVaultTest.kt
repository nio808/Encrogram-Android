package app.encrogram.core.internal.media

import app.encrogram.core.api.MediaFailure
import app.encrogram.core.api.MediaKind
import app.encrogram.core.internal.SecuredLog
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

class MediaVaultTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var files: File
    private lateinit var media: File
    private lateinit var outside: File
    private lateinit var keep: File
    private lateinit var fake: FakeNativeFiles
    private lateinit var vault: MediaVault

    private val canary = "CANARY_PLAINTEXT_7f3a9c".toByteArray()
    private fun plain(extra: Int = 200) = canary + ByteArray(extra) { (it * 7).toByte() } + canary

    @Before
    fun setUp() {
        SecuredLog.enabled = false
        root = tmp.newFolder("root")
        val data = File(root, "encrogram-secured").apply { mkdirs() }
        files = File(data, "files")
        media = File(data, "media")
        outside = File(root, "outside").apply { mkdirs() }
        keep = File(outside, "keep.txt").apply { writeText("outside, untouched") }
        fake = FakeNativeFiles()
        vault = MediaVault(files, media, fake)
    }

    @After
    fun tearDown() {
        SecuredLog.testSink = null
    }

    private fun dir(name: String) = File(media, name)
    private fun entries(d: File): List<String> = d.list()?.toList() ?: emptyList()

    private fun sealed(bytes: ByteArray = plain()): SealedFile = vault.sealBytes(bytes, "IMG_hint.jpg")

    private fun committed(name: String, bytes: ByteArray = plain()): Pair<String, FileKey> {
        val s = sealed(bytes)
        return vault.commit(s, name) to s.key
    }

    // ---- sealing -------------------------------------------------------------------------------------------------

    @Test
    fun sealBytesCommitAndReadBack() {
        val bytes = plain()
        val s = vault.sealBytes(bytes, "IMG_hint.jpg")
        assertEquals("prep", s.file.parentFile!!.name)
        assertEquals(bytes.size.toLong(), s.plainSize)
        assertFalse("sealed file holds plaintext", contains(s.file.readBytes(), canary))

        val name = vault.commit(s, "IMG_abcdefgh.jpg")
        assertEquals("IMG_abcdefgh.jpg", name)
        assertTrue(entries(dir("prep")).isEmpty())
        assertTrue(File(files, name).isFile)
        assertTrue(vault.present(name))

        assertArrayEquals(bytes, vault.readBytes(name, s.key, 10_000))
        assertNoPlaintextOnDisk()
    }

    @Test
    fun sealBytesZeroesTheDirectBufferOnSuccessAndOnFailure() {
        val ok = vault.sealBytes(plain())
        assertAllZero(fake.lastBuffer!!)
        assertEquals(listOf(ok.file.name), entries(dir("prep")))

        fake.failWrite = FakeNativeFiles.Fail.RUNTIME
        thrown<IllegalStateException> { vault.sealBytes(plain()) }
        assertAllZero(fake.lastBuffer!!)
        assertEquals("a failed seal leaves nothing in prep/", listOf(ok.file.name), entries(dir("prep")))

        fake.failWrite = FakeNativeFiles.Fail.NATIVE
        thrown<NativeFileException> { vault.sealBytes(plain()) }
        assertAllZero(fake.lastBuffer!!)
        assertEquals(listOf(ok.file.name), entries(dir("prep")))
    }

    @Test
    fun sealFileDeletesThePlainFileWhateverHappens() {
        val bytes = plain()
        val staged = vault.newViewFile().apply { writeBytes(bytes) }
        assertEquals("view", staged.parentFile!!.name)
        val s = vault.sealFile(staged)
        assertFalse("plaintext staging file deleted after sealing", staged.exists())
        assertEquals("prep", s.file.parentFile!!.name)
        assertEquals(bytes.size.toLong(), s.plainSize)
        val name = vault.commit(s, "video_abcdefgh.mp4")
        assertArrayEquals(bytes, vault.readBytes(name, s.key, 10_000))

        for (mode in listOf(FakeNativeFiles.Fail.NATIVE, FakeNativeFiles.Fail.RUNTIME, FakeNativeFiles.Fail.ERROR)) {
            fake.failEncrypt = mode
            val again = vault.newViewFile().apply { writeBytes(bytes) }
            thrown<Throwable> { vault.sealFile(again) }
            assertFalse("plaintext staging file deleted after a failed seal ($mode)", again.exists())
            assertTrue(entries(dir("prep")).isEmpty())
        }
        assertTrue(entries(dir("view")).isEmpty())
    }

    @Test
    fun sealFileRefusesAFileOutsideTheMediaFolderAndLeavesItAlone() {
        thrown<IllegalArgumentException> { vault.sealFile(keep) }
        assertTrue(keep.isFile)
        assertEquals(0, fake.encrypts)
        // A link inside view/ pointing outside is refused too, and neither it nor its target is touched.
        val view = File(media, "view").apply { mkdirs() }
        val link = File(view, "sneaky")
        Files.createSymbolicLink(link.toPath(), keep.toPath())
        thrown<IllegalArgumentException> { vault.sealFile(link) }
        assertTrue(keep.isFile)
        assertEquals(0, fake.encrypts)
    }

    // ---- commit and the files folder -----------------------------------------------------------------------------

    @Test
    fun commitAddsASuffixAndNeverOverwrites() {
        val a = plain(10)
        val b = plain(20)
        val (n1, k1) = committed("IMG_aaaaaaaa.jpg", a)
        val (n2, k2) = committed("IMG_aaaaaaaa.jpg", b)
        assertEquals("IMG_aaaaaaaa.jpg", n1)
        assertEquals("IMG_aaaaaaaa_1.jpg", n2)
        assertArrayEquals(a, vault.readBytes(n1, k1, 10_000))
        assertArrayEquals(b, vault.readBytes(n2, k2, 10_000))

        // A file the core already has under that name stays as it is.
        files.mkdirs()
        val existing = File(files, "report.pdf").apply { writeText("the core's own file") }
        val (n3, _) = committed("report.pdf")
        assertEquals("report_1.pdf", n3)
        assertEquals("the core's own file", existing.readText())
    }

    @Test
    fun commitRefusesNonBareNamesAndUnstagedFiles() {
        val s = sealed()
        for (bad in listOf("../x.jpg", "a/b.jpg", "/abs.jpg", "", ".", "..", "a\u0000b", "a\\b")) {
            thrown<IllegalArgumentException> { vault.commit(s, bad) }
        }
        assertTrue("the sealed file is still staged", s.file.isFile)
        thrown<IllegalArgumentException> { vault.commit(SealedFile(keep, FakeNativeFiles.newKey(), 1), "x.jpg") }
        assertTrue(keep.isFile)
        assertNull(files.list()?.firstOrNull())
    }

    @Test
    fun uncommitDeletesOnlyInsideTheFilesFolder() {
        val (name, _) = committed("IMG_bbbbbbbb.jpg")
        assertTrue(vault.uncommit(name))
        assertFalse(File(files, name).exists())
        assertFalse(vault.uncommit(name))

        assertFalse(vault.uncommit("../../outside/keep.txt"))
        assertFalse(vault.uncommit(keep.absolutePath))
        val link = File(files, "link.jpg")
        Files.createSymbolicLink(link.toPath(), keep.toPath())
        assertFalse(vault.uncommit("link.jpg"))
        assertTrue(keep.isFile)
    }

    @Test
    fun resolveConfinesPathsToTheFilesFolder() {
        val (name, key) = committed("IMG_cccccccc.jpg")
        assertEquals(File(files, name).absoluteFile, vault.resolve(name)!!.absoluteFile)

        val refused = listOf(
            null, "", ".", "..", "../outside/keep.txt", "../../outside/keep.txt", "sub/x.jpg", "a\\b",
            "/etc/passwd", keep.absolutePath, File(files, name).absolutePath, "x\u0000y",
        )
        for (p in refused) assertNull("refused: ${p?.length}", vault.resolve(p))

        // A symbolic link out of the files folder: never resolved, never present, never read.
        Files.createSymbolicLink(File(files, "evil.jpg").toPath(), keep.toPath())
        assertNull(vault.resolve("evil.jpg"))
        assertFalse(vault.present("evil.jpg"))
        val before = fake.reads
        assertEquals(MediaFailure.NOT_ON_DEVICE, thrown<VaultException> { vault.readBytes("evil.jpg", key, 10_000) }.failure)
        assertEquals(MediaFailure.NOT_ON_DEVICE, thrown<VaultException> { vault.decryptToView("evil.jpg", key) }.failure)
        assertEquals(before, fake.reads)
        assertEquals(0, fake.decrypts)

        // Even a link that points inside is refused (strict: the core never makes links).
        Files.createSymbolicLink(File(files, "alias.jpg").toPath(), File(files, name).toPath())
        assertNull(vault.resolve("alias.jpg"))

        // A linked directory doesn't help either: names with a separator are refused before any lookup.
        Files.createSymbolicLink(File(files, "sub").toPath(), outside.toPath())
        assertNull(vault.resolve("sub/keep.txt"))
        assertTrue(keep.isFile)
    }

    // ---- reading -------------------------------------------------------------------------------------------------

    @Test
    fun readBytesOverTheCapIsRefusedBeforeReading() {
        val bytes = ByteArray(100) { it.toByte() }
        val (name, key) = committed("voice_dddddddd.m4a", bytes)
        assertEquals(MediaFailure.TOO_LARGE, thrown<VaultException> { vault.readBytes(name, key, 99) }.failure)
        assertEquals(0, fake.reads)
        assertArrayEquals(bytes, vault.readBytes(name, key, 100))
    }

    @Test
    fun aMissingFileIsNotOnDevice() {
        val key = FakeNativeFiles.newKey()
        assertEquals(MediaFailure.NOT_ON_DEVICE, thrown<VaultException> { vault.readBytes("IMG_gone.jpg", key, 10) }.failure)
        assertEquals(MediaFailure.NOT_ON_DEVICE, thrown<VaultException> { vault.readBytes(null, key, 10) }.failure)
        assertEquals(MediaFailure.NOT_ON_DEVICE, thrown<VaultException> { vault.decryptToView("IMG_gone.jpg", key) }.failure)
        assertFalse(vault.present("IMG_gone.jpg"))
        assertEquals(0, fake.reads)
        assertEquals(0, fake.decrypts)

        // The core's empty placeholder for an accepted download is not "present".
        files.mkdirs()
        File(files, "IMG_placeholder.jpg").writeBytes(ByteArray(0))
        assertFalse(vault.present("IMG_placeholder.jpg"))
        assertNotNull(vault.resolve("IMG_placeholder.jpg"))
    }

    @Test
    fun aNonBmpNameGoesThroughAnAsciiLinkThatIsRemoved() {
        val bytes = plain()
        val name = "report 📄 final.pdf"
        val (stored, key) = committed(name, bytes)
        assertEquals("a non-ASCII name reaches the files folder as it is", name, stored)
        assertTrue(File(files, name).isFile)

        assertArrayEquals(bytes, vault.readBytes(stored, key, 10_000))
        assertEquals("link", File(fake.paths.last()).parentFile!!.name)
        assertTrue("JNI only ever saw ASCII paths", fake.nonAsciiPaths.isEmpty())
        assertTrue("the link is removed", entries(dir("link")).isEmpty())
        assertTrue("the original stays", File(files, name).isFile)

        val view = vault.decryptToView(stored, key)
        assertArrayEquals(bytes, view.readBytes())
        assertTrue(fake.nonAsciiPaths.isEmpty())
        assertTrue(entries(dir("link")).isEmpty())
        FileMediaReader(view).close()

        // A failing read still removes its link.
        fake.failRead = FakeNativeFiles.Fail.NATIVE
        thrown<NativeFileException> { vault.readBytes(stored, key, 10_000) }
        assertTrue(entries(dir("link")).isEmpty())
        assertTrue(File(files, name).isFile)
    }

    @Test
    fun whenHardLinksFailACiphertextCopyIsUsedAndRemoved() {
        val noLinks = MediaVault(files, media, fake) { _, _ -> throw IOException("links refused") }
        val bytes = plain()
        val s = noLinks.sealBytes(bytes)
        val name = noLinks.commit(s, "фото 😀.jpg")
        assertArrayEquals(bytes, noLinks.readBytes(name, s.key, 10_000))
        assertEquals("link", File(fake.paths.last()).parentFile!!.name)
        assertTrue(fake.nonAsciiPaths.isEmpty())
        assertTrue(entries(dir("link")).isEmpty())
        assertTrue(File(files, name).isFile)
    }

    @Test
    fun decryptToViewWritesOnlyIntoViewAndTheReaderDeletesIt() {
        val bytes = plain()
        val (name, key) = committed("video_eeeeeeee.mp4", bytes)
        val out = vault.decryptToView(name, key)
        assertEquals("view", out.parentFile!!.name)
        assertFalse("no extension on a view file", out.name.contains('.'))
        assertArrayEquals(bytes, out.readBytes())
        val reader = FileMediaReader(out)
        assertEquals(bytes.size.toLong(), reader.size())
        reader.close()
        assertFalse(out.exists())
        assertTrue(entries(dir("view")).isEmpty())
    }

    @Test
    fun decryptToViewDeletesItsOutputOnEveryFailure() {
        val (name, key) = committed("video_ffffffff.mp4")

        // The fake leaves partial plaintext behind and doesn't clean up: the vault must.
        fake.failDecrypt = FakeNativeFiles.Fail.NATIVE
        thrown<NativeFileException> { vault.decryptToView(name, key) }
        assertTrue("error result: output deleted", entries(dir("view")).isEmpty())

        fake.failDecrypt = FakeNativeFiles.Fail.RUNTIME
        thrown<IllegalStateException> { vault.decryptToView(name, key) }
        assertTrue("exception: output deleted", entries(dir("view")).isEmpty())

        fake.failDecrypt = FakeNativeFiles.Fail.ERROR
        thrown<OutOfMemoryError> { vault.decryptToView(name, key) }
        assertTrue("error: output deleted", entries(dir("view")).isEmpty())

        // A wrong key: the fake writes the unauthenticated plaintext, then reports the bad tag.
        fake.failDecrypt = FakeNativeFiles.Fail.NONE
        thrown<NativeFileException> { vault.decryptToView(name, FakeNativeFiles.newKey()) }
        assertTrue("bad tag: output deleted", entries(dir("view")).isEmpty())
        assertEquals(4, fake.decrypts)
        assertNoPlaintextOnDisk()
    }

    // ---- wipe ----------------------------------------------------------------------------------------------------

    @Test
    fun wipeTransientClosesReadersAndEmptiesAllThreeFolders() {
        val (name, key) = committed("video_gggggggg.mp4")
        sealed() // left in prep/
        val view = vault.decryptToView(name, key)
        val reader = FileMediaReader(view)
        val playback = vault.playbacks.open(1L, 2L, MediaKind.VIDEO, "video/mp4", 3, reader)
        val loose = vault.newViewFile().apply { writeBytes(plain()) }
        File(dir("view"), "nested/deeper").mkdirs()
        File(dir("view"), "nested/deeper/x").writeBytes(plain())
        File(dir("link").apply { mkdirs() }, "stale").writeBytes(byteArrayOf(1))
        // A link to a folder outside: removed, never followed.
        Files.createSymbolicLink(File(dir("view"), "out").toPath(), outside.toPath())

        val n = vault.wipeTransient()

        assertTrue(n >= 6)
        for (d in listOf("prep", "view", "link")) assertTrue("$d emptied", entries(dir(d)).isEmpty())
        assertTrue(playback.isClosed)
        assertTrue(reader.isClosed)
        assertEquals(-1, reader.readAt(0, ByteArray(4), 0, 4))
        assertEquals(0, vault.playbacks.count())
        assertFalse(view.exists())
        assertFalse(loose.exists())
        assertTrue("outside the media folder nothing is touched", keep.isFile)
        assertEquals("outside, untouched", keep.readText())
        assertTrue("committed files stay", File(files, name).isFile)
        assertEquals(0, vault.wipeTransient())
    }

    @Test
    fun wipeReplacesATransientFolderThatIsALink() {
        media.mkdirs()
        Files.createSymbolicLink(dir("view").toPath(), outside.toPath())
        vault.wipeTransient()
        assertFalse(Files.isSymbolicLink(dir("view").toPath()))
        assertTrue(keep.isFile)
        val f = vault.newViewFile()
        assertTrue(Files.isDirectory(f.parentFile!!.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertEquals(dir("view").absoluteFile, f.parentFile!!.absoluteFile)
    }

    // ---- space ---------------------------------------------------------------------------------------------------

    @Test
    fun freeSpace() {
        assertTrue(vault.freeBytes() > 0)
        assertTrue(vault.hasRoomFor(1))
        assertFalse(vault.hasRoomFor(Long.MAX_VALUE / 8))
        assertFalse(vault.hasRoomFor(-1))
    }

    @Test
    fun overlappingFoldersAreRefused() {
        thrown<IllegalArgumentException> { MediaVault(files, File(files, "media"), fake) }
        thrown<IllegalArgumentException> { MediaVault(File(media, "files"), media, fake) }
        thrown<IllegalArgumentException> { MediaVault(files, files, fake) }
    }

    // ---- the whole picture ---------------------------------------------------------------------------------------

    @Test
    fun nothingIsWrittenOutsideTheTwoRootsAndNothingIsLogged() {
        val lines = ArrayList<String>()
        SecuredLog.testSink = { synchronized(lines) { lines.add(it) } }
        val before = snapshotOutsideRoots()
        val bytes = plain()

        val a = vault.sealBytes(bytes, "CANARY_NAME.jpg")
        val nameA = vault.commit(a, "CANARY_NAME.jpg")
        val staged = vault.newViewFile().apply { writeBytes(bytes) }
        val b = vault.sealFile(staged)
        val nameB = vault.commit(b, "CANARY 📄.pdf")
        vault.readBytes(nameA, a.key, 10_000)
        vault.readBytes(nameB, b.key, 10_000)
        MediaVault(files, media, fake) { _, _ -> throw IOException("no") }.readBytes(nameB, b.key, 10_000)
        val view = vault.decryptToView(nameB, b.key)
        vault.playbacks.open(1, 1, MediaKind.FILE, null, null, FileMediaReader(view))
        fake.failDecrypt = FakeNativeFiles.Fail.RUNTIME
        thrown<IllegalStateException> { vault.decryptToView(nameA, a.key) }
        fake.failDecrypt = FakeNativeFiles.Fail.NONE
        thrown<VaultException> { vault.readBytes("../outside/keep.txt", a.key, 10) }
        vault.wipeTransient()
        vault.uncommit(nameA)

        assertEquals("nothing outside files/ and media/ changed", before, snapshotOutsideRoots())
        assertTrue(fake.nonAsciiPaths.isEmpty())
        val roots = listOf(files.canonicalPath, media.canonicalPath)
        for (p in fake.paths) {
            val c = File(p).canonicalPath
            assertTrue("native call outside the roots", roots.any { c.startsWith(it + File.separator) })
        }
        for (d in listOf("prep", "view", "link")) assertTrue(entries(dir(d)).isEmpty())
        assertNoPlaintextOnDisk()
        for (line in lines) {
            assertFalse(line, line.contains("CANARY"))
            assertFalse(line, line.contains(files.name + File.separator))
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    /** Every path under root that is not inside files/ or media/, with a digest of its content (links by target). */
    private fun snapshotOutsideRoots(): Map<String, String> {
        val out = sortedMapOf<String, String>()
        val r = root.toPath()
        val f = files.toPath().toAbsolutePath()
        val m = media.toPath().toAbsolutePath()
        Files.walk(r).use { s ->
            s.forEach { p ->
                val abs = p.toAbsolutePath()
                if (abs.startsWith(f) || abs.startsWith(m)) return@forEach
                val v = when {
                    Files.isSymbolicLink(abs) -> "link:" + Files.readSymbolicLink(abs)
                    Files.isDirectory(abs, LinkOption.NOFOLLOW_LINKS) -> "dir"
                    else -> "file:" + digest(Files.readAllBytes(abs))
                }
                out[r.relativize(abs).toString()] = v
            }
        }
        return out
    }

    private fun digest(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun assertNoPlaintextOnDisk() {
        Files.walk(root.toPath()).use { s ->
            s.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.forEach { p ->
                assertFalse("plaintext left on disk", contains(Files.readAllBytes(p), canary))
            }
        }
    }

    private fun assertAllZero(b: java.nio.ByteBuffer) {
        for (i in 0 until b.capacity()) assertEquals("byte $i", 0.toByte(), b.get(i))
    }

    private fun contains(hay: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || hay.size < needle.size) return false
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    private inline fun <reified T : Throwable> thrown(block: () -> Unit): T {
        try {
            block()
        } catch (t: Throwable) {
            if (t is T) return t
            throw AssertionError("expected ${T::class.java.simpleName}, got ${t.javaClass.simpleName}", t)
        }
        throw AssertionError("expected ${T::class.java.simpleName}, nothing thrown")
    }
}
