package com.g2transcribe

import android.app.AlarmManager
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Process
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private var needsRestart = false
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "stt_engine" || key == "mic_source") needsRestart = true
    }
    private var senseVoiceStatusView: TextView? = null
    private var senseVoiceButton: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 80, 48, 48)
            setBackgroundColor(0xFF121212.toInt())
        }

        val titleView = TextView(this).apply {
            text = getString(R.string.settings_title)
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, 32)
        }
        layout.addView(titleView)

        val saveLogSwitch = Switch(this).apply {
            text = getString(R.string.pref_save_log)
            setTextColor(0xFFFFFFFF.toInt())
            isChecked = prefs.getBoolean("save_log", false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("save_log", checked).apply()
            }
        }
        layout.addView(saveLogSwitch)

        val openLogsButton = Button(this).apply {
            text = getString(R.string.pref_open_logs)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF1F3D5C.toInt())
            setOnClickListener { startActivity(Intent(this@SettingsActivity, LogsActivity::class.java)) }
        }
        layout.addView(openLogsButton)

        val timeoutLabel = TextView(this).apply {
            val sec = prefs.getInt("display_timeout", 5)
            text = "${getString(R.string.pref_display_timeout)}: ${if (sec == 0) "OFF" else "${sec}秒"}"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 48, 0, 8)
        }
        layout.addView(timeoutLabel)

        val timeoutHint = TextView(this).apply {
            text = getString(R.string.pref_display_timeout_off)
            textSize = 12f
            setTextColor(0xFF888888.toInt())
        }
        layout.addView(timeoutHint)

        val timeoutSeek = SeekBar(this).apply {
            max = 30
            progress = prefs.getInt("display_timeout", 5)
            setPadding(0, 16, 0, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    prefs.edit().putInt("display_timeout", value).apply()
                    timeoutLabel.text = "${getString(R.string.pref_display_timeout)}: ${if (value == 0) "OFF" else "${value}秒"}"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        layout.addView(timeoutSeek)

        val engineLabel = TextView(this).apply {
            text = getString(R.string.pref_stt_engine)
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 64, 0, 8)
        }
        layout.addView(engineLabel)

        val current = prefs.getString("stt_engine", DEFAULT_ENGINE) ?: DEFAULT_ENGINE
        val engineOptions = buildList {
            add("android" to getString(R.string.pref_stt_engine_android))
            if (isPixel10()) add("mlkit" to getString(R.string.pref_stt_engine_mlkit))
            add("sensevoice" to getString(R.string.pref_stt_engine_sensevoice))
        }
        val engineGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            engineOptions.forEach { (value, label) ->
                addView(RadioButton(this@SettingsActivity).apply {
                    id = value.hashCode()
                    text = label
                    setTextColor(0xFFFFFFFF.toInt())
                    tag = value
                    isChecked = (value == current)
                })
            }
            setOnCheckedChangeListener { group, checkedId ->
                val tag = group.findViewById<RadioButton>(checkedId)?.tag as? String
                if (tag != null) {
                    prefs.edit().putString("stt_engine", tag).apply()
                }
            }
        }
        layout.addView(engineGroup)

        val micLabel = TextView(this).apply {
            text = getString(R.string.pref_mic_source)
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 48, 0, 8)
        }
        layout.addView(micLabel)

        val micHint = TextView(this).apply {
            text = getString(R.string.pref_mic_source_summary)
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            setPadding(0, 0, 0, 8)
        }
        layout.addView(micHint)

        val currentMic = prefs.getString("mic_source", DEFAULT_MIC_SOURCE) ?: DEFAULT_MIC_SOURCE
        val micGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            listOf(
                "g2"    to getString(R.string.pref_mic_source_g2),
                "phone" to getString(R.string.pref_mic_source_phone),
            ).forEach { (value, label) ->
                addView(RadioButton(this@SettingsActivity).apply {
                    id = ("mic_" + value).hashCode()
                    text = label
                    setTextColor(0xFFFFFFFF.toInt())
                    tag = value
                    isChecked = (value == currentMic)
                })
            }
            setOnCheckedChangeListener { group, checkedId ->
                val tag = group.findViewById<RadioButton>(checkedId)?.tag as? String
                if (tag != null) {
                    prefs.edit().putString("mic_source", tag).apply()
                }
            }
        }
        layout.addView(micGroup)

        // ---- SenseVoice model management ----
        val svLabel = TextView(this).apply {
            text = getString(R.string.pref_sensevoice_model)
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 48, 0, 8)
        }
        layout.addView(svLabel)

        val svHint = TextView(this).apply {
            text = getString(R.string.pref_sensevoice_model_summary)
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            setPadding(0, 0, 0, 8)
        }
        layout.addView(svHint)

        senseVoiceStatusView = TextView(this).apply {
            text = senseVoiceStatusText()
            textSize = 13f
            setTextColor(0xFFAAAAAA.toInt())
            setPadding(0, 0, 0, 8)
        }
        layout.addView(senseVoiceStatusView)

        senseVoiceButton = Button(this).apply {
            text = if (SenseVoiceModelManager.isPresent(this@SettingsActivity))
                getString(R.string.pref_sensevoice_redownload)
            else
                getString(R.string.pref_sensevoice_download)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF1F3D5C.toInt())
            setOnClickListener { startSenseVoiceDownload() }
        }
        layout.addView(senseVoiceButton)

        // ScrollView wrap so bottom controls stay reachable as engine sections grow.
        val scroll = ScrollView(this).apply { addView(layout) }
        setContentView(scroll)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (needsRestart) {
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle(R.string.engine_switch_title)
                        .setMessage(R.string.engine_switch_message)
                        .setPositiveButton(R.string.engine_switch_restart_now) { _, _ -> restartForEngineSwap() }
                        .setNegativeButton(R.string.engine_switch_later) { _, _ -> finish() }
                        .setCancelable(false)
                        .show()
                } else {
                    finish()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        PreferenceManager.getDefaultSharedPreferences(this)
            .registerOnSharedPreferenceChangeListener(prefListener)
    }

    override fun onPause() {
        super.onPause()
        PreferenceManager.getDefaultSharedPreferences(this)
            .unregisterOnSharedPreferenceChangeListener(prefListener)
    }

    private fun restartForEngineSwap() {
        startService(Intent(this, TranscribeService::class.java).apply {
            action = TranscribeService.ACTION_SHUTDOWN
        })
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val pi = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        (getSystemService(ALARM_SERVICE) as AlarmManager)
            .set(AlarmManager.RTC, System.currentTimeMillis() + 1500, pi)
        finishAffinity()
        Process.killProcess(Process.myPid())
    }

    private fun senseVoiceStatusText(): String {
        return if (SenseVoiceModelManager.isPresent(this)) {
            val mb = SenseVoiceModelManager.totalSizeBytes(this) / 1_048_576
            getString(R.string.pref_sensevoice_status_present, mb)
        } else {
            getString(R.string.pref_sensevoice_status_absent)
        }
    }

    private fun startSenseVoiceDownload() {
        val button = senseVoiceButton ?: return
        val status = senseVoiceStatusView ?: return
        button.isEnabled = false
        button.text = getString(R.string.pref_sensevoice_downloading)
        lifecycleScope.launch {
            val result = SenseVoiceModelManager.downloadAll(
                this@SettingsActivity,
                object : SenseVoiceModelManager.ProgressListener {
                    override fun onProgress(message: String, bytesDownloaded: Long, totalBytes: Long) {
                        val mb = bytesDownloaded / 1_048_576
                        val totalMb = if (totalBytes > 0) totalBytes / 1_048_576 else -1
                        val text = if (totalMb > 0) "$message ${mb}/${totalMb}MB" else "$message ${mb}MB"
                        runOnUiThread { button.text = text }
                    }
                },
            )
            withContext(Dispatchers.Main) {
                button.isEnabled = true
                when (result) {
                    is SenseVoiceModelManager.Result.Success -> {
                        button.text = getString(R.string.pref_sensevoice_redownload)
                        status.text = senseVoiceStatusText()
                        if (PreferenceManager.getDefaultSharedPreferences(this@SettingsActivity)
                                .getString("stt_engine", DEFAULT_ENGINE) == "sensevoice") {
                            needsRestart = true
                        }
                    }
                    is SenseVoiceModelManager.Result.Failure -> {
                        button.text = getString(R.string.pref_sensevoice_download)
                        AlertDialog.Builder(this@SettingsActivity)
                            .setTitle(R.string.pref_sensevoice_download_failed)
                            .setMessage(result.message)
                            .setPositiveButton(R.string.dialog_close, null)
                            .show()
                    }
                }
            }
        }
    }

    private fun isPixel10(): Boolean {
        // Pixel 10 family — enables ML Kit GenAI Speech Recognition Advanced mode.
        // Basic mode is intentionally not offered because it's the same SODA engine
        // as the standard Android option, so it would add nothing.
        val model = android.os.Build.MODEL ?: ""
        return model.startsWith("Pixel 10", ignoreCase = true)
    }

    companion object {
        const val DEFAULT_ENGINE = "android"
        const val DEFAULT_MIC_SOURCE = "g2"
    }
}
