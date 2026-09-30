package com.devreply.sdk.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.devreply.sdk.Answer
import com.devreply.sdk.ApiClient
import com.devreply.sdk.Block
import com.devreply.sdk.DevReplyTheme
import com.devreply.sdk.L10n
import com.devreply.sdk.Message
import com.devreply.sdk.chosenOption
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * A question with answer buttons (spec 05, 0.5.0) in the real message row, on a device: light, the dark preset
 * and Hebrew right to left. Taps an option: the callback gets it, the request body carries the answer, the
 * user's answer appears, the chosen button stays highlighted and the others go quiet (a second tap does
 * nothing). Screenshots in the test app's files/shots. No API key, no network.
 */
@RunWith(AndroidJUnit4::class)
class ButtonsQuestionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    @After fun reset() {
        instrumentation.runOnMainSync { L10n.override = null }
    }

    private class Texts(val user: String, val question: String, val options: List<String>)

    private val english = Texts(
        "I want to cancel my subscription.",
        "Sorry to see you go! **What's the main reason?** One tap helps us most.",
        listOf("Too expensive", "Missing a feature I need", "I found another app", "Something else"),
    )
    private val hebrew = Texts(
        "אני רוצה לבטל את המנוי.",
        "חבל שאתם עוזבים! **מה הסיבה העיקרית?** הקשה אחת עוזרת לנו מאוד.",
        listOf("יקר מדי", "חסרה לי תכונה", "מצאתי אפליקציה אחרת", "משהו אחר"),
    )

    private fun message(author: String, block: JSONObject, id: UUID = UUID.randomUUID(), at: String = "2026-09-30T10:00:00Z") = Message.parse(
        JSONObject()
            .put("id", id.toString())
            .put("author", author)
            .put("created_at", at)
            .put("blocks", JSONArray().put(block)),
    )

    private val tapped = mutableListOf<Block.Buttons.Option>()
    private val bodies = mutableListOf<JSONObject>()

    /**
     * The user's message, the question and, after a tap, the user's answer: what ConversationScreen shows.
     * The tap goes where ConversationModel.answer sends it: the label as text, the answer in the body.
     */
    private fun show(palette: Palette, texts: Texts): ActivityScenario<ComponentActivity> {
        val questionId = UUID.randomUUID()
        val user = message("user", JSONObject().put("type", "text").put("text", texts.user))
        // No min_sdk: shown as buttons whatever this build's version (the fallback path is unit-tested).
        val options = JSONArray()
        texts.options.forEachIndexed { i, label -> options.put(JSONObject().put("id", "o${i + 1}").put("label", label)) }
        val question = message(
            "agent",
            JSONObject().put("type", "buttons").put("text", texts.question).put("options", options).put("fallback", texts.question),
            id = questionId,
        )
        assertTrue("parsed as buttons", question.blocks.single() is Block.Buttons)
        val messages = mutableStateOf(listOf(user, question))

        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            activity.setContent {
                CompositionLocalProvider(
                    LocalTheme provides palette,
                    LocalLayoutDirection provides if (L10n.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    Column(
                        Modifier.fillMaxSize().background(palette.background).statusBarsPadding().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        messages.value.forEach { m ->
                            MessageRow(
                                m, "Fox",
                                chosen = chosenOption(m.id, messages.value),
                                onAnswer = { option ->
                                    tapped += option
                                    val answer = Answer(m.id, option.id)
                                    bodies += ApiClient.messageBody(option.label, null, emptyList(), answer)
                                    // What the server sends back: the user's message carrying the answer.
                                    val reply = JSONObject().put("type", "text").put("text", option.label).put("answer", answer.json())
                                    messages.value = messages.value + message("user", reply, at = "2026-09-30T10:01:00Z")
                                },
                            ) {}
                        }
                    }
                }
            }
        }
        device.waitForIdle()
        Thread.sleep(800)
        return scenario
    }

    private fun snapshot(name: String) {
        val dir = instrumentation.targetContext.getExternalFilesDir("shots")!!.apply { mkdirs() }
        assertTrue("screenshot $name", device.takeScreenshot(File(dir, "$name.png")))
    }

    /** Taps [label]; checks the callback, the body and that nothing else answers afterwards. */
    private fun answer(texts: Texts, label: String, shot: String) {
        val button = device.wait(Until.findObject(By.text(label)), 5_000)
        assertNotNull("option $label", button)
        button.click()
        device.waitForIdle()
        Thread.sleep(600)
        assertEquals(listOf(label), tapped.map { it.label })
        val body = bodies.single()
        assertEquals(label, body.getString("text"))
        assertEquals("o${texts.options.indexOf(label) + 1}", body.getJSONObject("answer").getString("option_id"))
        assertTrue(body.getJSONObject("answer").getString("message_id").isNotEmpty())
        // The answer shows as the user's message; the question's button with that label stays.
        assertEquals(2, device.findObjects(By.text(label)).size)
        snapshot(shot)
        // One answer per question: the other buttons are disabled.
        device.findObject(By.text(texts.options.first { it != label })).click()
        device.waitForIdle()
        Thread.sleep(300)
        assertEquals(1, tapped.size)
    }

    @Test fun light() {
        show(Palette.light(DevReplyTheme()), english).use {
            assertNotNull(device.wait(Until.findObject(By.textContains("Sorry to see you go")), 5_000))
            assertTrue("Markdown drawn, not shown", device.findObjects(By.textContains("**")).isEmpty())
            snapshot("and-buttons-light")
            answer(english, "Missing a feature I need", "and-buttons-light-answered")
        }
    }

    @Test fun dark() {
        show(Palette.dark(DevReplyTheme.Dark), english).use {
            assertNotNull(device.wait(Until.findObject(By.textContains("Sorry to see you go")), 5_000))
            snapshot("and-buttons-dark")
            answer(english, "Too expensive", "and-buttons-dark-answered")
        }
    }

    @Test fun hebrewRightToLeft() {
        instrumentation.runOnMainSync { L10n.override = "he" }
        show(Palette.light(DevReplyTheme()), hebrew).use {
            assertNotNull(device.wait(Until.findObject(By.textContains("חבל שאתם עוזבים")), 5_000))
            snapshot("and-buttons-he")
            answer(hebrew, "חסרה לי תכונה", "and-buttons-he-answered")
        }
    }
}
