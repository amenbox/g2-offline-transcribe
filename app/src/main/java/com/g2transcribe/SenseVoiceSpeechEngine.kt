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
 *   worker coroutine drains queue into Silero VAD. Two decode paths run off
 *   the same worker:
 *     - final: on each VAD-emitted segment (SenseVoice's natural granularity)
 *       → dispatched via [SttEngine.Listener.onFinal].
 *     - partial: while VAD reports speech in progress, re-decode the growing
 *       segment every [PARTIAL_INTERVAL_MS] and dispatch via onPartial.
 *       Without this, maxSpeechDuration (8 s) means continuous speech shows
 *       nothing on the glasses until the VAD finally cuts.
 *
 * Post-processing strips SenseVoice's `<|lang|><|emotion|>...` tag prefix and
 * collapses spurious inter-CJK spaces the model sometimes inserts.
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
            // ITN on — "にせんにじゅうろくねん" → "2026年" makes captions much easier
            // to skim on the glasses. The extra latency is negligible.
            useInverseTextNormalization = true,
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
        // Rolling buffer of everything fed to VAD since speech started (or the
        // last final). Cleared on segment finalize AND on transition to silence.
        val partialChunks = ArrayDeque<FloatArray>()
        var partialLenSamples = 0
        var lastPartialAt = 0L

        while (running) {
            val incoming = pcmChannel.receiveCatching().getOrNull() ?: break
            v.acceptWaveform(incoming)

            // Drain any finalized segments first. When the VAD hands us a
            // segment, that IS the authoritative final for the just-ended
            // utterance; the partial buffer we were building alongside is now
            // stale.
            while (!v.empty()) {
                val segment = v.front()
                v.pop()
                if (segment.samples.isNotEmpty()) {
                    decodeAndEmit(r, segment.samples, isFinal = true)
                }
                partialChunks.clear()
                partialLenSamples = 0
                lastPartialAt = 0L
            }

            // Then, if the VAD says speech is still in progress, keep growing
            // the partial buffer and re-decode it at a fixed cadence so the
            // caption updates while the user is still talking.
            if (v.isSpeechDetected()) {
                partialChunks.add(incoming)
                partialLenSamples += incoming.size
                val now = System.currentTimeMillis()
                if (partialLenSamples > 0 && now - lastPartialAt >= PARTIAL_INTERVAL_MS) {
                    val combined = FloatArray(partialLenSamples)
                    var offset = 0
                    for (chunk in partialChunks) {
                        System.arraycopy(chunk, 0, combined, offset, chunk.size)
                        offset += chunk.size
                    }
                    decodeAndEmit(r, combined, isFinal = false)
                    lastPartialAt = now
                }
            } else if (partialChunks.isNotEmpty()) {
                // Silence: whatever we'd been accumulating never made it to a
                // real speech segment, so throw it away.
                partialChunks.clear()
                partialLenSamples = 0
                lastPartialAt = 0L
            }
        }
        Log.w(TAG, "sensevoice loop exited — running=$running")
    }

    private fun decodeAndEmit(r: OfflineRecognizer, samples: FloatArray, isFinal: Boolean) {
        val stream = r.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            r.decode(stream)
            val raw = r.getResult(stream).text
            val cleaned = postProcess(raw)
            if (cleaned.isBlank()) return
            val kind = if (isFinal) "final" else "partial"
            Log.d(TAG, "$kind ${samples.size} samples → '${cleaned.take(60)}'")
            if (isFinal) listener?.onFinal(cleaned, "ja-JP")
            else listener?.onPartial(cleaned, "ja-JP")
        } finally {
            stream.release()
        }
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
        scope.cancel()
    }

    /**
     * SenseVoice raw output looks like `<|ja|><|NEUTRAL|><|Speech|><|withitn|>こんにちは`.
     * Additionally the model sometimes emits spaces between CJK characters
     * ("こ ん に ち は") which need collapsing for readable Japanese output.
     */
    private fun postProcess(raw: String): String {
        val noTags = TAG_PREFIX.replace(raw, "")
        // CJK_SPACE captures the leading CJK char in group 1; the trailing CJK
        // is a lookahead (non-consuming), so the match spans only "CJK + \s+".
        // Replace with just the captured char to drop the whitespace.
        val cjkSpaceCollapsed = CJK_SPACE.replace(noTags) { m -> m.groupValues[1] }
        return cjkSpaceCollapsed.trim()
    }

    companion object {
        private const val TAG = "SenseVoiceSpeechEngine"
        private const val SAMPLE_RATE = 16_000
        // Cadence for re-decoding the in-progress speech buffer as a partial.
        // 800 ms lands about 2 partial updates inside a typical VAD segment
        // (~2 s of speech) without saturating the CPU with decode work.
        private const val PARTIAL_INTERVAL_MS = 800L

        private val TAG_PREFIX = Regex("""<\|[^|>]*\|>""")
        // A CJK code-point, then one-or-more spaces, then another CJK code-point.
        // Matches per pair; caller replaces the span with just the two chars.
        private val CJK_SPACE = Regex("""([\p{IsHan}\p{IsHiragana}\p{IsKatakana}])\s+(?=[\p{IsHan}\p{IsHiragana}\p{IsKatakana}])""")
    }
}
