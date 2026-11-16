package dev.unrabbit.app

import android.content.Context

internal enum class SpeechLanguage(val code: String, val label: String) { ENGLISH("en", "English"), GERMAN("de", "Deutsch") }

internal class SpeechPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("unrabbit-speech", Context.MODE_PRIVATE)
    var language: SpeechLanguage
        get() = SpeechLanguage.entries.firstOrNull { it.code == preferences.getString("language", "en") } ?: SpeechLanguage.ENGLISH
        set(value) { preferences.edit().putString("language", value.code).apply() }
}
