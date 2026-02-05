package com.example.signex.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.signex.ui.ExtractionResult
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import com.example.signex.utils.FileLogger
import org.tensorflow.lite.Interpreter
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt
import kotlin.math.max

class SignLanguageClassifier(private val context: Context) {

    private val fileLogger = FileLogger.getInstance(context)

    companion object {
        private const val TAG = "AntigravityML"
        private const val SEQUENCE_LENGTH = 30

        // TFL3 Spec: [1, 30, 171] => Left(63) + Right(63) + Pose(45)
        private const val INPUT_DIM = 171
        private const val CONFIDENCE_THRESHOLD = 0.60f

        private const val MODEL_PATH = "model.tflite"
        private const val LABELS_PATH = "label_mapping2.txt"

        // Required Tasks
        private const val HAND_TASK = "hand_landmarker.task"
        private const val POSE_TASK = "pose_landmarker.task"
        private const val FACE_TASK = "face_landmarker.task" 
        private const val ENABLE_UI_FACE = true
    }

    private var handLandmarker: HandLandmarker? = null
    private var poseLandmarker: PoseLandmarker? = null
    private var faceLandmarker: FaceLandmarker? = null
    private var tfliteInterpreter: Interpreter? = null

    // For debugging initialization issues on the screen
    private val globalError = AtomicReference<String?>(null)

    // Pre-allocated buffers
    private var inputBuffer: ByteBuffer? = null
    private var outputBuffer: ByteBuffer? = null
    private var modelOutputClasses = 15 // Fixed to model spec

    private val frameBuffer = ArrayDeque<FloatArray>()
    private val labels = mutableListOf<String>()

    init {
        fileLogger.i(TAG, "========== SignLanguageClassifier Initialization Started ==========")
        fileLogger.i(TAG, "Log file location: ${fileLogger.getLogFilePath()}")
        setupLandmarkers()
        setupTFLite()
        loadLabels()
        fileLogger.i(TAG, "========== Initialization Complete ==========")
    }

    private fun setupLandmarkers() {
        fileLogger.i(TAG, "Setting up MediaPipe Landmarkers...")
        try {
            // Check if asset files exist
            val assets = context.assets.list("") ?: arrayOf()
            fileLogger.d(TAG, "Assets found: ${assets.joinToString()}")
            
            if (!assets.contains(HAND_TASK)) {
                fileLogger.e(TAG, "✗ CRITICAL: $HAND_TASK missing from assets!")
            }
            if (!assets.contains(POSE_TASK)) {
                fileLogger.e(TAG, "✗ CRITICAL: $POSE_TASK missing from assets!")
            }

            val baseOptionsBuilder = BaseOptions.builder()
                .setDelegate(com.google.mediapipe.tasks.core.Delegate.GPU)

            // Hand Landmarker
            try {
                handLandmarker = HandLandmarker.createFromOptions(context,
                    HandLandmarker.HandLandmarkerOptions.builder()
                        .setBaseOptions(baseOptionsBuilder.setModelAssetPath(HAND_TASK).build())
                        .setRunningMode(RunningMode.VIDEO)
                        .setNumHands(2)
                        .build())
                fileLogger.i(TAG, "✓ Hand Landmarker loaded (GPU)")
            } catch (t: Throwable) {
                fileLogger.e(TAG, "✗ Hand Landmarker GPU fail, falling back", t)
                handLandmarker = HandLandmarker.createFromOptions(context,
                    HandLandmarker.HandLandmarkerOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetPath(HAND_TASK).build())
                        .setRunningMode(RunningMode.VIDEO).setNumHands(2).build())
            }

            // Pose Landmarker
            try {
                poseLandmarker = PoseLandmarker.createFromOptions(context,
                    PoseLandmarker.PoseLandmarkerOptions.builder()
                        .setBaseOptions(baseOptionsBuilder.setModelAssetPath(POSE_TASK).build())
                        .setRunningMode(RunningMode.VIDEO).build())
                fileLogger.i(TAG, "✓ Pose Landmarker loaded (GPU)")
            } catch (t: Throwable) {
                poseLandmarker = PoseLandmarker.createFromOptions(context,
                    PoseLandmarker.PoseLandmarkerOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetPath(POSE_TASK).build())
                        .setRunningMode(RunningMode.VIDEO).build())
            }

            // Face Landmarker
            if (ENABLE_UI_FACE) {
                try {
                    faceLandmarker = FaceLandmarker.createFromOptions(context,
                        FaceLandmarker.FaceLandmarkerOptions.builder()
                            .setBaseOptions(baseOptionsBuilder.setModelAssetPath(FACE_TASK).build())
                            .setRunningMode(RunningMode.VIDEO).build())
                    fileLogger.i(TAG, "✓ Face Landmarker loaded (GPU)")
                } catch (t: Throwable) {
                    fileLogger.w(TAG, "Face Landmarker fallback")
                }
            }
        } catch (t: Throwable) {
            val errorMsg = "Landmarker Fail: ${t.message}"
            fileLogger.e(TAG, errorMsg, t)
            globalError.set(errorMsg)
        }
    }

    private fun setupTFLite() {
        try {
            val fileDescriptor = context.assets.openFd(MODEL_PATH)
            val mappedByteBuffer = FileInputStream(fileDescriptor.fileDescriptor).channel
                .map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
            
            val options = Interpreter.Options()
            options.setNumThreads(4)
            
            val interpreter = Interpreter(mappedByteBuffer, options)
            tfliteInterpreter = interpreter
            fileLogger.i(TAG, "✓ TFLite Model loaded successfully")

            // Read input/output shapes dynamically
            val inputShape = interpreter.getInputTensor(0).shape()
            Log.d(TAG, "TFLite Input Shape: ${inputShape.contentToString()}")
            
            val outputTensor = interpreter.getOutputTensor(0)
            val outputShape = outputTensor.shape()
            Log.d(TAG, "TFLite Output Shape: ${outputShape.contentToString()}")
            
            if (outputShape != null && outputShape.size > 1) {
                modelOutputClasses = outputShape[1]
            }

            // Pre-allocate buffers
            inputBuffer = ByteBuffer.allocateDirect(1 * SEQUENCE_LENGTH * INPUT_DIM * 4).order(ByteOrder.nativeOrder())
            outputBuffer = ByteBuffer.allocateDirect(1 * modelOutputClasses * 4).order(ByteOrder.nativeOrder())

        } catch (t: Throwable) {
            val errorMsg = "TFLite Load Error: ${t.message}"
            fileLogger.e(TAG, errorMsg, t)
            globalError.set(errorMsg)
        }
    }

    private fun loadLabels() {
        try {
            context.assets.open(LABELS_PATH).use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).useLines { lines ->
                    lines.forEach { line ->
                        if (line.isNotBlank()) {
                            // Handle "Label,Index" format if present
                            val label = line.split(",")[0].trim()
                            labels.add(label)
                        }
                    }
                }
            }
            // Limit to model output classes if known (8)
            // But we'll let inference map strictly by index
        } catch (e: Exception) {
            Log.e(TAG, "Labels Load Error", e)
        }
    }

    fun processFrame(bitmap: Bitmap, timestampMs: Long, rotationDegrees: Int = 0): ExtractionResult {
        val mpImage = com.google.mediapipe.framework.image.BitmapImageBuilder(bitmap).build()
        
        // Build Processing Options: 
        // Force 0 for detection because CameraX toBitmap() already rotates the bitmap to upright.
        val imageProcessingOptions = com.google.mediapipe.tasks.vision.core.ImageProcessingOptions.builder()
            .setRotationDegrees(0) 
            .build()

        // 1. Detect
        val handResult = handLandmarker?.detectForVideo(mpImage, imageProcessingOptions, timestampMs)
        val poseResult = poseLandmarker?.detectForVideo(mpImage, imageProcessingOptions, timestampMs)
        val faceResult = if (ENABLE_UI_FACE && faceLandmarker != null) {
            faceLandmarker?.detectForVideo(mpImage, imageProcessingOptions, timestampMs)
        } else null
        
        // Log detection results
        val handCount = handResult?.landmarks()?.size ?: 0
        val poseCount = poseResult?.landmarks()?.size ?: 0
        val faceCount = faceResult?.faceLandmarks()?.size ?: 0
        fileLogger.d(TAG, "Frame: ${bitmap.width}x${bitmap.height}, Hands=$handCount, Pose=$poseCount, Face=$faceCount, Rot_In=$rotationDegrees")

        // 2. Extract Features: [Left(63) | Right(63) | Pose(45)] = 171
        val features = FloatArray(INPUT_DIM)
        var hasData = false

        // --- Hands ---
        // MediaPipe Hands output is not guaranteed Left/Right order. Check handedness.
        var leftHandLm: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>? = null
        var rightHandLm: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>? = null

        if (handResult != null) {
            val landmarksList = handResult.landmarks()
            val handednessList = handResult.handedness()
            
            fileLogger.d(TAG, "Hand detection: ${landmarksList.size} hand(s) detected")
            
            for (i in landmarksList.indices) {
                val handedness = handednessList.getOrNull(i)?.getOrNull(0)?.categoryName() ?: "Right" // Default
                val confidence = handednessList.getOrNull(i)?.getOrNull(0)?.score() ?: 0f
                fileLogger.d(TAG, "  Hand $i: $handedness (confidence: ${String.format("%.2f", confidence)})")
                
                if (handedness == "Left") {
                    leftHandLm = landmarksList[i]
                } else {
                    rightHandLm = landmarksList[i]
                }
            }
        } else {
            fileLogger.d(TAG, "No hands detected in this frame")
        }

        // Helper to flatten 21 landmarks -> 63 floats
        fun addHandFeatures(landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>?, offset: Int) {
            if (landmarks != null) {
                hasData = true
                for (j in 0 until 21) {
                    features[offset + j * 3] = landmarks[j].x()
                    features[offset + j * 3 + 1] = landmarks[j].y()
                    features[offset + j * 3 + 2] = landmarks[j].z() // Depth
                }
            }
            // Else remains 0.0f
        }

        addHandFeatures(leftHandLm, 0)   // 0..62
        addHandFeatures(rightHandLm, 63) // 63..125

        // --- Pose ---
        // Extracting first 15 landmarks (0-14) * 3 = 45 floats
        // Map: 126..170
        if (poseResult != null && poseResult.landmarks().isNotEmpty()) {
            val plm = poseResult.landmarks()[0]
            val count = minOf(15, plm.size)
            fileLogger.d(TAG, "Pose landmarks extracted: $count points")
            for (j in 0 until count) {
                features[126 + j * 3] = plm[j].x()
                features[126 + j * 3 + 1] = plm[j].y()
                features[126 + j * 3 + 2] = plm[j].z()
            }
        } else {
            fileLogger.d(TAG, "No pose landmarks detected")
        }
        // 3. Sliding Window Buffer Logic
        var gesture = ""
        var confidence = 0f

        // Surface global initialization errors if any
        val errorMsg = globalError.get()
        if (errorMsg != null) {
            gesture = "Error: $errorMsg"
        } else {
            synchronized(frameBuffer) {
                // Push current features into buffer. If hasData is false, it's already zeros.
                frameBuffer.addLast(features)
                
                // Keep buffer at exactly SEQUENCE_LENGTH
                while (frameBuffer.size > SEQUENCE_LENGTH) {
                    frameBuffer.removeFirst()
                }

                // Sliding Window: Run inference on EVERY frame once buffer is full
                if (frameBuffer.size == SEQUENCE_LENGTH) {
                    val hasAnyHands = frameBuffer.any { 
                        it.any { value -> value != 0.0f } 
                    }

                    if (hasAnyHands) {
                        val output = runInference()
                        gesture = output.first
                        confidence = output.second
                    } else {
                        gesture = "WAITING..."
                    }
                } else {
                    gesture = "Buffering (${frameBuffer.size}/$SEQUENCE_LENGTH)"
                }
            }
        }

        // 4. Speech/Talking Detection (Mouth open check)
        var isTalking = false
        if (faceResult != null && faceResult.faceLandmarks().isNotEmpty()) {
            val flm = faceResult.faceLandmarks()[0]
            if (flm.size > 14) {
                // Indices 13 (top lip) and 14 (bottom lip)
                val dist = Math.abs(flm[13].y() - flm[14].y())
                isTalking = dist > 0.022f // Slightly increased threshold for accuracy
                if (isTalking) fileLogger.d(TAG, "Speech detected - Mouth distance: ${String.format("%.4f", dist)}")
            }
        }

        return ExtractionResult(gesture, handResult, poseResult, faceResult, isTalking, confidence, bitmap.width, bitmap.height)
    }

    private fun runInference(): Pair<String, Float> {
        val interpreter = tfliteInterpreter ?: return "Model Error" to 0f
        val inBuf = inputBuffer ?: return "Alloc Error" to 0f
        val outBuf = outputBuffer ?: return "Alloc Error" to 0f

        // Reset buffers
        inBuf.rewind()
        outBuf.rewind()

        // Fill Input directly
        synchronized(frameBuffer) {
            frameBuffer.forEach { frame -> frame.forEach { inBuf.putFloat(it) } }
        }
        
        try {
            interpreter.run(inBuf, outBuf)
            outBuf.rewind()

            var maxIdx = -1
            var maxProb = 0f
            // Read output
            for (i in 0 until modelOutputClasses) {
                 val prob = outBuf.float
                 if (prob > maxProb) {
                     maxProb = prob
                     maxIdx = i
                 }
            }

            val label = if (maxIdx in labels.indices) labels[maxIdx] else "Class $maxIdx"

            return if (maxProb >= CONFIDENCE_THRESHOLD) {
                label to maxProb
            } else {
                "$label?" to maxProb
            }
        } catch (e: Exception) {
            Log.e(TAG, "Inference Failed", e)
            return "Inference Error: ${e.message}" to 0f
        }
    }

    fun close() {
        handLandmarker?.close()
        poseLandmarker?.close()
        faceLandmarker?.close()
        tfliteInterpreter?.close()
    }
}
