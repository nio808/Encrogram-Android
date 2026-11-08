package app.encrogram.core.internal.media

/**
 * Gate for progress events per (chatId, itemId): passes when the value moved by at least 1 per mille and at least
 * [MIN_INTERVAL_MS] passed since the last pass; the final value (done >= total) always passes.
 * Engine thread only.
 */
internal class ProgressThrottle(private val clock: () -> Long = System::currentTimeMillis) {

    private class Last(val permille: Long, val atMs: Long)

    private val last = HashMap<Pair<Long, Long>, Last>()

    fun shouldEmit(chatId: Long, itemId: Long, done: Long, total: Long): Boolean {
        if (total <= 0) return false
        val key = chatId to itemId
        val now = clock()
        val permille = (done.coerceAtLeast(0) * 1000 / total).coerceAtMost(1000)
        val finished = done >= total
        val prev = last[key]
        val pass = finished || prev == null ||
            (Math.abs(permille - prev.permille) >= 1 && now - prev.atMs >= MIN_INTERVAL_MS)
        if (pass) last[key] = Last(permille, now)
        return pass
    }

    fun forget(chatId: Long, itemId: Long) {
        last.remove(chatId to itemId)
    }

    companion object {
        const val MIN_INTERVAL_MS = 250L
    }
}
