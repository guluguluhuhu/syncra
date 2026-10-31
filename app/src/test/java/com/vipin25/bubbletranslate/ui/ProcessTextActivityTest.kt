package com.vipin25.bubbletranslate.ui

import android.content.Intent
import com.vipin25.bubbletranslate.service.BubbleOverlayService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ProcessTextActivityTest {

    @Test
    fun given_translateIntent_when_onCreate_then_startsServiceWithActionTranslate() {
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            putExtra(Intent.EXTRA_PROCESS_TEXT, "Hello world")
        }
        
        val controller = Robolectric.buildActivity(ProcessTextActivity::class.java, intent)
        val activity = controller.create().get()

        val shadowActivity = shadowOf(activity)
        val startedIntent = shadowActivity.nextStartedService
        
        assertTrue(startedIntent != null)
        assertEquals(BubbleOverlayService.ACTION_TRANSLATE, startedIntent.action)
        assertEquals("Hello world", startedIntent.getStringExtra(BubbleOverlayService.EXTRA_TEXT))
        assertTrue(activity.isFinishing)
    }
}
