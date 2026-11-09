package app.encrogram.core.internal

import app.encrogram.core.api.MemberRole
import app.encrogram.core.backup.RepairKey
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Round 2, task 2: our own photo through `/_profile` and the group's through `/_group_profile`. Both replace the whole
 * profile (Commands.hs:3928 updateProfile_, :4019 runUpdateGroupProfile), so every other field must ride along as it is;
 * losing a group's preferences (history, fullDelete, reactions, our channel marker) would change the group for everyone.
 */
class ProfilePhotoTest {

    @Before
    fun quiet() {
        SecuredLog.enabled = false
    }

    private val photo = AvatarsTest.jpgUrl()

    // ---- our profile -----------------------------------------------------------------------------------------------

    @Test
    fun settingOurPhotoKeepsEveryOtherField() {
        val local = JSONObject(Samples.LOCAL_PROFILE)
        val p = CoreJson.profileUpdate(local, image = CoreJson.ImageChange(photo))!!
        assertEquals(photo, p.getString("image"))
        assertEquals("Alice", p.getString("displayName"))
        assertEquals("Alice A", p.getString("fullName"))
        assertEquals("hi", p.getString("shortDescr"))
        assertEquals("https://encrogram.nytmanager.com/a#xyz", p.getString("contactLink"))
        assertEquals("human", p.getString("peerType"))
        assertEquals(true, p.getJSONObject("futureField").getBoolean("keep"))
        val prefs = p.getJSONObject("preferences")
        assertEquals(3600, prefs.getJSONObject("timedMessages").getInt("ttl"))
        assertEquals("yes", prefs.getJSONObject("reactions").getString("allow"))
        assertEquals("yes", prefs.getJSONObject("fullDelete").getString("allow"))
        assertFalse(p.has("profileId"))
        assertFalse(p.has("localAlias"))
        // The input is a read of the core's state: never changed.
        assertEquals("data:image/jpg;base64,QUJD", local.getString("image"))
    }

    @Test
    fun removingOurPhotoAndNoChange() {
        val merged = CoreJson.profileUpdate(JSONObject(Samples.LOCAL_PROFILE))!!
        val removed = CoreJson.profileUpdate(merged, image = CoreJson.ImageChange(null))!!
        assertFalse(removed.has("image"))
        assertEquals("Alice A", removed.getString("fullName"))
        // Nothing to send: the same photo again, or no photo to remove.
        assertNull(CoreJson.profileUpdate(merged, image = CoreJson.ImageChange("data:image/jpg;base64,QUJD")))
        assertNull(CoreJson.profileUpdate(removed, image = CoreJson.ImageChange(null)))
        // A rename leaves the photo alone.
        assertEquals("data:image/jpg;base64,QUJD", CoreJson.profileUpdate(merged, displayName = "Al")!!.getString("image"))
        assertEquals("ImageChange(set)", CoreJson.ImageChange(photo).toString())
    }

    @Test
    fun aPausedContactPausesProfileChanges() {
        // b1: the profile goes to every contact at once, so any marked contact pauses it; marked group members don't.
        val contact = ForeignRelays.mark(RepairKey.contact(5))!!
        val member = ForeignRelays.mark(RepairKey.member(3, 7))!!
        assertNull(ForeignRelays.profileRefusal(emptySet()))
        assertNull(ForeignRelays.profileRefusal(setOf(member)))
        assertEquals(ErrorMapper.FOREIGN_RELAY_PROFILE, ForeignRelays.profileRefusal(setOf(member, contact)))
    }

    // ---- the group's photo -----------------------------------------------------------------------------------------

    /** GroupInfo.groupProfile as `/_get chat #3 count=1` returns it (Types.hs:865-875), with fields we never touch. */
    private val groupProfile = """{"displayName":"Team","fullName":"The team","shortDescr":"short","description":"Welcome!",
        "image":"data:image/jpg;base64,QUJD",
        "groupPreferences":{"timedMessages":{"enable":"off"},"directMessages":{"enable":"on","role":"admin"},
          "fullDelete":{"enable":"on"},"reactions":{"enable":"on"},"voice":{"enable":"on"},"files":{"enable":"on"},
          "history":{"enable":"on"},"comments":{"enable":"on"},"futurePref":{"enable":"on","x":1}},
        "memberAdmission":{"review":"all"},"futureField":[1,2]}"""

    @Test
    fun theGroupsPhotoKeepsItsPreferencesAndEverythingElse() {
        val current = JSONObject(groupProfile)
        val update = CoreJson.groupProfileUpdate(current, photo)!!
        assertEquals(photo, update.getString("image"))
        // Everything but the image, exactly as it was.
        val before = JSONObject(groupProfile).apply { remove("image") }
        val after = JSONObject(update.toString()).apply { remove("image") }
        assertTrue(before.similar(after))
        assertEquals("on", update.getJSONObject("groupPreferences").getJSONObject("history").getString("enable"))
        assertEquals("on", update.getJSONObject("groupPreferences").getJSONObject("comments").getString("enable"))
        assertEquals("admin", update.getJSONObject("groupPreferences").getJSONObject("directMessages").getString("role"))
        assertTrue(CoreJson.isChannel(JSONObject().put("groupProfile", update)))
        // The read itself is left alone.
        assertEquals("data:image/jpg;base64,QUJD", current.getString("image"))

        // Through the command and back, as the core will read it.
        val cmd = Commands.updateGroupProfile(3, update)
        val sent = JSONObject(cmd.removePrefix("/_group_profile #3 "))
        assertTrue(update.similar(sent))
    }

    @Test
    fun removingTheGroupsPhotoAndNoChange() {
        val removed = CoreJson.groupProfileUpdate(JSONObject(groupProfile), null)!!
        assertFalse(removed.has("image"))
        assertTrue(JSONObject(groupProfile).getJSONObject("groupPreferences").similar(removed.getJSONObject("groupPreferences")))
        assertNull(CoreJson.groupProfileUpdate(removed, null))
        assertNull(CoreJson.groupProfileUpdate(JSONObject(groupProfile), "data:image/jpg;base64,QUJD"))
        // A profile without fullName (required by the core) gets an empty one.
        val bare = CoreJson.groupProfileUpdate(JSONObject("""{"displayName":"G"}"""), photo)!!
        assertEquals("", bare.getString("fullName"))
    }

    @Test
    fun onlyTheOwnerOfAConnectedGroupSetsItsPhoto() {
        fun g(role: MemberRole, status: String) = CoreGroup(3, "Team", role, status, 3)
        assertNull(GroupRules.photoRefusal(g(MemberRole.OWNER, "creator")))
        assertNull(GroupRules.photoRefusal(g(MemberRole.OWNER, "connected")))
        // The core asserts GROwner (Commands.hs:4020), and members drop x.grp.info from anyone else (Subscriber.hs:3733).
        assertEquals(ErrorMapper.ONLY_OWNERS_PHOTO, GroupRules.photoRefusal(g(MemberRole.ADMIN, "connected")))
        assertEquals(ErrorMapper.ONLY_OWNERS_PHOTO, GroupRules.photoRefusal(g(MemberRole.MEMBER, "complete")))
        assertEquals(ErrorMapper.NOT_JOINED, GroupRules.photoRefusal(g(MemberRole.OWNER, "invited")))
        assertEquals(ErrorMapper.NOT_A_MEMBER, GroupRules.photoRefusal(g(MemberRole.OWNER, "left")))
        assertEquals(ErrorMapper.NOT_A_MEMBER, GroupRules.photoRefusal(g(MemberRole.OWNER, "deleted")))
        assertNotNull(GroupRules.photoRefusal(g(MemberRole.OWNER, "accepted")))
    }
}
