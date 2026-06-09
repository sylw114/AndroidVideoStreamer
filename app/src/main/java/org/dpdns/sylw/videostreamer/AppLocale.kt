package org.dpdns.sylw.videostreamer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.util.Locale

const val LANGUAGE_ZH = "zh"
const val LANGUAGE_EN = "en"

data class AppLanguageOption(val code: String, val labelResId: Int)

val APP_LANGUAGE_OPTIONS = listOf(
    AppLanguageOption(LANGUAGE_ZH, R.string.language_chinese),
    AppLanguageOption(LANGUAGE_EN, R.string.language_english)
)

private val PREF_APP_LANGUAGE = stringPreferencesKey("app_language")

fun resolveDefaultAppLanguage(): String {
    return if (Locale.getDefault().language.equals(LANGUAGE_ZH, ignoreCase = true)) {
        LANGUAGE_ZH
    } else {
        LANGUAGE_EN
    }
}

fun normalizeAppLanguage(language: String?): String {
    return when (language) {
        LANGUAGE_ZH -> LANGUAGE_ZH
        LANGUAGE_EN -> LANGUAGE_EN
        else -> resolveDefaultAppLanguage()
    }
}

suspend fun saveAppLanguage(context: Context, language: String) {
    context.dataStore.edit { preferences ->
        preferences[PREF_APP_LANGUAGE] = normalizeAppLanguage(language)
    }
}

suspend fun loadAppLanguage(context: Context): String {
    return context.dataStore.data
        .map { preferences -> normalizeAppLanguage(preferences[PREF_APP_LANGUAGE]) }
        .first()
}

private fun Context.createAppLanguageConfigurationContext(language: String): Context {
    val locale = when (normalizeAppLanguage(language)) {
        LANGUAGE_EN -> Locale.ENGLISH
        else -> Locale.SIMPLIFIED_CHINESE
    }
    val configuration = Configuration(resources.configuration)
    configuration.setLocales(LocaleList(locale))
    configuration.setLayoutDirection(locale)
    return createConfigurationContext(configuration)
}

private class AppLanguageContext(
    base: Context,
    language: String
) : ContextWrapper(base) {
    private val localizedContext = base.createAppLanguageConfigurationContext(language)

    override fun getAssets(): AssetManager = localizedContext.assets

    override fun getResources(): Resources = localizedContext.resources
}

fun Context.withAppLanguage(language: String): Context = AppLanguageContext(this, language)

tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

fun Context.localizedForCurrentAppLanguage(): Context {
    val language = StreamConfig.getAppLanguage() ?: runCatching {
        runBlocking { loadAppLanguage(applicationContext) }
    }.getOrElse {
        resolveDefaultAppLanguage()
    }
    StreamConfig.setAppLanguage(language)
    return withAppLanguage(language)
}

@Composable
fun AppLocaleProvider(language: String, content: @Composable () -> Unit) {
    val baseContext = LocalContext.current
    val localizedContext = remember(baseContext, language) {
        baseContext.withAppLanguage(language)
    }
    CompositionLocalProvider(LocalContext provides localizedContext) {
        content()
    }
}
