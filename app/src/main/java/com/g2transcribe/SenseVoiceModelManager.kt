package com.g2transcribe

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Locates and downloads the SenseVoice-Small int8 (sherpa-onnx packaged) bundle
 * plus the Silero VAD model. Both are required by [SenseVoiceSpeechEngine].
 *
 * - SenseVoice bundle (zh-en-ja-ko-yue int8): ~230 MB tar.bz2 → ~240 MB on disk.
 *   Distributed by the sherpa-onnx project on GitHub Releases.
 * - Silero VAD: ~1.8 MB single .onnx, hosted on Hugging Face.
 *
 * Stored under the app's private files dir / "sensevoice".
 */
object SenseVoiceModelManager {

    private const val TAG = "SenseVoiceModel"

    private const val ASR_BUNDLE_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2"
    private const val ASR_BUNDLE_NAME = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"

    private const val VAD_URL = "https://huggingface.co/csukuangfj/vad/resolve/main/silero_vad.onnx"
    private const val VAD_FILE_NAME = "silero_vad.onnx"

    fun modelDir(context: Context): File = File(context.filesDir, "sensevoice")
    fun asrDir(context: Context): File = File(modelDir(context), ASR_BUNDLE_NAME)
    fun vadFile(context: Context): File = File(modelDir(context), VAD_FILE_NAME)

    /** Expected files inside the sherpa-onnx SenseVoice int8 bundle. */
    object Files {
        const val MODEL = "model.int8.onnx"
        const val TOKENS = "tokens.txt"
    }

    private val requiredAsrFiles = listOf(Files.MODEL, Files.TOKENS)

    fun isPresent(context: Context): Boolean {
        if (!vadFile(context).exists()) return false
        val asr = asrDir(context)
        if (!asr.isDirectory) return false
        return requiredAsrFiles.all { File(asr, it).exists() }
    }

    fun totalSizeBytes(context: Context): Long {
        var s = if (vadFile(context).exists()) vadFile(context).length() else 0L
        val asr = asrDir(context)
        if (asr.isDirectory) {
            asr.walkTopDown().forEach { if (it.isFile) s += it.length() }
        }
        return s
    }

    interface ProgressListener {
        fun onProgress(message: String, bytesDownloaded: Long, totalBytes: Long)
    }

    sealed class Result {
        object Success : Result()
        data class Failure(val message: String) : Result()
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    suspend fun downloadAll(context: Context, progress: ProgressListener? = null): Result =
        withContext(Dispatchers.IO) {
            modelDir(context).mkdirs()
            try {
                if (!vadFile(context).exists()) {
                    val r = downloadTo(VAD_URL, vadFile(context), "VAD", progress)
                    if (r is Result.Failure) return@withContext r
                }
                if (!isAsrPresent(context)) {
                    val tarFile = File(modelDir(context), "asr.tar.bz2")
                    val r = downloadTo(ASR_BUNDLE_URL, tarFile, "SenseVoice", progress)
                    if (r is Result.Failure) return@withContext r
                    progress?.onProgress("SenseVoice 展開中…", 0, -1)
                    extractTarBz2(tarFile, modelDir(context))
                    tarFile.delete()
                }
                if (!isPresent(context)) {
                    return@withContext Result.Failure("展開後の必須ファイルが見つかりません")
                }
                Result.Success
            } catch (e: IOException) {
                Log.w(TAG, "downloadAll failed", e)
                Result.Failure("通信エラー: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "downloadAll crashed", e)
                Result.Failure("予期しないエラー: ${e.message}")
            }
        }

    private fun isAsrPresent(context: Context): Boolean {
        val asr = asrDir(context)
        return asr.isDirectory && requiredAsrFiles.all { File(asr, it).exists() }
    }

    private fun downloadTo(
        url: String,
        target: File,
        label: String,
        progress: ProgressListener?,
    ): Result {
        val tmp = File(target.parentFile, "${target.name}.part")
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return Result.Failure("$label HTTP ${response.code}")
            }
            val body = response.body ?: return Result.Failure("$label 空レスポンス")
            val total = body.contentLength()
            body.byteStream().use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var written = 0L
                    var lastReportedPct = -1
                    while (true) {
                        val read = input.read(buf)
                        if (read == -1) break
                        output.write(buf, 0, read)
                        written += read
                        if (total > 0) {
                            val pct = (written * 100 / total).toInt()
                            if (pct != lastReportedPct) {
                                lastReportedPct = pct
                                progress?.onProgress(label, written, total)
                            }
                        } else if (written % (1 * 1024 * 1024) < buf.size) {
                            progress?.onProgress(label, written, -1L)
                        }
                    }
                }
            }
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) return Result.Failure("$label リネーム失敗")
        return Result.Success
    }

    private fun extractTarBz2(tarBz2: File, dir: File) {
        tarBz2.inputStream().buffered().use { fis ->
            BZip2CompressorInputStream(fis).use { bz ->
                TarArchiveInputStream(bz).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        val out = File(dir, entry.name)
                        if (entry.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { tar.copyTo(it) }
                        }
                        entry = tar.nextEntry
                    }
                }
            }
        }
    }

    fun delete(context: Context): Boolean = modelDir(context).deleteRecursively()
}
