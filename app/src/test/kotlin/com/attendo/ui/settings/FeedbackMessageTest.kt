package com.attendo.ui.settings

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the feedback flow promises when the phone has no mail app.
 *
 * The intent itself — ACTION_SENDTO over `mailto:`, launched as a plain implicit intent
 * so Android's own resolution picks the chooser or the default handler — is Android
 * framework behaviour that a JVM test cannot exercise; the phone is where that part was
 * verified. What *is* this app's to keep honest is the promise that a missing mail app
 * is answered on screen rather than with silence, and this test pins that answer: the
 * message must exist, be a sentence, and point at the alternative that is still one tap
 * away in the dialog behind it.
 */
class FeedbackMessageTest {

    @Test
    fun `a missing mail app is answered with a sentence pointing at the alternative`() {
        assertTrue(NO_MAIL_APP_MESSAGE.isNotBlank())
        assertTrue(NO_MAIL_APP_MESSAGE.endsWith("."))
        assertTrue("says an email app is missing", NO_MAIL_APP_MESSAGE.contains("No email app"))
        assertTrue("points at the GitHub alternative", NO_MAIL_APP_MESSAGE.contains("GitHub"))
    }
}
