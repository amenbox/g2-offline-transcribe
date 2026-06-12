package com.g2transcribe

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.io.IOException
import java.io.OutputStream

/**
 * Android SpeechRecognizer driven by a PCM pipe.
 *
 *   G2 mic (BLE LC3) → DeviceManager.handlePcm() → acceptAudio(pcm)
 *     → write side of ParcelFileDescriptor pipe
 *     ↓
 *     read side → SpeechRecognizer (on-device, ja-JP, offline)
 *     → onResults / onPartialResults → Listener
 *
 * SpeechRecognizer is designed for single utterances and must be restarted to
 * keep transcribing continuously — that restart loop is implemented here.
 */
class SpeechEngine(private val context: Context) : SttEngine {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var listener: SttEngine.Listener? = null
    private var modelListener: SttEngine.ModelStatusListener? = null
    private var recognizer: SpeechRecognizer? = null
    private var sessionReadFd: ParcelFileDescriptor? = null
    @Volatile private var writeStream: OutputStream? = null
    @Volatile private var running = false

    override fun setListener(l: SttEngine.Listener) { listener = l }
    override fun setModelStatusListener(l: SttEngine.ModelStatusListener?) { modelListener = l }

    override fun start() {
        if (running) return
        running = true
        mainHandler.post {
            startSession()
            // OS-bundled recognizer is always available once started.
            modelListener?.onReady()
        }
    }

    override fun acceptAudio(pcm: ByteArray) {
        val out = writeStream ?: return
        try {
            out.write(pcm)
        } catch (e: IOException) {
            // Pipe closed between sessions — next session will reopen it
            Log.v(TAG, "Pipe write skipped: ${e.message}")
        }
    }

    override fun restart() {
        if (!running) return
        mainHandler.post {
            closeSession()
            if (running) startSession()
        }
    }

    override fun stop() {
        running = false
        mainHandler.post { closeSession() }
    }

    private fun startSession() {
        if (!running) return
        try {
            val pipe = ParcelFileDescriptor.createPipe()
            sessionReadFd = pipe[0]
            writeStream = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])

            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
            recognizer = rec
            rec.setRecognitionListener(InnerListener())

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, sessionReadFd)
                putExtra(
                    RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            }
            rec.startListening(intent)
            Log.d(TAG, "Session started")
        } catch (e: Exception) {
            Log.e(TAG, "startSession failed: ${e.message}", e)
            closeSession()
        }
    }

    private fun closeSession() {
        mainHandler.removeCallbacks(forceChunkRunnable)
        try { writeStream?.close() } catch (_: IOException) {}
        writeStream = null
        try { sessionReadFd?.close() } catch (_: IOException) {}
        sessionReadFd = null
        recognizer?.destroy()
        recognizer = null
    }

    private val forceChunkRunnable = Runnable {
        Log.d(TAG, "force chunk: ${FORCE_CHUNK_AFTER_MS / 1000}s without end-of-speech — stopping listening")
        try { recognizer?.stopListening() } catch (e: Exception) { Log.w(TAG, "stopListening: ${e.message}") }
    }

    private inner class InnerListener : RecognitionListener {
        private var lastPartialText: String? = null
        // Bail out if we get nothing but errors back-to-back (e.g. Pixel on-device
        // SpeechRecognizer + AudioPlaybackCapture stream: ERROR_SERVER_DISCONNECTED
        // loops at ~10ms forever). Reset on any sign of life.
        private var consecutiveErrors = 0
        // After onEndOfSpeech the on-device recognizer often emits one more partial
        // identical to the just-finalized text. Suppress it within this window so it
        // doesn't create a duplicate entry.
        private var staleFinalText: String? = null
        private var staleFinalAt: Long = 0L
        // Set by onEndOfSpeech (or force-chunk path) so the following onResults doesn't
        // emit a duplicate final.
        private var endOfSpeechFinalized = false

        override fun onReadyForSpeech(p: Bundle?) { Log.d(TAG, "onReadyForSpeech") }
        override fun onBeginningOfSpeech() {
            Log.d(TAG, "onBeginningOfSpeech")
            lastPartialText = null
            endOfSpeechFinalized = false
            mainHandler.removeCallbacks(forceChunkRunnable)
            mainHandler.postDelayed(forceChunkRunnable, FORCE_CHUNK_AFTER_MS)
        }
        override fun onRmsChanged(rms: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            Log.d(TAG, "onEndOfSpeech")
            mainHandler.removeCallbacks(forceChunkRunnable)
            // On-device recognizer keeps one session across utterances and rarely calls
            // onResults. Treat end-of-speech as the per-utterance finalize signal so each
            // utterance becomes its own entry on the phone.
            val text = lastPartialText
            lastPartialText = null
            if (!text.isNullOrBlank()) {
                listener?.onFinal(text, "ja-JP")
                staleFinalText = text
                staleFinalAt = System.currentTimeMillis()
                endOfSpeechFinalized = true
            }
        }
        override fun onEvent(eventType: Int, p: Bundle?) {}

        override fun onError(error: Int) {
            val name = when (error) {
                1 -> "NETWORK_TIMEOUT"
                2 -> "NETWORK"
                3 -> "AUDIO"
                4 -> "SERVER"
                5 -> "CLIENT"
                6 -> "SPEECH_TIMEOUT"
                7 -> "NO_MATCH"
                8 -> "RECOGNIZER_BUSY"
                9 -> "INSUFFICIENT_PERMISSIONS"
                10 -> "TOO_MANY_REQUESTS"
                11 -> "SERVER_DISCONNECTED"
                12 -> "LANGUAGE_NOT_SUPPORTED"
                13 -> "LANGUAGE_UNAVAILABLE"
                else -> "UNKNOWN"
            }
            Log.w(TAG, "onError: $error ($name) lastPartial=${lastPartialText?.take(20)}")
            mainHandler.removeCallbacks(forceChunkRunnable)
            lastPartialText?.let { listener?.onFinal(it, "ja-JP") }
            lastPartialText = null
            mainHandler.post {
                closeSession()
                if (running) startSession()
            }
        }

        override fun onResults(results: Bundle?) {
            mainHandler.removeCallbacks(forceChunkRunnable)
            if (!endOfSpeechFinalized) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    listener?.onFinal(text, "ja-JP")
                } else {
                    lastPartialText?.let { listener?.onFinal(it, "ja-JP") }
                }
            }
            endOfSpeechFinalized = false
            lastPartialText = null
            mainHandler.post {
                closeSession()
                if (running) startSession()
            }
        }

        override fun onPartialResults(partial: Bundle?) {
            val text = partial
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            Log.d(TAG, "onPartialResults: ${text ?: "<null>"}")
            if (text.isNullOrBlank()) return
            if (text == staleFinalText &&
                System.currentTimeMillis() - staleFinalAt < STALE_PARTIAL_WINDOW_MS) {
                Log.d(TAG, "dropping stale partial echo")
                return
            }
            staleFinalText = null
            lastPartialText = text
            listener?.onPartial(text, "ja-JP")
        }
    }

    companion object {
        private const val TAG = "SpeechEngine"
        private const val STALE_PARTIAL_WINDOW_MS = 700L
        private const val FORCE_CHUNK_AFTER_MS = 30_000L
    }
}
