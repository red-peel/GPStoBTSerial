package com.example.gpstovss

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.gpstovss.speed.GpsSpeedProvider
import com.example.gpstovss.speed.SpeedProvider
import com.google.android.gms.location.LocationServices
import java.io.OutputStream
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

class MainActivity : AppCompatActivity() {

    /* ========= UI ========= */
    private lateinit var btDeviceStatusText: TextView
    private lateinit var portStatusText: TextView
    private lateinit var connectedDevicesText: TextView
    private lateinit var speedText: TextView
    private lateinit var rawSpeedText: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var btnPick: Button
    private lateinit var btnPortToggle: Button

    private val COLOR_ACTIVE = 0xFF7FDBFF.toInt()
    private val COLOR_DISABLED = 0xFF3A3A3A.toInt()

    /* ========= Log ========= */
    private val tsFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val logLines = ArrayDeque<String>(300)
    private val LOG_MAX = 300

    /* ========= Speed provider (modular) ========= */
    private lateinit var speedProvider: SpeedProvider

    /* ========= Bluetooth ========= */
    private val btAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var selectedDevice: BluetoothDevice? = null
    private var socket: BluetoothSocket? = null
    private var out: OutputStream? = null
    @Volatile private var isPortOpening = false
    @Volatile private var isPortOpen = false

    private val SPP_UUID =
        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    /* ========= Permissions ========= */
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            if (perms.values.all { it }) {
                setPortStatus("OK")
                log("PORT", "Permissions granted")

                // Start speed provider AFTER permissions
                speedProvider.start()

                refreshConnectedDevices()
                refreshButtons()
            } else {
                setPortStatus("PERM DENIED")
                log("PORT", "Permissions denied")
                refreshButtons()
            }
        }

    /* ========= Connected device monitoring (best-effort) ========= */
    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val d = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    log("BT", "ACL_CONNECTED ${d?.name ?: d?.address}")
                    refreshConnectedDevices()
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val d = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    log("BT", "ACL_DISCONNECTED ${d?.name ?: d?.address}")
                    refreshConnectedDevices()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        btDeviceStatusText = findViewById(R.id.btDeviceStatusText)
        portStatusText = findViewById(R.id.portStatusText)
        connectedDevicesText = findViewById(R.id.connectedDevicesText)
        speedText = findViewById(R.id.speedText)
        rawSpeedText = findViewById(R.id.rawSpeedText)
        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)
        btnPick = findViewById(R.id.btnPick)
        btnPortToggle = findViewById(R.id.btnPortToggle)

        btnPick.setOnClickListener { pickBluetoothDevice() }
        btnPortToggle.setOnClickListener { togglePort() }

        setBtDeviceStatus("DISCONNECTED")
        setPortStatus("CLOSED")
        connectedDevicesText.text = "CONNECTED_DEVICES: (checking…)"
        speedText.text = "SPEED_MPH: --.--"
        rawSpeedText.text = "RAW_MPH: --.--"
        log("SYS", "GPStoVSS boot")

        // Create the modular speed provider (GPS-only for now)
        val fusedClient = LocationServices.getFusedLocationProviderClient(this)
        speedProvider = GpsSpeedProvider(
            context = this,
            fusedClient = fusedClient,
            onDebug = { msg -> log("SPD", msg) }
        )

        requestPermissions()
        refreshButtons()

        // UI tick: update display + transmit from provider at a steady rate
        startUiTick()
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(
            btReceiver,
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
        )
        refreshConnectedDevices()
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(btReceiver) } catch (_: Exception) {}
    }

    /* ========= Permissions ========= */
    private fun requestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.BLUETOOTH_CONNECT
        )

        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = perms.any { perm ->
            ActivityCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED
        }

        if (missing) {
            setPortStatus("REQUESTING PERMS…")
            log("PORT", "Requesting permissions")
            permissionLauncher.launch(perms.toTypedArray())
        } else {
            setPortStatus("OK")
            speedProvider.start()
            refreshConnectedDevices()
        }
    }


    /* ========= UI tick (display + TX) ========= */
    private fun startUiTick() {
        // 10 Hz UI/TX tick (independent of GPS update rate)
        val handler = android.os.Handler(mainLooper)
        val runnable = object : Runnable {
            override fun run() {
                val s = speedProvider.latest()

                speedText.text = "SPEED_MPH: %.2f".format(s.speedMph)
                rawSpeedText.text = "RAW_MPH: %.2f".format(s.rawMph)

                if (isPortOpen) {
                    val line = "SPEED_MPH:%.2f\r\n".format(s.speedMph)
                    try {
                        out?.write(line.toByteArray())
                        log("TX", line.trim())
                    } catch (e: Exception) {
                        setPortStatus("WRITE FAIL")
                        log("PORT", "Write failed (${e.message})")
                        closePort()
                    }
                }

                handler.postDelayed(this, 100L)
            }
        }
        handler.post(runnable)
    }

    /* ========= Connected devices (best-effort) ========= */
    private fun refreshConnectedDevices() {
        try {
            if (
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                connectedDevicesText.text = "CONNECTED_DEVICES: (no permission)"
                return
            }

            val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val a2dp = bm.getConnectedDevices(android.bluetooth.BluetoothProfile.A2DP)
            val headset = bm.getConnectedDevices(android.bluetooth.BluetoothProfile.HEADSET)
            val gatt = bm.getConnectedDevices(android.bluetooth.BluetoothProfile.GATT)

            val all = (a2dp + headset + gatt)
                .distinctBy { it.address }
                .map { it.name ?: it.address }

            connectedDevicesText.text =
                if (all.isEmpty()) "CONNECTED_DEVICES: (none)"
                else "CONNECTED_DEVICES:\n- " + all.joinToString("\n- ")
        } catch (_: Exception) {
            connectedDevicesText.text = "CONNECTED_DEVICES: (unavailable)"
        }
    }

    /* ========= BT picker (with DISCONNECT option) ========= */
    private fun pickBluetoothDevice() {
        if (isPortOpen || isPortOpening) return

        val devices = btAdapter?.bondedDevices?.toList().orEmpty()
        if (devices.isEmpty()) {
            setBtDeviceStatus("NO PAIRED DEVICES")
            log("BT", "No paired devices")
            return
        }

        val labels = devices.map { it.name ?: it.address }.toMutableList()
        labels.add("DISCONNECT DEVICE")

        AlertDialog.Builder(this)
            .setTitle("Pick Bluetooth device")
            .setItems(labels.toTypedArray()) { _, idx ->
                if (idx == devices.size) {
                    closePort()
                    selectedDevice = null
                    setBtDeviceStatus("DISCONNECTED")
                    log("BT", "Device selection cleared")
                    refreshButtons()
                    return@setItems
                }

                selectedDevice = devices[idx]
                val name = selectedDevice?.name ?: selectedDevice?.address ?: "UNKNOWN"
                setBtDeviceStatus("SELECTED $name")
                log("BT", "Selected $name")
                refreshButtons()
            }
            .show()
    }

    /* ========= PORT control (toggle) ========= */
    private fun togglePort() {
        if (isPortOpen || isPortOpening) closePort() else openPort()
    }

    private fun openPort() {
        val device = selectedDevice
        if (device == null) {
            setBtDeviceStatus("SELECT DEVICE")
            log("BT", "Open port requested but no device selected")
            refreshButtons()
            return
        }

        if (isPortOpen || isPortOpening) return
        isPortOpening = true

        val name = device.name ?: device.address ?: "UNKNOWN"
        setBtDeviceStatus("DEVICE $name")
        setPortStatus("OPENING…")
        log("PORT", "Opening RFCOMM to $name")
        refreshButtons()

        Thread {
            closePortSilently()

            try {
                val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
                s.connect()
                socket = s
                out = s.outputStream

                isPortOpen = true
                isPortOpening = false

                runOnUiThread {
                    ContextCompat.startForegroundService(
                        this,
                        Intent(this, com.example.gpstovss.service.VssForegroundService::class.java)
                    )
                    setPortStatus("OPEN")
                    log("PORT", "OPEN")
                    refreshButtons()
                    refreshConnectedDevices()
                }
            } catch (e: Exception) {
                closePortSilently()
                socket = null
                out = null

                isPortOpen = false
                isPortOpening = false

                runOnUiThread {
                    setPortStatus("FAILED")
                    log("PORT", "OPEN FAIL (${e.message})")
                    refreshButtons()
                    refreshConnectedDevices()
                }
            }
        }.start()
    }

    private fun closePort() {
        closePortSilently()
        socket = null
        out = null
        isPortOpen = false
        isPortOpening = false

        stopService(
            Intent(this, com.example.gpstovss.service.VssForegroundService::class.java)
        )

        setPortStatus("CLOSED")
        log("PORT", "CLOSED")
        refreshButtons()
        refreshConnectedDevices()
    }

    private fun closePortSilently() {
        try { out?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
    }

    /* ========= UI ========= */
    private fun refreshButtons() {
        setPickButtonEnabled(!(isPortOpen || isPortOpening))

        btnPortToggle.isEnabled = (selectedDevice != null)
        btnPortToggle.text = when {
            isPortOpening -> "OPENING…"
            isPortOpen -> "CLOSE PORT"
            else -> "OPEN PORT"
        }

        val isEnabledVisual = btnPortToggle.isEnabled && !isPortOpening
        btnPortToggle.alpha = if (isEnabledVisual) 1.0f else 0.6f
        btnPortToggle.backgroundTintList =
            android.content.res.ColorStateList.valueOf(if (isEnabledVisual) COLOR_ACTIVE else COLOR_DISABLED)
    }

    private fun setPickButtonEnabled(enabled: Boolean) {
        btnPick.isEnabled = enabled
        btnPick.alpha = if (enabled) 1.0f else 0.4f
        btnPick.backgroundTintList =
            android.content.res.ColorStateList.valueOf(if (enabled) COLOR_ACTIVE else COLOR_DISABLED)
    }

    private fun setBtDeviceStatus(msg: String) {
        btDeviceStatusText.text = "BT_DEVICE: $msg"
    }

    private fun setPortStatus(msg: String) {
        portStatusText.text = "PORT: $msg"
    }

    private fun log(tag: String, msg: String) {
        val line = "[${LocalTime.now().format(tsFmt)}] $tag: $msg"
        if (logLines.size >= LOG_MAX) logLines.removeFirst()
        logLines.addLast(line)

        logText.text = "LOG:\n" + logLines.joinToString("\n")
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        speedProvider.stop()
        stopService(
            Intent(this, com.example.gpstovss.service.VssForegroundService::class.java)
        )
        closePortSilently()
    }
}
