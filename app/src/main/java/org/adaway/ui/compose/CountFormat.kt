package org.adaway.ui.compose

import android.icu.text.CompactDecimalFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import java.text.NumberFormat
import java.util.Locale

/**
 * Write a count in full, grouped the way the user's language does it: 2,312,345 in English,
 * 2 312 345 in Russian.
 */
@Composable
@ReadOnlyComposable
fun formatFullCount(count: Int): String {
    return NumberFormat.getIntegerInstance(currentLocale()).format(count)
}

/**
 * Write a count in the short form the user's language uses, such as 1.2M in English or 1,2 млн in
 * Russian, for places too narrow for the full number.
 */
@Composable
@ReadOnlyComposable
fun formatCompactCount(count: Int): String {
    return CompactDecimalFormat.getInstance(currentLocale(), CompactDecimalFormat.CompactStyle.SHORT)
        .format(count)
}

@Composable
@ReadOnlyComposable
private fun currentLocale(): Locale {
    return LocalConfiguration.current.locales[0] ?: Locale.getDefault()
}
