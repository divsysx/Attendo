package com.attendo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.attendo.core.engine.DayMark

/**
 * The colours for the seven [DayMark] bands, resolved for the current light/dark mode.
 *
 * These live outside [MaterialTheme.colorScheme] because they carry meaning the scheme
 * has no slot for: "amber" here is not an accent, it is *partially attended*. Passing
 * them through a composition local keeps every screen — calendar cell, history row,
 * dashboard chip — drawing the same band from one definition.
 */
@Immutable
data class BandColors(
    val full: Color,
    val onFull: Color,
    val partial: Color,
    val onPartial: Color,
    val absent: Color,
    val onAbsent: Color,
    val pending: Color,
    val onPending: Color,
    val cancelled: Color,
    val onCancelled: Color,
    val notCounted: Color,
    val onNotCounted: Color,
    val none: Color,
    val onNone: Color,
) {
    /** Fill colour for a day in [mark]'s band. */
    fun containerFor(mark: DayMark): Color = when (mark) {
        DayMark.FULL -> full
        DayMark.PARTIAL -> partial
        DayMark.ABSENT -> absent
        DayMark.AWAITING_REVIEW -> pending
        DayMark.ALL_CANCELLED -> cancelled
        DayMark.NOT_COUNTED -> notCounted
        DayMark.NO_CLASS -> none
    }

    /** Text colour to use on top of [containerFor]. */
    fun contentFor(mark: DayMark): Color = when (mark) {
        DayMark.FULL -> onFull
        DayMark.PARTIAL -> onPartial
        DayMark.ABSENT -> onAbsent
        DayMark.AWAITING_REVIEW -> onPending
        DayMark.ALL_CANCELLED -> onCancelled
        DayMark.NOT_COUNTED -> onNotCounted
        DayMark.NO_CLASS -> onNone
    }
}

val LocalBandColors: ProvidableCompositionLocal<BandColors> =
    staticCompositionLocalOf { error("BandColors requested outside AttendoTheme") }

/** Shorthand for `LocalBandColors.current`. */
val bands: BandColors
    @Composable get() = LocalBandColors.current

private val LightScheme = lightColorScheme(
    primary = Plum40,
    onPrimary = Color.White,
    primaryContainer = Plum90,
    onPrimaryContainer = Plum10,
    secondary = Clay40,
    onSecondary = Color.White,
    secondaryContainer = Clay90,
    onSecondaryContainer = Clay20,
    tertiary = Ochre40,
    onTertiary = Color.White,
    tertiaryContainer = Ochre90,
    onTertiaryContainer = Ochre20,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightBackground,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = ErrorLight,
    onError = Color.White,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
)

private val DarkScheme = darkColorScheme(
    primary = Plum80,
    onPrimary = Plum20,
    primaryContainer = Plum30,
    onPrimaryContainer = Plum90,
    secondary = Clay80,
    onSecondary = Clay20,
    secondaryContainer = Clay30,
    onSecondaryContainer = Clay90,
    tertiary = Ochre80,
    onTertiary = Ochre20,
    tertiaryContainer = Ochre30,
    onTertiaryContainer = Ochre90,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkBackground,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = ErrorDark,
    onError = Color(0xFF690005),
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
)

private val LightBands = BandColors(
    full = FullContainerLight,
    onFull = FullLight,
    partial = PartialContainerLight,
    onPartial = PartialLight,
    absent = AbsentContainerLight,
    onAbsent = AbsentLight,
    pending = PendingContainerLight,
    onPending = PendingLight,
    cancelled = LightSurfaceVariant,
    onCancelled = LightOnSurfaceVariant,
    notCounted = NotCountedContainerLight,
    onNotCounted = NotCountedLight,
    none = Color.Transparent,
    onNone = LightOnSurfaceVariant,
)

private val DarkBands = BandColors(
    full = FullContainerDark,
    onFull = FullDark,
    partial = PartialContainerDark,
    onPartial = PartialDark,
    absent = AbsentContainerDark,
    onAbsent = AbsentDark,
    pending = PendingContainerDark,
    onPending = PendingDark,
    cancelled = DarkSurfaceVariant,
    onCancelled = DarkOnSurfaceVariant,
    notCounted = NotCountedContainerDark,
    onNotCounted = NotCountedDark,
    none = Color.Transparent,
    onNone = DarkOnSurfaceVariant,
)

@Composable
fun AttendoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalBandColors provides if (darkTheme) DarkBands else LightBands) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = AttendoTypography,
            content = content,
        )
    }
}
