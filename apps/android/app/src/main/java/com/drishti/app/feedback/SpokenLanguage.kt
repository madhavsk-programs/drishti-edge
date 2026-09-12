package com.drishti.app.feedback

import java.util.Locale

/** Languages DRISHTI can use for its spoken guidance and localized responses. */
enum class SpokenLanguage(
    val tag: String,
    val locale: Locale,
    val displayName: String,
    /** System-level instruction for the small Scene VLM; never inferred from the question. */
    val sceneAnswerInstruction: String,
) {
    ENGLISH(
        "en",
        Locale.ENGLISH,
        "English",
        "Answer only in natural English. Do not switch to another language.",
    ),
    HINDI(
        "hi-IN",
        Locale.forLanguageTag("hi-IN"),
        "हिन्दी",
        "Answer only in natural Hindi using Devanagari script. Do not answer in English.",
    ),
    TAMIL(
        "ta-IN",
        Locale.forLanguageTag("ta-IN"),
        "தமிழ்",
        // Production rejects Tamil Scene requests before loading the VLM. Keep
        // this explicit fallback non-generative in case a future caller bypasses it.
        "Tamil Scene description is unsupported. Do not generate an answer.",
    );

    companion object {
        fun fromTag(tag: String?): SpokenLanguage {
            val language = tag?.substringBefore('-')
            return entries.firstOrNull {
                it.tag.equals(tag, ignoreCase = true) ||
                    it.tag.substringBefore('-').equals(language, ignoreCase = true)
            } ?: ENGLISH
        }
    }
}
