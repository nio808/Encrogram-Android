package app.encrogram.core.internal

import app.encrogram.core.api.MemberRole
import app.encrogram.core.api.MemberStatus

/**
 * The core's own rules for roles, removal and group links, checked before anything is sent so the UI gets a
 * readable refusal instead of a core error. Pure logic. Each returns the refusal text, or null when allowed.
 * Also which group rows are one group after a rejoin ([successors]).
 * Sources: reference/simplex-chat 7.0.0.12, Library/Commands.hs.
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
