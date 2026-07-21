package com.g2transcribe

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.OutputStream
import java.util.Locale

/**
 * On-device STT via ML Kit GenAI Speech Recognition, Advanced mode (Gemini).
 * Available on Pixel 10; Settings hides this option on other hardware.
 *
 * Continuous streaming — unlike Android SpeechRecognizer we do not need to
 * restart per-utterance. The library emits Partial/Final/Completed responses
 * as long as PCM keeps flowing.
 *
 *   audio in → acceptAudio(pcm) → write side of ParcelFileDescriptor pipe
 *                                 ↓
 *                                 AudioSource.fromPfd(read side)
 *                                 ↓
 *                                 SpeechRecognizer.startRecognition() Flow
 *                                 → onPartial / onFinal
 */
class MlKitGenAiSpeechEngine(private val context: Context) : SttEngine {

    private var listener: SttEngine.Listener? = null
    private var modelListener: SttEngine.ModelStatusListener? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var recognizer: SpeechRecognizer? = null
    private var sessionReadFd: ParcelFileDescriptor? = null
    @Volatile private var writeStream: OutputStream? = null
    @Volatile private var running = false
    private var sessionJob: Job? = null
    // Captured from DownloadStarted; DownloadProgress carries only bytesDownloaded.
    @Volatile private var expectedDownloadBytes: Long = 0L

    override fun setListener(l: SttEngine.Listener) { listener = l }
    override fun setModelStatusListener(l: SttEngine.ModelStatusListener?) { modelListener = l }

    override fun start() {
        if (running) return
        running = true
        scope.launch { bootstrap() }
    }

    override fun acceptAudio(pcm: ByteArray) {
        val out = writeStream ?: return
        try {
            out.write(pcm)
        } catch (e: IOException) {
            Log.v(TAG, "Pipe write skipped: ${e.message}")
        }
    }

    override fun restart() {
        if (!running) return
        scope.launch {
            closeSessionSuspend()
            if (running) startSession()
        }
    }

    override fun stop() {
        running = false
        // Fire-and-forget close so callers aren't blocked; scope stays alive
        // long enough for the close to finish, then we cancel it.
        val s = scope
        s.launch {
            try { closeSessionSuspend() } finally { s.cancel() }
        }
    }

    private suspend fun bootstrap() {
        val options: SpeechRecognizerOptions = speechRecognizerOptions {
            locale = Locale.JAPAN
            preferredMode = SpeechRecognizerOptions.Mode.MODE_ADVANCED
        }
        val rec = try {
            SpeechRecognition.getClient(options)
        } catch (e: Exception) {
            Log.e(TAG, "getClient failed: ${e.message}", e)
            modelListener?.onUnavailable("ML Kit 初期化失敗: ${e.message}")
            running = false
            return
        }
        recognizer = rec

        try {
            when (val status = rec.checkStatus()) {
                FeatureStatus.AVAILABLE -> {
                    modelListener?.onReady()
                    startSession()
                }
                FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
                    modelListener?.onDownloading(null)
                    downloadThenStart(rec)
                }
                FeatureStatus.UNAVAILABLE -> {
                    modelListener?.onUnavailable("ML Kit GenAI モデル利用不可（Pixel 10 のみ対応）")
                    running = false
                }
                else -> {
                    modelListener?.onUnavailable("ML Kit ステータス不明: $status")
                    running = false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkStatus failed: ${e.message}", e)
            modelListener?.onUnavailable("ML Kit 状態確認失敗: ${e.message}")
            running = false
        }
    }

    private suspend fun downloadThenStart(rec: SpeechRecognizer) {
        try {
            rec.download().collect { status ->
                when (status) {
                    is DownloadStatus.DownloadStarted -> {
                        expectedDownloadBytes = status.bytesToDownload
                        modelListener?.onDownloading(0)
                    }
                    is DownloadStatus.DownloadProgress -> {
                        val total = expectedDownloadBytes
                        val pct = if (total > 0) {
                            ((status.totalBytesDownloaded * 100) / total).toInt().coerceIn(0, 100)
                        } else null
                        modelListener?.onDownloading(pct)
                    }
                    is DownloadStatus.DownloadCompleted -> {
                        modelListener?.onReady()
                        startSession()
                    }
                    is DownloadStatus.DownloadFailed -> {
                        modelListener?.onUnavailable("モデルDL失敗")
                        running = false
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "download failed: ${e.message}", e)
            modelListener?.onUnavailable("モデルDL例外: ${e.message}")
            running = false
        }
    }

    private suspend fun startSession() {
        if (!running) return
        val rec = recognizer ?: return
        try {
            val pipe = ParcelFileDescriptor.createPipe()
            sessionReadFd = pipe[0]
            writeStream = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
            val readFd = pipe[0]

            val request = speechRecognizerRequest { audioSource = AudioSource.fromPfd(readFd) }
            sessionJob = scope.launch {
                try {
                    rec.startRecognition(request).collect { resp -> handleResponse(resp) }
                } catch (e: Exception) {
                    Log.w(TAG, "recognition flow ended: ${e.message}")
                    if (running) {
                        closeSessionSuspend()
                        startSession()
                    }
                }
            }
            Log.d(TAG, "Session started")
        } catch (e: Exception) {
            Log.e(TAG, "startSession failed: ${e.message}", e)
            closeSessionSuspend()
        }
    }

    private fun handleResponse(resp: SpeechRecognizerResponse) {
        when (resp) {
            is SpeechRecognizerResponse.PartialTextResponse -> {
                val text = resp.text
                if (!text.isNullOrBlank()) listener?.onPartial(text, "ja-JP")
            }
            is SpeechRecognizerResponse.FinalTextResponse -> {
                val text = resp.text
                if (!text.isNullOrBlank()) listener?.onFinal(text, "ja-JP")
            }
            is SpeechRecognizerResponse.ErrorResponse -> {
                Log.w(TAG, "ML Kit error: ${resp}")
            }
            is SpeechRecognizerResponse.CompletedResponse -> {
                Log.d(TAG, "Recognition completed")
            }
        }
    }

    private suspend fun closeSessionSuspend() {
        try { writeStream?.close() } catch (_: IOException) {}
        writeStream = null
        try { sessionReadFd?.close() } catch (_: IOException) {}
        sessionReadFd = null
        try { recognizer?.stopRecognition() } catch (e: Exception) { Log.w(TAG, "stopRecognition: ${e.message}") }
        sessionJob?.cancel()
        sessionJob = null
        if (!running) {
            try { recognizer?.close() } catch (e: Exception) { Log.w(TAG, "close: ${e.message}") }
            recognizer = null
        }
    }

    companion object {
        private const val TAG = "MlKitGenAiSpeech"
    }
}
