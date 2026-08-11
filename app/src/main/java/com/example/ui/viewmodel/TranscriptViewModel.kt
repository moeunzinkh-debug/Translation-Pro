package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.repository.TranslationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TranscriptUiState(val isWorking: Boolean = false, val transcript: String = "", val error: String? = null)
class TranscriptViewModel(private val repository: TranslationRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(TranscriptUiState())
    val uiState: StateFlow<TranscriptUiState> = _uiState.asStateFlow()
    fun transcribe(audio: ByteArray, mimeType: String, language: String) = viewModelScope.launch {
        _uiState.value = _uiState.value.copy(isWorking = true, error = null)
        repository.transcribe(audio, mimeType, language).fold(
            onSuccess = { _uiState.value = TranscriptUiState(transcript = it) },
            onFailure = { _uiState.value = TranscriptUiState(error = it.localizedMessage ?: "Transcription failed.") }
        )
    }
}
