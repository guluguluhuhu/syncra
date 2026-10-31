package com.vipin25.bubbletranslate.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.vipin25.bubbletranslate.service.BubbleOverlayService

class ProcessTextActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        val isCheckLink = intent.component?.className?.contains("CheckLink") == true
        
        val action = if (isCheckLink) {
            BubbleOverlayService.ACTION_CHECK_LINK
        } else {
            BubbleOverlayService.ACTION_TRANSLATE
        }

        if (!text.isNullOrBlank()) {
            val serviceIntent = Intent(this, BubbleOverlayService::class.java).apply {
                this.action = action
                putExtra(BubbleOverlayService.EXTRA_TEXT, text)
            }
            startForegroundService(serviceIntent)
        }
        
        finish()
    }
}
