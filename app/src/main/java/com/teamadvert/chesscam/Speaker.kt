package com.teamadvert.chesscam

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Text-to-speech in English; lines spoken before the engine is ready are queued. */
class Speaker(context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private val queued = mutableListOf<String>()

    var enabled = true

    /** The last line spoken, for the Repeat button. */
    var lastLine: String? = null
        private set

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val result = tts.setLanguage(Locale.US)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.ENGLISH)
        }
        ready = true
        queued.forEach { speak(it, TextToSpeech.QUEUE_ADD) }
        queued.clear()
    }

    /** Speaks [text]; with [interrupt] anything still being said is cut off first. */
    fun say(text: String, interrupt: Boolean = false) {
        lastLine = text
        if (!enabled) return
        if (!ready) {
            if (interrupt) queued.clear()
            queued += text
            return
        }
        speak(text, if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD)
    }

    fun repeat() {
        val line = lastLine ?: return
        if (enabled && ready) speak(line, TextToSpeech.QUEUE_FLUSH)
    }

    fun stop() {
        queued.clear()
        if (ready) tts.stop()
    }

    fun shutdown() {
        tts.shutdown()
    }

    private fun speak(text: String, mode: Int) {
        tts.speak(text, mode, null, text.hashCode().toString())
    }
}
