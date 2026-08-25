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

    /** ISO-639-style codes used for naming exported files. */
    private val fileCodes = mapOf(
        "English" to "eng",
        "Spanish" to "spa",
        "Bahasa Indonesia" to "ind",
        "Bahasa Melayu" to "msa",
        "Thai" to "tha",
        "Vietnamese" to "vie",
        "Tagalog (Filipino)" to "tgl",
        "Khmer (Cambodian)" to "khm",
        "Lao" to "lao",
        "Burmese (Myanmar)" to "mya",
        "Mandarin (Simplified)" to "zho-hans",
        "Mandarin (Traditional)" to "zho-hant",
        "Japanese" to "jpn",
        "Korean" to "kor",
        "French" to "fra",
        "German" to "deu",
        "Italian" to "ita",
        "Portuguese" to "por",
        "Dutch" to "nld",
        "Russian" to "rus",
        "Arabic" to "ara",
        "Hindi" to "hin",
        "Bengali" to "ben",
        "Turkish" to "tur",
        "Polish" to "pol",
        "Swedish" to "swe",
        "Danish" to "dan",
        "Norwegian" to "nor",
        "Finnish" to "fin",
        "Greek" to "ell",
        "Hebrew" to "heb",
        "Ukrainian" to "ukr"
    )

    /** Short lowercase suffix for exported file names, e.g. "Khmer (Cambodian)" -> "khm". */
    fun fileCodeFor(language: String): String {
        return fileCodes[language] ?: language.take(3).lowercase()
    }
}
