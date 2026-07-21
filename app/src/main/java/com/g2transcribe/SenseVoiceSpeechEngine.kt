package com.g2transcribe

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Real-time Japanese ASR powered by sherpa-onnx + SenseVoice-Small int8.
 *
 *   PCM in → acceptAudio(bytes) → Float array queued
 *     ↓
 *   worker coroutine drains queue into Silero VAD. On each VAD-emitted speech
 *   segment, invoke the SenseVoice OfflineRecognizer and dispatch the text to
 *   [SttEngine.Listener.onFinal].
 *
 * SenseVoice is non-streaming — no partials are emitted. Segments are bounded
 * by the VAD (default max ~8 s). Post-processing strips SenseVoice's
 * `<|lang|><|emotion|>...` tag prefix and collapses spurious inter-CJK spaces
 * the model sometimes inserts.
 *
 * Models must be present on disk (see [SenseVoiceModelManager]).
 */
class SenseVoiceSpeechEngine(private val context: Context) : SttEngine {

    private var listener: SttEngine.Listener? = null
    private var modelListener: SttEngine.ModelStatusListener? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var workerJob: Job? = null
    private val pcmChannel = Channel<FloatArray>(Channel.UNLIMITED)

    @Volatile private var running = false
    private var vad: Vad? = null
    private var recognizer: OfflineRecognizer? = null

    override fun setListener(l: SttEngine.Listener) { listener = l }
    override fun setModelStatusListener(l: SttEngine.ModelStatusListener?) { modelListener = l }

    override fun start() {
        if (running) return
        if (!SenseVoiceModelManager.isPresent(context)) {
            modelListener?.onUnavailable("SenseVoiceモデル未ダウンロード（設定→ダウンロード）")
            return
        }
        running = true
        workerJob = scope.launch {
            try {
                initEngines()
                modelListener?.onReady()
                runLoop()
            } catch (e: Exception) {
                Log.e(TAG, "init/run failed", e)
                modelListener?.onUnavailable("SenseVoice起動失敗: ${e.message}")
                running = false
            } finally {
                vad?.release()
                recognizer?.release()
                vad = null
                recognizer = null
            }
        }
    }

    private fun initEngines() {
        val asr = SenseVoiceModelManager.asrDir(context)
        val vadPath = SenseVoiceModelManager.vadFile(context).absolutePath

        val senseVoiceConfig = OfflineSenseVoiceModelConfig(
            model = File(asr, SenseVoiceModelManager.Files.MODEL).absolutePath,
            language = "ja",
            // ITN off — matches sherpa-onnx's own realtime SenseVoice sample and
            // trims a bit of per-segment latency (we don't do heavy number/date
            // formatting here anyway).
            useInverseTextNormalization = false,
        )
        val modelConfig = OfflineModelConfig(
            senseVoice = senseVoiceConfig,
            tokens = File(asr, SenseVoiceModelManager.Files.TOKENS).absolutePath,
            numThreads = 4,
            debug = false,
            provider = "cpu",
        )
        val recognizerConfig = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = modelConfig,
        )
        recognizer = OfflineRecognizer(config = recognizerConfig)

        val vadConfig = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = vadPath,
                threshold = 0.5f,
                // 100 ms — matches sherpa-onnx's realtime SenseVoice sample.
                // 500 ms felt sluggish and often failed to segment fast dialogue
                // (TV, quick back-and-forth conversation).
                minSilenceDuration = 0.1f,
                minSpeechDuration = 0.25f,
                maxSpeechDuration = 8.0f,
                windowSize = 512,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
            debug = false,
            provider = "cpu",
        )
        vad = Vad(config = vadConfig)
        Log.i(TAG, "sensevoice + vad initialized")
    }

    private suspend fun runLoop() {
        val v = vad!!
        val r = recognizer!!
        while (running) {
            val incoming = pcmChannel.receiveCatching().getOrNull() ?: break
            v.acceptWaveform(incoming)
            while (!v.empty()) {
                val segment = v.front()
                v.pop()
                if (segment.samples.isEmpty()) continue
                val stream = r.createStream()
                try {
                    stream.acceptWaveform(segment.samples, SAMPLE_RATE)
                    r.decode(stream)
                    val raw = r.getResult(stream).text
                    val cleaned = postProcess(raw)
                    if (cleaned.isNotBlank()) {
                        Log.d(TAG, "segment ${segment.samples.size} samples → '${cleaned.take(60)}'")
                        listener?.onFinal(cleaned, "ja-JP")
                    }
                } finally {
                    stream.release()
                }
            }
        }
        Log.w(TAG, "sensevoice loop exited — running=$running")
    }

    override fun acceptAudio(pcm: ByteArray) {
        if (!running) return
        val n = pcm.size / 2
        val out = FloatArray(n)
        val bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) {
            out[i] = bb.short.toFloat() / 32768f
        }
        pcmChannel.trySend(out)
    }

    override fun restart() { /* stateless across segments */ }

    override fun stop() {
        running = false
        workerJob?.cancel()
        workerJob = null
        pcmChannel.close()
        scope.launch { scope.cancel() }
    }

    /**
     * SenseVoice raw output looks like `<|ja|><|NEUTRAL|><|Speech|><|withitn|>こんにちは`.
     * Additionally the model sometimes emits spaces between CJK characters
     * ("こ ん に ち は") which need collapsing for readable Japanese output.
     */
    private fun postProcess(raw: String): String {
        val noTags = TAG_PREFIX.replace(raw, "")
        val cjkSpaceCollapsed = CJK_SPACE.replace(noTags) { m ->
            m.value.first().toString() + m.value.last().toString()
        }
        return cjkSpaceCollapsed.trim()
    }

    companion object {
        private const val TAG = "SenseVoiceSpeechEngine"
        private const val SAMPLE_RATE = 16_000

        private val TAG_PREFIX = Regex("""<\|[^|>]*\|>""")
        // A CJK code-point, then one-or-more spaces, then another CJK code-point.
        // Matches per pair; caller replaces the span with just the two chars.
        private val CJK_SPACE = Regex("""([\p{IsHan}\p{IsHiragana}\p{IsKatakana}])\s+(?=[\p{IsHan}\p{IsHiragana}\p{IsKatakana}])""")
    }
}
