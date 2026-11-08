package app.encrogram.core.backup

/**
 * What to do for one contact after a restore (docs/recovery-phrase-plan.md limits 3 and 4; research/v2-plan-review.md
 * §5.3). Pure logic, unit-tested; the engine runs the commands.
 *
 * - The restored ratchet is as old as the backup. SimpleX never re-syncs by itself: the chat layer only reports
 *   `contactRatchetSync` (Library/Subscriber.hs:674-700). A message from the contact that still decrypts proves only
 *   the contact-to-us direction (review finding 1), so right after a restore the engine sends ONE
 *   `/_sync @id force=on` per contact whose stats say `ok` and `ratchetSyncSupported` ([Action.FORCE_SYNC]): the agent
 *   accepts a forced sync in any state (simplexmq Agent.hs:2472 `ratchetSyncAllowed cData || force`), sends a new
 *   ratchet key and moves to `started`. Plain `/_sync` (no force) stays the rule everywhere else: when the core reports
 *   `allowed` or `required` (Agent/Store.hs:473-475 `ratchetSyncAllowed`).
 * - Proof that the ratchet works both ways ([BackupStore.Pending.proven]) is only
 *   1. a completed sync: `ok` after `started`/`agreed` ([observe]). In the agent, `started` is left only through
 *      `agreed` (Agent.hs:3660-3687 `newRatchetKey`), and `agreed` becomes `ok` only when a message from the contact
 *      decrypts under the new ratchet (Agent.hs:3218 and 3238-3247 `resetRatchetSync`). `allowed`/`required` in between
 *      cancel the proof; or
 *   2. a delivery receipt on one of our messages sent after the restore ([delivered]).
 * - `/_switch @id` moves our receive queue to a new address the old phone doesn't know. Its messages travel over the
 *   ratchet (Commands.hs:1925-1931), so it is sent only once the ratchet is proven, the connection is ready and no
 *   switch is running. Sent once per contact; the contact stays listed until the switch is no longer running.
 */
internal object RestoreRepair {

    enum class Action { SYNC, FORCE_SYNC, SWITCH, WAIT, DONE, DROP }

    /** The parts of the agent's ConnectionStats this needs (Agent/Protocol.hs:709-716). */
    data class ConnStats(val ratchetSyncState: String, val ratchetSyncSupported: Boolean, val rcvSwitching: Boolean)

    /** Mirrors `ratchetSyncAllowed` (simplexmq Agent/Store.hs:473-475; upstream ConnectionStats.ratchetSyncAllowed). */
    fun syncAllowed(s: ConnStats): Boolean = s.ratchetSyncSupported && s.ratchetSyncState in SYNC_STATES

    /** [pending] must already include what [stats] shows ([observe]). */
    fun decide(stats: ConnStats?, contactReady: Boolean, pending: BackupStore.Pending): Action {
        // No active connection: nothing to sync or switch.
        if (stats == null) return Action.DROP
        if (syncAllowed(stats)) return Action.SYNC
        return when (stats.ratchetSyncState) {
            "started", "agreed" -> Action.WAIT
            // Sync needed but the connection can't do it: only a new invite helps.
            in SYNC_STATES -> Action.DROP
            "ok" -> when {
                pending.switched -> if (stats.rcvSwitching) Action.WAIT else Action.DONE
                pending.proven -> if (contactReady && !stats.rcvSwitching) Action.SWITCH else Action.WAIT
                // Once per contact, and only from `ok`: a sync already under way is never restarted.
                !pending.forced && !pending.syncing && stats.ratchetSyncSupported -> Action.FORCE_SYNC
                // Waiting for the sync to finish, or (old agent) for a delivery receipt.
                else -> Action.WAIT
            }
            else -> Action.WAIT
        }
    }

    /** A sync state seen for a restored contact (`contactRatchetSync` event or `/_info` stats), folded into its entry. */
    fun observe(p: BackupStore.Pending, state: String): BackupStore.Pending = when (state) {
        "started", "agreed" -> if (p.syncing) p else p.copy(syncing = true)
        "ok" -> if (p.syncing) p.copy(proven = true, syncing = false) else p
        "allowed", "required" -> if (p.syncing) p.copy(syncing = false) else p
        else -> p
    }

    /** The core accepted our `/_sync` (its reply says `started`). */
    fun syncAccepted(p: BackupStore.Pending, forced: Boolean): BackupStore.Pending =
        p.copy(syncing = true, forced = p.forced || forced)

    /** One of our messages ([itemId]) was delivered: proof only when it was sent after the restore. */
    fun delivered(p: BackupStore.Pending, itemId: Long, sinceItemId: Long): BackupStore.Pending =
        if (itemId > sinceItemId && !p.proven) p.copy(proven = true) else p

    /** A message from the contact decrypted: proves only their direction, so it changes nothing (finding 1). */
    fun received(p: BackupStore.Pending): BackupStore.Pending = p

    /**
     * A `contactRatchetSync` event: sync now? Always for `required` (sending is blocked until someone syncs:
     * upstream `ratchetSyncSendProhibited`), and for `allowed` when the contact is still being repaired after a
     * restore. Never forced.
     */
    fun syncOnEvent(status: String, restoredContact: Boolean): Boolean =
        status == "required" || (restoredContact && status == "allowed")

    /**
     * The end of a restore, in the order that keeps a finished restore safe (finding 8): the repair list, then the
     * words and keys, and only then the restore marker is cleared. The first step that fails stops the rest, so the
     * marker stays and the next start wipes the unfinished restore; a restore whose marker is gone is complete.
     * Returns the failed step ("repair_list", "keys", "marker"), or null when all three succeeded.
     */
    fun commitRestore(saveRepairList: () -> Boolean, storeKeys: () -> Boolean, clearMarker: () -> Boolean): String? = when {
        !saveRepairList() -> "repair_list"
        !storeKeys() -> "keys"
        !clearMarker() -> "marker"
        else -> null
    }

    private val SYNC_STATES = setOf("allowed", "required")
}
