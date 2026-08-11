package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
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
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var selected by remember { mutableStateOf<Uri?>(null) }
    var language by remember { mutableStateOf("Auto-detect") }
    var pendingExport by remember { mutableStateOf<String?>(null) }
    var exportMenuExpanded by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selected = it }
    val exportDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let { target ->
            pendingExport?.let { text -> context.contentResolver.openOutputStream(target)?.bufferedWriter()?.use { it.write(text) } }
        }
        pendingExport = null
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Audio to text", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Create a speaker-separated transcript from audio using your active Gemini key.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.AudioFile, null, tint = MaterialTheme.colorScheme.primary)
                Text(selected?.lastPathSegment ?: "Choose an audio file", fontWeight = FontWeight.Medium)
                OutlinedTextField(language, { language = it }, label = { Text("Spoken language (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { picker.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Select audio") }
                Button(
                    onClick = {
                        selected?.let { uri ->
                            viewModel.transcribeFromUri(
                                context = context,
                                uri = uri,
                                mimeType = context.contentResolver.getType(uri) ?: "audio/mpeg",
                                language = language.takeUnless { it == "Auto-detect" }.orEmpty()
                            )
                        }
                    },
                    enabled = selected != null && !state.isWorking,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (state.isWorking) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    else Text("Generate transcript")
                }
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.transcript.isNotBlank()) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Transcript", fontWeight = FontWeight.Bold)
                        val clipboard = LocalClipboardManager.current
                        IconButton(onClick = { clipboard.setText(AnnotatedString(state.transcript)) }) { Icon(Icons.Default.ContentCopy, "Copy transcript") }
                    }
                    Text(
                        "Speaker-separated chunks",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (state.chunks.isEmpty()) {
                        Text(state.transcript)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.chunks.forEach { chunk ->
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(
                                            chunk.speaker,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(chunk.text)
                                    }
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                    Text("Export", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Box(Modifier.fillMaxWidth()) {
                        Button(onClick = { exportMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Download, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Export")
                        }
                        DropdownMenu(expanded = exportMenuExpanded, onDismissRequest = { exportMenuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("Text file (.txt)") },
                                onClick = { exportMenuExpanded = false; pendingExport = state.transcript; exportDocument.launch("transcript.txt") }
                            )
                            DropdownMenuItem(
                                text = { Text("Subtitle file (.srt)") },
                                onClick = { exportMenuExpanded = false; pendingExport = transcriptToSrt(state.transcript); exportDocument.launch("transcript.srt") }
                            )
                        }
                    }
                    Text("SRT timing is estimated from transcript paragraphs. For frame-accurate subtitles, use an audio service that returns timestamps.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Creates readable estimated-duration subtitle cues when the transcription provider has no word timestamps. */
private fun transcriptToSrt(transcript: String): String {
    val cues = transcript.trim().split(Regex("\\n\\s*\\n|(?<=[.!?])\\s+(?=[A-Z])")).filter { it.isNotBlank() }
    var startSeconds = 0
    return cues.mapIndexed { index, cue ->
        val duration = (cue.split(Regex("\\s+")).size / 2).coerceIn(2, 8)
        val endSeconds = startSeconds + duration
        "${index + 1}\n${srtTime(startSeconds)} --> ${srtTime(endSeconds)}\n${cue.trim()}"
            .also { startSeconds = endSeconds }
    }.joinToString("\n\n") + "\n"
}

private fun srtTime(totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d:%02d,000".format(hours, minutes, seconds)
}
