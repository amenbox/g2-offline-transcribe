package com.g2transcribe

import android.app.AlarmManager
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Process
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager

class SettingsActivity : AppCompatActivity() {

    private var needsRestart = false
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "stt_engine" || key == "yyapis_api_key") needsRestart = true
    }

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
        val engineGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            listOf(
                "android" to getString(R.string.pref_stt_engine_android),
                "yyapis"  to getString(R.string.pref_stt_engine_yyapis),
            ).forEach { (value, label) ->
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

        val apiKeyLabel = TextView(this).apply {
            text = getString(R.string.pref_yyapis_api_key)
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 48, 0, 8)
        }
        layout.addView(apiKeyLabel)

        val apiKeyHint = TextView(this).apply {
            text = getString(R.string.pref_yyapis_api_key_summary)
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            setPadding(0, 0, 0, 8)
        }
        layout.addView(apiKeyHint)

        val apiKeyEdit = EditText(this).apply {
            setText(prefs.getString("yyapis_api_key", ""))
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF666666.toInt())
            hint = getString(R.string.pref_yyapis_api_key_placeholder)
            setBackgroundColor(0xFF1E1E1E.toInt())
            setPadding(24, 24, 24, 24)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    prefs.edit().putString("yyapis_api_key", s?.toString().orEmpty()).apply()
                }
            })
        }
        layout.addView(apiKeyEdit)

        setContentView(layout)

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

    companion object {
        const val DEFAULT_ENGINE = "android"
    }
}
