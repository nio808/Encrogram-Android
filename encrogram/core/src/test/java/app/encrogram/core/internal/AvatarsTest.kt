package app.encrogram.core.internal

import app.encrogram.core.api.AVATAR_MAX_BYTES
import app.encrogram.core.api.SecuredError
import app.encrogram.core.internal.media.DataUrl
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

/**
 * Round 2, task 1: profile photos in (contacts, groups, members, senders, our own) and the checks on our own photos
 * out. JSON shaped after chat core 7.0.0.12 (Types.hs Profile/LocalProfile/GroupProfile `image`, ImageData :890-904).
 */
class AvatarsTest {

    @Before
    fun quiet() {
        SecuredLog.enabled = false
    }

    companion object {
        /** A real 16x16 JPEG (our own placeholder). */
        val JPEG: ByteArray = Base64.getDecoder().decode(DataUrl.PLACEHOLDER.removePrefix(DataUrl.JPEG_PREFIX))

        /** A real 16x16 PNG. */
        val PNG: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAIAAACQkWg2AAAAM0lEQVR4XpXIoREAAAyEMEb/0esxXCPDnnAUHAVHwVFwFBwFR8FRcBQcBUfBUXAUHAVHOSWEgBAvLvKsAAAAAElFTkSuQmCC",
        )

        /** [JPEG] with one extra segment ([marker], e.g. 0xFE comment, 0xE1 Exif, 0xE2 ICC) right after SOI, [total] bytes in all. */
        fun jpegWithSegment(marker: Int, total: Int): ByteArray {
            val body = total - JPEG.size - 4
            require(body in 0..65533)
            val seg = ByteArray(4 + body)
            seg[0] = 0xFF.toByte()
            seg[1] = marker.toByte()
            seg[2] = ((body + 2) shr 8).toByte()
            seg[3] = ((body + 2) and 0xFF).toByte()
            for (i in 4 until seg.size) seg[i] = 'x'.code.toByte()
            return JPEG.copyOfRange(0, 2) + seg + JPEG.copyOfRange(2, JPEG.size)
        }

        fun jpgUrl(bytes: ByteArray = JPEG) = "data:image/jpg;base64," + Base64.getEncoder().encodeToString(bytes)
    }

    // ---- in --------------------------------------------------------------------------------------------------------

    @Test
    fun acceptsOnlyRealJpegAndPngDataUrls() {
        assertArrayEquals(JPEG, Avatars.of(jpgUrl())!!.bytes())
        assertArrayEquals(JPEG, Avatars.of("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(JPEG))!!.bytes())
        assertArrayEquals(PNG, Avatars.of("data:image/png;base64," + Base64.getEncoder().encodeToString(PNG))!!.bytes())

        val b64 = Base64.getEncoder().encodeToString(JPEG)
        for (bad in listOf(
            null, "", "data:image/jpg;base64,",
            "data:image/svg+xml;base64,$b64", "data:text/html;base64,$b64", "data:image/gif;base64,$b64", "data:image/webp;base64,$b64",
            "https://example.org/a.jpg", b64,
            "data:image/jpg;base64,@@@@", "data:image/jpg;base64,$b64 ", "data:image/jpg;base64,QUJD",   // not base64, or "ABC"
            "DATA:IMAGE/JPG;BASE64,$b64",
        )) {
            assertNull(bad?.take(40), Avatars.of(bad))
        }
    }

    @Test
    fun atMostTheCoresTwelveAndAHalfThousandCharacters() {
        // 22 + 4 * ceil(n / 3) characters: 9300 bytes fit, 9400 don't.
        val fits = jpgUrl(jpegWithSegment(0xFE, 9_300))
        val over = jpgUrl(jpegWithSegment(0xFE, 9_400))
        assertTrue(fits.length <= Avatars.MAX_DATA_URL_CHARS)
        assertTrue(over.length > Avatars.MAX_DATA_URL_CHARS)
        assertNotNull(Avatars.of(fits))
        assertNull(Avatars.of(over))
    }

    @Test
    fun equalPhotosShareOneInstance() {
        val url = jpgUrl()
        val a = Avatars.of(url)
        val b = Avatars.of(StringBuilder(url).toString())
        assertSame(a, b)
        assertEquals("MediaPreview(${JPEG.size} bytes)", a.toString())
    }

    private fun withImage(json: String, path: List<String>, url: String): String {
        val o = JSONObject(json)
        var at = o
        for (k in path) at = at.getJSONObject(k)
        at.put("image", url)
        return o.toString()
    }

    @Test
    fun contactsUsersGroupsMembersAndSendersCarryTheirPhoto() {
        val url = jpgUrl()
        val photo = Avatars.of(url)!!

        val contact = CoreJson.parseContact(JSONObject(withImage(Samples.contact(5, "Bob", "ready"), listOf("profile"), url)))!!
        assertSame(photo, contact.avatar)
        assertSame(photo, MessageMapper.summaryOf(CoreChat(contact, null, 0), archived = false).avatar)
        assertNull(CoreJson.parseContact(JSONObject(Samples.contact(6, "Cat", "ready")))!!.avatar)
        val svg = withImage(Samples.contact(7, "Eve", "ready"), listOf("profile"), "data:image/svg+xml;base64,PHN2Zz4=")
        assertNull(CoreJson.parseContact(JSONObject(svg))!!.avatar)

        val user = CoreJson.parseUser(JSONObject("""{"userId":1,"localDisplayName":"alice","profile":{"profileId":1,"displayName":"Alice","fullName":"","image":"$url","localAlias":""}}"""))!!
        assertEquals(CoreUser(1, "Alice", photo), user)

        val member = """{"groupMemberId":5,"groupId":3,"memberId":"bWVtYmVy","memberRole":"member","memberCategory":"invitee",
            "memberStatus":"connected","memberSettings":{"showMessages":true},"blockedByAdmin":false,"invitedBy":{"type":"user"},
            "localDisplayName":"bob","memberProfile":{"profileId":25,"displayName":"Bob","fullName":"","image":"$url","localAlias":""},
            "memberContactId":5,"memberContactProfileId":25,"createdAt":"2026-11-08T09:00:00Z","updatedAt":"2026-11-08T09:00:00Z"}"""
        assertSame(photo, CoreJson.parseMember(JSONObject(member), isMe = false)!!.avatar)

        val groupInfo = """{"groupId":3,"useRelays":false,"localDisplayName":"team",
            "groupProfile":{"displayName":"Team","fullName":"","image":"$url","groupPreferences":{"history":{"enable":"on"}}},
            "localAlias":"","fullGroupPreferences":{},"membership":${member.replace("\"groupMemberId\":5", "\"groupMemberId\":1")},
            "chatSettings":{"enableNtfs":"all","favorite":false},"createdAt":"2026-11-08T09:00:00Z","updatedAt":"2026-11-08T09:00:00Z",
            "groupSummary":{"currentMembers":2}}"""
        val group = CoreJson.parseGroup(JSONObject(groupInfo))!!
        assertSame(photo, group.avatar)
        assertSame(photo, MessageMapper.groupSummary(CoreGroupChat(group, null, 0), archived = false).avatar)

        val item = """{"chatDir":{"type":"groupRcv","groupMember":$member},
            "meta":{"itemId":70,"itemTs":"2026-11-08T10:00:00Z","itemText":"hi","itemStatus":{"type":"rcvNew"},
              "itemSharedMsgId":"c2hhcmVk","itemEdited":false,"userMention":false,"hasLink":false,"deletable":false,"editable":false,
              "showGroupAsSender":false,"createdAt":"2026-11-08T10:00:00Z","updatedAt":"2026-11-08T10:00:00Z"},
            "content":{"type":"rcvMsgContent","msgContent":{"type":"text","text":"hi"}},"mentions":{},"reactions":[]}"""
        val parsed = CoreJson.parseChatItem(CoreJson.groupChatId(3), JSONObject(item))!!
        assertSame(photo, parsed.sender!!.avatar)
        val message = MessageMapper.toMessage(parsed, null)
        assertSame(photo, message.sender!!.avatar)
        // Never in the strings that may reach a log.
        assertTrue(!message.toString().contains("base64") && !parsed.toString().contains(url.takeLast(20)))
    }

    // ---- out -------------------------------------------------------------------------------------------------------

    @Test
    fun ourOwnPhotoMustBeASmallCleanJpeg() {
        assertNull(Avatars.outgoingRefusal(JPEG))
        // Exactly the limit, with an ICC-like APP2 segment (not metadata): allowed, and its data URL fits the core.
        val max = jpegWithSegment(0xE2, AVATAR_MAX_BYTES)
        assertNull(Avatars.outgoingRefusal(max))
        val url = Avatars.dataUrl(max)
        assertTrue(url.startsWith("data:image/jpg;base64,"))
        assertTrue(url.length <= Avatars.MAX_DATA_URL_CHARS)
        assertNotNull("our own photo reads back as a photo", Avatars.of(url))

        assertEquals(SecuredError.Kind.TOO_LARGE, Avatars.outgoingRefusal(jpegWithSegment(0xE2, AVATAR_MAX_BYTES + 1))!!.kind)
        assertEquals(SecuredError.Kind.UNSUPPORTED, Avatars.outgoingRefusal(PNG)!!.kind)
        assertEquals(SecuredError.Kind.UNSUPPORTED, Avatars.outgoingRefusal(ByteArray(100))!!.kind)
        assertEquals(SecuredError.Kind.UNSUPPORTED, Avatars.outgoingRefusal(JPEG.copyOf(20))!!.kind)
        // Exif (location), a comment, or bytes after the end: refused.
        assertEquals(SecuredError.Kind.UNSUPPORTED, Avatars.outgoingRefusal(jpegWithSegment(0xE1, 2_000))!!.kind)
        assertEquals(SecuredError.Kind.UNSUPPORTED, Avatars.outgoingRefusal(jpegWithSegment(0xFE, 2_000))!!.kind)
        assertEquals(SecuredError.Kind.UNSUPPORTED, Avatars.outgoingRefusal(JPEG + byteArrayOf(1, 2, 3))!!.kind)
    }
}
