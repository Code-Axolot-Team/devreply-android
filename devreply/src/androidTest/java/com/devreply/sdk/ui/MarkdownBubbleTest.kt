package com.devreply.sdk.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.IntentFilter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.CompositionLocalProvider
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
import com.devreply.sdk.Block
import com.devreply.sdk.DevReplyTheme
import com.devreply.sdk.L10n
import com.devreply.sdk.Message
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
 * A team reply in DevReply Markdown (spec 05, 0.5.0) in the real team bubble, on a device: light, the dark
 * preset and Hebrew right to left. Saves screenshots to the test app's files/shots; taps a link (the browser
 * intent is caught) and long-presses to select. No API key, no network.
 */
@RunWith(AndroidJUnit4::class)
class MarkdownBubbleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    @After fun reset() {
        instrumentation.runOnMainSync { L10n.override = null }
    }

    private val english = "Thanks for the report! Try this:\n\n" +
        "1. Open **Settings**\n2. Tap `Export`\n3. Pick *CSV* (the ~~XLS~~ option is gone) and wait until the file is written\n\n" +
        "> The export button is greyed out\n\n" +
        "```\ncurl -H \"Authorization: Bearer sk_…\" https://api.devreply.com/v1/exports?format=csv&since=2026-09-01\n```\n\n" +
        "More: https://devreply.com/help"

    private val hebrew = "תודה על הדיווח! נסו כך:\n\n" +
        "1. פתחו את **הגדרות**\n2. לחצו על `Export`\n3. בחרו *CSV* (האפשרות ~~XLS~~ הוסרה) וחכו שהקובץ ייכתב\n\n" +
        "> כפתור הייצוא אפור\n\n" +
        "```\ncurl -H \"Authorization: Bearer sk_…\" https://api.devreply.com/v1/exports?format=csv&since=2026-09-01\n```\n\n" +
        "עוד: https://devreply.com/help"

    private fun message(author: String, block: JSONObject) = Message.parse(
        JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("author", author)
            .put("created_at", "2026-09-30T10:00:00Z")
            .put("blocks", JSONArray().put(block)),
    )

    private fun show(palette: Palette, markdown: String, question: String): ActivityScenario<ComponentActivity> {
        val user = message("user", JSONObject().put("type", "text").put("text", question))
        // No min_sdk: the block is shown as Markdown whatever this build's version.
        val reply = message("agent", JSONObject().put("type", "markdown").put("text", markdown))
        assertTrue("parsed as Markdown", reply.blocks.single() is Block.Markdown)
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
                        MessageRow(user, "Fox") {}
                        MessageRow(reply, "Fox") {}
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

    @Test fun light() {
        show(Palette.light(DevReplyTheme()), english, "How do I export my data?").use {
            assertNotNull(device.wait(Until.findObject(By.textContains("Thanks for the report")), 5_000))
            // Formatting is drawn, never shown as markers.
            assertTrue(device.findObjects(By.textContains("**")).isEmpty())
            snapshot("and-markdown-light")

            // The link opens the browser: catch the intent instead of leaving the app.
            val monitor = instrumentation.addMonitor(
                IntentFilter("android.intent.action.VIEW").apply { addDataScheme("https") },
                Instrumentation.ActivityResult(Activity.RESULT_OK, null), true,
            )
            val link = device.findObject(By.textContains("More: https://devreply.com/help"))
            assertNotNull("link", link)
            val b = link.visibleBounds
            // The paragraph wraps before the url: its last line starts with it.
            device.click(b.left + 80, b.bottom - 25)
            assertEquals("the link opened", 1, monitor.waitForActivityWithTimeout(3_000).let { monitor.hits })
            instrumentation.removeMonitor(monitor)

            // Selecting text still works: long-press a word of the first paragraph.
            val paragraph = device.findObject(By.textContains("Thanks for the report"))
            paragraph.visibleBounds.let { device.swipe(it.left + 60, it.centerY(), it.left + 60, it.centerY(), 120) }
            Thread.sleep(800)
            snapshot("and-markdown-light-selected")
        }
    }

    @Test fun dark() {
        show(Palette.dark(DevReplyTheme.Dark), english, "How do I export my data?").use {
            assertNotNull(device.wait(Until.findObject(By.textContains("Thanks for the report")), 5_000))
            snapshot("and-markdown-dark")
        }
    }

    @Test fun hebrewRightToLeft() {
        instrumentation.runOnMainSync { L10n.override = "he" }
        show(Palette.light(DevReplyTheme()), hebrew, "איך מייצאים את הנתונים?").use {
            assertNotNull(device.wait(Until.findObject(By.textContains("תודה על הדיווח")), 5_000))
            snapshot("and-markdown-he")
        }
    }
}
