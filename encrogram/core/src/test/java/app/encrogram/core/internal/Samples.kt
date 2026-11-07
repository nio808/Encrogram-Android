package app.encrogram.core.internal

/**
 * Engine replies and events shaped after reference/simplex-chat/bots/api (COMMANDS.md, EVENTS.md, TYPES.md),
 * chat core 7.0.0.12. Extra fields are included on purpose to prove the parser ignores what it doesn't need.
 */
object Samples {

    private const val USER = """{"userId":1,"agentUserId":"1","userContactId":1,"localDisplayName":"alice",
        "profile":{"profileId":1,"displayName":"Alice","fullName":"","localAlias":""},"fullPreferences":{},
        "activeUser":true,"activeOrder":1,"showNtfs":true,"sendRcptsContacts":true,"sendRcptsSmallGroups":true,
        "autoAcceptMemberContacts":false,"userChatRelay":false,"clientService":false,"futureField":{"x":1}}"""

    fun contact(id: Long, name: String, connStatus: String?, code: Boolean = false, contactLink: String? = null) = """
        {"contactId":$id,"localDisplayName":"${name.lowercase()}",
         "profile":{"profileId":${id + 10},"displayName":"$name","fullName":"","localAlias":""${
            if (contactLink != null) ""","contactLink":"$contactLink"""" else ""}},
         ${if (connStatus != null) """"activeConn":{"connId":${id + 100},"agentConnId":"YWJj","connChatVersion":16,
           "peerChatVRange":{"minVersion":1,"maxVersion":16},"connLevel":0,"viaGroupLink":false,"connType":"contact",
           "connStatus":{"type":"$connStatus"},"contactConnInitiated":true,"localAlias":"",
           "connectionCode":${if (code) """{"securityCode":"12345","verifiedAt":"2026-11-07T10:00:00Z"}""" else "null"},
           "pqSupport":true,"pqEncryption":true,"authErrCounter":0,"quotaErrCounter":0,"createdAt":"2026-11-07T09:00:00Z"},"""
           else ""}
         "contactUsed":true,"contactStatus":"active","chatSettings":{"enableNtfs":"all","favorite":false},
         "userPreferences":{},"mergedPreferences":{},"createdAt":"2026-11-07T09:00:00Z","updatedAt":"2026-11-07T09:30:00Z",
         "chatTs":null,"preparedContact":null,"contactRequestId":null,"contactGrpInvSent":false,"chatTags":[],
         "chatItemTTL":null,"chatDeleted":false}"""

    fun chatItem(itemId: Long, dir: String, contentType: String, text: String, status: String, ts: String = "2026-11-07T10:20:30.123456Z") = """
        {"chatDir":{"type":"$dir"},
         "meta":{"itemId":$itemId,"itemTs":"$ts","itemText":"$text","itemStatus":{"type":"$status"${
            if (status == "sndSent") ""","sndProgress":"complete"""" else ""}},
           "itemEdited":false,"userMention":false,"hasLink":false,"deletable":true,"editable":true,
           "showGroupAsSender":false,"createdAt":"$ts","updatedAt":"$ts"},
         "content":{"type":"$contentType","msgContent":{"type":"text","text":"$text"}},
         "mentions":{},"reactions":[]}"""

    val ACTIVE_USER = """{"result":{"type":"activeUser","user":$USER}}"""

    val NO_ACTIVE_USER = """{"error":{"type":"error","errorType":{"type":"noActiveUser"}}}"""

    val AGENT_SMP_AUTH = """{"error":{"type":"errorAgent","agentError":{"type":"SMP",
        "serverAddress":"smp://abc@relay.example","smpErr":{"type":"AUTH"}},"agentConnId":"YWJj"}}"""

    val AGENT_BROKER_TIMEOUT = """{"error":{"type":"errorAgent","agentError":{"type":"BROKER",
        "brokerAddress":"smp://abc@relay.example","brokerErr":{"type":"TIMEOUT"}},"agentConnId":""}}"""

    val STORE_DUPLICATE = """{"error":{"type":"errorStore","storeError":{"type":"duplicateName"}}}"""

    val CMD_ERROR_RESULT = """{"result":{"type":"chatCmdError","chatError":{"type":"error","errorType":{"type":"chatNotStarted"}}}}"""

    val NEW_CHAT_ITEMS_EVENT = """{"result":{"type":"newChatItems","user":$USER,"chatItems":[
        {"chatInfo":{"type":"direct","contact":${contact(5, "Bob", "ready")}},
         "chatItem":${chatItem(42, "directRcv", "rcvMsgContent", "hello 👋", "rcvNew")}},
        {"chatInfo":{"type":"group","groupInfo":{"groupId":3}},
         "chatItem":${chatItem(43, "groupRcv", "rcvMsgContent", "group text", "rcvNew")}}
        ]}}"""

    val STATUS_UPDATED_EVENT = """{"result":{"type":"chatItemsStatusesUpdated","user":$USER,"chatItems":[
        {"chatInfo":{"type":"direct","contact":${contact(5, "Bob", "ready")}},
         "chatItem":${chatItem(41, "directSnd", "sndMsgContent", "hi", "sndRcvd")}}]}}"""

    val API_CHATS = """{"result":{"type":"apiChats","user":$USER,"chats":[
        {"chatInfo":{"type":"direct","contact":${contact(5, "Bob", "ready", code = true)}},
         "chatItems":[${chatItem(41, "directSnd", "sndMsgContent", "hi", "sndSent")}],
         "chatStats":{"unreadCount":2,"unreadMentions":0,"reportsCount":0,"minUnreadItemId":40,"unreadChat":false}},
        {"chatInfo":{"type":"direct","contact":${contact(6, "Carol", "joined")}},
         "chatItems":[],"chatStats":{"unreadCount":0,"unreadMentions":0,"reportsCount":0,"minUnreadItemId":0,"unreadChat":false}},
        {"chatInfo":{"type":"direct","contact":${contact(1, "Ask SimpleX Team", null, contactLink = "simplex:/contact#/?v=2-7&smp=x")}},
         "chatItems":[],"chatStats":{"unreadCount":0,"unreadMentions":0,"reportsCount":0,"minUnreadItemId":0,"unreadChat":false}},
        {"chatInfo":{"type":"group","groupInfo":{"groupId":3}},"chatItems":[],
         "chatStats":{"unreadCount":9,"unreadMentions":0,"reportsCount":0,"minUnreadItemId":0,"unreadChat":false}},
        {"chatInfo":{"type":"contactConnection","contactConnection":{"pccConnId":7,"pccAgentConnId":"x",
         "pccConnStatus":{"type":"new"},"viaContactUri":false,"localAlias":"","createdAt":"2026-11-07T09:00:00Z",
         "updatedAt":"2026-11-07T09:00:00Z"}},"chatItems":[],
         "chatStats":{"unreadCount":0,"unreadMentions":0,"reportsCount":0,"minUnreadItemId":0,"unreadChat":false}}
        ]}}"""

    val API_CHAT = """{"result":{"type":"apiChat","user":$USER,"chat":{
        "chatInfo":{"type":"direct","contact":${contact(5, "Bob", "ready")}},
        "chatItems":[
          ${chatItem(40, "directRcv", "rcvMsgContent", "first", "rcvRead", "2026-11-07T10:00:00Z")},
          {"chatDir":{"type":"directSnd"},"meta":{"itemId":39,"itemTs":"2026-11-07T09:59:00Z","itemText":"connected",
           "itemStatus":{"type":"sndNew"},"itemEdited":false,"userMention":false,"hasLink":false,"deletable":false,
           "editable":false,"showGroupAsSender":false,"createdAt":"2026-11-07T09:59:00Z","updatedAt":"2026-11-07T09:59:00Z"},
           "content":{"type":"sndConnEvent","sndConnEvent":{"type":"switchQueue","phase":"started"}},"mentions":{},"reactions":[]},
          ${chatItem(41, "directSnd", "sndMsgContent", "second", "sndErrorAuth", "2026-11-07T10:01:00Z")}
        ],
        "chatStats":{"unreadCount":0,"unreadMentions":0,"reportsCount":0,"minUnreadItemId":0,"unreadChat":false}},
        "navInfo":{"afterUnread":0,"afterTotal":0}}}"""

    val INVITATION = """{"result":{"type":"invitation","user":$USER,
        "connLinkInvitation":{"connFullLink":"simplex:/invitation#/?v=2-7&smp=smp%3A%2F%2Ffp%40encrogram.nytmanager.com%3A5223%2Fq%23%2F%3Fv%3D1-4%26dh%3Dkey&e2e=v%3D2-3",
          "connShortLink":"https://encrogram.nytmanager.com/i#AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB?p=5223&c=7fc79ge_ADax0g_Lwu6YYwI1hFH6KwOG0y11w1YVORg"},
        "connection":{"pccConnId":12,"pccAgentConnId":"x","pccConnStatus":{"type":"new"},"viaContactUri":false,
          "localAlias":"","createdAt":"2026-11-07T09:00:00Z","updatedAt":"2026-11-07T09:00:00Z"}}}"""

    val CONNECTION_PLAN_OWN = """{"result":{"type":"connectionPlan","user":$USER,
        "connLink":{"connFullLink":"simplex:/invitation#/?v=2-7","connShortLink":null},
        "connectionPlan":{"type":"invitationLink","invitationLinkPlan":{"type":"ownLink"}}}}"""

    val OPERATORS = """{"result":{"type":"serverOperatorConditions","conditions":{
        "serverOperators":[
          {"operatorId":1,"operatorTag":"simplex","tradeName":"SimpleX Chat","legalName":"SimpleX Chat Ltd",
           "serverDomains":["simplex.im"],"conditionsAcceptance":{"type":"accepted","acceptedAt":null,"autoAccepted":true},
           "enabled":true,"smpRoles":{"storage":true,"proxy":true,"names":true},"xftpRoles":{"storage":true,"proxy":true,"names":false}},
          {"operatorId":2,"operatorTag":"flux","tradeName":"Flux","legalName":"InFlux Technologies Limited",
           "serverDomains":["simplexonflux.com"],"conditionsAcceptance":{"type":"required","deadline":null},
           "enabled":false,"smpRoles":{"storage":false,"proxy":true,"names":false},"xftpRoles":{"storage":false,"proxy":true,"names":false}}
        ],
        "currentConditions":{"conditionsId":1,"conditionsCommit":"abc","createdAt":"2026-01-01T00:00:00Z"},
        "conditionsAction":null}}}"""

    const val SMP = "smp://7fc79ge_ADax0g_Lwu6YYwI1hFH6KwOG0y11w1YVORg=:secretpass@encrogram.nytmanager.com:5223"
    const val XFTP = "xftp://sySVgvyI926QYrbpVtwz-SqLumN-LcYUKRFBYBW-5bM=:secretpass@engrogramapi.nytmanager.com:5443"

    private fun op(id: Int, domain: String, enabled: Boolean) =
        """{"operatorId":$id,"tradeName":"Op$id","serverDomains":["$domain"],
            "conditionsAcceptance":{"type":"accepted","acceptedAt":null,"autoAccepted":true},"enabled":$enabled,
            "smpRoles":{"storage":true,"proxy":true,"names":false},"xftpRoles":{"storage":true,"proxy":true,"names":false}}"""

    private fun srv(id: Long?, address: String, preset: Boolean, enabled: Boolean, deleted: Boolean = false) =
        """{"serverId":${id ?: "null"},"server":"$address","preset":$preset,"tested":null,"enabled":$enabled,
            "roles":{},"deleted":$deleted}"""

    /** A brand-new user: preset operator groups (SimpleX enabled) and an empty custom group. */
    val USER_SERVERS_FRESH = """{"result":{"type":"userServers","user":$USER,"userServers":[
        {"operator":${op(1, "simplex.im", true)},
         "smpServers":[${srv(1, "smp://aaaa@smp8.simplex.im", true, true)},${srv(2, "smp://bbbb@smp9.simplex.im", true, true)}],
         "xftpServers":[${srv(3, "xftp://cccc@xftp1.simplex.im:443", true, true)}],"chatRelays":[]},
        {"operator":${op(2, "simplexonflux.com", false)},
         "smpServers":[${srv(4, "smp://dddd@smp1.simplexonflux.com", true, true)}],"xftpServers":[],"chatRelays":[]},
        {"operator":null,"smpServers":[],"xftpServers":[],"chatRelays":[]}
        ]}}"""

    /** Custom group with a stale custom server, an older address of our SMP relay, and no XFTP. */
    val USER_SERVERS_STALE = """{"result":{"type":"userServers","user":$USER,"userServers":[
        {"operator":${op(1, "simplex.im", false)},"smpServers":[${srv(1, "smp://aaaa@smp8.simplex.im", true, true)}],
         "xftpServers":[],"chatRelays":[]},
        {"operator":null,
         "smpServers":[${srv(10, "smp://old@other.example.org:5223", false, true)},
                       ${srv(11, "smp://7fc79ge_ADax0g_Lwu6YYwI1hFH6KwOG0y11w1YVORg=:oldpass@encrogram.nytmanager.com:5223", false, false)}],
         "xftpServers":[],"chatRelays":[]}
        ]}}"""

    /** What the engine should report after our configuration. */
    fun userServersConfigured(simplexEnabled: Boolean = false, extraCustomSmp: String? = null, withXftp: Boolean = true) =
        """{"result":{"type":"userServers","user":$USER,"userServers":[
        {"operator":${op(1, "simplex.im", simplexEnabled)},
         "smpServers":[${srv(1, "smp://aaaa@smp8.simplex.im", true, true)}],
         "xftpServers":[${srv(3, "xftp://cccc@xftp1.simplex.im:443", true, true)}],"chatRelays":[]},
        {"operator":null,
         "smpServers":[${srv(20, SMP, false, true)}${if (extraCustomSmp != null) "," + srv(21, extraCustomSmp, false, true) else ""}],
         "xftpServers":[${if (withXftp) srv(22, XFTP, false, true) else ""}],"chatRelays":[]}
        ]}}"""

    val NETWORK_CONFIG_OK = """{"result":{"type":"networkConfig","networkConfig":{"socksMode":"always",
        "hostMode":"public","requiredHostMode":false,"sessionMode":"session","smpProxyMode":"unknown",
        "smpProxyFallback":"prohibit","smpWebPortServers":"preset",
        "tcpConnectTimeout":{"backgroundTimeout":45000000,"interactiveTimeout":15000000},
        "tcpTimeout":{"backgroundTimeout":30000000,"interactiveTimeout":10000000},"tcpTimeoutPerKb":5000,
        "rcvConcurrency":8,"smpPingInterval":600000000,"smpPingCount":3,"logTLSErrors":false}}}"""

    val NETWORK_CONFIG_DEFAULT = NETWORK_CONFIG_OK
        .replace("\"smpProxyMode\":\"unknown\"", "\"smpProxyMode\":\"never\"")
        .replace("\"smpProxyFallback\":\"prohibit\"", "\"smpProxyFallback\":\"allow\"")
}
