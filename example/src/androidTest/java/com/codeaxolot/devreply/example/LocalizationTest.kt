package com.codeaxolot.devreply.example

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

/**
 * The chat in the app's chosen language (spec 05, "Languages"), on a device: `DevReply.setLocale`
 * (the example app passes the `devreply.locale` extra), then home, the name form, the composer and
 * the notice in that language, and a team reply arriving under its persona label.
 *
 * Arguments: `-e locale es|de`, `-e nonce …`, `-e reply true` when a script answers
 * "L10n test <nonce>" with "Reply <nonce>" from the dashboard API. Clear the app's data first.
 */
@RunWith(AndroidJUnit4::class)
class LocalizationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val args = InstrumentationRegistry.getArguments()
    private val locale: String = args.getString("locale") ?: "es"
    private val nonce: String = args.getString("nonce") ?: "0"
    private val expectReply: Boolean = args.getString("reply") == "true"

    private data class Expected(
        val greeting: String, val start: String, val replyTime: String, val kicker: String,
        val composer: String, val notice: String,
    )

    private val expected = mapOf(
        "es" to Expected("¡Hola! 👋", "Inicia una conversación", "Suele responder en 3 días hábiles", "Antes de empezar", "Mensaje…", "¡Gracias, lo recibimos!"),
        "de" to Expected("Hallo 👋", "Unterhaltung beginnen", "Antwortet meist innerhalb von 3 Werktagen", "Bevor es losgeht", "Nachricht…", "Danke, ist angekommen!"),
    ).getValue(locale)

    private fun text(s: String) = By.text(Pattern.compile(Pattern.quote(s), Pattern.CASE_INSENSITIVE))

    @Test
    fun chatInTheAppsLanguage() {
        val context = instrumentation.targetContext
        context.startActivity(
            context.packageManager.getLaunchIntentForPackage("com.codeaxolot.devreply.example")!!
                .putExtra("devreply.locale", locale)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        device.wait(Until.findObject(By.res("openMessenger")), 10_000).click()

        // Home: DevReply's default texts, and the reply time from its preset, in that language.
        assertNotNull("greeting", device.wait(Until.findObject(text(expected.greeting)), 15_000))
        assertNotNull("start title", device.findObject(text(expected.start)))
        assertNotNull("reply time", device.wait(Until.findObject(text(expected.replyTime)), 15_000))
        Thread.sleep(1_500)
        snapshot("and-l10n-$locale-home")

        device.findObject(By.res("devreply.start.question")).click()
        val composerOrName = device.wait(
            Until.findObject(By.res(Pattern.compile("devreply\\.(composer|profile\\.name)"))), 15_000,
        )
        if (composerOrName.resourceName == "devreply.profile.name") {
            assertNotNull("name form", device.findObject(text(expected.kicker)))
            composerOrName.click()
            composerOrName.text = "Mia"
            device.findObject(By.res("devreply.profile.save")).click()
        }
        assertNotNull("composer placeholder", device.wait(Until.findObject(text(expected.composer)), 10_000))
        if (!expectReply) return

        val composer = device.findObject(By.res("devreply.composer"))
        composer.click()
        composer.text = "L10n test $nonce"
        device.findObject(By.res("devreply.send")).click()
        assertNotNull("notice", device.wait(Until.findObject(text(expected.notice)), 15_000))
        assertNotNull("the team's reply arrives", device.wait(Until.findObject(By.text("Reply $nonce")), 90_000))
        device.findObject(By.res("devreply.emailask.skip"))?.click()
        if (keyboardShown()) device.pressBack()
        Thread.sleep(1_500)
        assertTrue("under its persona label", device.findObjects(By.res("devreply.persona")).isNotEmpty())
        snapshot("and-l10n-$locale-chat")
    }

    private fun keyboardShown(): Boolean {
        Thread.sleep(700)
        val out = instrumentation.uiAutomation.executeShellCommand("dumpsys input_method")
        val text = android.os.ParcelFileDescriptor.AutoCloseInputStream(out).bufferedReader().readText()
        return Regex("mInputShown=true|isInputViewShown=true").containsMatchIn(text)
    }

    private fun snapshot(name: String) {
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
        device.takeScreenshot(File(dir, "$name.png"))
    }
}
