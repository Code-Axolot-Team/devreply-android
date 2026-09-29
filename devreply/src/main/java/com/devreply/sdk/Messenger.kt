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
    val context: Context? get() = appContext
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
        // Configured already with the same key (the messenger started itself from a notification, then
        // the app's own code ran): keep the state, only follow the app's screens.
        if (client != null && publicKey == this.publicKey && apiUrl == client?.baseUrl) {
            (app as? android.app.Application)?.let { AppWatcher.start(it, context as? android.app.Activity) }
            return
        }
        appContext = app
        app.getSharedPreferences("devreply", Context.MODE_PRIVATE).edit()
            .putString("last_key", publicKey).putString("last_api", apiUrl).apply()
        if (publicKey != this.publicKey) backoff = RegistrationBackoff()
        this.publicKey = publicKey
        client = ApiClient(apiUrl)
        tokens = TokenStore(app)
        cachedToken = null
        conversations = emptyList()
        val appName = app.applicationInfo.loadLabel(app.packageManager).toString()
        config = cachedConfig(app, publicKey, appName) ?: MessengerConfig.placeholder(appName)
        (app as? android.app.Application)?.let { AppWatcher.start(it, context as? android.app.Activity) }
        // Signed in as someone else than this install's user (the app called login before configure).
        val stored = account?.let { tokens?.token("user|$it") }
        if (hostUserId != null && stored != null && stored != hostUserId) forgetInstall()
        // Register early so the first open is instant, and pick up unread replies.
        scope.launch {
            sendUserId()
            hostUser?.let { (name, email) -> runCatching { saveProfile(name, email) } }
            flushAttributes()
            refresh()
            syncLocale()
            PushManager.sendTokenIfNeeded()
        }
    }

    /**
     * The messenger opened before the app configured DevReply (a notification tap starts the app and the
     * messenger together; React Native and Flutter configure from their own code a moment later): use
     * the key the app configured last time.
     */
    fun restore(context: Context) {
        if (client != null) return
        val prefs = context.applicationContext.getSharedPreferences("devreply", Context.MODE_PRIVATE)
        val key = prefs.getString("last_key", null) ?: return
        configure(context, key, prefs.getString("last_api", null) ?: DevReply.DEFAULT_API_URL)
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

    // ---- signed-in user (spec 03) ----

    /** The app's id for the signed-in user (`DevReply.login`). */
    private var hostUserId: String? = null

    fun login(userId: String) {
        val id = userId.trim().takeIf { it.isNotEmpty() } ?: return
        // Another person on this device: they start from a new, empty install.
        val stored = account?.let { tokens?.token("user|$it") }
        if (stored != null && stored != id) logout()
        hostUserId = id
        if (client == null) return
        scope.launch { sendUserId() }
    }

    /**
     * Labels this install's user with the app's id. The server refuses another id for the same user
     * (409): then this install belonged to someone else, so start a new one.
     */
    private suspend fun sendUserId(retry: Boolean = true) {
        val id = hostUserId ?: return
        val account = account ?: return
        try {
            profile = authorized { api, t -> api.updateProfile(t, null, null, userId = id) }
            withContext(Dispatchers.IO) { tokens?.setToken(id, "user|$account") }
        } catch (e: DevReplyError.Server) {
            if (e.status == 409 && retry) {
                logout(keepUserId = true)
                sendUserId(retry = false)
            }
        } catch (e: Exception) {
        }
    }

    /**
     * `DevReply.logout()`: the server stops accepting this install (and its push token), the device
     * forgets it, and the next person starts from a new, empty install. Conversations stay for the team.
     */
    fun logout(keepUserId: Boolean = false) {
        val api = client
        val token = account?.let { tokens?.token(it) }
        if (api != null && token != null) scope.launch { runCatching { withContext(Dispatchers.IO) { api.logout(token) } } }
        val id = hostUserId
        forgetInstall()
        if (keepUserId) hostUserId = id
        if (client == null) return
        scope.launch {
            refresh()
            PushManager.sendTokenIfNeeded()
        }
    }

    /** `DevReply.deleteUser()`: deletes the user's data on the server, then forgets the install. */
    suspend fun deleteUser(): Boolean {
        if (client == null) return false
        val ok = runCatching { authorized { api, t -> api.deleteUser(t) } }.isSuccess
        if (!ok) return false
        forgetInstall()
        scope.launch {
            refresh()
            PushManager.sendTokenIfNeeded()
        }
        return true
    }

    /** Everything this device knew about the user: the token, who they were, their chats on screen. */
    private fun forgetInstall() {
        account?.let {
            tokens?.deleteToken(it)
            tokens?.deleteToken("user|$it")
        }
        cachedToken = null
        hostUserId = null
        hostUser = null
        pendingAttributes.clear()
        conversations = emptyList()
        profile = null
        PushManager.forgetSentToken()
        com.devreply.sdk.ui.DevReplyActivity.current?.get()?.finish()
    }

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
