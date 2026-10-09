package com.aerocast.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.*

/**
 * On-Demand MediaPipe Hand Vision Service with Live CameraX Viewfinder.
 * - 10-second gesture watch window.
 * - Live Preview binding directly to PreviewView and GestureOverlayView.
 * - Calibrated relaxed thresholds:
 *   * Fist: Fingertips <= PIP * 1.10 (>=3 fingers curled)
 *   * Palm: Fingertips >= MCP * 1.18 (>=4 fingers extended)
 *   * Debounce: 2 consecutive frames for instant response.
 */
class VisionService(private val context: Context) {

    companion object {
        private const val TAG = "AeroCastVision"
        const val GESTURE_FIST = "GRAB"
        const val GESTURE_PALM = "DROP"
        private const val DEBOUNCE_FRAMES = 2
        private const val TIMEOUT_MS = 10000L // 10-second full watch window

        // Landmark indices
        private const val WRIST = 0
        private const val THUMB_CMC = 1
        private const val THUMB_MCP = 2
        private const val THUMB_IP = 3
        private const val THUMB_TIP = 4

        // Triplets: (Tip, PIP, MCP)
        private val FINGER_TRIPLETS = listOf(
            Triple(8, 6, 5),   // Index
            Triple(12, 10, 9), // Middle
            Triple(16, 14, 13),// Ring
            Triple(20, 18, 17) // Pinky
        )
    }

    interface VisionCallback {
        fun onGestureDetected(gesture: String)
        fun onTimeout()
        fun onProgress(streak: Int, required: Int, secondsLeft: Float)
        fun onLandmarks(landmarks: List<NormalizedLandmark>?, gesture: String)
    }

    private var handLandmarker: HandLandmarker? = null
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var preview: Preview? = null
    private var callback: VisionCallback? = null

    private var targetGesture: String = GESTURE_PALM
    private var consecutiveStreak = 0
    private var isRunning = false
    private var startTimeMs = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null

    init {
        setupLandmarker()
    }

    private fun setupLandmarker() {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("hand_landmarker.task")
                .build()

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setMinHandDetectionConfidence(0.50f)
                .setMinTrackingConfidence(0.50f)
                .setNumHands(1)
                .setRunningMode(RunningMode.IMAGE)
                .build()

            handLandmarker = HandLandmarker.createFromOptions(context, options)
            Log.d(TAG, "MediaPipe HandLandmarker initialized successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize HandLandmarker: ${e.message}", e)
        }
    }

    /**
     * Wakes up front camera for 10-second watch window with live preview rendering.
     */
    fun startGestureWatch(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView?,
        gesture: String, // GESTURE_FIST or GESTURE_PALM
        listener: VisionCallback
    ) {
        stopGestureWatch()

        targetGesture = gesture
        callback = listener
        consecutiveStreak = 0
        isRunning = true
        startTimeMs = System.currentTimeMillis()

        timeoutRunnable = Runnable {
            if (isRunning) {
                Log.d(TAG, "Gesture window timed out after 10s. Releasing camera.")
                mainHandler.post { callback?.onTimeout() }
                stopGestureWatch()
            }
        }
        mainHandler.postDelayed(timeoutRunnable!!, TIMEOUT_MS)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCamera(lifecycleOwner, previewView)
            } catch (e: Exception) {
                Log.e(TAG, "CameraProvider binding failed: ${e.message}")
                mainHandler.post { callback?.onTimeout() }
                stopGestureWatch()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView?) {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val cameraSelector = if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }

        // 1. Live Viewfinder Preview use case
        preview = Preview.Builder().build().also {
            if (previewView != null) {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
        }

        // 2. High-speed analysis use case
        imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalysis?.setAnalyzer(cameraExecutor) { imageProxy ->
            processImageProxy(imageProxy)
        }

        try {
            if (preview != null) {
                provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
            } else {
                provider.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalysis)
            }
            Log.d(TAG, "Camera bound for on-demand 10s gesture watch ($targetGesture).")
        } catch (e: Exception) {
            Log.e(TAG, "Camera bind to lifecycle failed: ${e.message}")
        }
    }

    private fun processImageProxy(imageProxy: ImageProxy) {
        if (!isRunning || handLandmarker == null) {
            imageProxy.close()
            return
        }

        try {
            val bitmap = imageProxy.toBitmap()
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = handLandmarker?.detect(mpImage)

            evaluateGestureResult(result)
        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error: ${e.message}")
        } finally {
            imageProxy.close()
        }
    }

    private fun evaluateGestureResult(result: HandLandmarkerResult?) {
        val landmarks = result?.landmarks()?.firstOrNull()
        val matched = if (landmarks != null && landmarks.size >= 21) {
            when (targetGesture) {
                GESTURE_FIST -> checkFistGesture(landmarks)
                GESTURE_PALM -> checkOpenPalmGesture(landmarks)
                else -> false
            }
        } else {
            false
        }

        if (matched) {
            consecutiveStreak++
        } else {
            consecutiveStreak = 0
        }

        val elapsed = System.currentTimeMillis() - startTimeMs
        val secondsLeft = max(0f, (TIMEOUT_MS - elapsed) / 1000f)

        mainHandler.post {
            callback?.onLandmarks(landmarks, if (matched) targetGesture else "SEARCHING")
            callback?.onProgress(consecutiveStreak, DEBOUNCE_FRAMES, secondsLeft)
        }

        if (consecutiveStreak >= DEBOUNCE_FRAMES) {
            Log.d(TAG, "Gesture $targetGesture CONFIRMED!")
            val confirmedGesture = targetGesture
            mainHandler.post {
                callback?.onGestureDetected(confirmedGesture)
            }
        }
    }

    /**
     * Fist (Grab):
     * Fingertips (8, 12, 16, 20) distance to wrist <= PIP distance * 1.10.
     * Requires >= 3 curled fingers.
     */
    private fun checkFistGesture(landmarks: List<NormalizedLandmark>): Boolean {
        val wrist = landmarks[WRIST]
        var curledCount = 0

        for (triplet in FINGER_TRIPLETS) {
            val tip = landmarks[triplet.first]
            val pip = landmarks[triplet.second]
            val mcp = landmarks[triplet.third]

            val dTip = dist2D(tip.x(), tip.y(), wrist.x(), wrist.y())
            val dPip = dist2D(pip.x(), pip.y(), wrist.x(), wrist.y())
            val dMcp = dist2D(mcp.x(), mcp.y(), wrist.x(), wrist.y())

            if ((dPip > 0f && dTip <= dPip * 1.10f) || (dMcp > 0f && dTip <= dMcp * 1.10f)) {
                curledCount++
            }
        }

        // Thumb curl check
        val thumbTip = landmarks[THUMB_TIP]
        val thumbIp = landmarks[THUMB_IP]
        val dThumbTip = dist2D(thumbTip.x(), thumbTip.y(), wrist.x(), wrist.y())
        val dThumbIp = dist2D(thumbIp.x(), thumbIp.y(), wrist.x(), wrist.y())
        if (dThumbIp > 0f && dThumbTip <= dThumbIp * 1.15f) {
            curledCount++
        }

        return curledCount >= 3
    }

    /**
     * Open Palm (Drop):
     * Fingertips (8, 12, 16, 20) distance to wrist >= MCP distance * 1.18.
     * Requires >= 4 extended fingers.
     */
    private fun checkOpenPalmGesture(landmarks: List<NormalizedLandmark>): Boolean {
        val wrist = landmarks[WRIST]
        var extendedCount = 0

        for (triplet in FINGER_TRIPLETS) {
            val tip = landmarks[triplet.first]
            val mcp = landmarks[triplet.third]

            val dTip = dist2D(tip.x(), tip.y(), wrist.x(), wrist.y())
            val dMcp = dist2D(mcp.x(), mcp.y(), wrist.x(), wrist.y())

            if (dMcp > 0f && dTip >= dMcp * 1.18f) {
                extendedCount++
            }
        }

        return extendedCount >= 4
    }

    private fun dist2D(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Stops camera session and releases hardware cleanly.
     */
    fun stopGestureWatch() {
        isRunning = false
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null

        mainHandler.post {
            try {
                cameraProvider?.unbindAll()
                imageAnalysis?.clearAnalyzer()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping camera: ${e.message}")
            }
        }
    }
}
