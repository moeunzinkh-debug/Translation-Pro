package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.repository.TranslationRepository
import com.example.data.transcript.TranscriptChunk
import com.example.data.transcript.TranscriptFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

data class TranscriptUiState(
    val isWorking: Boolean = false,
    val transcript: String = "",
    val chunks: List<TranscriptChunk> = emptyList(),
    val error: String? = null
)

class TranscriptViewModel(private val repository: TranslationRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(TranscriptUiState())
    val uiState: StateFlow<TranscriptUiState> = _uiState.asStateFlow()

    /** Transcribes bytes that are already available, primarily useful for tests and callers with cached audio. */
    fun transcribe(audio: ByteArray, mimeType: String, language: String) = viewModelScope.launch {
        if (_uiState.value.isWorking) return@launch
        try {
            runTranscription(audio, mimeType, language)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.value = TranscriptUiState(
                error = e.localizedMessage ?: "Transcription failed."
            )
        }
    }

    /** Reads a content URI off the main thread before sending it to Gemini. */
    fun transcribeFromUri(
        context: Context,
        uri: Uri,
        mimeType: String,
        language: String
    ) = viewModelScope.launch {
        if (_uiState.value.isWorking) return@launch
        _uiState.value = TranscriptUiState(isWorking = true)
        try {
            val audio = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8 * 1024)
                    var totalBytes = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        if (totalBytes > TranslationRepository.MAX_INLINE_AUDIO_BYTES - count) {
                            throw IOException(
                                "Audio file is too large for inline transcription. Please choose a file smaller than 14 MB."
                            )
                        }
                        output.write(buffer, 0, count)
                        totalBytes += count
                    }
                    output.toByteArray()
                }
            } ?: throw IOException("Unable to read the selected audio file.")

            runTranscription(audio, mimeType, language)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.value = TranscriptUiState(
                error = e.localizedMessage ?: "Could not read the audio file."
            )
        }
    }

    private suspend fun runTranscription(
        audio: ByteArray,
        mimeType: String,
        language: String
    ) {
        _uiState.value = TranscriptUiState(isWorking = true)
        repository.transcribe(audio, mimeType, language).fold(
            onSuccess = { rawTranscript ->
                val chunks = TranscriptFormatter.parse(rawTranscript)
                _uiState.value = TranscriptUiState(
                    transcript = TranscriptFormatter.format(chunks),
                    chunks = chunks
                )
            },
            onFailure = { error ->
                _uiState.value = TranscriptUiState(
                    error = error.localizedMessage ?: "Transcription failed."
                )
            }
        )
    }
}
