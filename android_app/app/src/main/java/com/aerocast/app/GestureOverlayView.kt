package com.aerocast.app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

/**
 * Augmented Reality Canvas Overlay for AeroCast Viewfinder.
 * Renders live hand skeleton landmarks, bounding boxes, and scanning indicators.
 */
class GestureOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var landmarks: List<NormalizedLandmark>? = null
    private var gestureType: String = ""
    private var isScanning: Boolean = false

    // Paint configurations
    private val landmarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8") // Sky blue
        style = Paint.Style.FILL
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8038BDF8")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F59E0B") // Amber
        style = Paint.Style.STROKE
        strokeWidth = 5f
        pathEffect = CornerPathEffect(16f)
    }

    private val glowBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#30F59E0B")
        style = Paint.Style.FILL
        pathEffect = CornerPathEffect(16f)
    }

    // Hand landmark skeleton connections
    private val connections = listOf(
        Pair(0, 1), Pair(1, 2), Pair(2, 3), Pair(3, 4),        // Thumb
        Pair(0, 5), Pair(5, 6), Pair(6, 7), Pair(7, 8),        // Index
        Pair(5, 9), Pair(9, 10), Pair(10, 11), Pair(11, 12),   // Middle
        Pair(9, 13), Pair(13, 14), Pair(14, 15), Pair(15, 16), // Ring
        Pair(13, 17), Pair(17, 18), Pair(18, 19), Pair(19, 20),// Pinky
        Pair(0, 17)                                            // Palm base
    )

    fun updateResults(newLandmarks: List<NormalizedLandmark>?, gesture: String, scanning: Boolean = true) {
        this.landmarks = newLandmarks
        this.gestureType = gesture
        this.isScanning = scanning

        // Update paint colors based on gesture match
        when (gesture) {
            "GRAB" -> {
                val amber = Color.parseColor("#F59E0B")
                landmarkPaint.color = amber
                linePaint.color = Color.parseColor("#80F59E0B")
                boxPaint.color = amber
                glowBoxPaint.color = Color.parseColor("#25F59E0B")
            }
            "DROP" -> {
                val purple = Color.parseColor("#A855F7")
                landmarkPaint.color = purple
                linePaint.color = Color.parseColor("#80A855F7")
                boxPaint.color = purple
                glowBoxPaint.color = Color.parseColor("#25A855F7")
            }
            else -> {
                val sky = Color.parseColor("#38BDF8")
                landmarkPaint.color = sky
                linePaint.color = Color.parseColor("#8038BDF8")
                boxPaint.color = sky
                glowBoxPaint.color = Color.parseColor("#1538BDF8")
            }
        }
        postInvalidate()
    }

    fun clear() {
        this.landmarks = null
        this.gestureType = ""
        this.isScanning = false
        postInvalidate()
    }

    private fun getX(lm: NormalizedLandmark, viewWidth: Float): Float = (1.0f - lm.x()) * viewWidth
    private fun getY(lm: NormalizedLandmark, viewHeight: Float): Float = lm.y() * viewHeight

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val currentLandmarks = landmarks ?: return
        if (currentLandmarks.size < 21) return

        val w = width.toFloat()
        val h = height.toFloat()

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE

        // Calculate bounding box and draw skeleton connections
        for (conn in connections) {
            val p1 = currentLandmarks[conn.first]
            val p2 = currentLandmarks[conn.second]

            val x1 = getX(p1, w)
            val y1 = getY(p1, h)
            val x2 = getX(p2, w)
            val y2 = getY(p2, h)

            canvas.drawLine(x1, y1, x2, y2, linePaint)
        }

        // Draw landmark dots & track bounding box
        for (lm in currentLandmarks) {
            val x = getX(lm, w)
            val y = getY(lm, h)

            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y

            canvas.drawCircle(x, y, 7f, landmarkPaint)
        }

        // Add padding to bounding box
        val pad = 40f
        minX = (minX - pad).coerceAtLeast(10f)
        minY = (minY - pad).coerceAtLeast(10f)
        maxX = (maxX + pad).coerceAtMost(w - 10f)
        maxY = (maxY + pad).coerceAtMost(h - 10f)

        if (maxX > minX && maxY > minY) {
            val rect = RectF(minX, minY, maxX, maxY)
            canvas.drawRoundRect(rect, 24f, 24f, glowBoxPaint)
            canvas.drawRoundRect(rect, 24f, 24f, boxPaint)
        }
    }
}
