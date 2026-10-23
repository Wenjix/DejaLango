package dev.dejalango.engine.hud

/** Optional spoken output (pronunciation of a chosen reply into an earbud). May be absent on AOSP devices. */
interface VoiceOutput {
    val isAvailable: Boolean
    fun say(text: String, lang: String)
    fun stop()
    fun close() {}

    object None : VoiceOutput {
        override val isAvailable: Boolean = false
        override fun say(text: String, lang: String) {}
        override fun stop() {}
    }
}
