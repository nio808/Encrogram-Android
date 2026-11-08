package app.encrogram.core.internal.media

import app.encrogram.core.internal.Samples

/** Media chat items, files and events shaped after the core's JSON (Messages/CIContent, Files.hs, Controller.hs:950-969). */
object MediaSamples {

    /** A 16x16 grey PNG (108 bytes) and the matching data URL. */
    const val PNG_B64 = "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAIAAACQkWg2AAAAM0lEQVR4XpXIoREAAAyEMEb/0esxXCPDnnAUHAVHwVFwFBwFR8FRcBQcBUfBUXAUHAVHOSWEgBAvLvKsAAAAAElFTkSuQmCC"
    const val PNG_URL = "data:image/png;base64,$PNG_B64"

    /** Name, path and key canaries: none of them may ever show in a toString. */
    const val NAME = "CANARY-NAME-report.pdf"
    const val PATH = "CANARY-PATH-0001.bin"
    const val KEY = "CANARYKEYAAAA"
    const val NONCE = "CANARYNONCEBBBB"

    /** fileStatus {type, ...extra}. */
    fun status(type: String, extra: String = "") = """{"type":"$type"${if (extra.isEmpty()) "" else ",$extra"}}"""

    /** CIFile. [source]: fileSource with a path and crypto args (a complete or sent file). */
    fun file(
        id: Long = 7,
        name: String = NAME,
        size: Long = 1_000_000,
        status: String = status("rcvInvitation"),
        protocol: String = "xftp",
        source: Boolean = false,
    ) = """{"fileId":$id,"fileName":"$name","fileSize":$size,"fileStatus":$status,"fileProtocol":"$protocol"${
        if (source) ""","fileSource":{"filePath":"$PATH","cryptoArgs":{"fileKey":"$KEY","fileNonce":"$NONCE"}}""" else ""}}"""

    /** MsgContent: image and video carry [image] (a data URL) and video and voice a [duration]. */
    fun content(type: String, text: String = "", image: String? = null, duration: Int? = null) =
        """{"type":"$type","text":"$text"${if (image != null) ""","image":"$image"""" else ""}${if (duration != null) ""","duration":$duration""" else ""}}"""

    /**
     * A chat item with media. [itemText] is meta.itemText, which the core fills with names such as "voice message 0:12"
     * when there is no caption. [dir] directRcv or directSnd; [itemStatus] the CIStatus tag.
     */
    fun item(
        itemId: Long,
        dir: String,
        msgContent: String,
        file: String?,
        itemStatus: String,
        itemText: String = "",
        deleted: Boolean = false,
        editable: Boolean = false,
        quote: String? = null,
    ): String {
        val outgoing = dir.endsWith("Snd")
        val ts = "2026-11-08T10:20:30.123456Z"
        return """
        {"chatDir":{"type":"$dir"},
         "meta":{"itemId":$itemId,"itemTs":"$ts","itemText":"$itemText","itemStatus":{"type":"$itemStatus"${
            if (itemStatus == "sndSent") ""","sndProgress":"complete"""" else ""}},
           "itemSharedMsgId":"c2hhcmVkLWlk",
           ${if (deleted) "\"itemDeleted\":{\"type\":\"deleted\",\"deletedTs\":\"2026-11-08T11:00:00Z\",\"chatType\":\"direct\"}," else ""}
           "itemEdited":false,"userMention":false,"hasLink":false,"deletable":$outgoing,"editable":$editable,
           "showGroupAsSender":false,"createdAt":"$ts","updatedAt":"$ts"},
         "content":{"type":"${if (outgoing) "sndMsgContent" else "rcvMsgContent"}","msgContent":$msgContent},
         "mentions":{},${if (quote != null) "\"quotedItem\":$quote," else ""}${if (file != null) "\"file\":$file," else ""}"reactions":[]}"""
    }

    /** CIQuote of a media message. */
    fun quote(itemId: Long, contentType: String, text: String, dir: String = "directRcv") =
        """{"chatDir":{"type":"$dir"},"itemId":$itemId,"sharedMsgId":"cXVvdGVk","sentAt":"2026-11-08T10:00:00Z",
           "content":{"type":"$contentType","text":"$text"}}"""

    /** The body of a file event; [chatItem] under [itemKey] ("chatItem" or "chatItem_"). */
    fun event(type: String, item: String?, itemKey: String = "chatItem_", extra: String = "") =
        """{"type":"$type"${if (item != null) ",\"$itemKey\":${Samples.aChatItem(5, item)}" else ""}${if (extra.isEmpty()) "" else ",$extra"}}"""

    fun descrReady(item: String, transferFileId: Long, complete: Boolean = true, text: String = "party: recipient\nsize: 1000000\n") =
        event(
            "rcvFileDescrReady", item, "chatItem",
            """"rcvFileTransfer":{"fileId":$transferFileId},"rcvFileDescr":{"fileDescrText":"${text.replace("\n", "\\n")}","fileDescrComplete":$complete}""",
        )
}
