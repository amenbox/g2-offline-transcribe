package com.g2transcribe

/**
 * Common contract for the speech-to-text backends. The service owns one [SttEngine]
 * implementation chosen at startup from preferences; the engine is fed PCM via
 * [acceptAudio] and emits transcripts to [Listener].
 */
interface SttEngine {
    interface Listener {
        fun onPartial(text: String, language: String)
        fun onFinal(text: String, language: String)
    }

    /** Lifecycle of the underlying model (download/availability). Optional. */
    interface ModelStatusListener {
        fun onReady()
        fun onDownloading(percent: Int?)
        fun onUnavailable(reason: String)
    }

    fun setListener(l: Listener)
    fun setModelStatusListener(l: ModelStatusListener?) {}
    fun start()
    fun acceptAudio(pcm: ByteArray)
    fun restart()
    fun stop()
}
