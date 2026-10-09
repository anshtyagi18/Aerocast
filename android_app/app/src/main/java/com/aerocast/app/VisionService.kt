package com.aerocast.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
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
 * On-Demand MediaPipe Hand Vision Service for Android.
 * Front camera is activated strictly for a 5-second window upon staging or beacon receipt,
 * and released immediately upon detection or timeout to guarantee zero battery drain.
 */
class VisionService(private val context: Context) {

    companion object {
        private const val TAG = "AeroCastVision"
        const val GESTURE_FIST = "GRAB"
        const val GESTURE_PALM = "DROP"
        private const val DEBOUNCE_FRAMES = 3
        private const val TIMEOUT_MS = 5000L // 5-second auto-release to prevent battery drain

        // Landmark indices
        private const val WRIST = 0
        private const val THUMB_MCP = 2
        private const val THUMB_TIP = 4

        private val FINGER_PAIRS = listOf(
            Pair(8, 5),   // Index Tip, Index MCP
            Pair(12, 9),  // Middle Tip, Middle MCP
            Pair(16, 13), // Ring Tip, Ring MCP
            Pair(20, 17)  // Pinky Tip, Pinky MCP
        )
    }

    interface VisionCallback {
        fun onGestureDetected(gesture: String)
        fun onTimeout()
        fun onProgress(streak: Int, required: Int, secondsLeft: Float)
    }

    private var handLandmarker: HandLandmarker? = null
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
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
                .setMinHandDetectionConfidence(0.65f)
                .setMinTrackingConfidence(0.65f)
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
     * Wakes up front camera for at most 5 seconds to detect target gesture.
     */
    fun startGestureWatch(
        lifecycleOwner: LifecycleOwner,
        gesture: String, // GESTURE_FIST or GESTURE_PALM
        listener: VisionCallback
    ) {
        stopGestureWatch()

        targetGesture = gesture
        callback = listener
        consecutiveStreak = 0
        isRunning = true
        startTimeMs = System.currentTimeMillis()

        // Set 5-second auto-release timeout to prevent battery drain
        timeoutRunnable = Runnable {
            if (isRunning) {
                Log.d(TAG, "Gesture window timed out after 5 seconds. Releasing camera.")
                mainHandler.post { callback?.onTimeout() }
                stopGestureWatch()
            }
        }
        mainHandler.postDelayed(timeoutRunnable!!, TIMEOUT_MS)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCamera(lifecycleOwner)
            } catch (e: Exception) {
                Log.e(TAG, "CameraProvider binding failed: ${e.message}")
                mainHandler.post { callback?.onTimeout() }
                stopGestureWatch()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCamera(lifecycleOwner: LifecycleOwner) {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        // Prefer front camera, fallback gracefully if absent
        val cameraSelector = if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }

        imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalysis?.setAnalyzer(cameraExecutor) { imageProxy ->
            processImageProxy(imageProxy)
        }

        try {
            provider.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalysis)
            Log.d(TAG, "Camera bound for on-demand gesture watch ($targetGesture).")
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
            callback?.onProgress(consecutiveStreak, DEBOUNCE_FRAMES, secondsLeft)
        }

        if (consecutiveStreak >= DEBOUNCE_FRAMES) {
            Log.d(TAG, "Gesture $targetGesture CONFIRMED! Releasing camera immediately.")
            val confirmedGesture = targetGesture
            mainHandler.post {
                callback?.onGestureDetected(confirmedGesture)
            }
            stopGestureWatch()
        }
    }

    /**
     * Fist Grab:
     * All 4 fingertips (8, 12, 16, 20) Euclidean distance to Wrist (0)
     * must be <= 0.85 relative to MCP joints (5, 9, 13, 17) to Wrist distance.
     */
    private fun checkFistGesture(landmarks: List<NormalizedLandmark>): Boolean {
        val wrist = landmarks[WRIST]
        for (pair in FINGER_PAIRS) {
            val tip = landmarks[pair.first]
            val mcp = landmarks[pair.second]

            val dTip = dist3D(tip.x(), tip.y(), tip.z(), wrist.x(), wrist.y(), wrist.z())
            val dMcp = dist3D(mcp.x(), mcp.y(), mcp.z(), wrist.x(), wrist.y(), wrist.z())

            if (dMcp == 0f || (dTip / dMcp) > 0.85f) {
                return false
            }
        }
        return true
    }

    /**
     * Open Palm Drop:
     * All 4 fingertips to Wrist distance >= 1.30 * MCP to Wrist distance.
     * Thumb extended outward: angle between Landmark 4 and 2 relative to wrist >= 45 degrees.
     */
    private fun checkOpenPalmGesture(landmarks: List<NormalizedLandmark>): Boolean {
        val wrist = landmarks[WRIST]

        // 4 fingers extension check
        for (pair in FINGER_PAIRS) {
            val tip = landmarks[pair.first]
            val mcp = landmarks[pair.second]

            val dTip = dist3D(tip.x(), tip.y(), tip.z(), wrist.x(), wrist.y(), wrist.z())
            val dMcp = dist3D(mcp.x(), mcp.y(), mcp.z(), wrist.x(), wrist.y(), wrist.z())

            if (dMcp == 0f || (dTip / dMcp) < 1.30f) {
                return false
            }
        }

        // Thumb extension angle relative to wrist
        val thumbMcp = landmarks[THUMB_MCP]
        val thumbTip = landmarks[THUMB_TIP]

        val v1x = thumbMcp.x() - wrist.x()
        val v1y = thumbMcp.y() - wrist.y()
        val v2x = thumbTip.x() - wrist.x()
        val v2y = thumbTip.y() - wrist.y()

        val angle = angleBetween2D(v1x, v1y, v2x, v2y)
        return angle >= 45.0
    }

    private fun dist3D(x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        val dz = z1 - z2
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun angleBetween2D(v1x: Float, v1y: Float, v2x: Float, v2y: Float): Double {
        val dot = (v1x * v2x + v1y * v2y).toDouble()
        val m1 = sqrt((v1x * v1x + v1y * v1y).toDouble())
        val m2 = sqrt((v2x * v2x + v2y * v2y).toDouble())
        if (m1 * m2 == 0.0) return 0.0
        val cos = max(-1.0, min(1.0, dot / (m1 * m2)))
        return Math.toDegrees(acos(cos))
    }

    /**
     * Cleanly stops camera session and releases hardware immediately.
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
