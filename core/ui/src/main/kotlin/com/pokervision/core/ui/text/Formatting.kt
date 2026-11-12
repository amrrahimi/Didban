package com.pokervision.core.ui.text

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.pokervision.core.common.result.AppError
import com.pokervision.core.model.game.PokerVariant
import com.pokervision.core.ui.R
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@StringRes
fun PokerVariant.labelRes(): Int = when (this) {
    PokerVariant.TEXAS_HOLDEM -> R.string.variant_texas_holdem
    PokerVariant.OMAHA -> R.string.variant_omaha
    PokerVariant.OMAHA_HI_LO -> R.string.variant_omaha_hi_lo
    PokerVariant.SEVEN_CARD_STUD -> R.string.variant_seven_card_stud
    PokerVariant.FIVE_CARD_DRAW -> R.string.variant_five_card_draw
    PokerVariant.SHORT_DECK -> R.string.variant_short_deck
}

@Composable
fun PokerVariant.label(): String = stringResource(labelRes())

@Composable
fun AppError.userMessage(): String = stringResource(
    when (this) {
        is AppError.Io -> R.string.error_io
        is AppError.NotFound -> R.string.error_not_found
        is AppError.Validation -> R.string.error_validation
        is AppError.PermissionDenied -> R.string.error_permission
        is AppError.Unsupported -> R.string.error_unsupported
        is AppError.Unknown -> R.string.error_unknown
    },
)

/** Human-readable size using locale-aware number formatting (Persian digits in fa locale). */
fun formatBytes(bytes: Long, locale: Locale = Locale.getDefault()): String {
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) String.format(locale, "%d %s", bytes, units[0])
    else String.format(locale, "%.1f %s", value, units[unit])
}

fun formatDuration(millis: Long, locale: Locale = Locale.getDefault()): String {
    val totalSeconds = millis / 1000
    return String.format(locale, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}

fun formatDateTime(epochMillis: Long, locale: Locale = Locale.getDefault()): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(epochMillis))
