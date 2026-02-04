package com.example.signex.ui

import android.app.Activity
import android.graphics.Bitmap
import android.util.Log
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.signex.ml.SignLanguageClassifier
import com.example.signex.utils.FileLogger
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    initialLensFacing: Int = CameraSelector.LENS_FACING_FRONT,
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    var currentLensFacing by remember { mutableStateOf(initialLensFacing) }
    var extractionResult by remember { mutableStateOf<ExtractionResult?>(null) }
    
    val executor = remember { Executors.newSingleThreadExecutor() }
    val classifier = remember { SignLanguageClassifier(context) }
    val overlayDrawer = remember { LandmarkOverlay() }
    val fileLogger = remember { FileLogger.getInstance(context) }

    // Start speech recognition automatically
    LaunchedEffect(Unit) {
        viewModel.startListening(context)
    }

    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
            classifier.close()
        }
    }

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("ANTIGRAVITY", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 2.sp))
                        Text("Vision Perception System", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        currentLensFacing = if (currentLensFacing == CameraSelector.LENS_FACING_FRONT) {
                            CameraSelector.LENS_FACING_BACK
                        } else {
                            CameraSelector.LENS_FACING_FRONT
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Default.FlipCameraAndroid,
                            contentDescription = "Flip Camera",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black, titleContentColor = Color.White, navigationIconContentColor = Color.White)
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize().background(Color.Black)) {
            
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                },
                update = { previewView ->
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = androidx.camera.core.Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                            .build()

                        imageAnalysis.setAnalyzer(executor) { imageProxy ->
                            try {
                                val bitmap = imageProxy.toBitmap()
                                // Use monotonically increasing timestamp from imageProxy
                                // Convert nanoseconds to milliseconds
                                val timestampMs = imageProxy.imageInfo.timestamp / 1_000_000
                                // Pass rotation for correct detection orientation
                                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                                viewModel.lastRotation = rotationDegrees
                                val result = classifier.processFrame(bitmap, timestampMs, rotationDegrees)
                                
                                (context as? Activity)?.runOnUiThread { 
                                    extractionResult = result
                                    viewModel.onGestureDetected(result.gesture)
                                    
                                    // Log extraction result details
                                    val handCount = result.handResult?.landmarks()?.size ?: 0
                                    val poseCount = result.poseResult?.landmarks()?.size ?: 0
                                    if (handCount > 0 || poseCount > 0) {
                                        fileLogger.d("CameraScreen", "ExtractionResult updated: Hands=$handCount, Pose=$poseCount, Gesture=${result.gesture}, TS=$timestampMs, Rot=$rotationDegrees")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("CameraScreen", "Analysis error", e)
                                fileLogger.e("CameraScreen", "Frame analysis error", e)
                            } finally {
                                imageProxy.close()
                            }
                        }

                        try {
                            cameraProvider.unbindAll()
                            val cameraSelector = CameraSelector.Builder().requireLensFacing(currentLensFacing).build()
                            cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
                            fileLogger.i("CameraScreen", "Camera bound successfully: LensFacing=$currentLensFacing")
                        } catch (e: Exception) { 
                            Log.e("CameraScreen", "Binding failed for LensFacing=$currentLensFacing", e)
                            fileLogger.e("CameraScreen", "Binding failed for LensFacing=$currentLensFacing", e)
                        }
                    }, ContextCompat.getMainExecutor(context))
                }
            )

            Canvas(modifier = Modifier.fillMaxSize()) {
                extractionResult?.let { res ->
                    drawContext.canvas.nativeCanvas.apply {
                        overlayDrawer.draw(
                            canvas = this,
                            result = res,
                            isFrontCamera = currentLensFacing == CameraSelector.LENS_FACING_FRONT
                        )
                    }
                }
            }

            // HUD
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
                    .padding(24.dp)
            ) {
                // Speech Input HUD
                val spokenText by viewModel.spokenText.collectAsState()
                if (spokenText.isNotEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.Blue.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).border(1.dp, Color.Blue.copy(alpha = 0.6f), RoundedCornerShape(12.dp)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(Color.Red))
                            Spacer(Modifier.width(8.dp))
                            Text("VOICE_INPUT: \"$spokenText\"", color = Color.White, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                // Warning if tasks are missing
                if (extractionResult?.gesture?.contains("Error") == true) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.Red.copy(alpha = 0.8f)),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    ) {
                        Text("ENGINE_ALERT: ${extractionResult?.gesture}", modifier = Modifier.padding(8.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }

                val smoothedGesture by viewModel.smoothedGesture.collectAsState()
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.6f)).border(1.dp, Color.Cyan.copy(alpha = 0.3f), RoundedCornerShape(12.dp)).padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("GESTURE ENGINE", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                        Text(
                            text = smoothedGesture,
                            color = if (smoothedGesture.contains("Buffering")) Color.Yellow else Color.Cyan,
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold)
                        )
                    }
                    
                    Column(horizontalAlignment = Alignment.End) {
                        val confValue = extractionResult?.confidence ?: 0f
                        Text("ACCURACY", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                        Text("${(confValue * 100).toInt()}%", color = if (confValue > 0.5) Color.Green else Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                    }
                }
            }
            
            Box(modifier = Modifier.padding(16.dp).align(Alignment.TopEnd)) {
                Column(horizontalAlignment = Alignment.End) {
                    Text("VISION_STREAM: ACTIVE", color = Color.Cyan, style = MaterialTheme.typography.labelSmall)
                    if (extractionResult?.isTalking == true) {
                        Text("SPEECH_SYNC: DETECTED", color = Color.Red, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
