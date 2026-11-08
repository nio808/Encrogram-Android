package app.encrogram.core.backup

import app.encrogram.core.internal.CoreGroup
import app.encrogram.core.internal.CoreMember

/**
 * What to do for one connection after a restore (docs/recovery-phrase-plan.md limits 3 and 4; research/v2-plan-review.md
 * §5.3): a contact, or a group member's connection ([RepairKey]); groups get exactly the same treatment, with the
 * `#<groupId> <groupMemberId>` forms of `/_info`, `/_sync` and `/_switch`. Pure logic, unit-tested; the engine runs the
 * commands.
 *
 * - The restored ratchet is as old as the backup. SimpleX never re-syncs by itself: the chat layer only reports
 *   `contactRatchetSync` / `groupMemberRatchetSync` (Library/Subscriber.hs:674-700). A message from the other side that
 *   still decrypts proves only their direction (review finding 1), so right after a restore the engine sends ONE
 *   `/_sync ... force=on` per connection that supports it ([Action.FORCE_SYNC]): the agent accepts a forced sync in any
 *   state (simplexmq Agent.hs:2472 `ratchetSyncAllowed cData || force`), sends a new ratchet key and moves to
 *   `started`. That includes a backup that itself holds `started` or `agreed` (R6.1): the first upload after a restore
 *   can run before an offline contact answered our forced sync, and from such a copy plain `/_sync` is refused and
 *   decrypt errors raise no event, so only another forced sync unblocks it. Plain `/_sync` (no force) stays the rule
 *   everywhere else: when the core reports `allowed` or `required` (Agent/Store.hs:473-475 `ratchetSyncAllowed`).
 * - Proof that the ratchet works both ways ([BackupStore.Pending.proven]) is only
 *   1. a completed sync that we started: `ok` after `started`/`agreed` ([observe]). In the agent, `started` is left only
 *      through `agreed` (Agent.hs:3660-3687 `newRatchetKey`), and `agreed` becomes `ok` only when a message from the
 *      other side decrypts under the new ratchet (Agent.hs:3218 and 3238-3247 `resetRatchetSync`). `allowed`/`required`
 *      in between cancel the proof; or
 *   2. a delivery receipt on one of our messages sent after the restore ([delivered]); in a group, a receipt from every
 *      member (the item's `sndProgress` is `complete`).
 * - `/_switch` moves our receive queue to a new address the old phone doesn't know. Its messages travel over the
 *   ratchet (Commands.hs:1925-1931), so it is sent only once the ratchet is proven, the connection is ready and no
 *   switch is running. Sent once per connection; it stays listed until the switch is no longer running.
 */
internal object RestoreRepair {

    enum class Action { SYNC, FORCE_SYNC, SWITCH, WAIT, DONE, DROP }

    /** Still `started`/`agreed` this long after our forced sync: force once more (the other side lost it). */
    const val REFORCE_AFTER_MS = 6L * 60 * 60 * 1000

    /** The parts of the agent's ConnectionStats this needs (Agent/Protocol.hs:709-716). */
    data class ConnStats(val ratchetSyncState: String, val ratchetSyncSupported: Boolean, val rcvSwitching: Boolean)

    /** Mirrors `ratchetSyncAllowed` (simplexmq Agent/Store.hs:473-475; upstream ConnectionStats.ratchetSyncAllowed). */
    fun syncAllowed(s: ConnStats): Boolean = s.ratchetSyncSupported && s.ratchetSyncState in SYNC_STATES

    /** [pending] must already include what [stats] shows ([observe]). [nowMs] decides a second force ([REFORCE_AFTER_MS]). */
    fun decide(stats: ConnStats?, contactReady: Boolean, pending: BackupStore.Pending, nowMs: Long = 0L): Action {
        // No active connection: nothing to sync or switch.
        if (stats == null) return Action.DROP
        if (syncAllowed(stats)) return Action.SYNC
        return when (stats.ratchetSyncState) {
            "started", "agreed" -> when {
                // Not forced by us yet: the state came with the backup and may never finish (R6.1).
                !pending.forced && stats.ratchetSyncSupported -> Action.FORCE_SYNC
                // Forced by us, but stuck for hours: once more, then only waiting.
                pending.forced && !pending.reforced && stats.ratchetSyncSupported && pending.forcedAtMs > 0 &&
                    nowMs - pending.forcedAtMs >= REFORCE_AFTER_MS -> Action.FORCE_SYNC
                else -> Action.WAIT
            }
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

    /**
     * A sync state seen for a restored connection (`contactRatchetSync` / `groupMemberRatchetSync` event or `/_info`
     * stats), folded into its entry. `started`/`agreed` counts as a sync under way only after we forced one: before
     * that it may be the backup's stale state (R6.1).
     */
    fun observe(p: BackupStore.Pending, state: String): BackupStore.Pending = when (state) {
        "started", "agreed" -> if (p.syncing || !p.forced) p else p.copy(syncing = true)
        "ok" -> if (p.syncing) p.copy(proven = true, syncing = false) else p
        "allowed", "required" -> if (p.syncing) p.copy(syncing = false) else p
        else -> p
    }

    /**
     * The core accepted a `/_sync` for a restored connection (its reply says `started`), forced or plain: either way the
     * ratchet is being re-synced, so it counts as the one forced sync (R6.1: no connection is forced twice, except the
     * one late retry, which this marks as used).
     */
    fun syncAccepted(p: BackupStore.Pending, nowMs: Long): BackupStore.Pending =
        p.copy(syncing = true, forced = true, reforced = p.reforced || p.forced, forcedAtMs = nowMs)

    /** One of our messages ([itemId]) was delivered: proof only when it was sent after the restore. */
    fun delivered(p: BackupStore.Pending, itemId: Long, sinceItemId: Long): BackupStore.Pending =
        if (itemId > sinceItemId && !p.proven) p.copy(proven = true) else p

    /** A message from the other side decrypted: proves only their direction, so it changes nothing (finding 1). */
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

    /**
     * The groups to repair, as one placeholder each ([RepairKey.group]), written with the repair list at commit time.
     * Their members can't be listed then: the restored chat isn't started yet and the core refuses `/_members`
     * (`chatNotStarted`, 8 Nov device test). Groups we were only invited to, or have left or been removed from, have
     * no member connections of ours to repair.
     */
    fun groupPlaceholders(groups: List<CoreGroup>): List<RepairKey> =
        groups.filter { !it.invited && !it.gone }.map { RepairKey.group(it.groupId) }

    /** One group's member connections to repair, from `/_members`: every other member whose connection works. */
    fun memberKeys(groupId: Long, members: List<CoreMember>): List<RepairKey> =
        members.filter { !it.isMe && it.active }.map { RepairKey.member(groupId, it.groupMemberId) }

    /**
     * [map] with [placeholder] replaced by its group's [members] (fresh entries; a member already listed keeps its
     * state), order kept. The caller writes the result in one atomic save, so the placeholder goes only together with
     * its member keys.
     */
    fun expand(map: Map<RepairKey, BackupStore.Pending>, placeholder: RepairKey, members: List<RepairKey>): LinkedHashMap<RepairKey, BackupStore.Pending> {
        val out = LinkedHashMap<RepairKey, BackupStore.Pending>()
        for ((k, v) in map) if (k != placeholder) out[k] = v
        for (m in members) if (m.isMember && m.groupId == placeholder.groupId) out.putIfAbsent(m, BackupStore.Pending())
        return out
    }

    private val SYNC_STATES = setOf("allowed", "required")
}

/**
 * A connection on the restore repair list ([BackupStore.pending]): a contact, or one member of a group. Member keys are
 * apart from contact ids (the core's groupMemberId and contactId are separate counters). Stored as `<contactId>` or
 * `#<groupId>:<groupMemberId>`, so lists written by earlier builds (contact ids only) still read.
 *
 * A third form, `#<groupId>:*` ([group]), stands for all of one group's member connections until the engine can list
 * them ([RestoreRepair.groupPlaceholders], [RestoreRepair.expand]). It is never a connection itself: no `/_info`,
 * `/_sync` or `/_switch` is ever sent for it.
 */
internal class RepairKey private constructor(val contactId: Long, val groupId: Long, val memberId: Long) {

    val isMember: Boolean get() = groupId > 0 && memberId > 0

    /** A whole group, still to be expanded into its members. */
    val isGroupPlaceholder: Boolean get() = groupId > 0 && memberId == 0L

    fun encode(): String = when {
        isMember -> "#$groupId:$memberId"
        isGroupPlaceholder -> "#$groupId:$GROUP_WILDCARD"
        else -> contactId.toString()
    }

    override fun equals(other: Any?): Boolean =
        other is RepairKey && other.contactId == contactId && other.groupId == groupId && other.memberId == memberId

    override fun hashCode(): Int = (contactId * 31 + groupId).hashCode() * 31 + memberId.hashCode()

    override fun toString(): String = when {
        isMember -> "member"
        isGroupPlaceholder -> "group"
        else -> "contact"
    }

    companion object {
        private const val GROUP_WILDCARD = "*"

        fun contact(contactId: Long): RepairKey = RepairKey(contactId, 0, 0)

        fun member(groupId: Long, groupMemberId: Long): RepairKey = RepairKey(0, groupId, groupMemberId)

        /** Every member connection of group [groupId], until [RestoreRepair.expand] lists them. */
        fun group(groupId: Long): RepairKey = RepairKey(0, groupId, 0)

        /** [encode]'s form back; null for anything else (a damaged line is skipped). */
        fun decode(s: String): RepairKey? {
            if (s.startsWith("#")) {
                val colon = s.indexOf(':')
                if (colon < 0) return null
                val g = s.substring(1, colon).toLongOrNull()?.takeIf { it > 0 } ?: return null
                val rest = s.substring(colon + 1)
                if (rest == GROUP_WILDCARD) return group(g)
                val m = rest.toLongOrNull()?.takeIf { it > 0 } ?: return null
                return member(g, m)
            }
            return s.toLongOrNull()?.takeIf { it > 0 }?.let { contact(it) }
        }
    }
}
