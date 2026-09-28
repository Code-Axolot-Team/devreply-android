package com.codeaxolot.devreply.example

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * The same flows as the iOS UI tests, against the live API, on a device. Build the demo against a
 * throwaway app (`-Pdevreply.pk=…`) and clear its data first (`pm clear`), so the name form shows.
 * The bubble test needs a script that replies "Founder reply <nonce>", then "… again" (`-e nonce …`).
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ChatFlowsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val pkg = "com.codeaxolot.devreply.example"
    private val nonce: String = InstrumentationRegistry.getArguments().getString("nonce") ?: "0"

    @Before
    fun launch() = relaunch()

    private fun relaunch() {
        val context = instrumentation.targetContext
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)!!
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        assertNotNull("demo app", device.wait(Until.findObject(By.res("openMessenger")), 10_000))
    }

    /** First message: the notice with the reply time, then the optional email card; the keyboard stays up. */
    @Test
    fun a_firstMessageNoticeEmailAskAndKeyboard() {
        startNew("idea", "Android notice test $nonce")
        val notice = device.wait(Until.findObject(By.res("devreply.notice")), 10_000)
        assertNotNull("we got it, under the first message", notice)
        assertTrue(device.hasObject(By.textContains("Please allow up to 3 working days for a reply")))
        assertTrue("sending keeps the keyboard up", keyboardShown())
        val field = device.wait(Until.findObject(By.res("devreply.emailask.field")), 5_000)
        assertNotNull("optional email card", field)
        Thread.sleep(600)
        snapshot("a1-notice-email-ask")

        // A long drag down through the thread hides the keyboard.
        dragDownThroughThread()
        assertFalse("a long drag hides the keyboard", keyboardShown())
        snapshot("a2-keyboard-hidden")

        field.click()
        field.text = "not-an-email"
        device.findObject(By.res("devreply.emailask.save")).click()
        assertNotNull("error shown", device.wait(Until.findObject(By.textContains("email")), 5_000))
        device.findObject(By.res("devreply.emailask.field")).text = "android-$nonce@example.com"
        device.findObject(By.res("devreply.emailask.save")).click()
        assertTrue("card goes away", device.wait(Until.gone(By.res("devreply.emailask.field")), 10_000))
        assertNotNull(device.wait(Until.findObject(By.textContains("email you at android-$nonce@example.com")), 5_000))
        hideKeyboard()
        snapshot("a3-email-saved")

        // A new request doesn't ask again.
        back()
        device.wait(Until.findObject(By.res("devreply.start.question")), 10_000).click()
        val composer = device.wait(Until.findObject(By.res("devreply.composer")), 10_000)
        composer.click()
        composer.text = "Another question $nonce"
        device.findObject(By.res("devreply.send")).click()
        assertNotNull(device.wait(Until.findObject(By.res("devreply.notice")), 10_000))
        assertFalse("we have the email now", device.wait(Until.hasObject(By.res("devreply.emailask.field")), 3_000))
    }

    /** A reply while the app is open and the chat closed: the bubble. Swipe hides it until the next reply; tap opens. */
    @Test
    fun b_unreadBubbleOpensTheReply() {
        startNew("question", "Bubble test $nonce")
        device.wait(Until.findObject(By.res("devreply.emailask.skip")), 5_000)?.click()
        hideKeyboard()
        back()
        device.wait(Until.findObject(By.res("devreply.close")), 5_000).click()
        assertNotNull(device.wait(Until.findObject(By.res("openMessenger")), 5_000))
        assertFalse("nothing unread yet", device.hasObject(By.res("devreply.bubble")))

        val bubble = device.wait(Until.findObject(By.res("devreply.bubble")), 90_000)
        assertNotNull("the reply shows up as the bubble", bubble)
        Thread.sleep(800)
        assertEquals("New reply from Knee Coach", bubble.contentDescription)
        val b = bubble.visibleBounds
        assertTrue("bottom right", b.centerX() > device.displayWidth * 0.75 && b.centerY() > device.displayHeight * 0.6)
        snapshot("b1-bubble")

        device.swipe(b.centerX(), b.centerY(), device.displayWidth - 4, b.centerY(), 20)
        assertTrue("a swipe hides it", device.wait(Until.gone(By.res("devreply.bubble")), 3_000))
        snapshot("b2-swiped")

        // The script replies again: the bubble is back, with both.
        val again = device.wait(Until.findObject(By.res("devreply.bubble")), 90_000)
        assertNotNull("a new reply brings it back", again)
        Thread.sleep(800)
        assertEquals("2 new replies from Knee Coach", again.contentDescription)
        snapshot("b2b-back-with-two")
        again.click()
        assertNotNull("opens the reply", device.wait(Until.findObject(By.text("Founder reply $nonce again")), 15_000))
        assertFalse("hidden while the chat is open", device.hasObject(By.res("devreply.bubble")))
        snapshot("b3-opened")
        back()
        device.wait(Until.findObject(By.res("devreply.close")), 5_000).click()
        assertNotNull(device.wait(Until.findObject(By.res("openMessenger")), 5_000))
        Thread.sleep(2_000)
        assertFalse("read: gone", device.hasObject(By.res("devreply.bubble")))
        snapshot("b4-read")
    }

    /**
     * Keyboard up, a long chat: every new message, sent or received, shows right above the composer,
     * never behind it. The script answers "Many test <nonce> 8" with "Founder reply <nonce>".
     */
    @Test
    fun c_newMessagesStayAboveTheComposer() {
        startNew("bug", "Many test $nonce 1")
        device.wait(Until.findObject(By.res("devreply.emailask.skip")), 5_000)?.click()
        val composer = device.wait(Until.findObject(By.res("devreply.composer")), 5_000)
        for (i in 2..8) {
            composer.click()
            composer.text = "Many test $nonce $i"
            device.findObject(By.res("devreply.send")).click()
            val sent = device.wait(Until.findObject(By.text("Many test $nonce $i")), 10_000)
            assertNotNull("message $i on screen", sent)
            Thread.sleep(700)
            val top = device.findObject(By.res("devreply.composer")).visibleBounds.top
            val msg = device.findObject(By.text("Many test $nonce $i"))
            assertNotNull("message $i still visible", msg)
            assertTrue("message $i above the composer (${msg.visibleBounds} vs $top)", msg.visibleBounds.bottom <= top)
        }
        assertTrue("keyboard still up", keyboardShown())
        snapshot("c1-sent-with-keyboard")
        // A reply arrives while the keyboard is up.
        val reply = device.wait(Until.findObject(By.text("Founder reply $nonce")), 90_000)
        assertNotNull("the reply arrives", reply)
        Thread.sleep(800)
        val top = device.findObject(By.res("devreply.composer")).visibleBounds.top
        val r = device.findObject(By.text("Founder reply $nonce"))
        assertTrue("the reply shows above the composer (${r.visibleBounds} vs $top)", r.visibleBounds.bottom <= top && r.visibleBounds.height() > 20)
        snapshot("c2-reply-with-keyboard")
    }

    private fun startNew(category: String, text: String) {
        device.findObject(By.res("openMessenger")).click()
        device.wait(Until.findObject(By.res("devreply.start.$category")), 15_000).click()
        val composerOrName = device.wait(
            Until.findObject(By.res(java.util.regex.Pattern.compile("devreply\\.(composer|profile\\.name)"))), 15_000,
        )
        if (composerOrName.resourceName == "devreply.profile.name") {
            snapshot("0-name-form")
            composerOrName.click()
            composerOrName.text = "Android Tester"
            device.findObject(By.res("devreply.profile.save")).click()
        }
        val composer = device.wait(Until.findObject(By.res("devreply.composer")), 10_000)
        composer.click()
        composer.text = text
        device.findObject(By.res("devreply.send")).click()
        assertNotNull("sent", device.wait(Until.findObject(By.text(text)), 10_000))
    }

    private fun dragDownThroughThread() {
        val top = device.displayHeight / 4
        device.drag(device.displayWidth / 2, top, device.displayWidth / 2, top + device.displayHeight / 3, 40)
        Thread.sleep(800)
    }

    private fun keyboardShown(): Boolean {
        Thread.sleep(700)
        val out = instrumentation.uiAutomation.executeShellCommand("dumpsys input_method")
        val text = android.os.ParcelFileDescriptor.AutoCloseInputStream(out).bufferedReader().readText()
        return Regex("mInputShown=true|isInputViewShown=true").containsMatchIn(text)
    }

    private fun hideKeyboard() {
        if (keyboardShown()) device.pressBack()
        Thread.sleep(600)
    }

    private fun back() {
        val button: UiObject2? = device.findObject(By.res("devreply.back"))
        if (button != null) button.click() else device.pressBack()
        Thread.sleep(500)
    }

    private fun snapshot(name: String) {
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
        device.takeScreenshot(File(dir, "$name.png"))
    }
}
