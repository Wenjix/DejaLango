package dev.dejalango.app.platform.tts

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.util.Log
import dev.dejalango.engine.hud.VoiceOutput
import java.util.Locale

/**
 * Android TextToSpeech behind the engine's [VoiceOutput]. On an AOSP device with no
 * TTS engine (Horizon OS), [isAvailable] stays false and [say] is a no-op.
 */
class AndroidVoiceOutput(context: Context) : VoiceOutput {

    @Volatile
    override var isAvailable: Boolean = false
        private set

    private var tts: TextToSpeech? = null
    private var currentLang: String? = null

    init {
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                isAvailable = status == TextToSpeech.SUCCESS
                if (isAvailable) {
                    tts?.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                }
                Log.i(TAG, "TTS init: ${if (isAvailable) "ok" else "unavailable ($status)"}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "TTS engine missing: ${t.message}")
        }
    }

    override fun say(text: String, lang: String) {
        val t = tts ?: return
        if (!isAvailable || text.isBlank()) return
        if (lang != currentLang) {
            val r = t.setLanguage(Locale.forLanguageTag(lang))
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "TTS language $lang not available")
                return
            }
            currentLang = lang
        }
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "dejalango-${System.currentTimeMillis()}")
    }

    override fun stop() {
        tts?.stop()
    }

    override fun close() {
        tts?.shutdown()
        tts = null
        isAvailable = false
    }

    private companion object {
        const val TAG = "AndroidVoiceOutput"
    }
}
