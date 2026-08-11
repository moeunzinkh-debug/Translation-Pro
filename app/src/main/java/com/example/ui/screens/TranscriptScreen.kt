package com.example.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.viewmodel.TranscriptViewModel

@Composable
fun TranscriptScreen(viewModel: TranscriptViewModel) {
    val state by viewModel.uiState.collectAsState(); val context = LocalContext.current
    var selected by remember { mutableStateOf<Uri?>(null) }; var language by remember { mutableStateOf("Auto-detect") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selected = it }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Audio to text", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Create an accurate transcript from audio using your active Gemini key.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.AudioFile, null, tint = MaterialTheme.colorScheme.primary)
            Text(selected?.lastPathSegment ?: "Choose an audio file", fontWeight = FontWeight.Medium)
            OutlinedTextField(language, { language = it }, label = { Text("Spoken language (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { picker.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Select audio") }
            Button(onClick = { selected?.let { uri -> context.contentResolver.openInputStream(uri)?.use { input -> viewModel.transcribe(input.readBytes(), context.contentResolver.getType(uri) ?: "audio/mpeg", language.takeUnless { it == "Auto-detect" }.orEmpty()) } } }, enabled = selected != null && !state.isWorking, modifier = Modifier.fillMaxWidth()) {
                if (state.isWorking) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp) else Text("Generate transcript")
            }
        }}
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.transcript.isNotBlank()) ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Transcript", fontWeight = FontWeight.Bold); val clip = LocalClipboardManager.current; IconButton(onClick = { clip.setText(AnnotatedString(state.transcript)) }) { Icon(Icons.Default.ContentCopy, "Copy transcript") } }
            Text(state.transcript)
        }}
    }
}
