package com.example.signex.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onOpenCamera: () -> Unit,
    onOpenSpeech: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Signex") })
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = onOpenCamera,
                modifier = Modifier.fillMaxWidth().height(80.dp)
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(32.dp))
                Spacer(modifier = Modifier.size(16.dp))
                Text("Open Camera", style = MaterialTheme.typography.titleLarge)
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onOpenSpeech,
                modifier = Modifier.fillMaxWidth().height(80.dp)
            ) {
                Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(32.dp))
                Spacer(modifier = Modifier.size(16.dp))
                Text("Speech to Text", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}
