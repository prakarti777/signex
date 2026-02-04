package com.example.signex.ui

import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

data class ExtractionResult(
    val gesture: String,
    val handResult: HandLandmarkerResult?,
    val poseResult: PoseLandmarkerResult?,
    val faceResult: FaceLandmarkerResult?,
    val isTalking: Boolean = false,
    val confidence: Float = 0f,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0
)
