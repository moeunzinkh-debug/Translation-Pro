package com.example.data.model

/** A locally stored Gemini credential and its app-managed daily request budget. */
data class GeminiKey(
    val id: String,
    val label: String,
    val value: String,
    val dailyLimit: Int = 20,
    val usedToday: Int = 0,
    val day: String = ""
) {
    val remainingToday: Int get() = (dailyLimit - usedToday).coerceAtLeast(0)
    val maskedValue: String get() = if (value.length < 10) "••••••" else "${value.take(4)}••••${value.takeLast(4)}"
}
