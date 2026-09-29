package com.devreply.sdk

/**
 * After the server refuses the public key (unknown or revoked), registration waits before trying
 * again: 1 min, 5 min, 30 min, then every 6 h, and fresh on the next app launch (spec 05, 0.4.0).
 * Without this, every refresh and poll would register again, about once a second.
 */
internal class RegistrationBackoff(private val now: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
    private var refusals = 0
    private var blockedUntil = 0L

    /** Registering is allowed now. */
    fun allowed(): Boolean = refusals == 0 || now() >= blockedUntil

    /** The key was refused. Returns true the first time, so the warning is logged once. */
    fun refused(): Boolean {
        refusals++
        blockedUntil = now() + DELAYS_MS[minOf(refusals, DELAYS_MS.size) - 1]
        return refusals == 1
    }

    fun succeeded() {
        refusals = 0
        blockedUntil = 0L
    }

    /** Milliseconds until the next try (0 = now). */
    fun waitMs(): Long = if (allowed()) 0 else blockedUntil - now()

    companion object {
        val DELAYS_MS = longArrayOf(60_000, 5 * 60_000, 30 * 60_000, 6 * 60 * 60_000)
    }
}
