package app.encrogram.core.internal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Command strings and reply shapes for the 12-word backup (chat core 7.0.0.12; sources cited in Commands.kt). */
class BackupCommandsTest {

    private fun ascii(s: String) = s.all { it.code in 0x20..0x7E }

    @Test
    fun exportAndImportTakeAnArchiveConfig() {
        val export = Commands.exportArchive("/data/x/tmp/backup-1.zip", "/data/x/tmp")
        assertTrue(export.startsWith("/_db export {"))
        val cfg = JSONObject(export.removePrefix("/_db export "))
        assertEquals("/data/x/tmp/backup-1.zip", cfg.getString("archivePath"))
        assertEquals("/data/x/tmp", cfg.getString("parentTempDirectory"))
        // No disableCompression: the core then deflates (Archive.hs:66).
        assertFalse(cfg.has("disableCompression"))

        val import = Commands.importArchive("/data/x/tmp/restore-1.zip", "/data/x/tmp")
        assertTrue(import.startsWith("/_db import {"))
        assertEquals("/data/x/tmp/restore-1.zip", JSONObject(import.removePrefix("/_db import ")).getString("archivePath"))
        assertTrue(ascii(export) && ascii(import))
    }

    @Test
    fun storageEncryptionCarriesBothKeys() {
        val cmd = Commands.storageEncryption("old+/key==", "new/+Key=")
        assertTrue(cmd.startsWith("/_db encryption {"))
        val cfg = JSONObject(cmd.removePrefix("/_db encryption "))
        assertEquals("old+/key==", cfg.getString("currentKey"))
        assertEquals("new/+Key=", cfg.getString("newKey"))
        assertFalse(cfg.has("keepKey"))
        for (bad in listOf("", "with space", "café")) {
            try {
                Commands.storageEncryption(bad, "ok")
                throw AssertionError("must refuse")
            } catch (e: IllegalArgumentException) {
                assertFalse(e.message.orEmpty().contains("with space"))
            }
        }
    }

    @Test
    fun contactCommands() {
        assertEquals("/_info @12", Commands.contactInfo(12))
        assertEquals("/_sync @12", Commands.syncRatchet(12))
        assertEquals("/_sync @12", Commands.syncRatchet(12, force = false))
        // Commands.hs:5573: "/_sync @" *> (APISyncContactRatchet <$> A.decimal <*> (" force=on" $> True <|> pure False))
        assertEquals("/_sync @12 force=on", Commands.syncRatchet(12, force = true))
        assertEquals("/_switch @12", Commands.switchContact(12))
    }

    @Test
    fun archiveReplies() {
        val exported = CoreJson.parseReply("""{"result":{"type":"archiveExported","archiveErrors":[]}}""") as CoreReply.Result
        assertEquals("archiveExported", exported.type)
        assertEquals(0, CoreJson.archiveErrorCount(exported.body))
        val imported = CoreJson.parseReply(
            """{"result":{"type":"archiveImported","archiveErrors":[{"type":"fileError","file":"secret-name.jpg","fileError":"x"},{"type":"import","importError":"y"}]}}""",
        ) as CoreReply.Result
        assertEquals(2, CoreJson.archiveErrorCount(imported.body))
        assertEquals(0, CoreJson.archiveErrorCount(JSONObject("{}")))
        // `/_db encryption` answers cmdOk; a running chat answers chatNotStopped.
        val notStopped = CoreJson.parseReply("""{"result":{"type":"chatCmdError","chatError":{"type":"error","errorType":{"type":"chatNotStopped"}}}}""")
        assertEquals("error.chatNotStopped", (notStopped as CoreReply.Error).error.tag)
    }

    private fun stats(state: String, switchStatus: String? = null, supported: Boolean = true) = """
        {"connAgentVersion":7,"rcvQueuesInfo":[{"rcvServer":"smp://abc@encrogram.nytmanager.com","status":"active",
        ${if (switchStatus != null) """"rcvSwitchStatus":"$switchStatus",""" else ""}"canAbortSwitch":false,"subStatus":{"type":"active"}}],
        "sndQueuesInfo":[{"sndServer":"smp://abc@encrogram.nytmanager.com","status":"active"}],
        "ratchetSyncState":"$state","ratchetSyncSupported":$supported}"""

    @Test
    fun contactInfoWithStats() {
        val body = (CoreJson.parseReply(
            """{"result":{"type":"contactInfo","user":{"userId":1},"contact":${Samples.contact(5, "Bob", "ready")},"connectionStats_":${stats("required")}}}""",
        ) as CoreReply.Result).body
        val (contact, s) = CoreJson.contactInfoStats(body)
        assertEquals(5L, contact!!.contactId)
        assertTrue(contact.ready)
        assertEquals("required", s!!.ratchetSyncState)
        assertTrue(s.ratchetSyncSupported)
        assertFalse(s.rcvSwitching)
    }

    @Test
    fun contactInfoWithoutConnection() {
        val body = JSONObject("""{"type":"contactInfo","contact":${Samples.contact(5, "Bob", null)}}""")
        val (contact, s) = CoreJson.contactInfoStats(body)
        assertEquals(5L, contact!!.contactId)
        assertNull(s)
    }

    @Test
    fun switchingQueuesAreSeen() {
        assertTrue(CoreJson.connectionStats(JSONObject(stats("ok", "sending_qadd")))!!.rcvSwitching)
        assertFalse(CoreJson.connectionStats(JSONObject(stats("ok")))!!.rcvSwitching)
        assertFalse(CoreJson.connectionStats(JSONObject(stats("ok", supported = false)))!!.ratchetSyncSupported)
        assertNull(CoreJson.connectionStats(JSONObject("{}")))
        assertNull(CoreJson.connectionStats(null))
    }

    @Test
    fun ratchetSyncEvent() {
        val body = (CoreJson.parseReply(
            """{"result":{"type":"contactRatchetSync","user":{"userId":1},"contact":${Samples.contact(8, "Eve", "ready")},
               "ratchetSyncProgress":{"ratchetSyncStatus":"allowed","connectionStats":${stats("allowed")}}}}""",
        ) as CoreReply.Result).body
        assertEquals(8L to "allowed", CoreJson.ratchetSyncEvent(body))
        assertNull(CoreJson.ratchetSyncEvent(JSONObject("""{"contact":${Samples.contact(8, "Eve", "ready")}}""")))
    }

    @Test
    fun switchEvent() {
        val body = (CoreJson.parseReply(
            """{"result":{"type":"contactSwitch","user":{"userId":1},"contact":${Samples.contact(9, "Dan", "ready")},
               "switchProgress":{"queueDirection":"rcv","switchPhase":"completed","connectionStats":${stats("ok")}}}}""",
        ) as CoreReply.Result).body
        assertEquals(9L to "rcv.completed", CoreJson.switchEvent(body))
    }

    @Test
    fun backupErrorTextsAreFixedSentences() {
        assertEquals(ErrorMapper.BACKUP_NETWORK, ErrorMapper.backupErrorText("network"))
        assertTrue(ErrorMapper.backupErrorText("server:503")!!.contains("503"))
        assertEquals(ErrorMapper.BACKUP_FAILED, ErrorMapper.backupErrorText("export"))
        assertNull(ErrorMapper.backupErrorText(null))
        assertEquals(ErrorMapper.OTHER_PHONE, ErrorMapper.backupErrorText("other_phone"))
        assertEquals(ErrorMapper.BACKUP_TOO_LARGE, ErrorMapper.backupErrorText("too_large"))
        assertTrue(ErrorMapper.OTHER_PHONE.startsWith("Another phone made a newer backup with these words."))
        assertTrue(ErrorMapper.SERVER_COPY_NOT_DELETED.startsWith("The copy on our server could not be deleted yet"))
        assertEquals(app.encrogram.core.api.SecuredError.Kind.NO_BACKUP, ErrorMapper.backupHttp("NOT_FOUND", 404).kind)
        assertEquals(app.encrogram.core.api.SecuredError.Kind.NETWORK, ErrorMapper.backupHttp("NETWORK", 0).kind)
        assertEquals(app.encrogram.core.api.SecuredError.Kind.WRONG_PHRASE, ErrorMapper.wrongPhrase().kind)
        assertEquals(app.encrogram.core.api.SecuredError.Kind.NEEDS_UNLOCK, ErrorMapper.needsUnlock().kind)
    }
}
