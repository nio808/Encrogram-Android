package app.encrogram.core.internal.media.prep

/**
 * Finds the largest preview that fits the character budget as a data URL (docs/media-plan.md §4.4, §2 point 8).
 * Starts at side 320, quality 70; lowers quality in steps to 40, then multiplies the side by 0.75 and restarts the
 * quality, down to side 64. Null when nothing fits: the caller uses the constant placeholder.
 */
internal object PreviewBudget {
    const val START_SIDE = 320
    const val MIN_SIDE = 64
    private val QUALITIES = intArrayOf(70, 60, 50, 40)
    private const val PREFIX = "data:image/jpg;base64,"

    fun dataUrlLength(byteCount: Int): Int = PREFIX.length + 4 * ((byteCount + 2) / 3)

    fun fit(maxChars: Int, encode: (side: Int, quality: Int) -> ByteArray?): ByteArray? {
        var side = START_SIDE
        while (true) {
            for (q in QUALITIES) {
                val data = encode(side, q)
                if (data != null && dataUrlLength(data.size) <= maxChars) return data
            }
            if (side <= MIN_SIDE) return null
            side = maxOf(MIN_SIDE, (side * 0.75).toInt())
        }
    }
}
