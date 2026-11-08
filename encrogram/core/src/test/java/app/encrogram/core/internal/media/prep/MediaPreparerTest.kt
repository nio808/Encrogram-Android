package app.encrogram.core.internal.media.prep

import app.encrogram.core.api.MediaInput
import app.encrogram.core.api.MediaKind
import app.encrogram.core.internal.SecuredLog
import app.encrogram.core.internal.media.DataUrl
import app.encrogram.core.internal.media.FakeNativeFiles
import app.encrogram.core.internal.media.MediaPolicy
import app.encrogram.core.internal.media.MediaPolicyConfig
import app.encrogram.core.internal.media.MediaVault
import app.encrogram.core.internal.media.Prepared
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MediaPreparerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var media: File
    private lateinit var fake: FakeNativeFiles
    private lateinit var vault: MediaVault
    private val opener = FakeOpener()
    private val codec = FakeCodec()
    private val video = FakeVideoTool()
    private val logs = ArrayList<String>()

    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)
    private fun cat(vararg p: ByteArray) = p.fold(ByteArray(0)) { a, b -> a + b }
    private fun be32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun box(type: String, vararg payload: ByteArray): ByteArray {
        val p = cat(*payload)
        return cat(be32(8L + p.size), ascii(type), p)
    }

    private val time = byteArrayOf(0x11, 0x22, 0x33, 0x44)
    private val mdatPayload = ByteArray(64) { (it * 3 + 1).toByte() }

    /** A small valid MP4 with a GPS canary in udta. */
    private fun mp4(brand: String = "isom"): ByteArray {
        val ftyp = box("ftyp", ascii(brand), be32(0), ascii("isom"))
        val gps = box("udta", box("©xyz", ascii("CANARY-GPS-51.5074")))
        val mvhd = box("mvhd", byteArrayOf(0, 0, 0, 0), time, time, ByteArray(20) { 9 })
        val moov = box("moov", mvhd, gps)
        return cat(ftyp, moov, box("mdat", mdatPayload))
    }

    private val exifJpeg: ByteArray = run {
        val base = DataUrl.decode(DataUrl.PLACEHOLDER)!!
        val payload = ascii("Exif\u0000\u0000CANARY-GPS-51.5074")
        val len = payload.size + 2
        cat(base.copyOfRange(0, 2), byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()), payload, base.copyOfRange(2, base.size))
    }

    private fun gif(): ByteArray {
        fun ints(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
        return cat(
            ascii("GIF89a"), ints(1, 0, 1, 0, 0x80, 0, 0), ints(0, 0, 0, 255, 255, 255),
            ints(0x21, 0xFE, 21), ascii("CANARY-GPS-51.5074 xx"), ints(0),
            ints(0x21, 0xF9, 4, 0, 10, 0, 0, 0), ints(0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0), ints(2, 2, 0x4C, 0x01, 0), ints(0x3B),
        )
    }

    @Before
    fun setUp() {
        SecuredLog.enabled = false
        SecuredLog.testSink = { logs.add(it) }
        media = File(tmp.root, "media")
        fake = FakeNativeFiles()
        vault = MediaVault(File(tmp.root, "files"), media, fake)
    }

    @After
    fun tearDown() {
        SecuredLog.testSink = null
    }

    private fun preparer(config: MediaPolicyConfig = MediaPolicy.DEFAULT) = MediaPreparer(opener, codec, video, vault, config)

    private fun prepare(
        src: FakeSource,
        asFile: Boolean = false,
        config: MediaPolicyConfig = MediaPolicy.DEFAULT,
    ): Prepared {
        opener.source = src
        return preparer(config).prepare(MediaInput.Picked("content://x/1", asFile))
    }

    private fun refused(src: FakeSource, reason: PrepException.Reason, asFile: Boolean = false, config: MediaPolicyConfig = MediaPolicy.DEFAULT) {
        try {
            prepare(src, asFile, config)
            fail("expected $reason")
        } catch (e: PrepException) {
            assertEquals(reason, e.reason)
        }
        assertTrue(src.closed)
        assertDirs(0)
    }

    private fun plainOf(p: Prepared): ByteArray = FakeNativeFiles.open(p.sealed.file.readBytes(), p.sealed.key)!!

    private fun list(name: String): List<String> = File(media, name).list()?.toList() ?: emptyList()

    /** view/ is empty and prep/ holds exactly [sealed] sealed outputs; link/ is empty. */
    private fun assertDirs(sealed: Int) {
        assertEquals(emptyList<String>(), list("view"))
        assertEquals(emptyList<String>(), list("link"))
        assertEquals(sealed, list("prep").size)
    }

    private fun has(b: ByteArray, s: String) = String(b, Charsets.ISO_8859_1).contains(s)

    // ---- photo ---------------------------------------------------------------------------------------------------

    @Test
    fun photoIsReencodedCleanAndNeutrallyNamed() {
        val src = FakeSource(exifJpeg, mimeType = "image/jpeg")
        val p = prepare(src)
        assertEquals(MediaKind.IMAGE, p.kind)
        assertFalse(p.unchanged)
        val plain = plainOf(p)
        assertArrayEquals(codec.outputs.first(), plain)       // the codec's output...
        assertFalse(plain.contentEquals(exifJpeg))            // ...not the source
        assertFalse(has(plain, "CANARY"))
        assertFalse(has(p.sealed.file.readBytes(), "CANARY"))
        assertTrue(Regex("IMG_[a-z2-7]{8}\\.jpg").matches(p.name))
        assertEquals("image/jpeg", p.mimeType)
        assertEquals(plain.size.toLong(), p.sealed.plainSize)
        assertNotNull(p.previewDataUrl)
        assertTrue(p.previewDataUrl!!.length <= 8_000)
        assertTrue(p.previewDataUrl!!.startsWith(DataUrl.JPEG_PREFIX))
        assertTrue(codec.allReleasedOnce)
        assertTrue(src.closed)
        assertDirs(1)
    }

    @Test
    fun previewShrinksToBudget() {
        val p = prepare(FakeSource(exifJpeg))
        // The fake's 320 px / q70 preview is 7168 bytes (> budget): the preparer must have lowered quality.
        assertTrue(p.previewDataUrl!!.length in 1..8_000)
        assertTrue(DataUrl.decode(p.previewDataUrl)!!.size < 7168)
    }

    @Test
    fun nothingFitsGivesThePlaceholder() {
        val p = prepare(FakeSource(exifJpeg), config = MediaPolicyConfig(previewChars = 50))
        assertEquals(DataUrl.PLACEHOLDER, p.previewDataUrl)
        assertTrue(codec.allReleasedOnce)
    }

    @Test
    fun smallPictureWhereScaleReturnsTheSameOneIsReleasedOnce() {
        codec.decodeWidth = 100
        codec.decodeHeight = 80
        val p = prepare(FakeSource(exifJpeg))
        assertNotNull(p.previewDataUrl)
        assertTrue(codec.allReleasedOnce)
        assertEquals(1, codec.pictures.size) // scale never made a copy
    }

    @Test
    fun codecWithMetadataInItsOutputIsRefused() {
        codec.dirtyOutput = true
        refused(FakeSource(exifJpeg), PrepException.Reason.UNSUPPORTED)
        assertTrue(codec.allReleasedOnce)
    }

    @Test
    fun undecodablePictureIsUnsupported() {
        codec.failDecode = IllegalStateException("bad")
        refused(FakeSource(exifJpeg), PrepException.Reason.UNSUPPORTED)
    }

    @Test
    fun sealFailureOfAPhotoIsStorageAndLeavesNothing() {
        fake.failWrite = FakeNativeFiles.Fail.NATIVE
        refused(FakeSource(exifJpeg), PrepException.Reason.STORAGE)
        assertTrue(codec.allReleasedOnce)
    }

    @Test
    fun anImageTypeWeCannotProcessIsRefusedNotSentAsADocument() {
        refused(FakeSource(ascii("BM") + ByteArray(50), displayName = "x.bmp", mimeType = "image/bmp"), PrepException.Reason.UNSUPPORTED)
    }

    // ---- GIF -----------------------------------------------------------------------------------------------------

    @Test
    fun gifIsSanitized() {
        val p = prepare(FakeSource(gif(), displayName = "cat.gif", mimeType = "image/gif"))
        assertEquals(MediaKind.GIF, p.kind)
        val plain = plainOf(p)
        assertFalse(has(plain, "CANARY"))
        assertTrue(has(plain, "GIF89a"))
        assertTrue(Regex("GIF_[a-z2-7]{8}\\.gif").matches(p.name))
        assertEquals("image/gif", p.mimeType)
        assertTrue(p.previewDataUrl!!.length <= 8_000)
        assertFalse(p.unchanged)
        assertTrue(codec.allReleasedOnce)
        assertDirs(1)
    }

    @Test
    fun gifWithoutAFrameGetsThePlaceholder() {
        codec.firstFrame = false
        assertEquals(DataUrl.PLACEHOLDER, prepare(FakeSource(gif())).previewDataUrl)
        assertDirs(1)
    }

    @Test
    fun malformedGifIsUnsupported() {
        refused(FakeSource(gif().copyOf(20)), PrepException.Reason.UNSUPPORTED)
    }

    // ---- video ---------------------------------------------------------------------------------------------------

    @Test
    fun videoIsNeutralizedSealedAndTheViewFileIsGone() {
        val src = FakeSource(mp4(), displayName = "clip.mp4", mimeType = "video/mp4")
        val p = prepare(src)
        assertEquals(MediaKind.VIDEO, p.kind)
        assertTrue(video.probedExisted && video.framedExisted)
        assertEquals(7, p.durationSeconds)
        assertTrue(Regex("video_[a-z2-7]{8}\\.mp4").matches(p.name))
        assertEquals("video/mp4", p.mimeType)
        val plain = plainOf(p)
        assertFalse(has(plain, "CANARY"))                       // udta zeroed in place
        assertEquals(mp4().size, plain.size)                    // sizes unchanged
        assertTrue(has(plain, "ftyp") && has(plain, "mdat"))
        assertNotNull(p.previewDataUrl)
        assertTrue(video.pictures.all { it.releases == 1 })
        assertEquals(1, fake.encrypts)
        assertEquals(0, fake.writes)
        assertDirs(1)
    }

    @Test
    fun movKeepsItsExtension() {
        val p = prepare(FakeSource(mp4("qt  ")))
        assertTrue(p.name.endsWith(".mov"))
        assertEquals("video/quicktime", p.mimeType)
    }

    @Test
    fun noFrameMeansPlaceholderAndAThrowingFrameToo() {
        video.frameOk = false
        assertEquals(DataUrl.PLACEHOLDER, prepare(FakeSource(mp4())).previewDataUrl)
        assertDirs(1)
        video.frameOk = true
        video.frameThrows = true
        assertEquals(DataUrl.PLACEHOLDER, prepare(FakeSource(mp4())).previewDataUrl)
        assertDirs(2)
    }

    @Test
    fun failingNeutralizeIsUnsupportedAndLeavesNothing() {
        // ftyp then a box that claims more bytes than the file has.
        val broken = cat(box("ftyp", ascii("isom"), be32(0), ascii("isom")), be32(5000), ascii("moov"), ByteArray(10))
        refused(FakeSource(broken, mimeType = "video/mp4"), PrepException.Reason.UNSUPPORTED)
        assertEquals(0, fake.encrypts)
    }

    @Test
    fun unreadableVideoProbeIsUnsupported() {
        video.info = null
        refused(FakeSource(mp4()), PrepException.Reason.UNSUPPORTED)
    }

    @Test
    fun failingSealOfAVideoIsStorageAndLeavesNothing() {
        fake.failEncrypt = FakeNativeFiles.Fail.NATIVE
        refused(FakeSource(mp4()), PrepException.Reason.STORAGE)
        assertTrue(video.pictures.all { it.releases == 1 })
    }

    @Test
    fun aVideoThatIsNotMp4OrMovIsUnsupported() {
        val webm = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) + ByteArray(60)
        refused(FakeSource(webm, displayName = "a.webm", mimeType = "video/webm"), PrepException.Reason.UNSUPPORTED)
        assertEquals(0, fake.encrypts)
    }

    // ---- documents -----------------------------------------------------------------------------------------------

    @Test
    fun smallDocumentIsSentByteForByte() {
        val doc = ascii("%PDF-1.7 ") + ByteArray(500) { it.toByte() }
        val p = prepare(FakeSource(doc, displayName = "re/port‮.pdf", mimeType = "application/pdf"))
        assertEquals(MediaKind.FILE, p.kind)
        assertTrue(p.unchanged)
        assertEquals("report.pdf", p.name)
        assertEquals("application/pdf", p.mimeType)
        assertNull(p.previewDataUrl)
        assertNull(p.durationSeconds)
        assertArrayEquals(doc, plainOf(p))
        assertEquals(1, fake.writes)
        assertEquals(0, fake.encrypts)
        assertDirs(1)
    }

    @Test
    fun asFileSendsAPhotoUnchangedWithItsMetadata() {
        val p = prepare(FakeSource(exifJpeg, displayName = "IMG_1.jpg"), asFile = true)
        assertEquals(MediaKind.FILE, p.kind)
        assertTrue(p.unchanged)
        assertArrayEquals(exifJpeg, plainOf(p))
        assertEquals(0, codec.pictures.size)
        assertDirs(1)
    }

    @Test
    fun largeDocumentIsStagedThenDeleted() {
        val cfg = MediaPolicyConfig(memorySealLimit = 1000)
        val doc = ByteArray(5000) { (it * 11).toByte() }
        val src = FakeSource(doc, displayName = "big.bin")
        val p = prepare(src, config = cfg)
        assertTrue(p.unchanged)
        assertEquals(1, src.streamsOpened)
        assertEquals(1, fake.encrypts)
        assertEquals(0, fake.writes)
        assertArrayEquals(doc, plainOf(p))
        assertEquals("application/octet-stream", p.mimeType)
        assertDirs(1)
    }

    @Test
    fun documentExactlyAtTheMemoryLimitStaysInMemory() {
        val cfg = MediaPolicyConfig(memorySealLimit = 1000)
        val doc = ByteArray(1000) { 5 }
        val p = prepare(FakeSource(doc, displayName = "a.bin", declared = null), config = cfg)
        assertEquals(1, fake.writes)
        assertArrayEquals(doc, plainOf(p))
        val p2 = prepare(FakeSource(ByteArray(1001) { 6 }, displayName = "b.bin", declared = null), config = cfg)
        assertEquals(1, fake.encrypts)
        assertEquals(1001, plainOf(p2).size)
        assertDirs(2)
    }

    @Test
    fun failingSealOfALargeDocumentLeavesNothing() {
        fake.failEncrypt = FakeNativeFiles.Fail.RUNTIME
        refused(FakeSource(ByteArray(5000), displayName = "big.bin"), PrepException.Reason.STORAGE, config = MediaPolicyConfig(memorySealLimit = 1000))
    }

    // ---- refusals ------------------------------------------------------------------------------------------------

    @Test
    fun declaredSizeOverTheCapIsTooLarge() {
        refused(FakeSource(ByteArray(10), declared = 200L * 1024 * 1024), PrepException.Reason.TOO_LARGE)
    }

    @Test
    fun aDeclaredSizeThatLiesStillHitsTheCap() {
        val cfg = MediaPolicyConfig(capBytes = 3000, memorySealLimit = 1000)
        // Source cap is above ours: our own counter must stop it.
        refused(FakeSource(ByteArray(8000), displayName = "x.bin", declared = 10, cap = Long.MAX_VALUE), PrepException.Reason.TOO_LARGE, config = cfg)
        // Source cap equal to the policy's (the real counting stream).
        refused(FakeSource(ByteArray(8000), displayName = "x.bin", declared = 10, cap = 3000), PrepException.Reason.TOO_LARGE, config = cfg)
        // Same for a video staged from a lying source.
        refused(FakeSource(mp4() + ByteArray(8000), declared = 10, cap = Long.MAX_VALUE), PrepException.Reason.TOO_LARGE, config = cfg)
        // And for a file small enough for memory by its claim.
        refused(FakeSource(ByteArray(8000), displayName = "y.bin", declared = null), PrepException.Reason.TOO_LARGE, config = cfg)
    }

    @Test
    fun lowSpaceIsStorage() {
        val cfg = MediaPolicyConfig(freeSpaceExtraBytes = Long.MAX_VALUE / 4)
        refused(FakeSource(exifJpeg), PrepException.Reason.STORAGE, config = cfg)
        refused(FakeSource(mp4()), PrepException.Reason.STORAGE, config = cfg)
        refused(FakeSource(ByteArray(100), displayName = "a.bin"), PrepException.Reason.STORAGE, config = cfg)
    }

    @Test
    fun unreadableInput() {
        opener.failure = PrepException(PrepException.Reason.UNREADABLE)
        try {
            preparer().prepare(MediaInput.Picked("content://x/1"))
            fail()
        } catch (e: PrepException) {
            assertEquals(PrepException.Reason.UNREADABLE, e.reason)
        }
        opener.failure = null
        refused(FakeSource(ByteArray(100), displayName = "a.bin", failRead = true), PrepException.Reason.UNREADABLE)
        refused(FakeSource(ByteArray(0), displayName = "empty.txt"), PrepException.Reason.UNREADABLE)
    }

    @Test
    fun logsCarryOnlyKindsAndReasons() {
        prepare(FakeSource(ByteArray(100), displayName = "CANARY_NAME.pdf", mimeType = "CANARY/mime"))
        try {
            prepare(FakeSource(ByteArray(10), declared = 200L * 1024 * 1024, displayName = "CANARY_NAME.pdf"))
        } catch (e: PrepException) {
        }
        assertTrue(logs.any { it.contains("MEDIA_PREPARED") && it.contains("FILE") })
        assertTrue(logs.any { it.contains("MEDIA_PREPARE_FAILED") && it.contains("TOO_LARGE") })
        assertTrue(logs.none { it.contains("CANARY") || it.contains("content://") })
    }
}
