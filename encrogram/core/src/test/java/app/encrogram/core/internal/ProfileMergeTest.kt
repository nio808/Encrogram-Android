package app.encrogram.core.internal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** `/_profile` replaces the whole profile (gotcha G4): the merge must keep every field it doesn't own. */
class ProfileMergeTest {

    private fun local() = JSONObject(Samples.LOCAL_PROFILE)

    @Test
    fun mergeAddsOurPreferencesAndKeepsEverythingElse() {
        val p = CoreJson.profileUpdate(local())
        assertNotNull(p)
        p!!
        val prefs = p.getJSONObject("preferences")
        assertEquals("yes", prefs.getJSONObject("fullDelete").getString("allow"))
        assertEquals("no", prefs.getJSONObject("calls").getString("allow"))
        // Other preferences, untouched.
        assertEquals("yes", prefs.getJSONObject("timedMessages").getString("allow"))
        assertEquals(3600, prefs.getJSONObject("timedMessages").getInt("ttl"))
        assertEquals("yes", prefs.getJSONObject("reactions").getString("allow"))
        // Every other profile field, untouched, including ones this code has never heard of.
        assertEquals("Alice", p.getString("displayName"))
        assertEquals("Alice A", p.getString("fullName"))
        assertEquals("hi", p.getString("shortDescr"))
        assertEquals("data:image/jpg;base64,QUJD", p.getString("image"))
        assertEquals("https://encrogram.nytmanager.com/a#xyz", p.getString("contactLink"))
        assertEquals("human", p.getString("peerType"))
        assertEquals(true, p.getJSONObject("futureField").getBoolean("keep"))
        // LocalProfile-only fields are not part of Profile and are not sent back.
        assertFalse(p.has("profileId"))
        assertFalse(p.has("localAlias"))
    }

    @Test
    fun mergeIsANoOpOnceDone() {
        val merged = CoreJson.profileUpdate(local())!!
        assertNull(CoreJson.profileUpdate(merged))
    }

    @Test
    fun profileWithoutPreferences() {
        val bare = JSONObject("""{"profileId":2,"displayName":"Bo","fullName":"","preferences":null,"localAlias":""}""")
        val p = CoreJson.profileUpdate(bare)!!
        assertEquals(2, p.getJSONObject("preferences").length())
        assertEquals("Bo", p.getString("displayName"))
    }

    @Test
    fun renameKeepsFieldsAndAddsPreferences() {
        val p = CoreJson.profileUpdate(local(), displayName = "Alicia")!!
        assertEquals("Alicia", p.getString("displayName"))
        assertEquals("data:image/jpg;base64,QUJD", p.getString("image"))
        assertEquals("yes", p.getJSONObject("preferences").getJSONObject("fullDelete").getString("allow"))
        // Same name and preferences already merged: nothing to send.
        val merged = CoreJson.profileUpdate(local())!!
        assertNull(CoreJson.profileUpdate(merged, displayName = "Alice"))
        assertNotNull(CoreJson.profileUpdate(merged, displayName = "Alicia"))
    }

    @Test
    fun inputIsNotChanged() {
        val l = local()
        CoreJson.profileUpdate(l, displayName = "Other")
        assertEquals("Alice", l.getString("displayName"))
        assertEquals("yes", l.getJSONObject("preferences").getJSONObject("calls").getString("allow"))
        assertFalse(l.getJSONObject("preferences").has("fullDelete"))
    }
}
