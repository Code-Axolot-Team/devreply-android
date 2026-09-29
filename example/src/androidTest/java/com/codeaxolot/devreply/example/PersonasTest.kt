package com.codeaxolot.devreply.example

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * SDK 0.4.0 (spec 05) on a device, against the live API: who replied (persona label once per
 * group, with the photo), the app icon in the header, team faces on the home screen.
 *
 * Needs a throwaway app with an icon, two personas with photos, and a script that answers the
 * message "Persona test <nonce>" with: "Anna one", "Anna two" (persona Anna), then "Support one"
 * (another persona), `-e nonce …`. Clear the app's data first (`pm clear`).
 */
@RunWith(AndroidJUnit4::class)
class PersonasTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val pkg = "com.codeaxolot.devreply.example"
    private val nonce: String = InstrumentationRegistry.getArguments().getString("nonce") ?: "0"
    private val anna: String = InstrumentationRegistry.getArguments().getString("anna") ?: "Anna"
    private val support: String = InstrumentationRegistry.getArguments().getString("support") ?: "Fox Support"

    @Test
    fun personasIconAndTeam() {
        val context = instrumentation.targetContext
        context.startActivity(
            context.packageManager.getLaunchIntentForPackage(pkg)!!
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        device.wait(Until.findObject(By.res("openMessenger")), 10_000).click()
        device.wait(Until.findObject(By.res("devreply.start.question")), 15_000).click()
        val composerOrName = device.wait(
            Until.findObject(By.res(java.util.regex.Pattern.compile("devreply\\.(composer|profile\\.name)"))), 15_000,
        )
        if (composerOrName.resourceName == "devreply.profile.name") {
            composerOrName.click()
            composerOrName.text = "Mia"
            device.findObject(By.res("devreply.profile.save")).click()
        }
        val composer = device.wait(Until.findObject(By.res("devreply.composer")), 10_000)
        composer.click()
        composer.text = "Persona test $nonce"
        device.findObject(By.res("devreply.send")).click()
        assertNotNull("sent", device.wait(Until.findObject(By.text("Persona test $nonce")), 10_000))
        // Stay on the thread (no Back: with the keyboard already down it would leave the chat).

        // The team answers: two replies as Anna, then one as another persona.
        assertNotNull("replies arrive", device.wait(Until.findObject(By.text("Support one")), 90_000))
        // Make room: skip the optional email card and put the keyboard away (only if it's up).
        device.findObject(By.text("No thanks"))?.click()
        if (keyboardShown()) device.pressBack()
        Thread.sleep(2_000) // photos load
        assertNotNull(device.findObject(By.text("Anna one")))
        assertNotNull(device.findObject(By.text("Anna two")))
        val labels = device.findObjects(By.res("devreply.persona"))
        assertEquals("one label per group: Anna's two replies share one, the other persona gets its own", 2, labels.size)
        assertTrue(labels.any { it.hasObject(By.text(anna)) })
        assertTrue(labels.any { it.hasObject(By.text(support)) })
        assertEquals("Anna's name shows once", 1, device.findObjects(By.text(anna)).size)
        assertNotNull("the app icon in the header", device.findObject(By.res("devreply.appicon")))
        snapshot("and04-thread")

        // Home: the team's faces next to the reply time, the icon in the header.
        device.findObject(By.res("devreply.back")).click()
        assertNotNull("team faces", device.wait(Until.findObject(By.res("devreply.team")), 10_000))
        assertNotNull(device.findObject(By.res("devreply.appicon")))
        Thread.sleep(1_500)
        snapshot("and04-home")
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
