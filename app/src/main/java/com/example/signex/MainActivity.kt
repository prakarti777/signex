package com.example.signex

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.camera.core.CameraSelector
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.example.signex.ui.CameraScreen
import com.example.signex.ui.MainScreen
import com.example.signex.ui.MainViewModel
import com.example.signex.ui.SpeechScreen
import com.example.signex.ui.theme.SignexTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SignexTheme {
                AppNavigation(viewModel)
            }
        }
    }
}

@Composable
fun AppNavigation(viewModel: MainViewModel) {
    var currentScreen by remember { mutableStateOf("main") } // Start on main dashboard for instant response
    var showCameraDialog by remember { mutableStateOf(false) }
    var selectedLensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_FRONT) }
    val context = LocalContext.current

    // Permission Launchers
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            showCameraDialog = true
        } else {
            Toast.makeText(context, "Camera permission needed", Toast.LENGTH_SHORT).show()
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            currentScreen = "speech"
        } else {
            Toast.makeText(context, "Microphone permission needed", Toast.LENGTH_SHORT).show()
        }
    }

    if (showCameraDialog) {
        AlertDialog(
            onDismissRequest = { showCameraDialog = false },
            title = { Text("Select Camera") },
            text = { Text("Which camera would you like to use?") },
            confirmButton = {
                TextButton(onClick = {
                    selectedLensFacing = CameraSelector.LENS_FACING_BACK
                    showCameraDialog = false
                    currentScreen = "camera"
                }) {
                    Text("Back Camera")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    selectedLensFacing = CameraSelector.LENS_FACING_FRONT
                    showCameraDialog = false
                    currentScreen = "camera"
                }) {
                    Text("Front Camera")
                }
            }
        )
    }

    // Check permissions on startup for direct camera load
    LaunchedEffect(Unit) {
        val hasCamera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val hasAudio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        
        if (!hasCamera) cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        if (!hasAudio) audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    when (currentScreen) {
        "main" -> {
            MainScreen(
                onOpenCamera = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        currentScreen = "camera"
                    } else {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                },
                onOpenSpeech = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        currentScreen = "speech"
                    } else {
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            )
        }
        "camera" -> {
            CameraScreen(
                initialLensFacing = selectedLensFacing,
                viewModel = viewModel,
                onBack = { currentScreen = "main" }
            )
        }
        "speech" -> {
            SpeechScreen(viewModel = viewModel, onBack = { currentScreen = "main" })
        }
    }
}
