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
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.location.*
import java.io.OutputStream
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.max

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

    // Terminal colors
    private val COLOR_ACTIVE = 0xFF7FDBFF.toInt()
    private val COLOR_DISABLED = 0xFF3A3A3A.toInt()

    /* ========= Time / Log ========= */
    private val tsFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val logLines = ArrayDeque<String>(300)
    private val LOG_MAX = 300

    /* ========= GPS ========= */
    private lateinit var fusedClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null

    /* ========= Bluetooth ========= */
    private val btAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var selectedDevice: BluetoothDevice? = null

    // “Port” = the RFCOMM socket + output stream
    private var socket: BluetoothSocket? = null
    private var out: OutputStream? = null

    @Volatile private var isPortOpening = false
    @Volatile private var isPortOpen = false

    // SPP UUID (RFCOMM serial)
    private val SPP_UUID =
        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    /* ========= Permissions ========= */
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            if (perms.values.all { it }) {
                setPortStatus("OK")
                log("PORT", "Permissions granted")
                startGps()
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

        // Edge-to-edge boilerplate (kept)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // UI refs
        btDeviceStatusText = findViewById(R.id.btDeviceStatusText)
        portStatusText = findViewById(R.id.portStatusText)
        connectedDevicesText = findViewById(R.id.connectedDevicesText)
        speedText = findViewById(R.id.speedText)
        rawSpeedText = findViewById(R.id.rawSpeedText)
        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)

        btnPick = findViewById(R.id.btnPick)
        btnPortToggle = findViewById(R.id.btnPortToggle)

        fusedClient = LocationServices.getFusedLocationProviderClient(this)

        // Button handlers
        btnPick.setOnClickListener { pickBluetoothDevice() }
        btnPick.setOnClickListener { pickBluetoothDevice() }
        btnPortToggle.setOnClickListener { togglePort() }

        // Initial UI state
        setBtDeviceStatus("DISCONNECTED")
        setPortStatus("CLOSED")
        connectedDevicesText.text = "CONNECTED_DEVICES: (checking…)"
        log("SYS", "GPStoVSS boot")

        requestPermissions()
        refreshButtons()
    }

    override fun onStart() {
        super.onStart()
        // Monitor BT connect/disconnect events (helps diagnose “not connected”)
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
        val perms = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.BLUETOOTH_CONNECT
        )

        val missing = perms.any { perm ->
            ActivityCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED
        }

        if (missing) {
            setPortStatus("REQUESTING PERMS…")
            log("PORT", "Requesting permissions")
            permissionLauncher.launch(perms)
        } else {
            setPortStatus("OK")
            startGps()
            refreshConnectedDevices()
        }
    }

    /* ========= GPS ========= */
    private fun startGps() {
        if (locationCallback != null) return

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            100L
        )
            .setMinUpdateIntervalMillis(100L)
            .setWaitForAccurateLocation(false)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return

                val bearingOk = loc.hasBearing()
                val bearingDeg = if (bearingOk) loc.bearing else null
                log("GPS", "speedMps=%.2f bearing=%s".format(loc.speed, bearingDeg?.toString() ?: "NONE"))

                val mphRaw = max(0f, loc.speed * 2.23694f)
                val mph = if (mphRaw < 0.5f) 0f else mphRaw

                ///format speed values for display
                speedText.text = "SPEED_MPH: %.2f".format(mph)
                rawSpeedText.text = "RAW_MPH: %.2f".format(mphRaw)

                // Only transmit if port is open
                if (isPortOpen) {
                    val line = "SPEED_MPH:%.2f\r\n".format(mph)
                    try {
                        out?.write(line.toByteArray())
                        // Log what was sent (you asked for this)
                        log("TX", line.trim())
                    } catch (e: Exception) {
                        setPortStatus("WRITE FAIL")
                        log("PORT", "Write failed (${e.message})")
                        closePort()
                    }
                }
            }
        }

        if (
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            setPortStatus("NO FINE LOCATION")
            log("PORT", "No fine location permission")
            return
        }

        fusedClient.requestLocationUpdates(request, locationCallback!!, mainLooper)
        log("GPS", "Updates started")
    }

    /* ========= BT: show connected devices (best-effort) ========= */
    private fun refreshConnectedDevices() {
        // Android does NOT provide a perfect “all connected devices” list for SPP.
        // This is a best-effort: common profiles + ACL events.
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
            connectedDevicesText.text = "OTHER_CONNECTED_DEVICES: (unavailable)"
        }
    }

    /* ========= BT: device selection ========= */
    private fun pickBluetoothDevice() {
        if (isPortOpen || isPortOpening) return

        val devices = btAdapter?.bondedDevices?.toList().orEmpty()
        if (devices.isEmpty()) {
            setBtDeviceStatus("NO PAIRED DEVICES")
            log("BT", "No paired devices")
            return
        }

        val deviceLabels = devices.map { d -> d.name ?: d.address }.toMutableList()
        deviceLabels.add("DISCONNECT DEVICE")

        AlertDialog.Builder(this)
            .setTitle("Pick Bluetooth device")
            .setItems(deviceLabels.toTypedArray()) { _, idx ->
                // Last item = disconnect option
                if (idx == devices.size) {
                    // Close the port if needed, then clear selection
                    if (isPortOpen || isPortOpening) closePort()
                    selectedDevice = null
                    setBtDeviceStatus("DISCONNECTED")
                    setPortStatus("CLOSED")
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


    /* ========= PORT control ========= */
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

        setPortStatus("CLOSED")
        log("PORT", "CLOSED")
        refreshButtons()
        refreshConnectedDevices()
    }

    private fun togglePort() {
        if (isPortOpen || isPortOpening) {
            closePort()
        } else {
            openPort()
        }
    }
    private fun closePortSilently() {
        try { out?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
    }

    /* ========= UI state ========= */
    private fun refreshButtons() {
        // SELECT BT disabled while the port is open/opening
        setPickButtonEnabled(!(isPortOpen || isPortOpening))

        // Toggle button enabled only if a device is selected
        btnPortToggle.isEnabled = (selectedDevice != null)

        // Button label reflects port state
        btnPortToggle.text = when {
            isPortOpening -> "OPENING…"
            isPortOpen -> "CLOSE PORT"
            else -> "OPEN PORT"
        }
        val enabledColor = COLOR_ACTIVE
        val disabledColor = COLOR_DISABLED
        val isEnabledVisual = btnPortToggle.isEnabled && !isPortOpening

        btnPortToggle.alpha = if (isEnabledVisual) 1.0f else 0.6f
        btnPortToggle.backgroundTintList =
            android.content.res.ColorStateList.valueOf(if (isEnabledVisual) enabledColor else disabledColor)
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

    /* ========= Log ========= */
    private fun log(tag: String, msg: String) {
        val line = "[${LocalTime.now().format(tsFmt)}] $tag: $msg"

        if (logLines.size >= LOG_MAX) logLines.removeFirst()
        logLines.addLast(line)

        logText.text = "LOG:\n" + logLines.joinToString("\n")

        // Auto-scroll to bottom
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()

        // Stop GPS
        locationCallback?.let { fusedClient.removeLocationUpdates(it) }
        locationCallback = null

        // Close port
        closePortSilently()
        socket = null
        out = null
        isPortOpen = false
        isPortOpening = false
    }
}
