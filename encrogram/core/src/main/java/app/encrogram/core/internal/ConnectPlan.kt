package app.encrogram.core.internal

import app.encrogram.core.api.LinkCheck
import app.encrogram.core.api.SecuredError
import org.json.JSONObject

/**
 * The verdict on a `/_connect plan` reply before anything is connected. Pure logic, so it is unit tested.
 *
 * Why a second check after [LinkChecker]: a short link only names where its data lives. The connection request
 * inside that data (`connLink.connFullLink` in the reply, CRConnectionPlan, Controller.hs:843) is what the core
 * connects to, and whoever made the link chose it. It is fixed data whose hash is the link key (simplexmq
 * Agent/Protocol.hs LinkKey), so `/connect` later uses exactly this request; it must be on our relay too.
 *
 * Group links never go through `/connect`: it plans again and, for a link whose (changeable) data says "not direct",
 * joins through chat relays on other servers (connectWithPlan, Commands.hs:4489-4500). They are joined with
 * `/_connect <userId> <full> [<short>]` using exactly the links checked here ([Verdict.JoinGroup]).
 */
internal object ConnectPlan {

    sealed class Verdict {
        /** A 1:1 invite or address: `/connect <checked link>` as before. */
        object Connect : Verdict()

        /** A group link: `/_connect` with these checked, normalized links ([Commands.connectResolved]). */
        data class JoinGroup(val fullLink: String, val shortLink: String?) : Verdict()

        /** [logTag] is a fixed word for SecuredLog. */
        data class Refuse(val error: SecuredError, val logTag: String) : Verdict()
    }

    /**
     * Group plans the engine joins (Controller.hs:1127-1134, tags `sumTypeJSON (dropPrefix "GLP")`, :1784). Only a
     * fresh join: `connectingConfirmReconnect` (a join already sent, not answered yet) is refused, because the admin's
     * core doesn't de-duplicate a second request and would add us twice (Subscriber.hs:1607, review #3).
     */
    private val JOINABLE_GROUP_PLANS = setOf("groupLink.ok")

    /** Plan kinds that may go on to `/connect`: 1:1 invites and addresses, and the core's own error plan (as before). */
    private val CONNECT_PLAN_PREFIXES = listOf("invitationLink.", "contactAddress.")
    private const val ERROR_PLAN_PREFIX = "error."
    private const val GROUP_PLAN_PREFIX = "groupLink."

    /**
     * [groupInput]: the user's link is a short group link (`/g#`, [LinkChecker.isShortGroupLink]); then the plan must
     * be a group plan, or nothing is connected. Only plan kinds this version knows go anywhere (review #4): 1:1 plans
     * to `/connect`, group plans to the group path; everything else is refused. `error.*` plans go to `/connect` as
     * before (the core's connectionPlanProceed is True for them, and `/connect` reports the error), except for `/g`.
     */
    fun verdict(body: JSONObject, allowedHosts: Set<String>, smpServers: List<String>, groupInput: Boolean = false): Verdict {
        val tag = CoreJson.connectionPlanTag(body)
        val links = body.optJSONObject("connLink")
        // 1. The request the core resolved, and the short link it will store, are on our relay (host, port, key).
        val full = LinkChecker.analyze(links?.str("connFullLink"), allowedHosts, smpServers)
        refusal(full.result, "resolved")?.let { return it }
        val shortText = links?.str("connShortLink")?.ifEmpty { null }
        val short = shortText?.let { LinkChecker.analyze(it, allowedHosts, smpServers) }
        if (short != null) refusal(short.result, "resolved_short")?.let { return it }
        // 2. Own links, known and still-connecting groups, relay channels.
        ErrorMapper.planRefusal(tag)?.let { return Verdict.Refuse(it, "plan") }
        // 3. Only known plan kinds, each on its own path; a `/g` link only as a group.
        val groupPlan = tag.startsWith(GROUP_PLAN_PREFIX)
        if (groupInput && !groupPlan) return Verdict.Refuse(ErrorMapper.invalidLink(), "plan_not_group")
        if (!groupPlan) {
            val known = CONNECT_PLAN_PREFIXES.any { tag.startsWith(it) } || tag.startsWith(ERROR_PLAN_PREFIX)
            return if (known) Verdict.Connect else Verdict.Refuse(ErrorMapper.invalidLink(), "plan_unknown")
        }
        if (tag !in JOINABLE_GROUP_PLANS) return Verdict.Refuse(ErrorMapper.fromConnectionPlan(tag), "plan")
        if (relayGroup(body)) return Verdict.Refuse(ErrorMapper.notAllowed(ErrorMapper.UNSUPPORTED_GROUP_LINK), "relay_group")
        val fullLink = full.normalized ?: return Verdict.Refuse(ErrorMapper.invalidLink(), "resolved")
        return Verdict.JoinGroup(fullLink, short?.normalized)
    }

    private fun refusal(r: LinkCheck, tag: String): Verdict.Refuse? = when (r) {
        LinkCheck.Approved -> null
        is LinkCheck.UnapprovedRelay -> Verdict.Refuse(ErrorMapper.unapprovedRelay(r.host), "${tag}_unapproved")
        LinkCheck.Malformed -> Verdict.Refuse(ErrorMapper.invalidLink(), "${tag}_malformed")
    }

    /**
     * A group joined through chat relays (a channel), not directly through its owner: GLPOk's `groupSLinkInfo_`
     * {direct, groupRelays} (Controller.hs:1156-1160) says not direct or names relays, or the link data carries a
     * public group (GroupShortLinkData.publicGroupData, Protocol.hs:1606-1609; GroupProfile.publicGroup). Encrogram
     * makes and joins only direct groups.
     */
    private fun relayGroup(body: JSONObject): Boolean {
        val plan = body.optJSONObject("connectionPlan")?.optJSONObject("groupLinkPlan") ?: return false
        val info = plan.optJSONObject("groupSLinkInfo_") ?: plan.optJSONObject("groupSLinkInfo")
        if (info != null) {
            // Anything but a literal true counts as not direct (fail closed).
            if (info.has("direct") && info.opt("direct") != true) return true
            if ((info.optJSONArray("groupRelays")?.length() ?: 0) > 0) return true
        }
        val data = plan.optJSONObject("groupSLinkData_") ?: plan.optJSONObject("groupSLinkData")
        if (data != null) {
            if (present(data, "publicGroupData")) return true
            val profile = data.optJSONObject("groupProfile")
            if (profile != null && present(profile, "publicGroup")) return true
        }
        return false
    }

    private fun present(o: JSONObject, key: String) = o.has(key) && !o.isNull(key)
}
