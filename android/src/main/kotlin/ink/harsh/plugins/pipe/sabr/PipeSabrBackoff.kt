package ink.harsh.plugins.pipe.sabr

import android.util.Log

/**
 * Process-wide holder for server-requested SABR backoff.
 *
 * The coordinator honours backoff *within* a session, but the server's request
 * is about the client as a whole: upstream (PipePipeClient) publishes it to an
 * app-wide `SabrBackoffCoordinator` so a session opened *during* a backoff
 * waits it out instead of immediately re-requesting. This is our minimal
 * equivalent — in-process only, not persisted, which is enough for a plugin
 * whose sessions all live in one process.
 *
 * Fed by each bridge's backoff observer; consulted by [PipeSabrManager.open]
 * before the first round of a new session.
 */
internal object PipeSabrBackoff {

    private const val TAG = "PipeSabrBackoff"

    /** Never sleep an open() longer than this, whatever the server asked for. */
    private const val MAX_WAIT_MS = 30_000L

    @Volatile
    private var untilMs = 0L

    /** Record a server-requested backoff. Zero and negative values are no-ops. */
    fun publish(backoffMs: Long) {
        if (backoffMs <= 0) {
            return
        }
        val until = System.currentTimeMillis() + backoffMs
        synchronized(this) {
            if (until > untilMs) {
                untilMs = until
            }
        }
    }

    fun remainingMs(): Long = Math.max(0L, untilMs - System.currentTimeMillis())

    /**
     * Sleep out any published backoff before starting new SABR work.
     *
     * Bounded by [MAX_WAIT_MS] so a hostile or garbled backoff value cannot
     * hang an open() indefinitely.
     */
    @Throws(InterruptedException::class)
    fun awaitClear() {
        val wait = Math.min(remainingMs(), MAX_WAIT_MS)
        if (wait <= 0) {
            return
        }
        Log.i(TAG, "honouring server-requested backoff: waiting " + wait + "ms")
        Thread.sleep(wait)
    }
}
