package com.devreply.sdk

import com.devreply.sdk.ui.ConversationModel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

// SDK 0.6.0 (spec 14, "0.6 wire formats"): link buttons, gifts, forms, offers, the notification permission.

private fun block(json: String, sdk: String = DEVREPLY_SDK_VERSION, schemes: List<String> = listOf("kneefit")) =
    Block.parse(JSONObject(json), sdk, schemes)

private const val LINKS = """{"type":"links","text":"New **lessons** are out",
    "links":[{"id":"l1","label":"Open the new lessons","url":"kneefit://lessons/new"},
             {"id":"l2","label":"Read more","url":"https://kneefit.app/blog"}],
    "min_sdk":"0.6.0","fallback":"New lessons are out\n\nOpen the new lessons: kneefit://lessons/new"}"""

private const val GIFT = """{"type":"gift","gift_id":"0195f0a0-0000-7000-8000-00000000a001","title":"50% off for a year",
    "text":"Thanks for finding that bug!","url":"kneefit://paywall?offer=bug_hunter&devreply_gift=gft_x",
    "expires_at":"2026-11-25T10:00:00Z","min_sdk":"0.6.0","fallback":"🎁 50% off for a year."}"""

private const val FORM = """{"type":"form","text":"Quick one",
    "questions":[
      {"id":"q1","text":"What stopped you?","kind":"single","options":[{"id":"o1","label":"Price"},{"id":"o2","label":"Time"}],"required":true},
      {"id":"q2","text":"Which features matter?","kind":"multi","options":[{"id":"o1","label":"Plans"},{"id":"o2","label":"Videos"},{"id":"o3","label":"Stats"}],"required":false},
      {"id":"q3","text":"Anything else?","kind":"text","required":false}],
    "min_sdk":"0.6.0","fallback":"1. What stopped you? …"}"""

class BlockDecodingTest {
    @Test fun links() {
        val b = block(LINKS) as Block.Links
        assertEquals("New **lessons** are out", b.text)
        assertTrue(b.nodes.isNotEmpty())
        assertEquals(listOf("l1", "l2"), b.links.map { it.id })
        assertEquals("kneefit://lessons/new", b.links[0].url)
        assertTrue(b.plainText.startsWith("New lessons are out"))
    }

    @Test fun linksWithoutIntroAndAtMostThree() {
        val b = block("""{"type":"links","links":[{"id":"a","label":"A","url":"https://a.com"},{"id":"a","label":"dup","url":"https://b.com"},
            {"id":"b","label":"B","url":"https://b.com"},{"id":"c","label":"C","url":"https://c.com"},{"id":"d","label":"D","url":"https://d.com"},
            {"id":"x","label":"","url":"https://x.com"}],"fallback":"f"}""") as Block.Links
        assertNull(b.text)
        assertEquals(listOf("a", "b", "c"), b.links.map { it.id })
    }

    @Test fun linksWithNoUsableLinkFallBack() {
        assertEquals(Block.Unsupported("f"), block("""{"type":"links","links":[{"id":"a","label":"A"}],"fallback":"f"}"""))
    }

    @Test fun gift() {
        val b = block(GIFT) as Block.Gift
        assertEquals("0195f0a0-0000-7000-8000-00000000a001", b.giftId)
        assertEquals("50% off for a year", b.title)
        assertEquals("Thanks for finding that bug!", b.text)
        assertEquals(Instant.parse("2026-11-25T10:00:00Z"), b.expiresAt)
        assertFalse(b.isExpired(Instant.parse("2026-11-25T09:59:59Z")))
        assertTrue(b.isExpired(Instant.parse("2026-11-25T10:00:00Z")))
        assertEquals("🎁 50% off for a year.", b.plainText)
    }

    @Test fun giftWithoutExpiryNeverExpires() {
        val b = block("""{"type":"gift","gift_id":"g1","title":"T","url":"https://x.com","fallback":"f"}""") as Block.Gift
        assertNull(b.expiresAt)
        assertFalse(b.isExpired(Instant.parse("2100-01-01T00:00:00Z")))
    }

    @Test fun giftMissingPartsOrAnOddIdFallsBack() {
        assertEquals(Block.Unsupported("f"), block("""{"type":"gift","gift_id":"g1","url":"https://x.com","fallback":"f"}"""))
        assertEquals(Block.Unsupported("f"), block("""{"type":"gift","gift_id":"../me","title":"T","url":"https://x.com","fallback":"f"}"""))
    }

    @Test fun form() {
        val b = block(FORM) as Block.Form
        assertEquals(listOf("q1", "q2", "q3"), b.questions.map { it.id })
        assertEquals(listOf(Block.Form.Kind.Single, Block.Form.Kind.Multi, Block.Form.Kind.Text), b.questions.map { it.kind })
        assertEquals(listOf(true, false, false), b.questions.map { it.required })
        assertEquals(listOf("Plans", "Videos", "Stats"), b.questions[1].options.map { it.label })
        assertTrue(b.questions[2].options.isEmpty())
        assertEquals("1. What stopped you? …", b.plainText)
    }

    @Test fun formWithAKindThisSdkDoesntKnowFallsBack() {
        val f = """{"type":"form","questions":[{"id":"q1","text":"Rate us","kind":"stars"}],"fallback":"f"}"""
        assertEquals(Block.Unsupported("f"), block(f))
        // A choice needs two options; more than five questions isn't a form.
        assertEquals(Block.Unsupported("f"), block("""{"type":"form","questions":[{"id":"q1","text":"?","kind":"single","options":[{"id":"o","label":"x"}]}],"fallback":"f"}"""))
        val six = (1..6).joinToString(",") { """{"id":"q$it","text":"?","kind":"text"}""" }
        assertEquals(Block.Unsupported("f"), block("""{"type":"form","questions":[$six],"fallback":"f"}"""))
    }

    @Test fun olderSdksShowTheFallback() {
        assertEquals(Block.Unsupported("New lessons are out\n\nOpen the new lessons: kneefit://lessons/new"), block(LINKS, sdk = "0.5.1"))
        assertEquals(Block.Unsupported("🎁 50% off for a year."), block(GIFT, sdk = "0.5.1"))
        assertEquals(Block.Unsupported("1. What stopped you? …"), block(FORM, sdk = "0.5.1"))
        // And a newer block this SDK doesn't know yet still falls back.
        assertEquals(Block.Unsupported("later"), block("""{"type":"poll","min_sdk":"0.9.0","fallback":"later"}"""))
        assertEquals(Block.Unsupported("later"), block("""{"type":"poll","fallback":"later"}"""))
    }

    @Test fun promotionalAndFormAnswerOnMessages() {
        val form = UUID.randomUUID()
        val team = Message.parse(JSONObject("""{"id":"${UUID.randomUUID()}","author":"admin","created_at":"2026-11-11T10:00:00Z",
            "promotional":true,"blocks":[{"type":"text","text":"50% off"}]}"""))
        assertTrue(team.promotional)
        val user = Message.parse(JSONObject("""{"id":"${UUID.randomUUID()}","author":"user","created_at":"2026-11-11T10:00:00Z",
            "promotional":true,"blocks":[{"type":"text","text":"What stopped you? Price",
            "form_answer":{"message_id":"$form","answers":{"q1":"o1","q2":["o1","o3"],"q3":"free text"}}}]}"""))
        assertFalse("only the team's messages are offers", user.promotional)
        val a = user.formAnswer!!
        assertEquals(form, a.messageId)
        assertEquals(listOf("o1"), a.chosen("q1"))
        assertEquals(listOf("o1", "o3"), a.chosen("q2"))
        assertEquals("free text", a.text("q3"))
        val old = Message.parse(JSONObject("""{"id":"${UUID.randomUUID()}","author":"admin","created_at":"2026-11-11T10:00:00Z","blocks":[]}"""))
        assertFalse(old.promotional)
        assertNull(old.formAnswer)
    }

    @Test fun profileAndConfig() {
        assertTrue(Profile.parse(JSONObject("""{"name":"A","no_promotions":true}""")).noPromotions)
        assertFalse(Profile.parse(JSONObject("""{"name":"A"}""")).noPromotions)
        val c = MessengerConfig.parse(JSONObject("""{"link_schemes":["kneefit","KneeFit2://","https","javascript","bad scheme",7,"intent"]}"""), "X")
        assertEquals(listOf("kneefit", "kneefit2"), c.linkSchemes)
        assertTrue(MessengerConfig.parse(JSONObject("{}"), "X").linkSchemes.isEmpty())
    }
}

class LinkPolicyTest {
    private val schemes = listOf("kneefit")

    @Test fun httpsAndTheAppsOwnSchemes() {
        assertTrue(LinkPolicy.isAllowed("https://kneefit.app/x", schemes))
        assertTrue(LinkPolicy.isAllowed("HTTPS://kneefit.app", schemes))
        assertTrue(LinkPolicy.isAllowed("kneefit://lessons/new", schemes))
        assertTrue(LinkPolicy.isAllowed("KneeFit://paywall?offer=a&devreply_gift=gft_x", schemes))
    }

    @Test fun everythingElseIsntOpened() {
        for (url in listOf(
            "http://kneefit.app", "otherapp://x", "javascript:alert(1)", "intent://x#Intent;scheme=kneefit;end",
            "file:///sdcard/x", "content://x", "mailto:a@b.c", "https:/x", "https:///x", "https://", "kneefit", "",
            "kneefit://x y", "kneefit://x\n", "market://details?id=x",
        )) assertFalse(url, LinkPolicy.isAllowed(url, schemes))
        assertFalse("not configured", LinkPolicy.isAllowed("kneefit://x", emptyList()))
        // A config can't allow the system's schemes.
        assertFalse(LinkPolicy.isAllowed("intent://x", listOf("intent")))
    }

    @Test fun markdownLinksMayUseTheAppsSchemes() {
        val withScheme = Markdown.parse("[Open](kneefit://lessons) or [x](otherapp://y)", schemes)
        val spans = (withScheme.single() as Markdown.Node.Paragraph).spans
        assertEquals("only the app's own scheme is a link", listOf("kneefit://lessons"), spans.mapNotNull { it.link })
        assertEquals("Open", spans.first().text)
        // Without schemes, exactly as before (the conformance cases).
        val plain = (Markdown.parse("[Open](kneefit://lessons)").single() as Markdown.Node.Paragraph).spans
        assertTrue(plain.all { it.link == null })
        // Via the block, with the schemes from the config.
        val b = block("""{"type":"markdown","text":"[Open](kneefit://lessons)"}""") as Block.Markdown
        assertEquals("kneefit://lessons", ((b.nodes.single() as Markdown.Node.Paragraph).spans.single()).link)
    }
}

class FormsTest {
    private val form = block(FORM) as Block.Form

    @Test fun requiredOnesAreMissingUntilAnswered() {
        assertEquals(listOf("q1"), Forms.missing(form, emptyMap()))
        assertEquals(listOf("q1"), Forms.missing(form, mapOf("q1" to "nope", "q3" to "hi")))
        assertEquals(emptyList<String>(), Forms.missing(form, mapOf("q1" to "o2")))
    }

    @Test fun cleanKeepsWhatTheServerAccepts() {
        val clean = Forms.clean(form, mapOf("q1" to "o1", "q2" to listOf("o3", "o1", "zz", "o1"), "q3" to "  free text  ", "q9" to "x"))
        assertEquals(mapOf("q1" to "o1", "q2" to listOf("o1", "o3"), "q3" to "free text"), clean)
        assertEquals(mapOf("q1" to "o1"), Forms.clean(form, mapOf("q1" to "o1", "q2" to emptyList<String>(), "q3" to "   ")))
    }

    @Test fun encodesTheRequestBody() {
        val id = UUID.fromString("0195f0a0-0000-7000-8000-00000000f001")
        val body = ApiClient.formAnswerBody(FormAnswer(id, mapOf("q1" to "o1", "q2" to listOf("o1", "o3"), "q3" to "free text")))
        val sent = body.getJSONObject("form_answer")
        assertEquals(id.toString(), sent.getString("message_id"))
        val answers = sent.getJSONObject("answers")
        assertEquals("o1", answers.getString("q1"))
        assertEquals(JSONArray("""["o1","o3"]""").toString(), answers.getJSONArray("q2").toString())
        assertEquals("free text", answers.getString("q3"))
        assertEquals(setOf("form_answer"), body.keys().asSequence().toSet())
    }

    @Test fun summaryWhileSending() {
        assertEquals(
            "What stopped you?\nPrice\n\nWhich features matter?\nPlans, Stats",
            Forms.summary(form, mapOf("q1" to "o1", "q2" to listOf("o1", "o3"))),
        )
    }
}

private fun messageJson(id: UUID, author: String, blocks: String, at: String = "2026-11-11T10:00:00Z") =
    """{"id":"$id","author":"$author","created_at":"$at","blocks":$blocks}"""

class FormSendingTest : MessengerHarness() {
    private val conv = UUID.fromString("0195f0a0-0000-7000-8000-00000000c001")
    private val formId = UUID.fromString("0195f0a0-0000-7000-8000-00000000f001")
    private val conversation = """{"id":"$conv","status":"open","unread":0,"last_message_at":"2026-11-11T10:00:00Z"}"""

    private fun model(): Pair<ConversationModel, Message> {
        val team = messageJson(formId, "admin", "[$FORM]")
        api.routes["GET /v1/conversations/$conv/messages"] = { 200 to """{"conversation":$conversation,"messages":[$team]}""" }
        val m = ConversationModel(conv, null)
        runBlocking { m.load() }
        return m to m.messages.single()
    }

    @Test fun sendsOnceWithEveryAnswer() {
        val (m, message) = model()
        val form = message.blocks.single() as Block.Form
        val answerId = UUID.randomUUID()
        api.routes["POST /v1/conversations/$conv/messages"] = {
            200 to messageJson(answerId, "user", """[{"type":"text","text":"What stopped you? Price","form_answer":{"message_id":"$formId","answers":{"q1":"o1","q3":"soon"}}}]""")
        }
        assertEquals("nothing picked: the required one is missing", listOf("q1"), m.submit(message, form))
        assertEquals(0, api.count("POST /v1/conversations/$conv/messages"))
        m.formDrafts[formId] = mapOf("q1" to "o1", "q3" to "soon")
        assertEquals(emptyList<String>(), m.submit(message, form))
        settle()
        val body = api.last("POST /v1/conversations/$conv/messages").body!!
        val sent = body.getJSONObject("form_answer")
        assertEquals(formId.toString(), sent.getString("message_id"))
        assertEquals("o1", sent.getJSONObject("answers").getString("q1"))
        assertEquals("soon", sent.getJSONObject("answers").getString("q3"))
        assertEquals(2, sent.getJSONObject("answers").length())
        assertFalse(body.has("text"))
        // Read-only now, with the answers from the user's message.
        assertEquals(listOf("o1"), m.formAnswer(formId)?.chosen("q1"))
        assertTrue(m.pending.isEmpty())
        // A second Send does nothing.
        m.submit(message, form)
        settle()
        assertEquals(1, api.count("POST /v1/conversations/$conv/messages"))
    }

    @Test fun answeredElsewhereReloads() {
        val (m, message) = model()
        val form = message.blocks.single() as Block.Form
        api.routes["POST /v1/conversations/$conv/messages"] = { 409 to """{"error":{"message":"already answered"}}""" }
        val answer = messageJson(UUID.randomUUID(), "user", """[{"type":"text","text":"Time","form_answer":{"message_id":"$formId","answers":{"q1":"o2"}}}]""", "2026-11-11T10:01:00Z")
        val team = messageJson(formId, "admin", "[$FORM]")
        api.routes["GET /v1/conversations/$conv/messages"] = { 200 to """{"conversation":$conversation,"messages":[$team,$answer]}""" }
        m.formDrafts[formId] = mapOf("q1" to "o1")
        m.submit(message, form)
        settle()
        assertTrue(m.pending.isEmpty())
        assertEquals("the server's answer shows", listOf("o2"), m.formAnswer(formId)?.chosen("q1"))
    }

    @Test fun refusedOpensTheFormAgainWithWhy() {
        val (m, message) = model()
        val form = message.blocks.single() as Block.Form
        api.routes["POST /v1/conversations/$conv/messages"] = { 400 to """{"error":{"message":"q1 is required"}}""" }
        m.formDrafts[formId] = mapOf("q1" to "o1")
        m.submit(message, form)
        settle()
        assertNull(m.formAnswer(formId))
        assertEquals("Q1 is required.", m.formErrors[formId])
        assertEquals(mapOf("q1" to "o1"), m.formDrafts[formId])
    }

    @Test fun linkTapsAndGiftOpeningsAreReported() {
        val (m, message) = model()
        api.routes["POST /v1/conversations/$conv/taps"] = { 204 to "" }
        api.routes["POST /v1/gifts/g1/opened"] = { 204 to "" }
        m.linkTapped(message, Block.Links.Link("l1", "Open", "kneefit://x"))
        m.giftOpened(Block.Gift("g1", "T", "", "kneefit://x", null, "f"))
        settle()
        val tap = api.last("POST /v1/conversations/$conv/taps").body!!
        assertEquals(formId.toString(), tap.getString("message_id"))
        assertEquals("l1", tap.getString("link_id"))
        assertEquals(2, tap.length())
        assertNull(api.last("POST /v1/gifts/g1/opened").body)
    }

    @Test fun aFailedReportIsIgnored() {
        val (m, message) = model()
        api.routes["POST /v1/conversations/$conv/taps"] = { 500 to "{}" }
        m.linkTapped(message, Block.Links.Link("l1", "Open", "kneefit://x"))
        settle()
        assertEquals(1, api.count("POST /v1/conversations/$conv/taps"))
    }
}

class OffersTest : MessengerHarness() {
    @Test fun optOutAndUndo() = runBlocking {
        api.routes["PATCH /v1/me"] = { 200 to """{"name":"Ana","no_promotions":true}""" }
        Messenger.setNoPromotions(true)
        assertEquals("""{"no_promotions":true}""", api.last("PATCH /v1/me").body.toString())
        assertTrue(Messenger.profile!!.noPromotions)
        // A server that doesn't echo it: the value that was saved.
        api.routes["PATCH /v1/me"] = { 200 to """{"name":"Ana"}""" }
        Messenger.setNoPromotions(false)
        assertEquals("""{"no_promotions":false}""", api.last("PATCH /v1/me").body.toString())
        assertFalse(Messenger.profile!!.noPromotions)
    }
}

class PushPermissionTest : MessengerHarness() {
    @Test fun mapping() {
        // Below Android 13: no prompt, only whether notifications are on.
        assertEquals(PushPermission.Allowed, PushPermission.of(32, granted = true, enabled = true, asked = false))
        assertEquals(PushPermission.Denied, PushPermission.of(29, granted = true, enabled = false, asked = false))
        assertEquals(PushPermission.Denied, PushPermission.of(26, granted = true, enabled = false, asked = true))
        // Android 13+.
        assertEquals(PushPermission.Allowed, PushPermission.of(33, granted = true, enabled = true, asked = false))
        assertEquals("granted, switched off in Settings", PushPermission.Denied, PushPermission.of(34, granted = true, enabled = false, asked = true))
        assertEquals(PushPermission.Denied, PushPermission.of(35, granted = false, enabled = false, asked = true))
        assertEquals(PushPermission.NotAsked, PushPermission.of(36, granted = false, enabled = false, asked = false))
        assertEquals(listOf("allowed", "denied", "not_asked"), PushPermission.entries.map { it.wire })
    }

    @Test fun sentOnlyWhenItChanged() = runBlocking {
        Messenger.syncPushPermission(PushPermission.NotAsked)
        Messenger.syncPushPermission(PushPermission.NotAsked)
        assertEquals(1, api.count("PATCH /v1/install"))
        assertEquals("""{"push_permission":"not_asked"}""", api.last("PATCH /v1/install").body.toString())
        Messenger.syncPushPermission(PushPermission.Allowed)
        Messenger.syncPushPermission(PushPermission.Allowed)
        assertEquals(2, api.count("PATCH /v1/install"))
        assertEquals("""{"push_permission":"allowed"}""", api.last("PATCH /v1/install").body.toString())
        // Kept per install, never the token itself.
        val mark = store.entries["push_permission|$account"]!!
        assertTrue(mark.endsWith("|allowed"))
        assertFalse(mark.contains("it_1"))
    }

    @Test fun aFailedSendIsTriedAgain() = runBlocking {
        api.routes["PATCH /v1/install"] = { 500 to "{}" }
        Messenger.syncPushPermission(PushPermission.Denied)
        api.routes["PATCH /v1/install"] = { 200 to "{}" }
        Messenger.syncPushPermission(PushPermission.Denied)
        Messenger.syncPushPermission(PushPermission.Denied)
        assertEquals(2, api.count("PATCH /v1/install"))
    }

    @Test fun aNewInstallGetsItAgain() = runBlocking {
        Messenger.syncPushPermission(PushPermission.Allowed)
        Messenger.logout()
        settle()
        Messenger.syncPushPermission(PushPermission.Allowed)
        val sent = api.requests.filter { it.method == "PATCH" && it.path == "/v1/install" }
        assertEquals(2, sent.size)
        assertNotNull(sent[1].token)
        assertTrue("the new install's token", sent[0].token != sent[1].token)
    }

    @Test fun withoutAContextNothingIsSent() = runBlocking {
        Messenger.syncPushPermission()
        assertEquals(0, api.count("PATCH /v1/install"))
    }
}

/** The fake API answers what spec 14 says, so the JSON stays honest. */
class WireShapesTest {
    @Test fun tapBody() {
        val id = UUID.fromString("0195f0a0-0000-7000-8000-00000000f001")
        val tap = ApiClient.tapBody(id, "l1")
        assertEquals(id.toString(), tap.getString("message_id"))
        assertEquals("l1", tap.getString("link_id"))
        assertEquals(JSONArray("""["o1"]""").toString(), JSONObject(ApiClient.formAnswerBody(FormAnswer(id, mapOf("q" to listOf("o1")))).toString())
            .getJSONObject("form_answer").getJSONObject("answers").getJSONArray("q").toString())
    }
}

/** 0.6.0: a start tile with an open conversation opens it, and nothing offers "Start a new conversation" there. */
class StartTileTest {
    private fun conv(id: UUID, category: String?, status: String = "open") = Conversation.parse(JSONObject(
        """{"id":"$id","status":"$status","category":${category?.let { "\"$it\"" } ?: "null"},"unread":0,"last_message_at":"2026-11-05T10:00:00Z"}""",
    ))

    @Test fun anOpenConversationInTheTopicOpensItWithNoNewConversationAction() {
        val id = UUID.randomUUID()
        val route = com.devreply.sdk.ui.startRoute(DevReplyCategory.Bug, listOf(conv(id, "bug")))
        assertEquals(com.devreply.sdk.ui.Route.Chat(id, DevReplyCategory.Bug), route)
        // The route carries only the conversation and its topic: no flag for a "Start a new conversation" button.
        val fields = com.devreply.sdk.ui.Route.Chat::class.java.declaredFields.map { it.name }.filter { !it.startsWith("$") }.toSet()
        assertEquals(setOf("conversationId", "category"), fields)
    }

    @Test fun otherwiseANewOneInTheTopic() {
        val closed = conv(UUID.randomUUID(), "bug", status = "closed")
        assertEquals(com.devreply.sdk.ui.Route.Chat(null, DevReplyCategory.Bug), com.devreply.sdk.ui.startRoute(DevReplyCategory.Bug, listOf(closed)))
        assertEquals(com.devreply.sdk.ui.Route.Chat(null, DevReplyCategory.Idea), com.devreply.sdk.ui.startRoute(DevReplyCategory.Idea, emptyList()))
    }
}
