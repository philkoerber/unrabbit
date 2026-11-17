package dev.unrabbit.app

import android.content.Context

internal enum class SpeechLanguage(val code: String, val label: String) { ENGLISH("en", "English"), GERMAN("de", "German") }

internal class SpeechPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("unrabbit-speech", Context.MODE_PRIVATE)
    var readAloud: Boolean
        get() = preferences.getBoolean("read-aloud", true)
        set(value) { preferences.edit().putBoolean("read-aloud", value).apply() }
    var language: SpeechLanguage
        get() = SpeechLanguage.entries.firstOrNull { it.code == preferences.getString("language", "en") } ?: SpeechLanguage.ENGLISH
        set(value) { preferences.edit().putString("language", value.code).apply() }
}
