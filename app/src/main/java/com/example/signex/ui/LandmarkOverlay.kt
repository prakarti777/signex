package com.example.signex.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

/**
 * Enhanced LandmarkOverlay with robust error handling and better visibility
 */
class LandmarkOverlay {
    
    companion object {
        private const val TAG = "LandmarkOverlay"
    }
    
    // Enhanced paint styles for better visibility
    private val handDotPaint = Paint().apply {
        color = Color.parseColor("#00E5FF") // Bright cyan
        strokeWidth = 12f
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val handLinePaint = Paint().apply {
        color = Color.parseColor("#00E5FF")
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
        alpha = 200
    }
    
    private val poseDotPaint = Paint().apply {
        color = Color.parseColor("#FF1744") // Bright red red
        strokeWidth = 14f
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val poseLinePaint = Paint().apply {
        color = Color.parseColor("#FFEB3B") // Yellow
        strokeWidth = 5f
        style = Paint.Style.STROKE
        isAntiAlias = true
        alpha = 220
    }

    private val faceDotPaint = Paint().apply {
        color = Color.parseColor("#76FF03") // Bright green
        strokeWidth = 8f
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        isFakeBoldText = true
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }
    
    private val debugPaint = Paint().apply {
        color = Color.YELLOW
        textSize = 30f
        isFakeBoldText = true
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }

    fun draw(
        canvas: Canvas, 
        result: ExtractionResult,
        isFrontCamera: Boolean = false
    ) {
        val viewWidth = canvas.width.toFloat()
        val viewHeight = canvas.height.toFloat()
        
        // Use dimensions from the actual processed frame
        val imageWidth = result.imageWidth
        val imageHeight = result.imageHeight
        
        // User requested 90-degree LEFT rotation for alignment
        // We swap dimensions for the scale calculation when rotating 90 degrees
        val logicalImgWidth = imageHeight.toFloat()
        val logicalImgHeight = imageWidth.toFloat()
        
        if (viewWidth <= 0 || viewHeight <= 0 || logicalImgWidth <= 0 || logicalImgHeight <= 0) return

        // Calculate scale for FILL_CENTER using swapped dimensions
        val scale = maxOf(viewWidth / logicalImgWidth, viewHeight / logicalImgHeight)
        val postScaleWidth = logicalImgWidth * scale
        val postScaleHeight = logicalImgHeight * scale
        val offsetX = (postScaleWidth - viewWidth) / 2f
        val offsetY = (postScaleHeight - viewHeight) / 2f

        /**
         * Apply 90-degree LEFT rotation for Front Camera.
         * For Back Camera, we rotate an additional 180 degrees (mirror both axes) 
         * because the sensor data is typically inverted compared to the front-facing mirror logic.
         */
        fun transformX(x: Float, y: Float): Float {
            val rx = if (isFrontCamera) y else 1.0f - y
            val mirroredX = if (isFrontCamera) 1.0f - rx else rx
            return (mirroredX * postScaleWidth) - offsetX
        }

        fun transformY(x: Float, y: Float): Float {
            val ry = if (isFrontCamera) 1.0f - x else x
            return (ry * postScaleHeight) - offsetY
        }

        try {
            var drawCount = 0
            
            // 1. Draw Hands
            result.handResult?.let { handResult ->
                handResult.landmarks().forEachIndexed { handIndex, handLandmarks ->
                    // Custom line drawing for rotated coords
                    handLandmarks.indices.flatMap { i -> 
                        val connections = listOf(Pair(0, 1), Pair(1, 2), Pair(2, 3), Pair(3, 4), Pair(0, 5), Pair(5, 6), Pair(6, 7), Pair(7, 8), Pair(0, 9), Pair(9, 10), Pair(10, 11), Pair(11, 12), Pair(0, 13), Pair(13, 14), Pair(14, 15), Pair(15, 16), Pair(0, 17), Pair(17, 18), Pair(18, 19), Pair(19, 20), Pair(5, 9), Pair(9, 13), Pair(13, 17))
                        connections.filter { it.first < handLandmarks.size && it.second < handLandmarks.size }
                    }.forEach { (s, e) ->
                        val p1 = handLandmarks[s]; val p2 = handLandmarks[e]
                        canvas.drawLine(transformX(p1.x(), p1.y()), transformY(p1.x(), p1.y()), transformX(p2.x(), p2.y()), transformY(p2.x(), p2.y()), handLinePaint)
                    }

                    handLandmarks.forEach { landmark ->
                        val x = transformX(landmark.x(), landmark.y())
                        val y = transformY(landmark.x(), landmark.y())
                        if (x.isFinite() && y.isFinite()) {
                            canvas.drawCircle(x, y, 10f, handDotPaint)
                            drawCount++
                        }
                    }
                    
                    val handedness = handResult.handedness().getOrNull(handIndex)?.getOrNull(0)?.categoryName() ?: "Unknown"
                    val score = handResult.handedness().getOrNull(handIndex)?.getOrNull(0)?.score() ?: 0f
                    handLandmarks.firstOrNull()?.let {
                        canvas.drawText("$handedness (${(score * 100).toInt()}%)", transformX(it.x(), it.y()), transformY(it.x(), it.y()) - 40, textPaint)
                    }
                }
            }

            // 2. Draw Pose
            result.poseResult?.let { poseResult ->
                if (poseResult.landmarks().isNotEmpty()) {
                    val plm = poseResult.landmarks()[0]
                    val skeleton = listOf(Pair(11, 12), Pair(11, 23), Pair(12, 24), Pair(23, 24), Pair(11, 13), Pair(13, 15), Pair(12, 14), Pair(14, 16))
                    skeleton.forEach { (s, e) ->
                        if (s < plm.size && e < plm.size) {
                            canvas.drawLine(transformX(plm[s].x(), plm[s].y()), transformY(plm[s].x(), plm[s].y()), transformX(plm[e].x(), plm[e].y()), transformY(plm[e].x(), plm[e].y()), poseLinePaint)
                        }
                    }
                    plm.take(25).forEach { lm ->
                        val x = transformX(lm.x(), lm.y()); val y = transformY(lm.x(), lm.y())
                        if (x.isFinite()) { canvas.drawCircle(x, y, 12f, poseDotPaint); drawCount++ }
                    }
                }
            }

            // 3. Draw Face
            result.faceResult?.let { faceResult ->
                if (faceResult.faceLandmarks().isNotEmpty()) {
                    val flm = faceResult.faceLandmarks()[0]
                    for (i in flm.indices step 8) { 
                        val x = transformX(flm[i].x(), flm[i].y())
                        val y = transformY(flm[i].x(), flm[i].y())
                        if (x.isFinite()) {
                            canvas.drawCircle(x, y, 4f, faceDotPaint)
                            drawCount++
                        }
                    }
                }
            }

            
            canvas.drawText("Landmarks: $drawCount | Frame: ${imageWidth}x${imageHeight}", 40f, 100f, debugPaint)
            
        } catch (e: Exception) { Log.e(TAG, "Draw Error", e) }
    }
    
    private fun drawHandConnections(canvas: Canvas, landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>, tx: (Float) -> Float, ty: (Float) -> Float) {
        val connections = listOf(
            Pair(0, 1), Pair(1, 2), Pair(2, 3), Pair(3, 4),
            Pair(0, 5), Pair(5, 6), Pair(6, 7), Pair(7, 8),
            Pair(0, 9), Pair(9, 10), Pair(10, 11), Pair(11, 12),
            Pair(0, 13), Pair(13, 14), Pair(14, 15), Pair(15, 16),
            Pair(0, 17), Pair(17, 18), Pair(18, 19), Pair(19, 20),
            Pair(5, 9), Pair(9, 13), Pair(13, 17)
        )
        connections.forEach { (s, e) ->
            if (s < landmarks.size && e < landmarks.size) {
                val x1 = tx(landmarks[s].x()); val y1 = ty(landmarks[s].y())
                val x2 = tx(landmarks[e].x()); val y2 = ty(landmarks[e].y())
                if (x1.isFinite() && x2.isFinite()) canvas.drawLine(x1, y1, x2, y2, handLinePaint)
            }
        }
    }
}
