package com.abfilepro.app.ui.i18n

import androidx.compose.runtime.compositionLocalOf

enum class AppLanguage(val code: String, val nativeNameKey: String) {
    ARABIC("ar", "b2_language_name_arabic"),
    ENGLISH("en", "b2_language_name_english"),
    FRENCH("fr", "b2_language_name_french");

    companion object {
        fun fromCode(code: String?): AppLanguage = entries.firstOrNull { it.code == code } ?: ARABIC
    }
}

val LocalAppLanguage = compositionLocalOf { AppLanguage.ARABIC }

