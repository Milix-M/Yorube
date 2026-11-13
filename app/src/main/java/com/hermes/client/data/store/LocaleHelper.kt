package com.hermes.client.data.store

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.ContextThemeWrapper
import java.util.Locale

/**
 * Dependency-free per-app locale: wraps a [Context] with the user's language
 * choice instead of pulling in AppCompat. "system" (or anything unknown)
 * returns the base context untouched. "zh" maps to Simplified Chinese.
 */
object LocaleHelper {

    fun localeFor(language: String): Locale? = when (language) {
        LANGUAGE_JA -> Locale.JAPANESE
        LANGUAGE_EN -> Locale.ENGLISH
        LANGUAGE_ZH -> Locale.SIMPLIFIED_CHINESE
        LANGUAGE_KO -> Locale.KOREAN
        else -> null
    }

    fun contextualize(base: Context, language: String): Context {
        val locale = localeFor(language) ?: return base
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        // API 24+ keeps the full locale list; older versions only need the primary.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(android.os.LocaleList(locale))
        }
        // Keep the Activity in the ContextWrapper chain so Compose can still
        // find its ActivityResultRegistryOwner when launching the image picker.
        return ContextThemeWrapper(base, base.theme).apply {
            applyOverrideConfiguration(config)
        }
    }

    /** Resolves a string with the user's language, mirroring resource lookup. */
    fun getString(context: Context, language: String, id: Int, vararg args: Any): String =
        contextualize(context, language).getString(id, *args)
}
