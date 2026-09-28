package com.devreply.sdk

import com.devreply.sdk.ui.defaultTitle
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** Version of this SDK. Sent on install registration and compared with each block's `min_sdk`. */
public const val DEVREPLY_SDK_VERSION: String = "0.3.0"

/** What a conversation is about. Set by the start button the user picked (spec 05). */
public enum class DevReplyCategory(internal val wire: String) {
    Bug("bug"), Billing("billing"), Idea("idea"), Question("question"), Other("other");

    internal companion object {
        /** Categories added on the server later map to [Other], so old SDKs keep working. */
        fun fromWire(raw: String?): DevReplyCategory? =
            if (raw == null) null else entries.firstOrNull { it.wire == raw } ?: Other
    }
}

// Forward compatibility (spec 05): a newer server must never break an SDK already shipped in apps.
// Rules for every model here: unknown enum values map to a safe default, optional or newer fields
// are read with defaults, and one bad item in a list is skipped instead of failing the list.

/** Reads an array, dropping elements that fail to parse. */
internal fun <T> JSONArray?.lossy(parse: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { i -> runCatching { parse(getJSONObject(i)) }.getOrNull() }
}

/** A string field, or null when missing, null or not a string. */
internal fun JSONObject.str(key: String): String? = if (isNull(key)) null else (opt(key) as? String)

internal fun JSONObject.int(key: String): Int? = (opt(key) as? Number)?.toInt()

/** RFC 3339 with or without fractional seconds (the server sends microseconds). */
internal fun parseRfc3339(s: String): Instant? = runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull()

internal data class MessengerConfig(
    val appName: String,
    val teamName: String,
    val greeting: String,
    val intro: String,
    val replyTime: String,
    /** What goes after "Please allow up to": "3 working days", "an hour". Set per app in the dashboard. */
    val replyWithin: String,
    val startButtons: List<StartButton>,
) {
    data class StartButton(val category: DevReplyCategory, val emoji: String, val title: String)

    companion object {
        /** Same as the server's defaults, so the first open looks final before the config arrives. */
        fun placeholder(appName: String) = MessengerConfig(
            appName = appName,
            teamName = appName,
            greeting = "Hi there 👋",
            intro = "Ask us anything, or tell us what's broken.",
            replyTime = "Usually replies within 3 working days",
            replyWithin = "3 working days",
            startButtons = listOf(DevReplyCategory.Bug, DevReplyCategory.Billing, DevReplyCategory.Idea, DevReplyCategory.Question)
                .map { StartButton(it, "", it.defaultTitle) },
        )

        /** Every field is optional on the wire; missing ones fall back to the placeholder. */
        fun parse(json: JSONObject, appName: String): MessengerConfig {
            val d = placeholder(appName)
            val name = json.str("app_name") ?: d.appName
            val buttons = json.optJSONArray("start_buttons")?.lossy { b ->
                StartButton(
                    category = DevReplyCategory.fromWire(b.getString("category")) ?: DevReplyCategory.Other,
                    emoji = b.str("emoji") ?: "",
                    title = b.getString("title"),
                )
            }
            return MessengerConfig(
                appName = name,
                teamName = json.str("team_name") ?: name,
                greeting = json.str("greeting") ?: d.greeting,
                intro = json.str("intro") ?: d.intro,
                replyTime = json.str("reply_time") ?: d.replyTime,
                replyWithin = json.str("reply_within") ?: d.replyWithin,
                startButtons = buttons ?: d.startButtons,
            )
        }
    }
}

internal data class Conversation(
    val id: UUID,
    val status: String,
    val category: DevReplyCategory?,
    val lastText: String?,
    val lastAuthor: String?,
    val unread: Int,
    val lastMessageAt: Instant,
) {
    val isClosed: Boolean get() = status == "closed"

    companion object {
        fun parse(json: JSONObject) = Conversation(
            id = UUID.fromString(json.getString("id")),
            lastMessageAt = parseRfc3339(json.getString("last_message_at")) ?: error("bad date"),
            status = json.str("status") ?: "open",
            category = DevReplyCategory.fromWire(json.str("category")),
            lastText = json.str("last_text"),
            lastAuthor = json.str("last_author"),
            unread = json.int("unread") ?: 0,
        )
    }
}

internal data class Message(
    val id: UUID,
    val author: Author,
    val blocks: List<Block>,
    val createdAt: Instant,
) {
    enum class Author { User, Admin, Agent, System }

    val isFromUser: Boolean get() = author == Author.User

    /** All blocks as plain text: what the list preview and older renderers show. */
    val plainText: String get() = blocks.joinToString("\n") { it.plainText }

    companion object {
        fun parse(json: JSONObject) = Message(
            id = UUID.fromString(json.getString("id")),
            createdAt = parseRfc3339(json.getString("created_at")) ?: error("bad date"),
            // Authors added later (e.g. a bot) show as the team, never as the user.
            author = when (json.str("author")) {
                "user" -> Author.User
                "admin" -> Author.Admin
                "agent" -> Author.Agent
                else -> Author.System
            },
            blocks = json.optJSONArray("blocks").lossy(Block::parse),
        )
    }
}

/**
 * A typed message block (spec 05). Unknown types, and types newer than this SDK, fall back to
 * plain text instead of failing, so old SDKs never break.
 */
internal sealed interface Block {
    data class Text(val text: String) : Block
    data class Image(val url: String, val width: Int?, val height: Int?) : Block
    data class File(val url: String, val name: String, val size: Int?, val mime: String?) : Block
    data class Unsupported(val fallback: String) : Block

    val plainText: String
        get() = when (this) {
            is Text -> text
            is Image -> "Photo"
            is File -> name
            is Unsupported -> fallback
        }

    companion object {
        fun parse(json: JSONObject): Block {
            val fallback = json.str("fallback") ?: "This message needs a newer version of the app."
            val minSdk = json.str("min_sdk")
            if (minSdk != null && isVersion(DEVREPLY_SDK_VERSION, olderThan = minSdk)) return Unsupported(fallback)
            return when (json.str("type")) {
                "text" -> Text(json.str("text") ?: fallback)
                "image" -> json.str("url")?.let { Image(it, json.int("width"), json.int("height")) } ?: Unsupported("Photo")
                "file" -> json.str("url")?.let {
                    File(it, json.str("name") ?: "File", json.int("size"), json.str("mime"))
                } ?: Unsupported(fallback)
                else -> Unsupported(fallback)
            }
        }

        fun isVersion(a: String, olderThan: String): Boolean {
            val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
            val pb = olderThan.split(".").map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val x = pa.getOrElse(i) { 0 }
                val y = pb.getOrElse(i) { 0 }
                if (x != y) return x < y
            }
            return false
        }
    }
}

internal data class MessagesPage(val conversation: Conversation, val messages: List<Message>) {
    companion object {
        fun parse(json: JSONObject) = MessagesPage(
            Conversation.parse(json.getJSONObject("conversation")),
            json.optJSONArray("messages").lossy(Message::parse),
        )
    }
}

internal data class StartedConversation(val conversation: Conversation, val message: Message)

/** What the end user told us about themselves. */
internal data class Profile(val name: String?, val email: String?) {
    companion object {
        fun parse(json: JSONObject) = Profile(json.str("name"), json.str("email"))
    }
}

/** A photo or file ready to upload. */
internal class OutgoingAttachment(
    val kind: String,
    val mime: String,
    val data: ByteArray,
    val filename: String?,
    val width: Int?,
    val height: Int?,
)

internal data class UploadSlot(val id: String, val uploadUrl: String, val uploadHeaders: Map<String, String>)

/**
 * Custom attribute values as JSON: text, number or true/false; null removes one.
 * Whole numbers go as integers, like the iOS SDK. Anything else is dropped.
 */
internal fun attributesJson(attributes: Map<String, Any?>): JSONObject {
    val out = JSONObject()
    for ((key, value) in attributes) {
        when (value) {
            null -> out.put(key, JSONObject.NULL)
            is String, is Boolean -> out.put(key, value)
            is Int, is Long, is Short, is Byte -> out.put(key, (value as Number).toLong())
            is Number -> {
                val d = value.toDouble()
                if (d.isNaN() || d.isInfinite()) continue
                if (d == Math.rint(d) && Math.abs(d) < 1e15) out.put(key, d.toLong()) else out.put(key, d)
            }
        }
    }
    return out
}
