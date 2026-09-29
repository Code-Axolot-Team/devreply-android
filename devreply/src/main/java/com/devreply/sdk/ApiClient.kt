package com.devreply.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

internal sealed class DevReplyError(message: String) : Exception(message) {
    /** The install token was rejected (revoked, or the server lost it). Register again. */
    object Unauthenticated : DevReplyError("unauthenticated")
    /** The public key is unknown, revoked, or for another platform. */
    object InvalidPublicKey : DevReplyError("invalid public key")
    object NotFound : DevReplyError("not found")
    class Invalid(message: String) : DevReplyError(message)
    class Server(val status: Int) : DevReplyError("server $status")
    /** The server can't do this right now (e.g. attachments not enabled). */
    object Unavailable : DevReplyError("unavailable")
    object Network : DevReplyError("network")
}

/** The public API (spec 07): `pk_` registers an install, the `it_` token does everything else. */
internal class ApiClient(val baseUrl: String) {

    suspend fun registerInstall(publicKey: String, device: DeviceInfo): String {
        val body = device.json().put("public_key", publicKey)
        val json = try {
            send("POST", "v1/installs", token = null, body = body)
        } catch (e: DevReplyError.Unauthenticated) {
            throw DevReplyError.InvalidPublicKey
        } catch (e: DevReplyError.Invalid) {
            // e.g. "this public key is for ios, not android"
            throw DevReplyError.InvalidPublicKey
        }
        return (json as JSONObject).getString("token")
    }

    suspend fun config(token: String): JSONObject = send("GET", "v1/messenger/config", token) as JSONObject

    suspend fun conversations(token: String): List<Conversation> =
        (send("GET", "v1/conversations", token) as? JSONArray).lossy(Conversation::parse)

    suspend fun startConversation(
        token: String, category: DevReplyCategory?, text: String, attachments: List<String>,
    ): StartedConversation {
        val json = send("POST", "v1/conversations", token, messageBody(text, category, attachments)) as JSONObject
        return StartedConversation(
            Conversation.parse(json.getJSONObject("conversation")),
            Message.parse(json.getJSONObject("message")),
        )
    }

    suspend fun messages(token: String, conversation: UUID): MessagesPage =
        MessagesPage.parse(send("GET", "v1/conversations/$conversation/messages", token) as JSONObject)

    suspend fun sendMessage(token: String, conversation: UUID, text: String, attachments: List<String>): Message =
        Message.parse(send("POST", "v1/conversations/$conversation/messages", token, messageBody(text, null, attachments)) as JSONObject)

    /** The chat's language changed (setLocale, or the device's): the team sees it next to the user. */
    suspend fun updateLocale(token: String, locale: String) {
        send("PATCH", "v1/install", token, JSONObject().put("locale", locale))
    }

    /** The device's FCM token, so replies reach it as pushes (the app forwards it: DevReply.registerPush). */
    suspend fun updatePushToken(token: String, pushToken: String) {
        send("PATCH", "v1/install", token, JSONObject().put("push_token", pushToken))
    }

    /** `DevReply.logout()`: this install's token and push token stop working. */
    suspend fun logout(token: String) {
        send("POST", "v1/logout", token)
    }

    /** `DevReply.deleteUser()`: the user's personal data, conversations and files are deleted. */
    suspend fun deleteUser(token: String) {
        send("DELETE", "v1/me", token)
    }

    /** A tap on a DevReply notification opened its conversation: the dashboard shows taps work. */
    suspend fun pushOpened(token: String) {
        send("POST", "v1/push_opened", token)
    }

    /** The app opened a DevReply link: the dashboard shows the deep link works. */
    suspend fun deepLinkOpened(token: String) {
        send("POST", "v1/deep_link_opened", token)
    }

    suspend fun profile(token: String): Profile = Profile.parse(send("GET", "v1/me", token) as JSONObject)

    suspend fun updateProfile(
        token: String, name: String?, email: String?, attributes: Map<String, Any?> = emptyMap(), userId: String? = null,
    ): Profile {
        val body = JSONObject()
        if (name != null) body.put("name", name)
        if (email != null) body.put("email", email)
        if (userId != null) body.put("user_id", userId)
        // null values reach the server as JSON null (= remove the attribute).
        body.put("attributes", attributesJson(attributes))
        return Profile.parse(send("PATCH", "v1/me", token, body) as JSONObject)
    }

    /** Asks the server for a one-time upload URL, then uploads straight to storage (no Firebase SDK needed). */
    suspend fun upload(token: String, attachment: OutgoingAttachment): String {
        val request = JSONObject()
            .put("kind", attachment.kind)
            .put("mime", attachment.mime)
            .put("size", attachment.data.size)
            .put("width", attachment.width ?: JSONObject.NULL)
            .put("height", attachment.height ?: JSONObject.NULL)
            .put("filename", attachment.filename ?: JSONObject.NULL)
        val json = send("POST", "v1/attachments", token, request) as JSONObject
        val headers = json.optJSONObject("upload_headers")
        val slot = UploadSlot(
            id = json.getString("id"),
            uploadUrl = json.getString("upload_url"),
            uploadHeaders = headers?.keys()?.asSequence()?.associateWith { headers.getString(it) } ?: emptyMap(),
        )
        withContext(Dispatchers.IO) {
            val status = try {
                val c = URL(slot.uploadUrl).openConnection() as HttpURLConnection
                try {
                    c.requestMethod = "PUT"
                    c.connectTimeout = 20_000
                    c.readTimeout = 60_000
                    c.doOutput = true
                    c.setFixedLengthStreamingMode(attachment.data.size)
                    slot.uploadHeaders.forEach { (k, v) -> c.setRequestProperty(k, v) }
                    c.outputStream.use { it.write(attachment.data) }
                    c.responseCode
                } finally {
                    c.disconnect()
                }
            } catch (e: IOException) {
                throw DevReplyError.Network
            }
            if (status !in 200..299) throw DevReplyError.Server(status)
        }
        return slot.id
    }

    private fun messageBody(text: String, category: DevReplyCategory?, attachments: List<String>) =
        JSONObject()
            .put("text", text)
            .put("category", category?.wire ?: JSONObject.NULL)
            .put("attachment_ids", JSONArray(attachments))

    /** Returns the parsed JSON body (object or array). */
    private suspend fun send(method: String, path: String, token: String?, body: JSONObject? = null): Any =
        withContext(Dispatchers.IO) {
            val (status, text) = try {
                val c = URL(baseUrl.trimEnd('/') + "/" + path).openConnection() as HttpURLConnection
                try {
                    c.requestMethod = method // Android's HttpURLConnection (OkHttp inside) supports PATCH
                    c.connectTimeout = 20_000
                    c.readTimeout = 20_000
                    c.setRequestProperty("Accept", "application/json")
                    c.setRequestProperty("User-Agent", "devreply-android/$DEVREPLY_SDK_VERSION")
                    if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
                    if (body != null) {
                        val bytes = body.toString().toByteArray()
                        c.doOutput = true
                        c.setRequestProperty("Content-Type", "application/json")
                        c.setFixedLengthStreamingMode(bytes.size)
                        c.outputStream.use { it.write(bytes) }
                    }
                    val code = c.responseCode
                    val stream = if (code in 200..299) c.inputStream else c.errorStream
                    code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
                } finally {
                    c.disconnect()
                }
            } catch (e: IOException) {
                throw DevReplyError.Network
            }
            when (status) {
                in 200..299 -> try {
                    val trimmed = text.trimStart()
                    if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed.ifEmpty { "{}" })
                } catch (e: Exception) {
                    throw DevReplyError.Server(status)
                }
                401 -> throw DevReplyError.Unauthenticated
                404 -> throw DevReplyError.NotFound
                503 -> throw DevReplyError.Unavailable
                400 -> throw DevReplyError.Invalid(
                    runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrDefault("Invalid request"),
                )
                else -> throw DevReplyError.Server(status)
            }
        }
}
