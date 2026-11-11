package com.devreply.sdk

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * The user's answers to a form (spec 14, 0.6.0): sent once as `{"form_answer": {"message_id", "answers"}}`, read back
 * from the user's message that answers it. [answers] by question id: an option id (single), option ids (multi),
 * or the text the user wrote.
 */
internal data class FormAnswer(val messageId: UUID, val answers: Map<String, Any>) {
    fun json(): JSONObject {
        val out = JSONObject()
        for ((id, value) in answers) {
            when (value) {
                is String -> out.put(id, value)
                is List<*> -> out.put(id, JSONArray(value.filterIsInstance<String>()))
            }
        }
        return JSONObject().put("message_id", messageId.toString()).put("answers", out)
    }

    /** The option ids chosen for [question] (single or multi), empty when none. */
    fun chosen(question: String): List<String> = when (val v = answers[question]) {
        is String -> listOf(v)
        is List<*> -> v.filterIsInstance<String>()
        else -> emptyList()
    }

    fun text(question: String): String? = answers[question] as? String

    companion object {
        fun parse(json: JSONObject?): FormAnswer? {
            if (json == null) return null
            val id = json.str("message_id")?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
            val raw = json.optJSONObject("answers") ?: return null
            val answers = LinkedHashMap<String, Any>()
            for (key in raw.keys()) {
                when (val v = raw.opt(key)) {
                    is String -> answers[key] = v
                    is JSONArray -> answers[key] = (0 until v.length()).mapNotNull { v.opt(it) as? String }
                }
            }
            return FormAnswer(id, answers)
        }
    }
}

/** What the form card works with before Send: checks and the request it becomes. */
internal object Forms {
    /** Free text is cut here (the server's message limit leaves room for the other answers). */
    const val MAX_TEXT = 2_000

    /**
     * Only answers the server accepts: known questions, known option ids (multi in the form's order, no repeats),
     * free text trimmed. Empty ones are left out (unanswered).
     */
    fun clean(form: Block.Form, draft: Map<String, Any>): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        for (q in form.questions) {
            val value = draft[q.id] ?: continue
            when (q.kind) {
                Block.Form.Kind.Single -> (value as? String)?.takeIf { v -> q.options.any { it.id == v } }?.let { out[q.id] = it }
                Block.Form.Kind.Multi -> {
                    val picked = (value as? Collection<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
                    val ids = q.options.map { it.id }.filter { it in picked }
                    if (ids.isNotEmpty()) out[q.id] = ids
                }
                Block.Form.Kind.Text -> (value as? String)?.trim()?.take(MAX_TEXT)?.takeIf { it.isNotEmpty() }?.let { out[q.id] = it }
            }
        }
        return out
    }

    /** Required questions still without an answer, in the form's order. */
    fun missing(form: Block.Form, draft: Map<String, Any>): List<String> {
        val answered = clean(form, draft)
        return form.questions.filter { it.required && it.id !in answered }.map { it.id }
    }

    /** What the user's message shows while it's being sent (the server writes its own summary). */
    fun summary(form: Block.Form, answers: Map<String, Any>): String =
        form.questions.mapNotNull { q ->
            val value = answers[q.id] ?: return@mapNotNull null
            val shown = when (q.kind) {
                Block.Form.Kind.Text -> value as? String
                else -> {
                    val ids = (value as? String)?.let { listOf(it) } ?: (value as? List<*>)?.filterIsInstance<String>() ?: emptyList()
                    q.options.filter { it.id in ids }.joinToString(", ") { it.label }
                }
            }
            shown?.takeIf { it.isNotEmpty() }?.let { "${q.text}\n$it" }
        }.joinToString("\n\n")

    /**
     * The answers to the form [form] (a message id): from a user message that answers it, else from answers still
     * being sent ([sending]). Null: not answered yet (the form is live).
     */
    fun answered(form: UUID, messages: List<Message>, sending: List<FormAnswer> = emptyList()): FormAnswer? =
        messages.firstNotNullOfOrNull { m -> m.formAnswer?.takeIf { m.isFromUser && it.messageId == form } }
            ?: sending.firstOrNull { it.messageId == form }
}
