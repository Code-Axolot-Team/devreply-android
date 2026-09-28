package com.codeaxolot.devreply.example

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Drives the real messenger against the live API: open → start a bug report → (name first) → send →
 * see it delivered and listed. Screenshots land in the app's external files dir, `shots/`.
 */
@RunWith(AndroidJUnit4::class)
class MessengerUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val pkg = "com.codeaxolot.devreply.example"

    @Before
    fun launch() {
        val context = instrumentation.targetContext
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        assertNotNull("demo app", device.wait(Until.findObject(By.res("openMessenger")), 10_000))
    }

    @Test
    fun sendMessageFromNativeMessenger() {
        snapshot("1-app")
        device.findObject(By.res("openMessenger")).click()
        val tile = device.wait(Until.findObject(By.res("devreply.start.bug")), 15_000)
        assertNotNull("messenger home with start buttons", tile)
        Thread.sleep(1_000)
        snapshot("2-messenger-home")

        tile.click()
        // Name first, unless this install already gave one.
        val composerOrName = device.wait(
            Until.findObject(By.res(java.util.regex.Pattern.compile("devreply\\.(composer|profile\\.name)"))), 15_000,
        )
        assertNotNull("composer or name form", composerOrName)
        if (composerOrName.resourceName == "devreply.profile.name") {
            snapshot("3a-name-first")
            composerOrName.click()
            composerOrName.text = "Android Tester"
            device.findObject(By.res("devreply.profile.email")).text = "android@example.com"
            device.findObject(By.res("devreply.profile.save")).click()
        }
        val composer = device.wait(Until.findObject(By.res("devreply.composer")), 10_000)
        assertNotNull("composer", composer)
        snapshot("3-new-conversation")

        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val text = "Test from the native Android messenger ($time)"
        composer.click()
        composer.text = text
        device.findObject(By.res("devreply.send")).click()
        assertNotNull("message on screen", device.wait(Until.findObject(By.text(text)), 10_000))
        assertNotNull("delivered", device.wait(Until.gone(By.text("SENDING…")), 15_000))
        assertFalse("no send error", device.hasObject(By.textContains("Tap to retry")))
        device.pressBack() // hides the keyboard
        Thread.sleep(800)
        snapshot("4-sent")

        back()
        assertNotNull("listed under Your conversations", device.wait(Until.findObject(By.text(text)), 10_000))
        Thread.sleep(500)
        snapshot("5-home-with-conversation")
    }

    private fun back() {
        val button: UiObject2? = device.findObject(By.res("devreply.back"))
        if (button != null) button.click() else device.pressBack()
    }

    private fun snapshot(name: String) {
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
        device.takeScreenshot(File(dir, "$name.png"))
    }
}
