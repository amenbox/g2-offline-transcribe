package com.g2transcribe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.preference.PreferenceManager
import com.mentra.bluetoothsdk.Device
import com.mentra.bluetoothsdk.DeviceModel
import com.mentra.bluetoothsdk.DeviceStore
import com.mentra.bluetoothsdk.MentraBluetoothSdk
import com.mentra.bluetoothsdk.MentraBluetoothSdkCallback
import com.mentra.bluetoothsdk.MicPcmEvent
import com.mentra.bluetoothsdk.ScanSession
import com.mentra.bluetoothsdk.TouchEvent
import com.mentra.bluetoothsdk.GlassesRuntimeState
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

class TranscribeService : LifecycleService() {

    companion object {
        private const val TAG = "TranscribeService"
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "g2transcribe"
        private const val SCAN_TIMEOUT_MS = 10_000L
        private const val PCM_TIMEOUT_MS = 8_000L
        private const val RECONNECT_DELAY_MS = 1_500L
        const val ACTION_SHUTDOWN = "com.g2transcribe.SHUTDOWN"
        const val ACTION_CLEAR_SESSION = "com.g2transcribe.CLEAR_SESSION"

        @Volatile var connectionStatus = "接続中…"
        @Volatile var latestText = ""
        @Volatile var lastTimestamp = ""
        @Volatile var needsCaseReset = false

        val transcriptHistory: MutableList<TranscriptEntry> =
            Collections.synchronizedList(mutableListOf())
        @Volatile var lastEntryFinalized = true

        fun snapshotHistory(): List<TranscriptEntry> =
            synchronized(transcriptHistory) { transcriptHistory.toList() }

        // callback: status, text, startsNewEntry
        var uiUpdateCallback: ((String, String, Boolean) -> Unit)? = null
        // Fired when the session is cleared (stop button or temple tap); UI should reset its list.
        var clearTranscriptCallback: (() -> Unit)? = null
    }

    private lateinit var sdk: MentraBluetoothSdk
    private lateinit var speech: SttEngine
    private lateinit var prefs: SharedPreferences
    private var phoneMic: PhoneMicSource? = null
    private var usePhoneMic = false
    @Volatile private var modelDownloading = false
    private var scanSession: ScanSession? = null
    @Volatile private var connectAttempted = false
    @Volatile private var glassesConnected = false
    @Volatile private var pcmEverReceived = false
    @Volatile private var reconnectAttempted = false
    private val watchdog = Handler(Looper.getMainLooper())
    private val pcmWatchdogRunnable = Runnable { onPcmTimeout() }
    private val displayClearRunnable = Runnable { clearG2Display() }
    private var logWriter: FileWriter? = null
    private val timestampFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val sessionStartTime = System.currentTimeMillis()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_SHUTDOWN -> { shutdownGracefully(); return START_NOT_STICKY }
            ACTION_CLEAR_SESSION -> { clearSession() }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithType(contentText: String) {
        val notification = buildNotification(contentText)
        val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notification, types)
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)

        createNotificationChannel()
        startForegroundWithType(getString(R.string.notif_connecting))

        if (prefs.getBoolean("save_log", false)) {
            openLogFile()
        }

        usePhoneMic = prefs.getString("mic_source", "g2") == "phone"

        speech = when (prefs.getString("stt_engine", "android")) {
            "yyapis" -> YyApiSpeechEngine(applicationContext, prefs.getString("yyapis_api_key", "") ?: "")
            "mlkit"  -> MlKitGenAiSpeechEngine(applicationContext)
            else     -> SpeechEngine(applicationContext)
        }
        speech.setListener(object : SttEngine.Listener {
            override fun onPartial(text: String, language: String) = onTranscription(text, false)
            override fun onFinal(text: String, language: String) = onTranscription(text, true)
        })
        speech.setModelStatusListener(object : SttEngine.ModelStatusListener {
            override fun onReady() {
                modelDownloading = false
                Log.d(TAG, "STT model ready")
            }
            override fun onDownloading(percent: Int?) {
                modelDownloading = true
                val pct = percent?.let { " $it%" } ?: ""
                connectionStatus = "音声モデルDL中$pct"
                updateNotification(connectionStatus)
                uiUpdateCallback?.invoke(connectionStatus, "", false)
            }
            override fun onUnavailable(reason: String) {
                modelDownloading = false
                Log.w(TAG, "STT model unavailable: $reason")
                connectionStatus = "音声エンジン使用不可: $reason"
                updateNotification(connectionStatus)
                uiUpdateCallback?.invoke(connectionStatus, "", false)
            }
        })
        speech.start()

        if (usePhoneMic) {
            phoneMic = PhoneMicSource { pcm -> speech.acceptAudio(pcm) }.also { it.start() }
        }

        sdk = MentraBluetoothSdk.create(applicationContext, SdkCallback())
        registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        startScan()

        Log.d(TAG, "TranscribeService started")
    }

    private fun startScan() {
        connectAttempted = false
        try { scanSession?.stop() } catch (_: Exception) {}

        val btAdapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (btAdapter?.isEnabled != true) {
            Log.w(TAG, "Bluetooth not enabled — waiting")
            onConnectionState("BT_OFF")
            return
        }

        try {
            scanSession = sdk.scan(DeviceModel.G2, SCAN_TIMEOUT_MS) { devices ->
                if (connectAttempted) return@scan
                val g2 = devices.firstOrNull { it.model == DeviceModel.G2 } ?: return@scan
                connectAttempted = true
                Log.d(TAG, "Discovered G2: ${g2.name} — connecting")
                sdk.connect(g2)
            }
        } catch (e: Exception) {
            Log.w(TAG, "scan failed: ${e.message}")
            onConnectionState("BT_OFF")
        }
    }

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            if (state == BluetoothAdapter.STATE_ON && !glassesConnected) {
                Log.d(TAG, "Bluetooth turned on — retrying scan")
                startScan()
            }
        }
    }

    override fun onDestroy() {
        watchdog.removeCallbacks(pcmWatchdogRunnable)
        watchdog.removeCallbacks(displayClearRunnable)
        try { unregisterReceiver(btStateReceiver) } catch (e: Exception) { Log.w(TAG, "unregister: ${e.message}") }
        try { scanSession?.stop() } catch (e: Exception) { Log.w(TAG, "scan stop: ${e.message}") }
        try { sdk.close() } catch (e: Exception) { Log.w(TAG, "sdk close: ${e.message}") }
        phoneMic?.stop()
        phoneMic = null
        speech.stop()
        closeLogFile()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
        getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        Log.d(TAG, "TranscribeService stopped")
        super.onDestroy()
    }

    private fun shutdownGracefully() {
        try {
            if (this::sdk.isInitialized) {
                sdk.clearDisplay()
                sdk.setScreenDisabled(true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "shutdown display off failed: ${e.message}")
        }
        watchdog.postDelayed({ stopSelf() }, 400)
    }

    // ---- PCM watchdog ----

    private fun armPcmWatchdog() {
        watchdog.removeCallbacks(pcmWatchdogRunnable)
        watchdog.postDelayed(pcmWatchdogRunnable, PCM_TIMEOUT_MS)
    }

    private fun onPcmTimeout() {
        if (pcmEverReceived || !glassesConnected) return
        if (!reconnectAttempted) {
            reconnectAttempted = true
            Log.w(TAG, "PCM watchdog: no audio — trying BLE disconnect+reconnect")
            try { sdk.disconnect() } catch (e: Exception) { Log.w(TAG, "disconnect: ${e.message}") }
            watchdog.postDelayed({ startScan() }, RECONNECT_DELAY_MS)
            armPcmWatchdog()
        } else {
            Log.w(TAG, "PCM watchdog: still no audio — prompting case reset")
            needsCaseReset = true
            connectionStatus = getString(R.string.status_needs_reset)
            try { sdk.displayText("ケースで開け直して") } catch (_: Exception) {}
            uiUpdateCallback?.invoke(connectionStatus, "", false)
            updateNotification(getString(R.string.notif_needs_reset))
        }
    }

    // ---- G2 display auto-clear ----

    private fun scheduleDisplayClear() {
        watchdog.removeCallbacks(displayClearRunnable)
        val seconds = prefs.getInt("display_timeout", 5)
        if (seconds > 0) {
            watchdog.postDelayed(displayClearRunnable, seconds * 1000L)
        }
    }

    private fun clearG2Display() {
        try { sdk.clearDisplay() } catch (_: Exception) {}
    }

    // ---- SDK callbacks ----

    private inner class SdkCallback : MentraBluetoothSdkCallback() {
        override fun onDeviceDiscovered(device: Device) {
            Log.d(TAG, "discovered ${device.name}")
        }

        override fun onGlassesChanged(glasses: GlassesRuntimeState) {
            val nowConnected = glasses.connected
            if (nowConnected && !glassesConnected) {
                glassesConnected = true
                needsCaseReset = false
                Log.d(TAG, if (usePhoneMic) "G2 connected — display-only (phone mic in use)"
                           else "G2 connected — enabling glasses mic")
                onConnectionState("CONNECTED")
                try {
                    DeviceStore.apply("bluetooth", "contextual_dashboard", false)
                    sdk.setScreenDisabled(false)
                    sdk.clearDisplay()
                } catch (e: Exception) { Log.w(TAG, "display: ${e.message}") }
                if (usePhoneMic) {
                    // Explicitly turn the glasses mic off so nothing streams over BLE.
                    try { sdk.setMicState(enabled = false, useGlassesMic = true) } catch (_: Exception) {}
                } else {
                    sdk.setMicState(enabled = true, useGlassesMic = true)
                    armPcmWatchdog()
                }
                // SDK sends "MentraOS Connected" to the glasses just after onGlassesChanged
                // fires. Overwrite it with our app name briefly, then let the auto-clear
                // timer take it away so the display is ready for transcription.
                val appName = getString(R.string.app_name)
                watchdog.postDelayed({
                    try {
                        sdk.displayText(appName)
                        scheduleDisplayClear()
                    } catch (e: Exception) { Log.w(TAG, "welcome: ${e.message}") }
                }, 1200)
            } else if (!nowConnected && glassesConnected) {
                glassesConnected = false
                onConnectionState("DISCONNECTED")
            }
        }

        override fun onMicPcm(event: MicPcmEvent) {
            if (usePhoneMic) return // Ignore any BLE audio when phone mic is authoritative
            if (!pcmEverReceived) {
                pcmEverReceived = true
                watchdog.removeCallbacks(pcmWatchdogRunnable)
                Log.d(TAG, "first PCM received — mic stream healthy")
            }
            speech.acceptAudio(event.pcm)
        }

        override fun onTouch(event: TouchEvent) {
            val gesture = event.gestureName ?: return
            Log.d(TAG, "gesture: $gesture")
            if (gesture == "single_tap") {
                clearSession()
            }
        }
    }

    // ---- Transcription + UI ----

    private fun clearSession() {
        Log.d(TAG, "clearSession requested")
        synchronized(transcriptHistory) {
            transcriptHistory.clear()
            lastEntryFinalized = true
        }
        latestText = ""
        lastTimestamp = ""
        // Rotate the log file so each session ends up as its own file.
        closeLogFile()
        if (prefs.getBoolean("save_log", false)) openLogFile()
        watchdog.removeCallbacks(displayClearRunnable)
        try { sdk.clearDisplay() } catch (_: Exception) {}
        clearTranscriptCallback?.invoke()
        uiUpdateCallback?.invoke(connectionStatus, "", false)
    }

    private fun onTranscription(text: String, isFinal: Boolean) {
        latestText = text
        val elapsed = (System.currentTimeMillis() - sessionStartTime) / 1000
        lastTimestamp = String.format("%02d:%02d:%02d", elapsed / 3600, (elapsed % 3600) / 60, elapsed % 60)

        val entry = TranscriptEntry(lastTimestamp, text)
        val startsNew: Boolean
        synchronized(transcriptHistory) {
            if (transcriptHistory.isEmpty() || lastEntryFinalized) {
                transcriptHistory.add(entry)
                startsNew = true
            } else {
                transcriptHistory[transcriptHistory.size - 1] = entry
                startsNew = false
            }
            lastEntryFinalized = isFinal
        }

        try {
            sdk.displayText(text)
            scheduleDisplayClear()
        } catch (e: Exception) { Log.w(TAG, "displayText: ${e.message}") }

        if (isFinal) {
            writeLog(lastTimestamp, text)
        }
        uiUpdateCallback?.invoke(connectionStatus, text, startsNew)
    }

    private fun onConnectionState(state: String) {
        val base = when (state) {
            "CONNECTED"  -> getString(R.string.status_connected)
            "CONNECTING" -> getString(R.string.status_connecting)
            "SCANNING"   -> getString(R.string.status_scanning)
            "BT_OFF"     -> getString(R.string.status_bt_off)
            else         -> getString(R.string.status_disconnected)
        }
        connectionStatus = if (usePhoneMic && state == "CONNECTED")
            base + getString(R.string.status_phone_mic_suffix)
        else base
        Log.d(TAG, "Connection: $connectionStatus")
        updateNotification(
            when (state) {
                "CONNECTED" -> getString(R.string.notif_running)
                "BT_OFF"    -> getString(R.string.status_bt_off)
                else        -> getString(R.string.notif_connecting)
            }
        )
        uiUpdateCallback?.invoke(connectionStatus, "", false)
    }

    // ---- Log file ----

    private fun openLogFile() {
        try {
            val dir = File(getExternalFilesDir(null), "logs")
            dir.mkdirs()
            val name = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault()).format(Date())
            logWriter = FileWriter(File(dir, "$name.txt"), true)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open log file: ${e.message}")
        }
    }

    private fun writeLog(ts: String, text: String) {
        try { logWriter?.apply { write("$ts\t$text\n"); flush() } } catch (_: Exception) {}
    }

    private fun closeLogFile() {
        try { logWriter?.close() } catch (_: Exception) {}
        logWriter = null
    }

    // ---- Notifications ----

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(contentText: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(contentText))
    }
}
