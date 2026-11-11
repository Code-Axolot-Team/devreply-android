package com.devreply.sdk

import com.devreply.sdk.ui.defaultTitle
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/** Version of this SDK. Sent on install registration and compared with each block's `min_sdk`. */
public const val DEVREPLY_SDK_VERSION: String = "0.6.0"

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

/** Who users see replying: a teammate or a shared persona (spec 05, 0.4.0). */
internal data class Persona(val name: String, val title: String, val avatarUrl: String?) {
    companion object {
        /** Null when there's no usable name: the message then shows without a label, as before 0.4. */
        fun parse(json: JSONObject?): Persona? {
            if (json == null) return null
            val name = json.str("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return Persona(name, json.str("title")?.trim() ?: "", json.str("avatar_url")?.takeIf { it.isNotBlank() })
        }
    }
}

internal data class MessengerConfig(
    val appName: String,
    val teamName: String,
    val greeting: String,
    val intro: String,
    val replyTime: String,
    /** What goes after "Please allow up to": "3 working days", "an hour". Set per app in the dashboard. */
    val replyWithin: String,
    val startButtons: List<StartButton>,
    /** The app's icon, uploaded in the dashboard. Null: initials. */
    val appIconUrl: String? = null,
    /** Teammates with a photo, shown on the home screen (up to 3). */
    val team: List<Persona> = emptyList(),
    /** Fields still at DevReply's defaults: shown in the user's language (0.4). */
    val localize: Set<String> = emptySet(),
    /** The reply-time preset, e.g. `3_working_days`: translated (0.4). */
    val replyWithinKey: String? = null,
    /** False: the team switched the chat off in the dashboard (0.4.4). Missing counts as on. */
    val enabled: Boolean = true,
    /**
     * The app's own deep-link schemes (0.6.0; Settings → deep link), e.g. `kneefit`: link buttons, gifts and
     * Markdown links may open them, as well as https. Lowercase, without `://`.
     */
    val linkSchemes: List<String> = emptyList(),
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
            localize = setOf("greeting", "intro", "start_buttons", "reply_time", "reply_within"),
            replyWithinKey = "3_working_days",
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
                appIconUrl = json.str("app_icon_url")?.takeIf { it.isNotBlank() },
                team = json.optJSONArray("team").lossy { Persona.parse(it) ?: error("no name") }.take(3),
                // A server before 0.4 sends neither: its English texts are shown as they are.
                localize = json.optJSONArray("localize")?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String }.toSet() }
                    ?: emptySet(),
                replyWithinKey = json.str("reply_within_key"),
                enabled = json.opt("enabled") != false,
                linkSchemes = LinkPolicy.schemes(json.optJSONArray("link_schemes")),
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

/**
 * Home's top card (0.5.1): the newest conversation whose last message is a team reply the user hasn't
 * read, so an answer is never missed below the start tiles.
 */
internal fun List<Conversation>.newestUnreadReply(): Conversation? =
    filter { it.unread > 0 && it.lastAuthor != "user" && it.lastAuthor != "system" }
        .maxByOrNull { it.lastMessageAt }

/**
 * What a start tile opens (0.5.1): the user's newest open conversation in that topic, if any (one without
 * a topic counts as Other); null starts a new one.
 */
internal fun List<Conversation>.openIn(category: DevReplyCategory): Conversation? =
    filter { !it.isClosed && (it.category ?: DevReplyCategory.Other) == category }.maxByOrNull { it.lastMessageAt }

internal data class Message(
    val id: UUID,
    val author: Author,
    val blocks: List<Block>,
    val createdAt: Instant,
    /** Who replied (team messages from 0.4 on). Null: no label. */
    val persona: Persona? = null,
    /** A user message that answers a buttons question (0.5.0): which question, which option. */
    val answer: Answer? = null,
    /** An offer or campaign from the team (0.6.0): shows the Offer tag and "Don't send me offers". */
    val promotional: Boolean = false,
    /** A user message that answers a form (0.6.0): which form, the answers. */
    val formAnswer: FormAnswer? = null,
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
            persona = if (json.str("author") == "user") null else runCatching { Persona.parse(json.optJSONObject("persona")) }.getOrNull(),
            // On the message's text block (or the message itself); only the user's messages answer.
            answer = if (json.str("author") != "user") null else Answer.parse(json.optJSONObject("answer"))
                ?: json.optJSONArray("blocks")?.let { a -> (0 until a.length()).firstNotNullOfOrNull { Answer.parse(a.optJSONObject(it)?.optJSONObject("answer")) } },
            // Only the team's messages are offers.
            promotional = json.str("author") != "user" && json.opt("promotional") == true,
            formAnswer = if (json.str("author") != "user") null else FormAnswer.parse(json.optJSONObject("form_answer"))
                ?: json.optJSONArray("blocks")?.let { a -> (0 until a.length()).firstNotNullOfOrNull { FormAnswer.parse(a.optJSONObject(it)?.optJSONObject("form_answer")) } },
        )
    }
}

/** A tap on a button of a buttons question (spec 05, 0.5.0): sent with the user's message, read back from it. */
internal data class Answer(val messageId: UUID, val optionId: String) {
    fun json(): JSONObject = JSONObject().put("message_id", messageId.toString()).put("option_id", optionId)

    companion object {
        fun parse(json: JSONObject?): Answer? {
            if (json == null) return null
            val id = json.str("message_id")?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
            val option = json.str("option_id")?.takeIf { it.isNotEmpty() } ?: return null
            return Answer(id, option)
        }
    }
}

/**
 * The option chosen for the buttons question [question]: from a user message that answers it, else from an
 * answer still being sent ([sending]). Null: not answered yet (the buttons are live).
 */
internal fun chosenOption(question: UUID, messages: List<Message>, sending: List<Answer> = emptyList()): String? =
    messages.firstNotNullOfOrNull { m -> m.answer?.takeIf { m.isFromUser && it.messageId == question }?.optionId }
        ?: sending.firstOrNull { it.messageId == question }?.optionId

/**
 * A typed message block (spec 05). Unknown types, and types newer than this SDK, fall back to
 * plain text instead of failing, so old SDKs never break.
 */
internal sealed interface Block {
    /** [key]: a text the SDK says in the user's language (`resolved`), from 0.4 servers. */
    data class Text(val text: String, val key: String? = null) : Block
    data class Image(val url: String, val width: Int?, val height: Int?) : Block
    data class File(val url: String, val name: String, val size: Int?, val mime: String?) : Block
    data class Unsupported(val fallback: String) : Block

    /**
     * A team or agent reply in DevReply Markdown (0.5.0), parsed once when decoded. [fallback] is its plain
     * text (the server's, or the same parse's): what previews and anything that can't show formatting use.
     */
    data class Markdown(val text: String, val nodes: List<com.devreply.sdk.Markdown.Node>, val fallback: String) : Block

    /**
     * A question from the team with 2 to 5 answers as buttons (0.5.0). [text] is the question in Markdown,
     * parsed once; [question] its plain text, what previews show. [fallback]: the server's text version.
     */
    data class Buttons(
        val text: String,
        val nodes: List<com.devreply.sdk.Markdown.Node>,
        val question: String,
        val options: List<Option>,
        val fallback: String,
    ) : Block {
        data class Option(val id: String, val label: String)
    }

    /**
     * 1 to 3 buttons that open a link (0.6.0): the app's own deep link or https. [text]: an optional intro in
     * Markdown ([nodes], parsed once). A tap is reported (`POST …/taps`) and the URL opened by the OS.
     */
    data class Links(
        val text: String?,
        val nodes: List<com.devreply.sdk.Markdown.Node>,
        val links: List<Link>,
        val fallback: String,
    ) : Block {
        data class Link(val id: String, val label: String, val url: String)
    }

    /** A gift (0.6.0): a card with "Open gift", which reports the opening and opens [url]; past [expiresAt], expired. */
    data class Gift(
        val giftId: String,
        val title: String,
        val text: String,
        val url: String,
        val expiresAt: Instant?,
        val fallback: String,
    ) : Block {
        fun isExpired(now: Instant = Instant.now()): Boolean = expiresAt != null && !now.isBefore(expiresAt)
    }

    /** Several questions answered in one go (0.6.0): single choice, multiple choice, free text. */
    data class Form(
        val text: String?,
        val nodes: List<com.devreply.sdk.Markdown.Node>,
        val questions: List<Question>,
        val fallback: String,
    ) : Block {
        enum class Kind { Single, Multi, Text }

        data class Question(
            val id: String,
            val text: String,
            val kind: Kind,
            val options: List<Buttons.Option>,
            val required: Boolean,
        )
    }

    val plainText: String
        get() = when (this) {
            is Text -> text
            is Image -> t("photo")
            is File -> name
            is Unsupported -> fallback
            is Markdown -> fallback
            is Buttons -> question
            is Links -> fallback
            is Gift -> fallback
            is Form -> fallback
        }

    companion object {
        /** [sdk]: the version compared with `min_sdk` (this SDK's; tests pass another). */
        /** [schemes]: the app's deep-link schemes Markdown links may open (from the config; tests pass others). */
        fun parse(json: JSONObject, sdk: String = DEVREPLY_SDK_VERSION, schemes: List<String> = LinkPolicy.appSchemes): Block {
            val fallback = json.str("fallback") ?: t("unsupported")
            val minSdk = json.str("min_sdk")
            if (minSdk != null && isVersion(sdk, olderThan = minSdk)) return Unsupported(fallback)
            return when (json.str("type")) {
                "text" -> Text(json.str("text") ?: fallback, json.str("key"))
                // The user's own messages stay plain text; this is for the team's and agents' replies.
                "markdown" -> json.str("text")?.let { md ->
                    runCatching {
                        val nodes = com.devreply.sdk.Markdown.parse(md, schemes)
                        Markdown(md, nodes, json.str("fallback") ?: com.devreply.sdk.Markdown.plain(nodes))
                    }.getOrNull()
                } ?: Unsupported(json.str("fallback") ?: json.str("text") ?: fallback)
                "buttons" -> buttons(json, schemes) ?: Unsupported(json.str("fallback") ?: json.str("text") ?: fallback)
                "links" -> links(json, schemes) ?: Unsupported(fallback)
                "gift" -> gift(json) ?: Unsupported(fallback)
                "form" -> form(json, schemes) ?: Unsupported(fallback)
                "image" -> json.str("url")?.let { Image(it, json.int("width"), json.int("height")) } ?: Unsupported(t("photo"))
                "file" -> json.str("url")?.let {
                    File(it, json.str("name") ?: t("file"), json.int("size"), json.str("mime"))
                } ?: Unsupported(fallback)
                else -> Unsupported(fallback)
            }
        }

        /** A question with 2 to 5 usable options (an id and a label each; the first of a repeated id), else null. */
        private fun buttons(json: JSONObject, schemes: List<String>): Buttons? = runCatching {
            val md = json.str("text")?.takeIf { it.isNotBlank() } ?: return null
            val options = json.optJSONArray("options").lossy { o ->
                val id = o.str("id")?.takeIf { it.isNotEmpty() } ?: error("no id")
                Buttons.Option(id, o.str("label")?.trim()?.takeIf { it.isNotEmpty() } ?: error("no label"))
            }.distinctBy { it.id }.take(5)
            if (options.size < 2) return null
            val nodes = com.devreply.sdk.Markdown.parse(md, schemes)
            val question = com.devreply.sdk.Markdown.plain(nodes).ifBlank { md }
            Buttons(md, nodes, question, options, json.str("fallback") ?: question)
        }.getOrNull()

        /** The optional Markdown intro of a links block or a form: its text and nodes, or nulls. */
        private fun intro(json: JSONObject, schemes: List<String>): Pair<String?, List<com.devreply.sdk.Markdown.Node>> {
            val md = json.str("text")?.takeIf { it.isNotBlank() } ?: return null to emptyList()
            return md to com.devreply.sdk.Markdown.parse(md, schemes)
        }

        /** 1 to 3 links with an id, a label and a URL (the first of a repeated id), else null. */
        private fun links(json: JSONObject, schemes: List<String>): Links? = runCatching {
            val links = json.optJSONArray("links").lossy { l ->
                Links.Link(
                    id = l.str("id")?.takeIf { it.isNotEmpty() } ?: error("no id"),
                    label = l.str("label")?.trim()?.takeIf { it.isNotEmpty() } ?: error("no label"),
                    url = l.str("url")?.trim()?.takeIf { it.isNotEmpty() } ?: error("no url"),
                )
            }.distinctBy { it.id }.take(3)
            if (links.isEmpty()) return null
            val (md, nodes) = intro(json, schemes)
            val fallback = json.str("fallback")
                ?: (listOfNotNull(md) + links.map { "${it.label}: ${it.url}" }).joinToString("\n\n")
            Links(md, nodes, links, fallback)
        }.getOrNull()

        /** A gift with an id, a title and a URL, else null. A date that can't be read means no expiry. */
        private fun gift(json: JSONObject): Gift? = runCatching {
            val id = json.str("gift_id")?.takeIf { it.isNotBlank() && it.all { c -> c.isLetterOrDigit() || c in "-_" } } ?: return null
            val title = json.str("title")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val url = json.str("url")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val text = json.str("text")?.trim() ?: ""
            Gift(id, title, text, url, json.str("expires_at")?.let(::parseRfc3339), json.str("fallback") ?: "$title. $text".trim())
        }.getOrNull()

        /**
         * 1 to 5 questions (the first of a repeated id), each with a text and a kind this SDK knows; single and
         * multi need 2 to 6 options. Anything else: null (the fallback, answered in the user's own words).
         */
        private fun form(json: JSONObject, schemes: List<String>): Form? = runCatching {
            val raw = json.optJSONArray("questions") ?: return null
            val questions = (0 until raw.length()).map { i ->
                val q = raw.optJSONObject(i) ?: return null
                val kind = when (q.str("kind")) {
                    "single" -> Form.Kind.Single
                    "multi" -> Form.Kind.Multi
                    "text" -> Form.Kind.Text
                    else -> return null // a newer kind: the user couldn't answer it here
                }
                val options = if (kind == Form.Kind.Text) emptyList() else q.optJSONArray("options").lossy { o ->
                    val id = o.str("id")?.takeIf { it.isNotEmpty() } ?: error("no id")
                    Buttons.Option(id, o.str("label")?.trim()?.takeIf { it.isNotEmpty() } ?: error("no label"))
                }.distinctBy { it.id }.take(6)
                if (kind != Form.Kind.Text && options.size < 2) return null
                Form.Question(
                    id = q.str("id")?.takeIf { it.isNotEmpty() } ?: return null,
                    text = q.str("text")?.trim()?.takeIf { it.isNotEmpty() } ?: return null,
                    kind = kind,
                    options = options,
                    required = q.opt("required") == true,
                )
            }.distinctBy { it.id }
            if (questions.isEmpty() || questions.size > 5) return null
            val (md, nodes) = intro(json, schemes)
            val fallback = json.str("fallback") ?: (listOfNotNull(md) + questions.mapIndexed { i, q ->
                "${i + 1}. ${q.text}" + if (q.options.isEmpty()) "" else " " + q.options.joinToString(" / ") { it.label }
            }).joinToString("\n")
            Form(md, nodes, questions, fallback)
        }.getOrNull()

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

/**
 * What the end user told us about themselves. [restored] (0.5.0): `login` moved this install back to the
 * user it had before a logout on this device (spec 03, same device after logout): their conversations are back.
 */
internal data class Profile(
    val name: String?,
    val email: String?,
    val restored: Boolean = false,
    /** The user said "Don't send me offers" (0.6.0). */
    val noPromotions: Boolean = false,
) {
    companion object {
        fun parse(json: JSONObject) = Profile(
            json.str("name"), json.str("email"), json.opt("restored") == true, json.opt("no_promotions") == true,
        )
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

/** What `POST /v1/conversations` accepts as `context` (the same rules as attributes on the server). */
internal const val MAX_CONTEXT_VALUES = 20
internal const val MAX_ATTRIBUTE_TEXT = 500

/** 1–40 characters: ASCII letters, digits, `_ - .` or space (the server refuses anything else). */
internal fun isAttributeKey(key: String): Boolean =
    key.length in 1..40 && key.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "_-. " }

/**
 * The context `DevReply.present(…, attributes)` sends with a new conversation, or null when nothing is
 * left. The server refuses the whole request for one bad value, so the SDK drops what it would refuse:
 * bad keys, nulls, other types, NaN; text is cut at 500 characters; at most 20 values (in the map's order).
 */
internal fun contextJson(context: Map<String, Any?>): JSONObject? {
    val clean = LinkedHashMap<String, Any>()
    for ((key, value) in context) {
        if (clean.size == MAX_CONTEXT_VALUES) break
        if (!isAttributeKey(key)) continue
        clean[key] = when (value) {
            is String -> if (value.length <= MAX_ATTRIBUTE_TEXT) value else {
                val cut = value.take(MAX_ATTRIBUTE_TEXT)
                if (cut.last().isHighSurrogate()) cut.dropLast(1) else cut
            }
            is Boolean -> value
            is Number -> value.toDouble().takeIf { !it.isNaN() && !it.isInfinite() }?.let { value } ?: continue
            else -> continue
        }
    }
    return if (clean.isEmpty()) null else attributesJson(clean)
}
