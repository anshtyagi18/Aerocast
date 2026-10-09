package com.aerocast.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.aerocast.app.databinding.ActivityMainBinding
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity(), VisionService.VisionCallback, NetworkManager.NetworkListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var visionService: VisionService
    private lateinit var networkManager: NetworkManager

    private var stagedFile: File? = null
    private var activeIncomingLaptopIp: String? = null
    private var activeIncomingPort: Int = 42425
    private var activeIncomingFilename: String? = null
    private var activeIncomingSize: Long = 0L
    private var isSessionActive = false

    // File picker launcher
    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { handleFileSelected(it) }
    }

    // Comprehensive runtime permissions launcher (Camera, Bluetooth, Location, Storage)
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        if (!cameraGranted) {
            Toast.makeText(this, "Camera permission needed for Air Drop / Grab gestures", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        visionService = VisionService(this)
        networkManager = NetworkManager(this)
        networkManager.setListener(this)

        setupUI()
        checkPermissions()
        handleShareIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        networkManager.start()
        val ip = networkManager.getLocalIpAddress()
        binding.tvDeviceIp.text = "Wi-Fi LAN: $ip"
        binding.tvBluetoothStatus.text = "Bluetooth: RFCOMM Fallback Armed (SPP)"
    }

    override fun onPause() {
        super.onPause()
        if (!isSessionActive) {
            visionService.stopGestureWatch()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        visionService.stopGestureWatch()
        networkManager.stop()
    }

    private fun setupUI() {
        binding.btnPickFile.setOnClickListener {
            pickFileLauncher.launch("*/*")
        }

        binding.btnOpenDownloads.setOnClickListener {
            val aeroCastDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AeroCast")
            if (!aeroCastDir.exists()) aeroCastDir.mkdirs()
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(aeroCastDir.absolutePath), "*/*")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Downloads folder: Download/AeroCast", Toast.LENGTH_LONG).show()
            }
        }

        binding.btnCancelStaging.setOnClickListener {
            cancelActiveSession()
        }

        binding.btnManualSend.setOnClickListener {
            triggerFistSend()
        }

        binding.btnManualReceive.setOnClickListener {
            triggerPalmReceive()
        }

        updatePillBadge("🔍", "Looking for Hand Gesture…", "#94A3B8", "READY")
    }

    private fun checkPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }

        // Bluetooth Permissions for Android 12+ (API 31+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        // Storage permissions
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            uri?.let { handleFileSelected(it) }
        }
    }

    // ================= 1. FILE STAGING & AIR GRAB FLOW =================
    private fun handleFileSelected(uri: Uri) {
        val file = copyUriToInternalFile(uri) ?: return
        stagedFile = file
        isSessionActive = true

        // Display staged file card in bottom drawer
        binding.cardFileBadge.visibility = View.VISIBLE
        binding.tvFileName.text = file.name
        binding.tvFileSize.text = "${formatFileSize(file.length())} • ✊ Fist or Tap to Cast"

        // Show manual send button
        binding.manualActionRow.visibility = View.VISIBLE
        binding.btnManualSend.visibility = View.VISIBLE
        binding.btnManualReceive.visibility = View.GONE

        // Update floating pill badge to Amber
        updatePillBadge("✊", "MAKE A FIST TO CAST", "#F59E0B", "ARMED")

        // Start front camera watch for Fist Grab with live viewfinder (continuous)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            visionService.startGestureWatch(this, binding.cameraPreview, VisionService.GESTURE_FIST, this)
        } else {
            checkPermissions()
        }
    }

    private fun triggerFistSend() {
        val file = stagedFile ?: return
        vibratePhone(120)

        // Turn floating pill badge Bright Amber: GRAB DETECTED
        updatePillBadge("✊", "GRAB DETECTED - STAGING FILE", "#F59E0B", "ARMED")

        // Broadcast stage beacon via Wi-Fi UDP and Bluetooth RFCOMM fallback
        networkManager.broadcastArmedDropBeacon(file)
        binding.tvFileSize.text = "${formatFileSize(file.length())} • Staged in Air! Open Palm on PC"
    }

    // ================= 2. INCOMING DROP CONFIRMATION FLOW =================
    override fun onLaptopArmedDrop(filename: String, size: Long, senderIp: String, tcpPort: Int) {
        vibratePhone(120)
        isSessionActive = true

        activeIncomingLaptopIp = senderIp
        activeIncomingPort = tcpPort
        activeIncomingFilename = filename
        activeIncomingSize = size

        // Display incoming file card in bottom drawer
        binding.cardFileBadge.visibility = View.VISIBLE
        binding.tvFileName.text = filename
        binding.tvFileSize.text = "${formatFileSize(size)} • ✋ Show Palm to Receive"

        // Show manual receive button
        binding.manualActionRow.visibility = View.VISIBLE
        binding.btnManualReceive.visibility = View.VISIBLE
        binding.btnManualSend.visibility = View.GONE

        // Update floating pill badge to Purple
        updatePillBadge("✋", "OPEN PALM TO RECEIVE", "#A855F7", "READY")

        // Start front camera watch for Open Palm Drop (continuous)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            visionService.startGestureWatch(this, binding.cameraPreview, VisionService.GESTURE_PALM, this)
        } else {
            checkPermissions()
        }
    }

    private fun triggerPalmReceive() {
        val laptopIp = activeIncomingLaptopIp ?: return
        val filename = activeIncomingFilename ?: "received_file"
        vibratePhone(120)

        // Turn floating pill badge Vibrant Purple: DROP CONFIRMED
        updatePillBadge("✋", "DROP CONFIRMED - RECEIVING STREAM", "#A855F7", "PULL")

        // Transition screen to active transfer view
        binding.transferOverlayContainer.visibility = View.VISIBLE
        binding.transferProgressBar.progress = 0
        binding.tvTransferPercent.text = "0%"
        binding.tvTransferSpeed.text = "⚡ Connecting to Laptop…"

        // Pull file from Laptop over Wi-Fi TCP or Bluetooth RFCOMM fallback
        networkManager.pullFileFromLaptop(laptopIp, activeIncomingPort, filename, activeIncomingSize)
    }

    // ================= 3. VISION CALLBACKS =================
    override fun onGestureDetected(gesture: String) {
        if (gesture == VisionService.GESTURE_FIST) {
            triggerFistSend()
        } else if (gesture == VisionService.GESTURE_PALM) {
            triggerPalmReceive()
        }
    }

    override fun onTimeout() {
        binding.gestureOverlay.clear()
        if (!isSessionActive) {
            updatePillBadge("🔍", "Looking for Hand Gesture…", "#94A3B8", "IDLE")
        }
    }

    override fun onProgress(streak: Int, required: Int, secondsLeft: Float) {
        val badge = if (streak > 0) "$streak/$required" else "LIVE"
        binding.pillTimerBadge.text = badge
    }

    override fun onLandmarks(landmarks: List<NormalizedLandmark>?, gesture: String) {
        binding.gestureOverlay.updateResults(landmarks, gesture, scanning = true)
    }

    // ================= 4. NETWORK CALLBACKS =================
    override fun onTransferProgress(filename: String, percent: Float, speedMbps: Float) {
        binding.transferOverlayContainer.visibility = View.VISIBLE
        binding.transferProgressBar.progress = percent.toInt()
        binding.tvTransferPercent.text = "${percent.toInt()}%"
        binding.tvTransferSpeed.text = "⚡ Streaming: ${String.format("%.1f", speedMbps)} MB/s"

        // Floating pill badge Active Blue
        updatePillBadge("⚡", "TRANSFERRING: ${percent.toInt()}% (${String.format("%.1f", speedMbps)} MB/s)", "#38BDF8", "${percent.toInt()}%")
    }

    override fun onTransferComplete(filename: String, savedFile: File) {
        vibratePhone(220)
        isSessionActive = false

        // Floating pill badge Solid Green
        updatePillBadge("✅", "TRANSFER COMPLETE!", "#10B981", "100%")

        binding.transferOverlayContainer.visibility = View.GONE
        binding.cardFileBadge.visibility = View.GONE
        binding.manualActionRow.visibility = View.GONE
        binding.gestureOverlay.clear()

        // Release camera cleanly and unstage after file complete
        visionService.stopGestureWatch()
        networkManager.unstageFile()

        Toast.makeText(this, "Saved directly to Download/AeroCast/${savedFile.name}", Toast.LENGTH_LONG).show()

        Handler(Looper.getMainLooper()).postDelayed({
            updatePillBadge("🔍", "Looking for Hand Gesture…", "#94A3B8", "READY")
        }, 3000)
    }

    override fun onError(error: String) {
        isSessionActive = false
        binding.transferOverlayContainer.visibility = View.GONE
        binding.gestureOverlay.clear()
        visionService.stopGestureWatch()
        networkManager.unstageFile()
        Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
        updatePillBadge("🔍", "Looking for Hand Gesture…", "#94A3B8", "ERR")
    }

    private fun cancelActiveSession() {
        isSessionActive = false
        stagedFile = null
        activeIncomingLaptopIp = null
        visionService.stopGestureWatch()
        networkManager.unstageFile()
        binding.gestureOverlay.clear()
        binding.cardFileBadge.visibility = View.GONE
        binding.manualActionRow.visibility = View.GONE
        binding.transferOverlayContainer.visibility = View.GONE
        updatePillBadge("🔍", "Looking for Hand Gesture…", "#94A3B8", "READY")
    }

    private fun updatePillBadge(icon: String, text: String, colorHex: String, timerText: String) {
        binding.pillIcon.text = icon
        binding.pillText.text = text
        binding.pillText.setTextColor(Color.parseColor(colorHex))
        binding.pillTimerBadge.text = timerText
        binding.pillTimerBadge.setTextColor(Color.parseColor(colorHex))
    }

    private fun copyUriToInternalFile(uri: Uri): File? {
        try {
            var fileName = "staged_file_${System.currentTimeMillis()}"
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex != -1) {
                    fileName = cursor.getString(nameIndex)
                }
            }

            val cacheFile = File(cacheDir, fileName)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(cacheFile).use { output ->
                    input.copyTo(output)
                }
            }
            return cacheFile
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to load file: ${e.message}", Toast.LENGTH_SHORT).show()
            return null
        }
    }

    private fun vibratePhone(durationMs: Long) {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        }
    }

    private fun formatFileSize(size: Long): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> String.format("%.1f KB", size / 1024f)
            size < 1024 * 1024 * 1024 -> String.format("%.1f MB", size / (1024f * 1024f))
            else -> String.format("%.2f GB", size / (1024f * 1024f * 1024f))
        }
    }
}
