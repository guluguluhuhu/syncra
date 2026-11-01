package com.vipin25.bubbletranslate.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.vipin25.bubbletranslate.service.BubbleOverlayService

class ProcessTextActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()

        if (!text.isNullOrBlank()) {
            val serviceIntent = Intent(this, BubbleOverlayService::class.java).apply {
                this.action = BubbleOverlayService.ACTION_TRANSLATE
                putExtra(BubbleOverlayService.EXTRA_TEXT, text)
            }
            startForegroundService(serviceIntent)
        }
        
        finish()
    }
}
