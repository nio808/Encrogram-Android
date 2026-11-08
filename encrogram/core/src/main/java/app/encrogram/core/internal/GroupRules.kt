package app.encrogram.core.internal

import app.encrogram.core.api.MemberRole
import app.encrogram.core.api.MemberStatus

/**
 * The core's own rules for roles, removal and group links, checked before anything is sent so the UI gets a
 * readable refusal instead of a core error. Pure logic. Each returns the refusal text, or null when allowed.
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
     * reading and deleting the link): an admin or the owner who is a current, connected member.
     */
    fun linkRefusal(g: CoreGroup): String? = statusRefusal(CoreJson.memberStatus(g.myStatus))
        ?: if (g.myRole < MemberRole.ADMIN) ErrorMapper.ONLY_ADMINS_LINK else null

    /** We must be a connected member (assertUserGroupRole :4063-4068: joined, not removed, active). */
    private fun membershipRefusal(me: CoreMember?): String? = if (me == null) ErrorMapper.NOT_A_MEMBER else statusRefusal(me.status)

    private fun statusRefusal(s: MemberStatus?): String? = when (s) {
        MemberStatus.CONNECTED -> null
        MemberStatus.INVITED -> ErrorMapper.NOT_JOINED
        MemberStatus.CONNECTING -> ErrorMapper.GROUP_CONNECTING
        MemberStatus.LEFT, MemberStatus.REMOVED, null -> ErrorMapper.NOT_A_MEMBER
    }
}
