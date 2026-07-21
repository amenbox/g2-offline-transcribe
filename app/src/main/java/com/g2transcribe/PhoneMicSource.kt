package com.g2transcribe

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * Reads PCM from the phone's built-in mic (or whatever the OS routes as the
 * VOICE_RECOGNITION source — includes wired/BLE headsets) and hands 16 kHz mono
 * 16-bit chunks to the caller. Format matches SpeechEngine's pipe expectation
 * so the STT engine can be swapped in transparently.
 */
class PhoneMicSource(private val onPcm: (ByteArray) -> Unit) {

    @Volatile private var running = false
    private var thread: Thread? = null
    private var recorder: AudioRecord? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        val bufBytes = maxOf(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CFG, ENCODING),
            CHUNK_BYTES * 2,
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL_CFG,
            ENCODING,
            bufBytes,
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            rec.release()
            return
        }
        recorder = rec
        running = true
        rec.startRecording()
        thread = Thread({
            val buf = ByteArray(CHUNK_BYTES)
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                val out = if (n == buf.size) buf.copyOf() else buf.copyOf(n)
                onPcm(out)
            }
        }, "PhoneMicPump").also { it.start() }
        Log.d(TAG, "Phone mic started ($SAMPLE_RATE Hz mono 16-bit)")
    }

    fun stop() {
        if (!running) return
        running = false
        try { recorder?.stop() } catch (e: Exception) { Log.w(TAG, "stop: ${e.message}") }
        try { recorder?.release() } catch (e: Exception) { Log.w(TAG, "release: ${e.message}") }
        recorder = null
        try { thread?.join(500) } catch (_: InterruptedException) {}
        thread = null
        Log.d(TAG, "Phone mic stopped")
    }

    companion object {
        private const val TAG = "PhoneMicSource"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CFG = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        // 100 ms per read — matches the LC3 frame cadence roughly and keeps
        // latency low without oversampling the STT pipe.
        private const val CHUNK_BYTES = SAMPLE_RATE * 2 / 10
    }
}
