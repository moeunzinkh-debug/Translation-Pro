package com.example.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.AiProvider
import com.example.data.model.TranslationTone
import com.example.data.repository.TranslationRepository
import com.example.data.security.SecureSettingsRepository
import com.example.data.subtitle.SubtitleFileContent
import com.example.data.subtitle.SubtitleParser
import com.example.data.subtitle.SubtitleProgress
import com.example.data.subtitle.SubtitleTranslatorEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class SubtitleUiState(
    val subtitleFile: SubtitleFileContent? = null,
    val sourceLanguage: String = "Auto-detect",
    val targetLanguage: String = "English",
    val tone: TranslationTone = TranslationTone.AUTO,
    val batchSize: Int = 8,
    val progress: SubtitleProgress? = null,
    val isTranslating: Boolean = false,
    val isParsing: Boolean = false,
    val errorMessage: String? = null,
    val exportedFilePath: String? = null,
    val infoMessage: String? = null
)

class SubtitleViewModel(
    private val translationRepository: TranslationRepository,
    private val settingsRepository: SecureSettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SubtitleUiState(
            sourceLanguage = settingsRepository.getDefaultSourceLanguage(),
            targetLanguage = settingsRepository.getDefaultTargetLanguage(),
            tone = settingsRepository.getDefaultTone()
        )
    )
    val uiState: StateFlow<SubtitleUiState> = _uiState.asStateFlow()

    private val engine = SubtitleTranslatorEngine(translationRepository)

    fun onSourceLanguageSelected(lang: String) {
        if (_uiState.value.isTranslating) return
        clearPreviousTranslation()
        _uiState.value = _uiState.value.copy(sourceLanguage = lang)
        settingsRepository.setDefaultSourceLanguage(lang)
    }

    fun onTargetLanguageSelected(lang: String) {
        if (_uiState.value.isTranslating) return
        clearPreviousTranslation()
        _uiState.value = _uiState.value.copy(targetLanguage = lang)
        settingsRepository.setDefaultTargetLanguage(lang)
    }

    fun onToneSelected(tone: TranslationTone) {
        if (_uiState.value.isTranslating) return
        clearPreviousTranslation()
        _uiState.value = _uiState.value.copy(tone = tone)
        settingsRepository.setDefaultTone(tone)
    }

    fun onBatchSizeSelected(size: Int) {
        if (_uiState.value.isTranslating) return
        _uiState.value = _uiState.value.copy(batchSize = size.coerceAtLeast(1))
    }

    fun loadSubtitleFromUri(context: Context, uri: Uri, nameHint: String? = null) {
        if (_uiState.value.isTranslating || _uiState.value.isParsing) return
        _uiState.value.subtitleFile?.segments?.forEach { it.translatedText = null }
        _uiState.value = _uiState.value.copy(
            subtitleFile = null,
            isParsing = true,
            errorMessage = null,
            infoMessage = null,
            exportedFilePath = null,
            progress = null
        )

        viewModelScope.launch {
            try {
                val content = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.bufferedReader().readText()
                    } ?: ""
                }

                val fileName = nameHint ?: uri.lastPathSegment ?: "subtitle.srt"

                if (content.isBlank()) {
                    _uiState.value = _uiState.value.copy(
                        isParsing = false,
                        errorMessage = "Selected file is empty."
                    )
                    return@launch
                }

                val parsed = withContext(Dispatchers.Default) {
                    SubtitleParser.parse(fileName, content)
                }

                if (parsed.segments.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isParsing = false,
                        errorMessage = "No valid subtitle dialogue lines found in file."
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        subtitleFile = parsed,
                        isParsing = false,
                        infoMessage = "Loaded ${parsed.segments.size} subtitle entries (${parsed.format.displayName})."
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isParsing = false,
                    errorMessage = "Failed to parse subtitle file: ${e.localizedMessage}"
                )
            }
        }
    }

    fun loadSampleSubtitle() {
        if (_uiState.value.isTranslating || _uiState.value.isParsing) return
        val sampleSrt = """
            1
            00:00:01,000 --> 00:00:04,200
            Welcome to Translate Pro subtitle translator!

            2
            00:00:04,500 --> 00:00:08,100
            It easily translates SRT and VTT files while preserving timing.

            3
            00:00:08,500 --> 00:00:12,000
            Idioms and slang like 'piece of cake' or 'under the weather' are handled smartly.
        """.trimIndent()

        val parsed = SubtitleParser.parse("sample_movie_subtitles.srt", sampleSrt)
        _uiState.value = _uiState.value.copy(
            subtitleFile = parsed,
            progress = null,
            exportedFilePath = null,
            errorMessage = null,
            infoMessage = "Loaded sample subtitle with 3 dialogue blocks."
        )
    }

    fun startTranslation() {
        if (_uiState.value.isTranslating) return
        val file = _uiState.value.subtitleFile ?: run {
            _uiState.value = _uiState.value.copy(errorMessage = "Please select or load a subtitle file first.")
            return
        }

        val provider = settingsRepository.getSelectedProvider()
        val apiKey = settingsRepository.getApiKeyForProvider(provider)
        if (apiKey.isBlank()) {
            val message = if (provider == AiProvider.GEMINI && settingsRepository.getGeminiKeys().isNotEmpty()) {
                "All Gemini keys have reached their app-managed daily request budget. Add another key or wait until tomorrow."
            } else {
                "API Key for ${provider.displayName} is missing. Configure it in Settings."
            }
            _uiState.value = _uiState.value.copy(errorMessage = message)
            return
        }

        // Snapshot the selected options when the user taps Start. This guarantees that every
        // batch in one run uses the same target language, even if the UI is recomposed while the
        // network requests are in flight.
        val selectedSourceLanguage = _uiState.value.sourceLanguage
        val selectedTargetLanguage = _uiState.value.targetLanguage
        val selectedTone = _uiState.value.tone
        val selectedBatchSize = _uiState.value.batchSize

        _uiState.value = _uiState.value.copy(
            isTranslating = true,
            errorMessage = null,
            infoMessage = null,
            exportedFilePath = null,
            progress = null
        )

        viewModelScope.launch {
            try {
                engine.translateSubtitles(
                    subtitleFile = file,
                    sourceLanguage = selectedSourceLanguage,
                    targetLanguage = selectedTargetLanguage,
                    tone = selectedTone,
                    batchSize = selectedBatchSize
                ).collect { progressState ->
                    _uiState.value = _uiState.value.copy(
                        progress = progressState,
                        isTranslating = !progressState.isComplete,
                        errorMessage = progressState.error ?: _uiState.value.errorMessage
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isTranslating = false,
                    errorMessage = "Subtitle translation failed: ${e.localizedMessage ?: "Unknown error"}"
                )
            }
        }
    }

    fun exportAndShare(context: Context, shouldShare: Boolean = false) {
        val currentState = _uiState.value
        val fileContent = currentState.subtitleFile ?: return
        if (currentState.progress?.isComplete != true || currentState.progress?.error != null) {
            _uiState.value = currentState.copy(
                errorMessage = "Translate all subtitle segments successfully before exporting."
            )
            return
        }

        viewModelScope.launch {
            try {
                val serialized = withContext(Dispatchers.Default) {
                    SubtitleParser.serialize(fileContent)
                }
                val langCode = _uiState.value.targetLanguage
                    .lowercase()
                    .replace(Regex("[^a-z0-9]+"), "_")
                    .trim('_')
                    .take(12)
                    .ifBlank { "target" }
                val originalName = fileContent.fileName
                    .substringBeforeLast(".")
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                    .take(100)
                    .ifBlank { "subtitle" }
                val ext = fileContent.format.extension
                val outFileName = "${originalName}_translated_$langCode.$ext"

                val outputDir = File(context.cacheDir, "subtitles")
                if (!outputDir.exists()) outputDir.mkdirs()

                val outFile = File(outputDir, outFileName)
                withContext(Dispatchers.IO) {
                    FileOutputStream(outFile).use { fos ->
                        fos.write(serialized.toByteArray())
                    }
                }

                _uiState.value = _uiState.value.copy(
                    exportedFilePath = outFile.absolutePath,
                    infoMessage = "Exported translated file to $outFileName"
                )

                if (shouldShare) {
                    shareFile(context, outFile)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Export failed: ${e.localizedMessage}"
                )
            }
        }
    }

    private fun shareFile(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share Subtitle File"))
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "Unable to launch share dialog: ${e.localizedMessage}"
            )
        }
    }

    private fun clearPreviousTranslation() {
        _uiState.value.subtitleFile?.segments?.forEach { it.translatedText = null }
        _uiState.value = _uiState.value.copy(
            progress = null,
            errorMessage = null,
            exportedFilePath = null,
            infoMessage = null
        )
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null, infoMessage = null)
    }
}
