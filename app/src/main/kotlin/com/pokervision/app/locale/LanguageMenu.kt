package com.pokervision.app.locale

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.os.LocaleListCompat
import com.pokervision.app.R

/** Per-app language: follow system, English, or Persian (RTL). Applied via AppCompat, persisted by the system. */
enum class AppLanguage(val tag: String) { SYSTEM(""), ENGLISH("en"), PERSIAN("fa") }

object LanguageController {
    fun current(): AppLanguage {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return AppLanguage.SYSTEM
        return when (locales[0]?.language) {
            "fa" -> AppLanguage.PERSIAN
            "en" -> AppLanguage.ENGLISH
            else -> AppLanguage.SYSTEM
        }
    }

    fun apply(language: AppLanguage) {
        val list = if (language == AppLanguage.SYSTEM) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(language.tag)
        }
        // Recreates the activity; Compose layout direction follows the new locale automatically.
        AppCompatDelegate.setApplicationLocales(list)
    }
}

@Composable
fun LanguageMenu() {
    var expanded by remember { mutableStateOf(false) }
    val current = remember { LanguageController.current() }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Outlined.Translate, contentDescription = stringResource(R.string.language_menu))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(
                AppLanguage.SYSTEM to R.string.language_system,
                AppLanguage.ENGLISH to R.string.language_english,
                AppLanguage.PERSIAN to R.string.language_persian,
            ).forEach { (lang, label) ->
                DropdownMenuItem(
                    text = { Text((if (lang == current) "✓ " else "") + stringResource(label)) },
                    onClick = {
                        expanded = false
                        if (lang != current) LanguageController.apply(lang)
                    },
                )
            }
        }
    }
}
