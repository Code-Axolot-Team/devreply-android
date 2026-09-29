package com.devreply.sdk

import android.content.Context
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.UUID

/** App-wide messenger state: the install, the config, the user's conversations. Compose reads it directly. */
internal object Messenger {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var appContext: Context? = null
    private var publicKey: String? = null
    var client: ApiClient? = null
        private set
    private var tokens: TokenStore? = null

    var config by mutableStateOf(MessengerConfig.placeholder(""))
        private set
    var conversations by mutableStateOf(emptyList<Conversation>())
        private set
    var lastError by mutableStateOf<DevReplyError?>(null)
        private set
    var profile by mutableStateOf<Profile?>(null)
        private set

    /** The messenger is on screen / this conversation is on screen. Compose state: the unread bubble reads it. */
    var isPresented by mutableStateOf(false)
    var visibleConversation: UUID? = null

    val unreadCount by derivedStateOf { conversations.sumOf { it.unread } }

    /** A name is required before the first message (spec 05), unless the app supplied one. */
    val needsName: Boolean get() = profile?.name.isNullOrBlank()

    private val registering = Mutex()
    private var cachedToken: String? = null
    private var backoff = RegistrationBackoff()

    fun configure(context: Context, publicKey: String, apiUrl: String) {
        val app = context.applicationContext
        appContext = app
        if (publicKey != this.publicKey) backoff = RegistrationBackoff()
        this.publicKey = publicKey
        client = ApiClient(apiUrl)
        tokens = TokenStore(app)
        cachedToken = null
        conversations = emptyList()
        val appName = app.applicationInfo.loadLabel(app.packageManager).toString()
        config = cachedConfig(app, publicKey, appName) ?: MessengerConfig.placeholder(appName)
        (app as? android.app.Application)?.let { AppWatcher.start(it, context as? android.app.Activity) }
        // Register early so the first open is instant, and pick up unread replies.
        scope.launch {
            hostUser?.let { (name, email) -> runCatching { saveProfile(name, email) } }
            flushAttributes()
            refresh()
            syncLocale()
        }
    }

    /** The app's language choice ([DevReply.setLocale]); null follows the device. */
    fun setLocale(tag: String?) {
        L10n.override = tag?.trim()?.replace('_', '-')?.takeIf { it.isNotEmpty() }
        if (client != null) scope.launch { syncLocale() }
    }

    private fun localePrefs() = appContext?.getSharedPreferences("devreply", Context.MODE_PRIVATE)

    /** Tells the server the chat's language when it differs from what it last heard (no push fields). */
    private suspend fun syncLocale() {
        val account = account ?: return
        val tag = L10n.tag
        val prefs = localePrefs()
        if (prefs?.getString("locale:$account", null) == tag) return
        runCatching { authorized { api, t -> api.updateLocale(t, tag) } }
            .onSuccess { prefs?.edit()?.putString("locale:$account", tag)?.apply() }
    }

    private val account: String?
        get() {
            val key = publicKey ?: return null
            val host = client?.baseUrl?.let { runCatching { URI(it).host }.getOrNull() } ?: "api"
            return "$host|$key"
        }

    /** The install token, registering this install first if needed. Concurrent callers share one registration. */
    suspend fun token(): String {
        val client = client
        val publicKey = publicKey
        val account = account
        val context = appContext
        if (client == null || publicKey == null || account == null || context == null) {
            throw DevReplyError.Invalid("Call DevReply.configure first")
        }
        cachedToken?.let { return it }
        return registering.withLock {
            cachedToken ?: (withContext(Dispatchers.IO) { tokens?.token(account) }
                ?: register(client, publicKey, account, context)).also { cachedToken = it }
        }
    }

    /** Registers this install, unless the key was refused recently (then fails at once, no request). */
    private suspend fun register(client: ApiClient, publicKey: String, account: String, context: Context): String {
        if (!backoff.allowed()) throw DevReplyError.InvalidPublicKey
        val token = try {
            client.registerInstall(publicKey, DeviceInfo.current(context))
        } catch (e: DevReplyError.InvalidPublicKey) {
            if (backoff.refused()) {
                android.util.Log.w("DevReply", "DevReply: this public key isn't recognised: ${publicKey.take(9)}… (next try in ${backoff.waitMs() / 1000} s)")
            }
            throw e
        }
        backoff.succeeded()
        // The registration carried the language: no PATCH needed for it.
        localePrefs()?.edit()?.putString("locale:$account", L10n.tag)?.apply()
        withContext(Dispatchers.IO) { tokens?.setToken(token, account) }
        return token
    }

    /** Runs [call] with the token. If the token was revoked, registers again once and retries. */
    suspend fun <T> authorized(call: suspend (ApiClient, String) -> T): T {
        val client = client ?: throw DevReplyError.Invalid("Call DevReply.configure first")
        return try {
            call(client, token())
        } catch (e: DevReplyError.Unauthenticated) {
            account?.let { tokens?.deleteToken(it) }
            cachedToken = null
            call(client, token())
        }
    }

    suspend fun refresh() {
        if (client == null) return
        try {
            val (configJson, list, me) = coroutineScope {
                val c = async { authorized { api, t -> api.config(t) } }
                val l = async { authorized { api, t -> api.conversations(t) } }
                val p = async { authorized { api, t -> api.profile(t) } }
                Triple(c.await(), l.await(), p.await())
            }
            conversations = list
            profile = me
            val parsed = MessengerConfig.parse(configJson, config.appName)
            if (parsed != config) {
                config = parsed
                cache(configJson)
            }
            lastError = null
        } catch (e: DevReplyError) {
            lastError = e
        } catch (e: Exception) {
            lastError = DevReplyError.Network
        }
    }

    /** Saves what the user typed (or what the host app passed to `DevReply.setUser`). */
    suspend fun saveProfile(name: String?, email: String?) {
        profile = authorized { api, t -> api.updateProfile(t, name, email) }
    }

    suspend fun loadProfileIfNeeded() {
        if (profile != null) return
        profile = runCatching { authorized { api, t -> api.profile(t) } }.getOrNull()
    }

    // Name, email and attributes from the host app, applied once the install exists.
    private var hostUser: Pair<String?, String?>? = null
    private val pendingAttributes = mutableMapOf<String, Any?>()

    fun setUser(name: String?, email: String?) {
        hostUser = name to email
        if (client == null) return
        scope.launch { runCatching { saveProfile(name, email) } }
    }

    fun setAttributes(attributes: Map<String, Any?>) {
        pendingAttributes.putAll(attributes)
        if (client == null) return
        scope.launch { flushAttributes() }
    }

    private suspend fun flushAttributes() {
        val batch = pendingAttributes.toMap()
        if (batch.isEmpty()) return
        val ok = runCatching { authorized { api, t -> api.updateProfile(t, null, null, batch) } }.isSuccess
        if (ok) batch.forEach { (k, v) -> if (pendingAttributes[k] == v) pendingAttributes.remove(k) }
    }

    private var reportedDeepLink = false

    /**
     * A DevReply link opened the app (the button in emails): shows that conversation if it's this
     * install's, the messenger home otherwise, and tells the server once that the deep link works.
     */
    fun openFromLink(context: Context, conversationId: UUID) {
        val start = java.lang.ref.WeakReference(context)
        val app = context.applicationContext
        scope.launch {
            if (!reportedDeepLink) {
                reportedDeepLink = runCatching { authorized { api, t -> api.deepLinkOpened(t) } }.isSuccess
            }
            if (conversation(conversationId) == null) refresh()
            val target = start.get()?.takeIf { it !is android.app.Activity || (!it.isFinishing && !it.isDestroyed) } ?: app
            val intent = android.content.Intent(target, com.devreply.sdk.ui.DevReplyActivity::class.java)
            if (conversation(conversationId) != null) {
                intent.putExtra(com.devreply.sdk.ui.DevReplyActivity.EXTRA_CONVERSATION, conversationId.toString())
            }
            if (target !is android.app.Activity) intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { target.startActivity(intent) }
        }
    }

    fun upsert(conversation: Conversation) {
        conversations = (listOf(conversation) + conversations.filter { it.id != conversation.id })
            .sortedByDescending { it.lastMessageAt }
    }

    fun conversation(id: UUID): Conversation? = conversations.firstOrNull { it.id == id }

    // The last config per public key, in the cache dir (spec 05: fetched on open and cached).
    private fun cacheFile(context: Context, key: String) = File(context.cacheDir, "devreply-config-$key.json")

    private fun cachedConfig(context: Context, key: String, appName: String): MessengerConfig? = runCatching {
        MessengerConfig.parse(JSONObject(cacheFile(context, key).readText()), appName)
    }.getOrNull()

    private fun cache(json: JSONObject) {
        val context = appContext ?: return
        val key = publicKey ?: return
        runCatching { cacheFile(context, key).writeText(json.toString()) }
    }
}
