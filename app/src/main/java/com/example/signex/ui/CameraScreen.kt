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
    
    var currentLensFacing by remember(initialLensFacing) { mutableStateOf(initialLensFacing) }
    var extractionResult by remember { mutableStateOf<ExtractionResult?>(null) }
    
    val executor = remember { Executors.newSingleThreadExecutor() }
    val classifier = viewModel.classifier // Use shared classifier from ViewModel
    val overlayDrawer = remember { LandmarkOverlay() }
    val fileLogger = remember { FileLogger.getInstance(context) }

    // Start speech recognition automatically
    LaunchedEffect(Unit) {
        viewModel.startListening(context)
    }

    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
            // Classifier close is now handled by ViewModel.onCleared
        }
    }

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    val isListening by viewModel.isListening.collectAsState()
    val currentSpeechText by viewModel.spokenText.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Surface(
                        onClick = {
                            if (isListening) viewModel.stopListening()
                            else viewModel.startListening(context)
                        },
                        color = Color.Transparent,
                        contentColor = Color.White
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (isListening) "LISTENING..." else "SIGNEX", 
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold, 
                                        letterSpacing = 2.sp,
                                        color = if (isListening) Color.Red else Color.White
                                    )
                                )
                                if (isListening) {
                                    Spacer(Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color.Red)
                                    )
                                }
                            }
                            Text(
                                if (isListening) "Tap to stop" else "Tap to start voice-to-text", 
                                style = MaterialTheme.typography.labelSmall, 
                                color = Color.Gray
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val newFacing = if (currentLensFacing == CameraSelector.LENS_FACING_FRONT) {
                            CameraSelector.LENS_FACING_BACK
                        } else {
                            CameraSelector.LENS_FACING_FRONT
                        }
                        Log.d("CameraScreen", "Flipping camera to: $newFacing")
                        currentLensFacing = newFacing
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
                    // CRITICAL: Read the state here so Compose knows the update block depends on it
                    val lensFacing = currentLensFacing
                    
                    cameraProviderFuture.addListener({
                        try {
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = androidx.camera.core.Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                            val imageAnalysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                                .build()

                            imageAnalysis.setAnalyzer(executor) { imageProxy ->
                                try {
                                    val bitmap = imageProxy.toBitmap()
                                    val timestampMs = imageProxy.imageInfo.timestamp / 1_000_000
                                    val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                                    viewModel.lastRotation = rotationDegrees
                                    val result = classifier.processFrame(bitmap, timestampMs, rotationDegrees)
                                    
                                    (context as? Activity)?.runOnUiThread { 
                                        extractionResult = result
                                        viewModel.onGestureDetected(result.gesture)
                                        
                                        val handCount = result.handResult?.landmarks()?.size ?: 0
                                        val poseCount = result.poseResult?.landmarks()?.size ?: 0
                                        if (handCount > 0 || poseCount > 0) {
                                            fileLogger.d("CameraScreen", "ExtractionResult updated: Hands=$handCount, Pose=$poseCount, Gesture=${result.gesture}, TS=$timestampMs, Rot=$rotationDegrees")
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e("CameraScreen", "Analysis error", e)
                                } finally {
                                    imageProxy.close()
                                }
                            }

                            cameraProvider.unbindAll()
                            val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                            cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
                            Log.i("CameraScreen", "Camera bound successfully: LensFacing=$lensFacing")
                            fileLogger.i("CameraScreen", "Camera bound successfully: LensFacing=$lensFacing")
                        } catch (e: Exception) { 
                            Log.e("CameraScreen", "Binding failed for LensFacing=$lensFacing", e)
                            fileLogger.e("CameraScreen", "Binding failed for LensFacing=$lensFacing", e)
                        }
                    }, ContextCompat.getMainExecutor(context))
                }
            )

            // Speech HUD - Positioned lower to avoid overlapping with status indicators
            if (currentSpeechText.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.7f)),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 70.dp, start = 16.dp, end = 16.dp, bottom = 16.dp)
                        .fillMaxWidth(0.9f)
                        .border(1.dp, Color.Cyan, RoundedCornerShape(12.dp)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("SPEECH_TO_TEXT", color = Color.Cyan, style = MaterialTheme.typography.labelSmall)
                        Text(
                            text = currentSpeechText,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
                        )
                    }
                }
            }

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
            
            // Status Indicators at the very top edge
            Box(modifier = Modifier.padding(8.dp).fillMaxWidth()) {
                // Vision Stream Active Label
                Text(
                    text = "VISION_STREAM: ACTIVE", 
                    color = Color.Cyan, 
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.TopEnd)
                )
                
                // Speech Sync Indicator
                if (extractionResult?.isTalking == true) {
                    Text(
                        text = "SPEECH_SYNC: DETECTED", 
                        color = Color.Red, 
                        style = MaterialTheme.typography.labelSmall, 
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = 14.dp)
                    )
                }
            }

        }
    }
}
