package com.devreply.sdk

import org.json.JSONArray

/**
 * `DevReply.deleteUser()` that never gives up (0.4.4): when the server can't be reached, the device
 * forgets the user at once and keeps the old install token here, in the same encrypted store as the
 * token itself. The SDK retries `DELETE /v1/me` with it at every configure and when the app comes back,
 * until the server confirms. Only ever the saved old token: never the new install's.
 *
 * [read] and [write] are the encrypted entry (TokenStore); null writes remove it.
 */
internal class PendingDeletions(private val read: () -> String?, private val write: (String?) -> Unit) {

    /** Oldest first. A damaged entry reads as empty. */
    fun all(): List<String> {
        val raw = read() ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { (array.opt(it) as? String)?.takeIf(String::isNotEmpty) }
        }.getOrDefault(emptyList())
    }

    /** Keeps at most [MAX] (the oldest go first); the same token only once. */
    fun add(token: String) {
        save((all().filter { it != token } + token).takeLast(MAX))
    }

    fun remove(token: String) {
        save(all().filter { it != token })
    }

    private fun save(tokens: List<String>) {
        write(if (tokens.isEmpty()) null else JSONArray(tokens).toString())
    }

    /** What a `DELETE /v1/me` answer means for the user's deletion. */
    enum class Outcome {
        /** Deleted now, or already gone (401: the token no longer works, 404: no such user). */
        Done,
        /** The server can't be reached, has a problem (5xx) or asks to slow down (429): try again later. */
        Retry,
        /** Refused for another reason (e.g. 400): retrying the same request won't help. */
        Failed,
    }

    companion object {
        const val MAX = 10

        /** [error]: what the request threw, or null when it succeeded. */
        fun outcome(error: Throwable?): Outcome = when (error) {
            null, DevReplyError.Unauthenticated, DevReplyError.NotFound -> Outcome.Done
            DevReplyError.Network, DevReplyError.Unavailable -> Outcome.Retry
            is DevReplyError.Server -> if (error.status >= 500 || error.status == 429) Outcome.Retry else Outcome.Failed
            else -> Outcome.Failed
        }
    }
}
