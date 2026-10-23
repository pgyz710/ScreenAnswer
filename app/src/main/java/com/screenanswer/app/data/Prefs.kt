package com.screenanswer.app.data

import android.content.Context

/**
 * Small wrapper around SharedPreferences. Everything stays on-device; the API key is only
 * sent to the configured DeepSeek-compatible endpoint as a Bearer token.
 */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString(KEY_API_KEY, "").orEmpty()
        set(value) {
            sp.edit().putString(KEY_API_KEY, value.trim()).apply()
        }

    var baseUrl: String
        get() {
            val value = sp.getString(KEY_BASE_URL, DEFAULT_BASE_URL).orEmpty().trim()
            return value.ifEmpty { DEFAULT_BASE_URL }
        }
        set(value) {
            val normalized = value.trim().ifEmpty { DEFAULT_BASE_URL }
            sp.edit().putString(KEY_BASE_URL, normalized).apply()
        }

    var model: String
        get() = if (sp.getString(KEY_MODEL, MODEL_CHAT) == MODEL_REASONER) {
            MODEL_REASONER
        } else {
            MODEL_CHAT
        }
        set(value) {
            sp.edit().putString(KEY_MODEL, if (value == MODEL_REASONER) MODEL_REASONER else MODEL_CHAT)
                .apply()
        }

    /** English mode: Latin OCR model, English-ish prompt contract and English speech input. */
    var englishMode: Boolean
        get() = sp.getBoolean(KEY_ENGLISH_MODE, false)
        set(value) {
            sp.edit().putBoolean(KEY_ENGLISH_MODE, value).apply()
        }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val MODEL_CHAT = "deepseek-chat"
        const val MODEL_REASONER = "deepseek-reasoner"

        private const val PREFS_NAME = "screen_answer_prefs"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL = "model"
        private const val KEY_ENGLISH_MODE = "english_mode"
    }
}
