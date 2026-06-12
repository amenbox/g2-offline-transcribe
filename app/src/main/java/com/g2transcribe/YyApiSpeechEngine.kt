package com.g2transcribe

import android.content.Context
import android.util.Log
import com.google.protobuf.ByteString
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.okhttp.OkHttpChannelBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import yysystem.StreamRequest
import yysystem.StreamResponse
import yysystem.StreamingConfig
import yysystem.YYSpeechEventType
import yysystem.YYSpeechGrpcKt
import java.util.concurrent.TimeUnit

/**
 * YYAPIs cloud STT (gRPC bidirectional streaming).
 *
 *   G2 mic (BLE LC3) → DeviceManager.handlePcm() → acceptAudio(pcm)
 *     → MutableSharedFlow<StreamRequest>
 *     ↓
 *     gRPC RecognizeStream(...) → Flow<StreamResponse>
 *     → Listener
 *
 * Audio: LINEAR16 PCM, 16 kHz mono — matches the G2 mic pipeline byte-for-byte.
 * Language: Japanese (language_code = 4). Model: 11 (会話モデル).
 */
class YyApiSpeechEngine(
    private val context: Context,
    private val apiKey: String,
) : SttEngine {

    private var listener: SttEngine.Listener? = null
    private var modelListener: SttEngine.ModelStatusListener? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sessionJob: Job? = null
    private var channel: ManagedChannel? = null

    // The first StreamRequest must carry streaming_config; subsequent ones carry audiobytes.
    // SharedFlow lets acceptAudio() emit from any thread without suspending.
    private val requestFlow = MutableSharedFlow<StreamRequest>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    @Volatile private var running = false

    // Hold a SPEECH_RECOGNIZED final until the matching TEXT_REVISED arrives, so
    // the user sees the LLM-corrected text as the committed line rather than two
    // entries (raw then revised). Fall back to the raw text on timeout.
    private var pendingFinalText: String? = null
    private var pendingFinalJob: Job? = null

    override fun setListener(l: SttEngine.Listener) { listener = l }
    override fun setModelStatusListener(l: SttEngine.ModelStatusListener?) { modelListener = l }

    override fun start() {
        if (running) return
        if (apiKey.isBlank()) {
            modelListener?.onUnavailable("YYAPIs API キー未設定（設定画面で入力してください）")
            return
        }
        running = true
        scope.launch { startSession() }
    }

    private suspend fun startSession() {
        try {
            channel = OkHttpChannelBuilder.forAddress(HOST, PORT)
                .useTransportSecurity()
                .build()
            val metadata = Metadata().apply {
                put(Metadata.Key.of("yyapis-api-key", Metadata.ASCII_STRING_MARSHALLER), apiKey)
            }
            val stub = YYSpeechGrpcKt.YYSpeechCoroutineStub(channel!!)

            // Seed the request stream with the streaming_config message before any audio.
            val config = StreamingConfig.newBuilder()
                .setEncoding("LINEAR16")
                .setSampleRateHertz(16000)
                .setAudioChannelCount(1)
                .setLanguageCode(4)            // 4 = ja-JP
                .setModel(11)                  // 11 = conversation
                .setEnableInterimResults(true)
                .setReviseTexts(true)
                .setSegmentationSilenceTimeoutMs(300)
                .build()
            val seed = StreamRequest.newBuilder().setStreamingConfig(config).build()
            requestFlow.emit(seed)

            modelListener?.onReady()
            Log.d(TAG, "YYAPIs session started")

            sessionJob = scope.launch {
                try {
                    stub.recognizeStream(requestFlow.asSharedFlow(), metadata).collect { resp ->
                        dispatchResponse(resp)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "stream error: ${e.message}")
                    if (running) {
                        modelListener?.onUnavailable("通信エラー: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "startSession failed", e)
            modelListener?.onUnavailable("接続エラー: ${e.message}")
        }
    }

    private fun dispatchResponse(resp: StreamResponse) {
        when (resp.eventType) {
            YYSpeechEventType.ERROR -> {
                Log.w(TAG, "YYAPIs ERROR: ${resp.error.message}")
            }
            YYSpeechEventType.SPEECH_RECOGNIZED -> {
                val result = resp.result ?: return
                val text = result.transcript
                if (text.isBlank()) return

                if (result.isFinal) {
                    // Surface the raw text as a partial so the user sees something, but
                    // hold off committing — wait for TEXT_REVISED to deliver the corrected
                    // version as the actual final.
                    pendingFinalText = text
                    listener?.onPartial(text, "ja-JP")
                    pendingFinalJob?.cancel()
                    pendingFinalJob = scope.launch {
                        delay(REVISE_TIMEOUT_MS)
                        val held = pendingFinalText
                        if (held != null) {
                            pendingFinalText = null
                            listener?.onFinal(held, "ja-JP")
                        }
                    }
                } else {
                    // New utterance starting while a previous raw final is still pending
                    // (TEXT_REVISED hasn't arrived). Commit the held one as final now so
                    // this incoming partial creates a fresh entry instead of overwriting it.
                    val held = pendingFinalText
                    if (held != null) {
                        pendingFinalJob?.cancel()
                        pendingFinalText = null
                        listener?.onFinal(held, "ja-JP")
                    }
                    listener?.onPartial(text, "ja-JP")
                }
            }
            YYSpeechEventType.TEXT_REVISED -> {
                if (pendingFinalText == null) return  // already committed raw, revise too late
                val result = resp.result ?: return
                val text = result.transcript
                if (text.isBlank()) return
                pendingFinalJob?.cancel()
                pendingFinalText = null
                listener?.onFinal(text, "ja-JP")
            }
            else -> { /* ignore deprecated/unhandled events */ }
        }
    }

    override fun acceptAudio(pcm: ByteArray) {
        if (!running) return
        val req = StreamRequest.newBuilder()
            .setAudiobytes(ByteString.copyFrom(pcm))
            .build()
        // tryEmit on a SharedFlow with extraBuffer never suspends.
        requestFlow.tryEmit(req)
    }

    override fun restart() {
        if (!running) return
        scope.launch {
            stop()
            running = true
            startSession()
        }
    }

    override fun stop() {
        running = false
        sessionJob?.cancel()
        sessionJob = null
        try { channel?.shutdown()?.awaitTermination(2, TimeUnit.SECONDS) } catch (_: Exception) {}
        channel = null
        scope.launch { scope.cancel() }
    }

    companion object {
        private const val TAG = "YyApiSpeechEngine"
        private const val HOST = "api-grpc-2.yysystem2021.com"
        private const val PORT = 443
        private const val REVISE_TIMEOUT_MS = 800L
    }
}
