package com.devreply.sdk

import com.devreply.sdk.ui.ConversationModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.UUID

// SDK 0.5.0 (next release): button replies (spec 05) and the device key (spec 03, same device after logout).

private const val QUESTION_ID = "01a0e892-b653-7191-9df9-c7e19fc68be7"

private val buttonsBlock = """{"type":"buttons","text":"Why are you **leaving**?",
    "options":[{"id":"o1","label":"Too expensive"},{"id":"o2","label":"Missing a feature"},{"id":"o3","label":"Something else"}],
    "min_sdk":"0.5.0","fallback":"Why are you leaving?\n\n1. Too expensive\n2. Missing a feature\n3. Something else\n\nReply with a number or in your own words."}"""

private fun message(author: String, blocks: String, id: String = UUID.randomUUID().toString(), extra: String = "") = Message.parse(
    JSONObject("""{"id":"$id","author":"$author","created_at":"2026-09-30T10:00:00Z","blocks":$blocks$extra}"""),
)

class ButtonsBlockTest {
    @Test fun thisBuildShowsTheButtons() {
        // Real server blocks carry min_sdk 0.5.0: this build (0.5.0) shows the question and its buttons.
        val m = message("agent", "[$buttonsBlock]")
        assertTrue(m.blocks.single() is Block.Buttons)
    }

    @Test fun olderSdksShowTheFallback() {
        val block = Block.parse(JSONObject(buttonsBlock), sdk = "0.4.4")
        assertTrue(block is Block.Unsupported)
        assertTrue((block as Block.Unsupported).fallback.startsWith("Why are you leaving?\n\n1. Too expensive"))
    }

    @Test fun from050TheQuestionAndItsOptions() {
        val block = Block.parse(JSONObject(buttonsBlock), sdk = "0.5.0") as Block.Buttons
        assertEquals("Why are you **leaving**?", block.text)
        assertEquals(Markdown.parse("Why are you **leaving**?"), block.nodes)
        assertEquals(listOf("o1", "o2", "o3"), block.options.map { it.id })
        assertEquals(listOf("Too expensive", "Missing a feature", "Something else"), block.options.map { it.label })
        // Previews and notifications: the question's plain text, not the numbered fallback.
        assertEquals("Why are you leaving?", block.plainText)
        // Newer SDKs too.
        assertTrue(Block.parse(JSONObject(buttonsBlock), sdk = "1.2.0") is Block.Buttons)
    }

    @Test fun withoutMinSdkIsDecodedToday() {
        val m = message("agent", """[{"type":"buttons","text":"Pick","options":[{"id":"a","label":"A"},{"id":"b","label":"B"}]}]""")
        assertEquals(listOf("A", "B"), (m.blocks.single() as Block.Buttons).options.map { it.label })
        assertEquals("Pick", m.plainText)
    }

    private fun parse(options: String, text: String = "\"Pick one\"") =
        Block.parse(JSONObject("""{"type":"buttons","text":$text,"options":$options,"fallback":"Pick: A or B"}"""), sdk = "0.5.0")

    @Test fun badOptionsAreSkippedAndTooFewShowTheFallback() {
        // One bad option is skipped; a repeated id counts once; at most 5.
        val ok = parse(
            """[{"id":"a","label":"A"},{"id":"","label":"no id"},{"label":"no id"},{"id":"x","label":"  "},5,
               {"id":"b","label":" B "},{"id":"a","label":"again"},{"id":"c","label":"C"},{"id":"d","label":"D"},
               {"id":"e","label":"E"},{"id":"f","label":"F"}]""",
        ) as Block.Buttons
        assertEquals(listOf("a", "b", "c", "d", "e"), ok.options.map { it.id })
        assertEquals("B", ok.options[1].label)

        assertEquals(Block.Unsupported("Pick: A or B"), parse("""[{"id":"a","label":"A"}]"""))
        assertEquals(Block.Unsupported("Pick: A or B"), parse("[]"))
        assertEquals(Block.Unsupported("Pick: A or B"), parse("""[{"id":"a","label":"A"},{"id":"b","label":"B"}]""", text = "\"  \""))
        assertEquals(Block.Unsupported("Pick: A or B"), parse("\"nope\""))
    }

    @Test fun anAnswerOnTheUsersMessage() {
        val onBlock = message("user", """[{"type":"text","text":"Too expensive","answer":{"message_id":"$QUESTION_ID","option_id":"o1"}}]""")
        assertEquals(Answer(UUID.fromString(QUESTION_ID), "o1"), onBlock.answer)
        assertEquals("Too expensive", onBlock.plainText)
        val onMessage = message("user", """[{"type":"text","text":"Too expensive"}]""", extra = ""","answer":{"message_id":"$QUESTION_ID","option_id":"o2"}""")
        assertEquals("o2", onMessage.answer?.optionId)
        // Only the user answers; broken answers are none.
        assertNull(message("agent", """[{"type":"text","text":"x","answer":{"message_id":"$QUESTION_ID","option_id":"o1"}}]""").answer)
        assertNull(message("user", """[{"type":"text","text":"x","answer":{"message_id":"nope","option_id":"o1"}}]""").answer)
        assertNull(message("user", """[{"type":"text","text":"x","answer":{"message_id":"$QUESTION_ID"}}]""").answer)
        assertNull(message("user", """[{"type":"text","text":"x"}]""").answer)
    }

    @Test fun whichOptionIsChosen() {
        val q = UUID.fromString(QUESTION_ID)
        val question = message("agent", "[$buttonsBlock]", id = QUESTION_ID)
        val typed = message("user", """[{"type":"text","text":"2"}]""")
        assertNull("typing doesn't answer the buttons", chosenOption(q, listOf(question, typed)))
        val other = message("user", """[{"type":"text","text":"x","answer":{"message_id":"${UUID.randomUUID()}","option_id":"o1"}}]""")
        assertNull("another question's answer", chosenOption(q, listOf(question, other)))
        val answer = message("user", """[{"type":"text","text":"Missing a feature","answer":{"message_id":"$QUESTION_ID","option_id":"o2"}}]""")
        assertEquals("o2", chosenOption(q, listOf(question, typed, answer)))
        // While it's being sent, the tapped option already counts; the server's answer wins.
        assertEquals("o3", chosenOption(q, listOf(question), sending = listOf(Answer(q, "o3"))))
        assertEquals("o2", chosenOption(q, listOf(question, answer), sending = listOf(Answer(q, "o3"))))
    }

    @Test fun theRequestBody() {
        val q = UUID.fromString(QUESTION_ID)
        val body = ApiClient.messageBody("Too expensive", null, emptyList(), Answer(q, "o1"))
        assertEquals("Too expensive", body.getString("text"))
        assertEquals(QUESTION_ID, body.getJSONObject("answer").getString("message_id"))
        assertEquals("o1", body.getJSONObject("answer").getString("option_id"))
        assertFalse("a typed message has no answer", ApiClient.messageBody("hi", null, emptyList()).has("answer"))
    }
}

/**
 * The public API in memory: the real [ApiClient] with its transport replaced. Records every request and answers
 * from [routes] ("METHOD path" → status to body), through the client's own status handling (409 → Server(409)).
 */
internal class FakeApi : ApiClient("https://api.test") {
    data class Request(val method: String, val path: String, val token: String?, val body: JSONObject?)

    val requests = java.util.Collections.synchronizedList(mutableListOf<Request>())
    val routes = java.util.concurrent.ConcurrentHashMap<String, () -> Pair<Int, String>>()

    override suspend fun send(method: String, path: String, token: String?, body: JSONObject?): Any {
        val route = "$method /$path"
        // A copy: the body as it went out.
        requests += Request(method, "/$path", token, body?.let { JSONObject(it.toString()) })
        val (status, text) = routes[route]?.invoke() ?: (404 to """{"error":{"message":"no route $route"}}""")
        return result(status, text)
    }

    fun count(route: String) = requests.count { "${it.method} ${it.path}" == route }
    fun last(route: String) = requests.last { "${it.method} ${it.path}" == route }

    init {
        var n = 0
        routes["POST /v1/installs"] = { 201 to """{"token":"it_${++n}"}""" }
        routes["GET /v1/messenger/config"] = { 200 to """{"app_name":"Test"}""" }
        routes["GET /v1/conversations"] = { 200 to "[]" }
        routes["GET /v1/me"] = { 200 to """{"name":"Ana"}""" }
        routes["PATCH /v1/me"] = { 200 to """{"name":"Ana"}""" }
        routes["POST /v1/logout"] = { 204 to "" }
        routes["PATCH /v1/install"] = { 200 to "{}" }
    }
}

/** The encrypted store, in memory. */
internal class MemoryStore : SecretStore {
    val entries = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun token(account: String): String? = entries[account]
    override fun setToken(token: String, account: String) { entries[account] = token }
    override fun deleteToken(account: String) { entries.remove(account) }
}

/** Messenger against [FakeApi], its coroutines run where they're launched (the JVM has no main thread). */
abstract class MessengerHarness {
    internal val api = FakeApi()
    internal val store = MemoryStore()
    private val job = SupervisorJob()
    private val savedScope = Messenger.scope
    private val device = DeviceInfo("Test Phone", "16", "1.0 (1)")

    @Before fun attach() {
        Messenger.scope = CoroutineScope(job + Dispatchers.Unconfined)
        Messenger.attachForTests(api, "pk_test_android", store, device)
    }

    @After fun detach() {
        settle()
        Messenger.detachForTests()
        Messenger.scope = savedScope
    }

    /** Waits for everything Messenger launched. */
    fun settle() = runBlocking {
        while (true) {
            val running = job.children.filter(Job::isActive).toList()
            if (running.isEmpty()) break
            running.forEach { it.join() }
        }
    }

    internal val account get() = "api.test|pk_test_android"
}

class DeviceKeyTest : MessengerHarness() {
    @Test fun random256BitsBase64Url() {
        val key = DeviceKey.generate()
        assertEquals(43, key.length)
        assertTrue(key.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertEquals(32, Base64.getUrlDecoder().decode(key).size)
        assertNotEquals(key, DeviceKey.generate())
        assertTrue(DeviceKey.isValid(key))
        assertFalse(DeviceKey.isValid("short"))
        assertFalse(DeviceKey.isValid("x".repeat(42) + "="))
    }

    @Test fun createdOnceThenKept() {
        val first = DeviceKey.get(store)
        assertEquals(first, store.entries[DeviceKey.ENTRY])
        assertEquals(first, DeviceKey.get(store))
        // A damaged entry is replaced.
        store.entries[DeviceKey.ENTRY] = "garbage"
        val replaced = DeviceKey.get(store)
        assertTrue(DeviceKey.isValid(replaced))
        assertEquals(replaced, DeviceKey.get(store))
    }

    @Test fun sentAtRegistration() = runBlocking {
        assertEquals("it_1", Messenger.token())
        val body = api.last("POST /v1/installs").body!!
        val key = store.entries[DeviceKey.ENTRY]!!
        assertTrue(DeviceKey.isValid(key))
        assertEquals(key, body.getString("device_key"))
        assertEquals("pk_test_android", body.getString("public_key"))
        assertEquals("android", body.getString("platform"))
    }

    @Test fun survivesLogoutAndGoesWithTheNextInstall() {
        runBlocking { Messenger.token() }
        Messenger.login("user-42")
        settle()
        val key = store.entries[DeviceKey.ENTRY]!!
        assertEquals("it_1", store.entries[account])
        assertEquals("user-42", store.entries["user|$account"])

        Messenger.logout()
        settle()
        assertEquals("the old install is revoked", 1, api.count("POST /v1/logout"))
        assertEquals("the device key stays", key, store.entries[DeviceKey.ENTRY])
        // The install is forgotten; the refresh after logout registered a new one with the same device key.
        assertNull(store.entries["user|$account"])
        assertEquals(2, api.count("POST /v1/installs"))
        assertEquals("it_2", store.entries[account])
        assertEquals(key, api.last("POST /v1/installs").body!!.getString("device_key"))
    }

    @Test fun survivesDeleteUserToo() {
        runBlocking { Messenger.token() }
        val key = store.entries[DeviceKey.ENTRY]!!
        api.routes["DELETE /v1/me"] = { 204 to "" }
        assertTrue(runBlocking { Messenger.deleteUser() })
        settle()
        assertEquals(key, store.entries[DeviceKey.ENTRY])
    }
}

class RestoredTest : MessengerHarness() {
    private val conversation = """{"id":"$QUESTION_ID","status":"open","category":"bug","last_text":"Old chat",
        "last_author":"admin","unread":1,"last_message_at":"2026-09-01T10:00:00Z"}"""

    @Test fun restoredLoginReloadsTheConversations() {
        runBlocking { Messenger.token() }
        val before = Messenger.reloads
        val lists = api.count("GET /v1/conversations")
        // The server moved this install back to the user who had it before logout: their chats are back.
        api.routes["GET /v1/conversations"] = { 200 to "[$conversation]" }
        api.routes["PATCH /v1/me"] = { 200 to """{"name":"Ana","restored":true}""" }
        Messenger.login("user-42")
        settle()
        assertEquals("user-42", api.last("PATCH /v1/me").body!!.getString("user_id"))
        assertEquals(lists + 1, api.count("GET /v1/conversations"))
        assertEquals(listOf(UUID.fromString(QUESTION_ID)), Messenger.conversations.map { it.id })
        assertEquals(1, Messenger.unreadCount)
        assertEquals("the open conversation reloads", before + 1, Messenger.reloads)
    }

    @Test fun anOrdinaryLoginDoesNot() {
        runBlocking { Messenger.token() }
        val before = Messenger.reloads
        Messenger.login("user-42")
        settle()
        assertEquals(1, api.count("PATCH /v1/me"))
        assertEquals(0, api.count("GET /v1/conversations"))
        assertEquals(before, Messenger.reloads)
    }

    @Test fun restoredIsReadFromTheProfile() {
        assertTrue(Profile.parse(JSONObject("""{"restored":true}""")).restored)
        assertFalse(Profile.parse(JSONObject("""{"restored":false}""")).restored)
        assertFalse("older servers send none", Profile.parse(JSONObject("""{"name":"A"}""")).restored)
        assertFalse(Profile.parse(JSONObject("""{"restored":"yes"}""")).restored)
    }
}

class AnswerSendingTest : MessengerHarness() {
    private val conversationId = UUID.fromString("0f1e2d3c-4b5a-4968-8776-655443322110")
    private val path = "/v1/conversations/$conversationId/messages"
    private val question = message("agent", """[{"type":"buttons","text":"Pick","options":[{"id":"o1","label":"Too expensive"},{"id":"o2","label":"Other"}]}]""", id = QUESTION_ID)
    private val option = (question.blocks.single() as Block.Buttons).options[0]
    private val conversationJson = """{"id":"$conversationId","status":"open","last_message_at":"2026-09-30T10:00:00Z"}"""
    private fun answerJson(option: String) = """{"id":"${UUID.randomUUID()}","author":"user","created_at":"2026-09-30T10:01:00Z",
        "blocks":[{"type":"text","text":"Too expensive","answer":{"message_id":"$QUESTION_ID","option_id":"$option"}}]}"""

    @Test fun aTapSendsTheLabelWithTheAnswer() {
        val model = ConversationModel(conversationId, null)
        api.routes["POST $path"] = { 201 to answerJson("o1") }
        model.answer(question, option)
        settle()
        val body = api.last("POST $path").body!!
        assertEquals("Too expensive", body.getString("text"))
        assertEquals(QUESTION_ID, body.getJSONObject("answer").getString("message_id"))
        assertEquals("o1", body.getJSONObject("answer").getString("option_id"))
        assertTrue(model.pending.isEmpty())
        assertEquals("o1", model.chosenOption(question.id))
        // One answer per question: another tap sends nothing.
        model.answer(question, (question.blocks.single() as Block.Buttons).options[1])
        settle()
        assertEquals(1, api.count("POST $path"))
    }

    @Test fun answeredAlreadyReloads() {
        val model = ConversationModel(conversationId, null)
        api.routes["POST $path"] = { 409 to """{"error":{"message":"already answered"}}""" }
        api.routes["GET $path"] = { 200 to """{"conversation":$conversationJson,"messages":[${answerJson("o2")}]}""" }
        model.answer(question, option)
        settle()
        assertEquals(1, api.count("GET $path"))
        assertTrue("no failed bubble", model.pending.isEmpty())
        assertEquals("the server's answer shows", "o2", model.chosenOption(question.id))
    }

    @Test fun anotherFailureCanBeRetried() {
        val model = ConversationModel(conversationId, null)
        api.routes["POST $path"] = { 500 to "{}" }
        model.answer(question, option)
        settle()
        val failed = model.pending.single()
        assertTrue(failed.failure != null)
        assertEquals("still shown as chosen while it waits", "o1", model.chosenOption(question.id))
        api.routes["POST $path"] = { 201 to answerJson("o1") }
        model.retry(failed)
        settle()
        assertEquals("o1", api.last("POST $path").body!!.getJSONObject("answer").getString("option_id"))
        assertTrue(model.pending.isEmpty())
    }
}
