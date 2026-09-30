package com.devreply.sdk.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.devreply.sdk.Answer
import com.devreply.sdk.Block
import com.devreply.sdk.Conversation
import com.devreply.sdk.DevReplyCategory
import com.devreply.sdk.DevReplyError
import com.devreply.sdk.DevReplyEvent
import com.devreply.sdk.EventHub
import com.devreply.sdk.Events
import com.devreply.sdk.Message
import com.devreply.sdk.Messenger
import com.devreply.sdk.OutgoingAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A photo or file picked by the user, ready to upload. Photos are resized and re-encoded as JPEG
 * (which drops EXIF, including location); files go as they are, up to 10 MB.
 */
internal class Staged(val attachment: OutgoingAttachment, val preview: ImageBitmap?) {
    val id: UUID = UUID.randomUUID()
    val name: String get() = attachment.filename ?: com.devreply.sdk.t("photo")

    sealed interface Problem {
        data object Unreadable : Problem
        data class TooBig(val name: String) : Problem
    }

    companion object {
        const val MAX_PHOTO_SIDE = 2048
        const val MAX_FILE_BYTES = 10 * 1024 * 1024

        fun photo(bytes: ByteArray): Staged? = runCatching {
            val bitmap = decodeScaled(bytes) ?: return null
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
            val attachment = OutgoingAttachment("image", "image/jpeg", out.toByteArray(), null, bitmap.width, bitmap.height)
            Staged(attachment, bitmap.asImageBitmap())
        }.getOrNull()

        /** From the photo picker or the file picker. Images picked as files still go as photos. */
        fun from(context: Context, uri: Uri): Result<Staged> {
            val resolver = context.contentResolver
            var name = uri.lastPathSegment ?: "file"
            var size = -1L
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.let { name = it }
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
            }
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            if (!mime.startsWith("image/") && size > MAX_FILE_BYTES) return Result.failure(ProblemException(Problem.TooBig(name)))
            val bytes = runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                ?: return Result.failure(ProblemException(Problem.Unreadable))
            if (mime.startsWith("image/")) photo(bytes)?.let { return Result.success(it) }
            if (bytes.size > MAX_FILE_BYTES) return Result.failure(ProblemException(Problem.TooBig(name)))
            return Result.success(Staged(OutgoingAttachment("file", mime, bytes, name, null, null), null))
        }

        /** Decodes at most [MAX_PHOTO_SIDE] on the long side, upright (ImageDecoder applies EXIF rotation). */
        private fun decodeScaled(bytes: ByteArray): Bitmap? {
            if (Build.VERSION.SDK_INT >= 28) {
                return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                    val scale = min(1f, MAX_PHOTO_SIDE.toFloat() / max(info.size.width, info.size.height))
                    decoder.setTargetSize((info.size.width * scale).roundToInt(), (info.size.height * scale).roundToInt())
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_PHOTO_SIDE) sample *= 2
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val scale = min(1f, MAX_PHOTO_SIDE.toFloat() / max(bitmap.width, bitmap.height))
            if (scale >= 1f) return bitmap
            return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt(), (bitmap.height * scale).roundToInt(), true)
        }
    }

    class ProblemException(val problem: Problem) : Exception()
}

/** One conversation. Starts empty for a new one; the first send creates it on the server. */
internal class ConversationModel(existingId: UUID?, category: DevReplyCategory?) {
    data class Pending(
        val text: String,
        val attachments: List<Staged>,
        val failure: String? = null,
        val id: UUID = UUID.randomUUID(),
        /** A tap on a buttons question's option: sent with the message (0.5.0). */
        val answer: Answer? = null,
    )

    var conversationId by mutableStateOf(existingId)
        private set
    var conversation by mutableStateOf<Conversation?>(existingId?.let { Messenger.conversation(it) })
        private set
    val category: DevReplyCategory? = conversation?.category ?: category
    var messages by mutableStateOf(emptyList<Message>())
        private set
    var pending by mutableStateOf(emptyList<Pending>())
        private set
    var sentCount by mutableIntStateOf(0)
        private set

    suspend fun load() {
        val id = conversationId ?: return
        val page = runCatching { Messenger.authorized { api, t -> api.messages(t, id) } }.getOrNull() ?: return
        conversation = page.conversation
        // Live messages that arrived while this request was out stay (the next poll has them too).
        val merged = messages.filter { m -> page.messages.none { it.id == m.id } && m.createdAt > (page.messages.lastOrNull()?.createdAt ?: java.time.Instant.MIN) }
            .fold(page.messages) { list, m -> com.devreply.sdk.mergeMessage(list, m) }
        if (merged != messages) messages = merged
        page.messages.forEach { com.devreply.sdk.LiveUpdates.saw(it.id) }
        Messenger.upsert(page.conversation)
    }

    /** A message from the live channel for this conversation: added, or replacing the one with its id. */
    fun receive(conversationId: UUID, message: Message) {
        if (conversationId != this.conversationId) return
        messages = com.devreply.sdk.mergeMessage(messages, message)
        // "Marked as resolved" and the like change the conversation itself: fetch it.
        if (message.author == Message.Author.System) Messenger.scope.launch { load() }
    }

    fun send(raw: String, attachments: List<Staged>) {
        val text = raw.trim()
        if (text.isEmpty() && attachments.isEmpty()) return
        val item = Pending(text, attachments)
        pending = pending + item
        sentCount++
        Messenger.scope.launch { sending.withLock { deliver(item) } }
    }

    /**
     * The option the user picked for the buttons question [question], or null while it's unanswered: from a
     * user message that answers it, else from an answer being sent (so the buttons settle at once).
     */
    fun chosenOption(question: UUID): String? = com.devreply.sdk.chosenOption(question, messages, pending.mapNotNull { it.answer })

    /** A tap on an option: sends its label as the user's message, with the answer. Once per question. */
    fun answer(question: Message, option: Block.Buttons.Option) {
        if (chosenOption(question.id) != null) return
        val item = Pending(option.label, emptyList(), answer = Answer(question.id, option.id))
        pending = pending + item
        sentCount++
        Messenger.scope.launch { sending.withLock { deliver(item) } }
    }

    fun retry(item: Pending) {
        if (pending.none { it.id == item.id }) return
        val fresh = item.copy(failure = null)
        pending = pending.map { if (it.id == item.id) fresh else it }
        Messenger.scope.launch { sending.withLock { deliver(fresh) } }
    }

    /** Sends go out one at a time, in the order typed: two in flight could reach the server swapped. */
    private val sending = Mutex()

    private suspend fun deliver(item: Pending) {
        try {
            val ids = item.attachments.map { staged ->
                Messenger.authorized { api, t -> api.upload(t, staged.attachment) }
            }
            val id = conversationId
            if (id != null) {
                val message = Messenger.authorized { api, t -> api.sendMessage(t, id, item.text, ids, item.answer) }
                // The live channel may have brought it already: never twice.
                messages = com.devreply.sdk.mergeMessage(messages, message)
                com.devreply.sdk.LiveUpdates.saw(message.id)
                Events.emit(DevReplyEvent.MessageSent(id.toString()))
            } else {
                // The app's context (DevReply.present) goes with the first conversation of this presentation only.
                val context = Messenger.presentContext
                val started = Messenger.authorized { api, t -> api.startConversation(t, category, item.text, ids, context) }
                Messenger.presentationConversationStarted()
                conversationId = started.conversation.id
                conversation = started.conversation
                messages = com.devreply.sdk.mergeMessage(messages, started.message)
                com.devreply.sdk.LiveUpdates.saw(started.message.id)
                Messenger.upsert(started.conversation)
                EventHub.firstMessage(started.conversation.id.toString(), started.conversation.category ?: category)
                    .forEach(Events::emit)
            }
            pending = pending.filter { it.id != item.id }
        } catch (e: DevReplyError.Server) {
            if (e.status == 409 && item.answer != null) {
                // Answered already (on another device, or a tap that got through before): show what the server has.
                pending = pending.filter { it.id != item.id }
                load()
            } else {
                failed(item, e)
            }
        } catch (e: Exception) {
            failed(item, e)
        }
    }

    /** Shows why [item] didn't go; a tap on it tries again. */
    private fun failed(item: Pending, e: Exception) {
        val reason = when (e) {
            is DevReplyError.Unavailable -> com.devreply.sdk.t("failed.attachments")
            is DevReplyError.Network -> com.devreply.sdk.t("failed.offline")
            is DevReplyError.Invalid -> com.devreply.sdk.t("failed.reason", "reason" to e.message)
            else -> com.devreply.sdk.t("failed.generic")
        }
        pending = pending.map { if (it.id == item.id) it.copy(failure = reason) else it }
    }
}

/** Loads an attachment, avatar or app icon once per URL and keeps it in memory, so polling doesn't reload it. */
internal object ImageCache {
    private val cache = android.util.LruCache<String, ImageBitmap>(48)

    fun cached(url: String): ImageBitmap? = cache.get(url)

    suspend fun image(url: String): ImageBitmap? {
        cache.get(url)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = java.net.URL(url).openStream().use { it.readBytes() }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        } ?: return null
        cache.put(url, bitmap)
        return bitmap
    }
}
