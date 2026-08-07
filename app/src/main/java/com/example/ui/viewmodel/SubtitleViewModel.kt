package com.example.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.TranslationTone
import com.example.data.repository.TranslationRepository
import com.example.data.security.SecureSettingsRepository
import com.example.data.subtitle.SubtitleFileContent
import com.example.data.subtitle.SubtitleParser
import com.example.data.subtitle.SubtitleProgress
import com.example.data.subtitle.SubtitleTranslatorEngine
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
        _uiState.value = _uiState.value.copy(sourceLanguage = lang)
    }

    fun onTargetLanguageSelected(lang: String) {
        _uiState.value = _uiState.value.copy(targetLanguage = lang)
    }

    fun onToneSelected(tone: TranslationTone) {
        _uiState.value = _uiState.value.copy(tone = tone)
    }

    fun onBatchSizeSelected(size: Int) {
        _uiState.value = _uiState.value.copy(batchSize = size)
    }

    fun loadSubtitleFromUri(context: Context, uri: Uri, nameHint: String? = null) {
        _uiState.value = _uiState.value.copy(
            isParsing = true,
            errorMessage = null,
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

                val parsed = SubtitleParser.parse(fileName, content)

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
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isParsing = false,
                    errorMessage = "Failed to parse subtitle file: ${e.localizedMessage}"
                )
            }
        }
    }

    fun loadSampleSubtitle() {
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
            errorMessage = null,
            infoMessage = "Loaded sample subtitle with 3 dialogue blocks."
        )
    }

    fun startTranslation() {
        val file = _uiState.value.subtitleFile ?: run {
            _uiState.value = _uiState.value.copy(errorMessage = "Please select or load a subtitle file first.")
            return
        }

        val provider = settingsRepository.getSelectedProvider()
        val apiKey = settingsRepository.getApiKeyForProvider(provider)
        if (apiKey.isBlank()) {
            _uiState.value = _uiState.value.copy(
                errorMessage = "API Key for ${provider.displayName} is missing. Configure it in Settings."
            )
            return
        }

        _uiState.value = _uiState.value.copy(
            isTranslating = true,
            errorMessage = null,
            exportedFilePath = null
        )

        viewModelScope.launch {
            engine.translateSubtitles(
                subtitleFile = file,
                sourceLanguage = _uiState.value.sourceLanguage,
                targetLanguage = _uiState.value.targetLanguage,
                tone = _uiState.value.tone,
                batchSize = _uiState.value.batchSize
            ).collect { progressState ->
                _uiState.value = _uiState.value.copy(
                    progress = progressState,
                    isTranslating = !progressState.isComplete
                )
            }
        }
    }

    fun exportAndShare(context: Context, shouldShare: Boolean = false) {
        val fileContent = _uiState.value.subtitleFile ?: return
        viewModelScope.launch {
            try {
                val serialized = SubtitleParser.serialize(fileContent)
                val langCode = _uiState.value.targetLanguage.take(3).lowercase()
                val originalName = fileContent.fileName.substringBeforeLast(".")
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

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null, infoMessage = null)
    }
}
