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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * SDK 0.4.4: `present(context, category, message, attributes)` opens a new conversation with the message
 * in the composer, not sent. The demo's "Report a bug" link calls it. Needs an install that already gave
 * its name (run MessengerUiTest first on a fresh install).
 */
@RunWith(AndroidJUnit4::class)
class PresentTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val pkg = "com.codeaxolot.devreply.example"

    @Before
    fun launch() {
        val context = instrumentation.targetContext
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)!!
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        assertNotNull("demo app", device.wait(Until.findObject(By.res("reportBug")), 10_000))
    }

    @Test
    fun prefilledComposerIsNotSent() {
        device.findObject(By.res("reportBug")).click()
        val composer = device.wait(Until.findObject(By.res("devreply.composer")), 15_000)
        assertNotNull("new bug report with the composer", composer)
        assertEquals("On the demo screen, ", device.findObject(By.res("devreply.composer")).text)
        assertTrue("nothing sent by itself", device.findObjects(By.text("SENDING…")).isEmpty())
        Thread.sleep(800)
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
        device.takeScreenshot(File(dir, "present-prefilled.png"))
    }
}
