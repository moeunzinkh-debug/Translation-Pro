package com.example.ui.components

object LanguageData {
    val languages = listOf(
        "Auto-detect",
        "English",
        "Spanish",
        "Bahasa Indonesia",
        "Bahasa Melayu",
        "Thai",
        "Vietnamese",
        "Tagalog (Filipino)",
        "Khmer (Cambodian)",
        "Lao",
        "Burmese (Myanmar)",
        "Mandarin (Simplified)",
        "Mandarin (Traditional)",
        "Japanese",
        "Korean",
        "French",
        "German",
        "Italian",
        "Portuguese",
        "Dutch",
        "Russian",
        "Arabic",
        "Hindi",
        "Bengali",
        "Turkish",
        "Polish",
        "Swedish",
        "Danish",
        "Norwegian",
        "Finnish",
        "Greek",
        "Hebrew",
        "Ukrainian"
    )

    val targetLanguages = languages.filter { it != "Auto-detect" }
}
