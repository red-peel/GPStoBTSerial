package com.example.gpstovss.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.gpstovss.R
import com.example.gpstovss.speed.GpsSpeedProvider
import com.example.gpstovss.speed.SpeedProvider
import com.google.android.gms.location.LocationServices
import java.io.OutputStream
import java.util.UUID
import kotlin.math.min

class VssForegroundService : Service() {

    private var deviceAddr: String? = null
    private var deviceName: String? = null

    private var socket: BluetoothSocket? = null
    private var out: OutputStream? = null

    private lateinit var speedProvider: SpeedProvider

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

    @Volatile private var running = false

    override fun onCreate() {
        super.onCreate()

        // Foreground notification must start ASAP after service start.
        startForeground(NOTIF_ID, buildNotification("Idle"))

        // Speed provider (GPS)
        val fused = LocationServices.getFusedLocationProviderClient(this)
        speedProvider = GpsSpeedProvider(
            context = this,
            fusedClient = fused,
            onDebug = { msg -> Log.d(TAG, msg) }
        )
        speedProvider.start()

        // Background worker thread for BT connect + TX loop
        workerThread = HandlerThread("VssAutoWorker").apply { start() }
        workerHandler = Handler(workerThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_AUTO -> {
                deviceAddr = intent.getStringExtra(EXTRA_DEVICE_ADDR)
                deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME)

                if (deviceAddr.isNullOrBlank()) {
                    Log.w(TAG, "START_AUTO missing device address; ignoring.")
                    stopSelf()
                    return START_NOT_STICKY
                }

                startAutoLoop()
            }

            ACTION_STOP -> {
                stopSelf()
            }

            else -> {
                // If service is restarted by the system, keep it alive.
                // We'll attempt reconnect using the last known deviceAddr (if still set).
                if (!deviceAddr.isNullOrBlank()) startAutoLoop()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        closeSocket()

        try { speedProvider.stop() } catch (_: Exception) {}

        workerThread?.quitSafely()
        workerThread = null
        workerHandler = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -----------------------------
    // AUTO connect + TX loop
    // -----------------------------

    private fun startAutoLoop() {
        if (running) return
        running = true

        val addr = deviceAddr ?: return
        val name = deviceName ?: addr

        updateNotification("Auto: searching $name")

        workerHandler?.post(object : Runnable {
            var backoffMs = 1500L

            override fun run() {
                if (!running) return

                try {
                    if (!isConnected()) {
                        connect(addr)
                        updateNotification("Auto: connected $name")
                    }

                    if (isConnected()) {
                        // Grab current speed and send to ESP32
                        val s = speedProvider.latest()
                        val line = "SPEED_MPH:%.2f\r\n".format(s.speedMph)

                        out?.write(line.toByteArray())
                        out?.flush()

                        // Healthy cadence
                        backoffMs = 1500L
                        workerHandler?.postDelayed(this, 100L) // 10 Hz
                        return
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Loop error: ${e.message}")
                    closeSocket()
                    updateNotification("Auto: reconnecting $name")
                }

                // Retry with backoff to avoid battery/BT spam
                backoffMs = min((backoffMs * 1.6).toLong(), 15000L)
                workerHandler?.postDelayed(this, backoffMs)
            }
        })
    }

    private fun isConnected(): Boolean = socket?.isConnected == true

    private fun connect(addr: String) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: throw IllegalStateException("No Bluetooth adapter")

        val device = adapter.getRemoteDevice(addr)

        closeSocket()

        // Classic SPP RFCOMM UUID
        val s = device.createRfcommSocketToServiceRecord(SPP_UUID)

        // Connect is blocking; we're on worker thread
        s.connect()

        socket = s
        out = s.outputStream
    }

    private fun closeSocket() {
        try { out?.close() } catch (_: Exception) {}
        out = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    // -----------------------------
    // Notification
    // -----------------------------

    private fun buildNotification(status: String): Notification {
        val channelId = CHANNEL_ID

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "GPStoVSS",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps GPStoVSS running in the background"
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("GPStoVSS running")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)

            // Don’t beep/vibrate every time you update the text
            .setOnlyAlertOnce(true)

            // Tells the system “this is an active service”
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

            // Slightly stronger “don’t mess with this” hint
            .setPriority(NotificationCompat.PRIORITY_LOW)

            .build()
    }


    private fun updateNotification(status: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(status))
    }

    companion object {
        private const val TAG = "VssForegroundService"

        const val ACTION_START_AUTO = "com.example.gpstovss.action.START_AUTO"
        const val ACTION_STOP = "com.example.gpstovss.action.STOP"

        const val EXTRA_DEVICE_ADDR = "device_addr"
        const val EXTRA_DEVICE_NAME = "device_name"

        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "gpstovss_channel"

        private val SPP_UUID: UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
