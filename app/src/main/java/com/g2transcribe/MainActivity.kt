package com.g2transcribe

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.g2transcribe.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: TranscriptAdapter

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) startService()
        else binding.statusText.text = "権限が必要です"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = TranscriptAdapter()
        binding.transcriptList.layoutManager = LinearLayoutManager(this)
        binding.transcriptList.adapter = adapter

        binding.stopButton.setOnClickListener { stopSession() }
        binding.exitButton.setOnClickListener { exitApp() }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        checkAndStart()
    }

    override fun onResume() {
        super.onResume()

        adapter.replaceAll(TranscribeService.snapshotHistory())
        if (adapter.itemCount > 0) {
            binding.transcriptList.scrollToPosition(adapter.itemCount - 1)
        }

        TranscribeService.uiUpdateCallback = { status, text, startsNewEntry ->
            runOnUiThread {
                binding.statusText.text = status
                if (text.isNotBlank()) {
                    val entry = TranscriptEntry(TranscribeService.lastTimestamp, text)
                    if (startsNewEntry) adapter.addEntry(entry)
                    else adapter.updateLastEntry(entry)
                    binding.transcriptList.scrollToPosition(adapter.itemCount - 1)
                }
            }
        }
        TranscribeService.clearTranscriptCallback = {
            runOnUiThread {
                adapter.replaceAll(emptyList())
            }
        }
        binding.statusText.text = TranscribeService.connectionStatus
    }

    override fun onPause() {
        super.onPause()
        TranscribeService.uiUpdateCallback = null
        TranscribeService.clearTranscriptCallback = null
    }

    private fun stopSession() {
        val intent = Intent(this, TranscribeService::class.java).apply {
            action = TranscribeService.ACTION_CLEAR_SESSION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    private fun checkAndStart() {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startService() else permLauncher.launch(missing.toTypedArray())
    }

    private fun startService() {
        val intent = Intent(this, TranscribeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun exitApp() {
        val shutdownIntent = Intent(this, TranscribeService::class.java).apply {
            action = TranscribeService.ACTION_SHUTDOWN
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(shutdownIntent)
        } else {
            startService(shutdownIntent)
        }
        finishAndRemoveTask()
        Handler(Looper.getMainLooper()).postDelayed({
            (getSystemService(ACTIVITY_SERVICE) as ActivityManager)
                .killBackgroundProcesses(packageName)
        }, 1200)
    }
}
