package app.encrogram.core.internal

import app.encrogram.core.api.MemberRole
import app.encrogram.core.api.MemberStatus
import app.encrogram.core.api.MessageStatus

/** Per earlier memberId of ours: the messages that membership sent, shared id -> text ([GroupRules.ownProofs]). */
internal typealias OwnProofs = Map<String, Map<String, String>>

/**
 * The core's own rules for roles, removal and group links, checked before anything is sent so the UI gets a
 * readable refusal instead of a core error. Pure logic. Each returns the refusal text, or null when allowed.
 * Also which group rows are one group after a rejoin ([successors]) and which of a rejoin's history is provably ours
 * ([markOwnHistory]). Sources: reference/simplex-chat 7.0.0.12, Library/Commands.hs.
 */
internal object GroupRules {

    /**
     * APIMembersRole (:2877-2890): never our own role (:2879 selfSelected), never a member pending approval (:2886),
     * and our role must be at least admin, at least the member's current role and at least the new one
     * (`assertUserGroupRole gInfo $ maximum [GRAdmin, maxRole, newRole]`, :2890). So only owners make owners or
     * change an owner's role. [me] and [target] come from a fresh `/_members`.
     */
    fun roleChangeRefusal(me: CoreMember?, target: CoreMember?, newRole: MemberRole): String? {
        membershipRefusal(me)?.let { return it }
        val m = me!!
        if (target == null || target.status == MemberStatus.LEFT || target.status == MemberStatus.REMOVED) return ErrorMapper.MEMBER_GONE
        if (target.isMe || target.groupMemberId == m.groupMemberId) return ErrorMapper.OWN_ROLE
        if (m.role < MemberRole.ADMIN) return ErrorMapper.ONLY_ADMINS_ROLES
        if (m.role < maxOf(target.role, newRole)) return ErrorMapper.ONLY_OWNERS_OWNERS
        if (target.pending) return ErrorMapper.MEMBER_PENDING
        return null
    }

    /**
     * APIRemoveMembers (:3014-3022): our role must be at least admin and at least the member's
     * (`assertUserGroupRole gInfo $ max GRAdmin maxRole`, :3022). We are never in the core's member list
     * (Store/Groups.hs:1230-1237), so removing ourselves is refused here: leaving is [ErrorMapper.REMOVE_SELF].
     * Invited and pending members can be removed (:3029-3035).
     */
    fun removeRefusal(me: CoreMember?, target: CoreMember?): String? {
        membershipRefusal(me)?.let { return it }
        val m = me!!
        if (target == null || target.status == MemberStatus.LEFT || target.status == MemberStatus.REMOVED) return ErrorMapper.MEMBER_GONE
        if (target.isMe || target.groupMemberId == m.groupMemberId) return ErrorMapper.REMOVE_SELF
        if (m.role < MemberRole.ADMIN) return ErrorMapper.ONLY_ADMINS_REMOVE
        if (m.role < target.role) return ErrorMapper.ONLY_OWNERS_REMOVE_OWNERS
        return null
    }

    /**
     * Group links (APICreateGroupLink :3241-3243 `assertUserGroupRole gInfo GRAdmin`; we apply the same rule to
     * reading the link): an admin or the owner who is a current, connected member. Deleting needs neither (the core
     * doesn't check, :3265-3268), so a demoted admin can remove their own link.
     */
    fun linkRefusal(g: CoreGroup): String? = statusRefusal(CoreJson.memberStatus(g.myStatus))
        ?: if (g.myRole < MemberRole.ADMIN) ErrorMapper.ONLY_ADMINS_LINK else null

    /**
     * The group's photo (APIUpdateGroupProfile, runUpdateGroupProfile :4020 `assertUserGroupRole gInfo GROwner`): the
     * owner, a current connected member. Members also drop a profile change from anyone below owner (Subscriber.hs:3733),
     * so an admin's change would reach nobody.
     */
    fun photoRefusal(g: CoreGroup): String? = statusRefusal(CoreJson.memberStatus(g.myStatus))
        ?: if (g.myRole < MemberRole.OWNER) ErrorMapper.ONLY_OWNERS_PHOTO else null

    /**
     * The group's disappearing-message timer: a group preference, sent in the group's profile like its photo
     * ([photoRefusal]: the owner, a current connected member).
     */
    fun timerRefusal(g: CoreGroup): String? = statusRefusal(CoreJson.memberStatus(g.myStatus))
        ?: if (g.myRole < MemberRole.OWNER) ErrorMapper.ONLY_OWNERS_TIMER else null

    /**
     * Rejoins. SimpleX groups have no shared id, so joining again after leaving or being removed makes a new group row
     * (the link plan ignores groups we left or were removed from, Store/Groups.hs:2888-2903, so the join goes ahead).
     * Telegram shows one chat; so an old row whose membership ended (left, removed or rejected, not deleted: a deleted
     * group can't be joined again) is replaced in the list by the next newer row of the same group that isn't a pending
     * invitation. Its history stays on the phone and comes back if the newer row is deleted.
     *
     * Same group: joined by the same link ([CoreGroup.linkKey], how the core itself recognises a group link), or the
     * same profile name and at least one shared memberId ([memberIds]: the other members' group-wide memberIds, null
     * when they couldn't be read). Member ids are random per member and group (Types.hs:1118), so two different groups
     * never share one; the name only limits how often [memberIds] is asked. Newer means a higher groupId (the core's
     * row id). Result: old groupId -> newer groupId.
     */
    fun successors(groups: List<CoreGroup>, memberIds: (CoreGroup) -> Set<String>?): Map<Long, Long> {
        val old = groups.filter { it.gone && !it.deleted }
        if (old.isEmpty()) return emptyMap()
        val newer = groups.filter { !it.invited }.sortedBy { it.groupId }
        val out = HashMap<Long, Long>()
        for (o in old) {
            for (n in newer) {
                if (n.groupId <= o.groupId) continue
                if (sameGroup(o, n, memberIds)) {
                    out[o.groupId] = n.groupId
                    break
                }
            }
        }
        return out
    }

    /**
     * The earlier rows of each row a rejoin made: every older row that [successors] leads to it, along the whole chain
     * (removed, back, removed, back again: both earlier rows). Result: chat id of the row -> those rows; rows without
     * one are absent. Only rows on this phone count: after a restore without the old rows, nothing is known.
     */
    fun earlierRows(groups: List<CoreGroup>, successors: Map<Long, Long>): Map<Long, List<CoreGroup>> {
        if (successors.isEmpty()) return emptyMap()
        val byId = groups.associateBy { it.groupId }
        val older = HashMap<Long, MutableList<Long>>()
        for ((old, newer) in successors) older.getOrPut(newer) { ArrayList() }.add(old)
        val out = HashMap<Long, List<CoreGroup>>()
        for (newer in older.keys) {
            val rows = ArrayList<CoreGroup>()
            // successors only ever points to a higher groupId, so the walk ends; [seen] keeps it linear anyway.
            val seen = HashSet<Long>()
            val todo = ArrayList(older[newer].orEmpty())
            var next = 0
            while (next < todo.size) {
                val o = todo[next++]
                if (!seen.add(o)) continue
                byId[o]?.let { rows.add(it) }
                older[o]?.let { todo.addAll(it) }
            }
            if (rows.isNotEmpty()) out[CoreJson.groupChatId(newer)] = rows
        }
        return out
    }

    /**
     * Our own sent messages among a row's items, by shared id -> text: what proves a history copy is ours. Only messages
     * we sent to the group (groupSnd) that still have their text; deleted ones prove nothing.
     */
    fun ownSent(items: List<CoreItem>): Map<String, String> {
        val out = HashMap<String, String>()
        for (i in items) {
            val id = i.sharedMsgId ?: continue
            if (i.group && i.outgoing && !i.local && !i.earlierOwn && i.isMessage && !i.deleted && i.text.isNotEmpty()) out[id] = i.text
        }
        return out
    }

    /**
     * The proof for one rejoined row: for each earlier row whose memberId and own sent messages ([ownSent], from
     * [sentOf]; null when they couldn't be read) are known, that memberId -> its messages. Rows without either add
     * nothing, so their history stays someone else's.
     */
    fun ownProofs(rows: List<CoreGroup>, sentOf: (CoreGroup) -> Map<String, String>?): OwnProofs {
        val out = HashMap<String, Map<String, String>>()
        for (row in rows) {
            val me = row.myMemberId ?: continue
            val sent = sentOf(row)?.takeIf { it.isNotEmpty() } ?: continue
            out[me] = (out[me].orEmpty()) + sent
        }
        return out
    }

    /**
     * A rejoin's history (Library/Internal.hs:1316-1329 sendHistory): the member who let us back in sends up to 100
     * earlier messages, each as x.grp.msg.forward naming its author by group-wide memberId (FwdMember, :1418) and keeping
     * the message's shared id (:1428). Ours carry the memberId of our earlier membership, which the new row doesn't know,
     * so the core makes an `unknown` member with our name for them (Subscriber.hs:3871-3887, Store/Groups.hs:1204-1213)
     * and stores each as a received, unread message with that shared id (Subscriber.hs:2195-2198, Messages.hs:1080-1086).
     *
     * A memberId alone proves nothing: every member knows ours, and whoever forwards chooses the author. So a message is
     * ours only when all three hold ([earlier] for its chat, [ownProofs]): its sender's group-wide id is one of our
     * earlier ids, its shared id is one of the messages we sent as that member (kept in that earlier row), and its text
     * is exactly what we sent. A forger can then only replay our own words. Anything unproven stays as the core has it.
     *
     * Ours becomes: outgoing, no sender, never unread, SENT_TO_SERVER (one tick: the copy proves it reached our server
     * and the member who sent the history, not that everyone got it), and neither editable nor deletable for everyone
     * (the core holds it as a received item and refuses both: Commands.hs:816 for edits, :859-861 and :889 for broadcast
     * deletes). A quote reads as ours under the same proof (author id, shared id and quoted text).
     */
    fun markOwnHistory(items: List<CoreItem>, earlier: (chatId: Long) -> OwnProofs?): List<CoreItem> = items.map { i ->
        if (!i.group) return@map i
        val proofs = earlier(i.chatId)?.takeIf { it.isNotEmpty() } ?: return@map i
        val quote = i.quote?.let { q -> if (!q.outgoing && proven(proofs, q.authorId, q.sharedMsgId, q.text)) q.copy(outgoing = true) else q }
        if (i.isMessage && !i.outgoing && !i.deleted && proven(proofs, i.sender?.groupWideId, i.sharedMsgId, i.text)) {
            i.copy(
                outgoing = true,
                status = MessageStatus.SENT_TO_SERVER,
                unread = false,
                editable = false,
                deletable = false,
                sender = null,
                quote = quote,
                earlierOwn = true,
            )
        } else if (quote !== i.quote) {
            i.copy(quote = quote)
        } else {
            i
        }
    }

    /**
     * G11, research/group-history.md. A join's history (Library/Internal.hs:1316-1329 sendHistory) reaches us as
     * x.grp.msg.forward from the member who let us in, each message keeping its original time: the core stores it with
     * item_ts = the forward's fwdBrokerTs = the original item's itemTs (:1418-1419; Subscriber.hs:3871, :2153-2198) and
     * forwarded_by_group_member_id set ([CoreItem.relayed]). The core has no history marker. So a relayed message is
     * history when it was sent before our membership began ([joinedAtMs]: membership.createdAt, or the moment we
     * accepted an invitation on this phone) by more than [HISTORY_MARGIN_MS] (clock skew between the phone and the
     * relays). A live message relayed for someone not yet connected to us was sent after we joined, so it never
     * qualifies; when in doubt (no time, no join time) it is not history and notifies.
     */
    fun beforeJoin(itemTsMs: Long, joinedAtMs: Long): Boolean =
        itemTsMs > 0 && joinedAtMs > 0 && itemTsMs < joinedAtMs - HISTORY_MARGIN_MS

    /**
     * [CoreItem.history] again with a later join time than the parser had: [acceptedAt] gives, per group chat id, when
     * we accepted its invitation on this phone (membership.createdAt is when the invitation arrived, possibly days
     * earlier, and the history covers everything up to our join). Never clears the flag.
     */
    fun markJoinHistory(items: List<CoreItem>, acceptedAt: (chatId: Long) -> Long?): List<CoreItem> = items.map { i ->
        if (i.history || !i.relayed || !i.isMessage || !i.group) return@map i
        val at = acceptedAt(i.chatId) ?: return@map i
        if (beforeJoin(i.timeMs, at)) i.copy(history = true, unread = false) else i
    }

    /**
     * A group joined recently enough that its history may still sit unread in the core ([HISTORY_SWEEP_WINDOW_MS]),
     * e.g. when the app stopped between the core storing it and the engine hearing of it. A join time in the future
     * (phone clock moved back) counts as recent.
     */
    fun joinHistorySweepDue(joinedAtMs: Long, nowMs: Long): Boolean =
        joinedAtMs > 0 && nowMs - joinedAtMs < HISTORY_SWEEP_WINDOW_MS

    /** Clock skew allowed between this phone and the relays' timestamps (G11). */
    const val HISTORY_MARGIN_MS = 2 * 60 * 1000L

    /** History arrives right after a join; later than this the sweep isn't worth a page read per group. */
    const val HISTORY_SWEEP_WINDOW_MS = 7L * 24 * 60 * 60 * 1000

    /** [memberId] is one of our earlier ids and that membership sent exactly [text] as [sharedMsgId]. */
    private fun proven(proofs: OwnProofs, memberId: String?, sharedMsgId: String?, text: String): Boolean {
        if (memberId == null || sharedMsgId == null || text.isEmpty()) return false
        return proofs[memberId]?.get(sharedMsgId) == text
    }

    private fun sameGroup(a: CoreGroup, b: CoreGroup, memberIds: (CoreGroup) -> Set<String>?): Boolean {
        if (a.linkKey != null && a.linkKey == b.linkKey) return true
        if (a.profileName.isEmpty() || a.profileName != b.profileName) return false
        val ia = memberIds(a)?.takeIf { it.isNotEmpty() } ?: return false
        val ib = memberIds(b) ?: return false
        return ib.any { it in ia }
    }

    /** We must be a connected member (assertUserGroupRole :4063-4068: joined, not removed, active). */
    private fun membershipRefusal(me: CoreMember?): String? = if (me == null) ErrorMapper.NOT_A_MEMBER else statusRefusal(me.status)

    private fun statusRefusal(s: MemberStatus?): String? = when (s) {
        MemberStatus.CONNECTED -> null
        MemberStatus.INVITED -> ErrorMapper.NOT_JOINED
        MemberStatus.CONNECTING -> ErrorMapper.GROUP_CONNECTING
        MemberStatus.LEFT, MemberStatus.REMOVED, null -> ErrorMapper.NOT_A_MEMBER
    }
}
