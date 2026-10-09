package com.aerocast.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.aerocast.app.databinding.ActivityMainBinding
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

    // File picker launcher
    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { handleFileSelected(it) }
    }

    // Runtime permissions launcher
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        if (cameraGranted) {
            Toast.makeText(this, "Camera ready for air gestures", Toast.LENGTH_SHORT).show()
        } else {
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

        // Handle incoming Android share sheet ("Share via AeroCast")
        handleShareIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        networkManager.start()
        val ip = networkManager.getLocalIpAddress()
        binding.tvDeviceIp.text = "LAN: $ip • Port 42424"
    }

    override fun onPause() {
        super.onPause()
        visionService.stopGestureWatch()
        networkManager.stop()
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
    }

    private fun checkPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
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
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            uri?.let { handleFileSelected(it) }
        }
    }

    // ================= FILE STAGING & AIR GRAB FLOW =================
    private fun handleFileSelected(uri: Uri) {
        val file = copyUriToInternalFile(uri) ?: return
        stagedFile = file

        // Update Staged card in UI
        binding.cardStagedFile.visibility = View.VISIBLE
        binding.tvStagedFileName.text = file.name
        binding.tvStagedFileSize.text = "${formatFileSize(file.length())} • Make Fist to Cast"

        // Show Dynamic Capsule
        showCapsule(
            icon = "✊",
            title = "Air Send: ${file.name}",
            subtitle = "Make Fist (✊) to Cast",
            isAccent = true
        )

        // Wake front camera for 5-second window waiting for Fist Grab
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            visionService.startGestureWatch(this, VisionService.GESTURE_FIST, this)
        } else {
            checkPermissions()
        }
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

    // ================= VISION CALLBACKS =================
    override fun onGestureDetected(gesture: String) {
        vibratePhone(120)

        if (gesture == VisionService.GESTURE_FIST) {
            // User confirmed Fist Grab -> broadcast beacon
            val file = stagedFile ?: return
            networkManager.broadcastArmedDropBeacon(file)

            showCapsule(
                icon = "✊",
                title = "File in Air: ${file.name}",
                subtitle = "Open Palm on Laptop to Drop",
                isAccent = true
            )
            binding.tvStatusBody.text = "File is in the air!\nPresent Open Palm (✋) to Laptop webcam to complete transfer."

        } else if (gesture == VisionService.GESTURE_PALM) {
            // User confirmed Open Palm -> pull file from Laptop
            val laptopIp = activeIncomingLaptopIp ?: return
            val filename = activeIncomingFilename ?: "received_file"

            showCapsule(
                icon = "⚡",
                title = "Receiving: $filename",
                subtitle = "High Speed LAN Streaming…",
                isAccent = true
            )
            binding.transferProgress.visibility = View.VISIBLE
            binding.transferProgress.progress = 0

            networkManager.pullFileFromLaptop(laptopIp, activeIncomingPort, filename, activeIncomingSize)
        }
    }

    override fun onTimeout() {
        hideCapsule()
        binding.tvStatusBody.text = "Gesture window closed. Cameras are completely OFF (0% drain)."
    }

    override fun onProgress(streak: Int, required: Int, secondsLeft: Float) {
        if (streak > 0) {
            binding.capsuleBadge.text = "$streak/$required"
        } else {
            binding.capsuleBadge.text = "${secondsLeft.toInt()}s"
        }
    }

    // ================= NETWORK CALLBACKS =================
    override fun onLaptopArmedDrop(filename: String, size: Long, senderIp: String, tcpPort: Int) {
        vibratePhone(100)

        activeIncomingLaptopIp = senderIp
        activeIncomingPort = tcpPort
        activeIncomingFilename = filename
        activeIncomingSize = size

        // Laptop staged a file -> wake up front camera for 5 seconds waiting for Open Palm!
        showCapsule(
            icon = "✋",
            title = "File in Air: $filename",
            subtitle = "Open Palm (✋) to Receive • ${formatFileSize(size)}",
            isAccent = true
        )

        binding.tvStatusBody.text = "Laptop casted '$filename'!\nPresent Open Palm (✋) to phone camera to drop it."
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            visionService.startGestureWatch(this, VisionService.GESTURE_PALM, this)
        } else {
            checkPermissions()
        }
    }

    override fun onTransferProgress(filename: String, percent: Float) {
        binding.transferProgress.visibility = View.VISIBLE
        binding.transferProgress.progress = percent.toInt()
        binding.capsuleSubtitle.text = "Transferring… ${percent.toInt()}%"
        binding.capsuleBadge.text = "${percent.toInt()}%"
    }

    override fun onTransferComplete(filename: String, savedFile: File) {
        vibratePhone(200)

        binding.transferProgress.visibility = View.GONE
        showCapsule(
            icon = "✓",
            title = "Dropped: $filename",
            subtitle = "Saved to Download/AeroCast",
            isAccent = false
        )

        binding.tvStatusBody.text = "Transfer complete! File saved directly to Download/AeroCast/${savedFile.name}."

        Handler(Looper.getMainLooper()).postDelayed({
            hideCapsule()
        }, 2500)
    }

    override fun onError(error: String) {
        binding.transferProgress.visibility = View.GONE
        Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
        hideCapsule()
    }

    // ================= DYNAMIC CAPSULE ANIMATIONS =================
    private fun showCapsule(icon: String, title: String, subtitle: String, isAccent: Boolean) {
        binding.capsuleIcon.text = icon
        binding.capsuleTitle.text = title
        binding.capsuleSubtitle.text = subtitle

        if (binding.dynamicCapsule.visibility != View.VISIBLE) {
            binding.dynamicCapsule.visibility = View.VISIBLE
            binding.dynamicCapsule.translationY = -120f
            binding.dynamicCapsule.alpha = 0f
            binding.dynamicCapsule.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(300)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        }
    }

    private fun hideCapsule() {
        if (binding.dynamicCapsule.visibility == View.VISIBLE) {
            binding.dynamicCapsule.animate()
                .translationY(-120f)
                .alpha(0f)
                .setDuration(250)
                .withEndAction {
                    binding.dynamicCapsule.visibility = View.GONE
                }
                .start()
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
